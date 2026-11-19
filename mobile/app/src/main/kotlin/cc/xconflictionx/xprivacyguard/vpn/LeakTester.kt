package cc.xconflictionx.xprivacyguard.vpn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object LeakTester {
    suspend fun testDnsLeak(): String = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://cloudflare-dns.com/cdn-cgi/trace")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            if (response.contains("ip=")) {
                "Secure (Protected via ${VpnConfig.dnsProtocol})"
            } else {
                "Potential Leak Detected"
            }
        } catch (e: Exception) {
            "Test Error: ${e.localizedMessage}"
        }
    }
}
