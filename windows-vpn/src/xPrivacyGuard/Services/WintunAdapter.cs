using System.ComponentModel;
using System.Diagnostics;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using xPrivacyGuard.Native;

namespace xPrivacyGuard.Services;

public sealed class WintunAdapter : IDisposable
{
    private const string AdapterName = "xPrivacyGuard";
    private const string TunnelType = "xPrivacyGuard MASQUE";
    private static readonly Guid AdapterGuid = new("8b3c17a4-5e41-4c9d-9f12-71c8a5e0d419");

    private nint _adapter;
    private nint _session;
    private nint _readWaitEvent;
    private int _disposed;
    private readonly List<string> _addedRouteCommands = [];
    private readonly System.Collections.Concurrent.ConcurrentDictionary<string, byte> _dynamicBypassIps = new();
    private readonly object _routeLock = new();

    public ulong InterfaceLuid { get; private set; }
    public int InterfaceIndex { get; private set; }
    public string? PhysicalGateway { get; private set; }
    public int PhysicalInterfaceIndex { get; private set; }

    public void Open(string ipv4Cidr, string? ipv6Cidr, int mtu, string peerEndpoint, bool lanBypass, IReadOnlyList<string> dnsServers)
    {
        var guid = AdapterGuid;
        _adapter = WintunNative.WintunOpenAdapter(AdapterName);
        if (_adapter == 0)
        {
            _adapter = WintunNative.WintunCreateAdapter(AdapterName, TunnelType, ref guid);
            if (_adapter == 0)
            {
                var err = Marshal.GetLastWin32Error();
                throw new Win32Exception(err, $"Failed to create Wintun adapter (Win32 error {err}). Ensure xPrivacyGuard is running as Administrator and wintun.dll is present.");
            }
        }

        WintunNative.WintunGetAdapterLUID(_adapter, out var luid);
        InterfaceLuid = luid;

        _session = WintunNative.WintunStartSession(_adapter, 0x400000); // 4 MiB ring buffer
        if (_session == 0)
        {
            var err = Marshal.GetLastWin32Error();
            Dispose();
            throw new Win32Exception(err, $"Failed to start Wintun session (Win32 error {err}).");
        }

        _readWaitEvent = WintunNative.WintunGetReadWaitEvent(_session);

        DiscoverPhysicalGateway();
        if (string.IsNullOrEmpty(PhysicalGateway) || PhysicalInterfaceIndex <= 0)
        {
            Dispose();
            throw new InvalidOperationException("No active physical network interface with an IPv4 gateway is available yet.");
        }

        ConfigureInterface(ipv4Cidr, ipv6Cidr, mtu, peerEndpoint, lanBypass, dnsServers);
    }

    public int ReceivePacket(byte[] buffer, uint timeoutMs = 200)
    {
        if (Volatile.Read(ref _disposed) != 0 || _session == 0) return -1;

        var pktPtr = WintunNative.WintunReceivePacket(_session, out var pktSize);
        if (pktPtr == 0)
        {
            var err = Marshal.GetLastWin32Error();
            if (err == WintunNative.ErrorNoMoreItems)
            {
                if (_readWaitEvent != 0)
                    WintunNative.WaitForSingleObject(_readWaitEvent, timeoutMs);
                return 0;
            }
            return -1;
        }

        try
        {
            var copyLen = (int)Math.Min(pktSize, (uint)buffer.Length);
            Marshal.Copy(pktPtr, buffer, 0, copyLen);
            return copyLen;
        }
        finally
        {
            WintunNative.WintunReleaseReceivePacket(_session, pktPtr);
        }
    }

    public bool SendPacket(byte[] packet, int length)
    {
        if (Volatile.Read(ref _disposed) != 0 || _session == 0 || length <= 0) return false;
        var ptr = WintunNative.WintunAllocateSendPacket(_session, (uint)length);
        if (ptr == 0) return false;
        Marshal.Copy(packet, 0, ptr, length);
        WintunNative.WintunSendPacket(_session, ptr);
        return true;
    }

    public static bool HasActivePhysicalNetwork()
        => TryGetActivePhysicalNetwork(out _, out _);

