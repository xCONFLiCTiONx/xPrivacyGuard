package cc.xconflictionx.xprivacyguard.vpn

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*

object VpnWatchdog {
    const val CHECK_INTERVAL_MS = 60_000L // check every 60 seconds
    const val OTHER_VPN_GRACE_PERIOD_MS = 60_000L // allow at least 60 seconds for other VPNs to fully connect

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var watchdogJob: Job? = null
    private var lastAutoUpdateCheck = 0L

    @Volatile
    private var networkCallbackRegistered = false
    private var vpnNetworkCallback: ConnectivityManager.NetworkCallback? = null

    fun start(context: Context) {
        val appContext = context.applicationContext
        ensureVpnNetworkCallback(appContext)
        if (watchdogJob?.isActive == true) return
        launchWatchdogLoop(appContext)
        Log.i("VpnWatchdog", "VPN Watchdog started (interval: ${CHECK_INTERVAL_MS / 1000}s).")
    }

    /**
     * Resets the 60-second watchdog countdown from zero so that a timer near the end of its cycle
     * never cuts off another VPN while it is still establishing its connection.
     */
    fun resetTimer(context: Context) {
        val appContext = context.applicationContext
        ensureVpnNetworkCallback(appContext)
        watchdogJob?.cancel()
        launchWatchdogLoop(appContext)
        Log.d("VpnWatchdog", "VPN Watchdog timer reset to full ${CHECK_INTERVAL_MS / 1000}s cycle.")
    }

    private fun launchWatchdogLoop(appContext: Context) {
        watchdogJob = scope.launch {
            while (isActive) {
                delay(CHECK_INTERVAL_MS)
                checkAndReconnectIfNeeded(appContext)
            }
        }
    }

    private fun ensureVpnNetworkCallback(appContext: Context) {
        if (networkCallbackRegistered) return
        synchronized(this) {
            if (networkCallbackRegistered) return
            try {
                val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return
                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build()
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        PrivacyVpnService.onVpnNetworkAvailableOrUpdated(appContext, network)
                    }

                    override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                        PrivacyVpnService.onVpnNetworkAvailableOrUpdated(appContext, network)
                    }

                    override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                        PrivacyVpnService.onVpnNetworkAvailableOrUpdated(appContext, network)
                    }

                    override fun onLost(network: Network) {
                        PrivacyVpnService.onVpnNetworkLost(appContext, network)
                    }
                }
                cm.registerNetworkCallback(request, callback)
                vpnNetworkCallback = callback
                networkCallbackRegistered = true
            } catch (e: Exception) {
                Log.w("VpnWatchdog", "Could not register VPN network callback", e)
            }
        }
    }

    internal fun checkAndReconnectIfNeeded(appContext: Context) {
        try {
            VpnConfig.init(appContext)
            VpnStatsManager.checkAndResetDailyStats()
            val isUserEnabled = VpnConfig.isVpnEnabled

            if (isUserEnabled) {
                val ourVpnConnected = PrivacyVpnService.isOurVpnConnected(appContext)
                if (!ourVpnConnected) {
                    if (PrivacyVpnService.shouldDeferAutoReconnect(appContext)) {
                        Log.i("VpnWatchdog", "Another VPN is connected/connecting or within 60s grace period; skipping reconnect.")
                    } else {
                        Log.w("VpnWatchdog", "Watchdog detected protection is enabled by user, xPrivacyGuard is not connected, and no other VPN has been active for 60s. Reconnecting...")
                        DiagnosticLogger.log("Watchdog: no other VPN active for 60s, reconnecting xPrivacyGuard")

                        val prepIntent = try {
                            VpnService.prepare(appContext)
                        } catch (e: Exception) {
                            Log.w("VpnWatchdog", "VpnService.prepare threw exception", e)
                            null
                        }

                        if (prepIntent == null) {
                            val serviceIntent = Intent(appContext, PrivacyVpnService::class.java).apply {
                                action = PrivacyVpnService.ACTION_AUTO_RECONNECT
                            }
                            try {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    appContext.startForegroundService(serviceIntent)
                                } else {
                                    appContext.startService(serviceIntent)
                                }
                            } catch (e: Exception) {
                                Log.e("VpnWatchdog", "Watchdog failed to start foreground VPN service, attempting startService fallback", e)
                                try {
                                    appContext.startService(serviceIntent)
                                } catch (e2: Exception) {
                                    Log.e("VpnWatchdog", "Fallback startService also failed", e2)
                                }
                            }
                        } else {
                            Log.w("VpnWatchdog", "VPN permission not granted yet; awaiting user approval in MainActivity.")
                        }
                    }
                }
            }

            // Periodic daily filter lists check (every 15 minutes)
            val now = System.currentTimeMillis()
            if (now - lastAutoUpdateCheck >= 15 * 60 * 1000L) {
                lastAutoUpdateCheck = now
                FilterListManager.checkAutoUpdate(appContext)
            }
        } catch (e: Exception) {
            Log.e("VpnWatchdog", "Error in watchdog check", e)
        }
    }

    fun stop() {
        watchdogJob?.cancel()
        watchdogJob = null
        Log.i("VpnWatchdog", "VPN Watchdog stopped.")
    }
}
