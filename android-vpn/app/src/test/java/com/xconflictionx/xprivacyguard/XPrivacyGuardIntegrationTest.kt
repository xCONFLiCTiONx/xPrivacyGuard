package com.xconflictionx.xprivacyguard

import com.xconflictionx.xprivacyguard.config.CidrAddress
import com.xconflictionx.xprivacyguard.config.WireGuardConfig
import com.xconflictionx.xprivacyguard.dns.ZeroTrustDnsConfig
import com.xconflictionx.xprivacyguard.dns.ZeroTrustDnsResolver
import com.xconflictionx.xprivacyguard.filter.FilterEngine
import com.xconflictionx.xprivacyguard.filter.FilterSource
import com.xconflictionx.xprivacyguard.filter.FilterSourceRepository
import com.xconflictionx.xprivacyguard.filter.FilterSourceType
import com.xconflictionx.xprivacyguard.firewall.FirewallManager
import com.xconflictionx.xprivacyguard.firewall.InstalledAppInfo
import com.xconflictionx.xprivacyguard.firewall.SpecialBypassGroup
import com.xconflictionx.xprivacyguard.packet.PacketAction
import com.xconflictionx.xprivacyguard.packet.PacketInspector
import com.xconflictionx.xprivacyguard.wireguard.WireGuardCrypto
import com.xconflictionx.xprivacyguard.wireguard.WireGuardSession
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Base64

class XPrivacyGuardIntegrationTest {

    @Test
    fun `packet inspector fails open for truncated packet buffers`() {
        val malformedPacket = ByteArray(20)
        malformedPacket[0] = 0x45

        val action = PacketInspector.inspectOutboundPacket(
            packet = malformedPacket,
            length = 40,
            filterEngine = FilterEngine()
        )

        assertEquals(PacketAction.ForwardToWireGuard, action)
    }

    @Test
    fun `parses wireguard conf accurately with dynamic keys`() {
        val (privBytes, _) = WireGuardCrypto.generateX25519KeyPair()
        val (_, peerPubBytes) = WireGuardCrypto.generateX25519KeyPair()
        val privB64 = Base64.getEncoder().encodeToString(privBytes)
        val pubB64 = Base64.getEncoder().encodeToString(peerPubBytes)

        val sampleConf = """
            [Interface]
            PrivateKey = $privB64
            Address = 10.0.0.2/32, fd00::2/128
            DNS = 1.1.1.1, 1.0.0.1
            MTU = 1420

            [Peer]
            PublicKey = $pubB64
            AllowedIPs = ::/0, 0.0.0.0/0
            Endpoint = 198.51.100.1:2408
            PersistentKeepalive = 25
        """.trimIndent()

        val parsed = WireGuardConfig.parse(sampleConf).getOrThrow()
        assertEquals(privB64, parsed.privateKeyBase64)
        assertEquals(pubB64, parsed.peerPublicKeyBase64)
        assertEquals("198.51.100.1", parsed.endpointHost)
        assertEquals(2408, parsed.endpointPort)
        assertEquals(1420, parsed.mtu)
        assertEquals(25, parsed.persistentKeepalive)
        assertEquals(2, parsed.addresses.size)
        assertEquals(2, parsed.dnsServers.size)
        assertEquals(32, parsed.privateKeyBytes().size)
        assertEquals(32, parsed.peerPublicKeyBytes().size)
    }

    @Test
    fun `merges multiple filters txt lists and blocks domains subdomains and paths`() {
        val list1Content = """
            ! Title: List One - Ad Domains
            ||googleads.g.doubleclick.net^
            ||ad.doubleclick.net^
            ||test-ad.xprivacyguard.local^
            ||cloudflare-dns.com^
        """.trimIndent()

        val list2Content = """
            ! Title: List Two - Tracker Paths & Scripts
            ||vk.com/rtrg^
            */ads.js
            */analytics.js
            0.0.0.0 telemetry.tracker.example
        """.trimIndent()

        val source1 = FilterSource(
            id = "remote_1",
            name = FilterSourceRepository.extractTitleOrNull(list1Content) ?: "List 1",
            type = FilterSourceType.REMOTE_URL,
            urlOrPath = "https://example.com/list1/filters.txt",
            enabled = true,
            ruleCount = FilterSourceRepository.countActiveRulesInText(list1Content),
            lastUpdatedMs = 0L,
            content = list1Content
        )
        val source2 = FilterSource(
            id = "remote_2",
            name = FilterSourceRepository.extractTitleOrNull(list2Content) ?: "List 2",
            type = FilterSourceType.REMOTE_URL,
            urlOrPath = "https://example.com/list2/filters.txt",
            enabled = true,
            ruleCount = FilterSourceRepository.countActiveRulesInText(list2Content),
            lastUpdatedMs = 0L,
            content = list2Content
        )

        val engine = FilterEngine()
        engine.loadSources(listOf(source1, source2))

        assertEquals(2, engine.stats.enabledSources)
        assertEquals(8, engine.stats.totalRules)

        // Exact & subdomain matches across multiple lists
        assertTrue(engine.checkDomain("googleads.g.doubleclick.net").blocked)
        assertTrue(engine.checkDomain("sub.ad.doubleclick.net").blocked)
        assertTrue(engine.checkDomain("test-ad.xprivacyguard.local").blocked)
        assertTrue(engine.checkDomain("telemetry.tracker.example").blocked)
        assertTrue(engine.checkDomain("cloudflare-dns.com").blocked)
        assertFalse(engine.checkDomain("cloudflare.com").blocked)

        // Domain + path rule: ||vk.com/rtrg^
        assertTrue(engine.checkUrl("vk.com", "/rtrg").blocked)
        assertTrue(engine.checkUrl("m.vk.com", "/rtrg?id=123").blocked)
        assertFalse(engine.checkUrl("vk.com", "/feed").blocked)

        // Wildcard script path rules: */ads.js, */analytics.js
        assertTrue(engine.checkUrl("news.example.com", "/static/js/ads.js").blocked)
        assertTrue(engine.checkUrl("news.example.com", "/v1/analytics.js").blocked)
        assertFalse(engine.checkUrl("news.example.com", "/static/js/app.js").blocked)
    }

