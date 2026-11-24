using System.IO;
using System.Runtime.InteropServices;
using System.Text.Json;
using xPrivacyGuard.Models;

namespace xPrivacyGuard.Native;

internal static partial class AetherIdentityMethods
{
    private const string Library = "aether.dll";

    [LibraryImport(Library, EntryPoint = "aether_identity_open", StringMarshalling = StringMarshalling.Utf8)]
    internal static partial nint IdentityOpen(string payload);

    [LibraryImport(Library, EntryPoint = "aether_identity_free")]
    internal static partial nint IdentityFree(ulong identity);

    [LibraryImport(Library, EntryPoint = "aether_team_code_request", StringMarshalling = StringMarshalling.Utf8)]
    internal static partial nint TeamCodeRequest(string payload);

    [LibraryImport(Library, EntryPoint = "aether_team_code_submit", StringMarshalling = StringMarshalling.Utf8)]
    internal static partial nint TeamCodeSubmit(ulong session, string code);

    [LibraryImport(Library, EntryPoint = "aether_team_code_resend")]
    internal static partial nint TeamCodeResend(ulong session);

    [LibraryImport(Library, EntryPoint = "aether_team_session_free")]
    internal static partial nint TeamSessionFree(ulong session);

    [LibraryImport(Library, EntryPoint = "aether_team_token_set", StringMarshalling = StringMarshalling.Utf8)]
    internal static partial nint TeamTokenSet(string token);

    [LibraryImport(Library, EntryPoint = "aether_team_token_clear")]
    internal static partial nint TeamTokenClear();

    [LibraryImport(Library, EntryPoint = "aether_verify_start", StringMarshalling = StringMarshalling.Utf8)]
    internal static partial nint VerifyStart(ulong identity, string payload);

    [LibraryImport(Library, EntryPoint = "aether_scan_start", StringMarshalling = StringMarshalling.Utf8)]
    internal static partial nint ScanStart(ulong identity, string payload);
}

public sealed record AetherIdentitySummary(
    string DeviceId,
    string Ipv4,
    string Ipv6,
    string Organization,
    string AssignedEndpoint,
    bool HasMasqueCert,
    long CertIssuedAt,
    bool CertUsable);

public sealed record AetherIdentityInfo(
    ulong Handle,
    AetherIdentitySummary Summary,
    string? Path);

public sealed class AetherIdentity : IAsyncDisposable
{
    private ulong _handle;
    private int _disposed;

    private AetherIdentity(ulong handle, AetherIdentitySummary summary, string? path)
    {
        _handle = handle;
        Info = new AetherIdentityInfo(handle, summary, path);
    }

    public AetherIdentityInfo Info { get; }
    public ulong Handle => Volatile.Read(ref _handle);

    public static void SetCachedTeamToken(string token)
    {
        if (string.IsNullOrWhiteSpace(token)) return;
        using var reply = NativeReply.From(AetherIdentityMethods.TeamTokenSet(token.Trim()));
    }

    public static void ClearCachedTeamToken()
    {
        using var reply = NativeReply.From(AetherIdentityMethods.TeamTokenClear());
    }

    public static async Task<AetherIdentity> OpenAsync(
        string identityPath,
        CloudflareOneConfig? config = null,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(identityPath);

        if (config?.HasValidToken == true)
        {
            try { SetCachedTeamToken(config.AccessToken); } catch { /* Best-effort cache seed. */ }
        }

        Dictionary<string, object?>? teamObj = null;
        var team = config?.NormalizedTeam ?? "";
        if (!string.IsNullOrEmpty(team))
        {
            teamObj = new Dictionary<string, object?> { ["team"] = team };
            if (config!.HasValidToken) teamObj["token"] = config.AccessToken.Trim();
            if (config.HasServiceToken)
            {
                teamObj["client_id"] = config.ServiceClientId.Trim();
                teamObj["client_secret"] = config.ServiceClientSecret.Trim();
            }
            if (!string.IsNullOrWhiteSpace(config.UserEmail))
                teamObj["email"] = config.UserEmail.Trim();
        }

        var payloadObj = new Dictionary<string, object?>
        {
            ["path"] = identityPath,
            ["transport"] = "masque",
            ["model"] = "Windows",
            ["locale"] = "en_US"
        };
        if (teamObj is not null) payloadObj["team"] = teamObj;

        var payload = JsonSerializer.Serialize(payloadObj);
        using var started = NativeReply.From(AetherIdentityMethods.IdentityOpen(payload));
        var job = started.Root.GetProperty("job").GetUInt64();
        var openTimeout = File.Exists(identityPath) ? TimeSpan.FromSeconds(30) : TimeSpan.FromMinutes(2);
        var result = await AetherJob.WaitAsync(job, openTimeout, cancellationToken).ConfigureAwait(false);
        var handle = result.GetProperty("identity").GetUInt64();
        var path = result.TryGetProperty("path", out var p) ? p.GetString() : identityPath;

        var summary = ParseSummary(result, team);
        return new AetherIdentity(handle, summary, path);
    }

