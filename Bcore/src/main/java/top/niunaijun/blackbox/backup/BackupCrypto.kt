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

import top.niunaijun.blackbox.backup.exception.VerifyException
import top.niunaijun.blackbox.backup.exception.WrongPasswordException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Optional per-entry encryption for the backup archive (blackbox-backup v1).
 *
 * Scheme (engine spec):
 *  - Key: PBKDF2WithHmacSHA256(password, salt = 16 random bytes, iterations = 120000,
 *    keyBits = 256) -> AES-256-GCM secret key. Salt / iterations / key size / nonce length are
 *    recorded in manifest.json "kdf".
 *  - Every zip entry payload (except manifest.json / manifest.sha256, which stay plaintext so
 *    the archive stays listable) is stored as:
 *        [4-byte magic 0x42424242][12-byte nonce][AES-256-GCM ciphertext + 16-byte tag]
 *  - Entry NAMES remain plaintext inside the zip; only entry CONTENTS are encrypted.
 *  - The nonce is fresh random bytes per entry, generated at pack time and embedded in the
 *    entry payload, so no nonce bookkeeping is needed.
 *
 * Streaming: both wrappers are incremental (8KB+ chunks), never buffering whole entries.
 */
object BackupCrypto {
    const val KDF_ALGO = "PBKDF2WithHmacSHA256"
    const val DEFAULT_ITERATIONS = 120000
    const val SALT_LENGTH_BYTES = 16
    const val KEY_BITS = 256
    const val NONCE_LENGTH_BYTES = 12
    const val GCM_TAG_BITS = 128

    /** Magic prefix written at the head of every encrypted entry payload. */
    const val ENTRY_MAGIC = 0x42424242

    private val MAGIC_BYTES = byteArrayOf(0x42, 0x42, 0x42, 0x42)
    private const val CHUNK_SIZE = 64 * 1024

    private val random = SecureRandom()

    fun newSalt(length: Int = SALT_LENGTH_BYTES): ByteArray = randomBytes(length)

    fun newNonce(length: Int = NONCE_LENGTH_BYTES): ByteArray = randomBytes(length)

    private fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    fun deriveKey(
        password: CharArray,
        salt: ByteArray,
        iterations: Int = DEFAULT_ITERATIONS,
        keyBits: Int = KEY_BITS,
    ): SecretKey {
        require(password.isNotEmpty()) { "password must not be empty" }
        require(salt.isNotEmpty()) { "salt must not be empty" }
        val factory = SecretKeyFactory.getInstance(KDF_ALGO)
        val spec = PBEKeySpec(password, salt, iterations, keyBits)
        return try {
            val encoded = factory.generateSecret(spec).encoded
            SecretKeySpec(encoded, "AES")
        } finally {
            // Clear the password copy held by PBEKeySpec as soon as possible.
            spec.clearPassword()
        }
    }

    fun isEntryEncrypted(firstFourBytes: ByteArray): Boolean =
        firstFourBytes.size >= 4 &&
            firstFourBytes[0] == MAGIC_BYTES[0] &&
            firstFourBytes[1] == MAGIC_BYTES[1] &&
            firstFourBytes[2] == MAGIC_BYTES[2] &&
            firstFourBytes[3] == MAGIC_BYTES[3]

    /**
     * Creates an encrypting stream over [sink]. The magic bytes and a fresh [nonce] are written
     * immediately; ciphertext+tag are written incrementally as data flows through.
     *
     * IMPORTANT: closing the returned stream finalizes the GCM tag but does NOT close [sink]
     * (the caller keeps control of the zip stream to continue with further entries).
     */
    fun encryptingStream(sink: OutputStream, key: SecretKey, nonce: ByteArray): OutputStream {
        require(nonce.size == NONCE_LENGTH_BYTES) {
            "GCM nonce must be $NONCE_LENGTH_BYTES bytes, got ${nonce.size}"
        }
        sink.write(MAGIC_BYTES)
        sink.write(nonce)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        return GcmEncryptStream(sink, cipher)
    }

