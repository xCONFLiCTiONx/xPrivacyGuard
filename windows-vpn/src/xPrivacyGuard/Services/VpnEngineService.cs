using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;
using System.Threading.Channels;
using xPrivacyGuard.Models;
using xPrivacyGuard.Native;

namespace xPrivacyGuard.Services;

public sealed record VpnRuntimeSnapshot(
    TunnelStatus Status,
    string StatusMessage,
    string? ActivePeer,
    string? AssignedIpv4,
    string? AssignedIpv6,
    string? Carrier,
    int Mtu,
    long BypassCount,
    long TxBytes,
    long RxBytes);

public sealed class VpnEngineService : IAsyncDisposable
{
    private readonly SettingsStore _settingsStore;
    private readonly ZeroTrustDnsResolver _dnsResolver = new();
    private readonly WfpFirewallService _wfp = new();
    private readonly SystemConnectivityMonitor _connectivityMonitor = new();

    private readonly SemaphoreSlim _lifecycleLock = new(1, 1);
    private readonly SemaphoreSlim _privateDnsConcurrency = new(8, 8);
    private readonly SemaphoreSlim _reconnectWakeSignal = new(0, 1);

    private CancellationTokenSource? _runCts;
    private CancellationTokenSource? _reconnectCts;
    private AetherMasqueTransport? _transport;
    private WintunAdapter? _wintun;
    private Task? _pumpTask;
    private volatile AppSettings? _activeSettings;

    private int _reconnecting;
    private volatile bool _isSuspended;
    private volatile bool _recoveryRequested;
    private volatile string? _recoveryReason;
    private long _lastRxTickMs;
    private long _outboundSinceRxTickMs;
    private int _migrationAttemptedForStall;

    private TunnelStatus _status = TunnelStatus.Disconnected;
    private string _statusMessage = "Disconnected";
    private string? _activePeer;
    private string? _assignedIpv4;
    private string? _assignedIpv6;
    private string? _carrier;
    private int _mtu = 1280;
    private long _bypassCount;
    private long _txBytes;
    private long _rxBytes;

    private readonly ConcurrentQueue<TrafficLogEntry> _recentLogs = new();
    private const int MaxLogEntries = 80;
    private long _lastLogTickMs;

    public event Action<VpnRuntimeSnapshot>? StateChanged;
    public event Action<TrafficLogEntry>? TrafficLogged;
    public event Action<AetherIdentitySummary>? IdentityEnrolled;

    public VpnEngineService(SettingsStore settingsStore)
    {
        _settingsStore = settingsStore;
        _connectivityMonitor.SystemSuspending += OnSystemSuspending;
        _connectivityMonitor.SystemResumed += OnSystemResumed;
        _connectivityMonitor.NetworkChanged += OnNetworkChanged;
    }

    public VpnRuntimeSnapshot Snapshot => new(
        _status,
        _statusMessage,
        _activePeer,
        _assignedIpv4,
        _assignedIpv6,
        _carrier,
        _mtu,
        Interlocked.Read(ref _bypassCount),
        Interlocked.Read(ref _txBytes),
        Interlocked.Read(ref _rxBytes));

    public IReadOnlyList<TrafficLogEntry> RecentLogs => _recentLogs.ToArray();

    public async Task ConnectAsync(AppSettings settings)
    {
        // Abort any in-flight reconnect or stalled connect attempt before acquiring the lifecycle lock.
        CancelReconnectLoop();
        CancelCurrentRun();

        await _lifecycleLock.WaitAsync().ConfigureAwait(false);
        try
        {
            await StopInternalAsync(clearWfp: true).ConfigureAwait(false);
            _activeSettings = settings;
            _recoveryRequested = false;
            _recoveryReason = null;
            await ConnectCoreAsync(settings, CancellationToken.None).ConfigureAwait(false);
        }
        catch (Exception ex)
        {
            VpnLogger.Error("CONNECT", "ConnectAsync failed", ex);
            _activeSettings = null;
            await StopInternalAsync(clearWfp: true).ConfigureAwait(false);
            UpdateState(TunnelStatus.Error, ex.Message);
            throw;
        }
        finally
        {
            _lifecycleLock.Release();
        }
    }