    public async Task<bool> VerifyPeerAsync(string peer, bool enableEch, TimeSpan timeout, CancellationToken cancellationToken)
    {
        ObjectDisposedException.ThrowIf(Volatile.Read(ref _disposed) != 0, this);
        var payload = JsonSerializer.Serialize(new
        {
            peer,
            transport = "masque",
            profile = "off",
            ech = enableEch
        });
        using var started = NativeReply.From(AetherIdentityMethods.VerifyStart(Handle, payload));
        var job = started.Root.GetProperty("job").GetUInt64();
        var result = await AetherJob.WaitAsync(job, timeout, cancellationToken).ConfigureAwait(false);
        return result.TryGetProperty("reachable", out var reachable) && reachable.ValueKind == JsonValueKind.True;
    }

    public async Task<string?> ScanEndpointAsync(bool enableEch, TimeSpan timeout, CancellationToken cancellationToken)
    {
        ObjectDisposedException.ThrowIf(Volatile.Read(ref _disposed) != 0, this);
        var payload = JsonSerializer.Serialize(new
        {
            transport = "masque",
            mode = "fast",
            ip = "v4",
            profile = "off",
            ports = new[] { 443, 4500, 500, 1701 },
            ech = enableEch
        });
        using var started = NativeReply.From(AetherIdentityMethods.ScanStart(Handle, payload));
        var job = started.Root.GetProperty("job").GetUInt64();
        var result = await AetherJob.WaitAsync(job, timeout, cancellationToken).ConfigureAwait(false);

        if (result.TryGetProperty("endpoint", out var ep))
        {
            if (ep.ValueKind == JsonValueKind.Object)
            {
                var ip = ep.TryGetProperty("ip", out var ipEl) ? ipEl.GetString()?.Trim() : null;
                var port = ep.TryGetProperty("port", out var portEl) ? portEl.GetInt32() : 443;
                if (!string.IsNullOrEmpty(ip))
                    return ip.Contains(':') && !ip.StartsWith('[') ? $"[{ip}]:{port}" : $"{ip}:{port}";
            }
            else if (ep.ValueKind == JsonValueKind.String)
            {
                var raw = ep.GetString()?.Trim();
                if (!string.IsNullOrEmpty(raw)) return raw;
            }
        }
        return null;
    }

    private static AetherIdentitySummary ParseSummary(JsonElement result, string fallbackTeam)
    {
        if (!result.TryGetProperty("summary", out var s) || s.ValueKind != JsonValueKind.Object)
            return new AetherIdentitySummary("", "172.16.0.2", "", fallbackTeam, "", false, 0, false);

        return new AetherIdentitySummary(
            DeviceId: GetString(s, "device_id", ""),
            Ipv4: GetString(s, "ipv4", "172.16.0.2"),
            Ipv6: GetString(s, "ipv6", ""),
            Organization: GetString(s, "organization", fallbackTeam),
            AssignedEndpoint: GetString(s, "assigned_endpoint", ""),
            HasMasqueCert: GetBool(s, "has_masque_cert"),
            CertIssuedAt: GetLong(s, "cert_issued_at"),
            CertUsable: GetBool(s, "cert_usable"));
    }

    private static string GetString(JsonElement obj, string prop, string fallback)
        => obj.TryGetProperty(prop, out var el) && el.ValueKind == JsonValueKind.String ? el.GetString() ?? fallback : fallback;

    private static bool GetBool(JsonElement obj, string prop)
        => obj.TryGetProperty(prop, out var el) && el.ValueKind == JsonValueKind.True;

    private static long GetLong(JsonElement obj, string prop)
        => obj.TryGetProperty(prop, out var el) && el.ValueKind == JsonValueKind.Number && el.TryGetInt64(out var val) ? val : 0L;

    public ValueTask DisposeAsync()
    {
        if (Interlocked.Exchange(ref _disposed, 1) == 0)
        {
            var handle = Interlocked.Exchange(ref _handle, 0);
            if (handle != 0)
            {
                try { using var reply = NativeReply.From(AetherIdentityMethods.IdentityFree(handle)); }
                catch { /* Best-effort native cleanup. */ }
            }
        }
        return ValueTask.CompletedTask;
    }
}

