using System.Collections.Concurrent;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using xPrivacyGuard.Native;

namespace xPrivacyGuard.Services;

/// <summary>
/// Always-on Windows Filtering Platform (WFP) policy engine:
/// 1. Transport-socket bypass for xPrivacyGuard.exe (Aether MASQUE sockets).
/// 2. Loopback and optional RFC1918 LAN permits.
/// 3. Split-tunnel bypass permits for excluded executables (games/clients/streaming apps) and dynamically resolved streaming/game IPs.
/// 4. Always-on full-traffic enforcement with fail-closed kill switch.
/// </summary>
public sealed class WfpFirewallService : IDisposable
{
    private static readonly Guid SubLayerGuid = new("d4c9a1e2-6b73-4f88-9a21-3e5c8b0f7a11");
    private nint _engine;
    private int _disposed;
    private readonly object _engineLock = new();
    private readonly ConcurrentDictionary<string, byte> _permittedRemoteIps = new();

    public bool IsActive => _engine != 0 && Volatile.Read(ref _disposed) == 0;

    public void ApplyPolicy(
        ulong tunnelLuid,
        bool killSwitchEnabled,
        bool lanBypassEnabled,
        IReadOnlyCollection<string> excludedExecutables)
    {
        lock (_engineLock)
        {
            ClearPolicyInternal();
            _permittedRemoteIps.Clear();

            var session = new WfpNative.FwpmSession0
            {
                Flags = WfpNative.FwpmSessionFlagDynamic,
                DisplayData = new WfpNative.FwpmDisplayData0
                {
                    Name = "xPrivacyGuard Dynamic WFP Session",
                    Description = "Always-on VPN kill switch and split-tunnel exclusions"
                }
            };

            var rc = WfpNative.FwpmEngineOpen0(null, WfpNative.RpcCAuthnWinnt, 0, ref session, out _engine);
            if (rc != 0)
                throw new InvalidOperationException($"FwpmEngineOpen0 failed with Win32 status 0x{rc:X8}. Run as Administrator.");

            rc = WfpNative.FwpmTransactionBegin0(_engine, 0);
            if (rc != 0)
            {
                ClearPolicyInternal();
                throw new InvalidOperationException($"FwpmTransactionBegin0 failed with status 0x{rc:X8}.");
            }

            try
            {
                var subLayer = new WfpNative.FwpmSublayer0
                {
                    SubLayerKey = SubLayerGuid,
                    DisplayData = new WfpNative.FwpmDisplayData0
                    {
                        Name = "xPrivacyGuard Policy Sublayer",
                        Description = "Enforces always-on WARP MASQUE and split-tunnel exclusions"
                    },
                    Weight = 0xFFFF
                };
                rc = WfpNative.FwpmSubLayerAdd0(_engine, ref subLayer, 0);
                if (rc != 0) throw new InvalidOperationException($"FwpmSubLayerAdd0 failed (0x{rc:X8}).");

                // 1. Always permit our own process (Aether MASQUE transport sockets)
                var selfPath = Environment.ProcessPath;
                if (!string.IsNullOrWhiteSpace(selfPath) && File.Exists(selfPath))
                {
                    AddAppFilter("xPrivacyGuard Self Permit V4", WfpNative.FwpmLayerAleAuthConnectV4, selfPath, WfpNative.FwpActionPermit, weight: 15);
                    AddAppFilter("xPrivacyGuard Self Permit V6", WfpNative.FwpmLayerAleAuthConnectV6, selfPath, WfpNative.FwpActionPermit, weight: 15);
                }

                // 2. Always permit loopback (IPv4 127.0.0.0/8 AND IPv6 ::1/128)
                AddSubnetV4Filter("xPrivacyGuard Loopback Permit V4", 0x7F000000u, 0xFF000000u, WfpNative.FwpActionPermit, weight: 14);
                AddSubnetV6Filter("xPrivacyGuard Loopback Permit V6", IPAddress.IPv6Loopback, 128, WfpNative.FwpActionPermit, weight: 14);

                // 2b. Always permit DHCPv4 (UDP 68->67) and DHCPv6 (UDP 546->547) so physical NICs
                //     can renew leases after sleep/resume even while the fail-closed kill switch is active.
                AddUdpPortPairFilter("xPrivacyGuard DHCPv4 Permit", WfpNative.FwpmLayerAleAuthConnectV4, localPort: 68, remotePort: 67, WfpNative.FwpActionPermit, weight: 14);
                AddUdpPortPairFilter("xPrivacyGuard DHCPv6 Permit", WfpNative.FwpmLayerAleAuthConnectV6, localPort: 546, remotePort: 547, WfpNative.FwpActionPermit, weight: 14);

                // 3. Permit LAN subnets, link-local (Bluetooth PAN / Wi-Fi Direct), multicast (mDNS/SSDP/CDP),
                //    and broadcast when LAN bypass is enabled so local devices & Windows Phone Link work directly.
                if (lanBypassEnabled)
                {
                    AddSubnetV4Filter("xPrivacyGuard LAN 192.168/16", 0xC0A80000u, 0xFFFF0000u, WfpNative.FwpActionPermit, weight: 13);
                    AddSubnetV4Filter("xPrivacyGuard LAN 10/8", 0x0A000000u, 0xFF000000u, WfpNative.FwpActionPermit, weight: 13);
                    AddSubnetV4Filter("xPrivacyGuard LAN 172.16/12", 0xAC100000u, 0xFFF00000u, WfpNative.FwpActionPermit, weight: 13);
                    AddSubnetV4Filter("xPrivacyGuard LinkLocal 169.254/16", 0xA9FE0000u, 0xFFFF0000u, WfpNative.FwpActionPermit, weight: 13);
                    AddSubnetV4Filter("xPrivacyGuard Multicast 224/4", 0xE0000000u, 0xF0000000u, WfpNative.FwpActionPermit, weight: 13);
                    AddSubnetV4Filter("xPrivacyGuard Broadcast 255.255.255.255/32", 0xFFFFFFFFu, 0xFFFFFFFFu, WfpNative.FwpActionPermit, weight: 13);
                    AddSubnetV6Filter("xPrivacyGuard LAN fe80::/10", IPAddress.Parse("fe80::"), 10, WfpNative.FwpActionPermit, weight: 13);
                    AddSubnetV6Filter("xPrivacyGuard LAN fc00::/7", IPAddress.Parse("fc00::"), 7, WfpNative.FwpActionPermit, weight: 13);
                    AddSubnetV6Filter("xPrivacyGuard Multicast ff00::/8", IPAddress.Parse("ff00::"), 8, WfpNative.FwpActionPermit, weight: 13);
                }

                // 4. Split-tunnel exclusions for user-excluded games, gaming clients, and streaming apps
                foreach (var exePath in excludedExecutables)
                {
                    if (string.IsNullOrWhiteSpace(exePath) || !File.Exists(exePath)) continue;
                    var label = Path.GetFileName(exePath);
                    AddAppFilter($"xPrivacyGuard Bypass {label} V4", WfpNative.FwpmLayerAleAuthConnectV4, exePath, WfpNative.FwpActionPermit, weight: 12);
                    AddAppFilter($"xPrivacyGuard Bypass {label} V6", WfpNative.FwpmLayerAleAuthConnectV6, exePath, WfpNative.FwpActionPermit, weight: 12);
                }

                // 5. Permit traffic going out of Wintun interface
                if (tunnelLuid != 0)
                {
                    AddInterfaceFilter("xPrivacyGuard Tunnel Permit V4", WfpNative.FwpmLayerAleAuthConnectV4, tunnelLuid, WfpNative.FwpActionPermit, weight: 10);
                    AddInterfaceFilter("xPrivacyGuard Tunnel Permit V6", WfpNative.FwpmLayerAleAuthConnectV6, tunnelLuid, WfpNative.FwpActionPermit, weight: 10);
                }

                // 6. Always-on fail-closed kill switch blocks non-tunnel outbound traffic (unless explicitly excluded above)
                if (killSwitchEnabled)
                {
                    AddCatchAllFilter("xPrivacyGuard KillSwitch Block V4", WfpNative.FwpmLayerAleAuthConnectV4, WfpNative.FwpActionBlock, weight: 1);
                    AddCatchAllFilter("xPrivacyGuard KillSwitch Block V6", WfpNative.FwpmLayerAleAuthConnectV6, WfpNative.FwpActionBlock, weight: 1);
                }

                rc = WfpNative.FwpmTransactionCommit0(_engine);
                if (rc != 0) throw new InvalidOperationException($"FwpmTransactionCommit0 failed (0x{rc:X8}).");
            }
            catch
            {
                WfpNative.FwpmTransactionAbort0(_engine);
                ClearPolicyInternal();
                throw;
            }
        }
    }

