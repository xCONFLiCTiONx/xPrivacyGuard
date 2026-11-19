package cc.xconflictionx.xprivacyguard.vpn

import android.net.VpnService
import android.util.Log
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class DnsProxyServer(
    private val vpnService: VpnService,
    private val blocklistFilter: BlocklistFilter,
    private val onQueryProcessed: (appName: String, domain: String, reason: String, blocked: Boolean) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: DatagramSocket? = null
    @Volatile
    private var isRunning = false

    fun start(localPort: Int = 1053) {
        if (isRunning) return
        isRunning = true
        scope.launch {
            try {
                val socket = DatagramSocket(InetSocketAddress("127.0.0.1", localPort))
                serverSocket = socket
                vpnService.protect(socket)
                Log.i("DnsProxyServer", "DNS Proxy Server listening on 127.0.0.1:$localPort")

                val buffer = ByteArray(1500)
                while (isRunning && !socket.isClosed) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (e: Exception) {
                        if (!isRunning) break
                        continue
                    }

                    val queryData = packet.data.copyOfRange(0, packet.length)
                    val clientAddr = packet.address
                    val clientPort = packet.port

                    scope.launch {
                        val responseData = resolveDnsQuery(queryData)
                        if (responseData != null && isRunning && !socket.isClosed) {
                            try {
                                val replyPacket = DatagramPacket(responseData, responseData.size, clientAddr, clientPort)
                                socket.send(replyPacket)
                            } catch (e: Exception) {
                                Log.e("DnsProxyServer", "Error sending DNS response", e)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("DnsProxyServer", "Failed to start DNS Proxy Server socket", e)
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
            serverSocket = null
        } catch (_: Exception) {}
        Log.i("DnsProxyServer", "DNS Proxy stopped.")
    }

    fun buildTestQuery(hostname: String): ByteArray = buildDnsAQuery(hostname)

    suspend fun resolveDnsQuery(dnsMessage: ByteArray, appName: String = "System"): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val domain = extractDomainFromDns(dnsMessage) ?: "unknown"

            // Instant local resolution for loopback / health-check queries
            if (domain.equals("localhost", ignoreCase = true) || domain.equals("127.0.0.1") || domain.endsWith(".local", ignoreCase = true)) {
                try {
                    val localAddr = InetAddress.getByName("127.0.0.1")
                    val localResp = buildDnsAnswerPacket(dnsMessage, localAddr)
                    if (localResp != null) {
                        // Not recorded: this is the internal health-check ping, not user traffic.
                        return@withContext localResp
                    }
                } catch (_: Exception) {}
            }

            val isExcludedApp = appName != "System" && appName != "Self-test" && (
                VpnConfig.excludedPackages.contains(appName) ||
                (appName.contains("bard", ignoreCase = true) || appName.contains("gemini", ignoreCase = true) || appName.contains("google", ignoreCase = true)) &&
                (VpnConfig.excludedPackages.contains("com.google.android.apps.bard") || VpnConfig.excludedPackages.contains("com.google.android.googlequicksearchbox"))
            )

            val checkResult = if (isExcludedApp) {
                BlocklistFilter.CheckResult(false, "Allowed (Bypass App)")
            } else {
                blocklistFilter.checkDomain(domain)
            }

            if (checkResult.isBlocked) {
                onQueryProcessed(appName, domain, checkResult.reason, true)
                return@withContext createBlockedResponse(dnsMessage)
            } else {
                onQueryProcessed(appName, domain, checkResult.reason, false)
            }

            var response: ByteArray? = null

            // Secure DoT (DNS over TLS, Port 853) encrypted resolution
            if (VpnConfig.isSecureDnsActive) {
                val endpoint = VpnConfig.dnsEndpoint

                response = sendDotQuery(dnsMessage, endpoint)
                if (response != null) {
                    return@withContext response
                }

                // Fallback 1: Cloudflare Security DoT (Port 853 with malware protection)
                response = sendDotQuery(dnsMessage, "security.cloudflare-dns.com")
                if (response != null) {
                    return@withContext response
                }

                // Fallback 2: AdGuard DoT (Port 853 with ad/tracker protection)
                response = sendDotQuery(dnsMessage, "dns.adguard-dns.com")
                if (response != null) {
                    return@withContext response
                }

                // Fallback 3: Try Quad9 DoT (Port 853)
                response = sendDotQuery(dnsMessage, "dns.quad9.net")
                if (response != null) {
                    return@withContext response
                }
            }

            // Fallback: Plain UDP DNS (over protected interface)
            val plainResponse = sendPlainDnsQuery(dnsMessage)
            if (plainResponse != null) {
                return@withContext plainResponse
            }

            // Fallback 4: System native DNS lookup (handles both IPv4 and IPv6)
            try {
                val addresses = InetAddress.getAllByName(domain)
                if (addresses.isNotEmpty()) {
                    val fallbackResponse = buildDnsAnswerPacket(dnsMessage, addresses[0])
                    if (fallbackResponse != null) {
                        Log.i("DnsProxyServer", "System DNS fallback resolved $domain to ${addresses[0].hostAddress}")
                        return@withContext fallbackResponse
                    }
                }
            } catch (e: Exception) {
                Log.e("DnsProxyServer", "System DNS fallback error for $domain", e)
            }

            Log.e("DnsProxyServer", "All DNS resolution methods failed for domain: $domain, returning SERVFAIL")
            return@withContext createServfailResponse(dnsMessage)
        } catch (e: Exception) {
            Log.e("DnsProxyServer", "DNS resolution error", e)
            return@withContext createServfailResponse(dnsMessage)
        }
    }

    private fun buildDnsAnswerPacket(query: ByteArray, address: InetAddress): ByteArray? {
        try {
            val addrBytes = address.address
            val isIpv6 = addrBytes.size == 16
            val isIpv4 = addrBytes.size == 4
            if (!isIpv4 && !isIpv6) return null

            val rDataLen = if (isIpv4) 4 else 16
            val typeVal = if (isIpv4) 1 else 28 // Type A = 1, Type AAAA = 28

            val response = ByteArray(query.size + 12 + rDataLen)
            System.arraycopy(query, 0, response, 0, query.size)

            // Set QR=1 (response), RA=1 (recursion available)
            response[2] = (response[2].toInt() or 0x80 or 0x01).toByte()
            response[3] = (response[3].toInt() and 0x0F).toByte() // RCODE=0 (NOERROR)

            // Set ANCOUNT = 1
            response[6] = 0x00
            response[7] = 0x01

            var pos = query.size
            // Answer Name pointer (compression offset to QNAME at index 12)
            response[pos++] = 0xC0.toByte()
            response[pos++] = 0x0C

            // TYPE (A=1 or AAAA=28)
            response[pos++] = (typeVal shr 8).toByte()
            response[pos++] = (typeVal and 0xFF).toByte()

            // CLASS IN (1)
            response[pos++] = 0x00
            response[pos++] = 0x01

            // TTL (300 seconds)
            response[pos++] = 0x00
            response[pos++] = 0x00
            response[pos++] = 0x01
            response[pos++] = 0x2C

            // RDLENGTH
            response[pos++] = (rDataLen shr 8).toByte()
            response[pos++] = (rDataLen and 0xFF).toByte()

            // RDATA (IPv4 or IPv6 address)
            System.arraycopy(addrBytes, 0, response, pos, rDataLen)
            return response.copyOfRange(0, pos + rDataLen)
        } catch (e: Exception) {
            Log.e("DnsProxyServer", "Failed to build fallback DNS answer packet", e)
        }
        return null
    }

    private fun parseEndpointHostAndPort(endpoint: String): Pair<String, Int> {
        val clean = endpoint.removePrefix("https://").removePrefix("http://").trim()
        val pathStripped = clean.substringBefore("/")
        if (pathStripped.startsWith("[")) {
            val host = pathStripped.substringAfter("[").substringBefore("]")
            val port = pathStripped.substringAfter("]:", "").toIntOrNull() ?: 853
            return Pair(host, port)
        }
        val colonCount = pathStripped.count { it == ':' }
        if (colonCount > 1) {
            // Raw IPv6 address without brackets
            return Pair(pathStripped, 853)
        }
        if (colonCount == 1) {
            val host = pathStripped.substringBefore(":")
            val port = pathStripped.substringAfter(":").toIntOrNull() ?: 853
            return Pair(host, port)
        }
        return Pair(pathStripped, 853)
    }

    private fun sendDotQuery(dnsMessage: ByteArray, endpoint: String): ByteArray? {
        val (host, port) = parseEndpointHostAndPort(endpoint)
        val addresses = resolveBootstrapAddresses(host)
        if (addresses.isEmpty()) return null

        for (address in addresses) {
            var socket: Socket? = null
            var sslSocket: SSLSocket? = null
            try {
                socket = Socket()
                vpnService.protect(socket)
                socket.connect(InetSocketAddress(address, port), 2500)
                socket.soTimeout = 2500

                val sslSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val sniHost = when {
                    host.contains(":") -> address.hostAddress
                    host.equals("security.cloudflare", ignoreCase = true) || host.equals("security.cloudflare.com", ignoreCase = true) -> "security.cloudflare-dns.com"
                    host.equals("family.cloudflare", ignoreCase = true) || host.equals("family.cloudflare.com", ignoreCase = true) -> "family.cloudflare-dns.com"
                    host.equals("cloudflare", ignoreCase = true) -> "cloudflare-dns.com"
                    host.equals("quad9", ignoreCase = true) -> "dns.quad9.net"
                    host.equals("adguard", ignoreCase = true) -> "dns.adguard-dns.com"
                    host.equals("mullvad", ignoreCase = true) -> "adblock.doh.mullvad.net"
                    host.equals("controld", ignoreCase = true) -> "p2.freedns.controld.com"
                    host.equals("google", ignoreCase = true) -> "dns.google"
                    else -> host
                }
                sslSocket = sslSocketFactory.createSocket(socket, sniHost, port, true) as SSLSocket
                sslSocket.startHandshake()

                val outputStream = sslSocket.outputStream
                val length = dnsMessage.size
                val lengthPrefix = byteArrayOf((length shr 8).toByte(), (length and 0xFF).toByte())
                outputStream.write(lengthPrefix)
                outputStream.write(dnsMessage)
                outputStream.flush()

                val inputStream = sslSocket.inputStream
                val responseLengthBuffer = ByteArray(2)
                val read = inputStream.read(responseLengthBuffer)
                if (read != 2) continue

                val responseLength = ((responseLengthBuffer[0].toInt() and 0xFF) shl 8) or (responseLengthBuffer[1].toInt() and 0xFF)
                if (responseLength <= 0) continue

                val responseBuffer = ByteArray(responseLength)
                var totalRead = 0
                while (totalRead < responseLength) {
                    val bytesRead = inputStream.read(responseBuffer, totalRead, responseLength - totalRead)
                    if (bytesRead == -1) break
                    totalRead += bytesRead
                }

                if (totalRead == responseLength) {
                    return responseBuffer
                }
            } catch (e: Exception) {
                Log.d("DnsProxyServer", "DoT query attempt via ${address.hostAddress}:$port failed: ${e.message}")
            } finally {
                try {
                    sslSocket?.close()
                    socket?.close()
                } catch (_: Exception) {}
            }
        }
        return null
    }

    private fun sendPlainDnsQuery(dnsMessage: ByteArray): ByteArray? {
        val servers = mutableListOf<String>()
        servers.addAll(VpnConfig.getAllDnsIps())
        servers.addAll(listOf(
            "1.1.1.2", "1.0.0.2",
            "2606:4700:4700::1112", "2606:4700:4700::1002",
            "94.140.14.14", "94.140.15.15",
            "2a10:50c0::ad1:ff", "2a10:50c0::ad2:ff",
            "1.1.1.1", "1.0.0.1",
            "2606:4700:4700::1111", "2606:4700:4700::1001",
            "9.9.9.9", "149.112.112.112",
            "2620:fe::fe", "2620:fe::9",
            "194.242.2.3", "194.242.2.4",
            "2a07:e340::3", "2a07:e340::4",
            "8.8.8.8", "8.8.4.4",
            "2001:4860:4860::8888", "2001:4860:4860::8844"
        ))
        for (serverIp in servers.distinct()) {
            var datagramSocket: DatagramSocket? = null
            try {
                val serverAddr = InetAddress.getByName(serverIp)
                datagramSocket = DatagramSocket()
                vpnService.protect(datagramSocket)
                datagramSocket.soTimeout = 1500

                val sendPacket = DatagramPacket(dnsMessage, dnsMessage.size, serverAddr, 53)
                datagramSocket.send(sendPacket)

                val recvBuffer = ByteArray(1500)
                val recvPacket = DatagramPacket(recvBuffer, recvBuffer.size)
                datagramSocket.receive(recvPacket)

                if (recvPacket.length > 0) {
                    return recvBuffer.copyOfRange(0, recvPacket.length)
                }
            } catch (e: Exception) {
                Log.d("DnsProxyServer", "Plain DNS query error for $serverIp: ${e.message}")
            } finally {
                datagramSocket?.close()
            }
        }
        return null
    }

    private fun resolveBootstrapAddresses(hostname: String): List<InetAddress> {
        val cleanHost = hostname.lowercase().trim().removePrefix("[").removeSuffix("]")
        val results = mutableListOf<InetAddress>()

        // 1. Direct IP check (works for IPv4 and IPv6 string literals)
        try {
            val direct = InetAddress.getByName(cleanHost)
            val isIpLiteral = cleanHost.all { it.isDigit() || it == '.' || it == ':' || it in 'a'..'f' || it in 'A'..'F' } &&
                    (cleanHost.contains(":") || cleanHost.matches(Regex("""\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}""")))
            if (isIpLiteral) {
                return listOf(direct)
            }
        } catch (_: Exception) {}

        // 2. User-configured IPv6 and IPv4 addresses
        val configuredIps = VpnConfig.getAllDnsIps()
        for (ipStr in configuredIps) {
            try {
                results.add(InetAddress.getByName(ipStr))
            } catch (_: Exception) {}
        }

        // 3. Known bootstrap IPs (IPv6 and IPv4) for popular DNS providers
        when (cleanHost) {
            "security.cloudflare-dns.com", "security.cloudflare", "security.cloudflare.com" -> {
                listOf(
                    "1.1.1.2", "1.0.0.2",
                    "2606:4700:4700::1112", "2606:4700:4700::1002"
                ).forEach { try { results.add(InetAddress.getByName(it)) } catch (_: Exception) {} }
            }
            "family.cloudflare-dns.com", "family.cloudflare", "family.cloudflare.com" -> {
                listOf(
                    "1.1.1.3", "1.0.0.3",
                    "2606:4700:4700::1113", "2606:4700:4700::1003"
                ).forEach { try { results.add(InetAddress.getByName(it)) } catch (_: Exception) {} }
            }
            "cloudflare-dns.com", "one.one.one.one", "1.1.1.1", "cloudflare" -> {
                listOf(
                    "1.1.1.1", "1.0.0.1",
                    "2606:4700:4700::1111", "2606:4700:4700::1001"
                ).forEach { try { results.add(InetAddress.getByName(it)) } catch (_: Exception) {} }
            }
            "dns.adguard-dns.com", "dns.adguard.com", "adguard" -> {
                listOf(
                    "94.140.14.14", "94.140.15.15",
                    "2a10:50c0::ad1:ff", "2a10:50c0::ad2:ff"
                ).forEach { try { results.add(InetAddress.getByName(it)) } catch (_: Exception) {} }
            }
            "dns.quad9.net", "quad9.net", "quad9" -> {
                listOf(
                    "9.9.9.9", "149.112.112.112",
                    "2620:fe::fe", "2620:fe::9"
                ).forEach { try { results.add(InetAddress.getByName(it)) } catch (_: Exception) {} }
            }
            "adblock.doh.mullvad.net", "dns.mullvad.net", "mullvad" -> {
                listOf(
                    "194.242.2.3", "194.242.2.4",
                    "2a07:e340::3", "2a07:e340::4"
                ).forEach { try { results.add(InetAddress.getByName(it)) } catch (_: Exception) {} }
            }
            "p2.freedns.controld.com", "freedns.controld.com", "controld" -> {
                listOf(
                    "76.76.2.2", "76.76.10.2",
                    "2606:1a40::2", "2606:1a40:1::2"
                ).forEach { try { results.add(InetAddress.getByName(it)) } catch (_: Exception) {} }
            }
            "dns.google", "google" -> {
                listOf(
                    "8.8.8.8", "8.8.4.4",
                    "2001:4860:4860::8888", "2001:4860:4860::8844"
                ).forEach { try { results.add(InetAddress.getByName(it)) } catch (_: Exception) {} }
            }
        }

        if (results.isNotEmpty()) {
            return results.distinctBy { it.hostAddress }
        }

        // 4. Protected UDP lookup fallback
        val lookup = resolveViaProtectedUdp(cleanHost)
        if (lookup != null) {
            results.add(lookup)
        }

        return results.distinctBy { it.hostAddress }
    }

    private fun resolveViaProtectedUdp(hostname: String): InetAddress? {
        val bootstrapServers = listOf(
            "94.140.14.14", "1.1.1.1", "8.8.8.8", "9.9.9.9",
            "2a10:50c0::ad1:ff", "2606:4700:4700::1111", "2001:4860:4860::8888"
        )
        for (serverIp in bootstrapServers) {
            var socket: DatagramSocket? = null
            try {
                val serverAddr = InetAddress.getByName(serverIp)
                socket = DatagramSocket()
                vpnService.protect(socket)
                socket.soTimeout = 2500

                val query = buildDnsAQuery(hostname)
                val sendPacket = DatagramPacket(query, query.size, serverAddr, 53)
                socket.send(sendPacket)

                val recvBuffer = ByteArray(512)
                val recvPacket = DatagramPacket(recvBuffer, recvBuffer.size)
                socket.receive(recvPacket)

                val address = parseAAnswer(recvBuffer, recvPacket.length)
                if (address != null) {
                    return address
                }
            } catch (e: Exception) {
                Log.d("DnsProxyServer", "Protected UDP bootstrap lookup error for $hostname via $serverIp: ${e.message}")
            } finally {
                socket?.close()
            }
        }

        return null
    }

    private fun buildDnsAQuery(hostname: String): ByteArray {
        val bytes = mutableListOf<Byte>()
        // Transaction ID
        bytes.add(0x12)
        bytes.add(0x34)
        // Flags: Standard query (0x01, 0x00)
        bytes.add(0x01)
        bytes.add(0x00)
        // Questions: 1
        bytes.add(0x00)
        bytes.add(0x01)
        // Answers: 0, Authority: 0, Additional: 0
        bytes.add(0x00); bytes.add(0x00)
        bytes.add(0x00); bytes.add(0x00)
        bytes.add(0x00); bytes.add(0x00)

        // QNAME
        for (part in hostname.split(".")) {
            if (part.isEmpty()) continue
            bytes.add(part.length.toByte())
            for (c in part.toByteArray(Charsets.UTF_8)) {
                bytes.add(c)
            }
        }
        bytes.add(0x00) // End of QNAME

        // QTYPE: A (0x0001)
        bytes.add(0x00); bytes.add(0x01)
        // QCLASS: IN (0x0001)
        bytes.add(0x00); bytes.add(0x01)

        return bytes.toByteArray()
    }

    private fun parseAAnswer(data: ByteArray, length: Int): InetAddress? {
        try {
            if (length < 12) return null
            val anCount = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
            if (anCount == 0) return null

            // Skip question section
            var index = 12
            while (index < length) {
                val len = data[index].toInt() and 0xFF
                if (len == 0) { index++; break }
                if ((len and 0xC0) == 0xC0) { index += 2; break }
                index += 1 + len
            }
            index += 4 // Skip QTYPE and QCLASS

            // Parse Answer section
            while (index < length - 10) {
                // NAME
                if ((data[index].toInt() and 0xC0) == 0xC0) {
                    index += 2
                } else {
                    while (index < length && data[index] != 0.toByte()) {
                        index += 1 + (data[index].toInt() and 0xFF)
                    }
                    index++
                }

                if (index + 10 > length) break
                val type = ((data[index].toInt() and 0xFF) shl 8) or (data[index + 1].toInt() and 0xFF)
                val rdLength = ((data[index + 8].toInt() and 0xFF) shl 8) or (data[index + 9].toInt() and 0xFF)
                index += 10

                if (type == 1 && rdLength == 4 && index + 4 <= length) { // Type A
                    val ip = ByteArray(4)
                    System.arraycopy(data, index, ip, 0, 4)
                    return InetAddress.getByAddress(ip)
                }
                index += rdLength
            }
        } catch (e: Exception) {
            Log.e("DnsProxyServer", "Failed to parse A record answer", e)
        }
        return null
    }

    companion object {
        fun extractDomainStatic(data: ByteArray): String? {
            try {
                if (data.size < 12) return null
                var index = 12
                val labels = mutableListOf<String>()
                while (index < data.size) {
                    val len = data[index].toInt() and 0xFF
                    if (len == 0) break
                    if ((len and 0xC0) == 0xC0) break
                    index++
                    if (index + len > data.size) break
                    val label = String(data, index, len, Charsets.UTF_8)
                    labels.add(label)
                    index += len
                }
                if (labels.isNotEmpty()) {
                    return labels.joinToString(".")
                }
            } catch (_: Exception) {}
            return null
        }
    }

    fun extractDomainFromDns(data: ByteArray): String? {
        return extractDomainStatic(data)
    }

    private fun createBlockedResponse(query: ByteArray): ByteArray {
        val response = query.clone()
        if (response.size >= 4) {
            response[2] = (response[2].toInt() or 0x80 or 0x01).toByte() // QR=1, RA=1
            response[3] = (response[3].toInt() and 0xF0 or 0x03).toByte() // RCODE=3 (NXDOMAIN)
        }
        return response
    }

    private fun createServfailResponse(query: ByteArray): ByteArray {
        val response = query.clone()
        if (response.size >= 4) {
            response[2] = (response[2].toInt() or 0x80).toByte() // QR=1
            response[3] = (response[3].toInt() and 0xF0 or 0x02).toByte() // RCODE=2 (SERVFAIL)
        }
        return response
    }
}