public sealed class AetherEmailSignIn : IAsyncDisposable
{
    private ulong _session;
    private int _disposed;

    private AetherEmailSignIn(ulong session, string email)
    {
        _session = session;
        Email = email;
    }

    public string Email { get; }
    public string? LastToken { get; private set; }

    public static async Task<AetherEmailSignIn> RequestCodeAsync(
        string team,
        string email,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(team);
        ArgumentException.ThrowIfNullOrWhiteSpace(email);
        var payload = JsonSerializer.Serialize(new { team, email });
        using var started = NativeReply.From(AetherIdentityMethods.TeamCodeRequest(payload));
        var job = started.Root.GetProperty("job").GetUInt64();
        var result = await AetherJob.WaitAsync(job, TimeSpan.FromMinutes(2), cancellationToken).ConfigureAwait(false);
        return new AetherEmailSignIn(
            result.GetProperty("session").GetUInt64(),
            result.TryGetProperty("email", out var e) ? e.GetString() ?? email : email);
    }

    public async Task<string?> SubmitCodeAsync(string code, CancellationToken cancellationToken = default)
    {
        ObjectDisposedException.ThrowIf(Volatile.Read(ref _disposed) != 0, this);
        ArgumentException.ThrowIfNullOrWhiteSpace(code);
        using var started = NativeReply.From(AetherIdentityMethods.TeamCodeSubmit(_session, code.Trim()));
        var job = started.Root.GetProperty("job").GetUInt64();
        var result = await AetherJob.WaitAsync(job, TimeSpan.FromMinutes(2), cancellationToken).ConfigureAwait(false);
        var token = result.TryGetProperty("token", out var tokenEl) ? tokenEl.GetString() : null;
        if (string.IsNullOrWhiteSpace(token))
            return null;
        LastToken = token;
        using var stored = NativeReply.From(AetherIdentityMethods.TeamTokenSet(token));
        return token;
    }

    public async Task ResendCodeAsync(CancellationToken cancellationToken = default)
    {
        ObjectDisposedException.ThrowIf(Volatile.Read(ref _disposed) != 0, this);
        using var started = NativeReply.From(AetherIdentityMethods.TeamCodeResend(_session));
        var job = started.Root.GetProperty("job").GetUInt64();
        _ = await AetherJob.WaitAsync(job, TimeSpan.FromMinutes(2), cancellationToken).ConfigureAwait(false);
    }

    public ValueTask DisposeAsync()
    {
        if (Interlocked.Exchange(ref _disposed, 1) == 0)
        {
            var session = Interlocked.Exchange(ref _session, 0);
            if (session != 0)
            {
                try { using var reply = NativeReply.From(AetherIdentityMethods.TeamSessionFree(session)); }
                catch { /* Best-effort cleanup. */ }
            }
        }
        return ValueTask.CompletedTask;
    }
}

internal static class AetherJob
{
    public static async Task<JsonElement> WaitAsync(
        ulong job,
        TimeSpan timeout,
        CancellationToken cancellationToken)
    {
        try
        {
            var deadline = DateTimeOffset.UtcNow + timeout;
            while (DateTimeOffset.UtcNow < deadline)
            {
                cancellationToken.ThrowIfCancellationRequested();
                await Task.Delay(150, cancellationToken).ConfigureAwait(false);
                using var poll = NativeReply.From(AetherMethods.JobPoll(job));
                var state = poll.Root.GetProperty("state").GetString();
                if (state == "running") continue;
                if (state != "done")
                    throw new AetherNativeException($"Unexpected Aether job state '{state}'.");
                var result = poll.Root.GetProperty("result");
                if (result.TryGetProperty("ok", out var ok) && ok.ValueKind == JsonValueKind.False)
                {
                    var message = result.TryGetProperty("error", out var error) ? error.GetString() : null;
                    throw new AetherNativeException(message ?? "Aether operation failed.");
                }
                return result.Clone();
            }
            throw new TimeoutException($"Aether job {job} exceeded {timeout.TotalSeconds:F0} seconds.");
        }
        catch
        {
            try { using var cancelled = NativeReply.From(AetherMethods.JobCancel(job)); }
            catch { /* Preserve original failure. */ }
            throw;
        }
        finally
        {
            try { using var freed = NativeReply.From(AetherMethods.JobFree(job)); }
            catch { /* Best-effort native job cleanup. */ }
        }
    }
}
