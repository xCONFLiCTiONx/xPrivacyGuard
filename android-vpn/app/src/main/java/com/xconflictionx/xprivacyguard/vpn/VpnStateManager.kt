package com.xconflictionx.xprivacyguard.vpn

import android.content.Context
import android.net.VpnService
import com.xconflictionx.xprivacyguard.config.CloudflareOneConfig
import com.xconflictionx.xprivacyguard.config.WireGuardConfig
import com.xconflictionx.xprivacyguard.dns.ZeroTrustDnsConfig
import com.xconflictionx.xprivacyguard.dns.ZeroTrustDnsResolver
import com.xconflictionx.xprivacyguard.filter.FilterEngine
import com.xconflictionx.xprivacyguard.filter.FilterSource
import com.xconflictionx.xprivacyguard.filter.FilterSourceRepository
import com.xconflictionx.xprivacyguard.filter.FilterStats
import com.xconflictionx.xprivacyguard.firewall.FirewallManager
import com.xconflictionx.xprivacyguard.firewall.InstalledAppInfo
import com.xconflictionx.xprivacyguard.vpn.transport.AetherNative
import com.xconflictionx.xprivacyguard.vpn.transport.TunnelProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class TunnelStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

data class TrafficLogEntry(
    val id: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val target: String,
    val protocol: String,
    val blocked: Boolean,
    val matchedRule: String? = null,
    val sourceName: String? = null
)

data class VpnRuntimeState(
    val status: TunnelStatus = TunnelStatus.DISCONNECTED,
    val statusMessage: String = "Disconnected",
    val activeTransport: TunnelProtocol = TunnelProtocol.CLOUDFLARE_MASQUE,
    val cloudflareOneConfig: CloudflareOneConfig = CloudflareOneConfig(),
    val handshakeEstablished: Boolean = false,
    val lastHandshakeMs: Long = 0L,
    val endpoint: String = "Not Configured",
    val tunnelAddresses: String = "Sign in to Cloudflare One on Tunnel tab",
    val tunnelDns: String = "System DNS (Default)",
    val zeroTrustDns: ZeroTrustDnsConfig = ZeroTrustDnsConfig(),
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L,
    val blockedCount: Long = 0L,
    val allowedCount: Long = 0L,
    val dnsFilteringEnabled: Boolean = true,
    val deepInspectionEnabled: Boolean = true,
    val useNxDomain: Boolean = false,
    val autoStartOnBoot: Boolean = true,
    val bypassedPackages: Set<String> = emptySet(),
    val effectiveBypassedPackages: Set<String> = emptySet(),
    val filterStats: FilterStats = FilterStats(0, 0, 0, 0, 0),
    val isScreenOn: Boolean = true,
    val dynamicKeepaliveSec: Int = 0,
    val isAlwaysOnActive: Boolean = false,
    val recentLogs: List<TrafficLogEntry> = emptyList()
)

object VpnStateManager {
    private const val PREFS_NAME = "xprivacy_wg_prefs_v2"
    private const val KEY_WG_CONF = "wg_conf_text"
    private const val KEY_DNS_FILTER = "dns_filter_enabled"
    private const val KEY_DEEP_INSPECT = "deep_inspect_enabled"
    private const val KEY_USE_NXDOMAIN = "use_nxdomain"
    private const val KEY_AUTO_START_BOOT = "auto_start_on_boot"
    private const val KEY_BYPASSED_PACKAGES = "firewall_tunneled_packages_v1"

    private const val KEY_ZT_IPV4_PRIMARY = "zt_dns_ipv4_primary"
    private const val KEY_ZT_IPV4_SECONDARY = "zt_dns_ipv4_secondary"
    private const val KEY_ZT_SOURCE_IPV4 = "zt_dns_source_ipv4"
    private const val KEY_ZT_IPV6 = "zt_dns_ipv6"
    private const val KEY_ZT_SOURCE_IPV6 = "zt_dns_source_ipv6"
    private const val KEY_ZT_DOT = "zt_dns_dot"
    private const val KEY_ZT_DOH = "zt_dns_doh"
    private const val KEY_PRIVATE_DNS_DOMAIN = "private_dns_domain"
    private const val KEY_PRIVATE_DNS_SERVER = "private_dns_server"

