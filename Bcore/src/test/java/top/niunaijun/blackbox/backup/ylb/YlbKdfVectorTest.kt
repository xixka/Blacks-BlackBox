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
package top.niunaijun.blackbox.backup.ylb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import top.niunaijun.blackbox.backup.ylb.exception.YlbFormatException
import java.math.BigInteger

/**
 * TC-U-09: fixed key derivation vectors.
 *
 * Provenance of every asserted value (all public specification material, no Meta Wolf assets):
 *  - BIP39 seeds: official test vectors of the BIP39 reference implementation
 *    (trezor/python-mnemonic vectors.json, entry 0: entropy 000...000).
 *  - BIP32: official BIP32 test vector 1 (bip-0032.mediawiki "Test vector 1",
 *    seed 000102030405060708090a0b0c0d0e0f); the asserted raw key/chain-code pairs are the
 *    base58check-decoded payloads of the specification's xprv strings.
 *  - Full chain m/44'/60'/0'/0/0: composition of the verified primitives (BIP39 seed for the
 *    vector mnemonic with empty passphrase + verified BIP32 hardened/normal CKDpriv).
 */
class YlbKdfVectorTest {

    // ---------------------------------------------------------------- BIP39 official vectors

    @Test
    fun `bip39 official vector - abandon x11 about with passphrase TREZOR`() {
        val seed = Bip39.mnemonicToSeed(
            YlbTestSupport.FIXED_MNEMONIC.toCharArray(),
            "TREZOR".toCharArray(),
        )
        assertEquals(
            "c55257c360c07c72029aebc1b53c05ed0362ada38ead3e3e9efa3708e53495531f09a6987599d18264c1e1c92f2cf141630c7a3c4ab7c81b2f001698e7463b04",
            BackupManifestCodecHex(seed),
        )
    }

    @Test
    fun `bip39 official vector - abandon x11 about with empty passphrase`() {
        val seed = Bip39.mnemonicToSeed(YlbTestSupport.FIXED_MNEMONIC.toCharArray(), CharArray(0))
        assertEquals(
            "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4",
            BackupManifestCodecHex(seed),
        )
    }

    @Test
    fun `bip39 seed derivation is deterministic and normalization is applied`() {
        val a = Bip39.mnemonicToSeed(YlbTestSupport.FIXED_MNEMONIC.toCharArray(), CharArray(0))
        val b = Bip39.mnemonicToSeed(
            "  ABANDON  abandon   abandon abandon abandon abandon abandon abandon abandon abandon abandon about ".toCharArray(),
            CharArray(0),
        )
        assertTrue(a.contentEquals(b))
        assertEquals(64, a.size)
    }

    // ---------------------------------------------------------------- BIP32 spec test vector 1

    @Test
    fun `bip32 test vector 1 - master key from seed 000102030405060708090a0b0c0d0e0f`() {
        val master = Bip32.masterFromSeed(hexToBytes("000102030405060708090a0b0c0d0e0f"))
        assertNotNull(master)
        assertEquals(
            "e8f32e723decf4051aefac8e2c93c9c5b214313817cdb01a1494b917c8436b35",
            master!!.key.toString(16).padStart(64, '0'),
        )
        assertEquals(
            "873dff81c02f525623fd1fe5167eac3a55a049de3d314bb42ee227ffed37d508",
            toHex(master.chainCode),
        )
    }

    @Test
    fun `bip32 test vector 1 - hardened m-0-prime`() {
        val master = Bip32.masterFromSeed(hexToBytes("000102030405060708090a0b0c0d0e0f"))!!
        val child = Bip32.ckdPriv(master, 0, hardened = true)!!
        assertEquals(
            "edb2e14f9ee77d26dd93b4ecede8d16ed408ce149b6cd80b0715a2d911a0afea",
            child.key.toString(16).padStart(64, '0'),
        )
        assertEquals(
            "47fdacbd0f1097043b78c63c20c34ef4ed9a111d980047ad16282c7ae6236141",
            toHex(child.chainCode),
        )
    }

    @Test
    fun `bip32 test vector 1 - normal derivation m-0-prime-1 exercises EC point math`() {
        val master = Bip32.masterFromSeed(hexToBytes("000102030405060708090a0b0c0d0e0f"))!!
        val hardened = Bip32.ckdPriv(master, 0, hardened = true)!!
        val child = Bip32.ckdPriv(hardened, 1, hardened = false)!!
        assertEquals(
            "3c6cb8d0f6a264c91ea8b5030fadaa8e538b020f0a387421a12de9319dc93368",
            child.key.toString(16).padStart(64, '0'),
        )
        assertEquals(
            "2a7857631386ba23dacac34180dd1983734e444fdbf774041578e9b6adb37c19",
            toHex(child.chainCode),
        )
    }

    // ---------------------------------------------------------------- full chain (BIP44 ETH)

    @Test
    fun `full chain - fixed mnemonic empty passphrase to m-44-60-0-0-0`() {
        val seed = Bip39.mnemonicToSeed(YlbTestSupport.FIXED_MNEMONIC.toCharArray(), CharArray(0))
        val master = Bip32.masterFromSeed(seed)!!
        val n44 = Bip32.ckdPriv(master, 44, hardened = true)!!
        val n60 = Bip32.ckdPriv(n44, 60, hardened = true)!!
        val n0h = Bip32.ckdPriv(n60, 0, hardened = true)!!
        val n0 = Bip32.ckdPriv(n0h, 0, hardened = false)!!
        val n0again = Bip32.ckdPriv(n0, 0, hardened = false)!!
        assertEquals(
            YlbTestSupport.FIXED_MNEMONIC_KEY_HEX,
            n0again.key.toString(16).padStart(64, '0'),
        )
    }