    @Test
    fun `normalizes gitlab and github blob urls to raw urls`() {
        val gitlabBlob = "https://gitlab.com/user/repo/-/blob/main/folder/filters.txt"
        assertEquals(
            "https://gitlab.com/user/repo/-/raw/main/folder/filters.txt",
            FilterSourceRepository.normalizeFilterUrl(gitlabBlob)
        )

        val githubBlob = "https://github.com/user/repo/blob/main/filters.txt"
        assertEquals(
            "https://raw.githubusercontent.com/user/repo/main/filters.txt",
            FilterSourceRepository.normalizeFilterUrl(githubBlob)
        )
    }

    @Test
    fun `packet inspector intercepts blocked DNS query and synthesizes valid DNS response`() {
        val engine = FilterEngine()
        engine.loadSources(
            listOf(
                FilterSource(
                    id = "s1",
                    name = "Test",
                    type = FilterSourceType.LOCAL_FILE,
                    urlOrPath = "filters.txt",
                    enabled = true,
                    ruleCount = 2,
                    lastUpdatedMs = 0L,
                    content = "||ad.doubleclick.net^\n*/ads.js\n"
                )
            )
        )

        val dnsQueryPacket = buildSampleIpv4DnsQuery("ad.doubleclick.net")
        val action = PacketInspector.inspectOutboundPacket(
            packet = dnsQueryPacket,
            length = dnsQueryPacket.size,
            filterEngine = engine
        )

        assertTrue(action is PacketAction.DropAndReply)
        val drop = action as PacketAction.DropAndReply
        assertEquals("ad.doubleclick.net", drop.targetHost)
        assertEquals("||ad.doubleclick.net^", drop.decision.matchedRule)
        assertTrue("Synthetic DNS reply must have IPv4 + UDP + DNS answer", drop.replyPacket.size > 40)

        // Allowed domain should be forwarded with extracted DNS payload for Zero Trust / System DNS resolution
        val allowedQueryPacket = buildSampleIpv4DnsQuery("one.one.one.one")
        val allowedAction = PacketInspector.inspectOutboundPacket(
            packet = allowedQueryPacket,
            length = allowedQueryPacket.size,
            filterEngine = engine
        )
        assertTrue(allowedAction is PacketAction.ForwardLogged)
        val forward = allowedAction as PacketAction.ForwardLogged
        assertNotNull(forward.dnsQueryPayload)

        // Wrap an upstream DNS response back into a valid IPv4/UDP packet for TUN
        val wrappedReply = PacketInspector.buildDnsReplyFromUpstream(
            originalPacket = allowedQueryPacket,
            length = allowedQueryPacket.size,
            dnsResponsePayload = forward.dnsQueryPayload!!
        )
        assertNotNull(wrappedReply)
        assertTrue(wrappedReply!!.size >= 28 + forward.dnsQueryPayload!!.size)
    }

    @Test
    fun `zero trust dns config defaults to system dns and supports custom endpoints`() {
        val defaultConfig = ZeroTrustDnsConfig()
        assertFalse(defaultConfig.isCustomConfigured)
        assertEquals("WireGuard DNS", defaultConfig.activeModeSummary())
        assertTrue(defaultConfig.configuredIpv4List().isEmpty())
        assertTrue(defaultConfig.configuredIpv6List().isEmpty())

        val customConfig = ZeroTrustDnsConfig(
            ipv4Primary = "198.51.100.10",
            ipv4Secondary = "198.51.100.11",
            sourceIpv4 = "203.0.113.5/32",
            ipv6Endpoint = "2001:db8::1",
            dotEndpoint = "example-gateway.cloudflare-gateway.com",
            dohEndpoint = "https://example-gateway.cloudflare-gateway.com/dns-query"
        )
        assertTrue(customConfig.isCustomConfigured)
        assertEquals(2, customConfig.configuredIpv4List().size)
        assertEquals(1, customConfig.configuredIpv6List().size)
        assertEquals("https://example-gateway.cloudflare-gateway.com/dns-query", customConfig.normalizedDohUrl())
        assertEquals("example-gateway.cloudflare-gateway.com", customConfig.normalizedDotHost())

        val aQuery = ZeroTrustDnsResolver.buildSimpleDnsAQuery("example.com")
        assertTrue(aQuery.size > 16)
    }