    private const val KEY_ACTIVE_TRANSPORT = "active_transport_protocol"
    private const val KEY_CF_TEAM = "cf_one_team_name"
    private const val KEY_CF_EMAIL = "cf_one_user_email"
    private const val KEY_CF_TOKEN = "cf_one_access_token"
    private const val KEY_CF_SERVICE_ID = "cf_one_service_client_id"
    private const val KEY_CF_SERVICE_SECRET = "cf_one_service_client_secret"
    private const val KEY_CF_CUSTOM_PEER = "cf_one_custom_peer"
    private const val KEY_CF_PREFER_H2 = "cf_one_prefer_h2"
    private const val KEY_CF_ENABLE_ECH = "cf_one_enable_ech"
    private const val KEY_CF_DEVICE_ID = "cf_one_device_id"
    private const val KEY_CF_ORG = "cf_one_organization"
    private const val KEY_CF_IPV4 = "cf_one_ipv4"
    private const val KEY_CF_IPV6 = "cf_one_ipv6"
    private const val KEY_CF_ASSIGNED_ENDPOINT = "cf_one_assigned_endpoint"
    private const val KEY_CF_CERT_ISSUED_AT = "cf_one_cert_issued_at"

    private const val MAX_LOG_ENTRIES = 120
    private const val TELEMETRY_FLUSH_INTERVAL_MS = 1500L

    val filterEngine = FilterEngine()

    @Volatile
    var activeVpnService: VpnService? = null

    @Volatile
    private var initialized = false

    @Volatile
    private var storageMigrated = false

    @Volatile
    private var uiVisible = false

    val isUiVisible: Boolean
        get() = uiVisible

    /**
     * Returns a Device-Protected Storage Context so Always-On VPN and BootCompletedReceiver
     * can read WireGuard configs, Zero Trust DNS settings, and filter lists immediately on reboot
     * (even during Direct Boot before screen unlock), while automatically migrating any existing
     * credential-encrypted settings once unlocked.
     */
    fun deviceProtectedContext(context: Context): Context {
        val appCtx = context.applicationContext ?: context
        val dpCtx = appCtx.createDeviceProtectedStorageContext()
        if (!storageMigrated) {
            runCatching {
                val um = appCtx.getSystemService(android.os.UserManager::class.java)
                if (um == null || um.isUserUnlocked) {
                    val dpPrefs = dpCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    if (!dpPrefs.contains(KEY_WG_CONF)) {
                        dpCtx.moveSharedPreferencesFrom(appCtx, PREFS_NAME)
                    }
                    val dpFilterPrefs = dpCtx.getSharedPreferences("xprivacy_filters_prefs", Context.MODE_PRIVATE)
                    if (!dpFilterPrefs.contains("filter_sources_v2")) {
                        dpCtx.moveSharedPreferencesFrom(appCtx, "xprivacy_filters_prefs")
                    }
                    val oldFiltersDir = java.io.File(appCtx.filesDir, "filters")
                    val newFiltersDir = java.io.File(dpCtx.filesDir, "filters")
                    if (oldFiltersDir.exists()) {
                        newFiltersDir.mkdirs()
                        oldFiltersDir.listFiles()?.forEach { file ->
                            val target = java.io.File(newFiltersDir, file.name)
                            if (!target.exists()) {
                                file.copyTo(target, overwrite = true)
                            }
                        }
                    }
                    storageMigrated = true
                }
            }
        }
        return dpCtx
    }

