package com.xconflictionx.xprivacyguard.packet

import com.xconflictionx.xprivacyguard.filter.FilterDecision
import com.xconflictionx.xprivacyguard.filter.FilterEngine
import com.xconflictionx.xprivacyguard.firewall.FirewallManager
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

sealed class PacketAction {
    data object ForwardToWireGuard : PacketAction()
    data class DropAndReply(
        val replyPacket: ByteArray,
        val targetHost: String,
        val protocol: String,
        val decision: FilterDecision,
        val logEvent: Boolean = true
    ) : PacketAction()

    data class ForwardLogged(
        val targetHost: String,
        val protocol: String,
        val decision: FilterDecision,
        val dnsQueryPayload: ByteArray? = null
    ) : PacketAction()
}

data class DnsQuestion(
    val qname: String,
    val qtype: Int,
    val qclass: Int,
    val questionEndOffset: Int
)

object PacketInspector {

    private const val PROTO_TCP = 6
    private const val PROTO_UDP = 17

    private fun isConfiguredDnsForwarder(packet: ByteArray, version: Int, sourcePort: Int, addresses: Set<String>, sourcePorts: Set<Int>): Boolean {
        if (addresses.isEmpty() || sourcePort !in sourcePorts) return false
        val offset = if (version == 4) 16 else 24
        val size = if (version == 4) 4 else 16
        if (packet.size < offset + size) return false
        val destination = packet.copyOfRange(offset, offset + size)
        return addresses.any { raw ->
            runCatching { java.net.InetAddress.getByName(raw).address.contentEquals(destination) }.getOrDefault(false)
        }
    }

    fun inspectOutboundPacket(
        packet: ByteArray,
        length: Int,
        filterEngine: FilterEngine,
        dnsFilteringEnabled: Boolean = true,
        deepInspectionEnabled: Boolean = true,
        useNxDomain: Boolean = false,
        bypassedPackages: Set<String> = emptySet(),
        dnsForwarderIps: Set<String> = emptySet(),
        dnsForwarderSourcePorts: Set<Int> = emptySet()
    ): PacketAction {
        // Never let a malformed or unexpected packet terminate the VPN's TUN loop.
        // The original packet is forwarded unchanged when inspection cannot safely parse it.
        if (length < 20 || length > packet.size) return PacketAction.ForwardToWireGuard

        return try {
            val version = (packet[0].toInt() ushr 4) and 0x0F
            when (version) {
                4 -> inspectIpv4(packet, length, filterEngine, dnsFilteringEnabled, deepInspectionEnabled, useNxDomain, bypassedPackages, dnsForwarderIps, dnsForwarderSourcePorts)
                6 -> inspectIpv6(packet, length, filterEngine, dnsFilteringEnabled, deepInspectionEnabled, useNxDomain, bypassedPackages, dnsForwarderIps, dnsForwarderSourcePorts)
                else -> PacketAction.ForwardToWireGuard
            }
        } catch (_: Exception) {
            // Fail open for this packet; never restart the whole VPN for an inspection failure.
            PacketAction.ForwardToWireGuard
        }
    }

    /**
     * Synthesizes a valid IPv4/IPv6 TCP RST packet if the input packet is TCP,
     * allowing immediate reset of stale sockets from newly-bypassed apps.
     */
    fun buildTcpResetIfTcp(packet: ByteArray, length: Int): ByteArray? {
        if (length < 20) return null
        val version = (packet[0].toInt() ushr 4) and 0x0F
        return when (version) {
            4 -> {
                val ihl = (packet[0].toInt() and 0x0F) * 4
                if (ihl < 20 || length < ihl + 20) return null
                val protocol = packet[9].toInt() and 0xFF
                if (protocol != PROTO_TCP) return null
                val tcpDataOffset = ((packet[ihl + 12].toInt() ushr 4) and 0x0F) * 4
                val flags = packet[ihl + 13].toInt() and 0xFF
                if ((flags and 0x04) != 0) return null // Ignore if already RST
                val payloadLen = (length - (ihl + tcpDataOffset)).coerceAtLeast(0)
                val synIncrement = if ((flags and 0x02) != 0 && payloadLen == 0) 1 else payloadLen
                buildIpv4TcpReset(packet, ihl, synIncrement)
            }
            6 -> {
                val ipv6HeaderLen = 40
                if (length < ipv6HeaderLen + 20) return null
                val nextHeader = packet[6].toInt() and 0xFF
                if (nextHeader != PROTO_TCP) return null
                val tcpDataOffset = ((packet[ipv6HeaderLen + 12].toInt() ushr 4) and 0x0F) * 4
                val flags = packet[ipv6HeaderLen + 13].toInt() and 0xFF
                if ((flags and 0x04) != 0) return null // Ignore if already RST
                val payloadLen = (length - (ipv6HeaderLen + tcpDataOffset)).coerceAtLeast(0)
                val synIncrement = if ((flags and 0x02) != 0 && payloadLen == 0) 1 else payloadLen
                buildIpv6TcpReset(packet, synIncrement)
            }
            else -> null
        }
    }

