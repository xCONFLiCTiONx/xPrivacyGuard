using System.Buffers.Binary;
using System.Text;

namespace xPrivacyGuard.Services;

public sealed record DnsQuestion(string QName, ushort QType, ushort QClass, int QuestionEndOffset);

public static class PacketInspector
{
    private const byte ProtoUdp = 17;

    public static (string QName, byte[] DnsQueryPayload)? ParseOutboundDnsQuery(byte[] packet, int length)
    {
        if (length < 40 || length > packet.Length) return null;
        try
        {
            var version = (packet[0] >> 4) & 0x0F;
            int udpOffset;
            if (version == 4)
            {
                var ihl = (packet[0] & 0x0F) * 4;
                if (ihl < 20 || length < ihl + 20 || packet[9] != ProtoUdp) return null;
                udpOffset = ihl;
            }
            else if (version == 6)
            {
                if (length < 60 || packet[6] != ProtoUdp) return null;
                udpOffset = 40;
            }
            else return null;

            var dstPort = BinaryPrimitives.ReadUInt16BigEndian(packet.AsSpan(udpOffset + 2, 2));
            if (dstPort != 53) return null;

            var dnsOffset = udpOffset + 8;
            var dnsLen = length - dnsOffset;
            if (dnsLen < 17) return null;

            var q = ParseDnsQuestion(packet, dnsOffset, dnsLen);
            if (q is null) return null;

            var rawDns = new byte[dnsLen];
            Buffer.BlockCopy(packet, dnsOffset, rawDns, 0, dnsLen);
            return (q.QName, rawDns);
        }
        catch
        {
            return null;
        }
    }

    public static (string QName, List<string> Ipv4Answers)? ParseInboundDnsResponse(byte[] packet, int length)
    {
        if (length < 40 || length > packet.Length) return null;
        try
        {
            var version = (packet[0] >> 4) & 0x0F;
            int udpOffset;
            if (version == 4)
            {
                var ihl = (packet[0] & 0x0F) * 4;
                if (ihl < 20 || length < ihl + 20 || packet[9] != ProtoUdp) return null;
                udpOffset = ihl;
            }
            else if (version == 6)
            {
                if (length < 60 || packet[6] != ProtoUdp) return null;
                udpOffset = 40;
            }
            else return null;

            var srcPort = BinaryPrimitives.ReadUInt16BigEndian(packet.AsSpan(udpOffset, 2));
            if (srcPort != 53) return null;

            var dnsOffset = udpOffset + 8;
            var dnsLen = length - dnsOffset;
            if (dnsLen < 17) return null;
            if ((packet[dnsOffset + 2] & 0x80) == 0) return null;

            var q = ParseDnsQuestion(packet, dnsOffset, dnsLen);
            if (q is null) return null;

            var dnsPayload = new byte[dnsLen];
            Buffer.BlockCopy(packet, dnsOffset, dnsPayload, 0, dnsLen);
            var ips = ExtractIpv4AnswersFromDnsResponse(dnsPayload);
            return ips.Count > 0 ? (q.QName, ips) : null;
        }
        catch
        {
            return null;
        }
    }

    public static byte[]? BuildDnsReplyFromUpstream(byte[] originalPacket, int length, byte[] dnsResponsePayload)
    {
        if (length < 28) return null;
        var version = (originalPacket[0] >> 4) & 0x0F;
        if (version == 4)
        {
            var ihl = (originalPacket[0] & 0x0F) * 4;
            if (length < ihl + 8) return null;
            var srcPort = BinaryPrimitives.ReadUInt16BigEndian(originalPacket.AsSpan(ihl, 2));
            var dstPort = BinaryPrimitives.ReadUInt16BigEndian(originalPacket.AsSpan(ihl + 2, 2));
            return BuildIpv4UdpReply(originalPacket, srcPort, dstPort, dnsResponsePayload);
        }
        if (version == 6)
        {
            const int ipv6HeaderLen = 40;
            if (length < ipv6HeaderLen + 8) return null;
            var srcPort = BinaryPrimitives.ReadUInt16BigEndian(originalPacket.AsSpan(ipv6HeaderLen, 2));
            var dstPort = BinaryPrimitives.ReadUInt16BigEndian(originalPacket.AsSpan(ipv6HeaderLen + 2, 2));
            return BuildIpv6UdpReply(originalPacket, srcPort, dstPort, dnsResponsePayload);
        }
        return null;
    }