    @Test
    fun `private key hex forms - raw, padded 64, padded 66 with duplicate collapse`() {
        // Key whose hex already has 64 chars: raw == padded64 -> collapsed to 2 forms.
        val key64 = BigInteger(1, hexToBytes(YlbTestSupport.FIXED_MNEMONIC_KEY_HEX))
        assertEquals(
            listOf(
                "1ab42cc412b618bdea3a599e3c9bae199ebf030895b039e9db1e30dafb12b727",
                "001ab42cc412b618bdea3a599e3c9bae199ebf030895b039e9db1e30dafb12b727",
            ),
            Bip32.privateKeyHexForms(key64),
        )
        // Short raw hex (leading zero byte): 3 distinct forms.
        val shortKey = BigInteger(1, hexToBytes("000f" + "ab".repeat(30)))
        assertEquals(
            listOf(
                "f" + "ab".repeat(30),
                "000f" + "ab".repeat(30),
                "00000f" + "ab".repeat(30),
            ),
            Bip32.privateKeyHexForms(shortKey),
        )
    }

    // ---------------------------------------------------------------- candidate sequence shape

    @Test
    fun `candidate sequence - 10 paths, 3 hex forms per key, mode collapse without password`() {
        val noPassword = YlbBackupParser.buildPasswordCandidates(
            YlbTestSupport.FIXED_MNEMONIC.toCharArray(),
            null,
        )
        assertEquals(10, noPassword.derivationPathAttempts)
        assertEquals(1, noPassword.passphraseModes)
        assertTrue(noPassword.candidates.size <= YlbBackupParser.MAX_CANDIDATES)
        assertTrue(noPassword.candidates.size >= 10)
        // The standard path padded-64 hex of the fixed mnemonic must be among the candidates.
        val labels = noPassword.candidates.map { it.label }
        assertTrue(labels.any { it.contains("m/44'/60'/0'/0/0") && it.contains("64") })
        // Duplicates collapsed: every candidate password string is distinct.
        val passwords = noPassword.candidates.map { String(it.password) }
        assertEquals(passwords.size, passwords.toSet().size)
        noPassword.wipeAll()
    }

    @Test
    fun `candidate sequence - password produces both passphrase modes`() {
        val withPassword = YlbBackupParser.buildPasswordCandidates(
            YlbTestSupport.FIXED_MNEMONIC.toCharArray(),
            "app-password".toCharArray(),
        )
        assertEquals(20, withPassword.derivationPathAttempts)
        assertEquals(2, withPassword.passphraseModes)
        assertTrue(withPassword.candidates.size <= YlbBackupParser.MAX_CANDIDATES)
        withPassword.wipeAll()
    }

    // ---------------------------------------------------------------- validation failures

    @Test
    fun `mnemonic validation - wrong word count`() {
        try {
            Bip39.validate("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon".toCharArray())
            fail("expected YlbFormatException")
        } catch (e: YlbFormatException) {
            assertTrue(e.message!!.contains("word count"))
        }
    }

    @Test
    fun `mnemonic validation - word outside the BIP39 English list`() {
        try {
            Bip39.validate("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon notaword".toCharArray())
            fail("expected YlbFormatException")
        } catch (e: YlbFormatException) {
            assertTrue(e.message!!.contains("word list"))
        }
    }

    @Test
    fun `mnemonic validation - broken checksum (12 x abandon)`() {
        try {
            Bip39.validate(
                "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon".toCharArray(),
            )
            fail("expected YlbFormatException")
        } catch (e: YlbFormatException) {
            assertTrue(e.message!!.contains("checksum"))
        }
    }

    @Test
    fun `mnemonic validation - official vector mnemonics pass`() {
        assertTrue(Bip39.isValid(YlbTestSupport.FIXED_MNEMONIC.toCharArray()))
        assertTrue(Bip39.isValid(YlbTestSupport.OTHER_VALID_MNEMONIC.toCharArray()))
        assertFalse(Bip39.isValid("not a mnemonic".toCharArray()))
    }

    @Test
    fun `invalid master seeds are rejected`() {
        // HMAC("Bitcoin seed", <seed>) yields IL; a seed of all zeros produces IL = 0 case only
        // with negligible probability, so the boundary is tested directly on the scalar check.
        assertFalse(Bip32.isValidScalar(BigInteger.ZERO))
        assertFalse(Bip32.isValidScalar(Bip32Order()))
        assertTrue(Bip32.isValidScalar(Bip32Order().subtract(BigInteger.ONE)))
        // Master of an all-zero 64-byte seed is some deterministic value; must be either a
        // valid node or null (never an out-of-range scalar).
        val master = Bip32.masterFromSeed(ByteArray(64))
        if (master != null) {
            assertTrue(Bip32.isValidScalar(master.key))
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Local hex helper (keeps the test independent from the production codec). */
    private fun BackupManifestCodecHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4]).append("0123456789abcdef"[v and 0x0F])
        }
        return sb.toString()
    }

    private fun toHex(bytes: ByteArray): String = BackupManifestCodecHex(bytes)

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(hex[i * 2], 16) shl 4) or Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
        return out
    }

    /** secp256k1 group order n (kept local so the boundary test does not depend on internals). */
    private fun Bip32Order(): BigInteger = BigInteger(
        "fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141",
        16,
    )
}