    /**
     * Wraps a raw DNS wire-format response payload into an IPv4 or IPv6 UDP packet
     * with swapped source/destination IP & ports and valid checksums.
     */
    fun buildDnsReplyFromUpstream(
        originalPacket: ByteArray,
        length: Int,
        dnsResponsePayload: ByteArray
    ): ByteArray? {
        if (length < 28) return null
        val version = (originalPacket[0].toInt() ushr 4) and 0x0F
        return when (version) {
            4 -> {
                val ihl = (originalPacket[0].toInt() and 0x0F) * 4
                if (length < ihl + 8) return null
                val srcPort = readUInt16(originalPacket, ihl)
                val dstPort = readUInt16(originalPacket, ihl + 2)
                buildIpv4UdpReply(originalPacket, ihl, srcPort, dstPort, dnsResponsePayload)
            }
            6 -> {
                val ipv6HeaderLen = 40
                if (length < ipv6HeaderLen + 8) return null
                val srcPort = readUInt16(originalPacket, ipv6HeaderLen)
                val dstPort = readUInt16(originalPacket, ipv6HeaderLen + 2)
                buildIpv6UdpReply(originalPacket, srcPort, dstPort, dnsResponsePayload)
            }
            else -> null
        }
    }

    private fun inspectIpv4(
        packet: ByteArray,
        length: Int,
        filterEngine: FilterEngine,
        dnsFilteringEnabled: Boolean,
        deepInspectionEnabled: Boolean,
        useNxDomain: Boolean,
        bypassedPackages: Set<String>,
        dnsForwarderIps: Set<String>,
        dnsForwarderSourcePorts: Set<Int>
    ): PacketAction {
        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (ihl < 20 || length < ihl + 8) return PacketAction.ForwardToWireGuard

        val protocol = packet[9].toInt() and 0xFF
        val srcPort = readUInt16(packet, ihl)
        val dstPort = readUInt16(packet, ihl + 2)

        if (protocol == PROTO_UDP && dstPort == 853) {
            return PacketAction.DropAndReply(
                replyPacket = ByteArray(0),
                targetHost = "dot-udp:853",
                protocol = "DoQ",
                decision = FilterDecision(blocked = true, matchedRule = "dot-downgrade", sourceName = "System"),
                logEvent = false
            )
        }

        if (protocol == PROTO_UDP && dstPort == 53) {
            val udpHeaderLen = 8
            val dnsOffset = ihl + udpHeaderLen
            val dnsLen = length - dnsOffset
            if (dnsLen < 12) return PacketAction.ForwardToWireGuard

            val question = parseDnsQuestion(packet, dnsOffset, dnsLen)
                ?: return PacketAction.ForwardToWireGuard
            val exempted = FirewallManager.isDomainExemptedByBypass(question.qname, bypassedPackages)
            val decision = if (dnsFilteringEnabled && !exempted) {
                filterEngine.checkDomain(question.qname)
            } else {
                FilterDecision(blocked = false)
            }

            return if (decision.blocked) {
                val dnsReply = buildDnsBlockedPayload(packet, dnsOffset, question, useNxDomain)
                val ipReply = buildIpv4UdpReply(packet, ihl, srcPort, dstPort, dnsReply)
                PacketAction.DropAndReply(
                    replyPacket = ipReply,
                    targetHost = question.qname,
                    protocol = "DNS",
                    decision = decision
                )
            } else {
                if (isConfiguredDnsForwarder(packet, 4, srcPort, dnsForwarderIps, dnsForwarderSourcePorts)) {
                    return PacketAction.ForwardToWireGuard
                }
                val rawDnsPayload = packet.copyOfRange(dnsOffset, dnsOffset + dnsLen)
                PacketAction.ForwardLogged(
                    targetHost = question.qname,
                    protocol = "DNS",
                    decision = decision,
                    dnsQueryPayload = rawDnsPayload
                )
            }
        }

        if (protocol == PROTO_TCP && dstPort == 853) {
            if (length < ihl + 20) return PacketAction.ForwardToWireGuard
            val tcpDataOffset = ((packet[ihl + 12].toInt() ushr 4) and 0x0F) * 4
            val payloadLen = (length - (ihl + tcpDataOffset)).coerceAtLeast(0)
            val rst = buildIpv4TcpReset(packet, ihl, payloadLen)
            val decision = FilterDecision(blocked = true, matchedRule = "dot-downgrade", sourceName = "System")
            return PacketAction.DropAndReply(
                replyPacket = rst,
                targetHost = "dot:853",
                protocol = "DoT",
                decision = decision,
                logEvent = false
            )
        }

        if (protocol == PROTO_TCP && deepInspectionEnabled && (dstPort == 80 || dstPort == 443)) {
            if (length < ihl + 20) return PacketAction.ForwardToWireGuard
            val tcpDataOffset = ((packet[ihl + 12].toInt() ushr 4) and 0x0F) * 4
            val payloadOffset = ihl + tcpDataOffset
            val payloadLen = length - payloadOffset
            if (payloadLen <= 0) return PacketAction.ForwardToWireGuard

            if (dstPort == 443) {
                val sniHost = extractTlsSni(packet, payloadOffset, payloadLen) ?: return PacketAction.ForwardToWireGuard
                val decision = if (FirewallManager.isDomainExemptedByBypass(sniHost, bypassedPackages)) {
                    FilterDecision(blocked = false)
                } else {
                    filterEngine.checkDomain(sniHost)
                }
                return if (decision.blocked) {
                    val rst = buildIpv4TcpReset(packet, ihl, payloadLen)
                    PacketAction.DropAndReply(
                        replyPacket = rst,
                        targetHost = sniHost,
                        protocol = "TLS/SNI",
                        decision = decision.copy(category = "TLS_SNI")
                    )
                } else {
                    PacketAction.ForwardLogged(sniHost, "TLS/SNI", decision)
                }
            } else if (dstPort == 80) {
                val httpReq = extractHttpHostAndPath(packet, payloadOffset, payloadLen)
                    ?: return PacketAction.ForwardToWireGuard
                val decision = if (FirewallManager.isDomainExemptedByBypass(httpReq.first, bypassedPackages)) {
                    FilterDecision(blocked = false)
                } else {
                    filterEngine.checkUrl(httpReq.first, httpReq.second)
                }
                val displayTarget = "${httpReq.first}${httpReq.second}"
                return if (decision.blocked) {
                    val rst = buildIpv4TcpReset(packet, ihl, payloadLen)
                    PacketAction.DropAndReply(
                        replyPacket = rst,
                        targetHost = displayTarget,
                        protocol = "HTTP",
                        decision = decision
                    )
                } else {
                    PacketAction.ForwardLogged(displayTarget, "HTTP", decision)
                }
            }
        }

        return PacketAction.ForwardToWireGuard
    }

