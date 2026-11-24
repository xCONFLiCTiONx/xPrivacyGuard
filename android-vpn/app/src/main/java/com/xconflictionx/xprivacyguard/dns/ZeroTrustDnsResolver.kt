package com.xconflictionx.xprivacyguard.dns

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

object ZeroTrustDnsResolver {

    private const val DNS_CACHE_TTL_MS = 600_000L // 10 minutes TTL to minimize radio wakeups
    private const val MAX_DNS_CACHE_ENTRIES = 2048
    private const val SYSTEM_DNS_CACHE_TTL_MS = 120_000L // 2 minutes TTL

    private data class CachedDnsEntry(
        val wireResponse: ByteArray,
        val expiresAtMs: Long
    )

    private val hostIpCache = ConcurrentHashMap<String, String>()
    private val dnsResponseCache = ConcurrentHashMap<String, CachedDnsEntry>(512)
    private val inFlightQueries = ConcurrentHashMap<String, CompletableFuture<ByteArray?>>()
    private val activeDnsUpstreamPorts = ConcurrentHashMap.newKeySet<Int>()

    // Persistent reusable TLS connection for DoT (RFC 7858 Section 3.3 session reuse)
    private val dotLock = Any()
    private var persistentDotHost: String? = null
    private var persistentDotSocket: SSLSocket? = null
    private var persistentDotOut: DataOutputStream? = null
    private var persistentDotIn: DataInputStream? = null

    // Persistent reusable TLS connection for DoH (HTTP/1.1 Keep-Alive)
    private val dohLock = Any()
    private var persistentDohHostPort: String? = null
    private var persistentDohSocket: SSLSocket? = null

    @Volatile
    private var cachedSystemDns: List<String> = emptyList()

    @Volatile
    private var cachedSystemDnsTimestampMs: Long = 0L

    fun activeUpstreamSourcePorts(): Set<Int> = activeDnsUpstreamPorts.toSet()

    fun clearCaches() {
        dnsResponseCache.clear()
        hostIpCache.clear()
        cachedSystemDns = emptyList()
        cachedSystemDnsTimestampMs = 0L
        resetPrivateDnsCooldown()
        closePersistentConnections()
    }

    /**
     * Called immediately when the underlying physical network switches (e.g. 5G <-> Wi-Fi).
     * Invalidates stale carrier/router DNS servers and drops old persistent sockets while
     * preserving valid public DNS cache entries to keep resolution snappy.
     */
    fun onNetworkChanged() {
        cachedSystemDns = emptyList()
        cachedSystemDnsTimestampMs = 0L
        hostIpCache.clear()
        resetPrivateDnsCooldown()
        closePersistentConnections()
    }

    fun closePersistentConnections() {
        synchronized(dotLock) {
            runCatching { persistentDotSocket?.close() }
            persistentDotSocket = null
            persistentDotOut = null
            persistentDotIn = null
            persistentDotHost = null
        }
        synchronized(dohLock) {
            runCatching { persistentDohSocket?.close() }
            persistentDohSocket = null
            persistentDohHostPort = null
        }
    }

    /**
     * Fast, zero-I/O check for a cached DNS wire-format response.
     * If found and not expired, returns a copy with the caller's 2-byte DNS Transaction ID.
     */
    fun getCachedDnsResponse(dnsQueryPayload: ByteArray): ByteArray? {
        val key = extractCacheKey(dnsQueryPayload) ?: return null
        val entry = dnsResponseCache[key] ?: return null
        val now = System.currentTimeMillis()
        if (now > entry.expiresAtMs) {
            dnsResponseCache.remove(key)
            return null
        }
        val copy = entry.wireResponse.copyOf()
        if (copy.size >= 2 && dnsQueryPayload.size >= 2) {
            copy[0] = dnsQueryPayload[0]
            copy[1] = dnsQueryPayload[1]
        }
        return copy
    }

    private fun putCachedDnsResponse(key: String?, response: ByteArray) {
        if (key == null || response.size < 12) return
        val rcode = response[3].toInt() and 0x0F
        if (rcode != 0 && rcode != 3) return

        if (dnsResponseCache.size >= MAX_DNS_CACHE_ENTRIES) {
            val now = System.currentTimeMillis()
            val iter = dnsResponseCache.entries.iterator()
            while (iter.hasNext()) {
                if (now > iter.next().value.expiresAtMs) {
                    iter.remove()
                }
            }
            if (dnsResponseCache.size >= MAX_DNS_CACHE_ENTRIES) {
                dnsResponseCache.clear()
            }
        }
        // Cache NXDOMAIN for 2 minutes, NOERROR for 10 minutes
        val ttl = if (rcode == 3) 120_000L else DNS_CACHE_TTL_MS
        dnsResponseCache[key] = CachedDnsEntry(
            wireResponse = response.copyOf(),
            expiresAtMs = System.currentTimeMillis() + ttl
        )
    }

