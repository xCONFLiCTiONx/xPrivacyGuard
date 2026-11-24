package cc.xconflictionx.xprivacyguard.vpn

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object SplitTunnelManager {
    var hostnameRules by mutableStateOf(
        mapOf(
            "*.google.com" to "DIRECT",
            "*.bank.com" to "DIRECT",
            "*.netflix.com" to "DIRECT"
        )
    )
        private set

    fun init(context: Context) {
        // Load from preferences if needed
    }

    fun addRule(domainPattern: String, action: String) {
        val clean = domainPattern.lowercase().trim()
        if (clean.isNotEmpty()) {
            hostnameRules = hostnameRules + (clean to action)
        }
    }

    fun removeRule(domainPattern: String) {
        hostnameRules = hostnameRules - domainPattern
    }

    fun shouldRouteDirect(domain: String): Boolean {
        val lower = domain.lowercase().trimEnd('.')
        for ((pattern, action) in hostnameRules) {
            if (action == "DIRECT") {
                if (pattern.startsWith("*.")) {
                    val suffix = pattern.removePrefix("*")
                    if (lower.endsWith(suffix) || lower == suffix.trimStart('.')) {
                        return true
                    }
                } else if (lower == pattern) {
                    return true
                }
            }
        }
        return false
    }
}