    private async Task ConnectCoreAsync(AppSettings settings, CancellationToken externalCt)
    {
        if (!WindowsSecurityChecker.IsAdministrator())
            throw new InvalidOperationException("Administrator privileges are required to create the Wintun adapter and install WFP routing rules.");

        if (!AetherRawTunnel.IsNativeLibraryAvailable(out var errOrVer))
            throw new InvalidOperationException($"Native aether.dll is not available: {errOrVer}");

        if (!WintunAdapter.HasActivePhysicalNetwork())
            throw new InvalidOperationException("No active physical network interface with an IPv4 gateway is available.");

        var sw = System.Diagnostics.Stopwatch.StartNew();
        VpnLogger.Info("CONNECT", $"Starting connection (alwaysOn={settings.AlwaysOnVpn}, killSwitch={settings.KillSwitchEnabled}, lanBypass={settings.LanBypassEnabled})");

        UpdateState(TunnelStatus.Connecting, "Initializing Cloudflare One MASQUE...");
        _runCts = externalCt.CanBeCanceled
            ? CancellationTokenSource.CreateLinkedTokenSource(externalCt)
            : new CancellationTokenSource();
        var ct = _runCts.Token;

        var excludedExes = settings.SplitTunnel.GetEffectiveExcludedExecutables();

        if (settings.KillSwitchEnabled)
        {
            VpnLogger.Info("WFP", $"Applying pre-connect fail-closed policy (lanBypass={settings.LanBypassEnabled}, excludedExes={excludedExes.Count})");
            _wfp.ApplyPolicy(0, true, settings.LanBypassEnabled, excludedExes);
        }

        _transport = new AetherMasqueTransport(
            _settingsStore.IdentityDirectory,
            settings.CloudflareOne,
            msg => UpdateState(TunnelStatus.Connecting, msg),
            summary => IdentityEnrolled?.Invoke(summary));

        var session = await _transport.ConnectAsync(ct).ConfigureAwait(false);

        UpdateState(TunnelStatus.Connecting, "Configuring Wintun adapter and routes...");
        var dnsServers = settings.ZeroTrustDns.ConfiguredIpv4List();
        _wintun = new WintunAdapter();
        _wintun.Open(
            ipv4Cidr: session.Ipv4,
            ipv6Cidr: session.Ipv6,
            mtu: session.Mtu,
            peerEndpoint: session.Peer,
            lanBypass: settings.LanBypassEnabled,
            dnsServers: dnsServers);
        VpnLogger.Info("WINTUN", $"Adapter ready: luid=0x{_wintun.InterfaceLuid:X}, ifIndex={_wintun.InterfaceIndex}, physGw={_wintun.PhysicalGateway}, physIfIndex={_wintun.PhysicalInterfaceIndex}");

        _wfp.ApplyPolicy(
            _wintun.InterfaceLuid,
            settings.KillSwitchEnabled,
            settings.LanBypassEnabled,
            excludedExes);
        VpnLogger.Info("WFP", $"Applied active tunnel policy (luid=0x{_wintun.InterfaceLuid:X}, killSwitch={settings.KillSwitchEnabled})");

        _activePeer = session.Peer;
        _assignedIpv4 = session.Ipv4;
        _assignedIpv6 = session.Ipv6;
        _carrier = session.Carrier.ToUpperInvariant();
        _mtu = session.Mtu;

        VpnLogger.Info("CONNECT", $"Connected via {_carrier} ({_activePeer}) in {sw.ElapsedMilliseconds}ms");
        UpdateState(TunnelStatus.Connected, $"Connected via {_carrier} ({_activePeer})");

        _pumpTask = Task.Run(() => RunPacketLoopsAsync(settings, ct), ct);
    }

    public async Task DisconnectAsync()
    {
        // Clear desired state and cancel active/reconnecting operations immediately so Disconnect never hangs.
        _activeSettings = null;
        _recoveryRequested = false;
        _recoveryReason = null;
        CancelReconnectLoop();
        CancelCurrentRun();
        SignalReconnectWake();

        await _lifecycleLock.WaitAsync().ConfigureAwait(false);
        try
        {
            _activeSettings = null;
            await StopInternalAsync(clearWfp: true).ConfigureAwait(false);
            UpdateState(TunnelStatus.Disconnected, "Disconnected");
        }
        finally
        {
            _lifecycleLock.Release();
        }
    }

