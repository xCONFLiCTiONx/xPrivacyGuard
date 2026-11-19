package cc.xconflictionx.xprivacyguard

import cc.xconflictionx.xprivacyguard.vpn.BlocklistFilter
import cc.xconflictionx.xprivacyguard.vpn.FilterListManager
import cc.xconflictionx.xprivacyguard.vpn.PrivacyVpnService
import cc.xconflictionx.xprivacyguard.vpn.VpnConfig
import cc.xconflictionx.xprivacyguard.vpn.VpnWatchdog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterListManagerTest {

    @Test
    fun testParseRuleCommentsAndMetadata() {
        assertNull(FilterListManager.parseRule("! Title: xCONFLiCTiONx Privacy Filters"))
        assertNull(FilterListManager.parseRule("# Comment line"))
        assertNull(FilterListManager.parseRule("[Adblock Plus 2.0]"))
        assertNull(FilterListManager.parseRule("; comment"))
    }

    @Test
    fun testParseRuleElementHidingCosmeticRulesIgnored() {
        assertNull(FilterListManager.parseRule("example.com##.ad-banner"))
        assertNull(FilterListManager.parseRule("##.ad-container"))
        assertNull(FilterListManager.parseRule("example.com#@#.ad-banner"))
    }

    @Test
    fun testParseRuleAdblockBlockRules() {
        val rule1 = FilterListManager.parseRule("||doubleclick.net^")
        assertNotNull(rule1)
        assertEquals("doubleclick.net", rule1?.domain)
        assertFalse(rule1!!.isAllow)

        val rule2 = FilterListManager.parseRule("||analytics.google.com^\$third-party,image")
        assertNotNull(rule2)
        assertEquals("analytics.google.com", rule2?.domain)
        assertFalse(rule2!!.isAllow)

        val rule3 = FilterListManager.parseRule("||badsite.com")
        assertNotNull(rule3)
        assertEquals("badsite.com", rule3?.domain)
        assertFalse(rule3!!.isAllow)
    }

    @Test
    fun testParseRuleXConflictionxFilterFormat() {
        val rule1 = FilterListManager.parseRule("||*.xconflictionx.cc^")
        assertNotNull(rule1)
        assertEquals("xconflictionx.cc", rule1?.domain)

        val rule2 = FilterListManager.parseRule("||privacy-test.xconflictionx.cc^")
        assertNotNull(rule2)
        assertEquals("privacy-test.xconflictionx.cc", rule2?.domain)

        val rule3 = FilterListManager.parseRule("127.0.0.1 analytics.xconflictionx.cc # test domain")
        assertNotNull(rule3)
        assertEquals("analytics.xconflictionx.cc", rule3?.domain)
    }

    @Test
    fun testParseRuleHostsFormat() {
        val rule1 = FilterListManager.parseRule("0.0.0.0 tracker.example.org")
        assertNotNull(rule1)
        assertEquals("tracker.example.org", rule1?.domain)

        val rule2 = FilterListManager.parseRule("127.0.0.1 adserver.test.com")
        assertNotNull(rule2)
        assertEquals("adserver.test.com", rule2?.domain)
    }

    @Test
    fun testParseRuleAllowlistRules() {
        val rule = FilterListManager.parseRule("@@||allowed-service.com^")
        assertNotNull(rule)
        assertEquals("allowed-service.com", rule?.domain)
        assertTrue(rule!!.isAllow)
    }

    @Test
    fun testParseFilterContentSample() {
        val sampleContent = "!" + " Title: Sample filter list\n" +
                "[Adblock Plus 2.0]\n" +
                "!\n" +
                "||doubleclick.net^\n" +
                "||analytics.google.com^\$third-party\n" +
                "0.0.0.0 badtracker.net\n" +
                "@@||allowed-analytics.google.com^\n" +
                "example.com##.ad-banner\n" +
                "||xconflictionx.cc^"

        val (blocks, allows) = FilterListManager.parseFilterContent(sampleContent)

        assertTrue(blocks.contains("doubleclick.net"))
        assertTrue(blocks.contains("analytics.google.com"))
        assertTrue(blocks.contains("badtracker.net"))
        assertTrue(blocks.contains("xconflictionx.cc"))
        assertTrue(allows.contains("allowed-analytics.google.com"))
        assertFalse(blocks.contains("example.com"))
    }

    @Test
    fun testParseJsonFilterRules() {
        val jsonRules = """
            [
              {
                "id": 1,
                "action": { "type": "block" },
                "condition": {
                  "requestDomains": ["vac.peacocktv.com", "amazon-adsystem.com"]
                }
              },
              {
                "id": 2,
                "action": { "type": "allow" },
                "condition": {
                  "requestDomains": ["assets.adobedtm.com"]
                }
              },
              {
                "id": 3,
                "action": { "type": "block" },
                "condition": {
                  "urlFilter": "||xprivacyguard-status.xconflictionx.cc^"
                }
              }
            ]
        """.trimIndent()

        val (blocks, allows) = FilterListManager.parseFilterContent(jsonRules)

        assertTrue(blocks.contains("vac.peacocktv.com"))
        assertTrue(blocks.contains("amazon-adsystem.com"))
        assertTrue(blocks.contains("xprivacyguard-status.xconflictionx.cc"))
        assertTrue(allows.contains("assets.adobedtm.com"))
    }

    @Test
    fun testBlocklistFilterWithCustomFilters() {
        VpnConfig.customFiltersEnabled = true
        val sampleContent = "||analytics.xconflictionx.cc^\n||google-analytics.com^"
        val (blocks, _) = FilterListManager.parseFilterContent(sampleContent)

        val blocklistFilter = BlocklistFilter()

        // Inject blocked domains manually into FilterListManager
        val method = FilterListManager::class.java.getDeclaredField("blockedDomains")
        method.isAccessible = true
        method.set(FilterListManager, blocks)

        val result1 = blocklistFilter.checkDomain("analytics.xconflictionx.cc")
        assertTrue(result1.isBlocked)
        assertEquals("Custom Filter List", result1.reason)

        val result2 = blocklistFilter.checkDomain("sub.analytics.xconflictionx.cc")
        assertTrue(result2.isBlocked)
        assertEquals("Custom Filter List", result2.reason)

        val result3 = blocklistFilter.checkDomain("google-analytics.com")
        assertTrue(result3.isBlocked)
        assertEquals("Custom Filter List", result3.reason)
    }

    @Test
    fun testDualStackDnsConfig() {
        VpnConfig.dnsIpv4 = "94.140.14.14, 94.140.15.15"
        VpnConfig.dnsIpv6 = "2a10:50c0::ad1:ff, 2a10:50c0::ad2:ff"

        val ipv4List = VpnConfig.getDnsIpv4List()
        assertEquals(2, ipv4List.size)
        assertEquals("94.140.14.14", ipv4List[0])
        assertEquals("94.140.15.15", ipv4List[1])

        val ipv6List = VpnConfig.getDnsIpv6List()
        assertEquals(2, ipv6List.size)
        assertEquals("2a10:50c0::ad1:ff", ipv6List[0])
        assertEquals("2a10:50c0::ad2:ff", ipv6List[1])

        val allIps = VpnConfig.getAllDnsIps()
        assertEquals(4, allIps.size)
        assertTrue(allIps.contains("94.140.14.14"))
        assertTrue(allIps.contains("2a10:50c0::ad1:ff"))
    }

    @Test
    fun testCloudflareSecurityDnsConfig() {
        VpnConfig.dnsEndpoint = "security.cloudflare-dns.com"
        VpnConfig.dnsIpv4 = "1.1.1.2, 1.0.0.2"
        VpnConfig.dnsIpv6 = "2606:4700:4700::1112, 2606:4700:4700::1002"

        val ipv4List = VpnConfig.getDnsIpv4List()
        assertEquals(2, ipv4List.size)
        assertEquals("1.1.1.2", ipv4List[0])
        assertEquals("1.0.0.2", ipv4List[1])

        val ipv6List = VpnConfig.getDnsIpv6List()
        assertEquals(2, ipv6List.size)
        assertEquals("2606:4700:4700::1112", ipv6List[0])
        assertEquals("2606:4700:4700::1002", ipv6List[1])

        val allIps = VpnConfig.getAllDnsIps()
        assertEquals(4, allIps.size)
        assertTrue(allIps.contains("1.1.1.2"))
        assertTrue(allIps.contains("2606:4700:4700::1112"))
    }

    @Test
    fun testAutoUpdateConfigAndTiming() {
        assertEquals(24 * 60 * 60 * 1000L, FilterListManager.ONE_DAY_MILLIS)
        assertTrue(VpnConfig.customFiltersAutoUpdate)

        VpnConfig.customFiltersAutoUpdate = false
        assertFalse(VpnConfig.customFiltersAutoUpdate)
        VpnConfig.customFiltersAutoUpdate = true
        assertTrue(VpnConfig.customFiltersAutoUpdate)
    }

    @Test
    fun testWatchdogIntervalAndVpnDetectionHelpers() {
        assertEquals(60_000L, VpnWatchdog.CHECK_INTERVAL_MS)
        assertEquals(60_000L, VpnWatchdog.OTHER_VPN_GRACE_PERIOD_MS)
        assertEquals(60_000L, PrivacyVpnService.OTHER_VPN_GRACE_PERIOD_MS)

        assertTrue(PrivacyVpnService.isOurTunnelAddress("10.120.0.1"))
        assertTrue(PrivacyVpnService.isOurTunnelAddress("10.120.0.2"))
        assertTrue(PrivacyVpnService.isOurTunnelAddress("fd00:120::1"))
        assertTrue(PrivacyVpnService.isOurTunnelAddress("fd00:120:0:0:0:0:0:1%tun0"))
        assertFalse(PrivacyVpnService.isOurTunnelAddress("10.8.0.2"))
        assertFalse(PrivacyVpnService.isOurTunnelAddress("100.64.0.1"))

        assertTrue(PrivacyVpnService.isVpnInterfaceName("tun0"))
        assertTrue(PrivacyVpnService.isVpnInterfaceName("wg0"))
        assertTrue(PrivacyVpnService.isVpnInterfaceName("ppp0"))
        assertTrue(PrivacyVpnService.isVpnInterfaceName("ipsec0"))
        assertFalse(PrivacyVpnService.isVpnInterfaceName("tunl0"))
        assertFalse(PrivacyVpnService.isVpnInterfaceName("wlan0"))
        assertFalse(PrivacyVpnService.isVpnInterfaceName("rmnet_data0"))
    }

    @Test
    fun testOtherVpnHandoffAndGracePeriodNeverCutsOffNearEndOfTimeout() {
        PrivacyVpnService.clearYieldState()
        PrivacyVpnService.hasConnectedOnceInSession = false

        // Fresh startup with no prior preemption or other VPN: does not defer
        assertFalse(PrivacyVpnService.shouldDeferByTiming(otherVpnCurrentlyConnected = false, nowMs = 1_000L))

        // Preempted by another VPN at t=10_000ms
        PrivacyVpnService.onPreemptedByAnotherVpn(context = null, nowMs = 10_000L)

        // Even if a check happens 5s later (e.g. near end of a prior timer) while other VPN is still handshaking: must defer
        assertTrue(PrivacyVpnService.shouldDeferByTiming(otherVpnCurrentlyConnected = false, nowMs = 15_000L))

        // At t=30_000ms, the other VPN finishes connecting
        assertTrue(PrivacyVpnService.shouldDeferByTiming(otherVpnCurrentlyConnected = true, nowMs = 30_000L))

        // Suppose at t=88_000ms (near the end of a 60s tick), the other VPN drops briefly to switch servers
        // Check runs 2s later at t=90_000ms: because uninterruptedNoVpnSinceMs was 0L, it starts a fresh 60s window and defers!
        assertTrue(PrivacyVpnService.shouldDeferByTiming(otherVpnCurrentlyConnected = false, nowMs = 90_000L))
        assertTrue(PrivacyVpnService.shouldDeferByTiming(otherVpnCurrentlyConnected = false, nowMs = 149_999L))

        // Only after a full 60s of uninterrupted "no other VPN" (at t=150_000ms) is auto-reconnect allowed
        assertFalse(PrivacyVpnService.shouldDeferByTiming(otherVpnCurrentlyConnected = false, nowMs = 150_000L))

        PrivacyVpnService.clearYieldState()
    }
}
