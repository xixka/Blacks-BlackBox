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

import java.math.BigInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Minimal BIP32 hierarchical deterministic key derivation on secp256k1, pure JDK
 * (java.math.BigInteger + javax.crypto.Mac). Only what the Meta Wolf importer needs:
 * master key generation from a BIP39 seed and private-child key derivation
 * (CKDpriv, hardened and normal), see https://github.com/bitcoin/bips/blob/master/bip-0032.mediawiki.
 *
 * Verified against the official BIP32 test vector 1
 * (seed 000102030405060708090a0b0c0d0e0f), see YlbKdfVectorTest.
 *
 * Per the BIP32 spec, a derivation whose IL is 0 or >= curve order produces no valid key;
 * such results are reported as null (the caller logs and skips the candidate) instead of
 * throwing, because this is a legitimate per-candidate outcome in the importer's trial loop.
 */
object Bip32 {

    /** Bit that marks a hardened (private) derivation index. */
    const val HARDENED_BIT: Int = Int.MIN_VALUE

    /** Order n of the secp256k1 group. */
    private val CURVE_ORDER_N = BigInteger("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141", 16)

    /** Field prime p of secp256k1 (2^256 - 2^32 - 977). */
    private val FIELD_PRIME_P = BigInteger("fffffffffffffffffffffffffffffffffffffffffffffffffffffffefffffc2f", 16)

    private val GENERATOR_X = BigInteger("79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798", 16)
    private val GENERATOR_Y = BigInteger("483ada7726a3c4655da4fbfc0e1108a8fd17b448a68554199c47d08ffb10d4b8", 16)

    private const val HMAC_KEY = "Bitcoin seed"

    /** A BIP32 extended private key node: scalar key + 32-byte chain code. */
    class Node(val key: BigInteger, val chainCode: ByteArray)

    // -----------------------------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------------------------

    /**
     * BIP32 master key: I = HMAC-SHA512(key = "Bitcoin seed", data = seed);
     * IL (first 32 bytes) is the master key, IR (last 32 bytes) the master chain code.
     *
     * @return null when IL is invalid (0 or >= n) - the seed produces no usable master key.
     */
    fun masterFromSeed(seed: ByteArray): Node? {
        val i = hmacSha512(HMAC_KEY.toByteArray(Charsets.US_ASCII), seed)
        val il = BigInteger(1, i.copyOfRange(0, 32))
        if (!isValidScalar(il)) return null
        return Node(il, i.copyOfRange(32, 64))
    }

    /**
     * CKDpriv: one child derivation step.
     *
     * @param index the index WITHOUT the hardened bit; set [hardened] = true for hardened
     *        derivation (index + 2^31 in the BIP32 notation "m/44'").
     * @return null when the derived IL is invalid (0 or >= n) or the child key would be 0.
     */
    fun ckdPriv(parent: Node, index: Int, hardened: Boolean): Node? {
        // Non-hardened indices use the low 31 bits; the hardened flag is passed separately.
        require(index >= 0) { "index out of range: $index" }
        val data = ByteArray(37)
        if (hardened) {
            // ser256(k_par) with a leading 0x00 marker + ser32(index).
            val keyBytes = bigIntegerTo32Bytes(parent.key)
            System.arraycopy(keyBytes, 0, data, 1, 32)
        } else {
            // serP(point(k_par)) compressed + ser32(index).
            val compressed = compressedPublicKey(parent.key)
            System.arraycopy(compressed, 0, data, 0, 33)
        }
        writeInt32(data, 33, index)
        val i = hmacSha512(parent.chainCode, data)
        val il = BigInteger(1, i.copyOfRange(0, 32))
        if (!isValidScalar(il)) return null
        val childKey = il.add(parent.key).mod(CURVE_ORDER_N)
        if (childKey.signum() == 0) return null
        return Node(childKey, i.copyOfRange(32, 64))
    }

    /** A scalar is a valid private key when 0 < k < n (BIP32 candidate rule). */
    fun isValidScalar(k: BigInteger): Boolean = k.signum() > 0 && k < CURVE_ORDER_N

    /**
     * The three hex string forms of a private key that the Meta Wolf zip password may use.
     * BigInteger.toString(16) drops leading zero bytes, so the candidates are (in order):
     * raw toString(16), zero padded to 64 characters, zero padded to 66 characters.
     * Duplicates (raw already 64 chars) are removed while preserving order.
     */
    fun privateKeyHexForms(key: BigInteger): List<String> {
        val raw = key.toString(16)
        val forms = LinkedHashSet<String>(3)
        forms.add(raw)
        forms.add(raw.padStart(64, '0'))
        forms.add(raw.padStart(66, '0'))
        return forms.toList()
    }