    @Test
    fun `configured DNS forwarders bypass recursive interception but remain filter checked`() {
        val engine = FilterEngine()
        val packet = buildSampleIpv4DnsQuery("one.one.one.one")
        val appAction = PacketInspector.inspectOutboundPacket(
            packet = packet,
            length = packet.size,
            filterEngine = engine,
            dnsForwarderIps = setOf("1.1.1.1")
        )
        assertTrue(appAction is PacketAction.ForwardLogged)

        val action = PacketInspector.inspectOutboundPacket(
            packet = packet,
            length = packet.size,
            filterEngine = engine,
            dnsForwarderIps = setOf("1.1.1.1"),
            dnsForwarderSourcePorts = setOf(54321)
        )
        assertTrue(action is PacketAction.ForwardToWireGuard)

        val blockingEngine = FilterEngine()
        blockingEngine.loadSources(
            listOf(
                FilterSource(
                    id = "dns_forwarder_test",
                    name = "DNS forwarder test",
                    type = FilterSourceType.LOCAL_FILE,
                    urlOrPath = "filters.txt",
                    enabled = true,
                    ruleCount = 1,
                    lastUpdatedMs = 0L,
                    content = "||one.one.one.one^"
                )
            )
        )
        val blocked = PacketInspector.inspectOutboundPacket(
            packet = packet,
            length = packet.size,
            filterEngine = blockingEngine,
            dnsForwarderIps = setOf("1.1.1.1"),
            dnsForwarderSourcePorts = setOf(54321)
        )
        assertTrue(blocked is PacketAction.DropAndReply)

        // Verify IPv6 DNS forwarder also bypasses recursive interception
        val ipv6Packet = buildSampleIpv6DnsQuery("one.one.one.one")
        val ipv6Action = PacketInspector.inspectOutboundPacket(
            packet = ipv6Packet,
            length = ipv6Packet.size,
            filterEngine = engine,
            dnsForwarderIps = setOf("2606:4700:4700::1111"),
            dnsForwarderSourcePorts = setOf(54321)
        )
        assertEquals(PacketAction.ForwardToWireGuard, ipv6Action)
    }

    @Test
    fun `packet inspector downgrades outbound DoT port 853 with unlogged TCP reset`() {
        val engine = FilterEngine()
        val synPacket = ByteArray(40).apply {
            this[0] = 0x45.toByte()
            this[2] = 0; this[3] = 40
            this[8] = 64
            this[9] = 6 // TCP
            this[12] = 10; this[13] = 0; this[14] = 0; this[15] = 2
            this[16] = 1; this[17] = 1; this[18] = 1; this[19] = 1
            // srcPort 50000, dstPort 853
            this[20] = (50000 ushr 8).toByte()
            this[21] = (50000 and 0xFF).toByte()
            this[22] = (853 ushr 8).toByte()
            this[23] = (853 and 0xFF).toByte()
            this[32] = 0x50.toByte() // data offset 20 bytes
            this[33] = 0x02.toByte() // SYN
        }
        val action = PacketInspector.inspectOutboundPacket(
            packet = synPacket,
            length = synPacket.size,
            filterEngine = engine
        )
        assertTrue(action is PacketAction.DropAndReply)
        val drop = action as PacketAction.DropAndReply
        assertFalse(drop.logEvent)
        assertEquals("DoT", drop.protocol)
    }

    @Test
    fun `packet inspector allows HTTPS and QUIC port 443 to 1_1_1_1 for one_one_one_one`() {
        val engine = FilterEngine()
        val udp443 = ByteArray(28).apply {
            this[0] = 0x45.toByte()
            this[2] = 0; this[3] = 28
            this[8] = 64
            this[9] = 17 // UDP
            this[12] = 100; this[13] = 96; this[14] = 0; this[15] = 2
            this[16] = 1; this[17] = 1; this[18] = 1; this[19] = 1
            this[20] = (50000 ushr 8).toByte()
            this[21] = (50000 and 0xFF).toByte()
            this[22] = (443 ushr 8).toByte()
            this[23] = (443 and 0xFF).toByte()
            this[24] = 0; this[25] = 8
        }
        assertEquals(
            PacketAction.ForwardToWireGuard,
            PacketInspector.inspectOutboundPacket(udp443, udp443.size, engine)
        )

        val tcp443Syn = ByteArray(40).apply {
            this[0] = 0x45.toByte()
            this[2] = 0; this[3] = 40
            this[8] = 64
            this[9] = 6 // TCP
            this[12] = 100; this[13] = 96; this[14] = 0; this[15] = 2
            this[16] = 1; this[17] = 1; this[18] = 1; this[19] = 1
            this[20] = (50001 ushr 8).toByte()
            this[21] = (50001 and 0xFF).toByte()
            this[22] = (443 ushr 8).toByte()
            this[23] = (443 and 0xFF).toByte()
            this[32] = 0x50.toByte()
            this[33] = 0x02.toByte()
        }
        assertEquals(
            PacketAction.ForwardToWireGuard,
            PacketInspector.inspectOutboundPacket(tcp443Syn, tcp443Syn.size, engine)
        )
    }

