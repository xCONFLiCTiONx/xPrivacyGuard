package com.xconflictionx.xprivacyguard.vpn.transport

/**
 * Boundary between Android's existing TUN/filter pipeline and an encrypted tunnel transport.
 *
 * Implementations own only the uplink/downlink carrier. The VpnService remains responsible
 * for TUN creation, app routing, local DNS interception, filtering, and packet inspection.
 *
 * A transport must not report CONNECTED until its authenticated tunnel is established.
 */
interface TunnelTransport {
    val protocol: TunnelProtocol

    /**
     * Establish the remote tunnel using already validated, locally stored credentials.
     * Implementations must fail closed and must never log secrets or private key material.
     */
    suspend fun connect(): Result<Unit>

    /** Send one complete IP packet from the Android TUN device into the tunnel. */
    suspend fun sendPacket(packet: ByteArray): Result<Unit>

    /** Receive the next complete IP packet from the remote tunnel, or null on clean shutdown. */
    suspend fun receivePacket(): ByteArray?

    /** Close sockets, cancel receive operations, and erase ephemeral session state. */
    suspend fun disconnect()
}

enum class TunnelProtocol {
    WIREGUARD,
    CLOUDFLARE_MASQUE
}

/**
 * Explicit lifecycle states shared by transport implementations.
 * Authentication/enrollment is deliberately not represented as tunnel connectivity.
 */
enum class TunnelTransportState {
    DISCONNECTED,
    AUTHENTICATING,
    ENROLLING_DEVICE,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    FAILED
}