    /**
     * Creates a decrypting stream over [source]. The stream expects the source to start with
     * the 4-byte magic followed by the nonce; both are consumed by this call.
     *
     * @throws VerifyException if the magic header is missing (corrupted/misrouted entry).
     * @throws WrongPasswordException on GCM tag mismatch while reading (wrong password / corrupted data).
     */
    fun decryptingStream(source: InputStream, key: SecretKey): InputStream {
        val magic = ByteArray(4)
        readFully(source, magic)
        if (!isEntryEncrypted(magic)) {
            throw VerifyException("encrypted entry payload is missing the 0x42424242 magic header")
        }
        val nonce = ByteArray(NONCE_LENGTH_BYTES)
        readFully(source, nonce)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        return GcmDecryptStream(source, cipher)
    }

    private fun readFully(source: InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val n = source.read(target, offset, target.size - offset)
            if (n < 0) {
                throw VerifyException("unexpected end of stream while reading entry header")
            }
            offset += n
        }
    }

    private class GcmEncryptStream(
        private val sink: OutputStream,
        private val cipher: Cipher,
    ) : OutputStream() {
        private var closed = false

        override fun write(b: Int) {
            checkNotClosed()
            val one = byteArrayOf(b.toByte())
            val out = cipher.update(one)
            if (out != null && out.isNotEmpty()) {
                sink.write(out)
            }
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            checkNotClosed()
            val out = cipher.update(b, off, len)
            if (out != null && out.isNotEmpty()) {
                sink.write(out)
            }
        }

        override fun flush() {
            sink.flush()
        }

        override fun close() {
            if (closed) return
            closed = true
            // Finalize: emit the 16-byte GCM authentication tag. Do NOT close the sink; the
            // zip entry lifecycle is owned by the caller (closeEntry).
            val tag = cipher.doFinal()
            if (tag.isNotEmpty()) {
                sink.write(tag)
            }
            sink.flush()
        }

        private fun checkNotClosed() {
            if (closed) throw IOException("encrypting stream already closed")
        }
    }

    private class GcmDecryptStream(
        private val source: InputStream,
        private val cipher: Cipher,
    ) : InputStream() {
        private val inBuf = ByteArray(CHUNK_SIZE)
        private var pending: ByteArray = EMPTY
        private var pendingPos = 0
        private var sourceEof = false
        private var finalized = false

        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (pendingPos >= pending.size) {
                if (!refill()) return -1
            }
            val n = minOf(len, pending.size - pendingPos)
            System.arraycopy(pending, pendingPos, b, off, n)
            pendingPos += n
            return n
        }

        /** Pulls the next chunk of plaintext out of the cipher, returning false at true EOF. */
        private fun refill(): Boolean {
            while (true) {
                if (sourceEof && finalized) return false
                if (!sourceEof) {
                    val n = source.read(inBuf, 0, inBuf.size)
                    if (n > 0) {
                        pending = cipher.update(inBuf, 0, n) ?: EMPTY
                        pendingPos = 0
                        if (pending.isNotEmpty()) return true
                        // update() buffered into GCM internals; keep pulling.
                        continue
                    }
                    if (n < 0) {
                        sourceEof = true
                    }
                    // n == 0 should not happen for len > 0; loop defensively.
                    continue
                }
                // Source exhausted: finish the cipher to emit the trailing tag / final plaintext.
                finalized = true
                pending = try {
                    cipher.doFinal() ?: EMPTY
                } catch (e: Exception) {
                    // AEADBadTagException and friends mean wrong password or corrupted payload.
                    throw WrongPasswordException(
                        "failed to decrypt entry: authentication tag mismatch (wrong password or corrupted data)",
                        e,
                    )
                }
                pendingPos = 0
                return pending.isNotEmpty()
            }
        }

        override fun close() {
            // Intentionally does NOT close the underlying zip stream.
        }

        companion object {
            private val EMPTY = ByteArray(0)
        }
    }
}
