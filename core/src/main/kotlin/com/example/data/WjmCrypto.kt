package com.example.data

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Thrown when an encrypted `.wjm` file is invalid, corrupted, tampered with, or unsupported. */
class WjmFormatException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/**
 * Authenticated AES-256-GCM binary container for encrypted `.wjm` macro bundles exported by
 * Wjust macro Studio and playable exclusively inside Wjust Player.
 *
 * Binary layout:
 * - `[0..3]`   Magic header: `"WJM1"` (`0x57 0x4A 0x4D 0x31`)
 * - `[4]`      Format version byte: `0x01`
 * - `[5..20]`  Random 16-byte per-file salt
 * - `[21..32]` Random 12-byte GCM nonce (IV)
 * - `[33..]`   AES-256-GCM ciphertext + 128-bit authentication tag (AAD = `[0..20]`)
 */
object WjmCrypto {
    private val MAGIC = byteArrayOf('W'.code.toByte(), 'J'.code.toByte(), 'M'.code.toByte(), '1'.code.toByte())
    private const val VERSION_BYTE: Byte = 0x01
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val GCM_TAG_BITS = 128
    private const val HEADER_AAD_LEN = 4 + 1 + SALT_LEN // 21 bytes
    private const val MIN_FILE_LEN = HEADER_AAD_LEN + IV_LEN + (GCM_TAG_BITS / 8) // 49 bytes
    private const val KDF_ROUNDS = 2048

    private val secureRandom = SecureRandom()

    fun isWjmHeader(prefix: ByteArray, length: Int = prefix.size): Boolean {
        if (length < MAGIC.size) return false
        return prefix[0] == MAGIC[0] &&
            prefix[1] == MAGIC[1] &&
            prefix[2] == MAGIC[2] &&
            prefix[3] == MAGIC[3]
    }

    /** Encrypts raw ZIP bundle bytes into an authenticated `.wjm` byte array. */
    fun encryptZipBytes(zipBytes: ByteArray): ByteArray {
        if (zipBytes.isEmpty()) {
            throw WjmFormatException("Data macro kosong dan tidak dapat dienkripsi.")
        }
        val salt = ByteArray(SALT_LEN).also { secureRandom.nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { secureRandom.nextBytes(it) }

        val aad = ByteArray(HEADER_AAD_LEN)
        System.arraycopy(MAGIC, 0, aad, 0, MAGIC.size)
        aad[4] = VERSION_BYTE
        System.arraycopy(salt, 0, aad, 5, SALT_LEN)

        val key = deriveAes256Key(salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aad)
        val cipherBytes = cipher.doFinal(zipBytes)

        val out = ByteArrayOutputStream(HEADER_AAD_LEN + IV_LEN + cipherBytes.size)
        out.write(aad)
        out.write(iv)
        out.write(cipherBytes)
        return out.toByteArray()
    }

    /** Encrypts [zipBytes] and writes the `.wjm` container to [output]. */
    fun encryptZipToStream(zipBytes: ByteArray, output: OutputStream) {
        val encrypted = encryptZipBytes(zipBytes)
        output.write(encrypted)
        output.flush()
    }

    /**
     * Decrypts and authenticates a `.wjm` container back into raw ZIP bundle bytes.
     * Throws [WjmFormatException] if header, version, length, or GCM authentication tag is invalid.
     */
    fun decryptZipBytes(wjmBytes: ByteArray): ByteArray {
        if (wjmBytes.size < MIN_FILE_LEN || !isWjmHeader(wjmBytes)) {
            throw WjmFormatException("File bukan format terenkripsi .wjm yang valid dari Wjust macro.")
        }
        val version = wjmBytes[4]
        if (version != VERSION_BYTE) {
            throw WjmFormatException("Versi format .wjm (v$version) tidak didukung. Perbarui Wjust Player.")
        }

        val aad = wjmBytes.copyOfRange(0, HEADER_AAD_LEN)
        val salt = wjmBytes.copyOfRange(5, HEADER_AAD_LEN)
        val iv = wjmBytes.copyOfRange(HEADER_AAD_LEN, HEADER_AAD_LEN + IV_LEN)
        val cipherOffset = HEADER_AAD_LEN + IV_LEN
        val cipherLen = wjmBytes.size - cipherOffset

        return try {
            val key = deriveAes256Key(salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(aad)
            cipher.doFinal(wjmBytes, cipherOffset, cipherLen)
        } catch (t: Throwable) {
            throw WjmFormatException("File .wjm rusak, tidak valid, atau telah dimodifikasi.", t)
        }
    }

    private fun deriveAes256Key(salt: ByteArray): SecretKeySpec {
        val master = assembleMasterMaterial()
        val md = MessageDigest.getInstance("SHA-256")
        md.update(master)
        md.update(salt)
        md.update(MAGIC)
        var state = md.digest()
        for (round in 1 until KDF_ROUNDS) {
            md.reset()
            md.update(state)
            md.update(salt)
            md.update((round and 0xFF).toByte())
            md.update(((round ushr 8) and 0xFF).toByte())
            state = md.digest()
        }
        return SecretKeySpec(state, "AES")
    }

    private fun assembleMasterMaterial(): ByteArray {
        val p1 = intArrayOf(0x57, 0x6A, 0x75, 0x73, 0x74, 0x50, 0x6C, 0x61, 0x79)
        val p2 = intArrayOf(0x2D, 0x57, 0x4A, 0x4D, 0x2D, 0x56, 0x31, 0x2D)
        val p3 = intArrayOf(0x4D, 0x61, 0x63, 0x72, 0x6F, 0x45, 0x6E, 0x63, 0x23, 0x39, 0x37, 0x34, 0x31)
        val mask = 0x5A
        val out = ByteArray(p1.size + p2.size + p3.size)
        var idx = 0
        for (v in p1) out[idx++] = (v xor mask).toByte()
        for (v in p2) out[idx++] = (v xor (mask ushr 1)).toByte()
        for (v in p3) out[idx++] = (v xor 0x3C).toByte()
        return out
    }
}
