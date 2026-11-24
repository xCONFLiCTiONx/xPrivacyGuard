using System.IO;
using System.Text;
using System.Text.Json.Serialization;
using System.Text.RegularExpressions;

namespace xPrivacyGuard.Models;

public sealed class CloudflareOneConfig
{
    public string TeamName { get; set; } = "";
    public string UserEmail { get; set; } = "";
    public string AccessToken { get; set; } = "";
    public string ServiceClientId { get; set; } = "";
    public string ServiceClientSecret { get; set; } = "";
    public string CustomPeerEndpoint { get; set; } = "";
    public bool PreferHttp2 { get; set; }
    public bool EnableEch { get; set; }
    public string DeviceId { get; set; } = "";
    public string Organization { get; set; } = "";
    public string Ipv4Address { get; set; } = "";
    public string Ipv6Address { get; set; } = "";
    public string AssignedEndpoint { get; set; } = "";
    public long CertIssuedAt { get; set; }

    [JsonIgnore]
    public string NormalizedTeam => NormalizeTeamName(TeamName);

    [JsonIgnore]
    public bool HasValidToken => !string.IsNullOrWhiteSpace(AccessToken) && !IsJwtExpired(AccessToken);

    [JsonIgnore]
    public bool HasServiceToken =>
        !string.IsNullOrWhiteSpace(ServiceClientId) && !string.IsNullOrWhiteSpace(ServiceClientSecret);

    [JsonIgnore]
    public bool IsEnrolled =>
        !string.IsNullOrWhiteSpace(DeviceId) && !string.IsNullOrWhiteSpace(Ipv4Address);

    [JsonIgnore]
    public bool CanConnect =>
        IsEnrolled || (!string.IsNullOrEmpty(NormalizedTeam) && (HasValidToken || HasServiceToken));

    [JsonIgnore]
    public string DisplayEndpoint
    {
        get
        {
            if (!string.IsNullOrWhiteSpace(CustomPeerEndpoint)) return CustomPeerEndpoint.Trim();
            if (!string.IsNullOrWhiteSpace(AssignedEndpoint)) return AssignedEndpoint.Trim();
            return "Cloudflare MASQUE (Auto)";
        }
    }

    [JsonIgnore]
    public string DisplayAddresses
    {
        get
        {
            var parts = new[] { Ipv4Address, Ipv6Address }
                .Select(s => s.Trim())
                .Where(s => s.Length > 0)
                .ToArray();
            return parts.Length > 0 ? string.Join(", ", parts) : "Assigned on MASQUE connect";
        }
    }

    public static string NormalizeTeamName(string raw)
    {
        if (string.IsNullOrWhiteSpace(raw)) return "";
        var s = raw.Trim().ToLowerInvariant();
        if (s.StartsWith("https://", StringComparison.Ordinal)) s = s[8..];
        if (s.StartsWith("http://", StringComparison.Ordinal)) s = s[7..];
        var slash = s.IndexOf('/');
        if (slash >= 0) s = s[..slash];
        const string suffix = ".cloudflareaccess.com";
        if (s.EndsWith(suffix, StringComparison.Ordinal)) s = s[..^suffix.Length];
        s = s.Trim('.');
        return s.Length > 0 && s.All(c => char.IsLetterOrDigit(c) || c == '-') ? s : "";
    }

    private static readonly Regex ExpClaimRegex = new("\"exp\"\\s*:\\s*(\\d+)", RegexOptions.Compiled);

    public static bool IsJwtExpired(string jwt, long? nowEpochSec = null)
    {
        var now = nowEpochSec ?? DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        var parts = jwt.Trim().Split('.');
        if (parts.Length != 3 || parts.Any(string.IsNullOrWhiteSpace)) return true;
        try
        {
            var payload = parts[1].Replace('-', '+').Replace('_', '/');
            var rem = payload.Length % 4;
            if (rem != 0) payload += new string('=', 4 - rem);
            var json = Encoding.UTF8.GetString(Convert.FromBase64String(payload));
            var match = ExpClaimRegex.Match(json);
            if (!match.Success) return false;
            if (!long.TryParse(match.Groups[1].Value, out var exp)) return true;
            return exp <= 0 || exp <= now;
        }
        catch
        {
            return true;
        }
    }
}