    public static bool TryGetActivePhysicalNetwork(out string? gateway, out int interfaceIndex)
    {
        gateway = null;
        interfaceIndex = 0;

        string? fallbackGateway = null;
        int fallbackIfIndex = 0;

        try
        {
            foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (nic.OperationalStatus != OperationalStatus.Up) continue;
                if (nic.Name.Equals(AdapterName, StringComparison.OrdinalIgnoreCase)) continue;
                if (nic.NetworkInterfaceType is NetworkInterfaceType.Loopback or NetworkInterfaceType.Tunnel) continue;

                var props = nic.GetIPProperties();
                var v4Props = props.GetIPv4Properties();
                if (v4Props is null || v4Props.Index <= 0) continue;

                var hasUnicastV4 = props.UnicastAddresses.Any(u =>
                {
                    if (u.Address.AddressFamily != AddressFamily.InterNetwork || IPAddress.IsLoopback(u.Address))
                        return false;
                    var b = u.Address.GetAddressBytes();
                    return !(b[0] == 169 && b[1] == 254);
                });
                if (!hasUnicastV4) continue;

                var gw = props.GatewayAddresses
                    .Select(g => g.Address)
                    .FirstOrDefault(a => a.AddressFamily == AddressFamily.InterNetwork && !IPAddress.Any.Equals(a));
                if (gw is null) continue;

                var desc = nic.Description ?? "";
                var isVirtual = desc.Contains("Hyper-V", StringComparison.OrdinalIgnoreCase)
                                || desc.Contains("Virtual", StringComparison.OrdinalIgnoreCase)
                                || desc.Contains("WSL", StringComparison.OrdinalIgnoreCase)
                                || desc.Contains("TAP-", StringComparison.OrdinalIgnoreCase)
                                || desc.Contains("Wintun", StringComparison.OrdinalIgnoreCase)
                                || desc.Contains("Bluetooth", StringComparison.OrdinalIgnoreCase);

                if (!isVirtual && nic.NetworkInterfaceType is NetworkInterfaceType.Ethernet
                        or NetworkInterfaceType.Wireless80211
                        or NetworkInterfaceType.GigabitEthernet
                        or NetworkInterfaceType.FastEthernetT
                        or NetworkInterfaceType.FastEthernetFx)
                {
                    gateway = gw.ToString();
                    interfaceIndex = v4Props.Index;
                    return true;
                }

                if (fallbackGateway is null)
                {
                    fallbackGateway = gw.ToString();
                    fallbackIfIndex = v4Props.Index;
                }
            }
        }
        catch { /* Best-effort NIC enumeration during sleep/resume transitions. */ }

        if (fallbackGateway is not null && fallbackIfIndex > 0)
        {
            gateway = fallbackGateway;
            interfaceIndex = fallbackIfIndex;
            return true;
        }

