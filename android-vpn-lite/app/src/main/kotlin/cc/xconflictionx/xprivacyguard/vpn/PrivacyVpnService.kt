package cc.xconflictionx.xprivacyguard.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import cc.xconflictionx.xprivacyguard.ui.MainActivity
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.NetworkInterface
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PrivacyVpnService : VpnService() {

    private var dnsProxyServer: DnsProxyServer? = null
    private val blocklistFilter = BlocklistFilter()
    private var localTunParcel: ParcelFileDescriptor? = null

    private val tunScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var tunJob: Job? = null

    companion object {
        var isRunning by mutableStateOf(false)
            private set
        const val ACTION_START = "cc.xconflictionx.xprivacyguard.START"
        const val ACTION_STOP = "cc.xconflictionx.xprivacyguard.STOP"
        const val ACTION_RESTART = "cc.xconflictionx.xprivacyguard.RESTART"
        const val ACTION_AUTO_RECONNECT = "cc.xconflictionx.xprivacyguard.AUTO_RECONNECT"

        const val VPN_IPV4_ADDRESS = "10.120.0.1"
        const val VPN_IPV4_DNS = "10.120.0.2"
        const val VPN_IPV6_ADDRESS = "fd00:120::1"
        const val VPN_IPV6_DNS = "fd00:120::2"

        const val OTHER_VPN_GRACE_PERIOD_MS = 60_000L
        private const val PREFS_VPN_HANDOFF = "vpn_handoff_state"
        private const val KEY_LAST_PREEMPTED_AT = "last_preempted_at"
        private const val KEY_LAST_OTHER_VPN_SEEN_AT = "last_other_vpn_seen_at"

        private const val CHANNEL_ID = "PrivacyVpnChannel"
        private const val NOTIF_ID = 1001
        private var isVpnRunning = false

        @Volatile
        private var activeTunFd: ParcelFileDescriptor? = null

        @Volatile
        var activeProxy: DnsProxyServer? = null

        /** Number of DNS queries actually intercepted from the TUN (proof that traffic reaches the firewall). */
        @Volatile
        var tunDnsPacketsSeen: Long = 0L

        @Volatile
        internal var lastPreemptedAtMs: Long = 0L

        @Volatile
        internal var lastOtherVpnSeenAtMs: Long = 0L

        @Volatile
        internal var uninterruptedNoVpnSinceMs: Long = 0L

        @Volatile
        internal var waitingForOtherVpnToConnect: Boolean = false

        @Volatile
        internal var hasConnectedOnceInSession: Boolean = false

        private val ourVpnNetworks: MutableSet<android.net.Network> =
            Collections.synchronizedSet(mutableSetOf())
        private val otherVpnNetworks: MutableSet<android.net.Network> =
            Collections.synchronizedSet(mutableSetOf())

        private fun loadPersistedHandoffState(context: Context?) {
            if (context == null) return
            if (lastPreemptedAtMs != 0L || lastOtherVpnSeenAtMs != 0L) return
            try {
                val sp = context.applicationContext.getSharedPreferences(PREFS_VPN_HANDOFF, Context.MODE_PRIVATE)
                val savedPreempted = sp.getLong(KEY_LAST_PREEMPTED_AT, 0L)
                val savedOtherSeen = sp.getLong(KEY_LAST_OTHER_VPN_SEEN_AT, 0L)
                if (lastPreemptedAtMs == 0L) lastPreemptedAtMs = savedPreempted
                if (lastOtherVpnSeenAtMs == 0L) lastOtherVpnSeenAtMs = savedOtherSeen
            } catch (_: Exception) {
            }
        }

        private fun savePersistedHandoffState(context: Context?) {
            if (context == null) return
            try {
                val sp = context.applicationContext.getSharedPreferences(PREFS_VPN_HANDOFF, Context.MODE_PRIVATE)
                sp.edit()
                    .putLong(KEY_LAST_PREEMPTED_AT, lastPreemptedAtMs)
                    .putLong(KEY_LAST_OTHER_VPN_SEEN_AT, lastOtherVpnSeenAtMs)
                    .apply()
            } catch (_: Exception) {
            }
        }

        fun onPreemptedByAnotherVpn(context: Context? = null, nowMs: Long = System.currentTimeMillis()) {
            lastPreemptedAtMs = nowMs
            lastOtherVpnSeenAtMs = nowMs
            uninterruptedNoVpnSinceMs = 0L
            waitingForOtherVpnToConnect = true
            ourVpnNetworks.clear()
            savePersistedHandoffState(context)
            if (context != null) {
                VpnWatchdog.resetTimer(context.applicationContext)
            }
        }

        fun markOtherVpnDetected(context: Context? = null, nowMs: Long = System.currentTimeMillis()) {
            lastOtherVpnSeenAtMs = nowMs
            uninterruptedNoVpnSinceMs = 0L
            waitingForOtherVpnToConnect = false
            savePersistedHandoffState(context)
        }

        fun clearYieldState(context: Context? = null) {
            lastPreemptedAtMs = 0L
            lastOtherVpnSeenAtMs = 0L
            uninterruptedNoVpnSinceMs = 0L
            waitingForOtherVpnToConnect = false
            otherVpnNetworks.clear()
            savePersistedHandoffState(context)
        }

        internal fun onVpnNetworkAvailableOrUpdated(context: Context, network: android.net.Network) {
            val cm = try {
                context.getSystemService(ConnectivityManager::class.java)
            } catch (_: Exception) {
                null
            }
            val (ourInterfaces, _) = collectTunnelInterfaces()
            val lp = try { cm?.getLinkProperties(network) } catch (_: Exception) { null }
            val matchesOurs = lp != null && (
                lp.linkAddresses.any { isOurTunnelAddress(it.address.hostAddress) } ||
                lp.dnsServers.any { isOurTunnelAddress(it.hostAddress) } ||
                (lp.interfaceName != null && ourInterfaces.contains(lp.interfaceName))
            )
            if (matchesOurs) {
                ourVpnNetworks.add(network)
                otherVpnNetworks.remove(network)
                return
            }

            val hasNonMatchingDetails = lp != null && (
                lp.linkAddresses.isNotEmpty() ||
                lp.dnsServers.isNotEmpty() ||
                !lp.interfaceName.isNullOrEmpty()
            )
            if (hasNonMatchingDetails || !isRunning || !isVpnRunning) {
                otherVpnNetworks.add(network)
                markOtherVpnDetected(context)
                VpnWatchdog.resetTimer(context.applicationContext)
            }
        }

        internal fun onVpnNetworkLost(context: Context, network: android.net.Network) {
            if (ourVpnNetworks.remove(network)) {
                return
            }
            val wasOther = otherVpnNetworks.remove(network)
            if (wasOther || !isRunning) {
                val now = System.currentTimeMillis()
                lastOtherVpnSeenAtMs = now
                if (otherVpnNetworks.isEmpty() && !waitingForOtherVpnToConnect) {
                    uninterruptedNoVpnSinceMs = now
                }
                savePersistedHandoffState(context)
                VpnWatchdog.resetTimer(context.applicationContext)
            }
        }

        /**
         * Evaluates whether automatic reconnect should be deferred based on timestamps and
         * continuous quiet-window tracking so a timer near the end never cuts off a connecting VPN.
         */
        internal fun shouldDeferByTiming(
            otherVpnCurrentlyConnected: Boolean,
            nowMs: Long = System.currentTimeMillis(),
            recordObservation: Boolean = true
        ): Boolean {
            if (otherVpnCurrentlyConnected) {
                lastOtherVpnSeenAtMs = nowMs
                uninterruptedNoVpnSinceMs = 0L
                waitingForOtherVpnToConnect = false
                return true
            }

            if (lastPreemptedAtMs > 0L && (nowMs - lastPreemptedAtMs) in 0 until OTHER_VPN_GRACE_PERIOD_MS) {
                return true
            }
            if (lastOtherVpnSeenAtMs > 0L && (nowMs - lastOtherVpnSeenAtMs) in 0 until OTHER_VPN_GRACE_PERIOD_MS) {
                return true
            }

            val needsQuietWindow = lastPreemptedAtMs > 0L || lastOtherVpnSeenAtMs > 0L || hasConnectedOnceInSession || waitingForOtherVpnToConnect
            if (needsQuietWindow) {
                if (uninterruptedNoVpnSinceMs == 0L) {
                    if (recordObservation) {
                        uninterruptedNoVpnSinceMs = nowMs
                        waitingForOtherVpnToConnect = false
                    }
                    return true
                }
                if ((nowMs - uninterruptedNoVpnSinceMs) in 0 until OTHER_VPN_GRACE_PERIOD_MS) {
                    return true
                }
            }

            return false
        }

        fun shouldDeferAutoReconnect(
            context: Context,
            nowMs: Long = System.currentTimeMillis(),
            recordObservation: Boolean = true
        ): Boolean {
            loadPersistedHandoffState(context)
            val otherConnected = isAnotherVpnConnected(context)
            val defer = shouldDeferByTiming(otherConnected, nowMs, recordObservation)
            if (recordObservation) {
                savePersistedHandoffState(context)
            }
            return defer
        }

        internal fun isOurTunnelAddress(hostAddress: String?): Boolean {
            val clean = hostAddress?.substringBefore('%')?.lowercase()?.trim() ?: return false
            if (clean.isEmpty()) return false
            return clean == VPN_IPV4_ADDRESS ||
                    clean == VPN_IPV4_DNS ||
                    clean == VPN_IPV6_ADDRESS ||
                    clean == VPN_IPV6_DNS ||
                    clean == "fd00:120:0:0:0:0:0:1" ||
                    clean == "fd00:120:0:0:0:0:0:2" ||
                    clean.startsWith("fd00:120:")
        }

        internal fun isVpnInterfaceName(name: String?): Boolean {
            val lower = name?.lowercase()?.trim() ?: return false
            if (lower.startsWith("tunl")) return false
            return lower.startsWith("tun") ||
                    lower.startsWith("wg") ||
                    lower.startsWith("ppp") ||
                    lower.startsWith("tap") ||
                    lower.startsWith("utun") ||
                    lower.startsWith("ipsec") ||
                    lower.startsWith("pptp") ||
                    lower.startsWith("l2tp")
        }

        private fun collectTunnelInterfaces(): Pair<Set<String>, Set<String>> {
            val ourInterfaces = mutableSetOf<String>()
            val otherVpnInterfaces = mutableSetOf<String>()
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces() ?: return Pair(ourInterfaces, otherVpnInterfaces)
                for (iface in Collections.list(interfaces)) {
                    if (!iface.isUp || iface.isLoopback) continue
                    val addrs = Collections.list(iface.inetAddresses)
                        .filter { !it.isLoopbackAddress && !it.isAnyLocalAddress }
                    if (addrs.any { isOurTunnelAddress(it.hostAddress) }) {
                        ourInterfaces.add(iface.name)
                    } else if (isVpnInterfaceName(iface.name) && (addrs.isNotEmpty() || (!isRunning && iface.isPointToPoint))) {
                        otherVpnInterfaces.add(iface.name)
                    }
                }
            } catch (_: Exception) {
            }
            return Pair(ourInterfaces, otherVpnInterfaces)
        }

        @Suppress("DEPRECATION")
        private fun getCandidateNetworks(cm: ConnectivityManager): Set<android.net.Network> =
            cm.allNetworks.toSet() + listOfNotNull(cm.activeNetwork)

        /**
         * Returns true if xPrivacyGuard is currently running AND its TUN interface is the active
         * connected VPN on the device.
         */
        fun isOurVpnConnected(context: Context): Boolean {
            if (!isRunning || !isVpnRunning) return false
            val tun = activeTunFd ?: return false
            val fdValid = try {
                tun.fileDescriptor?.valid() == true
            } catch (_: Exception) {
                false
            }
            if (!fdValid) return false

            val (ourInterfaces, otherVpnInterfaces) = collectTunnelInterfaces()
            if (otherVpnInterfaces.isNotEmpty()) return false

            val cm = try {
                context.getSystemService(ConnectivityManager::class.java)
            } catch (_: Exception) {
                null
            }
            if (cm != null) {
                try {
                    val networks = getCandidateNetworks(cm)
                    var sawOurVpnNetwork = false
                    for (net in networks) {
                        val caps = cm.getNetworkCapabilities(net) ?: continue
                        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                        val lp = cm.getLinkProperties(net)
                        if (lp != null) {
                            val matchesOurs =
                                lp.linkAddresses.any { isOurTunnelAddress(it.address.hostAddress) } ||
                                lp.dnsServers.any { isOurTunnelAddress(it.hostAddress) } ||
                                (lp.interfaceName != null && ourInterfaces.contains(lp.interfaceName))
                            if (matchesOurs) {
                                sawOurVpnNetwork = true
                            } else if (lp.linkAddresses.isNotEmpty() || lp.dnsServers.isNotEmpty()) {
                                // Another VPN owns a TRANSPORT_VPN network
                                return false
                            }
                        }
                    }
                    if (sawOurVpnNetwork) return true
                } catch (_: Exception) {
                }
            }

            return ourInterfaces.isNotEmpty()
        }

        /**
         * Returns true if another VPN app/profile (not xPrivacyGuard) is currently connected
         * or in the process of connecting.
         */
        fun isAnotherVpnConnected(context: Context): Boolean {
            if (otherVpnNetworks.isNotEmpty()) {
                markOtherVpnDetected(context)
                return true
            }

            val (ourInterfaces, otherVpnInterfaces) = collectTunnelInterfaces()
            if (otherVpnInterfaces.isNotEmpty()) {
                markOtherVpnDetected(context)
                return true
            }

            val cm = try {
                context.getSystemService(ConnectivityManager::class.java)
            } catch (_: Exception) {
                null
            } ?: return false

            try {
                @Suppress("DEPRECATION")
                val legacyVpnInfo = cm.getNetworkInfo(ConnectivityManager.TYPE_VPN)
                @Suppress("DEPRECATION")
                if (legacyVpnInfo != null && legacyVpnInfo.isConnectedOrConnecting && !isRunning && ourInterfaces.isEmpty()) {
                    markOtherVpnDetected(context)
                    return true
                }
            } catch (_: Exception) {
            }

            try {
                val networks = getCandidateNetworks(cm)
                for (net in networks) {
                    if (ourVpnNetworks.contains(net)) continue
                    val caps = cm.getNetworkCapabilities(net) ?: continue
                    if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue

                    // If our VPN is not even running and has no active interface, any TRANSPORT_VPN belongs to another VPN
                    if (!isRunning && ourInterfaces.isEmpty()) {
                        otherVpnNetworks.add(net)
                        markOtherVpnDetected(context)
                        return true
                    }

                    val lp = cm.getLinkProperties(net)
                    if (lp != null) {
                        val matchesOurs =
                            lp.linkAddresses.any { isOurTunnelAddress(it.address.hostAddress) } ||
                            lp.dnsServers.any { isOurTunnelAddress(it.hostAddress) } ||
                            (lp.interfaceName != null && ourInterfaces.contains(lp.interfaceName))
                        if (matchesOurs) {
                            ourVpnNetworks.add(net)
                            continue
                        }

                        otherVpnNetworks.add(net)
                        markOtherVpnDetected(context)
                        return true
                    } else {
                        otherVpnNetworks.add(net)
                        markOtherVpnDetected(context)
                        return true
                    }
                }
            } catch (_: Exception) {
            }

            return false
        }

        /**
         * Pushes real queries through the live filtering engine and reports whether
         * they were blocked. The app itself is excluded from the VPN, so this calls the
         * engine directly instead of the system resolver.
         */
        suspend fun selfTest(): String {
            val proxy = activeProxy ?: return "FAILED: protection is not running"
            val sb = StringBuilder()
            fun rcode(resp: ByteArray?): String = when {
                resp == null || resp.size < 4 -> "no response"
                (resp[3].toInt() and 0x0F) == 3 -> "BLOCKED (NXDOMAIN)"
                (resp[3].toInt() and 0x0F) == 2 -> "SERVFAIL"
                else -> "allowed/resolved"
            }
            val tests = mutableListOf<Pair<String, String>>(
                "doubleclick.net" to "built-in ad list",
                "google-analytics.com" to "built-in tracker list",
                "google.com" to "should resolve"
            )
            if (VpnConfig.customFiltersEnabled) {
                FilterListManager.blockedDomains.firstOrNull()?.let { tests.add(it to "filters.txt rule") }
            }
            for ((domain, label) in tests) {
                val resp = proxy.resolveDnsQuery(proxy.buildTestQuery(domain), "Self-test")
                sb.append("$domain ($label): ${rcode(resp)}\n")
            }
            sb.append("Custom filter rules loaded: ${FilterListManager.blockedDomains.size}\n")
            sb.append("DNS packets intercepted from TUN so far: $tunDnsPacketsSeen")
            return sb.toString()
        }
    }

    /** Resolves which installed app sent a DNS packet (Android 10+), or "System". */
    private fun lookupAppName(protocol: Int, srcIp: java.net.InetAddress, srcPort: Int, dstIp: java.net.InetAddress, dstPort: Int): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "System"
        return try {
            val cm = getSystemService(android.net.ConnectivityManager::class.java)
            val uid = cm.getConnectionOwnerUid(
                protocol,
                java.net.InetSocketAddress(srcIp, srcPort),
                java.net.InetSocketAddress(dstIp, dstPort)
            )
            if (uid < 0) return "System"
            val pkgs = packageManager.getPackagesForUid(uid) ?: return "UID $uid"
            val pkg = pkgs.firstOrNull() ?: return "UID $uid"
            try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
            } catch (_: Exception) {
                pkg
            }
        } catch (_: Exception) {
            "System"
        }
    }

    override fun onCreate() {
        super.onCreate()
        VpnConfig.init(this)
        VpnWatchdog.start(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        VpnConfig.init(this)
        when (intent?.action) {
            ACTION_STOP -> {
                VpnConfig.isVpnEnabled = false
                VpnConfig.save(this)
                stopVpn()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                promoteToForeground()
                if (VpnConfig.isVpnEnabled) {
                    if (shouldDeferAutoReconnect(this, recordObservation = false)) {
                        Log.i("PrivacyVpnService", "Restart deferred: another VPN is currently connected or within 60s grace period.")
                        releaseTunnel()
                        promoteToForeground()
                        return START_STICKY
                    }
                    if (dnsProxyServer == null) {
                        dnsProxyServer = DnsProxyServer(this, blocklistFilter) { appName, domain, reason, blocked ->
                            VpnStatsManager.recordQuery(appName, domain, reason, blocked)
                        }
                        activeProxy = dnsProxyServer
                        dnsProxyServer?.start(1053)
                    }
                    DiagnosticLogger.log("Applying changes: updating Local Privacy Firewall (bypass: ${VpnConfig.excludedPackages.size} app(s))")
                    setupLocalFirewallTun()
                    return START_STICKY
                }
                stopVpn()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                clearYieldState(this)
                VpnConfig.isVpnEnabled = true
                VpnConfig.save(this)
                promoteToForeground()
                startVpn()
                return START_STICKY
            }
            ACTION_AUTO_RECONNECT -> {
                if (VpnConfig.isVpnEnabled) {
                    promoteToForeground()
                    if (isOurVpnConnected(this)) {
                        return START_STICKY
                    }
                    if (shouldDeferAutoReconnect(this)) {
                        Log.i("PrivacyVpnService", "Auto-reconnect deferred: another VPN is connected or within 60s grace period.")
                        releaseTunnel()
                        promoteToForeground()
                        return START_STICKY
                    }
                    startVpn()
                    return START_STICKY
                } else {
                    stopVpn()
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
            else -> {
                if (VpnConfig.isVpnEnabled) {
                    promoteToForeground()
                    if (!isOurVpnConnected(this) && !shouldDeferAutoReconnect(this)) {
                        startVpn()
                    }
                    return START_STICKY
                } else {
                    stopVpn()
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }
    }

    override fun onRevoke() {
        Log.w("PrivacyVpnService", "VPN tunnel revoked by system (another VPN connecting). Entering 60s standby mode...")
        DiagnosticLogger.log("VPN preempted by another VPN connection; yielding for at least 60s so other VPN can fully connect")
        releaseTunnel()
        onPreemptedByAnotherVpn(applicationContext)
        if (VpnConfig.isVpnEnabled) {
            promoteToForeground()
            VpnWatchdog.start(applicationContext)
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun promoteToForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIF_ID, createNotification())
        }
    }

    private fun startVpn() {
        if (isVpnRunning && isRunning && isOurVpnConnected(this)) return
        try {
            promoteToForeground()

            if (VpnConfig.customFiltersEnabled && FilterListManager.blockedDomains.isEmpty()) {
                FilterListManager.downloadAndRefresh(this, force = true)
            }

            if (dnsProxyServer == null) {
                dnsProxyServer = DnsProxyServer(this, blocklistFilter) { appName, domain, reason, blocked ->
                    VpnStatsManager.recordQuery(appName, domain, reason, blocked)
                }
            }
            activeProxy = dnsProxyServer
            dnsProxyServer?.start(1053)

            Log.i("PrivacyVpnService", "Starting Local Privacy Firewall & Encrypted DNS Mode...")
            DiagnosticLogger.log("Starting Local Privacy Firewall & Encrypted DNS Mode")
            setupLocalFirewallTun()
        } catch (e: Exception) {
            Log.e("PrivacyVpnService", "Failed to start Privacy VPN Service", e)
            if (!VpnConfig.isVpnEnabled) {
                stopVpn()
            } else {
                releaseTunnel()
                promoteToForeground()
            }
        }
    }

    private fun setupLocalFirewallTun() {
        try {
            // Re-prepare VPN ownership in Android's Vpn service if another VPN app was previously used
            try {
                VpnService.prepare(this)
            } catch (e: Exception) {
                Log.w("PrivacyVpnService", "VpnService.prepare() check before establish threw exception", e)
            }

            val oldParcel = localTunParcel
            val builder = Builder()
                .setSession("xPrivacyGuard")
                // NOTE: the DNS IP must NOT equal the TUN's own address, or the kernel
                // treats it as local and packets never reach the TUN fd.
                .addAddress(VPN_IPV4_ADDRESS, 24)
                .addDnsServer(VPN_IPV4_DNS)
                .addRoute(VPN_IPV4_DNS, 32)
                .addAddress(VPN_IPV6_ADDRESS, 64)
                .addDnsServer(VPN_IPV6_DNS)
                .addRoute(VPN_IPV6_DNS, 128)

            val packagesToExclude = VpnConfig.excludedPackages + packageName
            val applied = mutableListOf<String>()
            val failed = mutableListOf<String>()
            for (pkg in packagesToExclude) {
                try {
                    builder.addDisallowedApplication(pkg)
                    applied.add(pkg)
                } catch (e: Exception) {
                    failed.add(pkg)
                    Log.e("PrivacyVpnService", "Could not disallow package $pkg", e)
                }
            }
            DiagnosticLogger.log("Bypassing (not tunneled): ${applied.joinToString().ifEmpty { "none" }}" +
                    if (failed.isNotEmpty()) " | FAILED: ${failed.joinToString()}" else "")

            // Establish the NEW tunnel first; Android atomically replaces the old one.
            val newParcel = builder.establish()
            if (newParcel != null) {
                localTunParcel = newParcel
                activeTunFd = newParcel
                isVpnRunning = true
                isRunning = true
                hasConnectedOnceInSession = true
                waitingForOtherVpnToConnect = false
                uninterruptedNoVpnSinceMs = 0L
                promoteToForeground()
                Log.i("PrivacyVpnService", "Local Privacy Firewall TUN established successfully. Excluded ${applied.size} apps.")
                startTunDnsLoop(newParcel)
                if (oldParcel != null && oldParcel !== newParcel) {
                    try { oldParcel.close() } catch (_: Exception) {}
                }
            } else {
                val oldValid = try { oldParcel?.fileDescriptor?.valid() == true } catch (_: Exception) { false }
                if (!oldValid) {
                    localTunParcel = null
                    activeTunFd = null
                    isVpnRunning = false
                    isRunning = false
                    onPreemptedByAnotherVpn(applicationContext)
                    promoteToForeground()
                }
                Log.e("PrivacyVpnService", "establish() returned null (VPN permission revoked or another VPN active)")
                DiagnosticLogger.log("ERROR: VPN establish() returned null - yielding for 60s before retry")
            }
        } catch (e: Exception) {
            Log.e("PrivacyVpnService", "Error establishing Local Firewall TUN", e)
            DiagnosticLogger.log("Error establishing tunnel: ${e.message}")
            val oldValid = try { localTunParcel?.fileDescriptor?.valid() == true } catch (_: Exception) { false }
            if (!oldValid) {
                localTunParcel = null
                activeTunFd = null
                isVpnRunning = false
                isRunning = false
                onPreemptedByAnotherVpn(applicationContext)
                promoteToForeground()
            }
        }
    }

    private fun startTunDnsLoop(tunFd: ParcelFileDescriptor) {
        tunJob?.cancel()
        tunJob = tunScope.launch {
            val fileDescriptor = tunFd.fileDescriptor
            val inputStream = FileInputStream(fileDescriptor)
            val outputStream = FileOutputStream(fileDescriptor)
            val buffer = ByteArray(4096)
            val pollFd = android.system.StructPollfd().apply {
                fd = fileDescriptor
                events = android.system.OsConstants.POLLIN.toShort()
            }

            Log.i("PrivacyVpnService", "TUN DNS Interceptor Loop started.")

            while (isActive && isRunning && localTunParcel === tunFd) {
                try {
                    pollFd.revents = 0
                    val ready = try {
                        android.system.Os.poll(arrayOf(pollFd), 500)
                    } catch (_: Exception) {
                        -1
                    }
                    if (!isActive || !isRunning || localTunParcel !== tunFd) break
                    if (ready <= 0) continue

                    val revents = pollFd.revents.toInt()
                    if ((revents and android.system.OsConstants.POLLIN.toInt()) == 0) {
                        val errMask = android.system.OsConstants.POLLERR.toInt() or
                                android.system.OsConstants.POLLNVAL.toInt() or
                                android.system.OsConstants.POLLHUP.toInt()
                        if ((revents and errMask) != 0) {
                            break
                        }
                        continue
                    }

                    val length = try {
                        inputStream.read(buffer)
                    } catch (_: Exception) {
                        break
                    }
                    if (length <= 0) continue

                    val packetData = buffer.copyOf(length)
                    if (isIpv4UdpDns(packetData)) {
                        processIpv4DnsPacket(packetData, outputStream)
                    } else if (isIpv6UdpDns(packetData)) {
                        processIpv6DnsPacket(packetData, outputStream)
                    } else if (isIpv4TcpDns(packetData)) {
                        sendIpv4TcpRst(packetData, outputStream)
                    } else if (isIpv6TcpDns(packetData)) {
                        sendIpv6TcpRst(packetData, outputStream)
                    }
                } catch (e: Exception) {
                    if (!isActive || !isRunning || localTunParcel !== tunFd) break
                    Log.d("PrivacyVpnService", "TUN read loop error: ${e.message}")
                }
            }
            Log.i("PrivacyVpnService", "TUN DNS Interceptor Loop stopped.")
            if (localTunParcel === tunFd && isRunning) {
                Log.w("PrivacyVpnService", "TUN interface closed externally. Entering 60s standby for auto-reconnect...")
                releaseTunnel()
                onPreemptedByAnotherVpn(applicationContext)
                if (VpnConfig.isVpnEnabled) {
                    promoteToForeground()
                    VpnWatchdog.start(applicationContext)
                }
            }
        }
    }

    private fun isIpv4UdpDns(data: ByteArray): Boolean {
        if (data.size < 28) return false
        val version = (data[0].toInt() and 0xF0) shr 4
        if (version != 4) return false
        val protocol = data[9].toInt() and 0xFF
        if (protocol != 17) return false
        val ihl = (data[0].toInt() and 0x0F) * 4
        if (data.size < ihl + 8) return false
        val dstPort = ((data[ihl + 2].toInt() and 0xFF) shl 8) or (data[ihl + 3].toInt() and 0xFF)
        return dstPort == 53
    }

    private fun processIpv4DnsPacket(packet: ByteArray, outputStream: FileOutputStream) {
        tunScope.launch {
            try {
                val ihl = (packet[0].toInt() and 0x0F) * 4
                val udpLen = ((packet[ihl + 4].toInt() and 0xFF) shl 8) or (packet[ihl + 5].toInt() and 0xFF)
                val dnsPayloadLen = udpLen - 8
                if (dnsPayloadLen <= 0 || ihl + 8 + dnsPayloadLen > packet.size) return@launch

                val dnsQuery = packet.copyOfRange(ihl + 8, ihl + 8 + dnsPayloadLen)
                val server = dnsProxyServer ?: return@launch
                tunDnsPacketsSeen++
                val appName = lookupAppName(
                    17,
                    java.net.InetAddress.getByAddress(packet.copyOfRange(12, 16)),
                    ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF),
                    java.net.InetAddress.getByAddress(packet.copyOfRange(16, 20)),
                    53
                )
                val dnsResponse = server.resolveDnsQuery(dnsQuery, appName) ?: return@launch

                val respIpHeaderLen = 20
                val respUdpHeaderLen = 8
                val totalLen = respIpHeaderLen + respUdpHeaderLen + dnsResponse.size
                val responsePacket = ByteArray(totalLen)

                responsePacket[0] = 0x45.toByte()
                responsePacket[1] = 0x00.toByte()
                responsePacket[2] = (totalLen shr 8).toByte()
                responsePacket[3] = (totalLen and 0xFF).toByte()
                responsePacket[4] = packet[4]
                responsePacket[5] = packet[5]
                responsePacket[6] = 0x40.toByte()
                responsePacket[7] = 0x00.toByte()
                responsePacket[8] = 64.toByte()
                responsePacket[9] = 17.toByte()

                System.arraycopy(packet, 16, responsePacket, 12, 4)
                System.arraycopy(packet, 12, responsePacket, 16, 4)

                val ipChecksum = calcChecksum(responsePacket, 0, respIpHeaderLen)
                responsePacket[10] = (ipChecksum.toInt() shr 8).toByte()
                responsePacket[11] = (ipChecksum.toInt() and 0xFF).toByte()

                responsePacket[20] = packet[ihl + 2]
                responsePacket[21] = packet[ihl + 3]
                responsePacket[22] = packet[ihl]
                responsePacket[23] = packet[ihl + 1]
                val respUdpLen = respUdpHeaderLen + dnsResponse.size
                responsePacket[24] = (respUdpLen shr 8).toByte()
                responsePacket[25] = (respUdpLen and 0xFF).toByte()
                responsePacket[26] = 0.toByte()
                responsePacket[27] = 0.toByte()

                System.arraycopy(dnsResponse, 0, responsePacket, 28, dnsResponse.size)

                synchronized(outputStream) {
                    outputStream.write(responsePacket)
                    outputStream.flush()
                }
            } catch (e: Exception) {
                Log.e("PrivacyVpnService", "Error processing TUN IPv4 DNS packet", e)
            }
        }
    }

    private fun isIpv6UdpDns(data: ByteArray): Boolean {
        if (data.size < 48) return false
        val version = (data[0].toInt() and 0xF0) shr 4
        if (version != 6) return false
        val nextHeader = data[6].toInt() and 0xFF
        if (nextHeader != 17) return false
        val dstPort = ((data[42].toInt() and 0xFF) shl 8) or (data[43].toInt() and 0xFF)
        return dstPort == 53
    }

    private fun processIpv6DnsPacket(packet: ByteArray, outputStream: FileOutputStream) {
        tunScope.launch {
            try {
                val udpLen = ((packet[44].toInt() and 0xFF) shl 8) or (packet[45].toInt() and 0xFF)
                val dnsPayloadLen = udpLen - 8
                if (dnsPayloadLen <= 0 || 48 + dnsPayloadLen > packet.size) return@launch

                val dnsQuery = packet.copyOfRange(48, 48 + dnsPayloadLen)
                val server = dnsProxyServer ?: return@launch
                tunDnsPacketsSeen++
                val appName = lookupAppName(
                    17,
                    java.net.InetAddress.getByAddress(packet.copyOfRange(8, 24)),
                    ((packet[40].toInt() and 0xFF) shl 8) or (packet[41].toInt() and 0xFF),
                    java.net.InetAddress.getByAddress(packet.copyOfRange(24, 40)),
                    53
                )
                val dnsResponse = server.resolveDnsQuery(dnsQuery, appName) ?: return@launch

                val respIpHeaderLen = 40
                val respUdpHeaderLen = 8
                val totalLen = respIpHeaderLen + respUdpHeaderLen + dnsResponse.size
                val responsePacket = ByteArray(totalLen)

                responsePacket[0] = 0x60.toByte()
                responsePacket[1] = 0.toByte()
                responsePacket[2] = 0.toByte()
                responsePacket[3] = 0.toByte()

                val payloadLen = respUdpHeaderLen + dnsResponse.size
                responsePacket[4] = (payloadLen shr 8).toByte()
                responsePacket[5] = (payloadLen and 0xFF).toByte()
                responsePacket[6] = 17.toByte()
                responsePacket[7] = 64.toByte()

                System.arraycopy(packet, 24, responsePacket, 8, 16)
                System.arraycopy(packet, 8, responsePacket, 24, 16)

                responsePacket[40] = packet[42]
                responsePacket[41] = packet[43]
                responsePacket[42] = packet[40]
                responsePacket[43] = packet[41]
                responsePacket[44] = (payloadLen shr 8).toByte()
                responsePacket[45] = (payloadLen and 0xFF).toByte()

                // Copy DNS payload FIRST before calculating UDP checksum!
                System.arraycopy(dnsResponse, 0, responsePacket, 48, dnsResponse.size)

                val udpChecksum = calcIpv6UdpChecksum(responsePacket, dnsResponse)
                responsePacket[46] = (udpChecksum.toInt() shr 8).toByte()
                responsePacket[47] = (udpChecksum.toInt() and 0xFF).toByte()

                synchronized(outputStream) {
                    outputStream.write(responsePacket)
                    outputStream.flush()
                }
            } catch (e: Exception) {
                Log.e("PrivacyVpnService", "Error processing TUN IPv6 DNS packet", e)
            }
        }
    }

    private fun isIpv4TcpDns(data: ByteArray): Boolean {
        if (data.size < 40) return false
        val version = (data[0].toInt() and 0xF0) shr 4
        if (version != 4) return false
        val protocol = data[9].toInt() and 0xFF
        if (protocol != 6) return false
        val ihl = (data[0].toInt() and 0x0F) * 4
        if (data.size < ihl + 20) return false
        val dstPort = ((data[ihl + 2].toInt() and 0xFF) shl 8) or (data[ihl + 3].toInt() and 0xFF)
        return dstPort == 53 || dstPort == 853
    }

    private fun sendIpv4TcpRst(packet: ByteArray, outputStream: FileOutputStream) {
        try {
            val ihl = (packet[0].toInt() and 0x0F) * 4
            val respIpHeaderLen = 20
            val respTcpHeaderLen = 20
            val totalLen = respIpHeaderLen + respTcpHeaderLen
            val rstPacket = ByteArray(totalLen)

            rstPacket[0] = 0x45.toByte()
            rstPacket[1] = 0x00.toByte()
            rstPacket[2] = (totalLen shr 8).toByte()
            rstPacket[3] = (totalLen and 0xFF).toByte()
            rstPacket[4] = packet[4]
            rstPacket[5] = packet[5]
            rstPacket[6] = 0x40.toByte()
            rstPacket[7] = 0x00.toByte()
            rstPacket[8] = 64.toByte()
            rstPacket[9] = 6.toByte()

            System.arraycopy(packet, 16, rstPacket, 12, 4)
            System.arraycopy(packet, 12, rstPacket, 16, 4)

            val ipChecksum = calcChecksum(rstPacket, 0, respIpHeaderLen)
            rstPacket[10] = (ipChecksum.toInt() shr 8).toByte()
            rstPacket[11] = (ipChecksum.toInt() and 0xFF).toByte()

            rstPacket[20] = packet[ihl + 2]
            rstPacket[21] = packet[ihl + 3]
            rstPacket[22] = packet[ihl]
            rstPacket[23] = packet[ihl + 1]

            val incomingSeq = ((packet[ihl + 4].toLong() and 0xFF) shl 24) or
                    ((packet[ihl + 5].toLong() and 0xFF) shl 16) or
                    ((packet[ihl + 6].toLong() and 0xFF) shl 8) or
                    (packet[ihl + 7].toLong() and 0xFF)
            val nextAck = incomingSeq + 1

            rstPacket[28] = (nextAck shr 24).toByte()
            rstPacket[29] = (nextAck shr 16).toByte()
            rstPacket[30] = (nextAck shr 8).toByte()
            rstPacket[31] = (nextAck and 0xFF).toByte()

            rstPacket[32] = 0x50.toByte()
            rstPacket[33] = 0x14.toByte()

            val tcpChecksum = calcIpv4TcpChecksum(rstPacket)
            rstPacket[36] = (tcpChecksum.toInt() shr 8).toByte()
            rstPacket[37] = (tcpChecksum.toInt() and 0xFF).toByte()

            synchronized(outputStream) {
                outputStream.write(rstPacket)
                outputStream.flush()
            }
            Log.d("PrivacyVpnService", "Sent IPv4 TCP RST for probe on port ${((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)}")
        } catch (e: Exception) {
            Log.e("PrivacyVpnService", "Error sending IPv4 TCP RST", e)
        }
    }

    private fun isIpv6TcpDns(data: ByteArray): Boolean {
        if (data.size < 60) return false
        val version = (data[0].toInt() and 0xF0) shr 4
        if (version != 6) return false
        val nextHeader = data[6].toInt() and 0xFF
        if (nextHeader != 6) return false
        val dstPort = ((data[42].toInt() and 0xFF) shl 8) or (data[43].toInt() and 0xFF)
        return dstPort == 53 || dstPort == 853
    }

    private fun sendIpv6TcpRst(packet: ByteArray, outputStream: FileOutputStream) {
        try {
            val respIpHeaderLen = 40
            val respTcpHeaderLen = 20
            val totalLen = respIpHeaderLen + respTcpHeaderLen
            val rstPacket = ByteArray(totalLen)

            rstPacket[0] = 0x60.toByte()
            rstPacket[1] = 0.toByte()
            rstPacket[2] = 0.toByte()
            rstPacket[3] = 0.toByte()

            rstPacket[4] = 0.toByte()
            rstPacket[5] = 20.toByte()
            rstPacket[6] = 6.toByte()
            rstPacket[7] = 64.toByte()

            System.arraycopy(packet, 24, rstPacket, 8, 16)
            System.arraycopy(packet, 8, rstPacket, 24, 16)

            rstPacket[40] = packet[42]
            rstPacket[41] = packet[43]
            rstPacket[42] = packet[40]
            rstPacket[43] = packet[41]

            val incomingSeq = ((packet[44].toLong() and 0xFF) shl 24) or
                    ((packet[45].toLong() and 0xFF) shl 16) or
                    ((packet[46].toLong() and 0xFF) shl 8) or
                    (packet[47].toLong() and 0xFF)
            val nextAck = incomingSeq + 1

            rstPacket[48] = (nextAck shr 24).toByte()
            rstPacket[49] = (nextAck shr 16).toByte()
            rstPacket[50] = (nextAck shr 8).toByte()
            rstPacket[51] = (nextAck and 0xFF).toByte()

            rstPacket[52] = 0x50.toByte()
            rstPacket[53] = 0x14.toByte()

            val tcpChecksum = calcIpv6TcpChecksum(rstPacket)
            rstPacket[56] = (tcpChecksum.toInt() shr 8).toByte()
            rstPacket[57] = (tcpChecksum.toInt() and 0xFF).toByte()

            synchronized(outputStream) {
                outputStream.write(rstPacket)
                outputStream.flush()
            }
            Log.d("PrivacyVpnService", "Sent IPv6 TCP RST for probe on port ${((packet[42].toInt() and 0xFF) shl 8) or (packet[43].toInt() and 0xFF)}")
        } catch (e: Exception) {
            Log.e("PrivacyVpnService", "Error sending IPv6 TCP RST", e)
        }
    }

    private fun calcIpv4TcpChecksum(ipPacket: ByteArray): Short {
        var sum = 0L
        sum += ((ipPacket[12].toInt() and 0xFF) shl 8) or (ipPacket[13].toInt() and 0xFF)
        sum += ((ipPacket[14].toInt() and 0xFF) shl 8) or (ipPacket[15].toInt() and 0xFF)
        sum += ((ipPacket[16].toInt() and 0xFF) shl 8) or (ipPacket[17].toInt() and 0xFF)
        sum += ((ipPacket[18].toInt() and 0xFF) shl 8) or (ipPacket[19].toInt() and 0xFF)
        sum += 6
        sum += 20

        for (i in 20..38 step 2) {
            if (i == 36) continue
            sum += ((ipPacket[i].toInt() and 0xFF) shl 8) or (ipPacket[i + 1].toInt() and 0xFF)
        }

        while ((sum shr 16) > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        val result = sum.inv().toShort()
        return if (result.toInt() == 0) (-1).toShort() else result
    }

    private fun calcIpv6TcpChecksum(ipv6Packet: ByteArray): Short {
        var sum = 0L
        for (i in 8..22 step 2) {
            sum += ((ipv6Packet[i].toInt() and 0xFF) shl 8) or (ipv6Packet[i + 1].toInt() and 0xFF)
        }
        for (i in 24..38 step 2) {
            sum += ((ipv6Packet[i].toInt() and 0xFF) shl 8) or (ipv6Packet[i + 1].toInt() and 0xFF)
        }
        sum += 20
        sum += 6

        for (i in 40..58 step 2) {
            if (i == 56) continue
            sum += ((ipv6Packet[i].toInt() and 0xFF) shl 8) or (ipv6Packet[i + 1].toInt() and 0xFF)
        }

        while ((sum shr 16) > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        val result = sum.inv().toShort()
        return if (result.toInt() == 0) (-1).toShort() else result
    }

    private fun calcIpv6UdpChecksum(ipv6Packet: ByteArray, udpPayload: ByteArray): Short {
        var sum = 0L
        for (i in 8..22 step 2) {
            sum += (((ipv6Packet[i].toInt() and 0xFF) shl 8) or (ipv6Packet[i + 1].toInt() and 0xFF))
        }
        for (i in 24..38 step 2) {
            sum += (((ipv6Packet[i].toInt() and 0xFF) shl 8) or (ipv6Packet[i + 1].toInt() and 0xFF))
        }
        val udpLen = 8 + udpPayload.size
        sum += (udpLen shr 16) and 0xFFFF
        sum += udpLen and 0xFFFF
        sum += 0x0011

        val srcPort = ((ipv6Packet[40].toInt() and 0xFF) shl 8) or (ipv6Packet[41].toInt() and 0xFF)
        val dstPort = ((ipv6Packet[42].toInt() and 0xFF) shl 8) or (ipv6Packet[43].toInt() and 0xFF)

        sum += srcPort
        sum += dstPort
        sum += udpLen

        var i = 0
        while (i < udpPayload.size - 1) {
            val word = ((udpPayload[i].toInt() and 0xFF) shl 8) or (udpPayload[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (i < udpPayload.size) {
            sum += (udpPayload[i].toInt() and 0xFF) shl 8
        }

        while ((sum shr 16) > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        val result = sum.inv().toShort()
        return if (result.toInt() == 0) (-1).toShort() else result
    }

    private fun calcChecksum(buffer: ByteArray, offset: Int, length: Int): Short {
        var sum = 0
        var i = offset
        while (i < offset + length - 1) {
            val word = ((buffer[i].toInt() and 0xFF) shl 8) or (buffer[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (i < offset + length) {
            sum += (buffer[i].toInt() and 0xFF) shl 8
        }
        while ((sum shr 16) > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum.inv().toShort()
    }

    private fun releaseTunnel() {
        try {
            tunJob?.cancel()
            tunJob = null
            dnsProxyServer?.stop()
            dnsProxyServer = null
            activeProxy = null
            localTunParcel?.close()
            localTunParcel = null
            activeTunFd = null
        } catch (e: Exception) {
            Log.e("PrivacyVpnService", "Error releasing tunnel resources", e)
        }
        isVpnRunning = false
        isRunning = false
    }

    private fun stopVpn() {
        releaseTunnel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.i("PrivacyVpnService", "Privacy VPN stopped.")
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "xPrivacyGuard Privacy Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active privacy firewall and encrypted DNS"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val stopIntent = Intent(this, PrivacyVpnService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (isRunning) "Privacy Protection Active" else "Privacy Protection Standby"
        val text = if (isRunning) {
            "Encrypted DNS (${VpnConfig.dnsProtocol}) & Local Privacy Firewall"
        } else {
            "Paused while another VPN is connected — will auto-reconnect"
        }

        val accentColor = if (isRunning) 0xFF22C55E.toInt() else 0xFFF38020.toInt()
        val largeIconRes = if (isRunning) {
            cc.xconflictionx.xprivacyguard.R.drawable.ic_shield_connected
        } else {
            cc.xconflictionx.xprivacyguard.R.drawable.ic_shield
        }
        val largeBitmap = runCatching {
            val drawable = getDrawable(largeIconRes) ?: return@runCatching null
            val size = (48 * resources.displayMetrics.density).toInt().coerceAtLeast(64)
            val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
            bmp
        }.getOrNull()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(cc.xconflictionx.xprivacyguard.R.drawable.ic_shield)
            .setColor(accentColor)
            .apply { if (largeBitmap != null) setLargeIcon(largeBitmap) }
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
    }

    override fun onDestroy() {
        stopVpn()
        if (VpnConfig.isVpnEnabled) {
            Log.w("PrivacyVpnService", "Service destroyed while protection is enabled by user. Triggering watchdog auto-restart...")
            VpnWatchdog.start(applicationContext)
        }
        super.onDestroy()
    }
}