public sealed class ZeroTrustDnsConfig
{
    public string Ipv4Primary { get; set; } = "";
    public string Ipv4Secondary { get; set; } = "";
    public string Ipv6Endpoint { get; set; } = "";
    public string DotEndpoint { get; set; } = "";
    public string DohEndpoint { get; set; } = "";
    public string PrivateDomain { get; set; } = "";
    public string PrivateDnsServer { get; set; } = "";

    [JsonIgnore]
    public bool IsCustomConfigured =>
        !string.IsNullOrWhiteSpace(Ipv4Primary)
        || !string.IsNullOrWhiteSpace(Ipv4Secondary)
        || !string.IsNullOrWhiteSpace(Ipv6Endpoint)
        || !string.IsNullOrWhiteSpace(DotEndpoint)
        || !string.IsNullOrWhiteSpace(DohEndpoint);

    [JsonIgnore]
    public bool IsPrivateDnsConfigured =>
        !string.IsNullOrWhiteSpace(PrivateDomain) && !string.IsNullOrWhiteSpace(PrivateDnsServer);

    public IReadOnlyList<string> ConfiguredIpv4List() =>
        new[] { Ipv4Primary.Trim(), Ipv4Secondary.Trim() }
            .Where(s => s.Length > 0 && !s.Equals("--", StringComparison.OrdinalIgnoreCase))
            .ToArray();

    public IReadOnlyList<string> ConfiguredIpv6List() =>
        new[] { Ipv6Endpoint.Trim() }
            .Where(s => s.Length > 0 && !s.Equals("--", StringComparison.OrdinalIgnoreCase))
            .ToArray();

    public string? NormalizedDohUrl()
    {
        var raw = DohEndpoint.Trim();
        if (raw.Length == 0 || raw == "--") return null;
        if (raw.StartsWith("http://", StringComparison.OrdinalIgnoreCase)
            || raw.StartsWith("https://", StringComparison.OrdinalIgnoreCase))
            return raw;
        return $"https://{raw}/dns-query";
    }

    public bool MatchesPrivateDomain(string hostname)
    {
        var domain = PrivateDomain.Trim().Trim('.').ToLowerInvariant();
        var host = hostname.Trim().Trim('.').ToLowerInvariant();
        return domain.Length > 0 && (host == domain || host.EndsWith("." + domain, StringComparison.Ordinal));
    }

    public string ActiveModeSummary()
    {
        if (IsPrivateDnsConfigured) return $"Split DNS ({PrivateDomain.Trim()})";
        if (!IsCustomConfigured) return "System / Tunnel DNS";
        var parts = new List<string>();
        if (NormalizedDohUrl() is not null) parts.Add("DoH");
        if (!string.IsNullOrWhiteSpace(DotEndpoint)) parts.Add("DoT");
        var v4 = ConfiguredIpv4List();
        if (v4.Count > 0) parts.Add($"IPv4 ({string.Join(", ", v4)})");
        if (ConfiguredIpv6List().Count > 0) parts.Add("IPv6");
        return $"Zero Trust Location ({string.Join(" + ", parts)})";
    }
}