    private fun inspectIpv6(
        packet: ByteArray,
        length: Int,
        filterEngine: FilterEngine,
        dnsFilteringEnabled: Boolean,
        deepInspectionEnabled: Boolean,
        useNxDomain: Boolean,
        bypassedPackages: Set<String>,
        dnsForwarderIps: Set<String>,
        dnsForwarderSourcePorts: Set<Int>
    ): PacketAction {
        val ipv6HeaderLen = 40
        if (length < ipv6HeaderLen + 8) return PacketAction.ForwardToWireGuard

        val nextHeader = packet[6].toInt() and 0xFF
        val srcPort = readUInt16(packet, ipv6HeaderLen)
        val dstPort = readUInt16(packet, ipv6HeaderLen + 2)

        if (nextHeader == PROTO_UDP && dstPort == 853) {
            return PacketAction.DropAndReply(
                replyPacket = ByteArray(0),
                targetHost = "dot-udp:853",
                protocol = "DoQv6",
                decision = FilterDecision(blocked = true, matchedRule = "dot-downgrade", sourceName = "System"),
                logEvent = false
            )
        }

        if (nextHeader == PROTO_UDP && dstPort == 53) {
            val dnsOffset = ipv6HeaderLen + 8
            val dnsLen = length - dnsOffset
            if (dnsLen < 12) return PacketAction.ForwardToWireGuard

            val question = parseDnsQuestion(packet, dnsOffset, dnsLen) ?: return PacketAction.ForwardToWireGuard
            val exempted = FirewallManager.isDomainExemptedByBypass(question.qname, bypassedPackages)
            val decision = if (dnsFilteringEnabled && !exempted) {
                filterEngine.checkDomain(question.qname)
            } else {
                FilterDecision(blocked = false)
            }

            return if (decision.blocked) {
                val dnsReply = buildDnsBlockedPayload(packet, dnsOffset, question, useNxDomain)
                val ipReply = buildIpv6UdpReply(packet, srcPort, dstPort, dnsReply)
                PacketAction.DropAndReply(
                    replyPacket = ipReply,
                    targetHost = question.qname,
                    protocol = "DNSv6",
                    decision = decision
                )
            } else {
                if (isConfiguredDnsForwarder(packet, 6, srcPort, dnsForwarderIps, dnsForwarderSourcePorts)) {
                    return PacketAction.ForwardToWireGuard
                }
                val rawDnsPayload = packet.copyOfRange(dnsOffset, dnsOffset + dnsLen)
                PacketAction.ForwardLogged(
                    targetHost = question.qname,
                    protocol = "DNSv6",
                    decision = decision,
                    dnsQueryPayload = rawDnsPayload
                )
            }
        }

        if (nextHeader == PROTO_TCP && dstPort == 853) {
            if (length < ipv6HeaderLen + 20) return PacketAction.ForwardToWireGuard
            val tcpDataOffset = ((packet[ipv6HeaderLen + 12].toInt() ushr 4) and 0x0F) * 4
            val payloadLen = (length - (ipv6HeaderLen + tcpDataOffset)).coerceAtLeast(0)
            val rst = buildIpv6TcpReset(packet, payloadLen)
            val decision = FilterDecision(blocked = true, matchedRule = "dot-downgrade", sourceName = "System")
            return PacketAction.DropAndReply(
                replyPacket = rst,
                targetHost = "dot:853",
                protocol = "DoTv6",
                decision = decision,
                logEvent = false
            )
        }

        if (nextHeader == PROTO_TCP && deepInspectionEnabled && (dstPort == 80 || dstPort == 443)) {
            if (length < ipv6HeaderLen + 20) return PacketAction.ForwardToWireGuard
            val tcpDataOffset = ((packet[ipv6HeaderLen + 12].toInt() ushr 4) and 0x0F) * 4
            val payloadOffset = ipv6HeaderLen + tcpDataOffset
            val payloadLen = length - payloadOffset
            if (payloadLen <= 0) return PacketAction.ForwardToWireGuard

            if (dstPort == 443) {
                val sniHost = extractTlsSni(packet, payloadOffset, payloadLen) ?: return PacketAction.ForwardToWireGuard
                val decision = if (FirewallManager.isDomainExemptedByBypass(sniHost, bypassedPackages)) {
                    FilterDecision(blocked = false)
                } else {
                    filterEngine.checkDomain(sniHost)
                }
                return if (decision.blocked) {
                    val rst = buildIpv6TcpReset(packet, payloadLen)
                    PacketAction.DropAndReply(
                        replyPacket = rst,
                        targetHost = sniHost,
                        protocol = "TLSv6/SNI",
                        decision = decision.copy(category = "TLS_SNI")
                    )
                } else {
                    PacketAction.ForwardLogged(sniHost, "TLSv6/SNI", decision)
                }
            } else if (dstPort == 80) {
                val httpReq = extractHttpHostAndPath(packet, payloadOffset, payloadLen)
                    ?: return PacketAction.ForwardToWireGuard
                val decision = if (FirewallManager.isDomainExemptedByBypass(httpReq.first, bypassedPackages)) {
                    FilterDecision(blocked = false)
                } else {
                    filterEngine.checkUrl(httpReq.first, httpReq.second)
                }
                val displayTarget = "${httpReq.first}${httpReq.second}"
                return if (decision.blocked) {
                    val rst = buildIpv6TcpReset(packet, payloadLen)
                    PacketAction.DropAndReply(
                        replyPacket = rst,
                        targetHost = displayTarget,
                        protocol = "HTTPv6",
                        decision = decision
                    )
                } else {
                    PacketAction.ForwardLogged(displayTarget, "HTTPv6", decision)
                }
            }
        }

        return PacketAction.ForwardToWireGuard
    }