    @Test
    fun `private split DNS matches only the configured domain and its subdomains`() {
        val config = ZeroTrustDnsConfig(
            privateDomain = "home.example.com",
            privateDnsServer = "192.0.2.53"
        )
        assertTrue(config.isPrivateDnsConfigured)
        assertTrue(config.matchesPrivateDomain("xserver.home.example.com"))
        assertTrue(config.matchesPrivateDomain("home.example.com"))
        assertFalse(config.matchesPrivateDomain("not-home.example.com"))
        assertFalse(config.matchesPrivateDomain("example.com"))
    }

    @Test
    fun `full tunnel AllowedIPs route to the configured private DNS server`() {
        val config = WireGuardConfig(
            privateKeyBase64 = Base64.getEncoder().encodeToString(ByteArray(32)),
            addresses = listOf(CidrAddress("10.0.0.2", 32)),
            dnsServers = listOf("1.1.1.1"),
            peerPublicKeyBase64 = Base64.getEncoder().encodeToString(ByteArray(32)),
            allowedIps = listOf(CidrAddress("0.0.0.0", 0), CidrAddress("::", 0)),
            endpointHost = "162.159.193.4",
            endpointPort = 2408
        )
        assertTrue(config.routesAddress("192.0.2.53"))
    }

    @Test
    fun `wireguard noise ikpsk2 handshake and transport encryption roundtrip`() {
        val (initPriv, initPub) = WireGuardCrypto.generateX25519KeyPair()
        val (respPriv, respPub) = WireGuardCrypto.generateX25519KeyPair()

        val config = WireGuardConfig(
            privateKeyBase64 = Base64.getEncoder().encodeToString(initPriv),
            addresses = emptyList(),
            dnsServers = listOf("1.1.1.1"),
            mtu = 1420,
            peerPublicKeyBase64 = Base64.getEncoder().encodeToString(respPub),
            allowedIps = emptyList(),
            endpointHost = "198.51.100.1",
            endpointPort = 2408
        )

        val session = WireGuardSession(config)
        val initiation = session.createHandshakeInitiation()
        assertEquals(148, initiation.size)

        // Simulate responder processing Type 1 Initiation and producing 92-byte Type 2 Response
        val responsePacket = simulateResponderHandshake(initiation, respPriv, respPub, initPub)
        assertNotNull(responsePacket)
        assertTrue(session.processHandshakeResponse(responsePacket!!))
        assertTrue(session.isEstablished)

        // Test transport encryption & padding removal on an IPv4 packet
        val sampleIpv4 = buildSampleIpv4DnsQuery("cloudflare.com")
        val encrypted = session.encryptTransportPacket(sampleIpv4)
        assertNotNull(encrypted)
        assertEquals(4, ByteBuffer.wrap(encrypted!!, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int)

        // Async DNS forwarding supplies an exact-length packet copy (no spare padding capacity).
        val exactLengthPacket = ByteArray(68).apply {
            this[0] = 0x45
            this[2] = 0
            this[3] = 68
        }
        val exactLengthOriginal = exactLengthPacket.copyOf()
        val paddedLength = ((exactLengthPacket.size + 15) / 16) * 16
        val exactLengthOutput = ByteArray(16 + paddedLength + 16)
        val exactLengthEncrypted = session.encryptTransportPacketInto(
            exactLengthPacket,
            exactLengthPacket.size,
            exactLengthOutput
        )
        assertEquals(16 + paddedLength + 16, exactLengthEncrypted)
        assertEquals(4, ByteBuffer.wrap(exactLengthOutput, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int)
        // Padding must happen in a separate buffer; the exact-length caller input stays intact.
        assertEquals(68, exactLengthPacket.size)
        assertEquals(68, ((exactLengthPacket[2].toInt() and 0xFF) shl 8) or (exactLengthPacket[3].toInt() and 0xFF))
        assertArrayEquals(exactLengthOriginal, exactLengthPacket)
    }

    @Test
    fun `wireguard session accepts delayed handshake response after retry initiation`() {
        val (initPriv, initPub) = WireGuardCrypto.generateX25519KeyPair()
        val (respPriv, respPub) = WireGuardCrypto.generateX25519KeyPair()

        val config = WireGuardConfig(
            privateKeyBase64 = Base64.getEncoder().encodeToString(initPriv),
            addresses = emptyList(),
            dnsServers = listOf("1.1.1.1"),
            mtu = 1420,
            peerPublicKeyBase64 = Base64.getEncoder().encodeToString(respPub),
            allowedIps = emptyList(),
            endpointHost = "198.51.100.1",
            endpointPort = 2408
        )

        val session = WireGuardSession(config)
        val firstInit = session.createHandshakeInitiation()
        // A retry fires before the first response arrives
        val secondInit = session.createHandshakeInitiation()
        assertEquals(148, secondInit.size)

        // First initiation's delayed Type 2 response arrives and must still establish the session
        val firstResponse = simulateResponderHandshake(firstInit, respPriv, respPub, initPub)
        assertNotNull(firstResponse)
        assertTrue(session.processHandshakeResponse(firstResponse!!))
        assertTrue(session.isEstablished)
    }

    @Test
    fun `firewall expands gemini playstore and google to complete companion bypass sets`() {
        val sampleInstalled = listOf(
            InstalledAppInfo(
                packageName = "com.google.android.apps.bard",
                appName = "Gemini",
                uid = 10150,
                isSystemApp = false,
                hasLauncherIcon = true,
                specialGroup = SpecialBypassGroup.GEMINI
            ),
            InstalledAppInfo(
                packageName = "com.android.vending",
                appName = "Google Play Store",
                uid = 10120,
                isSystemApp = false,
                hasLauncherIcon = true,
                specialGroup = SpecialBypassGroup.PLAY_STORE
            ),
            InstalledAppInfo(
                packageName = "com.google.android.googlequicksearchbox",
                appName = "Google",
                uid = 10110,
                isSystemApp = false,
                hasLauncherIcon = true,
                specialGroup = SpecialBypassGroup.GOOGLE
            ),
            InstalledAppInfo(
                packageName = "com.google.android.gms",
                appName = "Google Play services",
                uid = 10085,
                isSystemApp = true,
                hasLauncherIcon = false,
                specialGroup = SpecialBypassGroup.GOOGLE
            ),
            InstalledAppInfo(
                packageName = "com.google.android.custom.shared",
                appName = "Shared UID Sibling",
                uid = 10085,
                isSystemApp = true,
                hasLauncherIcon = false,
                specialGroup = null
            )
        )

        // 1. Selecting Gemini expands to Gemini + Google App + Play Services + AICore + Voice + shared UID siblings
        val geminiExpanded = FirewallManager.expandBypassPackages(
            selectedPackages = setOf(FirewallManager.PKG_GEMINI),
            installedApps = sampleInstalled
        )
        assertTrue(geminiExpanded.contains("com.google.android.apps.bard"))
        assertTrue(geminiExpanded.contains("com.google.android.googlequicksearchbox"))
        assertTrue(geminiExpanded.contains("com.google.android.gms"))
        assertTrue(geminiExpanded.contains("com.google.android.gsf"))
        assertTrue(geminiExpanded.contains("com.google.android.aicore"))
        assertTrue(geminiExpanded.contains("com.google.android.tts"))
        assertTrue(geminiExpanded.contains("com.google.android.custom.shared"))

        // 2. Selecting Play Store expands to Play Store + Download Manager + Play Services + Framework
        val playStoreExpanded = FirewallManager.expandBypassPackages(
            selectedPackages = setOf(FirewallManager.PKG_PLAY_STORE),
            installedApps = sampleInstalled
        )
        assertTrue(playStoreExpanded.contains("com.android.vending"))
        assertTrue(playStoreExpanded.contains("com.android.providers.downloads"))
        assertTrue(playStoreExpanded.contains("com.android.providers.downloads.ui"))
        assertTrue(playStoreExpanded.contains("com.google.android.gms"))
        assertTrue(playStoreExpanded.contains("com.google.android.gsf"))

        // 3. Selecting Google expands to Google + Play Services + Gemini + AICore + Assistant
        val googleExpanded = FirewallManager.expandBypassPackages(
            selectedPackages = setOf(FirewallManager.PKG_GOOGLE_APP),
            installedApps = sampleInstalled
        )
        assertTrue(googleExpanded.contains("com.google.android.googlequicksearchbox"))
        assertTrue(googleExpanded.contains("com.google.android.gms"))
        assertTrue(googleExpanded.contains("com.google.android.gsf"))
        assertTrue(googleExpanded.contains("com.google.android.apps.bard"))

        // 4. Search query matches "gemini", "playstore" (without space), and "google"
        assertTrue(FirewallManager.matchesSearchQuery(sampleInstalled[0], "gemini"))
        assertTrue(FirewallManager.matchesSearchQuery(sampleInstalled[1], "playstore"))
        assertTrue(FirewallManager.matchesSearchQuery(sampleInstalled[1], "Play Store"))
        assertTrue(FirewallManager.matchesSearchQuery(sampleInstalled[2], "google"))
    }

    @Test
    fun `filter rules remain active for every app routed through the VPN`() {
        val engine = FilterEngine()
        engine.loadSources(
            listOf(
                FilterSource(
                    id = "strict_list",
                    name = "Strict List",
                    type = FilterSourceType.LOCAL_FILE,
                    urlOrPath = "filters.txt",
                    enabled = true,
                    ruleCount = 2,
                    lastUpdatedMs = 0L,
                    content = "||gemini.google.com^\n||googleads.g.doubleclick.net^"
                )
            )
        )

        val geminiPacket = buildSampleIpv4DnsQuery("gemini.google.com")
        val geminiAction = PacketInspector.inspectOutboundPacket(
            packet = geminiPacket,
            length = geminiPacket.size,
            filterEngine = engine
        )
        assertTrue(geminiAction is PacketAction.DropAndReply)

        val adPacket = buildSampleIpv4DnsQuery("googleads.g.doubleclick.net")
        val adAction = PacketInspector.inspectOutboundPacket(
            packet = adPacket,
            length = adPacket.size,
            filterEngine = engine
        )
        assertTrue(adAction is PacketAction.DropAndReply)
    }

    private fun buildSampleIpv4DnsQuery(domain: String): ByteArray {
        val labels = domain.split('.')
        val qnameBytes = mutableListOf<Byte>()
        for (label in labels) {
            val bytes = label.toByteArray(StandardCharsets.US_ASCII)
            qnameBytes.add(bytes.size.toByte())
            qnameBytes.addAll(bytes.toList())
        }
        qnameBytes.add(0)

        val dnsLen = 12 + qnameBytes.size + 4
        val udpLen = 8 + dnsLen
        val totalLen = 20 + udpLen
        val pkt = ByteArray(totalLen)

        pkt[0] = 0x45.toByte()
        pkt[2] = ((totalLen ushr 8) and 0xFF).toByte()
        pkt[3] = (totalLen and 0xFF).toByte()
        pkt[8] = 64
        pkt[9] = 17 // UDP
        // src IP 10.0.0.2
        pkt[12] = 10; pkt[13] = 0; pkt[14] = 0; pkt[15] = 2
        // dst IP 1.1.1.1
        pkt[16] = 1; pkt[17] = 1; pkt[18] = 1; pkt[19] = 1

        // UDP header: srcPort 54321, dstPort 53
        pkt[20] = (54321 ushr 8).toByte()
        pkt[21] = (54321 and 0xFF).toByte()
        pkt[22] = 0
        pkt[23] = 53
        pkt[24] = ((udpLen ushr 8) and 0xFF).toByte()
        pkt[25] = (udpLen and 0xFF).toByte()

        // DNS header: ID=0x1234, RD=1, QDCOUNT=1
        pkt[28] = 0x12; pkt[29] = 0x34
        pkt[30] = 0x01; pkt[31] = 0x00
        pkt[32] = 0x00; pkt[33] = 0x01

        var pos = 40
        for (b in qnameBytes) {
            pkt[pos++] = b
        }
        // QTYPE=A (1), QCLASS=IN (1)
        pkt[pos++] = 0; pkt[pos++] = 1
        pkt[pos++] = 0; pkt[pos] = 1

        return pkt
    }

    private fun buildSampleIpv6DnsQuery(domain: String): ByteArray {
        val labels = domain.split('.')
        val qnameBytes = mutableListOf<Byte>()
        for (label in labels) {
            val bytes = label.toByteArray(StandardCharsets.US_ASCII)
            qnameBytes.add(bytes.size.toByte())
            qnameBytes.addAll(bytes.toList())
        }
        qnameBytes.add(0)

        val dnsLen = 12 + qnameBytes.size + 4
        val udpLen = 8 + dnsLen
        val totalLen = 40 + udpLen
        val pkt = ByteArray(totalLen)

        pkt[0] = 0x60.toByte()
        pkt[4] = ((udpLen ushr 8) and 0xFF).toByte()
        pkt[5] = (udpLen and 0xFF).toByte()
        pkt[6] = 17 // UDP
        pkt[7] = 64
        val dstBytes = java.net.InetAddress.getByName("2606:4700:4700::1111").address
        System.arraycopy(dstBytes, 0, pkt, 24, 16)

        // UDP header: srcPort 54321, dstPort 53
        pkt[40] = (54321 ushr 8).toByte()
        pkt[41] = (54321 and 0xFF).toByte()
        pkt[42] = 0
        pkt[43] = 53
        pkt[44] = ((udpLen ushr 8) and 0xFF).toByte()
        pkt[45] = (udpLen and 0xFF).toByte()

        pkt[48] = 0x12; pkt[49] = 0x34
        pkt[50] = 0x01; pkt[51] = 0x00
        pkt[52] = 0x00; pkt[53] = 0x01

        var pos = 60
        for (b in qnameBytes) {
            pkt[pos++] = b
        }
        pkt[pos++] = 0; pkt[pos++] = 1
        pkt[pos++] = 0; pkt[pos] = 1

        return pkt
    }

    private fun simulateResponderHandshake(
        initPkt: ByteArray,
        respStaticPriv: ByteArray,
        respStaticPub: ByteArray,
        expectedInitStaticPub: ByteArray
    ): ByteArray? {
        val buf = ByteBuffer.wrap(initPkt).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.int != 1) return null
        val initiatorIdx = buf.int

        val initEphPub = initPkt.copyOfRange(8, 40)
        val encStatic = initPkt.copyOfRange(40, 88)
        val encTimestamp = initPkt.copyOfRange(88, 116)

        var ck = WireGuardCrypto.blake2s256(WireGuardCrypto.CONSTRUCTION)
        var hash = WireGuardCrypto.blake2s256(ck, WireGuardCrypto.IDENTIFIER)
        hash = WireGuardCrypto.blake2s256(hash, respStaticPub)
        hash = WireGuardCrypto.blake2s256(hash, initEphPub)
        ck = WireGuardCrypto.kdf1(ck, initEphPub)

        val dh1 = WireGuardCrypto.x25519Dh(respStaticPriv, initEphPub)
        val (ck1, key1) = WireGuardCrypto.kdf2(ck, dh1)
        ck = ck1

        val decStatic = WireGuardCrypto.aeadDecrypt(key1, 0L, encStatic, hash)
        assertArrayEquals(expectedInitStaticPub, decStatic)
        hash = WireGuardCrypto.blake2s256(hash, encStatic)

        val dh2 = WireGuardCrypto.x25519Dh(respStaticPriv, decStatic)
        val (ck2, key2) = WireGuardCrypto.kdf2(ck, dh2)
        ck = ck2

        val decTime = WireGuardCrypto.aeadDecrypt(key2, 0L, encTimestamp, hash)
        assertEquals(12, decTime.size)
        hash = WireGuardCrypto.blake2s256(hash, encTimestamp)

        // Responder builds Type 2 Response
        val (respEphPriv, respEphPub) = WireGuardCrypto.generateX25519KeyPair()
        hash = WireGuardCrypto.blake2s256(hash, respEphPub)
        ck = WireGuardCrypto.kdf1(ck, respEphPub)

        ck = WireGuardCrypto.kdf1(ck, WireGuardCrypto.x25519Dh(respEphPriv, initEphPub))
        ck = WireGuardCrypto.kdf1(ck, WireGuardCrypto.x25519Dh(respEphPriv, decStatic))

        val (ck3, tau, key3) = WireGuardCrypto.kdf3(ck, ByteArray(32))
        ck = ck3
        hash = WireGuardCrypto.blake2s256(hash, tau)

        val encNothing = WireGuardCrypto.aeadEncrypt(key3, 0L, ByteArray(0), hash)
        val out = ByteArray(92)
        ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(2)
            putInt(777) // responder sender index
            putInt(initiatorIdx)
            put(respEphPub)
            put(encNothing)
        }
        val mac1Key = WireGuardCrypto.blake2s256(WireGuardCrypto.LABEL_MAC1, decStatic)
        val mac1 = WireGuardCrypto.blake2s128Keyed(mac1Key, out, 60)
        System.arraycopy(mac1, 0, out, 60, 16)
        return out
    }