    private void OnSystemSuspending()
    {
        _isSuspended = true;
        VpnLogger.Info("POWER", "System suspending (sleep/hibernation)");
    }

    private void OnSystemResumed()
    {
        _isSuspended = false;
        VpnLogger.Info("POWER", $"System resumed from sleep (vpnActive={_activeSettings is not null}, status={_status})");
        var settings = _activeSettings;
        if (settings is null) return;

        TriggerRecovery(settings, "System resumed from sleep");
    }

    private void OnNetworkChanged()
    {
        SignalReconnectWake();
        if (_isSuspended) return;

        var settings = _activeSettings;
        if (settings is null) return;

        if (_status == TunnelStatus.Connected && _wintun is { } activeWintun)
        {
            if (WintunAdapter.TryGetActivePhysicalNetwork(out var gw, out var ifIndex)
                && (!string.Equals(gw, activeWintun.PhysicalGateway, StringComparison.OrdinalIgnoreCase)
                    || ifIndex != activeWintun.PhysicalInterfaceIndex))
            {
                VpnLogger.Info("NET", $"Physical gateway changed: {activeWintun.PhysicalGateway} (if={activeWintun.PhysicalInterfaceIndex}) -> {gw} (if={ifIndex})");
                TriggerRecovery(settings, "Physical network interface changed");
            }
        }
    }

    private void TriggerRecovery(AppSettings settings, string reason)
    {
        if (_activeSettings is null) return;

        VpnLogger.Warn("RECOVERY", $"Triggering recovery: {reason}");
        _recoveryReason = reason;
        _recoveryRequested = true;
        CancelCurrentRun();
        SignalReconnectWake();
        StartReconnectLoop(settings);
    }

    private void StartReconnectLoop(AppSettings settings)
    {
        if (Interlocked.CompareExchange(ref _reconnecting, 1, 0) != 0)
            return;

        _ = Task.Run(() => ReconnectLoopAsync(settings));
    }

    private void CancelCurrentRun()
    {
        var cts = _runCts;
        if (cts is not null)
        {
            try { cts.Cancel(); } catch { }
        }
    }

    private void CancelReconnectLoop()
    {
        var cts = Interlocked.Exchange(ref _reconnectCts, null);
        if (cts is not null)
        {
            try { cts.Cancel(); } catch { }
            try { cts.Dispose(); } catch { }
        }
    }

    private void SignalReconnectWake()
    {
        try
        {
            if (_reconnectWakeSignal.CurrentCount == 0)
                _reconnectWakeSignal.Release();
        }
        catch { }
    }

    private async Task StopInternalAsync(bool clearWfp)
    {
        if (_runCts is not null)
        {
            try { _runCts.Cancel(); } catch { }
        }

        if (_pumpTask is not null)
        {
            try { await Task.WhenAny(_pumpTask, Task.Delay(1500)).ConfigureAwait(false); } catch { }
            _pumpTask = null;
        }

        if (clearWfp)
            _wfp.ClearPolicy();

        if (_wintun is not null)
        {
            try { _wintun.Dispose(); } catch { }
            _wintun = null;
        }

        if (_transport is not null)
        {
            try { await _transport.DisconnectAsync().ConfigureAwait(false); } catch { }
            _transport = null;
        }

        _runCts?.Dispose();
        _runCts = null;

        _activePeer = null;
        _assignedIpv4 = null;
        _assignedIpv6 = null;
        _carrier = null;
    }

