package com.xconflictionx.xprivacyguard.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xconflictionx.xprivacyguard.config.CloudflareOneConfig
import com.xconflictionx.xprivacyguard.config.WireGuardConfig
import com.xconflictionx.xprivacyguard.dns.ZeroTrustDnsConfig
import com.xconflictionx.xprivacyguard.filter.FilterDecision
import com.xconflictionx.xprivacyguard.filter.FilterSource
import com.xconflictionx.xprivacyguard.filter.FilterSourceRepository
import com.xconflictionx.xprivacyguard.firewall.FirewallManager
import com.xconflictionx.xprivacyguard.firewall.InstalledAppInfo
import com.xconflictionx.xprivacyguard.firewall.SpecialBypassGroup
import com.xconflictionx.xprivacyguard.security.DeviceSecurityChecker
import com.xconflictionx.xprivacyguard.security.DeviceSecurityReport
import com.xconflictionx.xprivacyguard.security.XSecuritySyncResult
import com.xconflictionx.xprivacyguard.vpn.TunnelStatus
import com.xconflictionx.xprivacyguard.vpn.VpnRuntimeState
import com.xconflictionx.xprivacyguard.vpn.VpnStateManager
import com.xconflictionx.xprivacyguard.vpn.transport.AetherNative
import com.xconflictionx.xprivacyguard.vpn.transport.TunnelProtocol
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SystemPermissionStatus(
    val batteryOptimizationIgnored: Boolean = false,
    val vpnPermissionGranted: Boolean = false,
    val notificationPermissionGranted: Boolean = true
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val filterRepo = FilterSourceRepository(application.applicationContext)

    val vpnState: StateFlow<VpnRuntimeState> = VpnStateManager.state

    private val _filterSources = MutableStateFlow<List<FilterSource>>(emptyList())
    val filterSources: StateFlow<List<FilterSource>> = _filterSources.asStateFlow()

    private val _wgConfText = MutableStateFlow("")
    val wgConfText: StateFlow<String> = _wgConfText.asStateFlow()

    private val _ztDnsDraft = MutableStateFlow(ZeroTrustDnsConfig())
    val ztDnsDraft: StateFlow<ZeroTrustDnsConfig> = _ztDnsDraft.asStateFlow()

    private val _cfOneDraft = MutableStateFlow(CloudflareOneConfig())
    val cfOneDraft: StateFlow<CloudflareOneConfig> = _cfOneDraft.asStateFlow()

    private val _cfOtpSessionId = MutableStateFlow(0L)
    val cfOtpSessionId: StateFlow<Long> = _cfOtpSessionId.asStateFlow()

    private val _cfAuthBusy = MutableStateFlow(false)
    val cfAuthBusy: StateFlow<Boolean> = _cfAuthBusy.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    private val _isSyncingFilters = MutableStateFlow(false)
    val isSyncingFilters: StateFlow<Boolean> = _isSyncingFilters.asStateFlow()

    private val _permissionStatus = MutableStateFlow(SystemPermissionStatus())
    val permissionStatus: StateFlow<SystemPermissionStatus> = _permissionStatus.asStateFlow()

    private val _installedApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    val installedApps: StateFlow<List<InstalledAppInfo>> = _installedApps.asStateFlow()

    private val _isLoadingApps = MutableStateFlow(false)
    val isLoadingApps: StateFlow<Boolean> = _isLoadingApps.asStateFlow()

    private val prefs = application.getSharedPreferences("xprivacyguard_prefs", Context.MODE_PRIVATE)

    private val _xSecurityDomain = MutableStateFlow(
        prefs.getString("xsecurity_domain", "") ?: ""
    )
    val xSecurityDomain: StateFlow<String> = _xSecurityDomain.asStateFlow()

    private val _xSecurityApiKey = MutableStateFlow(
        prefs.getString("xsecurity_api_key", "") ?: ""
    )
    val xSecurityApiKey: StateFlow<String> = _xSecurityApiKey.asStateFlow()

    private val _deviceSecurityReport = MutableStateFlow(DeviceSecurityReport())
    val deviceSecurityReport: StateFlow<DeviceSecurityReport> = _deviceSecurityReport.asStateFlow()

    private val _xSecuritySyncResult = MutableStateFlow<XSecuritySyncResult?>(null)
    val xSecuritySyncResult: StateFlow<XSecuritySyncResult?> = _xSecuritySyncResult.asStateFlow()

    private val _isSyncingSecurity = MutableStateFlow(false)
    val isSyncingSecurity: StateFlow<Boolean> = _isSyncingSecurity.asStateFlow()

    private val _showAlwaysOnPrompt = MutableStateFlow(false)
    val showAlwaysOnPrompt: StateFlow<Boolean> = _showAlwaysOnPrompt.asStateFlow()

    fun hasCompletedFirstRunSetup(): Boolean {
        return prefs.getBoolean("first_run_permissions_completed_v1", false)
    }

    fun markFirstRunSetupCompleted() {
        prefs.edit().putBoolean("first_run_permissions_completed_v1", true).apply()
    }

    fun requestAlwaysOnPrompt() {
        _showAlwaysOnPrompt.value = true
    }

    fun dismissAlwaysOnPrompt() {
        _showAlwaysOnPrompt.value = false
        markFirstRunSetupCompleted()
    }

    init {
        val initialSources = filterRepo.loadSources()
        _filterSources.value = initialSources
        VpnStateManager.initialize(application.applicationContext, initialSources)
        _wgConfText.value = VpnStateManager.loadWireGuardConfText(application.applicationContext)
        _ztDnsDraft.value = VpnStateManager.loadZeroTrustDnsConfig(application.applicationContext)
        _cfOneDraft.value = VpnStateManager.loadCloudflareOneConfig(application.applicationContext)
        refreshPermissionStatus()
        refreshInstalledApps()
        refreshDeviceSecurityReport()
        startSecurityAutoSyncLoop()
    }

    private fun startSecurityAutoSyncLoop() {
        viewModelScope.launch {
            var lastSyncedConnectedAt = 0L
            var wasConnected = false
            while (true) {
                val isConnected = vpnState.value.status == TunnelStatus.CONNECTED
                val now = System.currentTimeMillis()
                if (isConnected && _xSecurityApiKey.value.isNotBlank()) {
                    if (!wasConnected) {
                        delay(2000L)
                    }
                    if (vpnState.value.status == TunnelStatus.CONNECTED &&
                        (!wasConnected || now - lastSyncedConnectedAt >= 15L * 60L * 1000L)
                    ) {
                        syncDeviceSecurityToXServer(silent = true)
                        lastSyncedConnectedAt = System.currentTimeMillis()
                    }
                }
                wasConnected = isConnected
                delay(5000L)
            }
        }
    }

    fun refreshInstalledApps() {
        viewModelScope.launch {
            _isLoadingApps.value = true
            val ctx = getApplication<Application>().applicationContext
            val apps = withContext(Dispatchers.IO) {
                FirewallManager.loadInstalledApps(ctx)
            }
            _installedApps.value = apps
            VpnStateManager.refreshEffectiveBypassWithInstalledApps(ctx, apps)
            _isLoadingApps.value = false
        }
    }

    fun toggleAppBypass(app: InstalledAppInfo, shouldBypass: Boolean) {
        if (app.packageName == getApplication<Application>().packageName ||
            FirewallManager.ALWAYS_TUNNELED_PACKAGES.contains(app.packageName)
        ) {
            _statusBanner.value = "${app.appName} stays routed through the Zero Trust tunnel to reach xServer."
            return
        }

        val ctx = getApplication<Application>().applicationContext
        val apps = _installedApps.value
        val updated = vpnState.value.bypassedPackages.toMutableSet()

        if (shouldBypass) {
            updated.add(app.packageName)
            VpnStateManager.saveBypassedPackages(ctx, updated, apps)
            _statusBanner.value = "${app.appName} is now routed through the VPN"
        } else {
            if (!updated.contains(app.packageName) &&
                vpnState.value.effectiveBypassedPackages.contains(app.packageName)
            ) {
                _statusBanner.value = "${app.appName} is included automatically as a required companion app"
                return
            }
            updated.remove(app.packageName)
            VpnStateManager.saveBypassedPackages(ctx, updated, apps)
            _statusBanner.value = "${app.appName} is now bypassing the VPN"
        }
    }

    fun clearAllBypassedApps() {
        val ctx = getApplication<Application>().applicationContext
        VpnStateManager.saveBypassedPackages(ctx, emptySet(), _installedApps.value)
        _statusBanner.value = "Cleared app selections. Only xPrivacyGuard uses the tunnel; every other app bypasses it."
    }

    fun loadDefaultStaticWarpConfig() {
        _statusBanner.value = "No default WireGuard profile is bundled. Import your own Cloudflare Zero Trust .conf file."
    }

    fun exportConfigFile(): String? {
        val path = VpnStateManager.exportConfigFile(getApplication())
        _statusBanner.value = if (path != null) "Exported to $path" else "Failed to export config"
        return path
    }

    fun refreshPermissionStatus() {
        val ctx = getApplication<Application>().applicationContext
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val batteryIgnored = pm?.isIgnoringBatteryOptimizations(ctx.packageName) ?: false
        val vpnGranted = runCatching { VpnService.prepare(ctx) == null }.getOrDefault(false)
        val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        _permissionStatus.value = SystemPermissionStatus(
            batteryOptimizationIgnored = batteryIgnored,
            vpnPermissionGranted = vpnGranted,
            notificationPermissionGranted = notifGranted
        )
    }

    fun dismissBanner() {
        _statusBanner.value = null
    }

    fun showBanner(message: String) {
        _statusBanner.value = message
    }

    fun hasValidWgConfig(): Boolean {
        return WireGuardConfig.parse(_wgConfText.value).isSuccess
    }

    fun hasValidActiveTransportConfig(): Boolean {
        return when (vpnState.value.activeTransport) {
            TunnelProtocol.WIREGUARD -> hasValidWgConfig()
            TunnelProtocol.CLOUDFLARE_MASQUE -> {
                val cfg = vpnState.value.cloudflareOneConfig
                cfg.canConnect || VpnStateManager.hasPersistedAetherIdentity(getApplication(), cfg)
            }
        }
    }

    fun selectTransport(protocol: TunnelProtocol) {
        val ctx = getApplication<Application>().applicationContext
        VpnStateManager.saveActiveTransport(ctx, protocol)
        val label = if (protocol == TunnelProtocol.CLOUDFLARE_MASQUE) "Cloudflare One (MASQUE)" else "WireGuard"
        _statusBanner.value = "Active VPN transport switched to $label"
        val currentStatus = vpnState.value.status
        if (currentStatus == TunnelStatus.CONNECTED || currentStatus == TunnelStatus.CONNECTING) {
            (VpnStateManager.activeVpnService as? com.xconflictionx.xprivacyguard.vpn.XPrivacyGuardService)?.onFirewallBypassChanged()
        }
    }

    fun updateCfOneDraft(updated: CloudflareOneConfig) {
        _cfOneDraft.value = updated
    }

    fun saveCfOneSettings() {
        val ctx = getApplication<Application>().applicationContext
        val draft = _cfOneDraft.value
        val existing = VpnStateManager.loadCloudflareOneConfig(ctx)
        val merged = draft.copy(
            deviceId = existing.deviceId,
            organization = existing.organization,
            ipv4Address = existing.ipv4Address,
            ipv6Address = existing.ipv6Address,
            assignedEndpoint = existing.assignedEndpoint,
            certIssuedAt = existing.certIssuedAt
        )
        VpnStateManager.saveCloudflareOneConfig(ctx, merged)
        _cfOneDraft.value = merged
        if (merged.hasValidToken) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { AetherNative.nativeTeamTokenSet(merged.accessToken.trim()) }
            }
        }
        _statusBanner.value = "Saved Cloudflare One MASQUE settings"
    }

    fun requestCloudflareOtpCode() {
        val draft = _cfOneDraft.value
        val team = CloudflareOneConfig.normalizeTeamName(draft.teamName)
        val email = draft.userEmail.trim()
        if (team.isEmpty()) {
            _statusBanner.value = "Enter a valid Cloudflare Zero Trust team name (e.g. my-org)."
            return
        }
        if (email.isEmpty() || !email.contains('@')) {
            _statusBanner.value = "Enter a valid email address to receive your one-time code."
            return
        }
        viewModelScope.launch {
            _cfAuthBusy.value = true
            try {
                val prevSession = _cfOtpSessionId.value
                if (prevSession > 0L) {
                    withContext(Dispatchers.IO) { runCatching { AetherNative.nativeTeamSessionFree(prevSession) } }
                    _cfOtpSessionId.value = 0L
                }
                val payload = JSONObject().apply {
                    put("team", team)
                    put("email", email)
                }
                val res = withContext(Dispatchers.IO) {
                    AetherNative.awaitJob(AetherNative.nativeTeamCodeRequest(payload.toString()), timeoutMs = 45_000L)
                }
                val session = res.optLong("session", 0L)
                check(session > 0L) { "Cloudflare did not return a verification session" }
                _cfOtpSessionId.value = session
                saveCfOneSettings()
                _statusBanner.value = "Verification code sent to $email. Enter the 6-digit code below."
            } catch (e: Exception) {
                _statusBanner.value = "Code request failed: ${e.message ?: "Unknown error"}"
            } finally {
                _cfAuthBusy.value = false
            }
        }
    }

    fun resendCloudflareOtpCode() {
        val session = _cfOtpSessionId.value
        if (session <= 0L) {
            requestCloudflareOtpCode()
            return
        }
        viewModelScope.launch {
            _cfAuthBusy.value = true
            try {
                withContext(Dispatchers.IO) {
                    AetherNative.awaitJob(AetherNative.nativeTeamCodeResend(session), timeoutMs = 45_000L)
                }
                _statusBanner.value = "Verification code resent to ${_cfOneDraft.value.userEmail.trim()}"
            } catch (e: Exception) {
                _statusBanner.value = "Resend failed: ${e.message ?: "Unknown error"}"
            } finally {
                _cfAuthBusy.value = false
            }
        }
    }

    fun submitCloudflareOtpCode(code: String) {
        val session = _cfOtpSessionId.value
        val cleaned = code.trim()
        if (session <= 0L) {
            _statusBanner.value = "Request a verification code first."
            return
        }
        if (cleaned.isEmpty()) {
            _statusBanner.value = "Enter the verification code from your email."
            return
        }
        viewModelScope.launch {
            _cfAuthBusy.value = true
            try {
                val res = withContext(Dispatchers.IO) {
                    AetherNative.awaitJob(AetherNative.nativeTeamCodeSubmit(session, cleaned), timeoutMs = 45_000L)
                }
                val signedIn = res.optBoolean("signed_in", false)
                val token = res.optString("token", "").trim()
                if (signedIn && token.isNotEmpty()) {
                    withContext(Dispatchers.IO) { runCatching { AetherNative.nativeTeamSessionFree(session) } }
                    _cfOtpSessionId.value = 0L
                    val ctx = getApplication<Application>().applicationContext
                    val updated = _cfOneDraft.value.copy(accessToken = token)
                    _cfOneDraft.value = updated
                    VpnStateManager.saveCloudflareOneConfig(ctx, updated)
                    _statusBanner.value = "Cloudflare Zero Trust sign-in verified! Tap Connect on Home to enroll & start MASQUE."
                } else {
                    _statusBanner.value = "Verification code was rejected. Check the code or tap Resend."
                }
            } catch (e: Exception) {
                _statusBanner.value = "Verification failed: ${e.message ?: "Unknown error"}"
            } finally {
                _cfAuthBusy.value = false
            }
        }
    }

    fun logoutCloudflareOne() {
        viewModelScope.launch {
            val session = _cfOtpSessionId.value
            if (session > 0L) {
                withContext(Dispatchers.IO) { runCatching { AetherNative.nativeTeamSessionFree(session) } }
                _cfOtpSessionId.value = 0L
            }
            val ctx = getApplication<Application>().applicationContext
            withContext(Dispatchers.IO) {
                VpnStateManager.clearCloudflareOneCredentials(ctx)
            }
            _cfOneDraft.value = VpnStateManager.loadCloudflareOneConfig(ctx)
            _statusBanner.value = "Cleared Cloudflare One credentials and device identity."
        }
    }

    override fun onCleared() {
        val session = _cfOtpSessionId.value
        if (session > 0L) {
            runCatching { AetherNative.nativeTeamSessionFree(session) }
            _cfOtpSessionId.value = 0L
        }
        super.onCleared()
    }

    fun updateZtDnsDraft(updated: ZeroTrustDnsConfig) {
        _ztDnsDraft.value = updated
    }

    fun saveZtDnsConfig() {
        val cfg = _ztDnsDraft.value
        val hasDomain = cfg.privateDomain.isNotBlank()
        val hasServer = cfg.privateDnsServer.isNotBlank()
        if (hasDomain != hasServer) {
            _statusBanner.value = "Enter both the private DNS domain and server IP, or clear both."
            return
        }
        if (hasDomain) {
            val domain = cfg.privateDomain.trim().trim('.')
            if (domain.isEmpty() || domain.any { it.isWhitespace() } || !domain.contains('.')) {
                _statusBanner.value = "Enter a valid private DNS domain, such as home.example.com."
                return
            }
            val server = cfg.privateDnsServer.trim()
            val isIpLiteral = server.contains(':') || server.matches(Regex("[0-9.]+"))
            if (!isIpLiteral || runCatching { java.net.InetAddress.getByName(server) }.isFailure) {
                _statusBanner.value = "Private DNS server must be an IPv4 or IPv6 address."
                return
            }
            val wg = WireGuardConfig.parse(_wgConfText.value).getOrNull()
            if (wg != null && !wg.routesAddress(server)) {
                _statusBanner.value = "WireGuard AllowedIPs does not route to the private DNS server. Add its network to AllowedIPs."
                return
            }
        }
        VpnStateManager.saveZeroTrustDnsConfig(getApplication(), cfg)
        _ztDnsDraft.value = VpnStateManager.state.value.zeroTrustDns
        (VpnStateManager.activeVpnService as? com.xconflictionx.xprivacyguard.vpn.XPrivacyGuardService)?.onFirewallBypassChanged()
        _statusBanner.value = "Saved DNS Settings: ${VpnStateManager.state.value.zeroTrustDns.activeModeSummary()}"
    }

    fun resetZtDnsToSystemDefault() {
        val defaultCfg = ZeroTrustDnsConfig()
        _ztDnsDraft.value = defaultCfg
        VpnStateManager.saveZeroTrustDnsConfig(getApplication(), defaultCfg)
        (VpnStateManager.activeVpnService as? com.xconflictionx.xprivacyguard.vpn.XPrivacyGuardService)?.onFirewallBypassChanged()
        _statusBanner.value = "Reset DNS settings to system/WireGuard defaults"
    }

    fun clearDnsCache() {
        com.xconflictionx.xprivacyguard.dns.ZeroTrustDnsResolver.clearCaches()
        _statusBanner.value = "DNS cache cleared"
    }

    fun importFilterFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        var importedCount = 0
        var importedRules = 0
        for (uri in uris) {
            filterRepo.importLocalUri(uri).onSuccess { src ->
                importedCount++
                importedRules += src.ruleCount
            }.onFailure { err ->
                _statusBanner.value = "Failed to import filter file: ${err.message}"
            }
        }
        if (importedCount > 0) {
            val updated = filterRepo.loadSources()
            _filterSources.value = updated
            VpnStateManager.updateFilterSources(updated)
            _statusBanner.value = "Imported $importedCount filters.txt file(s) ($importedRules rules)"
        }
    }

    fun addRemoteFilterUrls(rawUrlsText: String, customName: String? = null) {
        viewModelScope.launch {
            _isSyncingFilters.value = true
            val result = filterRepo.addRemoteUrls(rawUrlsText, customName)
            val updated = filterRepo.loadSources()
            _filterSources.value = updated
            VpnStateManager.updateFilterSources(updated)

            val addedCount = result.addedOrUpdated.size
            val totalRulesAdded = result.addedOrUpdated.sumOf { it.ruleCount }
            _statusBanner.value = when {
                addedCount > 0 && result.errors.isEmpty() ->
                    "Added $addedCount filter list(s) ($totalRulesAdded rules)"
                addedCount > 0 && result.errors.isNotEmpty() ->
                    "Added $addedCount list(s), ${result.errors.size} failed (${result.errors.first()})"
                result.errors.isNotEmpty() ->
                    "Could not load URL(s): ${result.errors.first()}"
                else -> null
            }
            _isSyncingFilters.value = false
        }
    }

    fun refreshAllRemoteFilters() {
        viewModelScope.launch {
            _isSyncingFilters.value = true
            filterRepo.refreshRemoteSources()
                .onSuccess { updated ->
                    _filterSources.value = updated
                    VpnStateManager.updateFilterSources(updated)
                    _statusBanner.value = "Synced ${updated.size} filter list(s) (${VpnStateManager.state.value.filterStats.totalRules} total active rules)"
                }
                .onFailure { err ->
                    _statusBanner.value = "Sync error: ${err.message}"
                }
            _isSyncingFilters.value = false
        }
    }

    fun addCustomRule(rule: String) {
        val updated = filterRepo.addCustomRule(rule)
        _filterSources.value = updated
        VpnStateManager.updateFilterSources(updated)
        _statusBanner.value = "Added custom rule(s)"
    }

    fun toggleFilterSource(id: String, enabled: Boolean) {
        val updated = filterRepo.toggleSource(id, enabled)
        _filterSources.value = updated
        VpnStateManager.updateFilterSources(updated)
    }

    fun deleteFilterSource(id: String) {
        val updated = filterRepo.deleteSource(id)
        _filterSources.value = updated
        VpnStateManager.updateFilterSources(updated)
        _statusBanner.value = "Removed filter list"
    }

    fun testFilterQuery(query: String): FilterDecision {
        return VpnStateManager.filterEngine.testInput(query)
    }

    fun updateWgConfDraft(newText: String) {
        _wgConfText.value = newText
    }

    fun saveWgConf(confText: String): Boolean {
        val res = VpnStateManager.saveWireGuardConfText(getApplication(), confText)
        return res.fold(
            onSuccess = { cfg ->
                _wgConfText.value = confText
                _statusBanner.value = "Saved WireGuard config (Endpoint: ${cfg.endpoint})"
                true
            },
            onFailure = { err ->
                _statusBanner.value = "Invalid WireGuard config: ${err.message}"
                false
            }
        )
    }

    fun importWgConfUri(uri: Uri) {
        runCatching {
            val text = getApplication<Application>().contentResolver
                .openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: throw IllegalArgumentException("Cannot read selected .conf file")
            saveWgConf(text)
        }.onFailure { err ->
            _statusBanner.value = "Failed to import .conf: ${err.message}"
        }
    }

    fun clearWgConf() {
        VpnStateManager.clearWireGuardConf(getApplication())
        _wgConfText.value = ""
        _statusBanner.value = "Cleared WireGuard configuration"
    }

    fun setFilterSettings(
        dnsFilteringEnabled: Boolean = vpnState.value.dnsFilteringEnabled,
        deepInspectionEnabled: Boolean = vpnState.value.deepInspectionEnabled,
        useNxDomain: Boolean = vpnState.value.useNxDomain,
        autoStartOnBoot: Boolean = vpnState.value.autoStartOnBoot
    ) {
        VpnStateManager.setFilterSettings(
            context = getApplication(),
            dnsFilteringEnabled = dnsFilteringEnabled,
            deepInspectionEnabled = deepInspectionEnabled,
            useNxDomain = useNxDomain,
            autoStartOnBoot = autoStartOnBoot
        )
    }

    fun clearTrafficLogs() {
        VpnStateManager.clearLogs()
    }

    fun refreshDeviceSecurityReport() {
        val ctx = getApplication<Application>().applicationContext
        val st = vpnState.value
        _deviceSecurityReport.value = DeviceSecurityChecker.collectReport(
            context = ctx,
            vpnConnected = st.status == TunnelStatus.CONNECTED,
            blockedCount = st.blockedCount,
            allowedCount = st.allowedCount
        )
    }

    fun saveXSecuritySettings(domain: String, apiKey: String) {
        val cleaned = DeviceSecurityChecker.cleanDomain(domain)
        if (!DeviceSecurityChecker.isValidDomain(cleaned)) {
            _statusBanner.value = "Enter a valid domain name (e.g. security.home.example.com), not an IP address."
            return
        }
        val cleanKey = apiKey.trim()
        prefs.edit()
            .putString("xsecurity_domain", cleaned)
            .putString("xsecurity_api_key", cleanKey)
            .apply()
        _xSecurityDomain.value = cleaned
        _xSecurityApiKey.value = cleanKey
        _statusBanner.value = "Saved xSecurity domain ($cleaned) and API key."
        if (cleanKey.isNotEmpty() && vpnState.value.status == TunnelStatus.CONNECTED) {
            syncDeviceSecurityToXServer(silent = true)
        }
    }

    fun syncDeviceSecurityToXServer(silent: Boolean = false) {
        if (_isSyncingSecurity.value) return
        if (vpnState.value.status != TunnelStatus.CONNECTED) {
            if (!silent) {
                _statusBanner.value = "Connect the tunnel to xServer before syncing."
            }
            return
        }
        viewModelScope.launch {
            _isSyncingSecurity.value = true
            refreshDeviceSecurityReport()
            val report = _deviceSecurityReport.value
            val domain = _xSecurityDomain.value
            val key = _xSecurityApiKey.value
            val result = withContext(Dispatchers.IO) {
                DeviceSecurityChecker.syncReportToXServer(domain, key, report)
            }
            _xSecuritySyncResult.value = result
            if (!silent || result.ok) {
                if (!silent) _statusBanner.value = result.message
            }
            _isSyncingSecurity.value = false
        }
    }

    fun exportSettingsBackup(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val appCtx = getApplication<Application>()
                    val st = vpnState.value
                    val backup = com.xconflictionx.xprivacyguard.config.AppSettingsBackupData(
                        wireGuardConfig = VpnStateManager.loadWireGuardConfText(appCtx),
                        tunneledPackages = VpnStateManager.loadBypassedPackages(appCtx),
                        zeroTrustDns = VpnStateManager.loadZeroTrustDnsConfig(appCtx),
                        filterSettings = com.xconflictionx.xprivacyguard.config.FilterToggleSettings(
                            dnsFilteringEnabled = st.dnsFilteringEnabled,
                            deepInspectionEnabled = st.deepInspectionEnabled,
                            useNxDomain = st.useNxDomain,
                            autoStartOnBoot = st.autoStartOnBoot
                        ),
                        filterSources = filterRepo.loadSources(),
                        xSecurity = com.xconflictionx.xprivacyguard.config.XSecurityBackupConfig(
                            domain = _xSecurityDomain.value,
                            apiKey = _xSecurityApiKey.value
                        )
                    )
                    val json = com.xconflictionx.xprivacyguard.config.AppSettingsBackup.toJson(backup)
                    appCtx.contentResolver.openOutputStream(uri)?.use { os ->
                        os.write(json.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
                    } ?: throw IllegalStateException("Unable to open destination file for writing.")
                    backup
                }
            }
            result.onSuccess { backup ->
                val appCount = backup.tunneledPackages?.size ?: 0
                val filterCount = backup.filterSources?.size ?: 0
                val wgStatus = if (!backup.wireGuardConfig.isNullOrBlank()) "WireGuard" else "no WireGuard"
                _statusBanner.value = "Backup exported ($wgStatus, $appCount apps, $filterCount filter lists)."
            }.onFailure { err ->
                _statusBanner.value = "Backup export failed: ${err.message}"
            }
        }
    }

    fun importSettingsBackup(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val appCtx = getApplication<Application>()
                    val rawText = appCtx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: throw IllegalStateException("Unable to read selected backup file.")
                    val parsed = com.xconflictionx.xprivacyguard.config.AppSettingsBackup.fromJson(rawText).getOrThrow()
                    val importedParts = mutableListOf<String>()

                    parsed.wireGuardConfig?.let { wgText ->
                        VpnStateManager.saveWireGuardConfText(appCtx, wgText).onSuccess {
                            _wgConfText.value = wgText
                            importedParts.add("WireGuard")
                        }
                    }

                    parsed.tunneledPackages?.let { pkgs ->
                        VpnStateManager.saveBypassedPackages(appCtx, pkgs, _installedApps.value)
                        importedParts.add("${pkgs.size} apps")
                    }

                    parsed.zeroTrustDns?.let { dnsCfg ->
                        VpnStateManager.saveZeroTrustDnsConfig(appCtx, dnsCfg)
                        _ztDnsDraft.value = VpnStateManager.state.value.zeroTrustDns
                        importedParts.add("DNS")
                    }

                    parsed.filterSettings?.let { fs ->
                        val cur = vpnState.value
                        VpnStateManager.setFilterSettings(
                            context = appCtx,
                            dnsFilteringEnabled = fs.dnsFilteringEnabled ?: cur.dnsFilteringEnabled,
                            deepInspectionEnabled = fs.deepInspectionEnabled ?: cur.deepInspectionEnabled,
                            useNxDomain = fs.useNxDomain ?: cur.useNxDomain,
                            autoStartOnBoot = fs.autoStartOnBoot ?: cur.autoStartOnBoot
                        )
                        importedParts.add("toggles")
                    }

                    parsed.filterSources?.let { sources ->
                        if (sources.isNotEmpty()) {
                            filterRepo.saveSources(sources)
                            val loaded = filterRepo.loadSources()
                            _filterSources.value = loaded
                            VpnStateManager.updateFilterSources(loaded)
                            importedParts.add("${loaded.size} filters")
                        }
                    }

                    parsed.xSecurity?.let { xs ->
                        val editor = prefs.edit()
                        var changed = false
                        xs.domain?.let { dom ->
                            if (DeviceSecurityChecker.isValidDomain(dom)) {
                                editor.putString("xsecurity_domain", dom)
                                _xSecurityDomain.value = dom
                                changed = true
                            }
                        }
                        xs.apiKey?.let { key ->
                            editor.putString("xsecurity_api_key", key)
                            _xSecurityApiKey.value = key
                            changed = true
                        }
                        if (changed) {
                            editor.apply()
                            importedParts.add("xSecurity")
                        }
                    }

                    val st = vpnState.value.status
                    if (st == TunnelStatus.CONNECTED || st == TunnelStatus.CONNECTING) {
                        (VpnStateManager.activeVpnService as? com.xconflictionx.xprivacyguard.vpn.XPrivacyGuardService)?.onFirewallBypassChanged()
                    }
                    importedParts
                }
            }
            result.onSuccess { parts ->
                _statusBanner.value = if (parts.isEmpty()) {
                    "Backup imported (no compatible changes found)."
                } else {
                    "Backup restored: ${parts.joinToString(", ")}."
                }
            }.onFailure { err ->
                _statusBanner.value = "Backup import failed: ${err.message}"
            }
        }
    }
}
