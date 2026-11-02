package com.hesier.lumy.net

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.security.GeneralSecurityException

class BackupCipherTest {
    private val password = "备份密碼🔑123".toCharArray()
    private val plain = "{\"server\":\"https://example.test\",\"token\":\"secret\"}".toByteArray()

    @Test fun `roundtrip preserves data and caller password`() {
        val originalPassword = password.copyOf()
        val encoded = BackupCipher.encrypt(plain, password)
        assertTrue(BackupCipher.isEncrypted(encoded))
        assertArrayEquals(plain, BackupCipher.decrypt(encoded, password))
        assertArrayEquals(originalPassword, password)
        assertFalse(String(encoded, Charsets.ISO_8859_1).contains("secret"))
    }

    @Test fun `empty and binary payloads roundtrip`() {
        for (payload in listOf(byteArrayOf(), ByteArray(256) { it.toByte() })) {
            assertArrayEquals(payload, BackupCipher.decrypt(BackupCipher.encrypt(payload, password), password))
        }
    }

    @Test fun `decrypts independent UTF8 password fixture`() {
        // Generated with Python hashlib.pbkdf2_hmac and cryptography AESGCM.
        val hex = "4c554d59454e430a01000927c0000102030405060708090a0b0c0d0e0f" +
            "101112131415161718191a1b968bc0175099b3f2494769eb33ca4ed71e1fd8c02e66007a" +
            "c63e3c810a0daf29cec2cf26597cb7d710fb"
        val encoded = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertArrayEquals("independent backup fixture".toByteArray(), BackupCipher.decrypt(encoded, password))
    }

    @Test fun `random salt and nonce produce distinct envelopes`() {
        val first = BackupCipher.encrypt(plain, password)
        val second = BackupCipher.encrypt(plain, password)
        assertFalse(first.contentEquals(second))
        assertFalse(first.copyOfRange(13, 29).contentEquals(second.copyOfRange(13, 29)))
        assertFalse(first.copyOfRange(29, 41).contentEquals(second.copyOfRange(29, 41)))
        assertArrayEquals(plain, BackupCipher.decrypt(second, password))
    }

    @Test fun `wrong password and authenticated data corruption fail`() {
        val encoded = BackupCipher.encrypt(plain, password)
        assertThrows(GeneralSecurityException::class.java) {
            BackupCipher.decrypt(encoded, "incorrect".toCharArray())
        }
        for (offset in listOf(13, 29, 41, encoded.lastIndex)) {
            val corrupted = encoded.copyOf()
            corrupted[offset] = (corrupted[offset].toInt() xor 1).toByte()
            assertThrows(GeneralSecurityException::class.java) { BackupCipher.decrypt(corrupted, password) }
        }
    }

    @Test fun `malformed signature version and cost are rejected before key derivation`() {
        val encoded = BackupCipher.encrypt(plain, password)
        val badMagic = encoded.copyOf().also { it[0] = 0 }
        assertFalse(BackupCipher.isEncrypted(badMagic))
        assertInvalid(badMagic)
        val futureVersion = encoded.copyOf().also { it[8] = 2 }
        assertTrue(BackupCipher.isEncrypted(futureVersion))
        assertInvalid(futureVersion)
        for (cost in listOf(0, -1, 1, 599_999, 600_001, Int.MAX_VALUE)) {
            assertInvalid(encoded.copyOf().also { ByteBuffer.wrap(it).putInt(9, cost) })
        }
    }

    @Test fun `truncated and appended envelopes are rejected`() {
        val encoded = BackupCipher.encrypt(plain, password)
        for (size in listOf(0, 7, 8, 9, 13, 29, 41, 56)) {
            assertInvalid(encoded.copyOf(size))
        }
        assertFalse(BackupCipher.isEncrypted(byteArrayOf()))
        assertTrue(BackupCipher.isEncrypted(encoded.copyOf(8)))
        assertThrows(GeneralSecurityException::class.java) {
            BackupCipher.decrypt(encoded.copyOf(encoded.size - 1), password)
        }
        assertThrows(GeneralSecurityException::class.java) {
            BackupCipher.decrypt(encoded + byteArrayOf(0), password)
        }
    }

    @Test fun `size limits include envelope overhead`() {
        val maximumPlain = ByteArray(BackupCipher.MAX_ENCODED_BYTES - 57) { (it % 251).toByte() }
        val encoded = BackupCipher.encrypt(maximumPlain, password)
        assertEquals(BackupCipher.MAX_ENCODED_BYTES, encoded.size)
        assertArrayEquals(maximumPlain, BackupCipher.decrypt(encoded, password))
        assertThrows(IllegalArgumentException::class.java) {
            BackupCipher.encrypt(ByteArray(maximumPlain.size + 1), password)
        }
        assertInvalid(ByteArray(BackupCipher.MAX_ENCODED_BYTES + 1))
    }

    @Test fun `empty password is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { BackupCipher.encrypt(plain, charArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { BackupCipher.decrypt(byteArrayOf(), charArrayOf()) }
    }

    private fun assertInvalid(encoded: ByteArray) {
        assertThrows(IllegalArgumentException::class.java) { BackupCipher.decrypt(encoded, password) }
    }
}