    private async Task RunPacketLoopsAsync(AppSettings settings, CancellationToken ct)
    {
        var wintun = _wintun;
        var transport = _transport;
        if (wintun is null || transport is null) return;

        Interlocked.Exchange(ref _lastRxTickMs, Environment.TickCount64);
        Interlocked.Exchange(ref _outboundSinceRxTickMs, 0);
        Interlocked.Exchange(ref _migrationAttemptedForStall, 0);

        using var pumpCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        var pumpCt = pumpCts.Token;

        var bypassIpChannel = Channel.CreateBounded<string>(new BoundedChannelOptions(128)
        {
            FullMode = BoundedChannelFullMode.DropOldest,
            SingleReader = true
        });

        var routeWorker = Task.Run(async () =>
        {
            try
            {
                await foreach (var ip in bypassIpChannel.Reader.ReadAllAsync(pumpCt).ConfigureAwait(false))
                {
                    if (wintun.AddDynamicBypassIp(ip))
                        _wfp.PermitDynamicBypassIpv4(ip);
                }
            }
            catch (OperationCanceledException) { }
        }, pumpCt);

        var outboundTask = Task.Run(() => OutboundPump(wintun, transport, settings, pumpCt), pumpCt);
        var inboundTask = Task.Run(() => InboundPump(wintun, transport, settings, bypassIpChannel.Writer, pumpCt), pumpCt);

        await Task.WhenAny(outboundTask, inboundTask).ConfigureAwait(false);
        try { pumpCts.Cancel(); } catch { }
        bypassIpChannel.Writer.TryComplete();
        try { await Task.WhenAny(Task.WhenAll(outboundTask, inboundTask, routeWorker), Task.Delay(500)).ConfigureAwait(false); } catch { }

        if (_activeSettings is not null && (!ct.IsCancellationRequested || _recoveryRequested))
        {
            if (settings.AlwaysOnVpn || _recoveryRequested)
            {
                StartReconnectLoop(settings);
            }
            else
            {
                UpdateState(TunnelStatus.Error, "MASQUE tunnel connection closed unexpectedly.");
            }
        }
    }

    private async Task ReconnectLoopAsync(AppSettings settings)
    {
        var cts = new CancellationTokenSource();
        var prevCts = Interlocked.Exchange(ref _reconnectCts, cts);
        if (prevCts is not null)
        {
            try { prevCts.Cancel(); } catch { }
            try { prevCts.Dispose(); } catch { }
        }

        var reconnectCt = cts.Token;

        try
        {
            // Immediately tear down stale tunnel & Wintun routes (preserving WFP kill switch if active)
            // so dead routes cannot trap or loop traffic while the physical NIC comes back online.
            await _lifecycleLock.WaitAsync(reconnectCt).ConfigureAwait(false);
            try
            {
                if (_activeSettings is null) return;
                await StopInternalAsync(clearWfp: false).ConfigureAwait(false);
            }
            finally
            {
                _lifecycleLock.Release();
            }

            var delaySec = 1;
            while (_activeSettings is not null && !reconnectCt.IsCancellationRequested)
            {
                if (_isSuspended)
                {
                    await WaitForWakeOrTimeoutAsync(TimeSpan.FromSeconds(1), reconnectCt).ConfigureAwait(false);
                    continue;
                }

                var reason = _recoveryReason ?? "Tunnel dropped";
                if (!WintunAdapter.HasActivePhysicalNetwork())
                {
                    UpdateState(TunnelStatus.Reconnecting, $"{reason} — waiting for network interface...");
                    while (_activeSettings is not null && !reconnectCt.IsCancellationRequested && !WintunAdapter.HasActivePhysicalNetwork())
                    {
                        await WaitForWakeOrTimeoutAsync(TimeSpan.FromMilliseconds(250), reconnectCt).ConfigureAwait(false);
                    }

                    if (_activeSettings is null || reconnectCt.IsCancellationRequested) return;

                    // Brief settle after the NIC reports Up so DHCP/default routes finish installing.
                    await Task.Delay(400, reconnectCt).ConfigureAwait(false);
                    delaySec = 1;
                }

                UpdateState(TunnelStatus.Reconnecting, $"{reason} — reconnecting...");

                await _lifecycleLock.WaitAsync(reconnectCt).ConfigureAwait(false);
                try
                {
                    if (_activeSettings is null || reconnectCt.IsCancellationRequested) return;
                    await StopInternalAsync(clearWfp: false).ConfigureAwait(false);
                    await ConnectCoreAsync(settings, reconnectCt).ConfigureAwait(false);
                    _recoveryRequested = false;
                    _recoveryReason = null;
                    return;
                }
                catch (OperationCanceledException) when (_activeSettings is null || reconnectCt.IsCancellationRequested)
                {
                    return;
                }
                catch (Exception ex)
                {
                    VpnLogger.Error("RECONNECT", $"Reconnect attempt failed (retrying in {delaySec}s)", ex);
                    UpdateState(TunnelStatus.Reconnecting, $"Reconnect failed ({ex.Message}) — retrying in {delaySec}s...");
                }
                finally
                {
                    _lifecycleLock.Release();
                }

                var wokenEarly = await WaitForWakeOrTimeoutAsync(TimeSpan.FromSeconds(delaySec), reconnectCt).ConfigureAwait(false);
                delaySec = wokenEarly ? 1 : Math.Min(delaySec * 2, 10);
            }
        }
        catch (OperationCanceledException) { }
        finally
        {
            Interlocked.Exchange(ref _reconnecting, 0);
        }
    }