    public static DnsQuestion? ParseDnsQuestion(byte[] packet, int dnsOffset, int dnsLen)
    {
        if (dnsLen < 17) return null;
        var qdCount = BinaryPrimitives.ReadUInt16BigEndian(packet.AsSpan(dnsOffset + 4, 2));
        if (qdCount == 0) return null;

        var pos = dnsOffset + 12;
        var end = dnsOffset + dnsLen;
        var sb = new StringBuilder(64);
        while (pos < end)
        {
            var labelLen = packet[pos++];
            if (labelLen == 0) break;
            if ((labelLen & 0xC0) != 0 || pos + labelLen > end) return null;
            if (sb.Length > 0) sb.Append('.');
            sb.Append(Encoding.ASCII.GetString(packet, pos, labelLen));
            pos += labelLen;
        }
        if (pos + 4 > end || sb.Length == 0) return null;
        var qtype = BinaryPrimitives.ReadUInt16BigEndian(packet.AsSpan(pos, 2));
        var qclass = BinaryPrimitives.ReadUInt16BigEndian(packet.AsSpan(pos + 2, 2));
        return new DnsQuestion(sb.ToString(), qtype, qclass, pos + 4 - dnsOffset);
    }

    public static List<string> ExtractIpv4AnswersFromDnsResponse(byte[] dnsPayload)
    {
        var ips = new List<string>();
        if (dnsPayload.Length < 12) return ips;
        var qdCount = BinaryPrimitives.ReadUInt16BigEndian(dnsPayload.AsSpan(4, 2));
        var anCount = BinaryPrimitives.ReadUInt16BigEndian(dnsPayload.AsSpan(6, 2));
        if (anCount == 0) return ips;

        var pos = 12;
        for (var i = 0; i < qdCount && pos < dnsPayload.Length; i++)
        {
            pos = SkipDnsName(dnsPayload, pos);
            pos += 4;
        }

        for (var i = 0; i < anCount && pos + 10 <= dnsPayload.Length; i++)
        {
            pos = SkipDnsName(dnsPayload, pos);
            if (pos + 10 > dnsPayload.Length) break;
            var rtype = BinaryPrimitives.ReadUInt16BigEndian(dnsPayload.AsSpan(pos, 2));
            var rdLen = BinaryPrimitives.ReadUInt16BigEndian(dnsPayload.AsSpan(pos + 8, 2));
            pos += 10;
            if (pos + rdLen > dnsPayload.Length) break;
            if (rtype == 1 && rdLen == 4)
                ips.Add($"{dnsPayload[pos]}.{dnsPayload[pos + 1]}.{dnsPayload[pos + 2]}.{dnsPayload[pos + 3]}");
            pos += rdLen;
        }
        return ips;
    }

    private static int SkipDnsName(byte[] buf, int pos)
    {
        while (pos < buf.Length)
        {
            var len = buf[pos];
            if (len == 0) return pos + 1;
            if ((len & 0xC0) == 0xC0) return pos + 2;
            pos += 1 + len;
        }
        return pos;
    }

