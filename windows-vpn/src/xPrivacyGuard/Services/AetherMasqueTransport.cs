using System.IO;
using xPrivacyGuard.Models;
using xPrivacyGuard.Native;

namespace xPrivacyGuard.Services;

public sealed record AetherSessionInfo(
    string Peer,
    string Ipv4,
    string Ipv6,
    int Mtu,
    string Carrier,
    AetherIdentitySummary Identity);

public sealed class AetherMasqueTransport : IAsyncDisposable
{
    private readonly string _identityDir;
    private readonly CloudflareOneConfig _config;
    private readonly Action<string> _onStatus;
    private readonly Action<AetherIdentitySummary> _onIdentityOpened;

    private AetherIdentity? _identity;
    private AetherRawTunnel? _tunnel;

    public AetherSessionInfo? SessionInfo { get; private set; }
    public bool IsAlive => _tunnel?.IsAlive == true;

    public AetherMasqueTransport(
        string identityDir,
        CloudflareOneConfig config,
        Action<string> onStatus,
        Action<AetherIdentitySummary> onIdentityOpened)
    {
        _identityDir = identityDir;
        _config = config;
        _onStatus = onStatus;
        _onIdentityOpened = onIdentityOpened;
    }

    public async Task<AetherSessionInfo> ConnectAsync(CancellationToken ct = default)
    {
        await DisconnectAsync().ConfigureAwait(false);
        Directory.CreateDirectory(_identityDir);

        _onStatus("Opening Cloudflare MASQUE identity...");
        var identityPath = Path.Combine(_identityDir, "aether.toml");
        VpnLogger.Info("AUTH", $"Opening identity at '{identityPath}' (team='{_config.NormalizedTeam}', enrolled={_config.IsEnrolled}, hasToken={_config.HasValidToken}, hasServiceToken={_config.HasServiceToken}, preferH2={_config.PreferHttp2}, enableEch={_config.EnableEch})");
        _identity = await AetherIdentity.OpenAsync(identityPath, _config, ct).ConfigureAwait(false);

        var summary = _identity.Info.Summary;
        VpnLogger.Info("AUTH", $"Identity opened: deviceId='{summary.DeviceId}', org='{summary.Organization}', ipv4='{summary.Ipv4}', ipv6='{summary.Ipv6}', assignedEndpoint='{summary.AssignedEndpoint}', hasMasqueCert={summary.HasMasqueCert}, certUsable={summary.CertUsable}");
        if (!summary.HasMasqueCert)
            throw new InvalidOperationException("Cloudflare device identity does not have a MASQUE certificate.");

        try { _onIdentityOpened(summary); } catch { /* Non-fatal UI callback. */ }

        var isZeroTrust = !string.IsNullOrEmpty(_config.NormalizedTeam) || !string.IsNullOrWhiteSpace(summary.Organization);
        var useEch = _config.EnableEch && !isZeroTrust;
        if (_config.EnableEch && isZeroTrust)
            VpnLogger.Info("MASQUE", "Zero Trust mTLS gateway detected — bypassing ECH to avoid gateway rejection and slow port scans");

        var peer = await ResolveReachablePeerAsync(_identity, summary, useEch, ct).ConfigureAwait(false);

        _onStatus($"Establishing MASQUE CONNECT-IP tunnel ({peer})...");
        VpnLogger.Info("MASQUE", $"Starting raw tunnel to {peer} (h2={_config.PreferHttp2}, ech={useEch}, mtu=1280)");
        try
        {
            _tunnel = await AetherRawTunnel.StartAsync(
                identity: _identity.Handle,
                peer: peer,
                useHttp2: _config.PreferHttp2,
                enableEch: useEch,
                profile: null,
                mtu: 1280,
                startupTimeout: TimeSpan.FromSeconds(25),
                cancellationToken: ct).ConfigureAwait(false);
        }
        catch (AetherNativeException ex) when (useEch)
        {
            // Fall back cleanly without ECH if the gateway rejects it.
            VpnLogger.Warn("MASQUE", $"MASQUE tunnel with ECH failed on {peer} ({ex.Message}); retrying without ECH");
            _onStatus($"Retrying MASQUE tunnel ({peer}) without ECH...");
            _tunnel = await AetherRawTunnel.StartAsync(
                identity: _identity.Handle,
                peer: peer,
                useHttp2: _config.PreferHttp2,
                enableEch: false,
                profile: null,
                mtu: 1280,
                startupTimeout: TimeSpan.FromSeconds(25),
                cancellationToken: ct).ConfigureAwait(false);
        }

        var info = _tunnel.Info;
        VpnLogger.Info("MASQUE", $"Tunnel established: peer='{info.Peer}', carrier='{info.Carrier}', ipv4='{info.Ipv4}', ipv6='{info.Ipv6}', mtu={info.Mtu}");
        SessionInfo = new AetherSessionInfo(
            Peer: info.Peer,
            Ipv4: string.IsNullOrWhiteSpace(info.Ipv4) ? summary.Ipv4 : info.Ipv4!,
            Ipv6: string.IsNullOrWhiteSpace(info.Ipv6) ? summary.Ipv6 : info.Ipv6!,
            Mtu: info.Mtu,
            Carrier: info.Carrier,
            Identity: summary);

        return SessionInfo;
    }

    public int SendPacketFast(byte[] packet, int length)
        => _tunnel?.SendPacketFast(packet, length) ?? -1;

    public int ReceivePacketInto(byte[] buffer, int timeoutMs = 200)
        => _tunnel?.ReceivePacket(buffer, TimeSpan.FromMilliseconds(timeoutMs)) ?? -1;

    public bool RequestMigration()
        => _tunnel?.RequestMigration() == true;

