using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Net.Sockets;
using xPrivacyGuard.Models;

namespace xPrivacyGuard.Services;

public sealed class ZeroTrustDnsResolver
{
    private static readonly HttpClient Http = new()
    {
        Timeout = TimeSpan.FromSeconds(5)
    };

    public async Task<byte[]?> ResolveWireQueryAsync(
        byte[] dnsQueryPayload,
        string qname,
        ZeroTrustDnsConfig config,
        CancellationToken ct = default)
    {
        // 1. Split / Private DNS (e.g. *.internal.example.com -> local DNS server)
        if (config.IsPrivateDnsConfigured && config.MatchesPrivateDomain(qname))
        {
            var privateResp = await QueryUdpAsync(config.PrivateDnsServer.Trim(), dnsQueryPayload, ct).ConfigureAwait(false);
            if (privateResp is not null) return privateResp;
        }

        // 2. Cloudflare Zero Trust DoH endpoint if configured
        var dohUrl = config.NormalizedDohUrl();
        if (dohUrl is not null)
        {
            var dohResp = await QueryDohAsync(dohUrl, dnsQueryPayload, ct).ConfigureAwait(false);
            if (dohResp is not null) return dohResp;
        }

        // 3. Configured Zero Trust IPv4/IPv6 DNS endpoints
        foreach (var server in config.ConfiguredIpv4List())
        {
            var resp = await QueryUdpAsync(server, dnsQueryPayload, ct).ConfigureAwait(false);
            if (resp is not null) return resp;
        }

        return null;
    }

    private static async Task<byte[]?> QueryDohAsync(string url, byte[] query, CancellationToken ct)
    {
        try
        {
            using var content = new ByteArrayContent(query);
            content.Headers.ContentType = new MediaTypeHeaderValue("application/dns-message");
            using var req = new HttpRequestMessage(HttpMethod.Post, url) { Content = content };
            req.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("application/dns-message"));
            using var resp = await Http.SendAsync(req, ct).ConfigureAwait(false);
            if (!resp.IsSuccessStatusCode) return null;
            return await resp.Content.ReadAsByteArrayAsync(ct).ConfigureAwait(false);
        }
        catch
        {
            return null;
        }
    }

    private static async Task<byte[]?> QueryUdpAsync(string serverAddress, byte[] query, CancellationToken ct)
    {
        try
        {
            if (!IPAddress.TryParse(serverAddress, out var ip)) return null;
            using var udp = new UdpClient(ip.AddressFamily);
            using var timeoutCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            timeoutCts.CancelAfter(TimeSpan.FromSeconds(3));
            await udp.SendAsync(query, new IPEndPoint(ip, 53), timeoutCts.Token).ConfigureAwait(false);
            var result = await udp.ReceiveAsync(timeoutCts.Token).ConfigureAwait(false);
            return result.Buffer;
        }
        catch
        {
            return null;
        }
    }
}