    fun parseDnsQuestion(packet: ByteArray, dnsOffset: Int, dnsLen: Int): DnsQuestion? {
        if (dnsLen < 12) return null
        val flags = readUInt16(packet, dnsOffset + 2)
        // Must be a standard query (QR bit == 0)
        if ((flags and 0x8000) != 0) return null
        val qdCount = readUInt16(packet, dnsOffset + 4)
        if (qdCount < 1) return null

        var pos = dnsOffset + 12
        val end = dnsOffset + dnsLen
        val labels = mutableListOf<String>()

        while (pos < end) {
            val labelLen = packet[pos].toInt() and 0xFF
            pos++
            if (labelLen == 0) break
            if ((labelLen and 0xC0) != 0) return null // Unexpected pointer in first question
            if (pos + labelLen > end) return null
            labels.add(String(packet, pos, labelLen, StandardCharsets.US_ASCII))
            pos += labelLen
        }

        if (labels.isEmpty() || pos + 4 > end) return null
        val qtype = readUInt16(packet, pos)
        val qclass = readUInt16(packet, pos + 2)
        pos += 4

        return DnsQuestion(
            qname = labels.joinToString("."),
            qtype = qtype,
            qclass = qclass,
            questionEndOffset = pos - dnsOffset
        )
    }

