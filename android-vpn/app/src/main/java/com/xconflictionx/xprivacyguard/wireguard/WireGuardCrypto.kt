package com.xconflictionx.xprivacyguard.wireguard

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.Blake2sDigest
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object WireGuardCrypto {
    val CONSTRUCTION: ByteArray =
        "Noise_IKpsk2_25519_ChaChaPoly_BLAKE2s".toByteArray(StandardCharsets.US_ASCII)
    val IDENTIFIER: ByteArray =
        "WireGuard v1 zx2c4 Jason@zx2c4.com".toByteArray(StandardCharsets.US_ASCII)
    val LABEL_MAC1: ByteArray =
        "mac1----".toByteArray(StandardCharsets.US_ASCII)
    val LABEL_COOKIE: ByteArray =
        "cookie--".toByteArray(StandardCharsets.US_ASCII)
    val EMPTY_BYTES: ByteArray = ByteArray(0)

    private val secureRandom = SecureRandom()

    // Check if native BoringSSL/JCE ChaCha20-Poly1305 is available (Android 9+ ARMv8 NEON hardware acceleration)
    private val useNativeJceCipher: Boolean = runCatching {
        val c = Cipher.getInstance("ChaCha20-Poly1305")
        val testKey = SecretKeySpec(ByteArray(32), "ChaCha20")
        val testIv = IvParameterSpec(ByteArray(12))
        c.init(Cipher.ENCRYPT_MODE, testKey, testIv)
        val out = ByteArray(16)
        c.doFinal(EMPTY_BYTES, 0, 0, out, 0)
        true
    }.getOrDefault(false)

    private val threadLocalJceCipher = object : ThreadLocal<Cipher?>() {
        override fun initialValue(): Cipher? = runCatching {
            Cipher.getInstance("ChaCha20-Poly1305")
        }.getOrNull()
    }

    private val threadLocalBcCipher = object : ThreadLocal<ChaCha20Poly1305>() {
        override fun initialValue(): ChaCha20Poly1305 = ChaCha20Poly1305()
    }

    private val threadLocalNonce = object : ThreadLocal<ByteArray>() {
        override fun initialValue(): ByteArray = ByteArray(12)
    }

    fun blake2s256(vararg inputs: ByteArray): ByteArray {
        val digest = Blake2sDigest(256)
        for (input in inputs) {
            digest.update(input, 0, input.size)
        }
        val out = ByteArray(32)
        digest.doFinal(out, 0)
        return out
    }

    fun blake2s128Keyed(key: ByteArray, input: ByteArray, inputLen: Int = input.size): ByteArray {
        val digest = Blake2sDigest(key, 16, null, null)
        digest.update(input, 0, inputLen)
        val out = ByteArray(16)
        digest.doFinal(out, 0)
        return out
    }

    fun hmacBlake2s(key: ByteArray, vararg inputs: ByteArray): ByteArray {
        val blockSize = 64
        val normalizedKey = if (key.size > blockSize) {
            blake2s256(key).copyOf(blockSize)
        } else {
            key.copyOf(blockSize)
        }

        val iKeyPad = ByteArray(blockSize) { i -> (normalizedKey[i].toInt() xor 0x36).toByte() }
        val oKeyPad = ByteArray(blockSize) { i -> (normalizedKey[i].toInt() xor 0x5C).toByte() }

        val inner = Blake2sDigest(256)
        inner.update(iKeyPad, 0, blockSize)
        for (input in inputs) {
            inner.update(input, 0, input.size)
        }
        val innerHash = ByteArray(32)
        inner.doFinal(innerHash, 0)

        val outer = Blake2sDigest(256)
        outer.update(oKeyPad, 0, blockSize)
        outer.update(innerHash, 0, 32)
        val out = ByteArray(32)
        outer.doFinal(out, 0)
        return out
    }

    fun kdf1(key: ByteArray, input: ByteArray): ByteArray {
        val prk = hmacBlake2s(key, input)
        return hmacBlake2s(prk, byteArrayOf(0x01))
    }

    fun kdf2(key: ByteArray, input: ByteArray): Pair<ByteArray, ByteArray> {
        val prk = hmacBlake2s(key, input)
        val t1 = hmacBlake2s(prk, byteArrayOf(0x01))
        val t2 = hmacBlake2s(prk, t1, byteArrayOf(0x02))
        return t1 to t2
    }

    fun kdf3(key: ByteArray, input: ByteArray): Triple<ByteArray, ByteArray, ByteArray> {
        val prk = hmacBlake2s(key, input)
        val t1 = hmacBlake2s(prk, byteArrayOf(0x01))
        val t2 = hmacBlake2s(prk, t1, byteArrayOf(0x02))
        val t3 = hmacBlake2s(prk, t2, byteArrayOf(0x03))
        return Triple(t1, t2, t3)
    }

    fun generateX25519KeyPair(): Pair<ByteArray, ByteArray> {
        val privBytes = ByteArray(32)
        secureRandom.nextBytes(privBytes)
        clampPrivateKey(privBytes)
        val privParam = X25519PrivateKeyParameters(privBytes, 0)
        val pubBytes = privParam.generatePublicKey().encoded
        return privBytes to pubBytes
    }

    fun publicKeyFromPrivate(privateKey: ByteArray): ByteArray {
        val copy = privateKey.copyOf(32)
        clampPrivateKey(copy)
        return X25519PrivateKeyParameters(copy, 0).generatePublicKey().encoded
    }

    fun x25519Dh(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        val copy = privateKey.copyOf(32)
        clampPrivateKey(copy)
        val privParam = X25519PrivateKeyParameters(copy, 0)
        val pubParam = X25519PublicKeyParameters(publicKey, 0)
        val agreement = X25519Agreement()
        agreement.init(privParam)
        val shared = ByteArray(32)
        agreement.calculateAgreement(pubParam, shared, 0)
        return shared
    }

    private fun clampPrivateKey(key: ByteArray) {
        key[0] = (key[0].toInt() and 248).toByte()
        key[31] = (key[31].toInt() and 127).toByte()
        key[31] = (key[31].toInt() or 64).toByte()
    }

    private fun fillNonce(counter: Long): ByteArray {
        val nonce = threadLocalNonce.get()!!
        nonce[0] = 0; nonce[1] = 0; nonce[2] = 0; nonce[3] = 0
        var c = counter
        for (i in 4..11) {
            nonce[i] = (c and 0xFFL).toByte()
            c = c ushr 8
        }
        return nonce
    }

    /**
     * Zero-allocation in-place ChaCha20-Poly1305 encryption into [output] at [outputOffset].
     * Uses Android's native BoringSSL hardware-accelerated JCE Cipher when available.
     * Returns the number of bytes written (inputLen + 16).
     */
    fun aeadEncryptInto(
        keySpec: SecretKeySpec?,
        keyParam: KeyParameter,
        counter: Long,
        input: ByteArray,
        inputOffset: Int,
        inputLen: Int,
        output: ByteArray,
        outputOffset: Int
    ): Int {
        val nonce = fillNonce(counter)
        if (useNativeJceCipher && keySpec != null) {
            val jce = threadLocalJceCipher.get()
            if (jce != null) {
                jce.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(nonce))
                return jce.doFinal(input, inputOffset, inputLen, output, outputOffset)
            }
        }
        val cipher = threadLocalBcCipher.get()!!
        cipher.init(true, AEADParameters(keyParam, 128, nonce, EMPTY_BYTES))
        val len = cipher.processBytes(input, inputOffset, inputLen, output, outputOffset)
        return len + cipher.doFinal(output, outputOffset + len)
    }

    /**
     * Zero-allocation in-place ChaCha20-Poly1305 decryption into [output] at [outputOffset].
     * Returns the number of plaintext bytes written (inputLen - 16), or -1 on auth failure.
     */
    fun aeadDecryptInto(
        keySpec: SecretKeySpec?,
        keyParam: KeyParameter,
        counter: Long,
        input: ByteArray,
        inputOffset: Int,
        inputLen: Int,
        output: ByteArray,
        outputOffset: Int
    ): Int {
        if (inputLen < 16) return -1
        val nonce = fillNonce(counter)
        return try {
            if (useNativeJceCipher && keySpec != null) {
                val jce = threadLocalJceCipher.get()
                if (jce != null) {
                    jce.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(nonce))
                    return jce.doFinal(input, inputOffset, inputLen, output, outputOffset)
                }
            }
            val cipher = threadLocalBcCipher.get()!!
            cipher.init(false, AEADParameters(keyParam, 128, nonce, EMPTY_BYTES))
            val len = cipher.processBytes(input, inputOffset, inputLen, output, outputOffset)
            len + cipher.doFinal(output, outputOffset + len)
        } catch (_: Exception) {
            -1
        }
    }

    fun aeadEncrypt(
        key: ByteArray,
        counter: Long,
        plaintext: ByteArray,
        aad: ByteArray
    ): ByteArray {
        val nonce = fillNonce(counter)
        val cipher = threadLocalBcCipher.get()!!
        cipher.init(true, AEADParameters(KeyParameter(key), 128, nonce, aad))
        val out = ByteArray(cipher.getOutputSize(plaintext.size))
        val len = cipher.processBytes(plaintext, 0, plaintext.size, out, 0)
        cipher.doFinal(out, len)
        return out
    }

    fun aeadDecrypt(
        key: ByteArray,
        counter: Long,
        ciphertext: ByteArray,
        aad: ByteArray
    ): ByteArray = aeadDecryptSlice(key, counter, ciphertext, 0, ciphertext.size, aad)

    fun aeadDecryptSlice(
        key: ByteArray,
        counter: Long,
        input: ByteArray,
        inputOffset: Int,
        inputLen: Int,
        aad: ByteArray
    ): ByteArray {
        val nonce = fillNonce(counter)
        val cipher = threadLocalBcCipher.get()!!
        cipher.init(false, AEADParameters(KeyParameter(key), 128, nonce, aad))
        val out = ByteArray(cipher.getOutputSize(inputLen))
        val len = cipher.processBytes(input, inputOffset, inputLen, out, 0)
        cipher.doFinal(out, len)
        return out
    }

    fun tai64nNow(): ByteArray {
        val now = Instant.now()
        val secs = 0x400000000000000AL + now.epochSecond
        val nanos = now.nano
        val out = ByteArray(12)
        ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN).apply {
            putLong(secs)
            putInt(nanos)
        }
        return out
    }

    fun nextSenderIndex(): Int = secureRandom.nextInt()
}
