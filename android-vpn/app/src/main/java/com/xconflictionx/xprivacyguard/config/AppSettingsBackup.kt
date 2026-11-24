package com.xconflictionx.xprivacyguard.config

import com.xconflictionx.xprivacyguard.dns.ZeroTrustDnsConfig
import com.xconflictionx.xprivacyguard.filter.FilterSource
import com.xconflictionx.xprivacyguard.filter.FilterSourceRepository
import com.xconflictionx.xprivacyguard.filter.FilterSourceType
import org.json.JSONArray
import org.json.JSONObject

data class FilterToggleSettings(
    val dnsFilteringEnabled: Boolean? = null,
    val deepInspectionEnabled: Boolean? = null,
    val useNxDomain: Boolean? = null,
    val autoStartOnBoot: Boolean? = null
)

data class XSecurityBackupConfig(
    val domain: String? = null,
    val apiKey: String? = null
)

data class AppSettingsBackupData(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val exportedAtEpochMs: Long = System.currentTimeMillis(),
    val wireGuardConfig: String? = null,
    val tunneledPackages: Set<String>? = null,
    val zeroTrustDns: ZeroTrustDnsConfig? = null,
    val filterSettings: FilterToggleSettings? = null,
    val filterSources: List<FilterSource>? = null,
    val xSecurity: XSecurityBackupConfig? = null
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

object AppSettingsBackup {

    private val IPV4_REGEX = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    fun toJson(data: AppSettingsBackupData): String {
        val root = JSONObject()
        root.put("schemaVersion", data.schemaVersion)
        root.put("exportedAtEpochMs", data.exportedAtEpochMs)

        if (!data.wireGuardConfig.isNullOrBlank()) {
            root.put("wireGuardConfig", data.wireGuardConfig)
        }

        data.tunneledPackages?.let { pkgs ->
            val arr = JSONArray()
            pkgs.map { it.trim() }.filter { it.isNotEmpty() }.sorted().forEach { arr.put(it) }
            root.put("tunneledPackages", arr)
        }

        data.zeroTrustDns?.let { dns ->
            val obj = JSONObject().apply {
                put("ipv4Primary", dns.ipv4Primary.trim())
                put("ipv4Secondary", dns.ipv4Secondary.trim())
                put("sourceIpv4", dns.sourceIpv4.trim())
                put("ipv6Endpoint", dns.ipv6Endpoint.trim())
                put("sourceIpv6", dns.sourceIpv6.trim())
                put("dotEndpoint", dns.dotEndpoint.trim())
                put("dohEndpoint", dns.dohEndpoint.trim())
                put("privateDomain", dns.privateDomain.trim())
                put("privateDnsServer", dns.privateDnsServer.trim())
            }
            root.put("zeroTrustDns", obj)
        }

        data.filterSettings?.let { fs ->
            val obj = JSONObject().apply {
                fs.dnsFilteringEnabled?.let { put("dnsFilteringEnabled", it) }
                fs.deepInspectionEnabled?.let { put("deepInspectionEnabled", it) }
                fs.useNxDomain?.let { put("useNxDomain", it) }
                fs.autoStartOnBoot?.let { put("autoStartOnBoot", it) }
            }
            root.put("filterSettings", obj)
        }

        data.filterSources?.let { sources ->
            val arr = JSONArray()
            for (src in sources) {
                val obj = JSONObject().apply {
                    put("id", src.id)
                    put("name", src.name)
                    put("type", src.type.name)
                    put("urlOrPath", src.urlOrPath)
                    put("enabled", src.enabled)
                    put("lastUpdatedMs", src.lastUpdatedMs)
                    put("content", src.content)
                }
                arr.put(obj)
            }
            root.put("filterSources", arr)
        }

        data.xSecurity?.let { xs ->
            val obj = JSONObject().apply {
                xs.domain?.let { put("domain", it.trim()) }
                xs.apiKey?.let { put("apiKey", it.trim()) }
            }
            root.put("xSecurity", obj)
        }

        return root.toString(2)
    }

    /**
     * Parses a backup JSON string and retains ONLY valid, compatible settings supported by this build.
     * Unknown or incompatible fields are safely ignored.
     */
    fun fromJson(jsonText: String): Result<AppSettingsBackupData> = runCatching {
        val trimmed = jsonText.trim()
        require(trimmed.startsWith("{")) { "Backup file must be a valid JSON object." }

        val root = JSONObject(trimmed)
        val schemaVersion = root.optInt("schemaVersion", 1)
        val exportedAt = root.optLong("exportedAtEpochMs", 0L)

        // WireGuard config: keep only if it parses cleanly in this build
        val rawWg = root.optString("wireGuardConfig", "").trim()
        val validWg = if (rawWg.isNotEmpty() && WireGuardConfig.parse(rawWg).isSuccess) rawWg else null

        // Tunneled apps list
        val tunneledPackages: Set<String>? = if (root.has("tunneledPackages")) {
            val arr = root.optJSONArray("tunneledPackages")
            if (arr != null) {
                buildSet {
                    for (i in 0 until arr.length()) {
                        val pkg = arr.optString(i, "").trim()
                        if (pkg.isNotEmpty() && !pkg.contains(' ')) add(pkg)
                    }
                }
            } else null
        } else null

        // Zero Trust / Split DNS config
        val zeroTrustDns: ZeroTrustDnsConfig? = root.optJSONObject("zeroTrustDns")?.let { obj ->
            ZeroTrustDnsConfig(
                ipv4Primary = obj.optString("ipv4Primary", "").trim(),
                ipv4Secondary = obj.optString("ipv4Secondary", "").trim(),
                sourceIpv4 = obj.optString("sourceIpv4", "").trim(),
                ipv6Endpoint = obj.optString("ipv6Endpoint", "").trim(),
                sourceIpv6 = obj.optString("sourceIpv6", "").trim(),
                dotEndpoint = obj.optString("dotEndpoint", "").trim(),
                dohEndpoint = obj.optString("dohEndpoint", "").trim(),
                privateDomain = obj.optString("privateDomain", "").trim().trim('.').lowercase(),
                privateDnsServer = obj.optString("privateDnsServer", "").trim()
            )
        }

        // Filter & boot toggle settings
        val filterSettings: FilterToggleSettings? = root.optJSONObject("filterSettings")?.let { obj ->
            FilterToggleSettings(
                dnsFilteringEnabled = if (obj.has("dnsFilteringEnabled")) obj.optBoolean("dnsFilteringEnabled") else null,
                deepInspectionEnabled = if (obj.has("deepInspectionEnabled")) obj.optBoolean("deepInspectionEnabled") else null,
                useNxDomain = if (obj.has("useNxDomain")) obj.optBoolean("useNxDomain") else null,
                autoStartOnBoot = if (obj.has("autoStartOnBoot")) obj.optBoolean("autoStartOnBoot") else null
            )
        }

        // Filter sources: keep only valid FilterSourceType entries
        val filterSources: List<FilterSource>? = if (root.has("filterSources")) {
            val arr = root.optJSONArray("filterSources")
            if (arr != null) {
                buildList {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val id = obj.optString("id", "").trim()
                        val name = obj.optString("name", "").trim()
                        val typeStr = obj.optString("type", "").trim()
                        val mappedType = runCatching { FilterSourceType.valueOf(typeStr) }.getOrNull() ?: continue
                        if (id.isEmpty() || name.isEmpty()) continue
                        val content = obj.optString("content", "")
                        add(
                            FilterSource(
                                id = id,
                                name = name,
                                type = mappedType,
                                urlOrPath = obj.optString("urlOrPath", "").trim(),
                                enabled = obj.optBoolean("enabled", true),
                                ruleCount = FilterSourceRepository.countActiveRulesInText(content),
                                lastUpdatedMs = obj.optLong("lastUpdatedMs", System.currentTimeMillis()),
                                content = content
                            )
                        )
                    }
                }
            } else null
        } else null

        // xSecurity connection config: only keep valid non-IP domain
        val xSecurity: XSecurityBackupConfig? = root.optJSONObject("xSecurity")?.let { obj ->
            val cleanDomain = obj.optString("domain", "")
                .trim()
                .removePrefix("https://")
                .removePrefix("http://")
                .substringBefore("/")
                .trim()
            val validDomain = if (
                cleanDomain.isNotEmpty() &&
                !IPV4_REGEX.matches(cleanDomain) &&
                !cleanDomain.contains(':') &&
                cleanDomain.contains('.')
            ) cleanDomain else null
            val apiKey = obj.optString("apiKey", "").trim().takeIf { it.isNotEmpty() }
            if (validDomain != null || apiKey != null) {
                XSecurityBackupConfig(domain = validDomain, apiKey = apiKey)
            } else null
        }

        val hasAnySection = validWg != null ||
            tunneledPackages != null ||
            zeroTrustDns != null ||
            filterSettings != null ||
            filterSources != null ||
            xSecurity != null
        require(hasAnySection) { "Backup file contained no compatible settings for this build." }

        AppSettingsBackupData(
            schemaVersion = schemaVersion,
            exportedAtEpochMs = exportedAt,
            wireGuardConfig = validWg,
            tunneledPackages = tunneledPackages,
            zeroTrustDns = zeroTrustDns,
            filterSettings = filterSettings,
            filterSources = filterSources,
            xSecurity = xSecurity
        )
    }
}

