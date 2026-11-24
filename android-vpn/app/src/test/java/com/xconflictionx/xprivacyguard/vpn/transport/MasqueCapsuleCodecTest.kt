package com.xconflictionx.xprivacyguard.vpn.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MasqueCapsuleCodecTest {

    @Test
    fun quicVarIntRoundTripsAllWidths() {
        val values = listOf(0L, 63L, 64L, 16_383L, 16_384L, 1_073_741_823L,
            1_073_741_824L, (1L shl 62) - 1)
        values.forEach { value ->
            val encoded = MasqueCapsuleCodec.encodeVarInt(value)
            val decoded = MasqueCapsuleCodec.decodeVarInt(encoded)
            assertEquals(value, decoded.value)
            assertEquals(encoded.size, decoded.bytesRead)
        }
    }

    @Test
    fun capsuleRoundTripsAndPreservesPayload() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val encoded = MasqueCapsuleCodec.encodeCapsule(
            MasqueCapsuleCodec.ADDRESS_ASSIGN_CAPSULE_TYPE, payload
        )
        val decoded = MasqueCapsuleCodec.decodeCapsule(encoded)
        assertEquals(MasqueCapsuleCodec.ADDRESS_ASSIGN_CAPSULE_TYPE, decoded.type)
        assertArrayEquals(payload, decoded.payload)
    }

    @Test
    fun ipDatagramUsesContextZero() {
        val packet = byteArrayOf(0x45, 0, 0, 20, 1, 2, 3, 4)
        assertArrayEquals(packet, MasqueCapsuleCodec.decodeIpDatagram(
            MasqueCapsuleCodec.encodeIpDatagram(packet)
        ))
    }

    @Test
    fun rejectsTruncatedVarInt() {
        assertThrows(IllegalArgumentException::class.java) {
            MasqueCapsuleCodec.decodeVarInt(byteArrayOf(0x40))
        }
    }

    @Test
    fun rejectsCapsuleLengthMismatch() {
        assertThrows(IllegalArgumentException::class.java) {
            MasqueCapsuleCodec.decodeCapsule(byteArrayOf(0x01, 0x02, 0x7F))
        }
    }

    @Test
    fun rejectsNonZeroIpDatagramContext() {
        assertThrows(IllegalArgumentException::class.java) {
            MasqueCapsuleCodec.decodeIpDatagram(byteArrayOf(0x01, 0x45))
        }
    }
}