        return false;
    }

    private void DiscoverPhysicalGateway()
    {
        if (TryGetActivePhysicalNetwork(out var gw, out var ifIndex))
        {
            PhysicalGateway = gw;
            PhysicalInterfaceIndex = ifIndex;
        }
        else
        {
            PhysicalGateway = null;
            PhysicalInterfaceIndex = 0;
        }
    }

    private void ConfigureInterface(
        string ipv4Cidr,
        string? ipv6Cidr,
        int mtu,
        string peerEndpoint,
        bool lanBypass,
        IReadOnlyList<string> dnsServers)
    {
        var (v4Ip, v4Prefix) = ParseCidr(ipv4Cidr, 32);
        var v4Mask = PrefixToMaskV4(v4Prefix);

        // Configure IPv4 address & MTU on Wintun adapter
        RunNetsh($"interface ipv4 set address name=\"{AdapterName}\" static {v4Ip} {v4Mask} store=active");
        RunNetsh($"interface ipv4 set subinterface \"{AdapterName}\" mtu={mtu} store=active");

        // Configure DNS on Wintun adapter
        if (dnsServers.Count > 0)
        {
            RunNetsh($"interface ipv4 set dns name=\"{AdapterName}\" static {dnsServers[0]} validate=no");
            for (var i = 1; i < dnsServers.Count; i++)
                RunNetsh($"interface ipv4 add dns name=\"{AdapterName}\" {dnsServers[i]} index={i + 1} validate=no");
        }
        else
        {
            RunNetsh($"interface ipv4 set dns name=\"{AdapterName}\" static 1.1.1.1 validate=no");
        }

        // Lookup Wintun interface index
        foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (nic.Name.Equals(AdapterName, StringComparison.OrdinalIgnoreCase))
            {
                InterfaceIndex = nic.GetIPProperties().GetIPv4Properties()?.Index ?? 0;
                break;
            }
        }

        // 1. Pin Cloudflare MASQUE peer IP via physical gateway BEFORE installing tunnel routes (prevents loop)
        var peerIp = ExtractIpFromEndpoint(peerEndpoint);
        if (!string.IsNullOrEmpty(peerIp) && !string.IsNullOrEmpty(PhysicalGateway) && PhysicalInterfaceIndex > 0)
        {
            AddRouteV4($"{peerIp}/32", PhysicalGateway, PhysicalInterfaceIndex, 5);
        }

        // 2. Preserve LAN, link-local (Bluetooth PAN / Wi-Fi Direct), multicast (mDNS/SSDP/CDP),
        //    and broadcast routes via the physical interface when LAN bypass is enabled
        if (lanBypass && !string.IsNullOrEmpty(PhysicalGateway) && PhysicalInterfaceIndex > 0)
        {
            AddRouteV4("192.168.0.0/16", PhysicalGateway, PhysicalInterfaceIndex, 5);
            AddRouteV4("10.0.0.0/8", PhysicalGateway, PhysicalInterfaceIndex, 5);
            AddRouteV4("172.16.0.0/12", PhysicalGateway, PhysicalInterfaceIndex, 5);
            AddRouteV4("169.254.0.0/16", "0.0.0.0", PhysicalInterfaceIndex, 5);
            AddRouteV4("224.0.0.0/4", "0.0.0.0", PhysicalInterfaceIndex, 5);
            AddRouteV4("255.255.255.255/32", "0.0.0.0", PhysicalInterfaceIndex, 5);
        }

        // 3. Install split-half default routes (0.0.0.0/1 + 128.0.0.0/1) through Wintun
        if (InterfaceIndex > 0)
        {
            AddRouteV4("0.0.0.0/1", "0.0.0.0", InterfaceIndex, 5);
            AddRouteV4("128.0.0.0/1", "0.0.0.0", InterfaceIndex, 5);
        }

        if (!string.IsNullOrWhiteSpace(ipv6Cidr))
        {
            var (v6Ip, v6Prefix) = ParseCidr(ipv6Cidr, 128);
            RunNetsh($"interface ipv6 set address interface=\"{AdapterName}\" address={v6Ip}/{v6Prefix} store=active");
            RunNetsh($"interface ipv6 set subinterface \"{AdapterName}\" mtu={mtu} store=active");
            if (lanBypass && PhysicalInterfaceIndex > 0)
            {
                AddRouteV6("ff00::/8", "::", PhysicalInterfaceIndex, 5);
            }
            if (InterfaceIndex > 0)
            {
                AddRouteV6("::/1", "::", InterfaceIndex, 5);
                AddRouteV6("8000::/1", "::", InterfaceIndex, 5);
            }
        }
    }

    public bool AddDynamicBypassIp(string ipv4)
    {
        if (Volatile.Read(ref _disposed) != 0) return false;
        if (string.IsNullOrEmpty(PhysicalGateway) || PhysicalInterfaceIndex <= 0) return false;
        if (!IPAddress.TryParse(ipv4, out var addr) || addr.AddressFamily != AddressFamily.InterNetwork) return false;
        if (!_dynamicBypassIps.TryAdd(ipv4, 0)) return false;

        lock (_routeLock)
        {
            if (Volatile.Read(ref _disposed) != 0) return false;
            AddRouteV4($"{ipv4}/32", PhysicalGateway, PhysicalInterfaceIndex, 5);
        }
        return true;
    }

    private void AddRouteV4(string prefix, string nextHop, int ifIndex, int metric)
    {
        RunNetsh($"interface ipv4 add route {prefix} interface={ifIndex} nexthop={nextHop} metric={metric} store=active");
        _addedRouteCommands.Add($"interface ipv4 delete route {prefix} interface={ifIndex} nexthop={nextHop} store=active");
    }

    private void AddRouteV6(string prefix, string nextHop, int ifIndex, int metric)
    {
        RunNetsh($"interface ipv6 add route {prefix} interface={ifIndex} nexthop={nextHop} metric={metric} store=active");
        _addedRouteCommands.Add($"interface ipv6 delete route {prefix} interface={ifIndex} nexthop={nextHop} store=active");
    }

    private static void RunNetsh(string args)
    {
        try
        {
            using var proc = Process.Start(new ProcessStartInfo
            {
                FileName = "netsh.exe",
                Arguments = args,
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true
            });
            proc?.WaitForExit(5000);
        }
        catch { /* Best-effort netsh call. */ }
    }

    private static (string Ip, int Prefix) ParseCidr(string cidr, int defaultPrefix)
    {
        var trimmed = cidr.Trim();
        var slash = trimmed.IndexOf('/');
        if (slash < 0) return (trimmed, defaultPrefix);
        var ip = trimmed[..slash];
        return int.TryParse(trimmed[(slash + 1)..], out var prefix) ? (ip, prefix) : (ip, defaultPrefix);
    }

    private static string PrefixToMaskV4(int prefix)
    {
        uint mask = prefix == 0 ? 0 : 0xFFFFFFFFu << (32 - Math.Clamp(prefix, 0, 32));
        return $"{(mask >> 24) & 0xFF}.{(mask >> 16) & 0xFF}.{(mask >> 8) & 0xFF}.{mask & 0xFF}";
    }

    private static string? ExtractIpFromEndpoint(string endpoint)
    {
        var raw = endpoint.Trim();
        if (raw.StartsWith('['))
        {
            var end = raw.IndexOf(']');
            return end > 1 ? raw[1..end] : null;
        }
        var colon = raw.IndexOf(':');
        var host = colon > 0 ? raw[..colon] : raw;
        return IPAddress.TryParse(host, out var ip) && ip.AddressFamily == AddressFamily.InterNetwork ? host : null;
    }

    public void Dispose()
    {
        if (Interlocked.Exchange(ref _disposed, 1) != 0) return;

        List<string> deleteCmds;
        lock (_routeLock)
        {
            deleteCmds = [.. _addedRouteCommands];
            _addedRouteCommands.Clear();
        }
        foreach (var deleteCmd in deleteCmds)
            RunNetsh(deleteCmd);

        if (_session != 0)
        {
            try { WintunNative.WintunEndSession(_session); } catch { }
            _session = 0;
        }
        if (_adapter != 0)
        {
            try { WintunNative.WintunCloseAdapter(_adapter); } catch { }
            _adapter = 0;
        }
    }
}
