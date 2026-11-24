package cc.xconflictionx.xprivacyguard

import cc.xconflictionx.xprivacyguard.vpn.BlocklistFilter
import cc.xconflictionx.xprivacyguard.vpn.DnsProxyServer
import org.junit.Assert.assertEquals
import org.junit.Test

class DnsProxyServerTest {

    @Test
    fun testExtractDomainFromDnsQuery() {
        val query = buildTestDnsQuery("example.com")
        val domain = DnsProxyServer.extractDomainStatic(query)
        assertEquals("example.com", domain)
    }

    private fun buildTestDnsQuery(hostname: String): ByteArray {
        val bytes = mutableListOf<Byte>()
        bytes.add(0x12); bytes.add(0x34)
        bytes.add(0x01); bytes.add(0x00)
        bytes.add(0x00); bytes.add(0x01)
        bytes.add(0x00); bytes.add(0x00)
        bytes.add(0x00); bytes.add(0x00)
        bytes.add(0x00); bytes.add(0x00)

        for (part in hostname.split(".")) {
            if (part.isEmpty()) continue
            bytes.add(part.length.toByte())
            for (c in part.toByteArray(Charsets.UTF_8)) {
                bytes.add(c)
            }
        }
        bytes.add(0x00)
        bytes.add(0x00); bytes.add(0x01)
        bytes.add(0x00); bytes.add(0x01)

        return bytes.toByteArray()
    }
}