    // -----------------------------------------------------------------------------------------
    // HMAC-SHA512
    // -----------------------------------------------------------------------------------------

    private fun hmacSha512(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(key, "HmacSHA512"))
        return mac.doFinal(data)
    }

    // -----------------------------------------------------------------------------------------
    // secp256k1 point arithmetic (affine coordinates, BigInteger only)
    // -----------------------------------------------------------------------------------------

    /** Elliptic curve point; both coordinates null means the point at infinity. */
    private class Point(val x: BigInteger?, val y: BigInteger?)

    private val POINT_INFINITY = Point(null, null)
    private val GENERATOR = Point(GENERATOR_X, GENERATOR_Y)

    /**
     * Compressed SEC1 encoding of the public key: 0x02/0x03 prefix (parity of y) + 32-byte x.
     * @throws IllegalStateException when the scalar is not in [1, n) (caller bug).
     */
    internal fun compressedPublicKey(k: BigInteger): ByteArray {
        require(isValidScalar(k)) { "scalar out of range" }
        val p = scalarMultiply(k)
        if (p.x == null || p.y == null) throw IllegalStateException("scalar multiplication reached infinity")
        val out = ByteArray(33)
        out[0] = if (p.y.testBit(0)) 3 else 2
        System.arraycopy(bigIntegerTo32Bytes(p.x), 0, out, 1, 32)
        return out
    }

    /** Double-and-add scalar multiplication of the generator. */
    private fun scalarMultiply(k: BigInteger): Point {
        var acc = POINT_INFINITY
        var base = GENERATOR
        var remaining = k.mod(CURVE_ORDER_N)
        while (remaining.signum() > 0) {
            if (remaining.testBit(0)) acc = pointAdd(acc, base)
            base = pointDouble(base)
            remaining = remaining.shiftRight(1)
        }
        return acc
    }

    private fun pointAdd(p1: Point, p2: Point): Point {
        if (p1.x == null) return p2
        if (p2.x == null) return p1
        val x1 = p1.x
        val y1 = p1.y!!
        val x2 = p2.x
        val y2 = p2.y!!
        if (x1 == x2) {
            return if (y1 == y2) pointDouble(p1) else POINT_INFINITY
        }
        val slope = y2.subtract(y1).multiply(x2.subtract(x1).modInverse(FIELD_PRIME_P)).mod(FIELD_PRIME_P)
        val x3 = slope.multiply(slope).subtract(x1).subtract(x2).mod(FIELD_PRIME_P)
        val y3 = slope.multiply(x1.subtract(x3)).subtract(y1).mod(FIELD_PRIME_P)
        return Point(x3, y3)
    }

    private fun pointDouble(p: Point): Point {
        if (p.x == null || p.y == null) return POINT_INFINITY
        val x = p.x
        val y = p.y
        if (y.signum() == 0) return POINT_INFINITY
        val slope = BigInteger.valueOf(3).multiply(x).multiply(x)
            .multiply(y.shiftLeft(1).modInverse(FIELD_PRIME_P)).mod(FIELD_PRIME_P)
        val x3 = slope.multiply(slope).subtract(x.shiftLeft(1)).mod(FIELD_PRIME_P)
        val y3 = slope.multiply(x.subtract(x3)).subtract(y).mod(FIELD_PRIME_P)
        return Point(x3, y3)
    }

    // -----------------------------------------------------------------------------------------
    // Serialization helpers
    // -----------------------------------------------------------------------------------------

    private fun bigIntegerTo32Bytes(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        val out = ByteArray(32)
        if (raw.size == 32) {
            System.arraycopy(raw, 0, out, 0, 32)
        } else if (raw.size > 32) {
            // Leading zero byte from the two's complement encoding; the value fits in 32 bytes
            // because the caller validated 0 <= value < 2^256.
            System.arraycopy(raw, raw.size - 32, out, 0, 32)
        } else {
            System.arraycopy(raw, 0, out, 32 - raw.size, raw.size)
        }
        return out
    }

    private fun writeInt32(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value ushr 24).toByte()
        data[offset + 1] = (value ushr 16).toByte()
        data[offset + 2] = (value ushr 8).toByte()
        data[offset + 3] = value.toByte()
    }
}