    private fun buildDnsBlockedPayload(
        originalPacket: ByteArray,
        dnsOffset: Int,
        question: DnsQuestion,
        useNxDomain: Boolean
    ): ByteArray {
        val questionSectionLen = question.questionEndOffset - 12
        val canSynthZeroAddress = !useNxDomain && (question.qtype == 1 || question.qtype == 28)
        val rdataLen = when {
            !canSynthZeroAddress -> 0
            question.qtype == 1 -> 4
            else -> 16
        }
        val answerSectionLen = if (canSynthZeroAddress) 12 + rdataLen else 0
        val out = ByteArray(12 + questionSectionLen + answerSectionLen)

        // Transaction ID
        out[0] = originalPacket[dnsOffset]
        out[1] = originalPacket[dnsOffset + 1]

        // Flags: QR=1, Opcode=0, AA=1, RD=1, RA=1, RCODE=0 (or 3 for NXDOMAIN)
        val rcode = if (canSynthZeroAddress) 0 else 3
        val flags = 0x8580 or rcode
        writeUInt16(out, 2, flags)

        // QDCOUNT = 1, ANCOUNT = 1 (or 0), NSCOUNT = 0, ARCOUNT = 0
        writeUInt16(out, 4, 1)
        writeUInt16(out, 6, if (canSynthZeroAddress) 1 else 0)
        writeUInt16(out, 8, 0)
        writeUInt16(out, 10, 0)

        // Copy question section verbatim
        System.arraycopy(originalPacket, dnsOffset + 12, out, 12, questionSectionLen)

        if (canSynthZeroAddress) {
            var ansPos = 12 + questionSectionLen
            // Name pointer to offset 12 (0xC00C)
            writeUInt16(out, ansPos, 0xC00C)
            ansPos += 2
            writeUInt16(out, ansPos, question.qtype)
            ansPos += 2
            writeUInt16(out, ansPos, question.qclass)
            ansPos += 2
            // TTL = 60 seconds
            writeUInt32(out, ansPos, 60L)
            ansPos += 4
            // RDLENGTH (4 for A, 16 for AAAA; bytes are already 0x00)
            writeUInt16(out, ansPos, rdataLen)
        }

        return out
    }

