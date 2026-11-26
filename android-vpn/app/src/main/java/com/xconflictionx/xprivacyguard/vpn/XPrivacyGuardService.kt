package com.xconflictionx.xprivacyguard.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import com.xconflictionx.xprivacyguard.MainActivity
import com.xconflictionx.xprivacyguard.R
import com.xconflictionx.xprivacyguard.config.CloudflareOneConfig
import com.xconflictionx.xprivacyguard.config.WireGuardConfig
import com.xconflictionx.xprivacyguard.dns.ZeroTrustDnsConfig
import com.xconflictionx.xprivacyguard.dns.ZeroTrustDnsResolver
import com.xconflictionx.xprivacyguard.packet.PacketAction
import com.xconflictionx.xprivacyguard.packet.PacketInspector
import com.xconflictionx.xprivacyguard.vpn.transport.AetherMasqueTransport
import com.xconflictionx.xprivacyguard.vpn.transport.TunnelProtocol
import com.xconflictionx.xprivacyguard.wireguard.WireGuardCrypto
import com.xconflictionx.xprivacyguard.wireguard.WireGuardSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class XPrivacyGuardService : VpnService() {

    companion object {
        const val ACTION_START = "com.xconflictionx.xprivacyguard.START"
        const val ACTION_STOP = "com.xconflictionx.xprivacyguard.STOP"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "xprivacy_vpn_channel"
        private const val REKEY_AFTER_TIME_MS = 120_000L
        private const val REJECT_AFTER_TIME_MS = 175_000L
        private const val STALE_RX_REHANDSHAKE_MS = 30_000L
        private const val HANDSHAKE_BASE_RETRY_MS = 1_200L
        private const val HANDSHAKE_MAX_BACKOFF_MS = 12_000L
        private const val MAX_PENDING_PACKETS = 64
        private const val SCREEN_ON_KEEPALIVE_SEC = 25

        internal fun shouldTriggerPhysicalNetworkSwitch(
            triggerSwitch: Boolean,
            prevPhysicalNetId: Any?,
            bestPhysicalNetId: Any?,
            lastTunnelPhysicalNetId: Any?,
            hasActiveTunnel: Boolean,
            isConnected: Boolean,
            expectedLanSubnets: Set<String>,
            appliedLanSubnets: Set<String>
        ): Boolean {
            if (!triggerSwitch || bestPhysicalNetId == null || !hasActiveTunnel) return false
            val switchedDirectly = prevPhysicalNetId != null && prevPhysicalNetId != bestPhysicalNetId
            val switchedFromTunnelNetwork = lastTunnelPhysicalNetId != null && lastTunnelPhysicalNetId != bestPhysicalNetId
            val restoredFromOffline = prevPhysicalNetId == null
            val lanExclusionsDrifted = isConnected && expectedLanSubnets != appliedLanSubnets
            return switchedDirectly || switchedFromTunnelNetwork || restoredFromOffline || lanExclusionsDrifted
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dnsConcurrencyLimiter = Semaphore(16)
    private val pendingOutboundPackets = ConcurrentLinkedQueue<ByteArray>()
    private val isRebindingSocket = AtomicBoolean(false)

    private var tunnelJob: Job? = null
    private var udpRxJob: Job? = null
    private var aetherRxJob: Job? = null
    private var masqueWatchdogJob: Job? = null
    private var firewallReloadJob: Job? = null
    private var keepaliveWatchdogJob: Job? = null
    private var networkSwitchDebounceJob: Job? = null
    private var screenStateReceiver: BroadcastReceiver? = null
    private var tunInterface: ParcelFileDescriptor? = null

    private val tunnelGeneration = AtomicLong(0L)
    private val availablePhysicalNetworks = ConcurrentHashMap<Network, NetworkCapabilities>()
    private val activePhysicalNetworkFlow = MutableStateFlow<Network?>(null)

    @Volatile
    private var activeAetherTransport: AetherMasqueTransport? = null

    @Volatile
    private var appliedTransport: TunnelProtocol? = null

    @Volatile
    private var appliedBypassPackages: Set<String> = emptySet()

    @Volatile
    private var appliedTunDnsServers: List<String> = emptyList()

    @Volatile
    private var appliedExcludedLanSubnets: Set<String> = emptySet()


    @Volatile
    private var udpSocket: DatagramSocket? = null

    @Volatile
    private var activeWgSession: WireGuardSession? = null

    @Volatile
    private var activePeerAddress: InetSocketAddress? = null

    @Volatile
    private var activeConfig: WireGuardConfig? = null

    @Volatile
    private var currentPhysicalNetwork: Network? = null

    @Volatile
    private var lastTunnelPhysicalNetwork: Network? = null

    private var tunOutStream: FileOutputStream? = null
    private val tunWriteLock = Any()
    private val socketSwapLock = Any()

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val hasPhysicalNetwork = AtomicBoolean(true)
    private val isScreenInteractive = AtomicBoolean(true)
    private val unansweredHandshakeCount = AtomicInteger(0)
    private val lastTxEpochMs = AtomicLong(0L)
    private val lastRxEpochMs = AtomicLong(0L)
    private val lastHandshakeAttemptEpochMs = AtomicLong(0L)

    override fun onCreate() {
        super.onCreate()
        val interactive = runCatching {
            (getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive ?: true
        }.getOrDefault(true)
        isScreenInteractive.set(interactive)
        VpnStateManager.updateScreenState(interactive, effectiveScreenOnKeepaliveSec())
        refreshAlwaysOnState()
        registerScreenStateReceiver()
    }

    private fun refreshAlwaysOnState() {
        val alwaysOn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { isAlwaysOn }.getOrDefault(false)
        } else {
            false
        }
        VpnStateManager.updateAlwaysOnState(alwaysOn)
    }

    private inline fun <T> withTransitionWakeLock(tag: String, block: () -> T): T {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wl = runCatching {
            pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "xPrivacyGuard:$tag")?.apply {
                setReferenceCounted(false)
                acquire(25_000L)
            }
        }.getOrNull()
        return try {
            block()
        } finally {
            runCatching { if (wl?.isHeld == true) wl.release() }
        }
    }

    private fun effectiveScreenOnKeepaliveSec(): Int {
        val profileKa = activeConfig?.persistentKeepalive ?: 0
        return if (profileKa in 10..SCREEN_ON_KEEPALIVE_SEC) profileKa else SCREEN_ON_KEEPALIVE_SEC
    }

    override fun onBind(intent: Intent?): IBinder? {
        if (intent?.action == SERVICE_INTERFACE) {
            ensureTunnelRunning()
        }
        return super.onBind(intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpnTunnel()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                ensureTunnelRunning()
                return START_STICKY
            }
        }
    }

    private fun computeAllowedTunnelPackages(): Set<String> {
        val selectedTunnelPackages = VpnStateManager.resolveEffectiveBypassPackages(applicationContext)
        val pm = applicationContext.packageManager
        val installedPackages = runCatching {
            pm.getInstalledApplications(PackageManager.MATCH_DIRECT_BOOT_AWARE or PackageManager.MATCH_DIRECT_BOOT_UNAWARE)
                .mapNotNull { it.packageName }
                .toSet()
        }.getOrDefault(emptySet())
        return com.xconflictionx.xprivacyguard.firewall.FirewallManager.buildAllowedPackages(
            selectedPackages = selectedTunnelPackages,
            ownPackage = applicationContext.packageName,
            installedPackages = installedPackages
        )
    }

    /**
     * Computes the DNS servers advertised on Android's TUN interface.
     * When a private LAN DNS server (e.g. AdGuard Home) is configured, advertise ONLY
     * that server on TUN. Advertising public DoH-capable IPs like 1.1.1.1 alongside it causes
     * Android's DnsResolver (in opportunistic Private DNS mode) to auto-upgrade 1.1.1.1 to DoH
     * over QUIC/UDP:443, bypassing xPrivacyGuard's UDP:53 interceptor and breaking Split DNS.
     * ZeroTrustDnsResolver still uses config.dnsServers / DoT / DoH internally as fallbacks.
     */
    private fun computeTunDnsServers(
        ztDns: ZeroTrustDnsConfig,
        config: WireGuardConfig?
    ): List<String> {
        val privateServer = ztDns.privateDnsServer.trim()
        if (privateServer.isNotEmpty()) {
            return listOf(privateServer)
        }
        return (config?.dnsServers ?: emptyList())
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .ifEmpty { listOf("1.1.1.1", "1.0.0.1") }
    }

    private fun ensureTunnelRunning() {
        VpnStateManager.activeVpnService = this
        VpnStateManager.ensureInitialized(applicationContext)
        refreshAlwaysOnState()
        startForegroundWithNotification("Connecting to Zero Trust...")

        val expectedAllowed = computeAllowedTunnelPackages()
        val currentZtDns = VpnStateManager.state.value.zeroTrustDns
        val expectedTransport = VpnStateManager.state.value.activeTransport
        val expectedTunDns = computeTunDnsServers(currentZtDns, activeConfig)
        val expectedLanSubnets = formatLanExclusionKeys(computeLocalWifiLanExclusions(currentPhysicalNetwork ?: selectBestPhysicalNetwork()))
        if (tunnelJob?.isActive == true &&
            tunInterface != null &&
            appliedTransport == expectedTransport &&
            appliedBypassPackages == expectedAllowed &&
            appliedTunDnsServers == expectedTunDns &&
            appliedExcludedLanSubnets == expectedLanSubnets
        ) {
            if (expectedTransport == TunnelProtocol.WIREGUARD) {
                val session = activeWgSession
                val sock = udpSocket
                val peer = activePeerAddress
                if (session != null && sock != null && peer != null && !session.isEstablished) {
                    serviceScope.launch(Dispatchers.IO) {
                        triggerHandshakeIfAllowed(session, sock, peer, force = true)
                    }
                }
            }
        } else {
            startVpnTunnel()
        }
    }

    /**
     * Immediately rebuilds the TUN interface with the updated disallowed app list when the
     * user toggles any app in the Firewall tab while the VPN is connected.
     * Closes the previous TUN descriptor first so Android's ConnectivityService & netd
     * tear down any existing VPN-bound QUIC/TCP sockets for newly bypassed apps.
     */
    fun onFirewallBypassChanged() {
        firewallReloadJob?.cancel()
        firewallReloadJob = serviceScope.launch {
            delay(150L)
            if (tunnelJob?.isActive == true || tunInterface != null) {
                runCatching { tunInterface?.close() }
                tunInterface = null
                startVpnTunnel()
            }
        }
    }

    /**
     * Called on USER_PRESENT / boot broadcasts when the tunnel is already running.
     * Only rebuilds the TUN interface if the effective allowed package list or DNS config
     * actually changed (e.g., right after Direct Boot unlock); otherwise leaves the
     * established WireGuard session intact so unlocking the phone never drops the VPN.
     */
    fun syncTunnelIfConfigChanged() {
        serviceScope.launch(Dispatchers.IO) {
            VpnStateManager.ensureInitialized(applicationContext)
            val expectedAllowed = computeAllowedTunnelPackages()
            val currentZtDns = VpnStateManager.state.value.zeroTrustDns
            val expectedTunDns = computeTunDnsServers(currentZtDns, activeConfig)
            val expectedLanSubnets = formatLanExclusionKeys(computeLocalWifiLanExclusions(currentPhysicalNetwork ?: selectBestPhysicalNetwork()))
            val needsRebuild = tunnelJob?.isActive != true ||
                tunInterface == null ||
                appliedBypassPackages != expectedAllowed ||
                appliedTunDnsServers != expectedTunDns ||
                appliedExcludedLanSubnets != expectedLanSubnets

            if (needsRebuild) {
                onFirewallBypassChanged()
            } else {
                warmSessionIfStale()
            }
        }
    }

    /**
     * Called when the underlying physical network (Wi-Fi / 5G) connects or switches.
     * Invalidates stale DNS/socket state and migrates MASQUE or rebinds WireGuard cleanly.
     */
    private fun onPhysicalNetworkChanged(newNetwork: Network?, forceSocketRecreate: Boolean) {
        networkSwitchDebounceJob?.cancel()
        networkSwitchDebounceJob = serviceScope.launch {
            // Brief settle delay so Wi-Fi DHCP/validation + cell teardown coalesce into one switch
            delay(350L)
            withTransitionWakeLock("NetSwitch") {
                VpnStateManager.ensureInitialized(applicationContext)
                ZeroTrustDnsResolver.onNetworkChanged()

                val bestNet = currentPhysicalNetwork ?: selectBestPhysicalNetwork() ?: newNetwork
                if (bestNet == null) {
                    return@withTransitionWakeLock
                }

                // If local Wi-Fi LAN exclusion set changed (e.g., joined or left Home Wi-Fi),
                // rebuild the TUN interface so LAN traffic bypasses the VPN on Home Wi-Fi and
                // routes back through Cloudflare Zero Trust on Cellular / away networks.
                val expectedLanSubnets = formatLanExclusionKeys(computeLocalWifiLanExclusions(bestNet))
                if (expectedLanSubnets != appliedExcludedLanSubnets) {
                    startVpnTunnel()
                    return@withTransitionWakeLock
                }

                // Aether MASQUE path: trigger QUIC connection migration, retry once, or reconnect cleanly
                val aether = activeAetherTransport
                if (appliedTransport == TunnelProtocol.CLOUDFLARE_MASQUE) {
                    if (aether != null && aether.isAlive()) {
                        val migrated = runCatching { aether.migrateNetwork() }.getOrDefault(false)
                        if (migrated) {
                            lastTunnelPhysicalNetwork = bestNet
                            return@withTransitionWakeLock
                        }
                        delay(400L)
                        if (aether.isAlive() && runCatching { aether.migrateNetwork() }.getOrDefault(false)) {
                            lastTunnelPhysicalNetwork = bestNet
                            return@withTransitionWakeLock
                        }
                    }
                    startVpnTunnel()
                    return@withTransitionWakeLock
                }

                val session = activeWgSession ?: return@withTransitionWakeLock
                val cfg = activeConfig ?: return@withTransitionWakeLock

                if (forceSocketRecreate || udpSocket == null || udpSocket?.isClosed == true) {
                    rebindUdpSocketAndReceiver(cfg, session)
                } else {
                    udpSocket?.let { sock ->
                        runCatching { protect(sock) }
                        runCatching { bestNet.bindSocket(sock) }
                    }
                }
                lastTunnelPhysicalNetwork = bestNet

                unansweredHandshakeCount.set(0)
                lastHandshakeAttemptEpochMs.set(0L)
                val sock = udpSocket ?: return@withTransitionWakeLock
                val peer = activePeerAddress ?: return@withTransitionWakeLock
                triggerHandshakeIfAllowed(session, sock, peer, force = true)
            }
        }
    }

    private fun rebindUdpSocketAndReceiver(config: WireGuardConfig, wgSession: WireGuardSession) {
        val physNet = currentPhysicalNetwork ?: selectBestPhysicalNetwork()
        val resolvedPeer = runCatching {
            val ztDns = VpnStateManager.state.value.zeroTrustDns
            val ip = ZeroTrustDnsResolver.resolveHostnameToIp(
                context = applicationContext,
                hostname = config.endpointHost,
                config = ztDns,
                vpnService = this@XPrivacyGuardService,
                preferredNetwork = physNet
            ) ?: config.endpointHost
            InetSocketAddress(InetAddress.getByName(ip), config.endpointPort)
        }.getOrNull()

        synchronized(socketSwapLock) {
            val oldSocket = udpSocket
            udpRxJob?.cancel()

            val newSocket = DatagramSocket()
            protect(newSocket)
            physNet?.let { runCatching { it.bindSocket(newSocket) } }
            runCatching {
                newSocket.receiveBufferSize = 512 * 1024
                newSocket.sendBufferSize = 512 * 1024
                newSocket.trafficClass = 0x10
            }
            newSocket.soTimeout = 0
            udpSocket = newSocket
            runCatching { oldSocket?.close() }

            if (resolvedPeer != null && !resolvedPeer.isUnresolved) {
                activePeerAddress = resolvedPeer
            }

            udpRxJob = launchUdpReceiverLoop(newSocket, wgSession)
        }
    }

    private fun launchUdpReceiverLoop(
        socket: DatagramSocket,
        wgSession: WireGuardSession
    ): Job = serviceScope.launch(Dispatchers.IO) {
        val rxBuf = ByteArray(65535)
        val decryptedIpBuf = ByteArray(65535)
        val packet = DatagramPacket(rxBuf, rxBuf.size)

        while (isActive && !socket.isClosed) {
            val received = try {
                socket.receive(packet)
                true
            } catch (_: Exception) {
                if (!isActive || socket.isClosed) break
                delay(500L)
                false
            }

            if (!received) continue
            val len = packet.length
            if (len < 4) continue
            val now = System.currentTimeMillis()
            lastRxEpochMs.set(now)
            unansweredHandshakeCount.set(0)
            VpnStateManager.addRxBytes(len)

            val msgType = (rxBuf[0].toInt() and 0xFF) or
                ((rxBuf[1].toInt() and 0xFF) shl 8) or
                ((rxBuf[2].toInt() and 0xFF) shl 16) or
                ((rxBuf[3].toInt() and 0xFF) shl 24)

            when (msgType) {
                2 -> {
                    if (wgSession.processHandshakeResponse(rxBuf, len)) {
                        lastRxEpochMs.set(now)
                        unansweredHandshakeCount.set(0)
                        ZeroTrustDnsResolver.resetPrivateDnsCooldown()
                        VpnStateManager.updateStatus(
                            status = TunnelStatus.CONNECTED,
                            message = "Connected • WireGuard",
                            handshakeEstablished = true,
                            lastHandshakeMs = wgSession.lastHandshakeEpochMs
                        )
                        updateNotification("Connected • WireGuard")
                        val peer = activePeerAddress
                        if (peer != null) {
                            // Send keepalive confirmation
                            val kaBuf = ByteArray(32)
                            val kaLen = wgSession.encryptTransportPacketInto(WireGuardCrypto.EMPTY_BYTES, 0, kaBuf)
                            if (kaLen > 0) {
                                runCatching {
                                    socket.send(DatagramPacket(kaBuf, kaLen, peer))
                                    lastTxEpochMs.set(System.currentTimeMillis())
                                    VpnStateManager.addTxBytes(kaLen)
                                }
                            }
                            startContinuousKeepaliveAndWatchdog(wgSession)
                            // Flush any packets queued during handshake
                            val flushWgBuf = ByteArray(65535)
                            while (true) {
                                val queuedIp = pendingOutboundPackets.poll() ?: break
                                val paddedCap = ((queuedIp.size + 15) / 16) * 16
                                val paddedIn = if (queuedIp.size >= paddedCap) queuedIp else queuedIp.copyOf(paddedCap)
                                val encLen = wgSession.encryptTransportPacketInto(paddedIn, queuedIp.size, flushWgBuf)
                                if (encLen > 0) {
                                    runCatching {
                                        socket.send(DatagramPacket(flushWgBuf, encLen, peer))
                                        lastTxEpochMs.set(System.currentTimeMillis())
                                        VpnStateManager.addTxBytes(encLen)
                                    }
                                }
                            }
                        }
                    }
                }
                4 -> {
                    val plainLen = wgSession.decryptTransportPacketInto(rxBuf, len, decryptedIpBuf)
                    if (plainLen > 0) {
                        val out = tunOutStream
                        if (out != null) {
                            synchronized(tunWriteLock) {
                                runCatching {
                                    out.write(decryptedIpBuf, 0, plainLen)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun selectOrderedPhysicalNetworks(): Array<Network> {
        return availablePhysicalNetworks.entries
            .filter { (_, caps) ->
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
            .sortedBy { (_, caps) ->
                val validatedScore = if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 0 else 10
                val transportScore = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 0
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 1
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 2
                    else -> 3
                }
                validatedScore + transportScore
            }
            .map { it.key }
            .toTypedArray()
    }

    private fun selectBestPhysicalNetwork(): Network? {
        return selectOrderedPhysicalNetworks().firstOrNull()
    }

    private fun applyActivePhysicalNetwork(triggerSwitch: Boolean) {
        val ordered = selectOrderedPhysicalNetworks()
        val best = ordered.firstOrNull()
        val prev = currentPhysicalNetwork
        currentPhysicalNetwork = best
        hasPhysicalNetwork.set(best != null)
        activePhysicalNetworkFlow.value = best
        runCatching {
            if (ordered.isNotEmpty()) {
                setUnderlyingNetworks(ordered)
            } else {
                setUnderlyingNetworks(null)
            }
        }
        val hasActiveTunnel = tunnelJob?.isActive == true ||
            tunInterface != null ||
            activeAetherTransport != null ||
            activeWgSession != null
        val isConnected = VpnStateManager.state.value.status == TunnelStatus.CONNECTED
        val expectedLanSubnets = if (best != null && isConnected) {
            formatLanExclusionKeys(computeLocalWifiLanExclusions(best))
        } else {
            appliedExcludedLanSubnets
        }

        if (shouldTriggerPhysicalNetworkSwitch(
                triggerSwitch = triggerSwitch,
                prevPhysicalNetId = prev,
                bestPhysicalNetId = best,
                lastTunnelPhysicalNetId = lastTunnelPhysicalNetwork,
                hasActiveTunnel = hasActiveTunnel,
                isConnected = isConnected,
                expectedLanSubnets = expectedLanSubnets,
                appliedLanSubnets = appliedExcludedLanSubnets
            )
        ) {
            onPhysicalNetworkChanged(best, forceSocketRecreate = true)
        }
    }

    @Suppress("DEPRECATION")
    private fun seedInitialPhysicalNetworks(cm: ConnectivityManager) {
        availablePhysicalNetworks.clear()
        runCatching {
            for (net in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(net) ?: continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                ) {
                    availablePhysicalNetworks[net] = caps
                }
            }
        }
        applyActivePhysicalNetwork(triggerSwitch = false)
    }

    private suspend fun awaitPhysicalNetwork(): Network {
        currentPhysicalNetwork?.let { return it }
        VpnStateManager.updateStatus(
            status = TunnelStatus.CONNECTING,
            message = "Waiting for network...",
            handshakeEstablished = false
        )
        updateNotification("Waiting for network...")
        return activePhysicalNetworkFlow.first { it != null }!!
    }

    private fun registerPhysicalNetworkCallback() {
        unregisterPhysicalNetworkCallback()
        runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            seedInitialPhysicalNetworks(cm)
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull() ?: return
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
                    availablePhysicalNetworks[network] = caps
                    applyActivePhysicalNetwork(triggerSwitch = true)
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
                    val prevCaps = availablePhysicalNetworks.put(network, networkCapabilities)
                    val isNewNetwork = prevCaps == null
                    val becameValidated = prevCaps != null &&
                        !prevCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                        networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    applyActivePhysicalNetwork(triggerSwitch = isNewNetwork || becameValidated)
                }

                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                    val caps = availablePhysicalNetworks[network] ?: return
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
                    if (network == currentPhysicalNetwork) {
                        applyActivePhysicalNetwork(triggerSwitch = true)
                    }
                }

                override fun onLost(network: Network) {
                    availablePhysicalNetworks.remove(network)
                    ZeroTrustDnsResolver.onNetworkChanged()
                    applyActivePhysicalNetwork(triggerSwitch = true)
                }
            }
            cm.registerNetworkCallback(req, callback)
            networkCallback = callback
        }
    }

    private fun unregisterPhysicalNetworkCallback() {
        networkSwitchDebounceJob?.cancel()
        networkSwitchDebounceJob = null
        val cb = networkCallback ?: return
        networkCallback = null
        availablePhysicalNetworks.clear()
        runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.unregisterNetworkCallback(cb)
        }
    }

    private suspend fun waitForValidWireGuardConfig(): WireGuardConfig {
        while (true) {
            VpnStateManager.ensureInitialized(applicationContext)
            val confText = VpnStateManager.loadWireGuardConfText(applicationContext)
            val parsed = WireGuardConfig.parse(confText).getOrNull()
            if (parsed != null) return parsed

            VpnStateManager.updateStatus(
                status = TunnelStatus.CONNECTING,
                message = "Waiting for device unlock / WireGuard config..."
            )
            delay(2000L)
        }
    }

    private suspend fun resolvePeerSocketAddressWithRetry(
        config: WireGuardConfig,
        ztDns: ZeroTrustDnsConfig
    ): InetSocketAddress {
        while (true) {
            val physNet = awaitPhysicalNetwork()
            val resolvedAddr = runCatching {
                val ip = ZeroTrustDnsResolver.resolveHostnameToIp(
                    context = applicationContext,
                    hostname = config.endpointHost,
                    config = ztDns,
                    vpnService = this@XPrivacyGuardService,
                    preferredNetwork = physNet
                ) ?: config.endpointHost
                InetSocketAddress(InetAddress.getByName(ip), config.endpointPort)
            }.getOrNull()

            if (resolvedAddr != null && !resolvedAddr.isUnresolved) {
                return resolvedAddr
            }

            VpnStateManager.updateStatus(
                status = TunnelStatus.CONNECTING,
                message = "Resolving ${config.endpointHost}..."
            )
            delay(2500L)
        }
    }

    private fun startVpnTunnel() {
        val gen = tunnelGeneration.incrementAndGet()
        tunnelJob?.cancel()
        aetherRxJob?.cancel()
        aetherRxJob = null
        masqueWatchdogJob?.cancel()
        masqueWatchdogJob = null
        keepaliveWatchdogJob?.cancel()
        keepaliveWatchdogJob = null
        if (networkCallback == null) {
            registerPhysicalNetworkCallback()
        }

        val selectedTransport = VpnStateManager.state.value.activeTransport
        appliedTransport = selectedTransport
        if (selectedTransport == TunnelProtocol.CLOUDFLARE_MASQUE) {
            startAetherMasqueTunnel(gen)
            return
        }

        tunnelJob = serviceScope.launch {
            try {
                VpnStateManager.updateStatus(
                    status = TunnelStatus.CONNECTING,
                    message = "Establishing TUN & WireGuard session..."
                )

                val config = waitForValidWireGuardConfig()
                activeConfig = config
                val ztDns = VpnStateManager.state.value.zeroTrustDns

                runCatching { udpSocket?.close() }
                runCatching { tunInterface?.close() }

                if (ztDns.isPrivateDnsConfigured && !config.routesAddress(ztDns.privateDnsServer)) {
                    throw IllegalStateException("WireGuard AllowedIPs does not include the configured private DNS server.")
                }

                // 1. Build Android VpnService TUN interface
                val builder = Builder()
                    .setSession("xPrivacyGuard Zero Trust")
                    .setMtu(config.mtu)

                for (addr in config.addresses) {
                    runCatching {
                        builder.addAddress(addr.address, addr.prefixLength)
                    }
                }

                val tunDnsList = computeTunDnsServers(ztDns, config)

                for (dns in tunDnsList) {
                    runCatching {
                        builder.addDnsServer(dns)
                    }
                }
                if (ztDns.privateDomain.isNotBlank()) {
                    runCatching {
                        builder.addSearchDomain(ztDns.privateDomain.trim().trim('.'))
                    }
                }

                val activePhysNet = currentPhysicalNetwork ?: selectBestPhysicalNetwork()
                lastTunnelPhysicalNetwork = activePhysNet
                val lanExclusions = computeLocalWifiLanExclusions(activePhysNet)
                for (route in config.allowedIps) {
                    runCatching {
                        if (route.address == "0.0.0.0" && route.prefixLength == 0 && lanExclusions.isNotEmpty()) {
                            applyIpv4RoutesWithLanExclusions(builder, lanExclusions)
                        } else if (route.address == "::" && route.prefixLength == 0) {
                            applyIpv6RoutesWithLocalExclusions(builder, lanExclusions.isNotEmpty())
                        } else {
                            builder.addRoute(route.address, route.prefixLength)
                        }
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    builder.setMetered(false)
                }

                val allowedPackages = computeAllowedTunnelPackages()
                for (pkg in allowedPackages) {
                    runCatching {
                        builder.addAllowedApplication(pkg)
                    }
                }
                appliedBypassPackages = allowedPackages
                appliedTunDnsServers = tunDnsList
                appliedExcludedLanSubnets = formatLanExclusionKeys(lanExclusions)

                val establishedTun = builder.establish()
                    ?: throw IllegalStateException("VpnService.Builder.establish() returned null")
                tunInterface = establishedTun
                val orderedPhys = selectOrderedPhysicalNetworks()
                if (orderedPhys.isNotEmpty()) {
                    runCatching { setUnderlyingNetworks(orderedPhys) }
                }

                val tunIn = FileInputStream(establishedTun.fileDescriptor)
                val tunOut = FileOutputStream(establishedTun.fileDescriptor)
                tunOutStream = tunOut

                val wgSession = WireGuardSession(config)
                activeWgSession = wgSession

                // 2. Open protected UDP socket & resolve endpoint
                val peerAddress = resolvePeerSocketAddressWithRetry(config, ztDns)
                activePeerAddress = peerAddress
                lastTunnelPhysicalNetwork = currentPhysicalNetwork ?: selectBestPhysicalNetwork()
                rebindUdpSocketAndReceiver(config, wgSession)

                // 3. Initiate WireGuard Handshake
                udpSocket?.let { sock ->
                    triggerHandshakeIfAllowed(wgSession, sock, peerAddress, force = true)
                }

                VpnStateManager.updateStatus(
                    status = TunnelStatus.CONNECTED,
                    message = "Connected • WireGuard",
                    handshakeEstablished = false
                )
                updateNotification("Connected • WireGuard")

                // 4. Continuous keepalive and connection watchdog (maintains 24/7 NAT hole punching & auto-healing)
                startContinuousKeepaliveAndWatchdog(wgSession)

                // 5. Zero-allocation Outbound TUN loop
                // Pre-allocate buffers once for the lifetime of the tunnel to eliminate GC churn
                val tunBuf = ByteArray(65535)
                val wgTxBuf = ByteArray(65535)
                val reusableTxDatagram = DatagramPacket(wgTxBuf, wgTxBuf.size)

                while (isActive) {
                    val readLen = try {
                        tunIn.read(tunBuf)
                    } catch (_: Exception) {
                        -1
                    }
                    if (readLen <= 0) {
                        if (readLen < 0) {
                            if (isActive && tunInterface != null) {
                                delay(500L)
                                throw IllegalStateException("TUN descriptor closed or EOF encountered")
                            }
                            break
                        }
                        continue
                    }

                    val currentState = VpnStateManager.state.value
                    val activeDnsCfg = currentState.zeroTrustDns
                    val forwarderIps = buildSet {
                        addAll(config.dnsServers)
                        if (activeDnsCfg.privateDnsServer.isNotBlank()) add(activeDnsCfg.privateDnsServer.trim())
                        addAll(activeDnsCfg.configuredIpv4List())
                        addAll(activeDnsCfg.configuredIpv6List())
                        add("1.1.1.1")
                        add("1.0.0.1")
                    }
                    val action = PacketInspector.inspectOutboundPacket(
                        packet = tunBuf,
                        length = readLen,
                        filterEngine = VpnStateManager.filterEngine,
                        dnsFilteringEnabled = currentState.dnsFilteringEnabled,
                        deepInspectionEnabled = currentState.deepInspectionEnabled,
                        useNxDomain = currentState.useNxDomain,
                        bypassedPackages = emptySet(),
                        dnsForwarderIps = forwarderIps,
                        dnsForwarderSourcePorts = ZeroTrustDnsResolver.activeUpstreamSourcePorts()
                    )

                    when (action) {
                        is PacketAction.DropAndReply -> {
                            if (action.replyPacket.isNotEmpty()) {
                                synchronized(tunWriteLock) {
                                    runCatching {
                                        tunOut.write(action.replyPacket)
                                    }
                                }
                            }
                            if (action.logEvent) {
                                VpnStateManager.recordTrafficEvent(
                                    target = action.targetHost,
                                    protocol = action.protocol,
                                    blocked = true,
                                    matchedRule = action.decision.matchedRule,
                                    sourceName = action.decision.sourceName
                                )
                            }
                        }
                        is PacketAction.ForwardLogged -> {
                            VpnStateManager.recordTrafficEvent(
                                target = action.targetHost,
                                protocol = action.protocol,
                                blocked = false,
                                matchedRule = action.decision.matchedRule,
                                sourceName = action.decision.sourceName
                            )

                            val dnsPayload = action.dnsQueryPayload
                            if (dnsPayload != null) {
                                val cachedDnsResp = ZeroTrustDnsResolver.getCachedDnsResponse(dnsPayload)
                                if (cachedDnsResp != null) {
                                    val ipUdpReply = PacketInspector.buildDnsReplyFromUpstream(
                                        originalPacket = tunBuf,
                                        length = readLen,
                                        dnsResponsePayload = cachedDnsResp
                                    )
                                    if (ipUdpReply != null) {
                                        synchronized(tunWriteLock) {
                                            runCatching { tunOut.write(ipUdpReply) }
                                        }
                                        VpnStateManager.addRxBytes(ipUdpReply.size)
                                    }
                                } else {
                                    val reqCopy = tunBuf.copyOf(readLen)
                                    val activeDnsCfg = currentState.zeroTrustDns
                                    launch(Dispatchers.IO) {
                                        dnsConcurrencyLimiter.withPermit {
                                            val dnsResp = ZeroTrustDnsResolver.resolveRawDnsQuery(
                                                context = applicationContext,
                                                dnsQueryPayload = dnsPayload,
                                                config = activeDnsCfg,
                                                vpnService = this@XPrivacyGuardService,
                                                wireGuardDnsServers = config.dnsServers,
                                                preferredNetwork = currentPhysicalNetwork
                                            )
                                            if (dnsResp != null) {
                                                val ipUdpReply = PacketInspector.buildDnsReplyFromUpstream(
                                                    originalPacket = reqCopy,
                                                    length = reqCopy.size,
                                                    dnsResponsePayload = dnsResp
                                                )
                                                if (ipUdpReply != null) {
                                                    synchronized(tunWriteLock) {
                                                        runCatching { tunOut.write(ipUdpReply) }
                                                    }
                                                    VpnStateManager.addRxBytes(ipUdpReply.size)
                                                }
                                            } else {
                                                val asyncWgBuf = ByteArray(reqCopy.size + 64)
                                                forwardPacketOverWireGuard(
                                                    wgSession = wgSession,
                                                    ipPacket = reqCopy,
                                                    length = reqCopy.size,
                                                    wgTxBuf = asyncWgBuf,
                                                    txDatagram = null
                                                )
                                            }
                                        }
                                    }
                                }
                            } else {
                                forwardPacketOverWireGuard(
                                    wgSession = wgSession,
                                    ipPacket = tunBuf,
                                    length = readLen,
                                    wgTxBuf = wgTxBuf,
                                    txDatagram = reusableTxDatagram
                                )
                            }
                        }
                        PacketAction.ForwardToWireGuard -> {
                            forwardPacketOverWireGuard(
                                wgSession = wgSession,
                                ipPacket = tunBuf,
                                length = readLen,
                                wgTxBuf = wgTxBuf,
                                txDatagram = reusableTxDatagram
                            )
                        }
                    }
                }

                keepaliveWatchdogJob?.cancel()
                udpRxJob?.cancel()
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                if (!isActive || tunnelGeneration.get() != gen) throw CancellationException("Tunnel replaced or stopped", e)
                VpnStateManager.updateStatus(
                    status = TunnelStatus.CONNECTING,
                    message = "Reconnecting...",
                    handshakeEstablished = false
                )
                updateNotification("Reconnecting...")
                delay(2500L)
                if (isActive && tunnelGeneration.get() == gen) {
                    awaitPhysicalNetwork()
                    if (isActive && tunnelGeneration.get() == gen) {
                        startVpnTunnel()
                    }
                }
            }
        }
    }

    private fun parseIpAndPrefix(raw: String, defaultPrefix: Int): Pair<String, Int>? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val slash = trimmed.indexOf('/')
        return if (slash > 0) {
            val ip = trimmed.substring(0, slash).trim()
            val prefix = trimmed.substring(slash + 1).trim().toIntOrNull() ?: defaultPrefix
            if (ip.isNotEmpty()) ip to prefix else null
        } else {
            trimmed to defaultPrefix
        }
    }

    /**
     * Returns local RFC1918 IPv4 subnets (e.g. 192.168.88.0/24) directly attached to the active
     * physical Wi-Fi or Ethernet interface so local LAN services (such as xServer on 192.168.88.50)
     * connect directly over Wi-Fi instead of hairpinning out through Cloudflare and back.
     * Returns an empty list on Cellular (5G/LTE) so remote traffic still reaches xServer via Zero Trust.
     */
    private fun computeLocalWifiLanExclusions(physicalNet: Network?): List<Pair<InetAddress, Int>> {
        val net = physicalNet ?: return emptyList()
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return emptyList()
        val caps = runCatching { cm.getNetworkCapabilities(net) }.getOrNull() ?: return emptyList()
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return emptyList()
        val isLocalLan = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        if (!isLocalLan) return emptyList()

        val linkProps = runCatching { cm.getLinkProperties(net) }.getOrNull() ?: return emptyList()
        val seen = LinkedHashSet<String>()
        val result = mutableListOf<Pair<InetAddress, Int>>()
        for (la in linkProps.linkAddresses) {
            val addr = la.address as? Inet4Address ?: continue
            if (!addr.isSiteLocalAddress) continue
            val prefixLen = la.prefixLength
            if (prefixLen !in 8..30) continue
            val bytes = addr.address
            val ipInt = ((bytes[0].toInt() and 0xFF) shl 24) or
                ((bytes[1].toInt() and 0xFF) shl 16) or
                ((bytes[2].toInt() and 0xFF) shl 8) or
                (bytes[3].toInt() and 0xFF)
            val mask = (-1 shl (32 - prefixLen))
            val netInt = ipInt and mask
            val netBytes = byteArrayOf(
                ((netInt ushr 24) and 0xFF).toByte(),
                ((netInt ushr 16) and 0xFF).toByte(),
                ((netInt ushr 8) and 0xFF).toByte(),
                (netInt and 0xFF).toByte()
            )
            val netAddr = InetAddress.getByAddress(netBytes)
            val key = "${netAddr.hostAddress}/$prefixLen"
            if (seen.add(key)) {
                result.add(netAddr to prefixLen)
            }
        }
        if (result.isNotEmpty()) {
            // Also exclude IPv4 link-local (Bluetooth PAN / Wi-Fi Direct), multicast (mDNS/SSDP/CDP),
            // and limited broadcast so local discovery and Phone Link remain direct on Home Wi-Fi.
            val localExtras = listOf(
                InetAddress.getByAddress(byteArrayOf(169.toByte(), 254.toByte(), 0, 0)) to 16,
                InetAddress.getByAddress(byteArrayOf(224.toByte(), 0, 0, 0)) to 4,
                InetAddress.getByAddress(byteArrayOf(255.toByte(), 255.toByte(), 255.toByte(), 255.toByte())) to 32
            )
            for ((extraAddr, extraPrefix) in localExtras) {
                val key = "${extraAddr.hostAddress}/$extraPrefix"
                if (seen.add(key)) {
                    result.add(extraAddr to extraPrefix)
                }
            }
        }
        return result
    }

    private fun applyIpv6RoutesWithLocalExclusions(
        builder: Builder,
        hasLocalLan: Boolean
    ) {
        builder.addRoute("::", 0)
        if (hasLocalLan && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val v6LocalExclusions = listOf(
                "fe80::" to 10,
                "fc00::" to 7,
                "ff00::" to 8
            )
            for ((addrStr, prefixLen) in v6LocalExclusions) {
                runCatching {
                    builder.excludeRoute(IpPrefix(InetAddress.getByName(addrStr), prefixLen))
                }
            }
        }
    }

    private fun formatLanExclusionKeys(exclusions: List<Pair<InetAddress, Int>>): Set<String> =
        exclusions.map { (addr, prefix) -> "${addr.hostAddress}/$prefix" }.toSet()

    private fun applyIpv4RoutesWithLanExclusions(
        builder: Builder,
        exclusions: List<Pair<InetAddress, Int>>
    ) {
        if (exclusions.isEmpty()) {
            builder.addRoute("0.0.0.0", 0)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            builder.addRoute("0.0.0.0", 0)
            for ((netAddr, prefixLen) in exclusions) {
                runCatching { builder.excludeRoute(IpPrefix(netAddr, prefixLen)) }
            }
            return
        }
        // Fallback for Android 8–12: subtract excluded LAN prefixes from 0.0.0.0/0
        var ranges = listOf(0L to 0xFFFFFFFFL)
        for ((netAddr, prefixLen) in exclusions) {
            val b = netAddr.address
            val start = (((b[0].toLong() and 0xFF) shl 24) or
                ((b[1].toLong() and 0xFF) shl 16) or
                ((b[2].toLong() and 0xFF) shl 8) or
                (b[3].toLong() and 0xFF))
            val size = 1L shl (32 - prefixLen)
            val end = start + size - 1L
            val next = mutableListOf<Pair<Long, Long>>()
            for ((rStart, rEnd) in ranges) {
                if (end < rStart || start > rEnd) {
                    next.add(rStart to rEnd)
                } else {
                    if (rStart < start) next.add(rStart to (start - 1L))
                    if (rEnd > end) next.add((end + 1L) to rEnd)
                }
            }
            ranges = next
        }
        for ((rStart, rEnd) in ranges) {
            var cur = rStart
            while (cur <= rEnd) {
                var maxSize = 1L
                while ((cur and maxSize) == 0L && cur + (maxSize shl 1) - 1L <= rEnd && maxSize < (1L shl 31)) {
                    maxSize = maxSize shl 1
                }
                val prefixLen = 32 - java.lang.Long.numberOfTrailingZeros(maxSize)
                val ipBytes = byteArrayOf(
                    ((cur ushr 24) and 0xFF).toByte(),
                    ((cur ushr 16) and 0xFF).toByte(),
                    ((cur ushr 8) and 0xFF).toByte(),
                    (cur and 0xFF).toByte()
                )
                runCatching { builder.addRoute(InetAddress.getByAddress(ipBytes), prefixLen) }
                cur += maxSize
            }
        }
    }

    /**
     * Runs the Cloudflare One MASQUE transport via Aether's native raw-IP CONNECT-IP engine.
     * Preserves PacketInspector, ad blocking, Zero Trust DNS, per-app firewall, traffic counters,
     * network migration, and fail-closed behavior (TUN stays held during reconnects so traffic
     * never leaks to the physical network).
     */
    private fun startAetherMasqueTunnel(gen: Long) {
        tunnelJob = serviceScope.launch {
            var transport: AetherMasqueTransport? = null
            try {
                // Clean up any previous WireGuard or Aether transport cleanly first
                runCatching { udpSocket?.close() }
                udpSocket = null
                activeWgSession = null
                activeAetherTransport?.let { prev ->
                    activeAetherTransport = null
                    runCatching { prev.disconnect() }
                }

                VpnStateManager.ensureInitialized(applicationContext)
                var cfConfig = VpnStateManager.loadCloudflareOneConfig(applicationContext)
                if (!cfConfig.canConnect && !VpnStateManager.hasPersistedAetherIdentity(applicationContext, cfConfig)) {
                    VpnStateManager.updateStatus(
                        status = TunnelStatus.ERROR,
                        message = "Sign in to Cloudflare One in Tunnel settings.",
                        handshakeEstablished = false
                    )
                    updateNotification("Not signed in")
                    return@launch
                }

                // Wait for a physical network before dialing so we never spin while offline
                val dialPhysicalNetwork = awaitPhysicalNetwork()
                if (!isActive || tunnelGeneration.get() != gen) return@launch
                lastTunnelPhysicalNetwork = dialPhysicalNetwork

                VpnStateManager.updateStatus(
                    status = TunnelStatus.CONNECTING,
                    message = "Connecting to Zero Trust...",
                    handshakeEstablished = false
                )
                updateNotification("Connecting to Zero Trust...")

                val createdTransport = AetherMasqueTransport(
                    vpnService = this@XPrivacyGuardService,
                    identityDir = VpnStateManager.aetherIdentityDir(applicationContext),
                    config = cfConfig,
                    onStatusUpdate = { msg ->
                        if (tunnelGeneration.get() == gen) {
                            VpnStateManager.updateStatus(
                                status = TunnelStatus.CONNECTING,
                                message = msg,
                                handshakeEstablished = false
                            )
                        }
                    },
                    onIdentityOpened = { identity ->
                        val enrolledCf = cfConfig.copy(
                            deviceId = identity.deviceId.ifBlank { cfConfig.deviceId },
                            organization = identity.organization.ifBlank { cfConfig.organization },
                            ipv4Address = identity.ipv4.ifBlank { cfConfig.ipv4Address },
                            ipv6Address = identity.ipv6.ifBlank { cfConfig.ipv6Address },
                            assignedEndpoint = identity.assignedEndpoint.ifBlank { cfConfig.assignedEndpoint },
                            certIssuedAt = if (identity.certIssuedAt > 0L) identity.certIssuedAt else cfConfig.certIssuedAt
                        )
                        cfConfig = enrolledCf
                        VpnStateManager.saveCloudflareOneConfig(applicationContext, enrolledCf)
                    }
                )
                transport = createdTransport
                activeAetherTransport = createdTransport

                withTransitionWakeLock("MasqueConnect") {
                    createdTransport.connect().getOrThrow()
                }
                if (!isActive || tunnelGeneration.get() != gen) {
                    runCatching { createdTransport.disconnect() }
                    return@launch
                }
                val session = createdTransport.sessionInfo
                    ?: throw IllegalStateException("MASQUE session established without metadata")

                // Persist updated Cloudflare identity / assigned addresses for reboot recovery
                val updatedCf = cfConfig.copy(
                    deviceId = session.identity.deviceId.ifBlank { cfConfig.deviceId },
                    organization = session.identity.organization.ifBlank { cfConfig.organization },
                    ipv4Address = session.ipv4.ifBlank { cfConfig.ipv4Address },
                    ipv6Address = session.ipv6.ifBlank { cfConfig.ipv6Address },
                    assignedEndpoint = session.peer.ifBlank { cfConfig.assignedEndpoint },
                    certIssuedAt = if (session.identity.certIssuedAt > 0L) session.identity.certIssuedAt else cfConfig.certIssuedAt
                )
                VpnStateManager.saveCloudflareOneConfig(applicationContext, updatedCf)

                // Atomically replace Android TUN interface AFTER transport is live (fail-closed)
                val previousTun = tunInterface
                val ztDns = VpnStateManager.state.value.zeroTrustDns
                val builder = Builder()
                    .setSession("xServer Zero Trust")
                    .setMtu(session.mtu)

                val v4Pair = parseIpAndPrefix(session.ipv4, 32) ?: ("172.16.0.2" to 32)
                builder.addAddress(v4Pair.first, v4Pair.second)
                val activePhysNet = currentPhysicalNetwork ?: selectBestPhysicalNetwork() ?: dialPhysicalNetwork
                lastTunnelPhysicalNetwork = activePhysNet
                val lanExclusions = computeLocalWifiLanExclusions(activePhysNet)
                applyIpv4RoutesWithLanExclusions(builder, lanExclusions)

                val v6Pair = parseIpAndPrefix(session.ipv6, 128)
                if (v6Pair != null) {
                    runCatching {
                        builder.addAddress(v6Pair.first, v6Pair.second)
                        applyIpv6RoutesWithLocalExclusions(builder, lanExclusions.isNotEmpty())
                    }
                }

                val tunDnsList = computeTunDnsServers(ztDns, null)
                for (dns in tunDnsList) {
                    runCatching { builder.addDnsServer(dns) }
                }
                if (ztDns.privateDomain.isNotBlank()) {
                    runCatching {
                        builder.addSearchDomain(ztDns.privateDomain.trim().trim('.'))
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    builder.setMetered(false)
                }

                val allowedPackages = computeAllowedTunnelPackages()
                for (pkg in allowedPackages) {
                    runCatching { builder.addAllowedApplication(pkg) }
                }
                appliedBypassPackages = allowedPackages
                appliedTunDnsServers = tunDnsList
                appliedExcludedLanSubnets = formatLanExclusionKeys(lanExclusions)

                val establishedTun = builder.establish()
                    ?: throw IllegalStateException("VpnService.Builder.establish() returned null")
                tunInterface = establishedTun
                runCatching { previousTun?.close() }
                lastTunnelPhysicalNetwork = activePhysNet
                val orderedPhys = selectOrderedPhysicalNetworks()
                runCatching {
                    setUnderlyingNetworks(if (orderedPhys.isNotEmpty()) orderedPhys else arrayOf(activePhysNet))
                }

                val tunIn = FileInputStream(establishedTun.fileDescriptor)
                val tunOut = FileOutputStream(establishedTun.fileDescriptor)
                tunOutStream = tunOut

                val nowMs = System.currentTimeMillis()
                lastRxEpochMs.set(nowMs)
                lastTxEpochMs.set(nowMs)

                val carrierLabel = if (session.carrier == "h2") "MASQUE H2" else "MASQUE H3"
                VpnStateManager.updateStatus(
                    status = TunnelStatus.CONNECTED,
                    message = "Connected • $carrierLabel",
                    handshakeEstablished = true,
                    lastHandshakeMs = nowMs
                )
                updateNotification("Connected • $carrierLabel")

                val tunnelDied = AtomicBoolean(false)

                // Start MASQUE health watchdog to detect silent NAT drops & heal automatically
                startMasqueWatchdog(gen, createdTransport, tunnelDied)

                // Downlink loop: Cloudflare MASQUE raw IP packets -> Android TUN
                aetherRxJob?.cancel()
                aetherRxJob = serviceScope.launch(Dispatchers.IO) {
                    val rxBuf = ByteArray(65535)
                    while (isActive && !tunnelDied.get() && tunnelGeneration.get() == gen) {
                        val n = createdTransport.receivePacketInto(rxBuf, timeoutMs = 250)
                        if (n > 0) {
                            lastRxEpochMs.set(System.currentTimeMillis())
                            VpnStateManager.addRxBytes(n)
                            synchronized(tunWriteLock) {
                                runCatching { tunOut.write(rxBuf, 0, n) }
                            }
                        } else if (n < 0) {
                            if (tunnelDied.compareAndSet(false, true) &&
                                isActive &&
                                tunnelGeneration.get() == gen &&
                                activeAetherTransport === createdTransport &&
                                VpnStateManager.state.value.activeTransport == TunnelProtocol.CLOUDFLARE_MASQUE
                            ) {
                                // Proactively reconnect even if uplink tunIn.read() is blocked idle
                                serviceScope.launch {
                                    VpnStateManager.updateStatus(
                                        status = TunnelStatus.CONNECTING,
                                        message = "Reconnecting...",
                                        handshakeEstablished = false
                                    )
                                    updateNotification("Reconnecting...")
                                    delay(1200L)
                                    if (tunnelGeneration.get() == gen &&
                                        activeAetherTransport === createdTransport &&
                                        VpnStateManager.state.value.activeTransport == TunnelProtocol.CLOUDFLARE_MASQUE
                                    ) {
                                        awaitPhysicalNetwork()
                                        if (tunnelGeneration.get() == gen) {
                                            startVpnTunnel()
                                        }
                                    }
                                }
                            }
                            break
                        }
                    }
                }

                // Uplink loop: Android TUN -> PacketInspector -> ZeroTrustDNS / AdBlock -> MASQUE raw IP
                val tunBuf = ByteArray(65535)
                while (isActive && !tunnelDied.get()) {
                    val readLen = try {
                        tunIn.read(tunBuf)
                    } catch (_: Exception) {
                        -1
                    }
                    if (readLen <= 0) {
                        if (readLen < 0) {
                            if (isActive && tunInterface != null) {
                                delay(500L)
                                throw IllegalStateException("TUN descriptor closed or EOF encountered")
                            }
                            break
                        }
                        continue
                    }

                    if (tunnelDied.get() || !createdTransport.isAlive()) {
                        // Fail-closed: drop packet and trigger reconnect without releasing TUN
                        throw IllegalStateException("MASQUE tunnel disconnected")
                    }

                    val currentState = VpnStateManager.state.value
                    val activeDnsCfg = currentState.zeroTrustDns
                    val forwarderIps = buildSet {
                        if (activeDnsCfg.privateDnsServer.isNotBlank()) add(activeDnsCfg.privateDnsServer.trim())
                        addAll(activeDnsCfg.configuredIpv4List())
                        addAll(activeDnsCfg.configuredIpv6List())
                        add("1.1.1.1")
                        add("1.0.0.1")
                    }
                    val action = PacketInspector.inspectOutboundPacket(
                        packet = tunBuf,
                        length = readLen,
                        filterEngine = VpnStateManager.filterEngine,
                        dnsFilteringEnabled = currentState.dnsFilteringEnabled,
                        deepInspectionEnabled = currentState.deepInspectionEnabled,
                        useNxDomain = currentState.useNxDomain,
                        bypassedPackages = emptySet(),
                        dnsForwarderIps = forwarderIps,
                        dnsForwarderSourcePorts = ZeroTrustDnsResolver.activeUpstreamSourcePorts()
                    )

                    when (action) {
                        is PacketAction.DropAndReply -> {
                            if (action.replyPacket.isNotEmpty()) {
                                synchronized(tunWriteLock) {
                                    runCatching { tunOut.write(action.replyPacket) }
                                }
                            }
                            if (action.logEvent) {
                                VpnStateManager.recordTrafficEvent(
                                    target = action.targetHost,
                                    protocol = action.protocol,
                                    blocked = true,
                                    matchedRule = action.decision.matchedRule,
                                    sourceName = action.decision.sourceName
                                )
                            }
                        }
                        is PacketAction.ForwardLogged -> {
                            VpnStateManager.recordTrafficEvent(
                                target = action.targetHost,
                                protocol = action.protocol,
                                blocked = false,
                                matchedRule = action.decision.matchedRule,
                                sourceName = action.decision.sourceName
                            )
                            val dnsPayload = action.dnsQueryPayload
                            if (dnsPayload != null) {
                                val cachedDnsResp = ZeroTrustDnsResolver.getCachedDnsResponse(dnsPayload)
                                if (cachedDnsResp != null) {
                                    val ipUdpReply = PacketInspector.buildDnsReplyFromUpstream(
                                        originalPacket = tunBuf,
                                        length = readLen,
                                        dnsResponsePayload = cachedDnsResp
                                    )
                                    if (ipUdpReply != null) {
                                        synchronized(tunWriteLock) {
                                            runCatching { tunOut.write(ipUdpReply) }
                                        }
                                        VpnStateManager.addRxBytes(ipUdpReply.size)
                                    }
                                } else {
                                    val reqCopy = tunBuf.copyOf(readLen)
                                    launch(Dispatchers.IO) {
                                        dnsConcurrencyLimiter.withPermit {
                                            val dnsResp = ZeroTrustDnsResolver.resolveRawDnsQuery(
                                                context = applicationContext,
                                                dnsQueryPayload = dnsPayload,
                                                config = activeDnsCfg,
                                                vpnService = this@XPrivacyGuardService,
                                                wireGuardDnsServers = emptyList(),
                                                preferredNetwork = currentPhysicalNetwork
                                            )
                                            if (dnsResp != null) {
                                                val ipUdpReply = PacketInspector.buildDnsReplyFromUpstream(
                                                    originalPacket = reqCopy,
                                                    length = reqCopy.size,
                                                    dnsResponsePayload = dnsResp
                                                )
                                                if (ipUdpReply != null) {
                                                    synchronized(tunWriteLock) {
                                                        runCatching { tunOut.write(ipUdpReply) }
                                                    }
                                                    VpnStateManager.addRxBytes(ipUdpReply.size)
                                                }
                                            } else {
                                                val rc = createdTransport.sendPacketFast(reqCopy, reqCopy.size)
                                                if (rc == 0) {
                                                    lastTxEpochMs.set(System.currentTimeMillis())
                                                    VpnStateManager.addTxBytes(reqCopy.size)
                                                }
                                            }
                                        }
                                    }
                                }
                            } else {
                                val rc = createdTransport.sendPacketFast(tunBuf, readLen)
                                if (rc == 0) {
                                    lastTxEpochMs.set(System.currentTimeMillis())
                                    VpnStateManager.addTxBytes(readLen)
                                } else if (rc == -1) {
                                    throw IllegalStateException("MASQUE tunnel closed during send")
                                }
                            }
                        }
                        PacketAction.ForwardToWireGuard -> {
                            val rc = createdTransport.sendPacketFast(tunBuf, readLen)
                            if (rc == 0) {
                                lastTxEpochMs.set(System.currentTimeMillis())
                                VpnStateManager.addTxBytes(readLen)
                            } else if (rc == -1) {
                                throw IllegalStateException("MASQUE tunnel closed during send")
                            }
                        }
                    }
                }

                aetherRxJob?.cancel()
                masqueWatchdogJob?.cancel()
                if (tunnelDied.get() && isActive && tunnelGeneration.get() == gen) {
                    throw IllegalStateException("MASQUE downlink ended")
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                if (!isActive || tunnelGeneration.get() != gen) {
                    throw CancellationException("MASQUE tunnel replaced or stopped", e)
                }
                if (activeAetherTransport === transport) {
                    aetherRxJob?.cancel()
                    masqueWatchdogJob?.cancel()
                    activeAetherTransport = null
                    transport?.let { dead -> runCatching { dead.disconnect() } }
                }
                VpnStateManager.updateStatus(
                    status = TunnelStatus.CONNECTING,
                    message = "Reconnecting...",
                    handshakeEstablished = false
                )
                updateNotification("Reconnecting...")
                delay(2500L)
                if (isActive &&
                    tunnelGeneration.get() == gen &&
                    VpnStateManager.state.value.activeTransport == TunnelProtocol.CLOUDFLARE_MASQUE
                ) {
                    awaitPhysicalNetwork()
                    if (isActive && tunnelGeneration.get() == gen) {
                        startVpnTunnel()
                    }
                }
            }
        }
    }

    private fun startMasqueWatchdog(
        gen: Long,
        transport: AetherMasqueTransport,
        tunnelDied: AtomicBoolean
    ) {
        masqueWatchdogJob?.cancel()
        masqueWatchdogJob = serviceScope.launch(Dispatchers.IO) {
            var staleProbeCount = 0
            while (isActive && !tunnelDied.get() && tunnelGeneration.get() == gen) {
                if (!isScreenInteractive.get()) {
                    delay(45_000L)
                    continue
                }
                delay(15_000L)
                if (!isActive || tunnelDied.get() || tunnelGeneration.get() != gen) break
                if (!hasPhysicalNetwork.get()) continue

                if (!transport.isAlive()) {
                    if (tunnelDied.compareAndSet(false, true) && tunnelGeneration.get() == gen) {
                        startVpnTunnel()
                    }
                    break
                }

                val now = System.currentTimeMillis()
                val txTs = lastTxEpochMs.get()
                val rxTs = lastRxEpochMs.get()
                // Detect silent NAT blackhole: outbound packets sent recently, zero downlink for >30s
                if (txTs > rxTs && now - rxTs > 30_000L && now - txTs < 25_000L) {
                    staleProbeCount++
                    val migrated = runCatching { transport.migrateNetwork() }.getOrDefault(false)
                    if (!migrated || staleProbeCount >= 2) {
                        if (tunnelDied.compareAndSet(false, true) && tunnelGeneration.get() == gen) {
                            startVpnTunnel()
                        }
                        break
                    }
                } else {
                    staleProbeCount = 0
                }
            }
        }
    }

    private fun triggerHandshakeIfAllowed(
        wgSession: WireGuardSession,
        socket: DatagramSocket,
        peerAddress: InetSocketAddress,
        force: Boolean = false
    ) {
        if (!hasPhysicalNetwork.get() && !force) return

        val now = System.currentTimeMillis()
        val failures = unansweredHandshakeCount.get()
        // Exponential backoff (4s -> 8s -> 16s -> 30s max) so unreachable networks never spin CPU/radio
        val backoffMs = (HANDSHAKE_BASE_RETRY_MS shl failures.coerceAtMost(3))
            .coerceAtMost(HANDSHAKE_MAX_BACKOFF_MS)

        val prevAttempt = lastHandshakeAttemptEpochMs.get()
        if (!force && now - prevAttempt < backoffMs) return
        if (!lastHandshakeAttemptEpochMs.compareAndSet(prevAttempt, now) && !force) return
        lastHandshakeAttemptEpochMs.set(now)

        val currentFailures = unansweredHandshakeCount.incrementAndGet()
        // If 2 consecutive handshakes went unanswered, recreate the UDP socket asynchronously
        // and immediately send a fresh handshake on the newly bound socket.
        if (currentFailures >= 2 && activeConfig != null && isRebindingSocket.compareAndSet(false, true)) {
            val cfg = activeConfig!!
            serviceScope.launch(Dispatchers.IO) {
                try {
                    rebindUdpSocketAndReceiver(cfg, wgSession)
                    val freshSock = udpSocket
                    val freshPeer = activePeerAddress ?: peerAddress
                    if (freshSock != null && !freshSock.isClosed) {
                        sendHandshakePacket(wgSession, freshSock, freshPeer)
                    }
                } finally {
                    isRebindingSocket.set(false)
                }
            }
            return
        }
        val activeSock = udpSocket ?: socket
        val targetPeer = activePeerAddress ?: peerAddress
        sendHandshakePacket(wgSession, activeSock, targetPeer)
    }

    private fun sendHandshakePacket(
        wgSession: WireGuardSession,
        socket: DatagramSocket,
        targetPeer: InetSocketAddress
    ) {
        val now = System.currentTimeMillis()
        runCatching {
            protect(socket)
            val initPkt = wgSession.createHandshakeInitiation()
            socket.send(DatagramPacket(initPkt, initPkt.size, targetPeer))
            lastTxEpochMs.set(now)
            VpnStateManager.addTxBytes(initPkt.size)
        }
    }

    private fun forwardPacketOverWireGuard(
        wgSession: WireGuardSession,
        ipPacket: ByteArray,
        length: Int,
        wgTxBuf: ByteArray,
        txDatagram: DatagramPacket?
    ) {
        val socket = udpSocket ?: return
        val peerAddress = activePeerAddress ?: return
        val now = System.currentTimeMillis()
        val rxAge = now - lastRxEpochMs.get()
        val hsAge = now - wgSession.lastHandshakeEpochMs

        if (!wgSession.isEstablished || hsAge > REJECT_AFTER_TIME_MS) {
            if (pendingOutboundPackets.size >= MAX_PENDING_PACKETS) {
                pendingOutboundPackets.poll()
            }
            pendingOutboundPackets.offer(ipPacket.copyOf(length))
            // Never pass force = true per packet — let the handshake cooldown prevent a packet
            // burst from spamming initiations and triggering premature socket rebinds.
            triggerHandshakeIfAllowed(wgSession, socket, peerAddress, force = false)
            return
        }

        if (hsAge > REKEY_AFTER_TIME_MS || (rxAge > STALE_RX_REHANDSHAKE_MS && hsAge > STALE_RX_REHANDSHAKE_MS)) {
            triggerHandshakeIfAllowed(wgSession, socket, peerAddress)
        }

        val encryptedLen = wgSession.encryptTransportPacketInto(ipPacket, length, wgTxBuf)
        if (encryptedLen <= 0) return

        runCatching {
            if (txDatagram != null) {
                txDatagram.setData(wgTxBuf, 0, encryptedLen)
                txDatagram.socketAddress = peerAddress
                socket.send(txDatagram)
            } else {
                socket.send(DatagramPacket(wgTxBuf, encryptedLen, peerAddress))
            }
            lastTxEpochMs.set(now)
            VpnStateManager.addTxBytes(encryptedLen)
        }
    }

    private fun stopVpnTunnel(resetStatus: Boolean = true) {
        tunnelGeneration.incrementAndGet()
        unregisterPhysicalNetworkCallback()
        firewallReloadJob?.cancel()
        firewallReloadJob = null
        masqueWatchdogJob?.cancel()
        masqueWatchdogJob = null
        keepaliveWatchdogJob?.cancel()
        keepaliveWatchdogJob = null
        udpRxJob?.cancel()
        udpRxJob = null
        aetherRxJob?.cancel()
        aetherRxJob = null
        tunnelJob?.cancel()
        tunnelJob = null
        val aether = activeAetherTransport
        activeAetherTransport = null
        if (aether != null) {
            // Stop Aether raw tunnel, free identity, and unregister VpnService.protect() callback
            kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                runCatching { aether.disconnect() }
            }
        }
        appliedTransport = null
        activeWgSession = null
        activePeerAddress = null
        activeConfig = null
        tunOutStream = null
        appliedBypassPackages = emptySet()
        appliedTunDnsServers = emptyList()
        appliedExcludedLanSubnets = emptySet()
        lastTunnelPhysicalNetwork = null
        pendingOutboundPackets.clear()
        ZeroTrustDnsResolver.closePersistentConnections()
        runCatching { udpSocket?.close() }
        udpSocket = null
        runCatching { tunInterface?.close() }
        tunInterface = null
        if (VpnStateManager.activeVpnService === this) {
            VpnStateManager.activeVpnService = null
        }

        VpnStateManager.flushTelemetryNow()
        if (resetStatus) {
            VpnStateManager.updateStatus(
                status = TunnelStatus.DISCONNECTED,
                message = "Disconnected",
                handshakeEstablished = false
            )
        }
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
    }

    override fun onRevoke() {
        stopVpnTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        screenStateReceiver?.let {
            runCatching { unregisterReceiver(it) }
            screenStateReceiver = null
        }
        stopVpnTunnel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun warmSessionIfStale(forceMigrateMasque: Boolean = false) {
        if (!hasPhysicalNetwork.get()) return

        if (appliedTransport == TunnelProtocol.CLOUDFLARE_MASQUE || activeAetherTransport != null) {
            val aether = activeAetherTransport
            if (aether == null || !aether.isAlive()) {
                startVpnTunnel()
                return
            }
            val rxAge = System.currentTimeMillis() - lastRxEpochMs.get()
            if ((forceMigrateMasque && rxAge > 20_000L) || rxAge > STALE_RX_REHANDSHAKE_MS) {
                ZeroTrustDnsResolver.onNetworkChanged()
                if (!runCatching { aether.migrateNetwork() }.getOrDefault(false)) {
                    startVpnTunnel()
                }
            }
            return
        }

        val sock = udpSocket ?: return
        val peer = activePeerAddress ?: return
        val session = activeWgSession ?: return

        val now = System.currentTimeMillis()
        val hsAge = now - session.lastHandshakeEpochMs
        val rxAge = now - lastRxEpochMs.get()
        val lastAttemptAge = now - lastHandshakeAttemptEpochMs.get()
        if (!session.isEstablished ||
            hsAge > REKEY_AFTER_TIME_MS ||
            rxAge > STALE_RX_REHANDSHAKE_MS
        ) {
            if (lastAttemptAge >= HANDSHAKE_BASE_RETRY_MS) {
                unansweredHandshakeCount.set(0)
                lastHandshakeAttemptEpochMs.set(0L)
                triggerHandshakeIfAllowed(session, sock, peer, force = true)
            }
        } else {
            // Session is fresh; send a single 32-byte keepalive to refresh carrier/router NAT pinhole
            sendWireGuardKeepalive(sock, peer, session)
        }
    }

    private fun registerScreenStateReceiver() {
        if (screenStateReceiver != null) return
        screenStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON,
                    Intent.ACTION_USER_PRESENT -> {
                        val wasScreenOff = !isScreenInteractive.getAndSet(true)
                        VpnStateManager.updateScreenState(true, effectiveScreenOnKeepaliveSec())
                        val session = activeWgSession
                        if (session != null) {
                            // Restart watchdog immediately into warm screen-on cadence when waking from sleep
                            if (wasScreenOff) {
                                startContinuousKeepaliveAndWatchdog(session)
                            }
                            serviceScope.launch(Dispatchers.IO) {
                                warmSessionIfStale()
                            }
                        } else if (appliedTransport == TunnelProtocol.CLOUDFLARE_MASQUE || activeAetherTransport != null) {
                            serviceScope.launch(Dispatchers.IO) {
                                warmSessionIfStale(forceMigrateMasque = wasScreenOff)
                            }
                        }
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        isScreenInteractive.set(false)
                        VpnStateManager.updateScreenState(false, 0)
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenStateReceiver, filter)
    }

    /**
     * Screen-aware ultra-low-power tunnel maintainer:
     * - Screen ON: keeps the NAT pinhole & Cloudflare Zero Trust session warm with a lightweight
     *   25s keepalive (32 bytes) and proactive seamless rekeying at 120s so apps like xAssistant
     *   connect instantaneously with zero handshake delay.
     * - Screen OFF: drops to 0s keepalive and sends zero periodic rehandshakes so the cellular/Wi-Fi
     *   modem stays in deep sleep (0% idle radio/CPU drain). The TUN stays open in the kernel; any
     *   background traffic or screen-on event immediately triggers a single 1-RTT (~30-50ms) handshake.
     */
    private fun startContinuousKeepaliveAndWatchdog(wgSession: WireGuardSession) {
        keepaliveWatchdogJob?.cancel()
        val activeKaSec = effectiveScreenOnKeepaliveSec()
        VpnStateManager.updateScreenState(isScreenInteractive.get(), activeKaSec)
        keepaliveWatchdogJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                val screenOn = isScreenInteractive.get()
                if (!screenOn) {
                    // Ultra-low-power idle: never wake the radio while screen is off.
                    // Wakes immediately when ACTION_SCREEN_ON restarts this watchdog or when
                    // an app sends a packet through TUN.
                    delay(60_000L)
                    continue
                }

                val keepaliveSec = effectiveScreenOnKeepaliveSec()
                val intervalMs = when {
                    !wgSession.isEstablished -> HANDSHAKE_BASE_RETRY_MS
                    else -> (keepaliveSec * 1000L).coerceAtMost(25_000L)
                }
                delay(intervalMs)
                if (!isActive || !isScreenInteractive.get()) continue

                val sock = udpSocket ?: continue
                val peer = activePeerAddress ?: continue
                val now = System.currentTimeMillis()

                if (wgSession.isEstablished) {
                    val txAge = now - lastTxEpochMs.get()
                    val rxAge = now - lastRxEpochMs.get()
                    val hsAge = now - wgSession.lastHandshakeEpochMs
                    if (hsAge > REKEY_AFTER_TIME_MS ||
                        (rxAge > STALE_RX_REHANDSHAKE_MS && hsAge > STALE_RX_REHANDSHAKE_MS)
                    ) {
                        triggerHandshakeIfAllowed(wgSession, sock, peer)
                    } else if (txAge >= keepaliveSec * 1000L) {
                        sendWireGuardKeepalive(sock, peer, wgSession)
                    }
                } else {
                    triggerHandshakeIfAllowed(wgSession, sock, peer)
                }
            }
        }
    }

    private fun sendWireGuardKeepalive(
        sock: DatagramSocket,
        peer: InetSocketAddress,
        session: WireGuardSession
    ) {
        if (!session.isEstablished) return
        val kaBuf = ByteArray(32)
        val kaLen = session.encryptTransportPacketInto(WireGuardCrypto.EMPTY_BYTES, 0, kaBuf)
        if (kaLen > 0) {
            runCatching {
                val kaPacket = DatagramPacket(kaBuf, kaLen, peer)
                sock.send(kaPacket)
                lastTxEpochMs.set(System.currentTimeMillis())
            }
        }
    }

    private fun startForegroundWithNotification(contentText: String) {
        createNotificationChannel()
        val notification = buildNotification(contentText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
                )
            } catch (_: Exception) {
                runCatching {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                }
            }
        } else {
            runCatching { startForeground(NOTIFICATION_ID, notification) }
        }
    }

    private fun updateNotification(contentText: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    private fun buildNotification(contentText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, XPrivacyGuardService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val status = VpnStateManager.state.value.status
        val (accentColor, largeIconRes) = when (status) {
            TunnelStatus.CONNECTED -> 0xFF22C55E.toInt() to R.drawable.ic_shield_connected
            TunnelStatus.CONNECTING -> 0xFFF38020.toInt() to R.drawable.ic_shield
            TunnelStatus.DISCONNECTED, TunnelStatus.ERROR -> 0xFFEF4444.toInt() to R.drawable.ic_shield_disconnected
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

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("xServer Zero Trust")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_shield)
            .setColor(accentColor)
            .apply { if (largeBitmap != null) setLargeIcon(largeBitmap) }
            .setContentIntent(pendingOpen)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    null,
                    "Disconnect",
                    pendingStop
                ).build()
            )
            .build()
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "xServer Zero Trust Status",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Displays xServer Zero Trust tunnel connection status"
        }
        nm.createNotificationChannel(channel)
    }
}
