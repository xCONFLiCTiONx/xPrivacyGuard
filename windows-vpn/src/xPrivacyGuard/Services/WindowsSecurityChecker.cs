using System.Net;
using System.Net.Http;
using System.Security.Principal;
using System.Text;
using System.Text.Json;
using Microsoft.Win32;
using xPrivacyGuard.Models;

namespace xPrivacyGuard.Services;

public static class WindowsSecurityChecker
{
    private static readonly HttpClient Http = new()
    {
        Timeout = TimeSpan.FromSeconds(8)
    };

    public static bool IsAdministrator()
    {
        try
        {
            using var identity = WindowsIdentity.GetCurrent();
            var principal = new WindowsPrincipal(identity);
            return principal.IsInRole(WindowsBuiltInRole.Administrator);
        }
        catch
        {
            return false;
        }
    }

    public static string CleanDomain(string raw)
    {
        var s = raw.Trim().ToLowerInvariant();
        if (s.StartsWith("https://", StringComparison.Ordinal)) s = s[8..];
        if (s.StartsWith("http://", StringComparison.Ordinal)) s = s[7..];
        return s.TrimEnd('/');
    }

    public static bool IsValidDomain(string raw)
    {
        var clean = CleanDomain(raw);
        if (clean.Length == 0 || clean.Contains(':') || clean.Contains('/')) return false;
        if (IPAddress.TryParse(clean, out _)) return false;
        return clean.Contains('.') && clean.All(c => char.IsLetterOrDigit(c) || c is '.' or '-');
    }

    public static WindowsSecurityReport CollectReport(bool vpnConnected, long blockedCount, long allowedCount)
    {
        return new WindowsSecurityReport
        {
            MachineName = Environment.MachineName,
            OsVersion = Environment.OSVersion.VersionString,
            IsAdministrator = IsAdministrator(),
            DefenderEnabled = ReadRegistryDword(Registry.LocalMachine, @"SOFTWARE\Microsoft\Windows Defender\Real-Time Protection", "DisableRealtimeMonitoring", 0) == 0,
            FirewallEnabled = ReadRegistryDword(Registry.LocalMachine, @"SYSTEM\CurrentControlSet\Services\SharedAccess\Parameters\FirewallPolicy\StandardProfile", "EnableFirewall", 0) == 1,
            SecureBootEnabled = ReadRegistryDword(Registry.LocalMachine, @"SYSTEM\CurrentControlSet\Control\SecureBoot\State", "UEFISecureBootEnabled", 0) == 1,
            UacEnabled = ReadRegistryDword(Registry.LocalMachine, @"SOFTWARE\Microsoft\Windows\CurrentVersion\Policies\System", "EnableLUA", 0) == 1,
            VpnConnected = vpnConnected,
            BlockedCount = blockedCount,
            AllowedCount = allowedCount
        };
    }

    public static async Task<XSecuritySyncResult> SyncReportToXServerAsync(
        string domain,
        string apiKey,
        WindowsSecurityReport report,
        CancellationToken ct = default)
    {
        var host = CleanDomain(domain);
        if (!IsValidDomain(host))
            return new XSecuritySyncResult(false, "Enter a valid xSecurity domain name (e.g. security.example.com).");

        var key = apiKey.Trim();
        if (key.Length == 0)
            return new XSecuritySyncResult(false, "Enter an xServer API key first.");

        var payload = JsonSerializer.Serialize(new
        {
            platform = "windows",
            hostname = report.MachineName,
            machineName = report.MachineName,
            osVersion = report.OsVersion,
            isAdministrator = report.IsAdministrator,
            defenderEnabled = report.DefenderEnabled,
            firewallEnabled = report.FirewallEnabled,
            secureBootEnabled = report.SecureBootEnabled,
            uacEnabled = report.UacEnabled,
            vpnConnected = report.VpnConnected,
            blockedCount = report.BlockedCount,
            allowedCount = report.AllowedCount
        });

        var candidates = new List<string> { host };
        if (host.StartsWith("xserver.", StringComparison.OrdinalIgnoreCase))
            candidates.Add("security." + host["xserver.".Length..]);

        string? lastError = null;
        foreach (var targetHost in candidates)
        {
            try
            {
                using var req = new HttpRequestMessage(HttpMethod.Post, $"https://{targetHost}/api/agent/windows/report")
                {
                    Content = new StringContent(payload, Encoding.UTF8, "application/json")
                };
                req.Headers.Add("X-API-Key", key);

                using var resp = await Http.SendAsync(req, ct).ConfigureAwait(false);
                var body = await resp.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
                if (resp.StatusCode == HttpStatusCode.NotFound && targetHost != candidates[^1])
                {
                    lastError = $"HTTP 404 on {targetHost}";
                    continue;
                }
                if (!resp.IsSuccessStatusCode)
                {
                    var concise = body.TrimStart().StartsWith('<')
                        ? $"HTTP {(int)resp.StatusCode} ({resp.ReasonPhrase}) on https://{targetHost}/api/agent/windows/report. Use your xSecurity subdomain (e.g. security.example.com)."
                        : $"HTTP {(int)resp.StatusCode}: {body}";
                    return new XSecuritySyncResult(false, concise);
                }

                using var doc = JsonDocument.Parse(string.IsNullOrWhiteSpace(body) ? "{}" : body);
                var root = doc.RootElement;
                int? score = root.TryGetProperty("postureScore", out var sc) && sc.TryGetInt32(out var s) ? s : null;
                string? status = root.TryGetProperty("postureStatus", out var st) ? st.GetString() : null;
                int? alerts = root.TryGetProperty("openAlerts", out var al) && al.TryGetInt32(out var a) ? a : null;
                return new XSecuritySyncResult(true, $"Synced to {targetHost}", score, status, alerts);
            }
            catch (Exception ex)
            {
                lastError = $"Sync to {targetHost} failed: {ex.Message}";
            }
        }

        return new XSecuritySyncResult(false, lastError ?? "Sync failed.");
    }

    private static int ReadRegistryDword(RegistryKey root, string subKey, string valueName, int defaultValue)
    {
        try
        {
            using var key = root.OpenSubKey(subKey, writable: false);
            var val = key?.GetValue(valueName);
            return val is int i ? i : defaultValue;
        }
        catch
        {
            return defaultValue;
        }
    }
}
