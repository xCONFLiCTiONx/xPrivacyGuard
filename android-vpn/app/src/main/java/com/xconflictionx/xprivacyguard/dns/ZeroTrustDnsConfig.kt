package com.xconflictionx.xprivacyguard.dns

import java.util.Locale

data class ZeroTrustDnsConfig(
    val ipv4Primary: String = "",
    val ipv4Secondary: String = "",
    val sourceIpv4: String = "",
    val ipv6Endpoint: String = "",
    val sourceIpv6: String = "",
    val dotEndpoint: String = "",
    val dohEndpoint: String = "",
    val privateDomain: String = "",
    val privateDnsServer: String = ""
) {
    /**
     * Returns true if the user has filled in at least one custom Zero Trust DNS Location field.
     * When false, the app defaults to System DNS.
     */
    val isCustomConfigured: Boolean
        get() = ipv4Primary.isNotBlank() ||
            ipv4Secondary.isNotBlank() ||
            ipv6Endpoint.isNotBlank() ||
            dotEndpoint.isNotBlank() ||
            dohEndpoint.isNotBlank()

    fun configuredIpv4List(): List<String> =
        listOf(ipv4Primary.trim(), ipv4Secondary.trim())
            .filter { it.isNotEmpty() && !it.equals("--", ignoreCase = true) }

    fun configuredIpv6List(): List<String> =
        listOf(ipv6Endpoint.trim())
            .filter { it.isNotEmpty() && !it.equals("--", ignoreCase = true) }

    fun normalizedDotHost(): String? {
        val raw = dotEndpoint.trim()
        if (raw.isEmpty() || raw == "--") return null
        return raw.removePrefix("tls://").substringBefore('/').substringBefore(':').trim()
            .takeIf { it.isNotEmpty() }
    }

    fun normalizedDohUrl(): String? {
        val raw = dohEndpoint.trim()
        if (raw.isEmpty() || raw == "--") return null
        return if (raw.startsWith("http://", ignoreCase = true) ||
            raw.startsWith("https://", ignoreCase = true)
        ) {
            raw
        } else {
            "https://$raw/dns-query"
        }
    }

    val isPrivateDnsConfigured: Boolean
        get() = privateDomain.isNotBlank() && privateDnsServer.isNotBlank()

    fun matchesPrivateDomain(hostname: String): Boolean {
        val domain = privateDomain.trim().trimStart(46.toChar()).trimEnd(46.toChar()).lowercase(Locale.ROOT)
        val host = hostname.trim().trimEnd(46.toChar()).lowercase(Locale.ROOT)
        return domain.isNotEmpty() && (host == domain || host.endsWith(".$domain"))
    }

    fun activeModeSummary(): String {
        if (isPrivateDnsConfigured) return "Split DNS (${privateDomain.trim()})"
        if (!isCustomConfigured) return "WireGuard DNS"
        val parts = mutableListOf<String>()
        if (normalizedDohUrl() != null) parts.add("DoH")
        if (normalizedDotHost() != null) parts.add("DoT")
        if (configuredIpv4List().isNotEmpty()) parts.add("IPv4 (${configuredIpv4List().joinToString(", ")})")
        if (configuredIpv6List().isNotEmpty()) parts.add("IPv6")
        return "Zero Trust Location (${parts.joinToString(" + ")})"
    }
}

