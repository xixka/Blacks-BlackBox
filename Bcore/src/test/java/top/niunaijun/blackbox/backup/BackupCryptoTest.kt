/*
 * Copyright (C) 2026 Blacks-BlackBox contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package top.niunaijun.blackbox.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import top.niunaijun.blackbox.backup.exception.WrongPasswordException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random

/**
 * TC-U-07: encryption round-trip (correct password decrypts; wrong password raises
 * WrongPasswordException).
 */
class BackupCryptoTest {

    private fun payload(size: Int): ByteArray {
        val pattern = ByteArray(size)
        Random(7).nextBytes(pattern)
        return pattern
    }

    @Test
    fun correctPasswordRoundtripRestoresExactBytes() {
        val salt = BackupCrypto.newSalt()
        val key = BackupCrypto.deriveKey("correct-horse".toCharArray(), salt)
        val original = payload(256 * 1024) // multi-chunk payload to exercise streaming

        val cipherText = ByteArrayOutputStream()
        val nonce = BackupCrypto.newNonce()
        BackupCrypto.encryptingStream(cipherText, key, nonce).use { es ->
            es.write(original)
        }
        val encrypted = cipherText.toByteArray()

        // Encrypted payload = 4 magic + 12 nonce + ciphertext + 16 tag.
        assertEquals(original.size + BackupCrypto.NONCE_LENGTH_BYTES + 16 + 4, encrypted.size)
        assertTrue("payload must start with the 0x42424242 magic", BackupCrypto.isEntryEncrypted(encrypted))

        val decrypted = BackupCrypto.decryptingStream(ByteArrayInputStream(encrypted), key).use { it.readBytes() }
        assertTrue(original.contentEquals(decrypted))
    }

    @Test
    fun emptyEntryStillProducesMagicNonceAndTag() {
        val salt = BackupCrypto.newSalt()
        val key = BackupCrypto.deriveKey("pw".toCharArray(), salt)
        val out = ByteArrayOutputStream()
        BackupCrypto.encryptingStream(out, key, BackupCrypto.newNonce()).use { /* write nothing */ }
        val encrypted = out.toByteArray()
        assertEquals(4 + BackupCrypto.NONCE_LENGTH_BYTES + 16, encrypted.size)

        val decrypted = BackupCrypto.decryptingStream(ByteArrayInputStream(encrypted), key).use { it.readBytes() }
        assertEquals(0, decrypted.size)
    }

    @Test
    fun wrongPasswordRaisesWrongPasswordException() {
        val salt = BackupCrypto.newSalt()
        val key = BackupCrypto.deriveKey("right-password".toCharArray(), salt)
        val out = ByteArrayOutputStream()
        BackupCrypto.encryptingStream(out, key, BackupCrypto.newNonce()).use { it.write(payload(1024)) }
        val encrypted = out.toByteArray()

        val wrongKey = BackupCrypto.deriveKey("wrong-password".toCharArray(), salt)
        try {
            BackupCrypto.decryptingStream(ByteArrayInputStream(encrypted), wrongKey).use { it.readBytes() }
            fail("expected WrongPasswordException")
        } catch (e: WrongPasswordException) {
            assertTrue(
                "message should mention the GCM tag mismatch, was: ${e.message}",
                e.message!!.contains("tag mismatch") || e.message!!.contains("wrong password"),
            )
        }
    }

    @Test
    fun corruptedPayloadRaisesWrongPasswordException() {
        val salt = BackupCrypto.newSalt()
        val key = BackupCrypto.deriveKey("pw".toCharArray(), salt)
        val out = ByteArrayOutputStream()
        BackupCrypto.encryptingStream(out, key, BackupCrypto.newNonce()).use { it.write(payload(512)) }
        val encrypted = out.toByteArray()
        encrypted[encrypted.size - 1] = (encrypted[encrypted.size - 1] + 1).toByte() // flip tag byte

        try {
            BackupCrypto.decryptingStream(ByteArrayInputStream(encrypted), key).use { it.readBytes() }
            fail("expected WrongPasswordException")
        } catch (e: WrongPasswordException) {
            // expected
        }
    }

    @Test
    fun kdfConstantsMatchTheV1Spec() {
        assertEquals("PBKDF2WithHmacSHA256", BackupCrypto.KDF_ALGO)
        assertEquals(120000, BackupCrypto.DEFAULT_ITERATIONS)
        assertEquals(16, BackupCrypto.SALT_LENGTH_BYTES)
        assertEquals(256, BackupCrypto.KEY_BITS)
        assertEquals(12, BackupCrypto.NONCE_LENGTH_BYTES)
        assertEquals(0x42424242, BackupCrypto.ENTRY_MAGIC)
    }
}