public sealed class SplitTunnelConfig
{
    public bool BypassNetflix { get; set; } = true;
    public bool BypassHulu { get; set; } = true;
    public bool BypassPrimeVideo { get; set; } = true;
    public bool BypassDisneyPlus { get; set; } = true;
    public bool BypassMax { get; set; } = true;
    public bool BypassAppleTv { get; set; } = true;
    public bool BypassAppleOne { get; set; } = true;
    public bool BypassPeacock { get; set; } = true;
    public bool BypassYouTube { get; set; } = true;
    public bool BypassYouTubeTv { get; set; } = true;
    public bool BypassSlingTv { get; set; } = true;
    public bool BypassPlutoTv { get; set; } = true;
    public bool BypassParamountPlus { get; set; } = true;
    public bool BypassEspnPlus { get; set; } = true;
    public bool BypassSpotify { get; set; } = true;
    public bool BypassTubi { get; set; } = true;
    public bool BypassGamingClients { get; set; } = true;
    public bool BypassPhoneLink { get; set; } = true;
    public List<string> CustomExcludedDomains { get; set; } = [];
    public List<string> CustomExcludedExecutables { get; set; } = [];

    private static readonly string[] PhoneLinkDomains =
    [
        "yourphone.microsoft.com",
        "wns.windows.com",
        "notify.windows.com",
        "cdp.microsoft.com",
        "activity.windows.com",
        "iris.microsoft.com",
        "pcs.microsoft.com",
        "communication.microsoft.com",
        "trouter.teams.microsoft.com",
        "trouter.skype.com",
        "trouter.io",
        "signalr.net"
    ];

    private static readonly string[] NetflixDomains =
        ["netflix.com", "nflxvideo.net", "nflximg.net", "nflximg.com", "nflxext.com", "nflxso.net", "fast.com"];

    private static readonly string[] HuluDomains =
        ["hulu.com", "huluim.com", "hulustream.com", "huluad.com", "huluqa.com"];

    private static readonly string[] PrimeVideoDomains =
        ["primevideo.com", "aiv-cdn.net", "aiv-delivery.net", "atv-ps.amazon.com", "atv-ext.amazon.com", "amazonvideo.com", "pv-cdn.net"];

    private static readonly string[] DisneyPlusDomains =
        ["disneyplus.com", "disney-plus.net", "dssott.com", "bamgrid.com", "disneystreaming.com", "cdn.registerdisney.go.com"];

    private static readonly string[] MaxDomains =
        ["max.com", "hbomax.com", "hbomaxcdn.com", "hbo.com", "warnermediacdn.com", "discomax.com"];

    private static readonly string[] AppleTvDomains =
        ["tv.apple.com", "uts-api.itunes.apple.com", "play-edge.itunes.apple.com", "play.itunes.apple.com", "hls.itunes.apple.com", "hls-amt.itunes.apple.com", "np-edge.itunes.apple.com", "mzstatic.com"];

    private static readonly string[] AppleOneDomains =
        ["music.apple.com", "amp-api.music.apple.com", "audio-ssl.itunes.apple.com", "aod-ssl.itunes.apple.com", "mvod.itunes.apple.com", "fitness.apple.com", "arcade.apple.com", "itunes.apple.com", "mzstatic.com"];

    private static readonly string[] SpotifyDomains =
        ["spotify.com", "scdn.co", "spotifycdn.com", "spoti.fi", "audio-ak-spotify-com.akamaized.net"];

    private static readonly string[] PeacockDomains =
        ["peacocktv.com", "nbcuni.com", "nbcudigitaladops.com", "peacock.com"];

    private static readonly string[] YouTubeDomains =
        ["youtube.com", "music.youtube.com", "googlevideo.com", "ytimg.com", "youtu.be", "youtubei.googleapis.com", "ggpht.com"];

    private static readonly string[] YouTubeTvDomains =
        ["tv.youtube.com", "googlevideo.com", "ytimg.com", "youtubei.googleapis.com", "ggpht.com"];

    private static readonly string[] SlingTvDomains =
        ["sling.com", "slingtv.com", "movenetworks.com", "movetv.com"];

    private static readonly string[] TubiDomains =
        ["tubitv.com", "tubi.tv", "tubi.io", "adrise.tv"];