    @Test
    fun `empty WireGuard configuration is rejected until user imports a profile`() {
        assertTrue(WireGuardConfig.parse("").isFailure)
    }

    @Test
    fun `verifies personal app classification for Include Mode`() {
        assertTrue(FirewallManager.isGooglePackage("com.google.android.gms"))
        assertTrue(FirewallManager.isGooglePackage("com.android.vending"))
        assertTrue(FirewallManager.isMicrosoftPackage("com.microsoft.teams"))
        assertTrue(FirewallManager.isMicrosoftPackage("com.skype.raider"))
        assertTrue(FirewallManager.isSystemOrCarrierPackage("com.samsung.android.messaging", false))

        // Personal third-party apps
        assertTrue(FirewallManager.isPersonalApp("org.signal.messenger", false))
        assertTrue(FirewallManager.isPersonalApp("org.telegram.messenger", false))
        assertTrue(FirewallManager.isPersonalApp("com.whatsapp", false))
        assertFalse(FirewallManager.isPersonalApp("com.google.android.apps.messaging", false))
    }

    @Test
    fun `VPN allowlist defaults to xPrivacyGuard and companion xAssistant`() {
        val allowed = FirewallManager.buildAllowedPackages(
            selectedPackages = emptySet(),
            ownPackage = "com.xconflictionx.xprivacyguard",
            installedPackages = setOf("com.example.chat", "com.example.browser", "cc.xconflictionx.xassistant")
        )
        assertEquals(setOf("cc.xconflictionx.xassistant", "com.xconflictionx.xprivacyguard"), allowed)
    }

