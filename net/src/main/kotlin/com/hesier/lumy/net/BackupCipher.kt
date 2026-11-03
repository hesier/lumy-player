package com.hesier.lumy.net

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Portable password-encrypted backups; no device-bound keys are used.
 *
 * Format (big endian): magic "LUMYENC\n" (8), version (1), PBKDF2 iteration count
 * (4), random salt (16), random GCM nonce (12), ciphertext, GCM tag (16).
 * The entire 41-byte header is authenticated as AAD. Version 2 uses 10,000
 * PBKDF2-HMAC-SHA1 iterations for responsive TV backups; legacy version 1 uses
 * 600,000. Both derive a 256-bit AES key and work on Android API 21. Only the
 * fixed version/cost pairs are accepted to prevent attacker-controlled work.
 *
 * Malformed/unsupported envelopes throw [IllegalArgumentException]. Authentication
 * failures (including an incorrect password) throw [java.security.GeneralSecurityException].
 * Callers own and should clear the password; these methods do not modify it.
 */
object BackupCipher {
    const val MAX_ENCODED_BYTES = 2 * 1024 * 1024
    private val MAGIC = byteArrayOf(0x4c, 0x55, 0x4d, 0x59, 0x45, 0x4e, 0x43, 0x0a)
    private const val VERSION = 2
    private const val ITERATIONS = 10_000
    private const val LEGACY_ITERATIONS = 600_000
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val TAG_BYTES = 16
    private const val HEADER_BYTES = 8 + 1 + 4 + SALT_BYTES + NONCE_BYTES
    private const val KEY_BITS = 256
    private val random = SecureRandom()

    fun encrypt(plain: ByteArray, password: CharArray): ByteArray {
        require(password.isNotEmpty()) { "Password must not be empty" }
        require(plain.size <= MAX_ENCODED_BYTES - HEADER_BYTES - TAG_BYTES) { "Backup is too large" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val header = ByteBuffer.allocate(HEADER_BYTES)
            .put(MAGIC).put(VERSION.toByte()).putInt(ITERATIONS).put(salt).put(nonce).array()
        val cipher = cipher(Cipher.ENCRYPT_MODE, PBEKeySpec(password, salt, ITERATIONS, KEY_BITS), nonce)
        cipher.updateAAD(header)
        return header + cipher.doFinal(plain)
    }

    fun decrypt(encoded: ByteArray, password: CharArray): ByteArray {
        require(password.isNotEmpty()) { "Password must not be empty" }
        require(encoded.size in (HEADER_BYTES + TAG_BYTES)..MAX_ENCODED_BYTES) { "Invalid backup size" }
        require(isEncrypted(encoded)) { "Invalid backup signature" }
        val header = ByteBuffer.wrap(encoded, 0, HEADER_BYTES)
        header.position(MAGIC.size)
        val iterations = when (header.get().toInt()) {
            1 -> LEGACY_ITERATIONS
            VERSION -> ITERATIONS
            else -> throw IllegalArgumentException("Unsupported backup version")
        }
        require(header.int == iterations) { "Unsupported backup key derivation cost" }
        val salt = ByteArray(SALT_BYTES).also(header::get)
        val nonce = ByteArray(NONCE_BYTES).also(header::get)
        val cipher = cipher(Cipher.DECRYPT_MODE, PBEKeySpec(password, salt, iterations, KEY_BITS), nonce)
        cipher.updateAAD(encoded, 0, HEADER_BYTES)
        return cipher.doFinal(encoded, HEADER_BYTES, encoded.size - HEADER_BYTES)
    }

    /** Recognizes the format signature, including truncated or unsupported versions. */
    fun isEncrypted(encoded: ByteArray): Boolean =
        encoded.size >= MAGIC.size && MAGIC.indices.all { encoded[it] == MAGIC[it] }

    private fun cipher(mode: Int, spec: PBEKeySpec, nonce: ByteArray): Cipher {
        val key = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce))
            }
        } finally {
            key.fill(0)
        }
    }
}