    // Lock-free atomic counters to prevent per-packet StateFlow / Compose recomposition storms
    private val rxBytesCounter = AtomicLong(0L)
    private val txBytesCounter = AtomicLong(0L)
    private val blockedCounter = AtomicLong(0L)
    private val allowedCounter = AtomicLong(0L)
    private val logIdSequence = AtomicLong(0L)
    private val lastTelemetryPublishMs = AtomicLong(0L)
    private val logsDirty = AtomicBoolean(false)
    private val logBuffer = ConcurrentLinkedDeque<TrafficLogEntry>()

    private val _state = MutableStateFlow(VpnRuntimeState())
    val state: StateFlow<VpnRuntimeState> = _state.asStateFlow()

    fun setUiVisible(visible: Boolean) {
        uiVisible = visible
        if (visible) {
            flushTelemetryNow()
        }
    }

    @Synchronized
    fun ensureInitialized(context: Context) {
        if (!initialized || !storageMigrated) {
            val repo = FilterSourceRepository(context.applicationContext)
            initialize(context.applicationContext, repo.loadSources())
        }
    }

    @Synchronized
    fun initialize(context: Context, sources: List<FilterSource>) {
        val wasMigrated = storageMigrated
        val dpCtx = deviceProtectedContext(context)
        if (!wasMigrated && storageMigrated) {
            (activeVpnService as? XPrivacyGuardService)?.onFirewallBypassChanged()
        }
        runCatching {
            dpCtx.getSharedPreferences("xprivacy_wg_prefs", Context.MODE_PRIVATE).edit().clear().apply()
        }

        val prefs = dpCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val dnsFilter = prefs.getBoolean(KEY_DNS_FILTER, true)
        val deepInspect = prefs.getBoolean(KEY_DEEP_INSPECT, true)
        val nxDomain = prefs.getBoolean(KEY_USE_NXDOMAIN, false)
        val autoStart = prefs.getBoolean(KEY_AUTO_START_BOOT, true)

        val ztDns = loadZeroTrustDnsConfig(dpCtx)
        val confText = loadWireGuardConfText(dpCtx)
        val parsed = WireGuardConfig.parse(confText).getOrNull()
        val transport = loadActiveTransport(dpCtx)
        val cfConfig = loadCloudflareOneConfig(dpCtx)
        val bypassed = loadBypassedPackages(dpCtx)
        val effectiveBypassed = FirewallManager.expandBypassPackages(bypassed)

        filterEngine.loadSources(sources)
        initialized = true

        val displayEndpoint = if (transport == TunnelProtocol.CLOUDFLARE_MASQUE) {
            cfConfig.displayEndpoint
        } else {
            parsed?.endpoint ?: "Not Configured"
        }
        val displayAddresses = if (transport == TunnelProtocol.CLOUDFLARE_MASQUE) {
            cfConfig.displayAddresses
        } else {
            parsed?.addresses?.joinToString(", ") ?: "Import a .conf file in Tunnel tab"
        }

        _state.update { current ->
            current.copy(
                activeTransport = transport,
                cloudflareOneConfig = cfConfig,
                endpoint = displayEndpoint,
                tunnelAddresses = displayAddresses,
                tunnelDns = ztDns.activeModeSummary(),
                zeroTrustDns = ztDns,
                dnsFilteringEnabled = dnsFilter,
                deepInspectionEnabled = deepInspect,
                useNxDomain = nxDomain,
                autoStartOnBoot = autoStart,
                bypassedPackages = bypassed,
                effectiveBypassedPackages = effectiveBypassed,
                filterStats = filterEngine.stats
            )
        }
    }

    fun aetherIdentityDir(context: Context): java.io.File {
        return java.io.File(deviceProtectedContext(context).filesDir, "aether")
    }

    fun aetherIdentityFile(context: Context, config: CloudflareOneConfig): java.io.File {
        val team = config.normalizedTeam
        val fileName = if (team.isNotEmpty()) "aether-team-$team.toml" else "aether-masque.toml"
        return java.io.File(aetherIdentityDir(context), fileName)
    }

    fun hasPersistedAetherIdentity(
        context: Context,
        config: CloudflareOneConfig = loadCloudflareOneConfig(context)
    ): Boolean {
        val file = aetherIdentityFile(context, config)
        return file.exists() && file.length() > 0L
    }