    @Test
    fun `VPN allowlist includes selected apps and excludes unselected apps`() {
        val allowed = FirewallManager.buildAllowedPackages(
            selectedPackages = setOf("com.example.chat", "com.example.missing"),
            ownPackage = "com.xconflictionx.xprivacyguard",
            installedPackages = setOf("com.example.chat", "com.example.browser")
        )
        assertEquals(setOf("com.xconflictionx.xprivacyguard", "com.example.chat"), allowed)
    }

    @Test
    fun `firewall user app filter excludes settings and system apps`() {
        val settingsApp = InstalledAppInfo(
            packageName = "com.android.settings",
            appName = "Settings",
            uid = 1000,
            isSystemApp = true,
            hasLauncherIcon = true
        )
        val updatedSystemApp = InstalledAppInfo(
            packageName = "com.android.settings",
            appName = "Settings",
            uid = 1000,
            isSystemApp = false,
            hasLauncherIcon = true
        )
        val systemUiApp = InstalledAppInfo(
            packageName = "com.android.systemui",
            appName = "System UI",
            uid = 1001,
            isSystemApp = true,
            hasLauncherIcon = false
        )
        val userApp = InstalledAppInfo(
            packageName = "org.signal.messenger",
            appName = "Signal",
            uid = 10250,
            isSystemApp = false,
            hasLauncherIcon = true
        )

        assertFalse(FirewallManager.isUserApp(settingsApp))
        assertFalse(FirewallManager.isUserApp(updatedSystemApp))
        assertFalse(FirewallManager.isUserApp(systemUiApp))
        assertTrue(FirewallManager.isUserApp(userApp))
    }

