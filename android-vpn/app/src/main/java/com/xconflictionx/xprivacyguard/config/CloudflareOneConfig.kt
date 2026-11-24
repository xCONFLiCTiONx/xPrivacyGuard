package com.xconflictionx.xprivacyguard.config

import java.util.Base64

/**
 * Persisted configuration and enrollment metadata for Cloudflare One (MASQUE via Aether).
 * WireGuard settings remain completely separate so both transports can coexist safely.
 */
data class CloudflareOneConfig(
    val teamName: String = "",
    val userEmail: String = "",
    val accessToken: String = "",
    val serviceClientId: String = "",
    val serviceClientSecret: String = "",
    val customPeerEndpoint: String = "",
    val preferHttp2: Boolean = false,
    val enableEch: Boolean = false,
    val deviceId: String = "",
    val organization: String = "",
    val ipv4Address: String = "",
    val ipv6Address: String = "",
    val assignedEndpoint: String = "",
    val certIssuedAt: Long = 0L
) {
    val normalizedTeam: String
        get() = normalizeTeamName(teamName)

    val hasValidToken: Boolean
        get() = accessToken.isNotBlank() && !isJwtExpired(accessToken)

    val hasServiceToken: Boolean
        get() = serviceClientId.isNotBlank() && serviceClientSecret.isNotBlank()

    val isEnrolled: Boolean
        get() = deviceId.isNotBlank() && ipv4Address.isNotBlank()

    val canConnect: Boolean
        get() = isEnrolled || (normalizedTeam.isNotEmpty() && (hasValidToken || hasServiceToken))

    val displayEndpoint: String
        get() = when {
            customPeerEndpoint.isNotBlank() -> customPeerEndpoint.trim()
            assignedEndpoint.isNotBlank() -> assignedEndpoint.trim()
            else -> "Cloudflare MASQUE (Auto)"
        }

    val displayAddresses: String
        get() = listOf(ipv4Address, ipv6Address)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(", ")
            .ifEmpty { "Assigned on MASQUE connect" }

    companion object {
        fun normalizeTeamName(raw: String): String {
            var s = raw.trim().lowercase()
            s = s.removePrefix("https://").removePrefix("http://")
            s = s.substringBefore('/')
            s = s.removeSuffix(".cloudflareaccess.com")
            s = s.trim('.')
            return if (s.isNotEmpty() && s.all { it.isLetterOrDigit() || it == '-' }) s else ""
        }

        private val EXP_CLAIM_REGEX = Regex("\"exp\"\\s*:\\s*(\\d+)")

        /**
         * Returns true if [jwt] is malformed or its `exp` claim is at or before [nowEpochSec].
         * Matches Aether's `zerotrust::jwt_expired` check (no artificial skew margin, since
         * Cloudflare WARP enrollment JWTs can be short-lived).
         */
        fun isJwtExpired(jwt: String, nowEpochSec: Long = System.currentTimeMillis() / 1000L): Boolean {
            val parts = jwt.trim().split('.')
            if (parts.size != 3 || parts.any { it.isBlank() }) return true
            return try {
                val payloadJson = String(Base64.getUrlDecoder().decode(padBase64Url(parts[1])), Charsets.UTF_8)
                val match = EXP_CLAIM_REGEX.find(payloadJson) ?: return false
                val exp = match.groupValues[1].toLongOrNull() ?: return true
                exp <= 0L || exp <= nowEpochSec
            } catch (_: Exception) {
                true
            }
        }

        private fun padBase64Url(input: String): String {
            val rem = input.length % 4
            return if (rem == 0) input else input + "=".repeat(4 - rem)
        }
    }
}