    private static byte[] BuildIpv4UdpReply(byte[] orig, ushort origSrcPort, ushort origDstPort, byte[] udpPayload)
    {
        var udpLen = 8 + udpPayload.Length;
        var totalLen = 20 + udpLen;
        var reply = new byte[totalLen];
        reply[0] = 0x45;
        BinaryPrimitives.WriteUInt16BigEndian(reply.AsSpan(2, 2), (ushort)totalLen);
        reply[8] = 64;
        reply[9] = ProtoUdp;
        Buffer.BlockCopy(orig, 16, reply, 12, 4);
        Buffer.BlockCopy(orig, 12, reply, 16, 4);
        BinaryPrimitives.WriteUInt16BigEndian(reply.AsSpan(10, 2), ComputeChecksum(reply, 0, 20));

        BinaryPrimitives.WriteUInt16BigEndian(reply.AsSpan(20, 2), origDstPort);
        BinaryPrimitives.WriteUInt16BigEndian(reply.AsSpan(22, 2), origSrcPort);
        BinaryPrimitives.WriteUInt16BigEndian(reply.AsSpan(24, 2), (ushort)udpLen);
        Buffer.BlockCopy(udpPayload, 0, reply, 28, udpPayload.Length);
        BinaryPrimitives.WriteUInt16BigEndian(reply.AsSpan(26, 2), ComputeTransportChecksumV4(reply, 20, udpLen, ProtoUdp));
        return reply;
    }

    private static byte[] BuildIpv6UdpReply(byte[] orig, ushort origSrcPort, ushort origDstPort, byte[] udpPayload)
    {
        var udpLen = 8 + udpPayload.Length;
        var reply = new byte[40 + udpLen];
        reply[0] = 0x60;
        BinaryPrimitives.WriteUInt16BigEndian(reply.AsSpan(4, 2), (ushort)udpLen);
        reply[6] = ProtoUdp;
        reply[7] = 64;
        Buffer.BlockCopy(orig, 24, reply, 8, 16);
        Buffer.BlockCopy(orig, 8, reply, 24, 16);

        BinaryPrimitives.WriteUInt16BigEndian(reply.AsSpan(40, 2), origDstPort);
        BinaryPrimitives.WriteUInt16BigEndian(reply.AsSpan(42, 2), origSrcPort);
        BinaryPrimitives.WriteUInt16BigEndian(reply.AsSpan(44, 2), (ushort)udpLen);
        Buffer.BlockCopy(udpPayload, 0, reply, 48, udpPayload.Length);
        BinaryPrimitives.WriteUInt16BigEndian(reply.AsSpan(46, 2), ComputeTransportChecksumV6(reply, 40, udpLen, ProtoUdp));
        return reply;
    }

    private static ushort ComputeChecksum(byte[] data, int offset, int length)
    {
        uint sum = 0;
        var i = offset;
        var end = offset + length;
        while (i + 1 < end)
        {
            sum += (uint)((data[i] << 8) | data[i + 1]);
            i += 2;
        }
        if (i < end) sum += (uint)(data[i] << 8);
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return (ushort)~sum;
    }

    private static ushort ComputeTransportChecksumV4(byte[] packet, int transportOffset, int transportLen, byte proto)
    {
        uint sum = 0;
        for (var i = 12; i < 20; i += 2) sum += (uint)((packet[i] << 8) | packet[i + 1]);
        sum += proto;
        sum += (uint)transportLen;
        sum += PartialSum(packet, transportOffset, transportLen);
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return (ushort)~sum;
    }

    private static ushort ComputeTransportChecksumV6(byte[] packet, int transportOffset, int transportLen, byte proto)
    {
        uint sum = 0;
        for (var i = 8; i < 40; i += 2) sum += (uint)((packet[i] << 8) | packet[i + 1]);
        sum += (uint)transportLen;
        sum += proto;
        sum += PartialSum(packet, transportOffset, transportLen);
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return (ushort)~sum;
    }

    private static uint PartialSum(byte[] data, int offset, int length)
    {
        uint sum = 0;
        var i = offset;
        var end = offset + length;
        while (i + 1 < end)
        {
            sum += (uint)((data[i] << 8) | data[i + 1]);
            i += 2;
        }
        if (i < end) sum += (uint)(data[i] << 8);
        return sum;
    }
}
