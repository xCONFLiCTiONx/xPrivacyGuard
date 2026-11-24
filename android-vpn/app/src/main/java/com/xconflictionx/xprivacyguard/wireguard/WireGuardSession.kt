package com.xconflictionx.xprivacyguard.wireguard

import com.xconflictionx.xprivacyguard.config.WireGuardConfig
import org.bouncycastle.crypto.params.KeyParameter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.spec.SecretKeySpec

class WireGuardSession(private val config: WireGuardConfig) {

    private val initiatorStaticPriv: ByteArray = config.privateKeyBytes()
    private val initiatorStaticPub: ByteArray = WireGuardCrypto.publicKeyFromPrivate(initiatorStaticPriv)
    private val responderStaticPub: ByteArray = config.peerPublicKeyBytes()
    private val presharedKey: ByteArray = config.presharedKeyBytes()

    private data class PendingHandshake(
        val senderIndex: Int,
        val ephemeralPriv: ByteArray,
        val chainingKey: ByteArray,
        val hash: ByteArray
    )

    // Keep up to 3 recent in-flight handshakes so a slightly delayed Type 2 response
    // from an earlier initiation is still accepted if a retry was already sent.
    private val pendingHandshakes = ArrayDeque<PendingHandshake>(4)

    // Active transport session state
    @Volatile
    var peerReceiverIndex: Int = 0
        private set

    @Volatile
    var activeLocalIndex: Int = 0
        private set

    @Volatile
    private var sendingKey: ByteArray? = null

    @Volatile
    private var receivingKey: ByteArray? = null

    @Volatile
    private var sendingKeySpec: SecretKeySpec? = null

    @Volatile
    private var sendingKeyParam: KeyParameter? = null

    @Volatile
    private var receivingKeySpec: SecretKeySpec? = null

    @Volatile
    private var receivingKeyParam: KeyParameter? = null

    private val sendCounter = AtomicLong(0L)

    @Volatile
    var lastHandshakeEpochMs: Long = 0L
        private set

    val isEstablished: Boolean
        get() = sendingKey != null && receivingKey != null

    /**
     * Creates a 148-byte WireGuard Type 1 Handshake Initiation packet.
     */
    @Synchronized
    fun createHandshakeInitiation(): ByteArray {
        val senderIndex = WireGuardCrypto.nextSenderIndex()

        var chainingKey = WireGuardCrypto.blake2s256(WireGuardCrypto.CONSTRUCTION)
        var hash = WireGuardCrypto.blake2s256(chainingKey, WireGuardCrypto.IDENTIFIER)
        hash = WireGuardCrypto.blake2s256(hash, responderStaticPub)

        val (ephPriv, ephPub) = WireGuardCrypto.generateX25519KeyPair()

        hash = WireGuardCrypto.blake2s256(hash, ephPub)
        chainingKey = WireGuardCrypto.kdf1(chainingKey, ephPub)

        val dh1 = WireGuardCrypto.x25519Dh(ephPriv, responderStaticPub)
        val (ck1, key1) = WireGuardCrypto.kdf2(chainingKey, dh1)
        chainingKey = ck1

        val encryptedStatic = WireGuardCrypto.aeadEncrypt(key1, 0L, initiatorStaticPub, hash)
        hash = WireGuardCrypto.blake2s256(hash, encryptedStatic)

        val dh2 = WireGuardCrypto.x25519Dh(initiatorStaticPriv, responderStaticPub)
        val (ck2, key2) = WireGuardCrypto.kdf2(chainingKey, dh2)
        chainingKey = ck2

        val timestamp = WireGuardCrypto.tai64nNow()
        val encryptedTimestamp = WireGuardCrypto.aeadEncrypt(key2, 0L, timestamp, hash)
        hash = WireGuardCrypto.blake2s256(hash, encryptedTimestamp)

        if (pendingHandshakes.size >= 3) {
            pendingHandshakes.removeFirst()
        }
        pendingHandshakes.addLast(
            PendingHandshake(
                senderIndex = senderIndex,
                ephemeralPriv = ephPriv,
                chainingKey = chainingKey,
                hash = hash
            )
        )

        val packet = ByteArray(148)
        val buf = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(1)
        buf.putInt(senderIndex)
        buf.put(ephPub)
        buf.put(encryptedStatic)
        buf.put(encryptedTimestamp)

        val mac1Key = WireGuardCrypto.blake2s256(WireGuardCrypto.LABEL_MAC1, responderStaticPub)
        val mac1 = WireGuardCrypto.blake2s128Keyed(mac1Key, packet, 116)
        System.arraycopy(mac1, 0, packet, 116, 16)

        return packet
    }

