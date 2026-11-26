package com.xconflictionx.xprivacyguard.vpn.transport

import android.content.Context
import android.net.VpnService
import android.os.Build
import android.provider.Settings
import com.xconflictionx.xprivacyguard.config.CloudflareOneConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class AetherIdentityInfo(
    val deviceId: String,
    val ipv4: String,
    val ipv6: String,
    val organization: String,
    val assignedEndpoint: String,
    val hasMasqueCert: Boolean,
    val certIssuedAt: Long,
    val certUsable: Boolean
)

data class AetherSessionInfo(
    val tunnelHandle: Long,
    val peer: String,
    val ipv4: String,
    val ipv6: String,
    val mtu: Int,
    val carrier: String,
    val identity: AetherIdentityInfo
)

/**
 * Manages the native Aether MASQUE (CONNECT-IP) raw-IP tunnel lifecycle under Android's VpnService.
 *
 * Security & lifecycle invariants:
 * - Registers VpnService.protect(fd) BEFORE any Aether network operation (identity open, verify, scan, tunnel).
 * - Fails closed if socket protection is rejected or if the MASQUE tunnel drops.
 * - Stops the raw tunnel and releases all native handles BEFORE unregistering the socket protector.
 */
class AetherMasqueTransport(
    private val vpnService: VpnService,
    private val identityDir: File,
    private val config: CloudflareOneConfig,
    private val onStatusUpdate: (String) -> Unit = {},
    private val onIdentityOpened: (AetherIdentityInfo) -> Unit = {}
) : TunnelTransport {

    override val protocol: TunnelProtocol = TunnelProtocol.CLOUDFLARE_MASQUE

    private val identityHandle = AtomicLong(0L)
    private val tunnelHandle = AtomicLong(0L)
    private val protectorRegistered = AtomicBoolean(false)

    @Volatile
    var sessionInfo: AetherSessionInfo? = null
        private set

    override suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            disconnect()

            if (!identityDir.exists()) {
                identityDir.mkdirs()
            }

            // 1. Register VpnService.protect(fd) BEFORE any Aether socket can be created
            AetherNative.registerSocketProtector(vpnService)
            protectorRegistered.set(true)

            // 2. Seed cached Cloudflare Access JWT if available and unexpired
            if (config.hasValidToken) {
                runCatching { AetherNative.nativeTeamTokenSet(config.accessToken.trim()) }
            }

            // 3. Open or enroll the MASQUE device identity
            onStatusUpdate("Opening Cloudflare MASQUE identity...")
            val identityBasePath = File(identityDir, "aether.toml").absolutePath
            val deviceName = resolveDeviceName(vpnService)
            val openPayload = JSONObject().apply {
                put("path", identityBasePath)
                put("transport", "masque")
                put("model", "Android")
                put("name", deviceName)
                put("locale", "en_US")
                val team = config.normalizedTeam
                if (team.isNotEmpty()) {
                    put("team", JSONObject().apply {
                        put("team", team)
                        if (config.hasValidToken) {
                            put("token", config.accessToken.trim())
                        }
                        if (config.hasServiceToken) {
                            put("client_id", config.serviceClientId.trim())
                            put("client_secret", config.serviceClientSecret.trim())
                        }
                        if (config.userEmail.isNotBlank()) {
                            put("email", config.userEmail.trim())
                        }
                    })
                }
            }

            val openResult = AetherNative.awaitJob(
                AetherNative.nativeIdentityOpen(openPayload.toString()),
                timeoutMs = 60_000L
            )
            val idHandle = openResult.optLong("identity", 0L)
            check(idHandle > 0L) { "Aether did not return a valid identity handle" }
            identityHandle.set(idHandle)

            val summaryJson = openResult.optJSONObject("summary") ?: JSONObject()
            val identityInfo = AetherIdentityInfo(
                deviceId = summaryJson.optString("device_id", ""),
                ipv4 = summaryJson.optString("ipv4", "172.16.0.2"),
                ipv6 = summaryJson.optString("ipv6", ""),
                organization = summaryJson.optString("organization", config.normalizedTeam),
                assignedEndpoint = summaryJson.optString("assigned_endpoint", ""),
                hasMasqueCert = summaryJson.optBoolean("has_masque_cert", false),
                certIssuedAt = summaryJson.optLong("cert_issued_at", 0L),
                certUsable = summaryJson.optBoolean("cert_usable", false)
            )
            check(identityInfo.hasMasqueCert) {
                "Cloudflare device identity does not have a MASQUE certificate"
            }

            // Persist enrolled identity metadata immediately so a transient peer scan or
            // tunnel timeout never loses enrollment state or triggers a false "not signed in" error.
            runCatching { onIdentityOpened(identityInfo) }

            // 4. Select and verify a reachable MASQUE edge endpoint
            val peer = resolveReachablePeer(idHandle, identityInfo)

            // 5. Establish the raw-IP MASQUE CONNECT-IP tunnel (HTTP/3 QUIC, or HTTP/2 if configured)
            onStatusUpdate("Establishing MASQUE CONNECT-IP tunnel ($peer)...")
            val rawPayload = JSONObject().apply {
                put("peer", peer)
                put("h2", config.preferHttp2)
                put("ech", config.enableEch)
                put("mtu", 1280)
                put("startup_secs", 25)
            }
            val startResult = AetherNative.awaitJob(
                AetherNative.nativeRawTunnelStart(idHandle, rawPayload.toString()),
                timeoutMs = 35_000L
            )
            val tHandle = startResult.optLong("tunnel", 0L)
            check(tHandle > 0L) { "Aether raw MASQUE tunnel did not return a valid handle" }
            tunnelHandle.set(tHandle)

            sessionInfo = AetherSessionInfo(
                tunnelHandle = tHandle,
                peer = startResult.optString("peer", peer),
                ipv4 = startResult.optString("ipv4", identityInfo.ipv4).ifBlank { identityInfo.ipv4 },
                ipv6 = startResult.optString("ipv6", identityInfo.ipv6).ifBlank { identityInfo.ipv6 },
                mtu = startResult.optInt("mtu", 1280),
                carrier = startResult.optString("carrier", if (config.preferHttp2) "h2" else "h3"),
                identity = identityInfo
            )
        }.onFailure {
            disconnect()
        }
    }

    private suspend fun resolveReachablePeer(
        idHandle: Long,
        identityInfo: AetherIdentityInfo
    ): String {
        val custom = config.customPeerEndpoint.trim()
        if (custom.isNotEmpty()) {
            return ensurePort(custom, 443)
        }

        val candidates = buildFastGatewayCandidates(identityInfo)

        // Aether's FFI verify/scan jobs only probe UDP/QUIC unless AETHER_MASQUE_H2 is set in env.
        // When HTTP/2 TCP fallback is enabled, skip UDP probing and dial the prioritized anycast edge directly.
        if (config.preferHttp2) {
            return candidates.first()
        }

        // Probe Cloudflare's prioritized anycast MASQUE gateways (Zero Trust 162.159.197.x first for teams,
        // plus assigned/cached edge) in parallel with noise disabled so healthy networks pick the fastest
        // gateway in ~150-400ms instead of running a slow 45s subnet scan.
        onStatusUpdate("Finding fastest Cloudflare MASQUE gateway...")
        val fastWinner = probeFastestCandidate(
            idHandle = idHandle,
            candidates = candidates.take(5),
            timeoutMs = 6_000L
        )
        if (fastWinner != null) {
            return fastWinner
        }

        // Fallback scan if port 443 anycast probes were blocked (e.g. restrictive firewall/ISP)
        onStatusUpdate("Scanning alternate Cloudflare MASQUE ports...")
        val scanPayload = JSONObject().apply {
            put("transport", "masque")
            put("mode", "fast")
            put("ip", "v4")
            put("profile", "off")
            put("ports", JSONArray(listOf(443, 4500, 500, 1701)))
            put("ech", config.enableEch)
        }
        val scannedEndpoint = runCatching {
            val scanResult = AetherNative.awaitJob(
                AetherNative.nativeScanStart(idHandle, scanPayload.toString()),
                timeoutMs = 15_000L
            )
            val endpointObj = scanResult.optJSONObject("endpoint")
            val scannedIp = endpointObj?.optString("ip", "")?.trim().orEmpty()
            val scannedPort = endpointObj?.optInt("port", 443) ?: 443
            if (scannedIp.isNotEmpty()) {
                if (scannedIp.contains(':') && !scannedIp.startsWith('[')) {
                    "[$scannedIp]:$scannedPort"
                } else {
                    "$scannedIp:$scannedPort"
                }
            } else {
                val endpointStr = scanResult.optString("endpoint", "").trim()
                if (endpointStr.isNotEmpty()) ensurePort(endpointStr, 443) else null
            }
        }.getOrNull()

        if (!scannedEndpoint.isNullOrBlank()) {
            return scannedEndpoint
        }

        // Last resort: dial the top prioritized anycast gateway directly so raw tunnel startup
        // can either succeed or surface the exact QUIC/TLS error.
        return candidates.first()
    }

    internal fun buildFastGatewayCandidates(identityInfo: AetherIdentityInfo): List<String> {
        val isZeroTrust = config.normalizedTeam.isNotEmpty() || identityInfo.organization.isNotBlank()
        val ordered = LinkedHashSet<String>()
        identityInfo.assignedEndpoint.trim().takeIf { it.isNotEmpty() }?.let {
            ordered.add(ensurePort(it, 443))
        }
        config.assignedEndpoint.trim().takeIf { it.isNotEmpty() }?.let {
            ordered.add(ensurePort(it, 443))
        }
        val anycastSeeds = if (isZeroTrust) {
            listOf(
                "162.159.197.2:443",
                "162.159.197.1:443",
                "162.159.197.3:443",
                "162.159.198.2:443",
                "162.159.198.1:443",
                "162.159.199.2:443"
            )
        } else {
            listOf(
                "162.159.198.2:443",
                "162.159.198.1:443",
                "162.159.199.2:443",
                "162.159.199.1:443"
            )
        }
        ordered.addAll(anycastSeeds)
        return ordered.toList()
    }

    private suspend fun probeFastestCandidate(
        idHandle: Long,
        candidates: List<String>,
        timeoutMs: Long
    ): String? {
        if (candidates.isEmpty()) return null
        return withTimeoutOrNull(timeoutMs) {
            coroutineScope {
                val winner = CompletableDeferred<String?>()
                val remaining = AtomicInteger(candidates.size)
                val jobs = candidates.map { candidate ->
                    launch(Dispatchers.IO) {
                        val reachable = try {
                            val verifyPayload = JSONObject().apply {
                                put("peer", candidate)
                                put("transport", "masque")
                                put("profile", "off")
                                put("ech", config.enableEch)
                            }
                            val res = AetherNative.awaitJob(
                                AetherNative.nativeVerifyStart(idHandle, verifyPayload.toString()),
                                timeoutMs = timeoutMs
                            )
                            res.optBoolean("reachable", false)
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (_: Exception) {
                            false
                        }

                        if (reachable) {
                            winner.complete(candidate)
                        } else if (remaining.decrementAndGet() == 0) {
                            winner.complete(null)
                        }
                    }
                }
                val result = winner.await()
                jobs.forEach { it.cancel() }
                result
            }
        }
    }

    private fun ensurePort(addr: String, defaultPort: Int): String {
        if (addr.startsWith("[")) {
            return if (addr.contains("]:")) addr else "$addr:$defaultPort"
        }
        val colonCount = addr.count { it == ':' }
        return when {
            colonCount == 0 -> "$addr:$defaultPort"
            colonCount == 1 -> addr
            else -> "[$addr]:$defaultPort"
        }
    }

    /** Fast hot-path send without allocating a copy. Returns 0 on success, -2 if queue full, -1 if closed. */
    fun sendPacketFast(packet: ByteArray, length: Int): Int {
        val handle = tunnelHandle.get()
        if (handle <= 0L) return -1
        return AetherNative.nativeRawTunnelSend(handle, packet, length)
    }

    /** Fast hot-path receive into a pre-allocated buffer. Returns >0 bytes, 0 on timeout, -1 on tunnel close. */
    fun receivePacketInto(outBuf: ByteArray, timeoutMs: Int = 250): Int {
        val handle = tunnelHandle.get()
        if (handle <= 0L) return -1
        return AetherNative.nativeRawTunnelRecv(handle, outBuf, timeoutMs)
    }

    fun isAlive(): Boolean {
        val handle = tunnelHandle.get()
        return handle > 0L && AetherNative.nativeRawTunnelIsAlive(handle)
    }

    fun migrateNetwork(): Boolean {
        val handle = tunnelHandle.get()
        return handle > 0L && AetherNative.nativeRawTunnelMigrate(handle)
    }

    override suspend fun sendPacket(packet: ByteArray): Result<Unit> {
        val rc = sendPacketFast(packet, packet.size)
        return if (rc == 0 || rc == -2) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("Aether MASQUE tunnel is closed"))
        }
    }

    override suspend fun receivePacket(): ByteArray? = withContext(Dispatchers.IO) {
        val buf = ByteArray(2048)
        while (true) {
            val n = receivePacketInto(buf, 250)
            if (n > 0) return@withContext buf.copyOf(n)
            if (n < 0) return@withContext null
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }

    override suspend fun disconnect() {
        val tHandle = tunnelHandle.getAndSet(0L)
        if (tHandle > 0L) {
            runCatching { AetherNative.nativeRawTunnelStop(tHandle) }
        }
        val idHandle = identityHandle.getAndSet(0L)
        if (idHandle > 0L) {
            runCatching { AetherNative.nativeIdentityFree(idHandle) }
        }
        sessionInfo = null
        // Unregister socket protector ONLY after tunnel and identity handles have stopped
        if (protectorRegistered.getAndSet(false)) {
            runCatching { AetherNative.unregisterSocketProtector() }
        }
    }

    companion object {
        private const val OS_FALLBACK_NAME = "Android"
        private const val MAX_DEVICE_NAME_LENGTH = 64

        /**
         * Resolves a human-readable device name for Cloudflare Zero Trust registration:
         * 1. User-configured Android device name (Settings.Global.DEVICE_NAME / OEM equivalents)
         * 2. Hardware manufacturer + model (Build.MANUFACTURER / Build.MODEL / Build.DEVICE)
         * 3. OS fallback ("Android") when device info is unavailable or unknown.
         */
        internal fun resolveDeviceName(context: Context?): String {
            val userDeviceName = context?.let { ctx ->
                runCatching {
                    val cr = ctx.contentResolver
                    Settings.Global.getString(cr, Settings.Global.DEVICE_NAME)
                        ?.takeIf { isUsableDeviceValue(it) }
                        ?: Settings.System.getString(cr, "device_name")
                            ?.takeIf { isUsableDeviceValue(it) }
                        ?: Settings.Secure.getString(cr, "bluetooth_name")
                            ?.takeIf { isUsableDeviceValue(it) }
                }.getOrNull()
            }
            return formatDeviceName(
                userDeviceName = userDeviceName,
                manufacturer = runCatching { Build.MANUFACTURER }.getOrNull(),
                model = runCatching { Build.MODEL }.getOrNull(),
                device = runCatching { Build.DEVICE }.getOrNull()
            )
        }

        internal fun formatDeviceName(
            userDeviceName: String?,
            manufacturer: String?,
            model: String?,
            device: String? = null
        ): String {
            sanitizeDeviceName(userDeviceName)?.let { return it }

            val cleanManufacturer = sanitizeDeviceName(manufacturer)
            val cleanModel = sanitizeDeviceName(model)
            val hardwareName = when {
                cleanModel != null && cleanManufacturer != null -> {
                    if (cleanModel.startsWith(cleanManufacturer, ignoreCase = true)) {
                        cleanModel
                    } else {
                        "$cleanManufacturer $cleanModel"
                    }
                }
                cleanModel != null -> cleanModel
                cleanManufacturer != null -> cleanManufacturer
                else -> sanitizeDeviceName(device)
            }

            return sanitizeDeviceName(hardwareName) ?: OS_FALLBACK_NAME
        }

        private fun isUsableDeviceValue(raw: String?): Boolean = sanitizeDeviceName(raw) != null

        private fun sanitizeDeviceName(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            val cleaned = raw
                .map { ch -> if (ch.isISOControl()) ' ' else ch }
                .joinToString("")
                .trim()
                .replace(Regex("\\s+"), " ")
            if (cleaned.isEmpty() || cleaned.equals("unknown", ignoreCase = true)) {
                return null
            }
            return cleaned.take(MAX_DEVICE_NAME_LENGTH).trim()
        }
    }
}