    private async Task<bool> WaitForWakeOrTimeoutAsync(TimeSpan timeout, CancellationToken ct)
    {
        try
        {
            return await _reconnectWakeSignal.WaitAsync(timeout, ct).ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
            return false;
        }
    }

    private void OutboundPump(WintunAdapter wintun, AetherMasqueTransport transport, AppSettings settings, CancellationToken ct)
    {
        var buf = new byte[65535];
        var hasPrivateDns = settings.ZeroTrustDns.IsPrivateDnsConfigured && IsPrivateLanIp(settings.ZeroTrustDns.PrivateDnsServer);

        while (!ct.IsCancellationRequested)
        {
            var len = wintun.ReceivePacket(buf, 150);
            if (len < 0) break;
            if (len == 0)
            {
                if (!transport.IsAlive) break;
                continue;
            }

            // Optional Private LAN Split-DNS (e.g. internal.example.com -> local DNS server on LAN)
            if (hasPrivateDns)
            {
                var dnsQ = PacketInspector.ParseOutboundDnsQuery(buf, len);
                if (dnsQ is not null
                    && settings.ZeroTrustDns.MatchesPrivateDomain(dnsQ.Value.QName)
                    && _privateDnsConcurrency.Wait(0))
                {
                    var origCopy = new byte[len];
                    Buffer.BlockCopy(buf, 0, origCopy, 0, len);
                    var qname = dnsQ.Value.QName;
                    var dnsPayload = dnsQ.Value.DnsQueryPayload;
                    _ = Task.Run(async () =>
                    {
                        try
                        {
                            var dnsResp = await _dnsResolver.ResolveWireQueryAsync(
                                dnsPayload, qname, settings.ZeroTrustDns, ct).ConfigureAwait(false);
                            if (dnsResp is not null)
                            {
                                var replyPkt = PacketInspector.BuildDnsReplyFromUpstream(origCopy, origCopy.Length, dnsResp);
                                if (replyPkt is not null) wintun.SendPacket(replyPkt, replyPkt.Length);
                                return;
                            }
                        }
                        catch { }
                        finally
                        {
                            _privateDnsConcurrency.Release();
                        }
                        transport.SendPacketFast(origCopy, origCopy.Length);
                    }, ct);
                    continue;
                }
            }

            var sendRc = transport.SendPacketFast(buf, len);
            if (sendRc == 0)
            {
                Interlocked.Add(ref _txBytes, len);
                Interlocked.CompareExchange(ref _outboundSinceRxTickMs, Environment.TickCount64, 0);
            }
            else if (sendRc == -1)
            {
                // Underlying MASQUE tunnel is closed/dead; break immediately so reconnect starts.
                VpnLogger.Warn("PUMP", "OutboundPump: SendPacketFast returned -1 (tunnel closed)");
                break;
            }
        }
    }