    private static readonly string[] PlutoTvDomains =
        ["pluto.tv", "plutotv.net"];

    private static readonly string[] ParamountPlusDomains =
        ["paramountplus.com", "cbsi.com", "cbsivideo.com", "cbsaavideo.com", "cbsinteractive.com", "pplusstatic.com"];

    private static readonly string[] EspnPlusDomains =
        ["espn.com", "espn.go.com", "espncdn.com", "bamgrid.com", "dssott.com", "dtci.technology"];

    private static readonly string[] GamingDomains =
    [
        "steampowered.com", "steamcommunity.com", "steamcontent.com", "steamstatic.com", "steamgames.com",
        "steamserver.net", "steam-chat.com", "valvesoftware.com", "steamdeck.com", "protondb.com",
        "epicgames.com", "unrealengine.com", "ol.epicgames.com",
        "battle.net", "blizzard.com", "bnet.com",
        "riotgames.com", "leagueoflegends.com", "playvalorant.com",
        "ea.com", "origin.com", "ubisoft.com", "ubi.com", "ubisoftconnect.com", "uplay.com",
        "xboxlive.com", "xbox.com", "xboxservices.com", "gamepass.com", "msgamestudios.com",
        "gog.com", "gog-statics.com", "gogalaxy.com",
        "rockstargames.com", "bungie.net", "activision.com", "callofduty.com"
    ];

    public HashSet<string> GetEffectiveExcludedDomains()
    {
        var set = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        if (BypassNetflix) foreach (var d in NetflixDomains) set.Add(d);
        if (BypassHulu) foreach (var d in HuluDomains) set.Add(d);
        if (BypassPrimeVideo) foreach (var d in PrimeVideoDomains) set.Add(d);
        if (BypassDisneyPlus) foreach (var d in DisneyPlusDomains) set.Add(d);
        if (BypassMax) foreach (var d in MaxDomains) set.Add(d);
        if (BypassAppleTv) foreach (var d in AppleTvDomains) set.Add(d);
        if (BypassAppleOne) foreach (var d in AppleOneDomains) set.Add(d);
        if (BypassPeacock) foreach (var d in PeacockDomains) set.Add(d);
        if (BypassYouTube) foreach (var d in YouTubeDomains) set.Add(d);
        if (BypassYouTubeTv) foreach (var d in YouTubeTvDomains) set.Add(d);
        if (BypassSlingTv) foreach (var d in SlingTvDomains) set.Add(d);
        if (BypassPlutoTv) foreach (var d in PlutoTvDomains) set.Add(d);
        if (BypassParamountPlus) foreach (var d in ParamountPlusDomains) set.Add(d);
        if (BypassEspnPlus) foreach (var d in EspnPlusDomains) set.Add(d);
        if (BypassSpotify) foreach (var d in SpotifyDomains) set.Add(d);
        if (BypassTubi) foreach (var d in TubiDomains) set.Add(d);
        if (BypassGamingClients) foreach (var d in GamingDomains) set.Add(d);
        if (BypassPhoneLink) foreach (var d in PhoneLinkDomains) set.Add(d);

        foreach (var raw in CustomExcludedDomains)
        {
            var clean = raw.Trim().Trim('.').ToLowerInvariant();
            if (clean.StartsWith("https://", StringComparison.Ordinal)) clean = clean[8..];
            if (clean.StartsWith("http://", StringComparison.Ordinal)) clean = clean[7..];
            var slash = clean.IndexOf('/');
            if (slash >= 0) clean = clean[..slash];
            if (clean.Length > 0 && clean.Contains('.')) set.Add(clean);
        }
        return set;
    }

    public HashSet<string> GetEffectiveExcludedExecutables()
    {
        var set = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var exe in CustomExcludedExecutables)
        {
            if (!string.IsNullOrWhiteSpace(exe) && File.Exists(exe))
                set.Add(exe);
        }