    @Test
    fun `triggers physical network switch when wifi drops to offline before cellular comes up`() {
        // Exact Wi-Fi -> null -> Cellular sequence when turning off Wi-Fi on Android:
        // prevPhysicalNetId is null (from onLost(wifi)), best is cellular, lastTunnelPhysicalNetId is wifi,
        // and VPN status is still CONNECTED with stale Wi-Fi LAN exclusions.
        assertTrue(
            com.xconflictionx.xprivacyguard.vpn.XPrivacyGuardService.shouldTriggerPhysicalNetworkSwitch(
                triggerSwitch = true,
                prevPhysicalNetId = null,
                bestPhysicalNetId = "cell-102",
                lastTunnelPhysicalNetId = "wifi-101",
                hasActiveTunnel = true,
                isConnected = true,
                expectedLanSubnets = emptySet(),
                appliedLanSubnets = setOf("192.168.88.0/24")
            )
        )

        // Direct handoff when both Wi-Fi and Cellular were available simultaneously
        assertTrue(
            com.xconflictionx.xprivacyguard.vpn.XPrivacyGuardService.shouldTriggerPhysicalNetworkSwitch(
                triggerSwitch = true,
                prevPhysicalNetId = "wifi-101",
                bestPhysicalNetId = "cell-102",
                lastTunnelPhysicalNetId = "wifi-101",
                hasActiveTunnel = true,
                isConnected = true,
                expectedLanSubnets = emptySet(),
                appliedLanSubnets = setOf("192.168.88.0/24")
            )
        )

        // Restored from offline back onto the same cellular network while CONNECTED
        assertTrue(
            com.xconflictionx.xprivacyguard.vpn.XPrivacyGuardService.shouldTriggerPhysicalNetworkSwitch(
                triggerSwitch = true,
                prevPhysicalNetId = null,
                bestPhysicalNetId = "cell-102",
                lastTunnelPhysicalNetId = "cell-102",
                hasActiveTunnel = true,
                isConnected = true,
                expectedLanSubnets = emptySet(),
                appliedLanSubnets = emptySet()
            )
        )

        // Same network becoming validated while CONNECTING on that same network must NOT restart
        assertFalse(
            com.xconflictionx.xprivacyguard.vpn.XPrivacyGuardService.shouldTriggerPhysicalNetworkSwitch(
                triggerSwitch = true,
                prevPhysicalNetId = "cell-102",
                bestPhysicalNetId = "cell-102",
                lastTunnelPhysicalNetId = "cell-102",
                hasActiveTunnel = true,
                isConnected = false,
                expectedLanSubnets = emptySet(),
                appliedLanSubnets = setOf("192.168.88.0/24")
            )
        )

        // Wi-Fi DHCP finishes assigning 192.168.88.0/24 after CONNECTED -> rebuild TUN exclusions
        assertTrue(
            com.xconflictionx.xprivacyguard.vpn.XPrivacyGuardService.shouldTriggerPhysicalNetworkSwitch(
                triggerSwitch = true,
                prevPhysicalNetId = "wifi-101",
                bestPhysicalNetId = "wifi-101",
                lastTunnelPhysicalNetId = "wifi-101",
                hasActiveTunnel = true,
                isConnected = true,
                expectedLanSubnets = setOf("192.168.88.0/24"),
                appliedLanSubnets = emptySet()
            )
        )

        // No-op when steady-state on same validated network with matching LAN exclusions
        assertFalse(
            com.xconflictionx.xprivacyguard.vpn.XPrivacyGuardService.shouldTriggerPhysicalNetworkSwitch(
                triggerSwitch = true,
                prevPhysicalNetId = "cell-102",
                bestPhysicalNetId = "cell-102",
                lastTunnelPhysicalNetId = "cell-102",
                hasActiveTunnel = true,
                isConnected = true,
                expectedLanSubnets = emptySet(),
                appliedLanSubnets = emptySet()
            )
        )
    }

    @Test
    fun `VPN allowlist always excludes Windows Phone Link companion packages`() {
        val allowed = FirewallManager.buildAllowedPackages(
            selectedPackages = setOf(
                "com.example.chat",
                "com.microsoft.appmanager",
                "com.samsung.android.mdx",
                "com.samsung.android.mdx.kit"
            ),
            ownPackage = "com.xconflictionx.xprivacyguard",
            installedPackages = setOf(
                "com.example.chat",
                "com.microsoft.appmanager",
                "com.samsung.android.mdx",
                "com.samsung.android.mdx.kit"
            )
        )
        assertTrue(allowed.contains("com.example.chat"))
        assertTrue(allowed.contains("com.xconflictionx.xprivacyguard"))
        assertFalse(allowed.contains("com.microsoft.appmanager"))
        assertFalse(allowed.contains("com.samsung.android.mdx"))
        assertFalse(allowed.contains("com.samsung.android.mdx.kit"))
    }

}