    private fun parseTomlStringValue(text: String, key: String): String {
        val regex = Regex("""(?m)^\s*${Regex.escape(key)}\s*=\s*"([^"]*)"\s*$""")
        return regex.find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }

    private fun parseTomlLongValue(text: String, key: String): Long {
        val regex = Regex("""(?m)^\s*${Regex.escape(key)}\s*=\s*(\d+)\s*$""")
        return regex.find(text)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
    }

    fun loadActiveTransport(context: Context): TunnelProtocol {
        val prefs = deviceProtectedContext(context).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_ACTIVE_TRANSPORT, TunnelProtocol.CLOUDFLARE_MASQUE.name)
        return runCatching { TunnelProtocol.valueOf(raw ?: TunnelProtocol.CLOUDFLARE_MASQUE.name) }
            .getOrDefault(TunnelProtocol.CLOUDFLARE_MASQUE)
    }

    fun saveActiveTransport(context: Context, protocol: TunnelProtocol) {
        val dpCtx = deviceProtectedContext(context)
        dpCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIVE_TRANSPORT, protocol.name)
            .apply()
        val parsedWg = WireGuardConfig.parse(loadWireGuardConfText(dpCtx)).getOrNull()
        val cfConfig = loadCloudflareOneConfig(dpCtx)
        _state.update { current ->
            current.copy(
                activeTransport = protocol,
                endpoint = if (protocol == TunnelProtocol.CLOUDFLARE_MASQUE) {
                    cfConfig.displayEndpoint
                } else {
                    parsedWg?.endpoint ?: "Not Configured"
                },
                tunnelAddresses = if (protocol == TunnelProtocol.CLOUDFLARE_MASQUE) {
                    cfConfig.displayAddresses
                } else {
                    parsedWg?.addresses?.joinToString(", ") ?: "Import a .conf file in Tunnel tab"
                }
            )
        }
    }

    fun loadCloudflareOneConfig(context: Context): CloudflareOneConfig {
        val prefs = deviceProtectedContext(context).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val base = CloudflareOneConfig(
            teamName = prefs.getString(KEY_CF_TEAM, "") ?: "",
            userEmail = prefs.getString(KEY_CF_EMAIL, "") ?: "",
            accessToken = prefs.getString(KEY_CF_TOKEN, "") ?: "",
            serviceClientId = prefs.getString(KEY_CF_SERVICE_ID, "") ?: "",
            serviceClientSecret = prefs.getString(KEY_CF_SERVICE_SECRET, "") ?: "",
            customPeerEndpoint = prefs.getString(KEY_CF_CUSTOM_PEER, "") ?: "",
            preferHttp2 = prefs.getBoolean(KEY_CF_PREFER_H2, false),
            enableEch = prefs.getBoolean(KEY_CF_ENABLE_ECH, false),
            deviceId = prefs.getString(KEY_CF_DEVICE_ID, "") ?: "",
            organization = prefs.getString(KEY_CF_ORG, "") ?: "",
            ipv4Address = prefs.getString(KEY_CF_IPV4, "") ?: "",
            ipv6Address = prefs.getString(KEY_CF_IPV6, "") ?: "",
            assignedEndpoint = prefs.getString(KEY_CF_ASSIGNED_ENDPOINT, "") ?: "",
            certIssuedAt = prefs.getLong(KEY_CF_CERT_ISSUED_AT, 0L)
        )
        if (!base.isEnrolled) {
            val identityFile = aetherIdentityFile(context, base)
            if (identityFile.exists() && identityFile.length() > 0L) {
                val hydrated = runCatching {
                    val toml = identityFile.readText()
                    val fileDeviceId = parseTomlStringValue(toml, "device_id")
                    val fileIpv4 = parseTomlStringValue(toml, "ipv4")
                    val fileIpv6 = parseTomlStringValue(toml, "ipv6")
                    val fileOrg = parseTomlStringValue(toml, "organization")
                    val fileEndpoint = parseTomlStringValue(toml, "assigned_endpoint")
                    val fileCertIssuedAt = parseTomlLongValue(toml, "cert_issued_at")
                    if (fileDeviceId.isNotBlank() && fileIpv4.isNotBlank()) {
                        base.copy(
                            deviceId = base.deviceId.ifBlank { fileDeviceId },
                            organization = base.organization.ifBlank { fileOrg },
                            ipv4Address = base.ipv4Address.ifBlank { fileIpv4 },
                            ipv6Address = base.ipv6Address.ifBlank { fileIpv6 },
                            assignedEndpoint = base.assignedEndpoint.ifBlank { fileEndpoint },
                            certIssuedAt = if (base.certIssuedAt > 0L) base.certIssuedAt else fileCertIssuedAt
                        )
                    } else {
                        base
                    }
                }.getOrDefault(base)
                if (hydrated != base) {
                    return hydrated
                }
            }
        }
        return base
    }

    fun saveCloudflareOneConfig(context: Context, config: CloudflareOneConfig) {
        deviceProtectedContext(context).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CF_TEAM, config.teamName.trim())
            .putString(KEY_CF_EMAIL, config.userEmail.trim())
            .putString(KEY_CF_TOKEN, config.accessToken.trim())
            .putString(KEY_CF_SERVICE_ID, config.serviceClientId.trim())
            .putString(KEY_CF_SERVICE_SECRET, config.serviceClientSecret.trim())
            .putString(KEY_CF_CUSTOM_PEER, config.customPeerEndpoint.trim())
            .putBoolean(KEY_CF_PREFER_H2, config.preferHttp2)
            .putBoolean(KEY_CF_ENABLE_ECH, config.enableEch)
            .putString(KEY_CF_DEVICE_ID, config.deviceId.trim())
            .putString(KEY_CF_ORG, config.organization.trim())
            .putString(KEY_CF_IPV4, config.ipv4Address.trim())
            .putString(KEY_CF_IPV6, config.ipv6Address.trim())
            .putString(KEY_CF_ASSIGNED_ENDPOINT, config.assignedEndpoint.trim())
            .putLong(KEY_CF_CERT_ISSUED_AT, config.certIssuedAt)
            .apply()

        _state.update { current ->
            val isMasque = current.activeTransport == TunnelProtocol.CLOUDFLARE_MASQUE
            current.copy(
                cloudflareOneConfig = config,
                endpoint = if (isMasque) config.displayEndpoint else current.endpoint,
                tunnelAddresses = if (isMasque) config.displayAddresses else current.tunnelAddresses
            )
        }
    }

    fun clearCloudflareOneCredentials(context: Context) {
        val current = loadCloudflareOneConfig(context)
        val cleared = current.copy(
            accessToken = "",
            serviceClientId = "",
            serviceClientSecret = "",
            deviceId = "",
            organization = "",
            ipv4Address = "",
            ipv6Address = "",
            assignedEndpoint = "",
            certIssuedAt = 0L
        )
        saveCloudflareOneConfig(context, cleared)
        runCatching { aetherIdentityDir(context).deleteRecursively() }
        runCatching { AetherNative.nativeTeamTokenClear() }
    }

    fun loadBypassedPackages(context: Context): Set<String> {
        val prefs = deviceProtectedContext(context).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getStringSet(KEY_BYPASSED_PACKAGES, emptySet()) ?: emptySet()
        return stored.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    fun saveBypassedPackages(
        context: Context,
        packages: Set<String>,
        installedApps: List<InstalledAppInfo> = emptyList()
    ) {
        val cleaned = packages.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        deviceProtectedContext(context).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_BYPASSED_PACKAGES, cleaned)
            .apply()

        val pm = context.packageManager
        val effective = FirewallManager.expandBypassPackages(
            selectedPackages = cleaned,
            installedApps = installedApps,
            sharedUidLookup = { uid -> runCatching { pm.getPackagesForUid(uid) }.getOrNull() }
        )

        _state.update {
            it.copy(
                bypassedPackages = cleaned,
                effectiveBypassedPackages = effective
            )
        }

        val currentStatus = _state.value.status
        if (currentStatus == TunnelStatus.CONNECTED || currentStatus == TunnelStatus.CONNECTING) {
            (activeVpnService as? XPrivacyGuardService)?.onFirewallBypassChanged()
        }
    }

    fun refreshEffectiveBypassWithInstalledApps(context: Context, installedApps: List<InstalledAppInfo>) {
        val currentSelected = _state.value.bypassedPackages.ifEmpty { loadBypassedPackages(context) }
        val pm = context.packageManager
        val effective = FirewallManager.expandBypassPackages(
            selectedPackages = currentSelected,
            installedApps = installedApps,
            sharedUidLookup = { uid -> runCatching { pm.getPackagesForUid(uid) }.getOrNull() }
        )
        _state.update {
            it.copy(
                bypassedPackages = currentSelected,
                effectiveBypassedPackages = effective
            )
        }
    }

    fun resolveEffectiveBypassPackages(context: Context): Set<String> {
        val selected = _state.value.bypassedPackages.ifEmpty { loadBypassedPackages(context) }
        if (selected.isEmpty()) return emptySet()
        val pm = context.packageManager
        val installed = runCatching { FirewallManager.loadInstalledApps(context) }.getOrDefault(emptyList())
        return FirewallManager.expandBypassPackages(
            selectedPackages = selected,
            installedApps = installed,
            sharedUidLookup = { uid -> runCatching { pm.getPackagesForUid(uid) }.getOrNull() }
        )
    }

    fun loadZeroTrustDnsConfig(context: Context): ZeroTrustDnsConfig {
        val prefs = deviceProtectedContext(context).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedDomain = prefs.getString(KEY_PRIVATE_DNS_DOMAIN, null)?.trim().orEmpty()
        val savedServer = prefs.getString(KEY_PRIVATE_DNS_SERVER, null)?.trim().orEmpty()
        return ZeroTrustDnsConfig(
            ipv4Primary = prefs.getString(KEY_ZT_IPV4_PRIMARY, null)?.trim().orEmpty(),
            ipv4Secondary = prefs.getString(KEY_ZT_IPV4_SECONDARY, null)?.trim().orEmpty(),
            sourceIpv4 = prefs.getString(KEY_ZT_SOURCE_IPV4, null)?.trim().orEmpty(),
            ipv6Endpoint = prefs.getString(KEY_ZT_IPV6, null)?.trim().orEmpty(),
            sourceIpv6 = prefs.getString(KEY_ZT_SOURCE_IPV6, null)?.trim().orEmpty(),
            dotEndpoint = prefs.getString(KEY_ZT_DOT, null)?.trim().orEmpty(),
            dohEndpoint = prefs.getString(KEY_ZT_DOH, null)?.trim().orEmpty(),
            privateDomain = savedDomain,
            privateDnsServer = savedServer
        )
    }

    fun saveZeroTrustDnsConfig(context: Context, config: ZeroTrustDnsConfig) {
        val cleaned = ZeroTrustDnsConfig(
            ipv4Primary = config.ipv4Primary.trim(),
            ipv4Secondary = config.ipv4Secondary.trim(),
            sourceIpv4 = config.sourceIpv4.trim(),
            ipv6Endpoint = config.ipv6Endpoint.trim(),
            sourceIpv6 = config.sourceIpv6.trim(),
            dotEndpoint = config.dotEndpoint.trim(),
            dohEndpoint = config.dohEndpoint.trim(),
            privateDomain = config.privateDomain.trim().trim(46.toChar()).lowercase(),
            privateDnsServer = config.privateDnsServer.trim()
        )
        deviceProtectedContext(context).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ZT_IPV4_PRIMARY, cleaned.ipv4Primary)
            .putString(KEY_ZT_IPV4_SECONDARY, cleaned.ipv4Secondary)
            .putString(KEY_ZT_SOURCE_IPV4, cleaned.sourceIpv4)
            .putString(KEY_ZT_IPV6, cleaned.ipv6Endpoint)
            .putString(KEY_ZT_SOURCE_IPV6, cleaned.sourceIpv6)
            .putString(KEY_ZT_DOT, cleaned.dotEndpoint)
            .putString(KEY_ZT_DOH, cleaned.dohEndpoint)
            .putString(KEY_PRIVATE_DNS_DOMAIN, cleaned.privateDomain)
            .putString(KEY_PRIVATE_DNS_SERVER, cleaned.privateDnsServer)
            .apply()

        ZeroTrustDnsResolver.clearCaches()

        _state.update {
            it.copy(
                zeroTrustDns = cleaned,
                tunnelDns = cleaned.activeModeSummary()
            )
        }
    }

    fun isAutoStartOnBootEnabled(context: Context): Boolean {
        val prefs = deviceProtectedContext(context).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_AUTO_START_BOOT, true)
    }

    fun hasConfiguredTunnel(context: Context): Boolean {
        val transport = loadActiveTransport(context)
        if (transport == TunnelProtocol.CLOUDFLARE_MASQUE) {
            val cf = loadCloudflareOneConfig(context)
            return cf.canConnect || hasPersistedAetherIdentity(context, cf)
        }
        return loadWireGuardConfText(context).isNotBlank()
    }

    fun updateFilterSources(sources: List<FilterSource>) {
        filterEngine.loadSources(sources)
        _state.update { it.copy(filterStats = filterEngine.stats) }
    }

    fun updateScreenState(isScreenOn: Boolean, keepaliveSec: Int = 0) {
        _state.update {
            it.copy(
                isScreenOn = isScreenOn,
                dynamicKeepaliveSec = if (isScreenOn) keepaliveSec else 0
            )
        }
    }

    fun updateAlwaysOnState(alwaysOn: Boolean) {
        _state.update { it.copy(isAlwaysOnActive = alwaysOn) }
    }

    fun loadWireGuardConfText(context: Context): String {
        val prefs = deviceProtectedContext(context).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_WG_CONF, "") ?: ""
        return saved
    }

    fun resetToDefaultStaticConfig(context: Context): Result<WireGuardConfig> {
        return Result.failure(IllegalStateException("Import your own WireGuard configuration first."))
    }

    fun exportConfigFile(context: Context): String? {
        val confText = loadWireGuardConfText(context)
        return try {
            val fileName = "xPrivacyGuard.conf"
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                resolver.openOutputStream(uri)?.use { os ->
                    os.write(confText.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
                }
                "Downloads/$fileName"
            } else {
                val dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                val target = java.io.File(dir, fileName)
                java.io.FileOutputStream(target).use { os ->
                    os.write(confText.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
                }
                target.absolutePath
            }
        } catch (_: Exception) {
            null
        }
    }

    fun saveWireGuardConfText(context: Context, confText: String): Result<WireGuardConfig> {
        val result = WireGuardConfig.parse(confText)
        result.onSuccess { cfg ->
            deviceProtectedContext(context).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_WG_CONF, confText)
                .apply()
            _state.update {
                it.copy(
                    endpoint = cfg.endpoint,
                    tunnelAddresses = cfg.addresses.joinToString(", ")
                )
            }
        }
        return result
    }

    fun clearWireGuardConf(context: Context) {
        deviceProtectedContext(context).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_WG_CONF)
            .apply()
        _state.update {
            it.copy(
                endpoint = "Not Configured",
                tunnelAddresses = "Import a .conf file in WG Conf tab"
            )
        }
    }

    fun setFilterSettings(
        context: Context,
        dnsFilteringEnabled: Boolean = _state.value.dnsFilteringEnabled,
        deepInspectionEnabled: Boolean = _state.value.deepInspectionEnabled,
        useNxDomain: Boolean = _state.value.useNxDomain,
        autoStartOnBoot: Boolean = _state.value.autoStartOnBoot
    ) {
        deviceProtectedContext(context).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DNS_FILTER, dnsFilteringEnabled)
            .putBoolean(KEY_DEEP_INSPECT, deepInspectionEnabled)
            .putBoolean(KEY_USE_NXDOMAIN, useNxDomain)
            .putBoolean(KEY_AUTO_START_BOOT, autoStartOnBoot)
            .apply()

        _state.update {
            it.copy(
                dnsFilteringEnabled = dnsFilteringEnabled,
                deepInspectionEnabled = deepInspectionEnabled,
                useNxDomain = useNxDomain,
                autoStartOnBoot = autoStartOnBoot
            )
        }
    }

    fun updateStatus(
        status: TunnelStatus,
        message: String,
        handshakeEstablished: Boolean = _state.value.handshakeEstablished,
        lastHandshakeMs: Long = _state.value.lastHandshakeMs
    ) {
        _state.update {
            it.copy(
                status = status,
                statusMessage = message,
                handshakeEstablished = handshakeEstablished,
                lastHandshakeMs = lastHandshakeMs,
                rxBytes = rxBytesCounter.get(),
                txBytes = txBytesCounter.get(),
                blockedCount = blockedCounter.get(),
                allowedCount = allowedCounter.get()
            )
        }
    }

    fun addRxBytes(bytes: Int) {
        if (bytes <= 0) return
        rxBytesCounter.addAndGet(bytes.toLong())
        maybePublishTelemetry()
    }

    fun addTxBytes(bytes: Int) {
        if (bytes <= 0) return
        txBytesCounter.addAndGet(bytes.toLong())
        maybePublishTelemetry()
    }

    fun recordTrafficEvent(
        target: String,
        protocol: String,
        blocked: Boolean,
        matchedRule: String?,
        sourceName: String?
    ) {
        if (blocked) {
            blockedCounter.incrementAndGet()
        } else {
            allowedCounter.incrementAndGet()
            // When the app UI is not on screen, skip allocating log entries for allowed traffic
            if (!uiVisible) return
        }

        val now = System.currentTimeMillis()
        val head = logBuffer.peekFirst()
        if (head == null || head.target != target || head.blocked != blocked || now - head.timestampMs > 1500L) {
            val entry = TrafficLogEntry(
                id = logIdSequence.incrementAndGet().toString(),
                timestampMs = now,
                target = target,
                protocol = protocol,
                blocked = blocked,
                matchedRule = matchedRule,
                sourceName = sourceName
            )
            logBuffer.addFirst(entry)
            while (logBuffer.size > MAX_LOG_ENTRIES) {
                logBuffer.pollLast()
            }
            logsDirty.set(true)
        }

        maybePublishTelemetry()
    }

    private fun maybePublishTelemetry() {
        if (!uiVisible) return
        val now = System.currentTimeMillis()
        val prev = lastTelemetryPublishMs.get()
        if (now - prev < TELEMETRY_FLUSH_INTERVAL_MS) return
        if (!lastTelemetryPublishMs.compareAndSet(prev, now)) return
        flushTelemetryNow()
    }

    fun flushTelemetryNow() {
        val rx = rxBytesCounter.get()
        val tx = txBytesCounter.get()
        val blk = blockedCounter.get()
        val alw = allowedCounter.get()
        val shouldCopyLogs = logsDirty.compareAndSet(true, false)
        val snapshotLogs = if (shouldCopyLogs) logBuffer.toList() else null

        _state.update { current ->
            current.copy(
                rxBytes = rx,
                txBytes = tx,
                blockedCount = blk,
                allowedCount = alw,
                recentLogs = snapshotLogs ?: current.recentLogs
            )
        }
    }

    fun clearLogs() {
        logBuffer.clear()
        blockedCounter.set(0L)
        allowedCounter.set(0L)
        logsDirty.set(false)
        _state.update { it.copy(recentLogs = emptyList(), blockedCount = 0L, allowedCount = 0L) }
    }
}