    private void InboundPump(
        WintunAdapter wintun,
        AetherMasqueTransport transport,
        AppSettings settings,
        ChannelWriter<string> bypassIpWriter,
        CancellationToken ct)
    {
        var buf = new byte[65535];
        var lastWallUtc = DateTimeOffset.UtcNow;

        while (!ct.IsCancellationRequested)
        {
            var len = transport.ReceivePacketInto(buf, 200);
            if (len < 0)
            {
                VpnLogger.Warn("PUMP", $"InboundPump: ReceivePacketInto returned {len} (tunnel closed)");
                break;
            }

            var nowWallUtc = DateTimeOffset.UtcNow;
            if ((nowWallUtc - lastWallUtc).TotalSeconds > 15)
            {
                // Fallback sleep/resume detection if OS power notifications were missed (e.g. Modern Standby).
                VpnLogger.Warn("PUMP", $"InboundPump: wall-clock jump of {(nowWallUtc - lastWallUtc).TotalSeconds:F1}s detected");
                _recoveryReason = "System resume detected";
                _recoveryRequested = true;
                break;
            }
            lastWallUtc = nowWallUtc;

            if (len == 0)
            {
                var outboundStart = Interlocked.Read(ref _outboundSinceRxTickMs);
                if (outboundStart != 0)
                {
                    var stalledMs = Environment.TickCount64 - outboundStart;
                    if (stalledMs >= 10_000 && Interlocked.Exchange(ref _migrationAttemptedForStall, 1) == 0)
                    {
                        VpnLogger.Warn("PUMP", $"No inbound traffic for {stalledMs}ms while sending outbound; requesting QUIC migration");
                        transport.RequestMigration();
                    }
                    else if (stalledMs >= 16_000)
                    {
                        VpnLogger.Warn("PUMP", $"No inbound traffic for {stalledMs}ms after migration attempt; forcing reconnect");
                        _recoveryReason = "Tunnel unresponsive";
                        _recoveryRequested = true;
                        break;
                    }
                }
                continue;
            }

            Interlocked.Exchange(ref _lastRxTickMs, Environment.TickCount64);
            Interlocked.Exchange(ref _outboundSinceRxTickMs, 0);
            Interlocked.Exchange(ref _migrationAttemptedForStall, 0);

            // Inspect inbound DNS responses for split-tunneled streaming & gaming domains
            var dnsAns = PacketInspector.ParseInboundDnsResponse(buf, len);
            if (dnsAns is not null && settings.SplitTunnel.IsDomainExcluded(dnsAns.Value.QName))
            {
                Interlocked.Increment(ref _bypassCount);
                RecordLog("SPLIT", dnsAns.Value.QName, false, "SPLIT_BYPASS", $"Direct route ({dnsAns.Value.Ipv4Answers.Count} IP)");
                foreach (var ip in dnsAns.Value.Ipv4Answers)
                    bypassIpWriter.TryWrite(ip);
            }

            if (wintun.SendPacket(buf, len))
                Interlocked.Add(ref _rxBytes, len);
        }
    }

    private static bool IsPrivateLanIp(string rawIp)
    {
        if (!IPAddress.TryParse(rawIp.Trim(), out var ip) || ip.AddressFamily != AddressFamily.InterNetwork)
            return false;
        var b = ip.GetAddressBytes();
        return b[0] == 10
               || (b[0] == 192 && b[1] == 168)
               || (b[0] == 172 && b[1] >= 16 && b[1] <= 31);
    }

    private void RecordLog(string protocol, string targetHost, bool blocked, string? rule, string? source)
    {
        var nowMs = Environment.TickCount64;
        var prev = Interlocked.Read(ref _lastLogTickMs);
        if (nowMs - prev < 100) return;
        Interlocked.Exchange(ref _lastLogTickMs, nowMs);

        var entry = new TrafficLogEntry(DateTimeOffset.UtcNow, protocol, targetHost, blocked, rule, source);
        _recentLogs.Enqueue(entry);
        while (_recentLogs.Count > MaxLogEntries)
            _recentLogs.TryDequeue(out _);
        TrafficLogged?.Invoke(entry);
    }

    private void UpdateState(TunnelStatus status, string message)
    {
        _status = status;
        _statusMessage = message;
        VpnLogger.Info("STATE", $"{status}: {message}");
        StateChanged?.Invoke(Snapshot);
    }

    public async ValueTask DisposeAsync()
    {
        _connectivityMonitor.Dispose();
        await DisconnectAsync().ConfigureAwait(false);
        _wfp.Dispose();
        _lifecycleLock.Dispose();
        _privateDnsConcurrency.Dispose();
        _reconnectWakeSignal.Dispose();
    }
}