    public void PermitDynamicBypassIpv4(string ipv4)
    {
        if (Volatile.Read(ref _disposed) != 0) return;
        if (!IPAddress.TryParse(ipv4, out var ip) || ip.AddressFamily != AddressFamily.InterNetwork) return;
        if (!_permittedRemoteIps.TryAdd(ipv4, 0)) return;

        var bytes = ip.GetAddressBytes();
        uint hostOrder = ((uint)bytes[0] << 24) | ((uint)bytes[1] << 16) | ((uint)bytes[2] << 8) | bytes[3];

        lock (_engineLock)
        {
            if (_engine == 0) return;
            try
            {
                AddSubnetV4Filter($"xPrivacyGuard SplitIP {ipv4}", hostOrder, 0xFFFFFFFFu, WfpNative.FwpActionPermit, weight: 11);
            }
            catch { /* Best-effort dynamic permit. */ }
        }
    }

    public void ClearPolicy()
    {
        lock (_engineLock)
        {
            ClearPolicyInternal();
            _permittedRemoteIps.Clear();
        }
    }

    private void ClearPolicyInternal()
    {
        var handle = Interlocked.Exchange(ref _engine, 0);
        if (handle != 0)
        {
            try { WfpNative.FwpmEngineClose0(handle); } catch { }
        }
    }

