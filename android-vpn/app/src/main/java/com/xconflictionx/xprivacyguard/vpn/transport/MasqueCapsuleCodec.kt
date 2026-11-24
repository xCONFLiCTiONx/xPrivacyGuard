package com.xconflictionx.xprivacyguard.vpn.transport

import java.io.ByteArrayOutputStream

/**
 * RFC 9297 Capsule Protocol and HTTP Datagram context-id framing primitives.
 *
 * This is protocol framing only. It does not implement QUIC, HTTP/3, TLS, Cloudflare
 * device orchestration, or Cloudflare's WARP authentication/session protocol.
 */
object MasqueCapsuleCodec {
    const val DATAGRAM_CAPSULE_TYPE: Long = 0x00L
    const val ADDRESS_ASSIGN_CAPSULE_TYPE: Long = 0x01L
    const val ADDRESS_REQUEST_CAPSULE_TYPE: Long = 0x02L
    const val ROUTE_ADVERTISEMENT_CAPSULE_TYPE: Long = 0x03L
    const val IP_DATAGRAM_CONTEXT_ID: Long = 0L

    data class Capsule(val type: Long, val payload: ByteArray)

    /**
     * Encode one RFC 9297 capsule: QUIC-varint type, QUIC-varint length, payload.
     */
    fun encodeCapsule(type: Long, payload: ByteArray): ByteArray {
        require(type >= 0) { "Capsule type must be non-negative" }
        val out = ByteArrayOutputStream(payload.size + 16)
        out.write(encodeVarInt(type))
        out.write(encodeVarInt(payload.size.toLong()))
        out.write(payload)
        return out.toByteArray()
    }

    /**
     * Decode exactly one capsule. Trailing bytes are rejected to avoid ambiguous framing.
     */
    fun decodeCapsule(encoded: ByteArray): Capsule {
        val type = decodeVarInt(encoded, 0)
        val length = decodeVarInt(encoded, type.bytesRead)
        require(length.value <= Int.MAX_VALUE) { "Capsule payload is too large" }
        val payloadStart = type.bytesRead + length.bytesRead
        val payloadLength = length.value.toInt()
        require(payloadStart <= encoded.size && payloadLength == encoded.size - payloadStart) {
            "Capsule length does not match encoded data"
        }
        return Capsule(type.value, encoded.copyOfRange(payloadStart, encoded.size))
    }

    /**
     * HTTP Datagram payloads begin with a QUIC-varint context ID. For CONNECT-IP,
     * context ID zero carries one complete IP packet.
     */
    fun encodeIpDatagram(ipPacket: ByteArray): ByteArray {
        require(ipPacket.isNotEmpty()) { "IP packet cannot be empty" }
        return encodeVarInt(IP_DATAGRAM_CONTEXT_ID) + ipPacket
    }

    fun decodeIpDatagram(datagram: ByteArray): ByteArray {
        val context = decodeVarInt(datagram, 0)
        require(context.value == IP_DATAGRAM_CONTEXT_ID) {
            "Unsupported HTTP Datagram context ID: ${context.value}"
        }
        require(context.bytesRead < datagram.size) { "IP datagram payload is empty" }
        return datagram.copyOfRange(context.bytesRead, datagram.size)
    }

    /** QUIC variable-length integer encoding (RFC 9000, Section 16). */
    fun encodeVarInt(value: Long): ByteArray {
        require(value >= 0 && value <= MAX_VARINT) { "Value is outside QUIC varint range" }
        val length = when {
            value <  (1L shl 6) -> 1
            value <  (1L shl 14) -> 2
            value <  (1L shl 30) -> 4
            else -> 8
        }
        val out = ByteArray(length)
        for (i in 0 until length) {
            out[length - 1 - i] = (value ushr (i * 8)).toByte()
        }
        out[0] = (out[0].toInt() or when (length) {
            1 -> 0x00
            2 -> 0x40
            4 -> 0x80
            else -> 0xC0
        }).toByte()
        return out
    }

    data class DecodedVarInt(val value: Long, val bytesRead: Int)

    fun decodeVarInt(input: ByteArray, offset: Int = 0): DecodedVarInt {
        require(offset >= 0 && offset < input.size) { "Missing QUIC varint" }
        val first = input[offset].toInt() and 0xFF
        val length = 1 shl (first ushr 6)
        require(input.size - offset >= length) { "Truncated QUIC varint" }
        var value = (first and 0x3F).toLong()
        for (i in 1 until length) {
            value = (value shl 8) or (input[offset + i].toInt() and 0xFF).toLong()
        }
        return DecodedVarInt(value, length)
    }

    private const val MAX_VARINT: Long = (1L shl 62) - 1
}
