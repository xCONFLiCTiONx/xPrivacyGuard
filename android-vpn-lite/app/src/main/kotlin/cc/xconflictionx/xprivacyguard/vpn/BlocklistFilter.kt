package cc.xconflictionx.xprivacyguard.vpn

class BlocklistFilter {

    private val adDomains = setOf(
        "doubleclick.net",
        "googleadservices.com",
        "adservice.google.com",
        "googlesyndication.com",
        "adnxs.com",
        "adform.net",
        "ads.twitter.com",
        "adserver.example.net"
    )

    private val trackerDomains = setOf(
        "googletagmanager.com",
        "google-analytics.com",
        "connect.facebook.net",
        "analytics.twitter.com",
        "telemetry.microsoft.com",
        "mixpanel.com",
        "segment.io",
        "hotjar.com",
        "tracking.example.com"
    )

    private val malwareDomains = setOf(
        "malware.example.org",
        "phishing.example.com",
        "badsite.test",
        "cryptominer.test"
    )

    private val defaultAllowDomains = setOf(
        "cloudflare-dns.com",
        "security.cloudflare-gateway.com",
        "dns.google",
        "dns.quad9.net",
        "dns.adguard-dns.com"
    )

    data class CheckResult(val isBlocked: Boolean, val reason: String)

    fun checkDomain(domain: String): CheckResult {
        val lower = domain.lowercase().trim().trimEnd('.')

        // 1. Check Custom Allowlist (User explicit override)
        if (VpnConfig.customAllowlist.any { lower == it || lower.endsWith(".$it") }) {
            return CheckResult(false, "Allowed (Custom)")
        }

        // 2. Check Custom Blocklist (User explicit block)
        if (VpnConfig.customBlocklist.any { lower == it || lower.endsWith(".$it") }) {
            return CheckResult(true, "Custom Block")
        }

        // 3. Check Custom Filter Lists (filters.txt) Module
        if (VpnConfig.customFiltersEnabled) {
            if (FilterListManager.isDomainAllowed(lower)) {
                return CheckResult(false, "Allowed (Custom Filter)")
            }
            if (FilterListManager.isDomainBlocked(lower)) {
                return CheckResult(true, "Custom Filter List")
            }
        }

        // 4. Check Ad Blocking
        if (VpnConfig.adBlockingEnabled && adDomains.any { lower == it || lower.endsWith(".$it") }) {
            return CheckResult(true, "Ad")
        }

        // 5. Check Tracker Blocking
        if (VpnConfig.trackerBlockingEnabled && trackerDomains.any { lower == it || lower.endsWith(".$it") }) {
            return CheckResult(true, "Tracker")
        }

        // 6. Check Malware Protection
        if (VpnConfig.malwareProtectionEnabled && malwareDomains.any { lower == it || lower.endsWith(".$it") }) {
            return CheckResult(true, "Malware")
        }

        // 7. Check Default System Allowlist
        if (defaultAllowDomains.contains(lower)) {
            return CheckResult(false, "Allowed (System)")
        }

        return CheckResult(false, "Allowed")
    }

    fun isBlocked(domain: String): Boolean {
        return checkDomain(domain).isBlocked
    }
}
