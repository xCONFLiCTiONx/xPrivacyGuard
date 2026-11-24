package com.xconflictionx.xprivacyguard.security

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class DeviceSecurityReport(
    val deviceModel: String = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
    val androidVersion: String = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
    val securityPatch: String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Build.VERSION.SECURITY_PATCH else "unknown",
    val deviceSecure: Boolean = false,
    val adbEnabled: Boolean = false,
    val developerOptionsEnabled: Boolean = false,
    val autoBlockerEnabled: Boolean? = null,
    val autoBlockerMaxRestrictions: Boolean? = null,
    val verifiedBootTags: String = Build.TAGS ?: "unknown",
    val vpnConnected: Boolean = false,
    val blockedCount: Long = 0L,
    val allowedCount: Long = 0L
)

data class XSecuritySyncResult(
    val ok: Boolean,
    val message: String,
    val postureScore: Int? = null,
    val postureStatus: String? = null,
    val openAlerts: Int? = null
)

object DeviceSecurityChecker {

    private val IPV4_REGEX = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    fun isValidDomain(raw: String): Boolean {
        val clean = cleanDomain(raw)
        if (clean.isEmpty() || clean.contains(':') || clean.contains('/')) return false
        if (IPV4_REGEX.matches(clean)) return false
        return clean.contains('.') && clean.all { it.isLetterOrDigit() || it == '.' || it == '-' }
    }

    fun cleanDomain(raw: String): String {
        return raw.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .trimEnd('/')
            .lowercase()
    }

    fun collectReport(
        context: Context,
        vpnConnected: Boolean,
        blockedCount: Long,
        allowedCount: Long
    ): DeviceSecurityReport {
        val kg = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val deviceSecure = kg?.isDeviceSecure ?: false
        val cr = context.contentResolver
        val adbEnabled = runCatching {
            Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0) == 1
        }.getOrDefault(false)
        val devEnabled = runCatching {
            Settings.Global.getInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        }.getOrDefault(false)
        val autoBlocker = runCatching {
            Settings.Secure.getInt(cr, "rampart_main_switch_enabled") == 1
        }.getOrNull()
        val autoBlockerStrict = runCatching {
            Settings.Secure.getInt(cr, "rampart_strict_protection_switch_enabled") == 1
        }.getOrNull()

        return DeviceSecurityReport(
            deviceSecure = deviceSecure,
            adbEnabled = adbEnabled,
            developerOptionsEnabled = devEnabled,
            autoBlockerEnabled = autoBlocker,
            autoBlockerMaxRestrictions = autoBlockerStrict,
            vpnConnected = vpnConnected,
            blockedCount = blockedCount,
            allowedCount = allowedCount
        )
    }

    fun syncReportToXServer(
        domain: String,
        apiKey: String,
        report: DeviceSecurityReport
    ): XSecuritySyncResult {
        val host = cleanDomain(domain)
        if (!isValidDomain(host)) {
            return XSecuritySyncResult(false, "Enter a valid domain name (not an IP address).")
        }
        val key = apiKey.trim()
        if (key.isEmpty()) {
            return XSecuritySyncResult(false, "Enter an xServer API key from /keys/ first.")
        }

        val url = URL("https://$host/api/agent/android/report")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 8000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("X-API-Key", key)
        }

        return try {
            val payload = JSONObject().apply {
                put("deviceModel", report.deviceModel)
                put("androidVersion", report.androidVersion)
                put("securityPatch", report.securityPatch)
                put("deviceSecure", report.deviceSecure)
                put("adbEnabled", report.adbEnabled)
                put("developerOptionsEnabled", report.developerOptionsEnabled)
                if (report.autoBlockerEnabled != null) {
                    put("autoBlockerEnabled", report.autoBlockerEnabled)
                }
                if (report.autoBlockerMaxRestrictions != null) {
                    put("autoBlockerMaxRestrictions", report.autoBlockerMaxRestrictions)
                }
                put("verifiedBootTags", report.verifiedBootTags)
                put("vpnConnected", report.vpnConnected)
                put("blockedCount", report.blockedCount)
                put("allowedCount", report.allowedCount)
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(payload.toString())
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.let { BufferedReader(InputStreamReader(it)).readText() }.orEmpty()
            if (code !in 200..299) {
                val err = runCatching { JSONObject(body).optString("error") }.getOrNull().orEmpty()
                return XSecuritySyncResult(false, "HTTP $code: ${err.ifBlank { "Sync failed" }}")
            }
            val json = JSONObject(body)
            val posture = json.optJSONObject("posture")
            XSecuritySyncResult(
                ok = true,
                message = "Synced to $host",
                postureScore = posture?.optInt("score"),
                postureStatus = posture?.optString("status"),
                openAlerts = posture?.optInt("openAlertCount")
            )
        } catch (exc: Exception) {
            XSecuritySyncResult(false, exc.message ?: "Connection failed")
        } finally {
            conn.disconnect()
        }
    }
}

