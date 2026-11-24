package com.xconflictionx.xprivacyguard.config

import java.util.Base64

data class CidrAddress(
    val address: String,
    val prefixLength: Int
) {
    val isIpv6: Boolean get() = address.contains(':')

    override fun toString(): String = "$address/$prefixLength"

    companion object {
        fun parse(raw: String): CidrAddress {
            val trimmed = raw.trim()
            val slashIdx = trimmed.indexOf('/')
            return if (slashIdx > 0) {
                val addr = trimmed.substring(0, slashIdx).trim()
                val prefix = trimmed.substring(slashIdx + 1).trim().toIntOrNull()
                    ?: if (addr.contains(':')) 128 else 32
                CidrAddress(addr, prefix)
            } else {
                CidrAddress(trimmed, if (trimmed.contains(':')) 128 else 32)
            }
        }
    }
}

data class WireGuardConfig(
    val privateKeyBase64: String,
    val addresses: List<CidrAddress>,
    val dnsServers: List<String>,
    val mtu: Int = 1420,
    val peerPublicKeyBase64: String,
    val presharedKeyBase64: String? = null,
    val allowedIps: List<CidrAddress>,
    val endpointHost: String,
    val endpointPort: Int,
    val persistentKeepalive: Int = 0
) {
    val endpoint: String get() = "$endpointHost:$endpointPort"

    fun privateKeyBytes(): ByteArray = Base64.getDecoder().decode(privateKeyBase64.trim())
    fun peerPublicKeyBytes(): ByteArray = Base64.getDecoder().decode(peerPublicKeyBase64.trim())
    fun routesAddress(address: String): Boolean {
        val target = runCatching { java.net.InetAddress.getByName(address).address }.getOrNull() ?: return false
        return allowedIps.any { route ->
            val network = runCatching { java.net.InetAddress.getByName(route.address).address }.getOrNull()
                ?: return@any false
            if (network.size != target.size) return@any false
            val prefix = route.prefixLength.coerceIn(0, network.size * 8)
            for (bit in 0 until prefix) {
                val mask = 1 shl (7 - (bit % 8))
                if ((network[bit / 8].toInt() and mask) != (target[bit / 8].toInt() and mask)) {
                    return@any false
                }
            }
            true
        }
    }

    fun presharedKeyBytes(): ByteArray =
        presharedKeyBase64?.takeIf { it.isNotBlank() }?.let { Base64.getDecoder().decode(it.trim()) }
            ?: ByteArray(32)

    fun toConfString(): String = buildString {
        appendLine("[Interface]")
        appendLine("PrivateKey = $privateKeyBase64")
        if (addresses.isNotEmpty()) {
            appendLine("Address = ${addresses.joinToString(", ")}")
        }
        if (dnsServers.isNotEmpty()) {
            appendLine("DNS = ${dnsServers.joinToString(", ")}")
        }
        appendLine("MTU = $mtu")
        appendLine()
        appendLine("[Peer]")
        appendLine("PublicKey = $peerPublicKeyBase64")
        if (!presharedKeyBase64.isNullOrBlank()) {
            appendLine("PresharedKey = $presharedKeyBase64")
        }
        if (allowedIps.isNotEmpty()) {
            appendLine("AllowedIPs = ${allowedIps.joinToString(", ")}")
        }
        appendLine("Endpoint = $endpointHost:$endpointPort")
        if (persistentKeepalive > 0) {
            appendLine("PersistentKeepalive = $persistentKeepalive")
        }
    }

    companion object {


        fun parse(confText: String): Result<WireGuardConfig> = runCatching {
            require(confText.isNotBlank()) { "No WireGuard configuration loaded. Import or paste a .conf file." }

            var section = ""
            var privateKey = ""
            val addresses = mutableListOf<CidrAddress>()
            val dnsServers = mutableListOf<String>()
            var mtu = 1420

            var publicKey = ""
            var presharedKey: String? = null
            val allowedIps = mutableListOf<CidrAddress>()
            var endpointHost = ""
            var endpointPort = 2408
            var persistentKeepalive = 0

            confText.lineSequence().forEach { rawLine ->
                val line = rawLine.substringBefore('#').trim()
                if (line.isEmpty()) return@forEach

                if (line.startsWith('[') && line.endsWith(']')) {
                    section = line.lowercase()
                    return@forEach
                }

                val eqIndex = line.indexOf('=')
                if (eqIndex <= 0) return@forEach

                val key = line.substring(0, eqIndex).trim().lowercase()
                val value = line.substring(eqIndex + 1).trim()

                when (section) {
                    "[interface]" -> when (key) {
                        "privatekey" -> privateKey = value
                        "address" -> value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                            .forEach { addresses.add(CidrAddress.parse(it)) }
                        "dns" -> value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                            .forEach { dnsServers.add(it) }
                        "mtu" -> mtu = value.toIntOrNull()?.coerceIn(1280, 1500) ?: 1420
                    }
                    "[peer]" -> when (key) {
                        "publickey" -> publicKey = value
                        "presharedkey" -> presharedKey = value
                        "allowedips" -> value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                            .forEach { allowedIps.add(CidrAddress.parse(it)) }
                        "endpoint" -> {
                            val parsed = parseEndpoint(value)
                            endpointHost = parsed.first
                            endpointPort = parsed.second
                        }
                        "persistentkeepalive" -> persistentKeepalive = value.toIntOrNull() ?: 25
                    }
                }
            }

            require(privateKey.isNotBlank()) { "Missing [Interface] PrivateKey" }
            require(Base64.getDecoder().decode(privateKey).size == 32) {
                "PrivateKey must be a 32-byte base64 key"
            }
            require(publicKey.isNotBlank()) { "Missing [Peer] PublicKey" }
            require(Base64.getDecoder().decode(publicKey).size == 32) {
                "PublicKey must be a 32-byte base64 key"
            }
            require(endpointHost.isNotBlank()) { "Missing [Peer] Endpoint" }
            require(addresses.isNotEmpty()) { "Missing [Interface] Address" }

            if (dnsServers.isEmpty()) {
                dnsServers.addAll(listOf("1.1.1.1", "1.0.0.1"))
            }
            if (allowedIps.isEmpty()) {
                allowedIps.add(CidrAddress("0.0.0.0", 0))
                allowedIps.add(CidrAddress("::", 0))
            }

            WireGuardConfig(
                privateKeyBase64 = privateKey,
                addresses = addresses,
                dnsServers = dnsServers,
                mtu = mtu,
                peerPublicKeyBase64 = publicKey,
                presharedKeyBase64 = presharedKey,
                allowedIps = allowedIps,
                endpointHost = endpointHost,
                endpointPort = endpointPort,
                persistentKeepalive = persistentKeepalive
            )
        }

        private fun parseEndpoint(endpoint: String): Pair<String, Int> {
            val trimmed = endpoint.trim()
            return if (trimmed.startsWith('[')) {
                val closeBracket = trimmed.indexOf(']')
                require(closeBracket > 1) { "Invalid IPv6 endpoint: $endpoint" }
                val host = trimmed.substring(1, closeBracket)
                val portPart = trimmed.substring(closeBracket + 1).removePrefix(":")
                val port = portPart.toIntOrNull() ?: 2408
                host to port
            } else {
                val lastColon = trimmed.lastIndexOf(':')
                require(lastColon > 0) { "Endpoint must include host:port" }
                val host = trimmed.substring(0, lastColon).trim()
                val port = trimmed.substring(lastColon + 1).trim().toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid port in endpoint: $endpoint")
                host to port
            }
        }
    }
}