        if (BypassSpotify)
        {
            var spotifyExe = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "Spotify", "Spotify.exe");
            if (File.Exists(spotifyExe)) set.Add(spotifyExe);
        }

        if (BypassGamingClients)
        {
            string[] commonClients =
            [
                @"C:\Program Files (x86)\Steam\steam.exe",
                @"C:\Program Files (x86)\Steam\steamservice.exe",
                @"C:\Program Files (x86)\Steam\bin\cef\cef.win7x64\steamwebhelper.exe",
                @"C:\Program Files (x86)\Epic Games\Launcher\Portal\Binaries\Win64\EpicGamesLauncher.exe",
                @"C:\Program Files (x86)\Epic Games\Launcher\Portal\Binaries\Win32\EpicGamesLauncher.exe",
                @"C:\Program Files (x86)\Battle.net\Battle.net.exe",
                @"C:\Program Files (x86)\Origin\Origin.exe",
                @"C:\Program Files\Electronic Arts\EA Desktop\EA Desktop\EADesktop.exe",
                @"C:\Program Files\Electronic Arts\EA Desktop\EA Desktop\EABackgroundService.exe",
                @"C:\Program Files (x86)\Ubisoft\Ubisoft Game Launcher\upc.exe",
                @"C:\Program Files (x86)\Ubisoft\Ubisoft Game Launcher\UbisoftConnect.exe",
                @"C:\Program Files (x86)\Ubisoft\Ubisoft Game Launcher\UplayWebCore.exe",
                @"C:\Riot Games\Riot Client\RiotClientServices.exe",
                @"C:\Program Files (x86)\GOG Galaxy\GalaxyClient.exe",
                @"C:\Program Files (x86)\GOG Galaxy\GalaxyClientService.exe"
            ];
            foreach (var p in commonClients)
                if (File.Exists(p)) set.Add(p);

            AddDiscoveredExecutables(set, @"C:\Program Files\WindowsApps", "Microsoft.GamingApp_*",
                "XboxPcApp.exe", "XboxApp.exe", "XboxPcAppFT.exe");
            AddDiscoveredExecutables(set, @"C:\Program Files\WindowsApps", "Microsoft.GamingServices_*",
                "GamingServices.exe", "GamingServicesNet.exe");
        }

        if (BypassPhoneLink)
        {
            AddDiscoveredExecutables(set, @"C:\Program Files\WindowsApps", "Microsoft.YourPhone_*",
                "PhoneExperienceHost.exe", "YourPhoneAppProxyHost.exe", "YourPhone.exe");
            AddDiscoveredExecutables(set, @"C:\Program Files\WindowsApps", "MicrosoftWindows.CrossDevice_*",
                "CrossDeviceService.exe", "CrossDeviceResume.exe");
            AddDiscoveredExecutables(set, @"C:\Windows\SystemApps", "MicrosoftWindows.Client.CBS_*",
                "CrossDeviceResume.exe");
        }

        return set;
    }

    private static void AddDiscoveredExecutables(
        HashSet<string> target,
        string rootDir,
        string folderPattern,
        params string[] exeNames)
    {
        try
        {
            if (!Directory.Exists(rootDir)) return;
            foreach (var dir in Directory.EnumerateDirectories(rootDir, folderPattern))
            {
                foreach (var exeName in exeNames)
                {
                    var fullPath = Path.Combine(dir, exeName);
                    if (File.Exists(fullPath))
                        target.Add(fullPath);
                }
            }
        }
        catch { /* Best-effort discovery for WindowsApps / SystemApps. */ }
    }

    private HashSet<string>? _cachedExcludedDomains;

    public void InvalidateCache() => _cachedExcludedDomains = null;

    public bool IsDomainExcluded(string hostname)
    {
        var host = hostname.Trim().Trim('.').ToLowerInvariant();
        if (host.Length == 0) return false;
        var excluded = _cachedExcludedDomains ??= GetEffectiveExcludedDomains();
        var current = host;
        while (true)
        {
            if (excluded.Contains(current)) return true;
            var dot = current.IndexOf('.');
            if (dot < 0) return false;
            current = current[(dot + 1)..];
        }
    }
}