    /**
     * Extracts "qname:qtype:qclass" from a DNS query payload for cache & deduplication lookup.
     */
    private fun extractCacheKey(dnsQueryPayload: ByteArray): String? {
        if (dnsQueryPayload.size < 17) return null
        val qdCount = ((dnsQueryPayload[4].toInt() and 0xFF) shl 8) or (dnsQueryPayload[5].toInt() and 0xFF)
        if (qdCount < 1) return null

        var pos = 12
        val sb = StringBuilder(64)
        while (pos < dnsQueryPayload.size) {
            val len = dnsQueryPayload[pos].toInt() and 0xFF
            pos++
            if (len == 0) break
            if ((len and 0xC0) != 0 || pos + len > dnsQueryPayload.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (i in 0 until len) {
                val ch = (dnsQueryPayload[pos + i].toInt() and 0xFF).toChar().lowercaseChar()
                sb.append(ch)
            }
            pos += len
        }
        if (sb.isEmpty() || pos + 4 > dnsQueryPayload.size) return null
        val qtype = ((dnsQueryPayload[pos].toInt() and 0xFF) shl 8) or (dnsQueryPayload[pos + 1].toInt() and 0xFF)
        val qclass = ((dnsQueryPayload[pos + 2].toInt() and 0xFF) shl 8) or (dnsQueryPayload[pos + 3].toInt() and 0xFF)
        sb.append(':').append(qtype).append(':').append(qclass)
        return sb.toString()
    }

    @Suppress("DEPRECATION")
    fun getSystemDnsServers(context: Context, preferredNetwork: Network? = null): List<String> {
        val now = System.currentTimeMillis()
        val existing = cachedSystemDns
        if (preferredNetwork == null && existing.isNotEmpty() && now - cachedSystemDnsTimestampMs < SYSTEM_DNS_CACHE_TTL_MS) {
            return existing
        }

        val result = mutableListOf<String>()
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm != null) {
                fun collectFromNetwork(net: Network) {
                    val caps = cm.getNetworkCapabilities(net) ?: return
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
                    val lp = cm.getLinkProperties(net) ?: return
                    for (dns in lp.dnsServers) {
                        val hostAddr = dns.hostAddress
                        if (!hostAddr.isNullOrBlank() && !result.contains(hostAddr)) {
                            result.add(hostAddr)
                        }
                    }
                }

                // 1. Prefer the explicitly tracked active physical network first
                if (preferredNetwork != null) {
                    collectFromNetwork(preferredNetwork)
                }

                // 2. Otherwise inspect non-VPN networks prioritized by validated Wi-Fi/Ethernet > Cellular
                if (result.isEmpty()) {
                    val orderedNetworks = cm.allNetworks
                        .mapNotNull { net ->
                            val caps = cm.getNetworkCapabilities(net) ?: return@mapNotNull null
                            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
                            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@mapNotNull null
                            val validatedScore = if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 0 else 10
                            val transportScore = when {
                                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 0
                                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 1
                                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 2
                                else -> 3
                            }
                            Triple(net, validatedScore + transportScore, caps)
                        }
                        .sortedBy { it.second }

                    for ((net, _, _) in orderedNetworks) {
                        collectFromNetwork(net)
                        if (result.isNotEmpty()) break
                    }
                }
            }
        }
        if (result.isNotEmpty()) {
            // Prefer IPv4 DNS literals first for faster UDP resolution on dual-stack mobile networks
            val sorted = result.sortedBy { it.contains(':') }
            cachedSystemDns = sorted
            cachedSystemDnsTimestampMs = now
            return sorted
        }
        return listOf("1.1.1.1", "1.0.0.1")
    }

    /**
     * Resolves a raw DNS wire-format query payload with in-memory caching, in-flight deduplication,
     * and persistent TLS socket reuse so duplicate queries never spawn extra sockets or TLS handshakes.
     */
    fun resolveRawDnsQuery(
        context: Context,
        dnsQueryPayload: ByteArray,
        config: ZeroTrustDnsConfig,
        vpnService: VpnService? = null,
        wireGuardDnsServers: List<String> = emptyList(),
        preferredNetwork: Network? = null
    ): ByteArray? {
        // 1. Check in-memory DNS cache first (0 network / 0 radio cost)
        getCachedDnsResponse(dnsQueryPayload)?.let { return it }

        val cacheKey = extractCacheKey(dnsQueryPayload)
        if (cacheKey != null) {
            val existingFuture = inFlightQueries[cacheKey]
            if (existingFuture != null) {
                val sharedResp = runCatching { existingFuture.get(4, TimeUnit.SECONDS) }.getOrNull()
                if (sharedResp != null && sharedResp.size >= 12) {
                    val copy = sharedResp.copyOf()
                    if (dnsQueryPayload.size >= 2) {
                        copy[0] = dnsQueryPayload[0]
                        copy[1] = dnsQueryPayload[1]
                    }
                    return copy
                }
            }
        }

        val myFuture = CompletableFuture<ByteArray?>()
        if (cacheKey != null) {
            inFlightQueries[cacheKey] = myFuture
        }

        return try {
            val resolved = performUpstreamResolution(
                context = context,
                dnsQueryPayload = dnsQueryPayload,
                cacheKey = cacheKey,
                config = config,
                vpnService = vpnService,
                wireGuardDnsServers = wireGuardDnsServers,
                preferredNetwork = preferredNetwork
            )
            myFuture.complete(resolved)
            resolved
        } catch (e: Exception) {
            myFuture.complete(null)
            null
        } finally {
            if (cacheKey != null) {
                inFlightQueries.remove(cacheKey, myFuture)
            }
        }
    }

    @Volatile
    private var lastPrivateDnsFailureEpochMs: Long = 0L

    private val consecutivePrivateDnsFailures = java.util.concurrent.atomic.AtomicInteger(0)

    fun resetPrivateDnsCooldown() {
        lastPrivateDnsFailureEpochMs = 0L
        consecutivePrivateDnsFailures.set(0)
    }

    private fun isDirectlyReachableOnWifi(
        context: Context,
        ipStr: String,
        preferredNetwork: Network?
    ): Boolean {
        if (!isPrivateIpv4Literal(ipStr)) return false
        val targetBytes = runCatching { InetAddress.getByName(ipStr) }.getOrNull() as? Inet4Address ?: return false
        val tb = targetBytes.address
        val targetInt = ((tb[0].toInt() and 0xFF) shl 24) or
            ((tb[1].toInt() and 0xFF) shl 16) or
            ((tb[2].toInt() and 0xFF) shl 8) or
            (tb[3].toInt() and 0xFF)

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        fun networkContainsTarget(net: Network): Boolean {
            val caps = runCatching { cm.getNetworkCapabilities(net) }.getOrNull() ?: return false
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return false
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            ) return false
            val lp = runCatching { cm.getLinkProperties(net) }.getOrNull() ?: return false
            for (la in lp.linkAddresses) {
                val addr = la.address as? Inet4Address ?: continue
                val prefixLen = la.prefixLength
                if (prefixLen !in 8..30) continue
                val b = addr.address
                val netIp = ((b[0].toInt() and 0xFF) shl 24) or
                    ((b[1].toInt() and 0xFF) shl 16) or
                    ((b[2].toInt() and 0xFF) shl 8) or
                    (b[3].toInt() and 0xFF)
                val mask = -1 shl (32 - prefixLen)
                if ((netIp and mask) == (targetInt and mask)) return true
            }
            return false
        }

        if (preferredNetwork != null && networkContainsTarget(preferredNetwork)) return true
        return runCatching { cm.allNetworks.any { networkContainsTarget(it) } }.getOrDefault(false)
    }

    private fun performUpstreamResolution(
        context: Context,
        dnsQueryPayload: ByteArray,
        cacheKey: String?,
        config: ZeroTrustDnsConfig,
        vpnService: VpnService?,
        wireGuardDnsServers: List<String>,
        preferredNetwork: Network? = null
    ): ByteArray? {
        val queryName = extractQueryName(dnsQueryPayload)
        val privateServerIp = config.privateDnsServer.trim()
        val privateServerDirectWifi = privateServerIp.isNotEmpty() &&
            isDirectlyReachableOnWifi(context, privateServerIp, preferredNetwork)

        if (queryName != null && config.isPrivateDnsConfigured && config.matchesPrivateDomain(queryName)) {
            if (privateServerIp.isNotEmpty()) {
                // On Home Wi-Fi, query the local DNS server directly over Wi-Fi (bypassing Cloudflare).
                // On Cellular / away networks, query through the VPN tunnel.
                val primaryProtect = privateServerDirectWifi || vpnService == null
                val privateReply = runCatching {
                    queryUdpDns(
                        serverIp = privateServerIp,
                        port = 53,
                        dnsQueryPayload = dnsQueryPayload,
                        vpnService = vpnService,
                        timeoutMs = if (privateServerDirectWifi) 1200 else 2000,
                        protectSocket = primaryProtect,
                        preferredNetwork = if (primaryProtect) preferredNetwork else null
                    )
                }.getOrNull() ?: if (privateServerDirectWifi && vpnService != null) {
                    runCatching {
                        queryUdpDns(
                            serverIp = privateServerIp,
                            port = 53,
                            dnsQueryPayload = dnsQueryPayload,
                            vpnService = vpnService,
                            timeoutMs = 2000,
                            protectSocket = false
                        )
                    }.getOrNull()
                } else null
                if (hasPositiveDnsAnswer(privateReply)) {
                    lastPrivateDnsFailureEpochMs = 0L
                    android.util.Log.d("ZeroTrustDns", "PrivateDNS OK for $queryName -> $privateServerIp (directWifi=$privateServerDirectWifi)")
                    putCachedDnsResponse(cacheKey, privateReply!!)
                    return privateReply
                }
            }

            // Also check any private LAN DNS servers in WireGuard config (e.g. local router DNS)
            val lanDnsServers = wireGuardDnsServers
                .map { it.trim() }
                .filter { it.isNotEmpty() && it != privateServerIp && isPrivateIpv4Literal(it) }
                .distinct()
            for (lanIp in lanDnsServers) {
                val lanReply = runCatching {
                    queryUdpDns(
                        serverIp = lanIp,
                        port = 53,
                        dnsQueryPayload = dnsQueryPayload,
                        vpnService = vpnService,
                        timeoutMs = 1500,
                        protectSocket = false
                    )
                }.getOrNull()
                if (hasPositiveDnsAnswer(lanReply)) {
                    android.util.Log.d("ZeroTrustDns", "PrivateDNS LAN OK for $queryName -> $lanIp")
                    putCachedDnsResponse(cacheKey, lanReply!!)
                    return lanReply
                }
            }

            // Synthesize a direct resolution to privateDnsServer for privateDomain hosts
            val syntheticReply = buildSyntheticPrivateDomainResponse(dnsQueryPayload, privateServerIp)
            if (syntheticReply != null) {
                android.util.Log.d("ZeroTrustDns", "PrivateDNS synthetic for $queryName -> $privateServerIp")
                putCachedDnsResponse(cacheKey, syntheticReply)
                return syntheticReply
            }
            return buildDnsErrorResponse(dnsQueryPayload, 2)
        }

        // Prefer IPv4 WireGuard DNS servers before IPv6 for faster and more reliable tunnel resolution
        val orderedWgDns = wireGuardDnsServers
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sortedBy { it.contains(':') }
        val systemDns = orderedWgDns.ifEmpty { getSystemDnsServers(context, preferredNetwork) }
        val bootstrapIps = (config.configuredIpv4List() + systemDns + listOf("1.1.1.1", "1.0.0.1")).distinct()

        // If a private/AdGuard DNS server is configured and tunnel is active, query it first
        if (privateServerIp.isNotEmpty() && vpnService != null) {
            val now = System.currentTimeMillis()
            val sinceFail = now - lastPrivateDnsFailureEpochMs
            val inCooldown = consecutivePrivateDnsFailures.get() >= 3 && sinceFail <= 2_000L
            if (!inCooldown) {
                var adguardErr: String? = null
                val adguardReply = try {
                    queryUdpDns(
                        serverIp = privateServerIp,
                        port = 53,
                        dnsQueryPayload = dnsQueryPayload,
                        vpnService = vpnService,
                        timeoutMs = if (privateServerDirectWifi) 1500 else 2500,
                        protectSocket = privateServerDirectWifi,
                        preferredNetwork = if (privateServerDirectWifi) preferredNetwork else null
                    )
                } catch (e: Exception) {
                    adguardErr = "${e.javaClass.simpleName}: ${e.message}"
                    null
                }
                if (isValidDnsReply(adguardReply)) {
                    resetPrivateDnsCooldown()
                    android.util.Log.d("ZeroTrustDns", "AdGuard OK for $queryName -> $privateServerIp")
                    putCachedDnsResponse(cacheKey, adguardReply!!)
                    return adguardReply
                } else {
                    val failCount = consecutivePrivateDnsFailures.incrementAndGet()
                    if (failCount >= 3) {
                        lastPrivateDnsFailureEpochMs = now
                    }
                    android.util.Log.w("ZeroTrustDns", "AdGuard FAIL #$failCount for $queryName -> $privateServerIp err=$adguardErr bytes=${adguardReply?.size}")
                }
            } else {
                android.util.Log.d("ZeroTrustDns", "AdGuard COOLDOWN (${2_000L - sinceFail}ms left) for $queryName")
            }
        } else {
            android.util.Log.d("ZeroTrustDns", "AdGuard SKIPPED for $queryName server='$privateServerIp' vpn=${vpnService != null}")
        }

        if (config.isCustomConfigured) {
            // 1. Try persistent DoT (RFC 7858 port 853 TLS) if configured — minimal framing overhead with TLS reuse
            config.normalizedDotHost()?.let { dotHost ->
                val resp = runCatching {
                    queryDotPersistent(dotHost, dnsQueryPayload, bootstrapIps, vpnService)
                }.getOrNull()
                if (isValidDnsReply(resp)) {
                    putCachedDnsResponse(cacheKey, resp!!)
                    return resp
                }
            }

            // 2. Try persistent DoH (RFC 8484 HTTP/1.1 Keep-Alive) if configured
            config.normalizedDohUrl()?.let { dohUrl ->
                val resp = runCatching {
                    queryDohPersistent(dohUrl, dnsQueryPayload, bootstrapIps, vpnService)
                }.getOrNull()
                if (isValidDnsReply(resp)) {
                    putCachedDnsResponse(cacheKey, resp!!)
                    return resp
                }
            }

            // 3. Try Zero Trust UDP IPv4/IPv6 Location endpoints if configured
            val customUdpTargets = config.configuredIpv4List() + config.configuredIpv6List()
            for (serverIp in customUdpTargets) {
                val resp = runCatching {
                    queryUdpDns(serverIp, 53, dnsQueryPayload, vpnService, timeoutMs = 1500)
                }.getOrNull()
                if (isValidDnsReply(resp)) {
                    putCachedDnsResponse(cacheKey, resp!!)
                    return resp
                }
            }
        }

        // Default (or fallback): Resolve using System / WireGuard DNS servers over single-packet UDP
        val fallbackServers = (systemDns + listOf("1.1.1.1", "1.0.0.1")).distinct()
        for (sysIp in fallbackServers) {
            if (sysIp == privateServerIp && orderedWgDns.isNotEmpty()) continue
            val useProtected = orderedWgDns.isEmpty() || !orderedWgDns.contains(sysIp)
            val resp = runCatching {
                queryUdpDns(
                    serverIp = sysIp,
                    port = 53,
                    dnsQueryPayload = dnsQueryPayload,
                    vpnService = vpnService,
                    timeoutMs = 1500,
                    protectSocket = useProtected,
                    preferredNetwork = if (useProtected) preferredNetwork else null
                )
            }.getOrNull()
            if (isValidDnsReply(resp)) {
                putCachedDnsResponse(cacheKey, resp!!)
                return resp
            }
        }

        return null
    }

    private fun hasPositiveDnsAnswer(resp: ByteArray?): Boolean {
        if (resp == null || resp.size < 12) return false
        val rcode = resp[3].toInt() and 0x0F
        if (rcode != 0) return false
        val anCount = ((resp[6].toInt() and 0xFF) shl 8) or (resp[7].toInt() and 0xFF)
        return anCount > 0
    }

    private fun isPrivateIpv4Literal(ip: String): Boolean {
        val parts = ip.trim().split('.')
        if (parts.size != 4) return false
        val b0 = parts[0].toIntOrNull() ?: return false
        val b1 = parts[1].toIntOrNull() ?: return false
        return b0 == 10 ||
            (b0 == 172 && b1 in 16..31) ||
            (b0 == 192 && b1 == 168) ||
            (b0 == 100 && b1 in 64..127)
    }

    private fun buildSyntheticPrivateDomainResponse(query: ByteArray, ipv4Literal: String): ByteArray? {
        if (query.size < 17) return null
        val octets = ipv4Literal.trim().split('.').map { it.toIntOrNull() ?: return null }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return null

        var pos = 12
        while (pos < query.size) {
            val len = query[pos++].toInt() and 0xFF
            if (len == 0) break
            if ((len and 0xC0) != 0 || pos + len > query.size) return null
            pos += len
        }
        if (pos + 4 > query.size) return null
        val qtype = ((query[pos].toInt() and 0xFF) shl 8) or (query[pos + 1].toInt() and 0xFF)
        val questionEnd = pos + 4

        if (qtype != 1) {
            // Return NOERROR with 0 answers for AAAA / HTTPS queries so clients use the IPv4 A record immediately
            return buildDnsErrorResponse(query, 0)
        }

        val response = ByteArray(questionEnd + 16)
        System.arraycopy(query, 0, response, 0, questionEnd)
        response[2] = 0x85.toByte() // QR=1, AA=1, RD=1
        response[3] = 0x80.toByte() // RA=1, RCODE=0
        response[4] = 0; response[5] = 1 // QDCOUNT=1
        response[6] = 0; response[7] = 1 // ANCOUNT=1
        response[8] = 0; response[9] = 0 // NSCOUNT=0
        response[10] = 0; response[11] = 0 // ARCOUNT=0

        var aPos = questionEnd
        response[aPos++] = 0xC0.toByte()
        response[aPos++] = 0x0C.toByte()
        response[aPos++] = 0x00; response[aPos++] = 0x01 // TYPE A
        response[aPos++] = 0x00; response[aPos++] = 0x01 // CLASS IN
        response[aPos++] = 0x00; response[aPos++] = 0x00
        response[aPos++] = 0x00; response[aPos++] = 0x3C // TTL 60s
        response[aPos++] = 0x00; response[aPos++] = 0x04 // RDLENGTH 4
        response[aPos++] = octets[0].toByte()
        response[aPos++] = octets[1].toByte()
        response[aPos++] = octets[2].toByte()
        response[aPos] = octets[3].toByte()
        return response
    }

    private fun buildDnsErrorResponse(query: ByteArray, rcode: Int): ByteArray {
        if (query.size < 17) return query
        var questionEnd = 12
        while (questionEnd < query.size) {
            val labelLength = query[questionEnd++].toInt() and 0xff
            if (labelLength == 0) break
            if ((labelLength and 0xc0) != 0 || questionEnd + labelLength > query.size) return query
            questionEnd += labelLength
        }
        if (questionEnd + 4 > query.size) return query
        questionEnd += 4
        val response = query.copyOf(questionEnd)
        response[2] = (response[2].toInt() or 0x80).toByte() // QR
        response[3] = ((response[3].toInt() and 0xf0) or (rcode and 0x0f)).toByte()
        response[6] = 0
        response[7] = 0
        response[8] = 0
        response[9] = 0
        response[10] = 0
        response[11] = 0
        return response
    }

    private fun extractQueryName(payload: ByteArray): String? {
        if (payload.size < 17) return null
        var pos = 12
        val labels = mutableListOf<String>()
        while (pos < payload.size) {
            val length = payload[pos++].toInt() and 0xff
            if (length == 0) break
            if ((length and 0xc0) != 0 || pos + length > payload.size) return null
            labels += String(payload, pos, length, StandardCharsets.US_ASCII)
            pos += length
        }
        return labels.takeIf { it.isNotEmpty() }?.joinToString(".")?.lowercase(Locale.ROOT)
    }

    private fun isValidDnsReply(resp: ByteArray?): Boolean {
        if (resp == null || resp.size < 12) return false
        val rcode = resp[3].toInt() and 0x0F
        return rcode != 2 && rcode != 5
    }

    fun resolveHostnameToIp(
        context: Context,
        hostname: String,
        config: ZeroTrustDnsConfig,
        vpnService: VpnService? = null,
        preferredNetwork: Network? = null
    ): String? {
        val cleanHost = hostname.trim().lowercase(Locale.ROOT)
        if (cleanHost.isEmpty()) return null
        if (cleanHost.all { it.isDigit() || it == '.' } || cleanHost.contains(':')) {
            return cleanHost
        }
        hostIpCache[cleanHost]?.let { return it }

        val query = buildSimpleDnsAQuery(cleanHost)
        val response = resolveRawDnsQuery(
            context = context,
            dnsQueryPayload = query,
            config = config,
            vpnService = vpnService,
            preferredNetwork = preferredNetwork
        ) ?: return null
        return parseFirstARecordFromDnsResponse(response)?.also {
            hostIpCache[cleanHost] = it
        }
    }

    private fun queryUdpDns(
        serverIp: String,
        port: Int,
        dnsQueryPayload: ByteArray,
        vpnService: VpnService?,
        timeoutMs: Int = 1800,
        protectSocket: Boolean = true,
        preferredNetwork: Network? = null
    ): ByteArray? {
        val socket = DatagramSocket()
        val sourcePort = socket.localPort
        activeDnsUpstreamPorts.add(sourcePort)
        return try {
            if (protectSocket) {
                vpnService?.protect(socket)
                preferredNetwork?.let { runCatching { it.bindSocket(socket) } }
            }
            socket.soTimeout = timeoutMs
            val addr = InetAddress.getByName(serverIp)
            socket.send(DatagramPacket(dnsQueryPayload, dnsQueryPayload.size, addr, port))
            val rxBuf = ByteArray(1500)
            val rxPkt = DatagramPacket(rxBuf, rxBuf.size)
            socket.receive(rxPkt)
            rxBuf.copyOf(rxPkt.length)
        } finally {
            activeDnsUpstreamPorts.remove(sourcePort)
            runCatching { socket.close() }
        }
    }

    /**
     * Queries DNS-over-TLS (port 853) reusing a persistent TLS socket across queries.
     * Reconnects once transparently if the persistent socket was closed by the server.
     */
    private fun queryDotPersistent(
        dotHost: String,
        dnsQueryPayload: ByteArray,
        bootstrapDnsIps: List<String>,
        vpnService: VpnService?
    ): ByteArray? {
        synchronized(dotLock) {
            for (attempt in 0..1) {
                try {
                    var ssl = persistentDotSocket
                    if (ssl == null || ssl.isClosed || !ssl.isConnected || persistentDotHost != dotHost) {
                        runCatching { ssl?.close() }
                        val targetIp = resolveBootstrapIp(dotHost, bootstrapDnsIps, vpnService) ?: return null
                        val rawSocket = Socket()
                        vpnService?.protect(rawSocket)
                        rawSocket.keepAlive = true
                        rawSocket.tcpNoDelay = true
                        rawSocket.connect(InetSocketAddress(InetAddress.getByName(targetIp), 853), 2500)
                        rawSocket.soTimeout = 2500

                        val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
                        ssl = (sslFactory.createSocket(rawSocket, dotHost, 853, true) as SSLSocket).apply {
                            sslParameters = sslParameters.apply {
                                serverNames = listOf(SNIHostName(dotHost))
                                endpointIdentificationAlgorithm = "HTTPS"
                            }
                            startHandshake()
                        }
                        persistentDotSocket = ssl
                        persistentDotHost = dotHost
                        persistentDotOut = DataOutputStream(ssl.outputStream)
                        persistentDotIn = DataInputStream(BufferedInputStream(ssl.inputStream))
                    }

                    val out = persistentDotOut!!
                    val input = persistentDotIn!!
                    out.writeShort(dnsQueryPayload.size)
                    out.write(dnsQueryPayload)
                    out.flush()

                    val respLen = input.readUnsignedShort()
                    val respBytes = ByteArray(respLen)
                    input.readFully(respBytes)
                    if (respBytes.size >= 2 && dnsQueryPayload.size >= 2) {
                        respBytes[0] = dnsQueryPayload[0]
                        respBytes[1] = dnsQueryPayload[1]
                    }
                    return respBytes
                } catch (_: Exception) {
                    runCatching { persistentDotSocket?.close() }
                    persistentDotSocket = null
                    persistentDotOut = null
                    persistentDotIn = null
                }
            }
            return null
        }
    }

    /**
     * Queries DNS-over-HTTPS (RFC 8484) reusing a persistent HTTP/1.1 Keep-Alive TLS socket.
     */
    private fun queryDohPersistent(
        dohUrlStr: String,
        dnsQueryPayload: ByteArray,
        bootstrapDnsIps: List<String>,
        vpnService: VpnService?
    ): ByteArray? {
        val uri = URI(dohUrlStr)
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 443
        val hostPortKey = "$host:$port"
        val path = buildString {
            append(if (uri.rawPath.isNullOrEmpty()) "/dns-query" else uri.rawPath)
            if (!uri.rawQuery.isNullOrEmpty()) {
                append('?').append(uri.rawQuery)
            }
        }

        synchronized(dohLock) {
            for (attempt in 0..1) {
                try {
                    var ssl = persistentDohSocket
                    if (ssl == null || ssl.isClosed || !ssl.isConnected || persistentDohHostPort != hostPortKey) {
                        runCatching { ssl?.close() }
                        val targetIp = resolveBootstrapIp(host, bootstrapDnsIps, vpnService) ?: return null
                        val rawSocket = Socket()
                        vpnService?.protect(rawSocket)
                        rawSocket.keepAlive = true
                        rawSocket.tcpNoDelay = true
                        rawSocket.connect(InetSocketAddress(InetAddress.getByName(targetIp), port), 2500)
                        rawSocket.soTimeout = 2500

                        val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
                        ssl = (sslFactory.createSocket(rawSocket, host, port, true) as SSLSocket).apply {
                            sslParameters = sslParameters.apply {
                                serverNames = listOf(SNIHostName(host))
                                endpointIdentificationAlgorithm = "HTTPS"
                            }
                            startHandshake()
                        }
                        persistentDohSocket = ssl
                        persistentDohHostPort = hostPortKey
                    }

                    val requestHeaders = buildString {
                        append("POST $path HTTP/1.1\r\n")
                        append("Host: $host\r\n")
                        append("Content-Type: application/dns-message\r\n")
                        append("Accept: application/dns-message\r\n")
                        append("Content-Length: ${dnsQueryPayload.size}\r\n")
                        append("Connection: keep-alive\r\n\r\n")
                    }

                    val out = ssl.outputStream
                    out.write(requestHeaders.toByteArray(StandardCharsets.US_ASCII))
                    out.write(dnsQueryPayload)
                    out.flush()

                    val body = readHttpKeepAliveResponse(ssl.inputStream) ?: throw IllegalStateException("Empty DoH body")
                    if (body.size >= 2 && dnsQueryPayload.size >= 2) {
                        body[0] = dnsQueryPayload[0]
                        body[1] = dnsQueryPayload[1]
                    }
                    return body
                } catch (_: Exception) {
                    runCatching { persistentDohSocket?.close() }
                    persistentDohSocket = null
                }
            }
            return null
        }
    }

    private fun readHttpKeepAliveResponse(input: InputStream): ByteArray? {
        val headerBuf = ByteArrayOutputStream(256)
        var state = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            headerBuf.write(b)
            state = when {
                state == 0 && b == '\r'.code -> 1
                state == 1 && b == '\n'.code -> 2
                state == 2 && b == '\r'.code -> 3
                state == 3 && b == '\n'.code -> 4
                b == '\r'.code -> 1
                else -> 0
            }
            if (state == 4) break
            if (headerBuf.size() > 8192) return null
        }

        val headerText = String(headerBuf.toByteArray(), StandardCharsets.ISO_8859_1)
        if (!headerText.startsWith("HTTP/1.1 200") && !headerText.startsWith("HTTP/1.0 200")) {
            return null
        }

        var contentLength = -1
        var isChunked = false
        for (line in headerText.split("\r\n")) {
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = line.substringAfter(':').trim().toIntOrNull() ?: -1
            } else if (line.startsWith("Transfer-Encoding:", ignoreCase = true) &&
                line.contains("chunked", ignoreCase = true)
            ) {
                isChunked = true
            }
        }

        if (contentLength >= 0) {
            val body = ByteArray(contentLength)
            var readTotal = 0
            while (readTotal < contentLength) {
                val r = input.read(body, readTotal, contentLength - readTotal)
                if (r < 0) return null
                readTotal += r
            }
            return body
        } else if (isChunked) {
            val out = ByteArrayOutputStream()
            while (true) {
                val lineSb = StringBuilder()
                while (true) {
                    val ch = input.read()
                    if (ch < 0) return null
                    if (ch == '\r'.code) {
                        input.read() // consume \n
                        break
                    }
                    lineSb.append(ch.toChar())
                }
                val chunkSize = lineSb.toString().trim().substringBefore(';').toIntOrNull(16) ?: break
                if (chunkSize == 0) {
                    input.read(); input.read() // trailing \r\n
                    break
                }
                val chunk = ByteArray(chunkSize)
                var readTotal = 0
                while (readTotal < chunkSize) {
                    val r = input.read(chunk, readTotal, chunkSize - readTotal)
                    if (r < 0) return null
                    readTotal += r
                }
                out.write(chunk)
                input.read(); input.read() // consume \r\n after chunk
            }
            return out.toByteArray()
        }
        return null
    }

    private fun resolveBootstrapIp(
        host: String,
        bootstrapDnsIps: List<String>,
        vpnService: VpnService?
    ): String? {
        if (host.all { it.isDigit() || it == '.' } || host.contains(':')) {
            return host
        }
        hostIpCache[host]?.let { return it }

        val query = buildSimpleDnsAQuery(host)
        for (dnsIp in bootstrapDnsIps) {
            val resp = runCatching { queryUdpDns(dnsIp, 53, query, vpnService, timeoutMs = 1500) }.getOrNull()
            if (resp != null) {
                val resolvedIp = parseFirstARecordFromDnsResponse(resp)
                if (!resolvedIp.isNullOrBlank()) {
                    hostIpCache[host] = resolvedIp
                    return resolvedIp
                }
            }
        }

        return runCatching {
            val addr = InetAddress.getAllByName(host).firstOrNull { it is Inet4Address }
                ?: InetAddress.getByName(host)
            addr.hostAddress?.also { hostIpCache[host] = it }
        }.getOrNull()
    }

    fun buildSimpleDnsAQuery(domain: String): ByteArray {
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        data.writeShort(0x4242)
        data.writeShort(0x0100)
        data.writeShort(1)
        data.writeShort(0)
        data.writeShort(0)
        data.writeShort(0)

        for (label in domain.trim().trimEnd('.').split('.')) {
            val bytes = label.toByteArray(StandardCharsets.US_ASCII)
            data.writeByte(bytes.size)
            data.write(bytes)
        }
        data.writeByte(0)
        data.writeShort(1)
        data.writeShort(1)
        return out.toByteArray()
    }

    fun parseFirstARecordFromDnsResponse(resp: ByteArray): String? {
        if (resp.size < 12) return null
        val qdCount = ((resp[4].toInt() and 0xFF) shl 8) or (resp[5].toInt() and 0xFF)
        val anCount = ((resp[6].toInt() and 0xFF) shl 8) or (resp[7].toInt() and 0xFF)
        if (anCount <= 0) return null

        var pos = 12
        for (q in 0 until qdCount) {
            pos = skipDnsName(resp, pos)
            if (pos < 0 || pos + 4 > resp.size) return null
            pos += 4
        }

        for (a in 0 until anCount) {
            pos = skipDnsName(resp, pos)
            if (pos < 0 || pos + 10 > resp.size) return null
            val rtype = ((resp[pos].toInt() and 0xFF) shl 8) or (resp[pos + 1].toInt() and 0xFF)
            val rdLength = ((resp[pos + 8].toInt() and 0xFF) shl 8) or (resp[pos + 9].toInt() and 0xFF)
            pos += 10
            if (pos + rdLength > resp.size) return null
            if (rtype == 1 && rdLength == 4) {
                return "${resp[pos].toInt() and 0xFF}.${resp[pos + 1].toInt() and 0xFF}." +
                    "${resp[pos + 2].toInt() and 0xFF}.${resp[pos + 3].toInt() and 0xFF}"
            }
            pos += rdLength
        }
        return null
    }

    private fun skipDnsName(buf: ByteArray, startPos: Int): Int {
        var pos = startPos
        while (pos < buf.size) {
            val len = buf[pos].toInt() and 0xFF
            if (len == 0) return pos + 1
            if ((len and 0xC0) == 0xC0) return pos + 2
            pos += 1 + len
        }
        return -1
    }
}