    public void Dispose()
    {
        if (Interlocked.Exchange(ref _disposed, 1) == 0)
            ClearPolicy();
    }

    private void AddAppFilter(string name, Guid layer, string exePath, uint action, byte weight)
    {
        var rc = WfpNative.FwpmGetAppIdFromFileName0(exePath, out var appIdPtr);
        if (rc != 0 || appIdPtr == 0) return;
        try
        {
            var cond = new WfpNative.FwpmFilterCondition0
            {
                FieldKey = WfpNative.FwpmConditionAleAppId,
                MatchType = WfpNative.FwpMatchEqual,
                ConditionValue = new WfpNative.FwpValue0
                {
                    Type = WfpNative.FwpByteBlobType,
                    ByteBlobPtr = appIdPtr
                }
            };
            AddFilterWithConditions(name, layer, action, weight, [cond]);
        }
        finally
        {
            WfpNative.FwpmFreeMemory0(ref appIdPtr);
        }
    }

    private void AddInterfaceFilter(string name, Guid layer, ulong luid, uint action, byte weight)
    {
        var luidPtr = Marshal.AllocHGlobal(sizeof(ulong));
        try
        {
            Marshal.WriteInt64(luidPtr, unchecked((long)luid));
            var cond = new WfpNative.FwpmFilterCondition0
            {
                FieldKey = WfpNative.FwpmConditionIpLocalInterface,
                MatchType = WfpNative.FwpMatchEqual,
                ConditionValue = new WfpNative.FwpValue0
                {
                    Type = WfpNative.FwpUint64,
                    Uint64Ptr = luidPtr
                }
            };
            AddFilterWithConditions(name, layer, action, weight, [cond]);
        }
        finally
        {
            Marshal.FreeHGlobal(luidPtr);
        }
    }

    private void AddSubnetV4Filter(string name, uint addr, uint mask, uint action, byte weight)
    {
        var addrMaskPtr = Marshal.AllocHGlobal(Marshal.SizeOf<WfpNative.FwpV4AddrAndMask>());
        try
        {
            Marshal.StructureToPtr(new WfpNative.FwpV4AddrAndMask { Addr = addr, Mask = mask }, addrMaskPtr, false);
            var cond = new WfpNative.FwpmFilterCondition0
            {
                FieldKey = WfpNative.FwpmConditionIpRemoteAddress,
                MatchType = WfpNative.FwpMatchEqual,
                ConditionValue = new WfpNative.FwpValue0
                {
                    Type = WfpNative.FwpV4AddrMask,
                    V4AddrMaskPtr = addrMaskPtr
                }
            };
            AddFilterWithConditions(name, WfpNative.FwpmLayerAleAuthConnectV4, action, weight, [cond]);
        }
        finally
        {
            Marshal.FreeHGlobal(addrMaskPtr);
        }
    }

