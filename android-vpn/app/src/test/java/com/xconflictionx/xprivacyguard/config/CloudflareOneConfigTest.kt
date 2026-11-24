package com.xconflictionx.xprivacyguard.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class CloudflareOneConfigTest {

    private fun makeJwt(expEpochSec: Long): String {
        val header = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
        val payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"sub":"user@example.com","exp":$expEpochSec}""".toByteArray())
        return "$header.$payload.signature"
    }

    @Test
    fun normalizeTeamName_stripsUrlAndSuffix() {
        assertEquals("my-org", CloudflareOneConfig.normalizeTeamName("https://My-Org.cloudflareaccess.com/warp"))
        assertEquals("my-org", CloudflareOneConfig.normalizeTeamName("my-org.cloudflareaccess.com"))
        assertEquals("my-org", CloudflareOneConfig.normalizeTeamName("  MY-ORG  "))
        assertEquals("", CloudflareOneConfig.normalizeTeamName("bad name!"))
        assertEquals("", CloudflareOneConfig.normalizeTeamName(""))
    }

    @Test
    fun jwtExpiration_detectsExpiredAndValidTokens() {
        val now = 1_800_000_000L
        val validJwt = makeJwt(now + 3600L)
        val shortLivedJwt = makeJwt(now + 45L)
        val expiredJwt = makeJwt(now - 10L)

        assertFalse(CloudflareOneConfig.isJwtExpired(validJwt, now))
        assertFalse(CloudflareOneConfig.isJwtExpired(shortLivedJwt, now))
        assertTrue(CloudflareOneConfig.isJwtExpired(expiredJwt, now))
        assertTrue(CloudflareOneConfig.isJwtExpired("not-a-jwt", now))
    }

    @Test
    fun canConnect_requiresEnrolledOrValidAuth() {
        val empty = CloudflareOneConfig()
        assertFalse(empty.canConnect)

        val enrolled = CloudflareOneConfig(
            teamName = "my-org",
            deviceId = "dev-123",
            ipv4Address = "172.16.0.2/32"
        )
        assertTrue(enrolled.isEnrolled)
        assertTrue(enrolled.canConnect)

        val serviceToken = CloudflareOneConfig(
            teamName = "my-org",
            serviceClientId = "id.access",
            serviceClientSecret = "secret"
        )
        assertTrue(serviceToken.canConnect)
    }
}