    private fun buildIpv4UdpReply(
        reqPacket: ByteArray,
        reqIhl: Int,
        reqSrcPort: Int,
        reqDstPort: Int,
        udpPayload: ByteArray
    ): ByteArray {
        val ipHeaderLen = 20
        val udpLen = 8 + udpPayload.size
        val totalLen = ipHeaderLen + udpLen
        val out = ByteArray(totalLen)

        out[0] = 0x45.toByte() // IPv4, IHL=5 (20 bytes)
        out[1] = 0x00
        writeUInt16(out, 2, totalLen)
        writeUInt16(out, 4, 0x1337)
        writeUInt16(out, 6, 0x4000) // Don't Fragment
        out[8] = 64 // TTL
        out[9] = PROTO_UDP.toByte()
        // Swap src and dst IPv4 addresses
        System.arraycopy(reqPacket, 16, out, 12, 4) // old dst -> new src
        System.arraycopy(reqPacket, 12, out, 16, 4) // old src -> new dst

        val ipChecksum = computeIpv4HeaderChecksum(out, 0, ipHeaderLen)
        writeUInt16(out, 10, ipChecksum)

        // UDP header (swap ports)
        writeUInt16(out, ipHeaderLen, reqDstPort)
        writeUInt16(out, ipHeaderLen + 2, reqSrcPort)
        writeUInt16(out, ipHeaderLen + 4, udpLen)
        writeUInt16(out, ipHeaderLen + 6, 0)

        System.arraycopy(udpPayload, 0, out, ipHeaderLen + 8, udpPayload.size)

        val udpChecksum = computeTransportChecksumIpv4(out, ipHeaderLen, udpLen, PROTO_UDP)
        writeUInt16(out, ipHeaderLen + 6, if (udpChecksum == 0) 0xFFFF else udpChecksum)

        return out
    }

    private fun buildIpv6UdpReply(
        reqPacket: ByteArray,
        reqSrcPort: Int,
        reqDstPort: Int,
        udpPayload: ByteArray
    ): ByteArray {
        val ipv6HeaderLen = 40
        val udpLen = 8 + udpPayload.size
        val totalLen = ipv6HeaderLen + udpLen
        val out = ByteArray(totalLen)

        out[0] = 0x60.toByte() // Version 6
        writeUInt16(out, 4, udpLen)
        out[6] = PROTO_UDP.toByte()
        out[7] = 64 // Hop limit
        // Swap src (8..23) and dst (24..39)
        System.arraycopy(reqPacket, 24, out, 8, 16)
        System.arraycopy(reqPacket, 8, out, 24, 16)

        // UDP header
        writeUInt16(out, ipv6HeaderLen, reqDstPort)
        writeUInt16(out, ipv6HeaderLen + 2, reqSrcPort)
        writeUInt16(out, ipv6HeaderLen + 4, udpLen)
        writeUInt16(out, ipv6HeaderLen + 6, 0)

        System.arraycopy(udpPayload, 0, out, ipv6HeaderLen + 8, udpPayload.size)

        val udpChecksum = computeTransportChecksumIpv6(out, ipv6HeaderLen, udpLen, PROTO_UDP)
        writeUInt16(out, ipv6HeaderLen + 6, if (udpChecksum == 0) 0xFFFF else udpChecksum)

        return out
    }

    private fun buildIpv4TcpReset(reqPacket: ByteArray, reqIhl: Int, reqPayloadLen: Int): ByteArray {
        val ipHeaderLen = 20
        val tcpHeaderLen = 20
        val totalLen = ipHeaderLen + tcpHeaderLen
        val out = ByteArray(totalLen)

        out[0] = 0x45.toByte()
        writeUInt16(out, 2, totalLen)
        writeUInt16(out, 6, 0x4000)
        out[8] = 64
        out[9] = PROTO_TCP.toByte()
        System.arraycopy(reqPacket, 16, out, 12, 4)
        System.arraycopy(reqPacket, 12, out, 16, 4)
        writeUInt16(out, 10, computeIpv4HeaderChecksum(out, 0, ipHeaderLen))

        val srcPort = readUInt16(reqPacket, reqIhl)
        val dstPort = readUInt16(reqPacket, reqIhl + 2)
        val reqSeq = readUInt32(reqPacket, reqIhl + 4)
        val reqAck = readUInt32(reqPacket, reqIhl + 8)

        val ackAdvance = if (reqPayloadLen > 0) reqPayloadLen else 1
        writeUInt16(out, ipHeaderLen, dstPort)
        writeUInt16(out, ipHeaderLen + 2, srcPort)
        writeUInt32(out, ipHeaderLen + 4, reqAck)
        writeUInt32(out, ipHeaderLen + 8, (reqSeq + ackAdvance) and 0xFFFFFFFFL)
        out[ipHeaderLen + 12] = 0x50.toByte() // 5 words (20 bytes)
        out[ipHeaderLen + 13] = 0x14.toByte() // RST | ACK
        writeUInt16(out, ipHeaderLen + 14, 0)
        writeUInt16(out, ipHeaderLen + 16, 0)
        writeUInt16(out, ipHeaderLen + 16, computeTransportChecksumIpv4(out, ipHeaderLen, tcpHeaderLen, PROTO_TCP))

        return out
    }