    private unsafe void AddSubnetV6Filter(string name, IPAddress address, byte prefixLength, uint action, byte weight)
    {
        var addrMaskPtr = Marshal.AllocHGlobal(Marshal.SizeOf<WfpNative.FwpV6AddrAndMask>());
        try
        {
            var v6 = new WfpNative.FwpV6AddrAndMask { PrefixLength = prefixLength };
            var bytes = address.GetAddressBytes();
            for (var i = 0; i < 16 && i < bytes.Length; i++)
                v6.Addr[i] = bytes[i];
            Marshal.StructureToPtr(v6, addrMaskPtr, false);

            var cond = new WfpNative.FwpmFilterCondition0
            {
                FieldKey = WfpNative.FwpmConditionIpRemoteAddress,
                MatchType = WfpNative.FwpMatchEqual,
                ConditionValue = new WfpNative.FwpValue0
                {
                    Type = WfpNative.FwpV6AddrMask,
                    V6AddrMaskPtr = addrMaskPtr
                }
            };
            AddFilterWithConditions(name, WfpNative.FwpmLayerAleAuthConnectV6, action, weight, [cond]);
        }
        finally
        {
            Marshal.FreeHGlobal(addrMaskPtr);
        }
    }

    private void AddCatchAllFilter(string name, Guid layer, uint action, byte weight)
        => AddFilterWithConditions(name, layer, action, weight, []);

    private void AddUdpPortPairFilter(string name, Guid layer, ushort localPort, ushort remotePort, uint action, byte weight)
    {
        WfpNative.FwpmFilterCondition0[] conditions =
        [
            new()
            {
                FieldKey = WfpNative.FwpmConditionIpProtocol,
                MatchType = WfpNative.FwpMatchEqual,
                ConditionValue = new WfpNative.FwpValue0 { Type = WfpNative.FwpUint8, Uint8 = WfpNative.IpProtoUdp }
            },
            new()
            {
                FieldKey = WfpNative.FwpmConditionIpLocalPort,
                MatchType = WfpNative.FwpMatchEqual,
                ConditionValue = new WfpNative.FwpValue0 { Type = WfpNative.FwpUint16, Uint16 = localPort }
            },
            new()
            {
                FieldKey = WfpNative.FwpmConditionIpRemotePort,
                MatchType = WfpNative.FwpMatchEqual,
                ConditionValue = new WfpNative.FwpValue0 { Type = WfpNative.FwpUint16, Uint16 = remotePort }
            }
        ];
        AddFilterWithConditions(name, layer, action, weight, conditions);
    }

    private void AddFilterWithConditions(string name, Guid layer, uint action, byte weight, WfpNative.FwpmFilterCondition0[] conditions)
    {
        var condPtr = nint.Zero;
        if (conditions.Length > 0)
        {
            var size = Marshal.SizeOf<WfpNative.FwpmFilterCondition0>();
            condPtr = Marshal.AllocHGlobal(size * conditions.Length);
            for (var i = 0; i < conditions.Length; i++)
                Marshal.StructureToPtr(conditions[i], condPtr + i * size, false);
        }

        try
        {
            var filter = new WfpNative.FwpmFilter0
            {
                FilterKey = Guid.NewGuid(),
                DisplayData = new WfpNative.FwpmDisplayData0 { Name = name },
                LayerKey = layer,
                SubLayerKey = SubLayerGuid,
                Weight = new WfpNative.FwpValue0 { Type = WfpNative.FwpUint8, Uint8 = weight },
                NumFilterConditions = (uint)conditions.Length,
                FilterCondition = condPtr,
                Action = new WfpNative.FwpmAction0 { Type = action }
            };
            var rc = WfpNative.FwpmFilterAdd0(_engine, ref filter, 0, out _);
            if (rc != 0) throw new InvalidOperationException($"FwpmFilterAdd0('{name}') failed (0x{rc:X8}).");
        }
        finally
        {
            if (condPtr != 0) Marshal.FreeHGlobal(condPtr);
        }
    }
}