public enum FilterSourceType
{
    RemoteUrl,
    LocalFile,
    CustomRules
}

public sealed class FilterSource
{
    public string Id { get; set; } = Guid.NewGuid().ToString("N");
    public string Name { get; set; } = "";
    public FilterSourceType Type { get; set; } = FilterSourceType.RemoteUrl;
    public string UrlOrPath { get; set; } = "";
    public bool Enabled { get; set; } = true;
    public int RuleCount { get; set; }
    public long LastUpdatedMs { get; set; }
    public string Content { get; set; } = "";

    [JsonIgnore]
    public string TypeLabel => Type switch
    {
        FilterSourceType.RemoteUrl => "Remote",
        FilterSourceType.LocalFile => "Local",
        _ => "Custom"
    };

    [JsonIgnore]
    public string UpdatedLabel => LastUpdatedMs > 0
        ? DateTimeOffset.FromUnixTimeMilliseconds(LastUpdatedMs).ToLocalTime().ToString("yyyy-MM-dd HH:mm")
        : "Never";
}

public sealed record FilterDecision(
    bool Blocked,
    string? MatchedRule = null,
    string? SourceName = null,
    string Category = "DOMAIN");

public sealed record FilterStats(
    int TotalRules,
    int DomainBlockRules,
    int PathBlockRules,
    int ExceptionRules,
    int EnabledSources);

public enum TunnelStatus
{
    Disconnected,
    Connecting,
    Connected,
    Reconnecting,
    Error
}

public sealed record TrafficLogEntry(
    DateTimeOffset Timestamp,
    string Protocol,
    string TargetHost,
    bool Blocked,
    string? MatchedRule,
    string? SourceName)
{
    public string TimeText => Timestamp.ToLocalTime().ToString("HH:mm:ss");
    public string ActionText => Blocked ? "BLOCKED" : (MatchedRule == "SPLIT_BYPASS" ? "BYPASS" : "ALLOWED");
    public string DetailText => Blocked && !string.IsNullOrEmpty(MatchedRule)
        ? $"{MatchedRule} ({SourceName ?? "Filter"})"
        : SourceName ?? "";
}

public sealed class WindowsSecurityReport
{
    public string MachineName { get; init; } = Environment.MachineName;
    public string OsVersion { get; init; } = Environment.OSVersion.VersionString;
    public bool IsAdministrator { get; init; }
    public bool DefenderEnabled { get; init; }
    public bool FirewallEnabled { get; init; }
    public bool SecureBootEnabled { get; init; }
    public bool UacEnabled { get; init; }
    public bool VpnConnected { get; init; }
    public long BlockedCount { get; init; }
    public long AllowedCount { get; init; }
}

public sealed record XSecuritySyncResult(
    bool Ok,
    string Message,
    int? PostureScore = null,
    string? PostureStatus = null,
    int? OpenAlerts = null);

public sealed class AppSettings
{
    public CloudflareOneConfig CloudflareOne { get; set; } = new();
    public ZeroTrustDnsConfig ZeroTrustDns { get; set; } = new();
    public SplitTunnelConfig SplitTunnel { get; set; } = new();
    public bool AlwaysOnVpn { get; set; } = true;
    public bool KillSwitchEnabled { get; set; } = true;
    public bool LanBypassEnabled { get; set; } = true;
    public bool DnsFilteringEnabled { get; set; } = true;
    public bool DeepInspectionEnabled { get; set; } = true;
    public bool UseNxDomain { get; set; }
    public string XSecurityDomain { get; set; } = "";
    public string XSecurityApiKey { get; set; } = "";
}
