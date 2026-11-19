package cc.xconflictionx.xprivacyguard.vpn

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object VpnConfig {
    private var preferencesManager: PreferencesManager? = null

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private var pendingRestart: Runnable? = null

    /**
     * Call after ANY setting/filter/list change. Debounced (so rapid edits coalesce) and
     * rebuilds the running tunnel in place; also flushes Android's per-network DNS cache so
     * previously-resolved domains are re-checked against the new rules immediately.
     */
    fun applyChanges(context: Context) {
        if (!isVpnEnabled || !PrivacyVpnService.isRunning) return
        val app = context.applicationContext
        pendingRestart?.let { mainHandler.removeCallbacks(it) }
        val task = Runnable {
            try {
                val intent = Intent(app, PrivacyVpnService::class.java).apply {
                    action = PrivacyVpnService.ACTION_RESTART
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    app.startForegroundService(intent)
                } else {
                    app.startService(intent)
                }
            } catch (e: Exception) {
                android.util.Log.e("VpnConfig", "Failed to apply changes (restart)", e)
            }
        }
        pendingRestart = task
        mainHandler.postDelayed(task, 400)
    }

    var isVpnEnabled by mutableStateOf(false)
    var secureDnsEnabled by mutableStateOf(true)
    const val dnsProtocol: String = "DoT"
    var dnsEndpoint by mutableStateOf("security.cloudflare-dns.com")
    var dnsIpv4 by mutableStateOf("1.1.1.2, 1.0.0.2")
    var dnsIpv6 by mutableStateOf("2606:4700:4700::1112, 2606:4700:4700::1002")
    var adBlockingEnabled by mutableStateOf(true)
    var trackerBlockingEnabled by mutableStateOf(true)
    var malwareProtectionEnabled by mutableStateOf(true)
    var killSwitchEnabled by mutableStateOf(true)

    /**
     * DoT (DNS over TLS, Port 853) is active whenever Secure DNS is enabled by user.
     */
    val isSecureDnsActive: Boolean
        get() = secureDnsEnabled

    fun getDnsIpv4List(): List<String> =
        dnsIpv4.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    fun getDnsIpv6List(): List<String> =
        dnsIpv6.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    fun getAllDnsIps(): List<String> =
        getDnsIpv4List() + getDnsIpv6List()

    var customBlocklist by mutableStateOf(setOf<String>())
    var customAllowlist by mutableStateOf(setOf<String>())
    var excludedPackages by mutableStateOf(setOf<String>())

    var customFiltersEnabled by mutableStateOf(false)
    var customFiltersAutoUpdate by mutableStateOf(true)
    var customFilterUrls by mutableStateOf(setOf<String>())
    var customFiltersLastUpdated by mutableStateOf(0L)
    var customFiltersRuleCount by mutableStateOf(0)
    var customFiltersStatus by mutableStateOf("Disabled")
    var customFiltersIsDownloading by mutableStateOf(false)

    var blockedPackages: Set<String>
        get() = excludedPackages
        set(value) { excludedPackages = value }

    fun init(context: Context) {
        val manager = PreferencesManager(context)
        preferencesManager = manager
        if (manager.isFirstLaunch) {
            manager.isVpnEnabled = true
            manager.dnsProtocol = "DoT"
            manager.dnsEndpoint = "security.cloudflare-dns.com"
            manager.dnsIpv4 = "1.1.1.2, 1.0.0.2"
            manager.dnsIpv6 = "2606:4700:4700::1112, 2606:4700:4700::1002"
            manager.isFirstLaunch = false
        }
        isVpnEnabled = manager.isVpnEnabled
        secureDnsEnabled = manager.secureDnsEnabled

        // Always enforce DoT on Android and normalize any legacy DoH URLs
        var endpoint = manager.dnsEndpoint
        if (endpoint.startsWith("http://") || endpoint.startsWith("https://")) {
            endpoint = endpoint.removePrefix("https://").removePrefix("http://").substringBefore("/")
            if (endpoint.isEmpty() || endpoint.contains("dns-query")) {
                endpoint = "security.cloudflare-dns.com"
            }
            manager.dnsEndpoint = endpoint
        }
        manager.dnsProtocol = "DoT"
        dnsEndpoint = endpoint
        dnsIpv4 = manager.dnsIpv4
        dnsIpv6 = manager.dnsIpv6

        adBlockingEnabled = manager.adBlockingEnabled
        trackerBlockingEnabled = manager.trackerBlockingEnabled
        malwareProtectionEnabled = manager.malwareProtectionEnabled
        killSwitchEnabled = manager.killSwitchEnabled
        customBlocklist = manager.getCustomBlocklist()
        customAllowlist = manager.getCustomAllowlist()
        excludedPackages = manager.getExcludedPackages()
        val defaultFilterUrl = "https://gitlab.com/xCONFLiCTiONx/privacyfilters/-/raw/main/xCONFLiCTiONx/filters.txt?ref_type=heads"
        var storedUrls = manager.getCustomFilterUrls()
        if (storedUrls.isEmpty()) {
            storedUrls = setOf(defaultFilterUrl)
            manager.setCustomFilterUrls(storedUrls)
            manager.customFiltersEnabled = true
        }
        customFilterUrls = storedUrls
        customFiltersEnabled = manager.customFiltersEnabled
        customFiltersAutoUpdate = manager.customFiltersAutoUpdate
        customFiltersLastUpdated = manager.customFiltersLastUpdated

        FilterListManager.init(context)
        VpnStatsManager.init(context)
    }

    fun save(context: Context) {
        val manager = preferencesManager ?: PreferencesManager(context).also { preferencesManager = it }
        manager.isVpnEnabled = isVpnEnabled
        manager.secureDnsEnabled = secureDnsEnabled
        manager.dnsProtocol = dnsProtocol
        manager.dnsEndpoint = dnsEndpoint
        manager.dnsIpv4 = dnsIpv4
        manager.dnsIpv6 = dnsIpv6
        manager.adBlockingEnabled = adBlockingEnabled
        manager.trackerBlockingEnabled = trackerBlockingEnabled
        manager.malwareProtectionEnabled = malwareProtectionEnabled
        manager.killSwitchEnabled = killSwitchEnabled
        manager.setCustomBlocklist(customBlocklist)
        manager.setCustomAllowlist(customAllowlist)
        manager.setExcludedPackages(excludedPackages)
        manager.setCustomFilterUrls(customFilterUrls)
        manager.customFiltersEnabled = if (customFilterUrls.isNotEmpty()) customFiltersEnabled else false
        manager.customFiltersAutoUpdate = customFiltersAutoUpdate
        manager.customFiltersLastUpdated = customFiltersLastUpdated
    }

    fun addCustomBlock(context: Context, domain: String) {
        val clean = domain.lowercase().trim().trimEnd('.')
        if (clean.isNotEmpty()) {
            customBlocklist = customBlocklist + clean
            save(context)
            applyChanges(context)
        }
    }

    fun removeCustomBlock(context: Context, domain: String) {
        customBlocklist = customBlocklist - domain.lowercase().trim().trimEnd('.')
        save(context)
        applyChanges(context)
    }

    fun addCustomAllow(context: Context, domain: String) {
        val clean = domain.lowercase().trim().trimEnd('.')
        if (clean.isNotEmpty()) {
            customAllowlist = customAllowlist + clean
            save(context)
            applyChanges(context)
        }
    }

    fun removeCustomAllow(context: Context, domain: String) {
        customAllowlist = customAllowlist - domain.lowercase().trim().trimEnd('.')
        save(context)
        applyChanges(context)
    }

    fun updateExcludedPackages(context: Context, packages: Set<String>) {
        excludedPackages = packages
        save(context)
    }

    fun updateBlockedPackages(context: Context, packages: Set<String>) {
        updateExcludedPackages(context, packages)
    }

    fun addCustomFilterUrl(context: Context, url: String) {
        val clean = url.trim()
        if (clean.isNotEmpty()) {
            val updated = customFilterUrls + clean
            customFilterUrls = updated
            if (updated.size == 1) {
                customFiltersEnabled = true
            }
            save(context)
            FilterListManager.downloadAndRefresh(context, force = true)
        }
    }

    fun removeCustomFilterUrl(context: Context, url: String) {
        val clean = url.trim()
        val updated = customFilterUrls - clean
        customFilterUrls = updated
        if (updated.isEmpty()) {
            customFiltersEnabled = false
        }
        save(context)
        FilterListManager.downloadAndRefresh(context, force = true)
        applyChanges(context)
    }

    fun setCustomFiltersEnabled(context: Context, enabled: Boolean) {
        if (enabled && customFilterUrls.isEmpty()) {
            customFiltersEnabled = false
            save(context)
            return
        }
        customFiltersEnabled = enabled
        save(context)
        applyChanges(context)
        if (enabled && FilterListManager.blockedDomains.isEmpty()) {
            FilterListManager.downloadAndRefresh(context, force = true)
        }
    }

    fun setCustomFiltersAutoUpdate(context: Context, enabled: Boolean) {
        customFiltersAutoUpdate = enabled
        save(context)
        if (enabled) {
            DailyFilterUpdateReceiver.schedule(context)
            FilterListManager.checkAutoUpdate(context)
        } else {
            DailyFilterUpdateReceiver.cancel(context)
        }
    }
}