    /**
     * Processes a 92-byte WireGuard Type 2 Handshake Response packet from the peer.
     */
    @Synchronized
    fun processHandshakeResponse(packet: ByteArray, length: Int = packet.size): Boolean {
        if (length < 92) return false
        val buf = ByteBuffer.wrap(packet, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        val msgType = buf.int
        if (msgType != 2) return false

        val responderIndex = buf.int
        val receiverIndex = buf.int
        val pending = pendingHandshakes.lastOrNull { it.senderIndex == receiverIndex } ?: return false

        val ephPriv = pending.ephemeralPriv
        var chainingKey = pending.chainingKey
        var hash = pending.hash

        val expectedMac1Key = WireGuardCrypto.blake2s256(WireGuardCrypto.LABEL_MAC1, initiatorStaticPub)
        val expectedMac1 = WireGuardCrypto.blake2s128Keyed(expectedMac1Key, packet, 60)
        for (i in 0 until 16) {
            if (packet[60 + i] != expectedMac1[i]) return false
        }

        val responderEphPub = ByteArray(32)
        System.arraycopy(packet, 12, responderEphPub, 0, 32)
        val encryptedNothing = ByteArray(16)
        System.arraycopy(packet, 44, encryptedNothing, 0, 16)

        hash = WireGuardCrypto.blake2s256(hash, responderEphPub)
        chainingKey = WireGuardCrypto.kdf1(chainingKey, responderEphPub)

        val dhee = WireGuardCrypto.x25519Dh(ephPriv, responderEphPub)
        chainingKey = WireGuardCrypto.kdf1(chainingKey, dhee)

        val dhse = WireGuardCrypto.x25519Dh(initiatorStaticPriv, responderEphPub)
        chainingKey = WireGuardCrypto.kdf1(chainingKey, dhse)

        val (ck3, tau, key) = WireGuardCrypto.kdf3(chainingKey, presharedKey)
        chainingKey = ck3
        hash = WireGuardCrypto.blake2s256(hash, tau)

        val decryptedNothing = runCatching {
            WireGuardCrypto.aeadDecrypt(key, 0L, encryptedNothing, hash)
        }.getOrNull() ?: return false

        if (decryptedNothing.isNotEmpty()) return false
        hash = WireGuardCrypto.blake2s256(hash, encryptedNothing)

        val (txKey, rxKey) = WireGuardCrypto.kdf2(chainingKey, WireGuardCrypto.EMPTY_BYTES)
        sendingKey = txKey
        receivingKey = rxKey
        sendingKeySpec = SecretKeySpec(txKey, "ChaCha20")
        sendingKeyParam = KeyParameter(txKey)
        receivingKeySpec = SecretKeySpec(rxKey, "ChaCha20")
        receivingKeyParam = KeyParameter(rxKey)

        peerReceiverIndex = responderIndex
        activeLocalIndex = pending.senderIndex
        sendCounter.set(0L)
        lastHandshakeEpochMs = System.currentTimeMillis()

        pendingHandshakes.clear()

        return true
    }

    /**
     * Zero-allocation in-place transport packet encryption.
     * Pads [ipPacket] (0..[length]-1) with zeros up to a 16-byte boundary and writes
     * the complete Type 4 WireGuard packet into [outWgBuffer]. If the input has no spare
     * capacity, a padded copy is allocated; pre-sized TUN buffers remain zero-allocation.
     * Returns the total WireGuard packet length written, or -1 if session is not established.
     */
    fun encryptTransportPacketInto(
        ipPacket: ByteArray,
        length: Int,
        outWgBuffer: ByteArray
    ): Int {
        val keyParam = sendingKeyParam ?: return -1
        val keySpec = sendingKeySpec
        val counter = sendCounter.getAndIncrement()

        if (length !in 0..ipPacket.size) return -1

        val paddedLen = if (length == 0) 0 else ((length + 15) / 16) * 16

        // TUN buffers have spare capacity and stay zero-allocation. DNS workers pass
        // exact-length copies, so create a padded working buffer only for those callers.
        if (outWgBuffer.size < 32 + paddedLen) return -1
        val paddedPacket = if (ipPacket.size >= paddedLen) ipPacket else ipPacket.copyOf(paddedLen)
        if (paddedLen > length) {
            paddedPacket.fill(0, length, paddedLen)
        }

        // Write 16-byte WireGuard Transport Data header (little-endian)
        outWgBuffer[0] = 4; outWgBuffer[1] = 0; outWgBuffer[2] = 0; outWgBuffer[3] = 0
        val peerIdx = peerReceiverIndex
        outWgBuffer[4] = (peerIdx and 0xFF).toByte()
        outWgBuffer[5] = ((peerIdx ushr 8) and 0xFF).toByte()
        outWgBuffer[6] = ((peerIdx ushr 16) and 0xFF).toByte()
        outWgBuffer[7] = ((peerIdx ushr 24) and 0xFF).toByte()

        var c = counter
        for (i in 8..15) {
            outWgBuffer[i] = (c and 0xFFL).toByte()
            c = c ushr 8
        }

        val writtenCipherLen = try {
            WireGuardCrypto.aeadEncryptInto(
                keySpec = keySpec,
                keyParam = keyParam,
                counter = counter,
                input = paddedPacket,
                inputOffset = 0,
                inputLen = paddedLen,
                output = outWgBuffer,
                outputOffset = 16
            )
        } catch (_: Exception) {
            return -1
        }
        return 16 + writtenCipherLen
    }

    /**
     * Zero-allocation in-place transport packet decryption into [outIpBuffer].
     * Returns:
     *  - `> 0`: actual unpadded IPv4/IPv6 packet length written into [outIpBuffer]
     *  - `0`: valid authenticated keepalive packet
     *  - `-1`: invalid or unauthenticated packet
     */
    fun decryptTransportPacketInto(
        wgPacket: ByteArray,
        length: Int,
        outIpBuffer: ByteArray
    ): Int {
        if (length < 32) return -1
        val keyParam = receivingKeyParam ?: return -1
        val keySpec = receivingKeySpec

        // Fast little-endian header read without allocating ByteBuffer
        val msgType = (wgPacket[0].toInt() and 0xFF) or
            ((wgPacket[1].toInt() and 0xFF) shl 8) or
            ((wgPacket[2].toInt() and 0xFF) shl 16) or
            ((wgPacket[3].toInt() and 0xFF) shl 24)
        if (msgType != 4) return -1

        val receiverIdx = (wgPacket[4].toInt() and 0xFF) or
            ((wgPacket[5].toInt() and 0xFF) shl 8) or
            ((wgPacket[6].toInt() and 0xFF) shl 16) or
            ((wgPacket[7].toInt() and 0xFF) shl 24)
        if (receiverIdx != activeLocalIndex) return -1

        var counter = 0L
        for (i in 15 downTo 8) {
            counter = (counter shl 8) or (wgPacket[i].toLong() and 0xFFL)
        }

        val plainPaddedLen = WireGuardCrypto.aeadDecryptInto(
            keySpec = keySpec,
            keyParam = keyParam,
            counter = counter,
            input = wgPacket,
            inputOffset = 16,
            inputLen = length - 16,
            output = outIpBuffer,
            outputOffset = 0
        )
        if (plainPaddedLen < 0) return -1
        if (plainPaddedLen == 0) return 0

        val ipVersion = (outIpBuffer[0].toInt() ushr 4) and 0x0F
        val actualLength = when (ipVersion) {
            4 -> {
                if (plainPaddedLen < 20) return -1
                ((outIpBuffer[2].toInt() and 0xFF) shl 8) or (outIpBuffer[3].toInt() and 0xFF)
            }
            6 -> {
                if (plainPaddedLen < 40) return -1
                val payloadLen = ((outIpBuffer[4].toInt() and 0xFF) shl 8) or (outIpBuffer[5].toInt() and 0xFF)
                40 + payloadLen
            }
            else -> plainPaddedLen
        }

        if (actualLength <= 0 || actualLength > plainPaddedLen) return -1
        return actualLength
    }

    fun encryptTransportPacket(ipPacket: ByteArray, length: Int = ipPacket.size): ByteArray? {
        val paddedCap = ((length + 15) / 16) * 16
        val workIn = if (ipPacket.size >= paddedCap) ipPacket else ipPacket.copyOf(paddedCap)
        val out = ByteArray(16 + paddedCap + 16)
        val total = encryptTransportPacketInto(workIn, length, out)
        if (total < 0) return null
        return if (total == out.size) out else out.copyOf(total)
    }

    fun decryptTransportPacket(wgPacket: ByteArray, length: Int = wgPacket.size): ByteArray? {
        if (length < 32) return null
        val out = ByteArray(length - 32)
        val actualLen = decryptTransportPacketInto(wgPacket, length, out)
        if (actualLen < 0) return null
        if (actualLen == 0) return WireGuardCrypto.EMPTY_BYTES
        return if (actualLen == out.size) out else out.copyOf(actualLen)
    }
}