    private fun buildIpv6TcpReset(reqPacket: ByteArray, reqPayloadLen: Int): ByteArray {
        val ipv6HeaderLen = 40
        val tcpHeaderLen = 20
        val totalLen = ipv6HeaderLen + tcpHeaderLen
        val out = ByteArray(totalLen)

        out[0] = 0x60.toByte()
        writeUInt16(out, 4, tcpHeaderLen)
        out[6] = PROTO_TCP.toByte()
        out[7] = 64
        System.arraycopy(reqPacket, 24, out, 8, 16)
        System.arraycopy(reqPacket, 8, out, 24, 16)

        val srcPort = readUInt16(reqPacket, ipv6HeaderLen)
        val dstPort = readUInt16(reqPacket, ipv6HeaderLen + 2)
        val reqSeq = readUInt32(reqPacket, ipv6HeaderLen + 4)
        val reqAck = readUInt32(reqPacket, ipv6HeaderLen + 8)

        val ackAdvance = if (reqPayloadLen > 0) reqPayloadLen else 1
        writeUInt16(out, ipv6HeaderLen, dstPort)
        writeUInt16(out, ipv6HeaderLen + 2, srcPort)
        writeUInt32(out, ipv6HeaderLen + 4, reqAck)
        writeUInt32(out, ipv6HeaderLen + 8, (reqSeq + ackAdvance) and 0xFFFFFFFFL)
        out[ipv6HeaderLen + 12] = 0x50.toByte()
        out[ipv6HeaderLen + 13] = 0x14.toByte() // RST | ACK
        writeUInt16(out, ipv6HeaderLen + 16, computeTransportChecksumIpv6(out, ipv6HeaderLen, tcpHeaderLen, PROTO_TCP))

        return out
    }

    /**
     * Parses a TLS ClientHello record to extract the SNI (Server Name Indication) hostname.
     */
    fun extractTlsSni(packet: ByteArray, offset: Int, length: Int): String? {
        if (length < 43) return null
        // ContentType == 22 (Handshake)
        if ((packet[offset].toInt() and 0xFF) != 0x16) return null
        // HandshakeType == 1 (ClientHello)
        if ((packet[offset + 5].toInt() and 0xFF) != 0x01) return null

        val end = offset + length
        var pos = offset + 5 + 4 + 2 + 32 // RecordHeader(5) + HandshakeHeader(4) + ClientVersion(2) + Random(32)
        if (pos >= end) return null

        // Session ID
        val sessionIdLen = packet[pos].toInt() and 0xFF
        pos += 1 + sessionIdLen
        if (pos + 2 > end) return null

        // Cipher Suites
        val cipherSuitesLen = readUInt16(packet, pos)
        pos += 2 + cipherSuitesLen
        if (pos + 1 > end) return null

        // Compression Methods
        val compressionMethodsLen = packet[pos].toInt() and 0xFF
        pos += 1 + compressionMethodsLen
        if (pos + 2 > end) return null

        // Extensions length
        val extensionsLen = readUInt16(packet, pos)
        pos += 2
        val extensionsEnd = (pos + extensionsLen).coerceAtMost(end)

        while (pos + 4 <= extensionsEnd) {
            val extType = readUInt16(packet, pos)
            val extLen = readUInt16(packet, pos + 2)
            pos += 4
            if (pos + extLen > extensionsEnd) break

            if (extType == 0x0000 && extLen >= 5) { // server_name extension
                val listLen = readUInt16(packet, pos)
                var sniPos = pos + 2
                val sniEnd = (sniPos + listLen).coerceAtMost(pos + extLen)
                while (sniPos + 3 <= sniEnd) {
                    val nameType = packet[sniPos].toInt() and 0xFF
                    val nameLen = readUInt16(packet, sniPos + 1)
                    sniPos += 3
                    if (sniPos + nameLen > sniEnd) break
                    if (nameType == 0) {
                        return String(packet, sniPos, nameLen, StandardCharsets.US_ASCII)
                    }
                    sniPos += nameLen
                }
            }
            pos += extLen
        }
        return null
    }