    public async Task DisconnectAsync()
    {
        var tunnel = Interlocked.Exchange(ref _tunnel, null);
        if (tunnel is not null)
        {
            try { await tunnel.DisposeAsync().ConfigureAwait(false); } catch { }
        }

        var identity = Interlocked.Exchange(ref _identity, null);
        if (identity is not null)
        {
            try { await identity.DisposeAsync().ConfigureAwait(false); } catch { }
        }

        SessionInfo = null;
    }

    public async ValueTask DisposeAsync() => await DisconnectAsync().ConfigureAwait(false);

    private async Task<string> ResolveReachablePeerAsync(
        AetherIdentity identity,
        AetherIdentitySummary summary,
        bool useEch,
        CancellationToken ct)
    {
        var custom = _config.CustomPeerEndpoint.Trim();
        if (custom.Length > 0)
        {
            var ep = EnsurePort(custom, 443);
            VpnLogger.Info("PROBE", $"Using custom peer endpoint: {ep}");
            return ep;
        }

        var candidates = BuildFastGatewayCandidates(summary);
        if (_config.PreferHttp2)
        {
            VpnLogger.Info("PROBE", $"PreferHttp2 enabled; using primary candidate {candidates[0]}");
            return candidates[0];
        }

        var probeList = candidates.Take(5).ToList();
        _onStatus("Finding fastest Cloudflare MASQUE gateway...");
        VpnLogger.Info("PROBE", $"Probing {probeList.Count} MASQUE candidates (ech={useEch}): {string.Join(", ", probeList)}");
        var winner = await ProbeFastestCandidateAsync(identity, probeList, useEch, TimeSpan.FromSeconds(6), ct)
            .ConfigureAwait(false);
        if (!string.IsNullOrWhiteSpace(winner))
        {
            VpnLogger.Info("PROBE", $"Fastest reachable candidate: {winner}");
            return winner;
        }

        VpnLogger.Warn("PROBE", "Fast candidate probe returned no reachable gateway; scanning alternate ports");
        _onStatus("Scanning alternate Cloudflare MASQUE ports...");
        try
        {
            var scanned = await identity.ScanEndpointAsync(useEch, TimeSpan.FromSeconds(15), ct)
                .ConfigureAwait(false);
            if (!string.IsNullOrWhiteSpace(scanned))
            {
                var ep = EnsurePort(scanned, 443);
                VpnLogger.Info("PROBE", $"Port scan selected endpoint: {ep}");
                return ep;
            }
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
        catch (Exception ex)
        {
            VpnLogger.Warn("PROBE", $"Alternate port scan failed ({ex.Message}); falling back to {candidates[0]}");
        }

        VpnLogger.Warn("PROBE", $"Falling back to primary anycast candidate: {candidates[0]}");
        return candidates[0];
    }

    private List<string> BuildFastGatewayCandidates(AetherIdentitySummary summary)
    {
        var isZeroTrust = !string.IsNullOrEmpty(_config.NormalizedTeam) || !string.IsNullOrWhiteSpace(summary.Organization);
        var ordered = new List<string>();
        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);

        void Add(string? ep)
        {
            if (string.IsNullOrWhiteSpace(ep)) return;
            var normalized = EnsurePort(ep.Trim(), 443);
            if (seen.Add(normalized)) ordered.Add(normalized);
        }

        // Prioritize Cloudflare's dedicated MASQUE anycast gateways before the account's assignedEndpoint,
        // because assignedEndpoint can be a WireGuard-only 162.159.192/193.x IP.
        string[] seeds = isZeroTrust
            ? ["162.159.197.2:443", "162.159.197.1:443", "162.159.197.3:443", "162.159.198.2:443", "162.159.198.1:443", "162.159.199.2:443"]
            : ["162.159.198.2:443", "162.159.198.1:443", "162.159.199.2:443", "162.159.199.1:443"];

        foreach (var s in seeds) Add(s);
        Add(summary.AssignedEndpoint);
        Add(_config.AssignedEndpoint);
        return ordered;
    }

    private async Task<string?> ProbeFastestCandidateAsync(
        AetherIdentity identity,
        IReadOnlyList<string> candidates,
        bool useEch,
        TimeSpan timeout,
        CancellationToken ct)
    {
        if (candidates.Count == 0) return null;
        using var linkedCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        linkedCts.CancelAfter(timeout);

        var tcs = new TaskCompletionSource<string?>(TaskCreationOptions.RunContinuationsAsynchronously);
        var remaining = candidates.Count;

        var tasks = candidates.Select(candidate => Task.Run(async () =>
        {
            try
            {
                var ok = await identity.VerifyPeerAsync(candidate, useEch, timeout, linkedCts.Token)
                    .ConfigureAwait(false);
                if (ok)
                {
                    if (tcs.TrySetResult(candidate))
                        linkedCts.Cancel();
                    return;
                }
            }
            catch { }

            if (Interlocked.Decrement(ref remaining) == 0)
                tcs.TrySetResult(null);
        }, linkedCts.Token)).ToArray();

        using var reg = linkedCts.Token.Register(() => tcs.TrySetResult(null));
        var winner = await tcs.Task.ConfigureAwait(false);
        try { await Task.WhenAny(Task.WhenAll(tasks), Task.Delay(500)).ConfigureAwait(false); } catch { }
        return winner;
    }

    private static string EnsurePort(string addr, int defaultPort)
    {
        if (addr.StartsWith('['))
            return addr.Contains("]:", StringComparison.Ordinal) ? addr : $"{addr}:{defaultPort}";
        var colons = addr.Count(c => c == ':');
        return colons switch
        {
            0 => $"{addr}:{defaultPort}",
            1 => addr,
            _ => $"[{addr}]:{defaultPort}"
        };
    }
}
