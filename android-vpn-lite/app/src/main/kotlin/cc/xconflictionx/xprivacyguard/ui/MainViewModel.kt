package cc.xconflictionx.xprivacyguard.ui

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.VpnService
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cc.xconflictionx.xprivacyguard.vpn.PrivacyVpnService
import cc.xconflictionx.xprivacyguard.vpn.VpnConfig
import cc.xconflictionx.xprivacyguard.vpn.VpnStatsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppRuleInfo(
    val appName: String,
    val packageName: String,
    val iconBitmap: ImageBitmap?,
    val isExcluded: Boolean
) {
    val isBlocked: Boolean get() = isExcluded
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val isVpnActive get() = PrivacyVpnService.isRunning

    val totalRequests get() = VpnStatsManager.totalRequests
    val blockedRequests get() = VpnStatsManager.blockedRequests
    val adsBlocked get() = VpnStatsManager.adsBlocked
    val trackersBlocked get() = VpnStatsManager.trackersBlocked
    val logEntries get() = VpnStatsManager.logEntries

    var pauseTimerMinutes by mutableStateOf<Int?>(null)
    private var pauseJob: Job? = null

    val installedApps = mutableStateListOf<AppRuleInfo>()
    var isAppsLoading by mutableStateOf(false)

    init {
        VpnConfig.init(application)
        loadInstalledApps()
    }

    fun loadInstalledApps() {
        viewModelScope.launch(Dispatchers.Main) {
            isAppsLoading = true
            val list = withContext(Dispatchers.IO) {
                val pm = getApplication<Application>().packageManager
                val packages = try {
                    pm.getInstalledApplications(PackageManager.GET_META_DATA)
                } catch (_: Exception) {
                    emptyList()
                }
                val excludedSet = VpnConfig.excludedPackages

                packages.mapNotNull { appInfo ->
                    val label = pm.getApplicationLabel(appInfo).toString()
                    val pkgName = appInfo.packageName
                    if (pkgName == getApplication<Application>().packageName) null
                    else if ((appInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
                             pm.getLaunchIntentForPackage(pkgName) != null ||
                             pkgName == "com.google.android.apps.bard" ||
                             pkgName == "com.google.android.googlequicksearchbox" ||
                             pkgName == "com.google.android.wearable.app") {
                        val iconBitmap = try {
                            val drawable = pm.getApplicationIcon(appInfo)
                            drawableToBitmap(drawable)?.asImageBitmap()
                        } catch (_: Exception) {
                            null
                        }
                        AppRuleInfo(
                            appName = label,
                            packageName = pkgName,
                            iconBitmap = iconBitmap,
                            isExcluded = excludedSet.contains(pkgName)
                        )
                    } else null
                }.sortedBy { it.appName.lowercase() }
            }
            installedApps.clear()
            installedApps.addAll(list)
            isAppsLoading = false
        }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap? {
        try {
            if (drawable is BitmapDrawable && drawable.bitmap != null) {
                return drawable.bitmap
            }
            val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 96
            val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 96
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            return bitmap
        } catch (_: Exception) {
            return null
        }
    }

    fun toggleAppExclusion(packageName: String, exclude: Boolean) {
        // When bypassing Gemini, also bypass Google App (which powers Gemini Assistant and Watch communication)
        val relatedPackages = when (packageName) {
            "com.google.android.apps.bard" -> setOf(packageName, "com.google.android.googlequicksearchbox")
            "com.google.android.googlequicksearchbox" -> setOf(packageName, "com.google.android.apps.bard")
            else -> setOf(packageName)
        }

        for (pkg in relatedPackages) {
            val index = installedApps.indexOfFirst { it.packageName == pkg }
            if (index != -1) {
                installedApps[index] = installedApps[index].copy(isExcluded = exclude)
            }
        }

        val baseExcluded = installedApps.filter { it.isExcluded }.map { it.packageName }.toSet()
        val currentExcluded = if (exclude) {
            baseExcluded + relatedPackages
        } else {
            baseExcluded - relatedPackages
        }

        VpnConfig.updateExcludedPackages(getApplication(), currentExcluded)

        if (isVpnActive) {
            VpnConfig.applyChanges(getApplication())
        }
    }

    fun toggleAppBlock(packageName: String, block: Boolean) {
        toggleAppExclusion(packageName, block)
    }

    private fun Context.findActivity(): ComponentActivity? = when (this) {
        is ComponentActivity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

    fun toggleVpn(context: Context, enable: Boolean) {
        pauseJob?.cancel()
        pauseTimerMinutes = null
        VpnConfig.isVpnEnabled = enable
        VpnConfig.save(getApplication())
        if (enable) {
            PrivacyVpnService.clearYieldState(context)
            val activity = context.findActivity()
            if (activity is MainActivity) {
                activity.prepareVpn()
            } else {
                val intent = VpnService.prepare(context)
                if (intent == null) {
                    startVpnService(context)
                }
            }
        } else {
            stopVpnService(context)
        }
    }

    fun pauseProtection(context: Context, minutes: Int) {
        pauseJob?.cancel()
        pauseTimerMinutes = minutes
        VpnConfig.isVpnEnabled = false
        VpnConfig.save(getApplication())
        stopVpnService(context)

        pauseJob = viewModelScope.launch {
            var remainingSeconds = minutes * 60
            while (remainingSeconds > 0) {
                delay(1000)
                remainingSeconds--
                pauseTimerMinutes = (remainingSeconds + 59) / 60
            }
            pauseTimerMinutes = null
            VpnConfig.isVpnEnabled = true
            VpnConfig.save(getApplication())
            startVpnService(context)
        }
    }

    fun startVpnService(context: Context) {
        val intent = Intent(context, PrivacyVpnService::class.java).apply {
            action = PrivacyVpnService.ACTION_START
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stopVpnService(context: Context) {
        val intent = Intent(context, PrivacyVpnService::class.java).apply {
            action = PrivacyVpnService.ACTION_STOP
        }
        context.startService(intent)
    }

    fun restartVpn(context: Context) {
        val intent = Intent(context, PrivacyVpnService::class.java).apply {
            action = PrivacyVpnService.ACTION_RESTART
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun updateSettings(secureDns: Boolean, adBlock: Boolean, trackerBlock: Boolean, malware: Boolean, killSwitch: Boolean) {
        VpnConfig.secureDnsEnabled = secureDns
        VpnConfig.adBlockingEnabled = adBlock
        VpnConfig.trackerBlockingEnabled = trackerBlock
        VpnConfig.malwareProtectionEnabled = malware
        VpnConfig.killSwitchEnabled = killSwitch
        VpnConfig.save(getApplication())
        VpnConfig.applyChanges(getApplication())
    }

    fun updateDnsSettings(
        endpoint: String,
        ipv4: String = VpnConfig.dnsIpv4,
        ipv6: String = VpnConfig.dnsIpv6
    ) {
        val clean = endpoint.removePrefix("https://").removePrefix("http://").substringBefore("/")
        VpnConfig.dnsEndpoint = if (clean.isEmpty() || clean.contains("dns-query")) "security.cloudflare-dns.com" else clean
        VpnConfig.dnsIpv4 = ipv4
        VpnConfig.dnsIpv6 = ipv6
        VpnConfig.save(getApplication())
        VpnConfig.applyChanges(getApplication())
    }

    fun updateDnsSettings(
        protocol: String,
        endpoint: String,
        ipv4: String = VpnConfig.dnsIpv4,
        ipv6: String = VpnConfig.dnsIpv6
    ) = updateDnsSettings(endpoint, ipv4, ipv6)

    fun clearLogs() {
        VpnStatsManager.clearLogs()
    }

    fun openVpnSettings(context: Context) {
        val intents = listOf(
            Intent(Settings.ACTION_VPN_SETTINGS),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (intent in intents) {
            try {
                if (context !is ComponentActivity) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return
            } catch (_: Exception) {
            }
        }
    }

    fun addFilterUrl(context: Context, url: String) {
        VpnConfig.addCustomFilterUrl(context, url)
    }

    fun removeFilterUrl(context: Context, url: String) {
        VpnConfig.removeCustomFilterUrl(context, url)
    }

    fun toggleCustomFilters(context: Context, enabled: Boolean) {
        VpnConfig.setCustomFiltersEnabled(context, enabled)
    }

    fun toggleCustomFiltersAutoUpdate(context: Context, enabled: Boolean) {
        VpnConfig.setCustomFiltersAutoUpdate(context, enabled)
    }

    fun refreshCustomFilters(context: Context) {
        cc.xconflictionx.xprivacyguard.vpn.FilterListManager.downloadAndRefresh(context, force = true)
    }
}