    /**
     * Extracts (Host, Path) from an HTTP/1.x request payload on port 80.
     * Fast-paths non-HTTP packets before allocating strings.
     */
    fun extractHttpHostAndPath(packet: ByteArray, offset: Int, length: Int): Pair<String, String>? {
        if (length < 16 || offset + 4 > packet.size) return null
        val b0 = packet[offset].toInt().toChar()
        if (b0 != 'G' && b0 != 'P' && b0 != 'H' && b0 != 'O' && b0 != 'D') {
            return null
        }

        val sampleLen = length.coerceAtMost(1024)
        val text = String(packet, offset, sampleLen, StandardCharsets.ISO_8859_1)
        val lines = text.split("\r\n")
        if (lines.isEmpty()) return null

        val firstLine = lines[0]
        val parts = firstLine.split(' ')
        if (parts.size < 2) return null
        val method = parts[0]
        if (method != "GET" && method != "POST" && method != "HEAD" && method != "OPTIONS" && method != "PUT") {
            return null
        }

        val path = parts[1].ifEmpty { "/" }
        var host: String? = null
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isEmpty()) break
            if (line.startsWith("Host:", ignoreCase = true)) {
                host = line.substring(5).trim().substringBefore(':')
                break
            }
        }
        return if (!host.isNullOrBlank()) host to path else null
    }

    private fun computeIpv4HeaderChecksum(buf: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = 0
        while (i < length - 1) {
            if (i != 10) {
                sum += readUInt16(buf, offset + i)
            }
            i += 2
        }
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return (sum.inv() and 0xFFFF).toInt()
    }

    private fun computeTransportChecksumIpv4(
        buf: ByteArray,
        transportOffset: Int,
        transportLen: Int,
        protocol: Int
    ): Int {
        var sum = 0L
        // Pseudo-header: src IP (12..15), dst IP (16..19), protocol, transportLen
        sum += readUInt16(buf, 12)
        sum += readUInt16(buf, 14)
        sum += readUInt16(buf, 16)
        sum += readUInt16(buf, 18)
        sum += protocol
        sum += transportLen

        var i = 0
        while (i < transportLen - 1) {
            sum += readUInt16(buf, transportOffset + i)
            i += 2
        }
        if (transportLen % 2 != 0) {
            sum += (buf[transportOffset + transportLen - 1].toInt() and 0xFF) shl 8
        }
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return (sum.inv() and 0xFFFF).toInt()
    }

    private fun computeTransportChecksumIpv6(
        buf: ByteArray,
        transportOffset: Int,
        transportLen: Int,
        nextHeader: Int
    ): Int {
        var sum = 0L
        // IPv6 pseudo-header: src (8..23), dst (24..39)
        var addrPos = 8
        while (addrPos < 40) {
            sum += readUInt16(buf, addrPos)
            addrPos += 2
        }
        sum += transportLen
        sum += nextHeader

        var i = 0
        while (i < transportLen - 1) {
            sum += readUInt16(buf, transportOffset + i)
            i += 2
        }
        if (transportLen % 2 != 0) {
            sum += (buf[transportOffset + transportLen - 1].toInt() and 0xFF) shl 8
        }
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return (sum.inv() and 0xFFFF).toInt()
    }

    private fun readUInt16(buf: ByteArray, offset: Int): Int {
        return ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)
    }

    private fun readUInt32(buf: ByteArray, offset: Int): Long {
        return ((buf[offset].toLong() and 0xFF) shl 24) or
            ((buf[offset + 1].toLong() and 0xFF) shl 16) or
            ((buf[offset + 2].toLong() and 0xFF) shl 8) or
            (buf[offset + 3].toLong() and 0xFF)
    }

    private fun writeUInt16(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 1] = (value and 0xFF).toByte()
    }

    private fun writeUInt32(buf: ByteArray, offset: Int, value: Long) {
        ByteBuffer.wrap(buf, offset, 4).putInt(value.toInt())
    }
}
