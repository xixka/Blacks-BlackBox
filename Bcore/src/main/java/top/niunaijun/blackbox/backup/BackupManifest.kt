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

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import top.niunaijun.blackbox.backup.exception.UnsupportedFormatException
import top.niunaijun.blackbox.backup.exception.VerifyException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Device descriptor stored inside manifest.json.
 */
data class DeviceInfo(
    @SerializedName("model") val model: String?,
    @SerializedName("android_sdk") val androidSdk: Int,
)

/**
 * Key-derivation descriptor stored inside manifest.json when [BackupManifest.encrypted] is true.
 *
 * Format: PBKDF2WithHmacSHA256(password, salt = 16 random bytes, iterations = 120000,
 * keyBits = 256) -> AES-256-GCM key. Nonces are 12 random bytes per zip entry and are stored
 * inside the entry payload itself (after the 4-byte magic), therefore only their length is
 * recorded here.
 */
data class ManifestKdf(
    @SerializedName("algo") val algo: String,
    @SerializedName("salt") val salt: String,
    @SerializedName("iterations") val iterations: Int,
    @SerializedName("key_bits") val keyBits: Int,
    @SerializedName("nonce_bytes") val nonceBytes: Int,
)

/**
 * Per-application metadata recorded in manifest.json apps[].
 */
data class ManifestApp(
    @SerializedName("package_name") val packageName: String,
    @SerializedName("user_id") val userId: Int,
    @SerializedName("version_name") val versionName: String?,
    @SerializedName("version_code") val versionCode: Long,
    @SerializedName("signing_cert_sha256") val signingCertSha256: String?,
    @SerializedName("include_apk") val includeApk: Boolean,
    @SerializedName("exclude_cache") val excludeCache: Boolean,
    @SerializedName("possibly_inconsistent") val possiblyInconsistent: Boolean,
    @SerializedName("size_bytes") val sizeBytes: Long,
    @SerializedName("file_count") val fileCount: Int,
)

/**
 * A symbolic link discovered while packing. [path] is the zip-internal path of the link
 * ("apps/{escaped_pkg}/data_user/..."), [target] is the raw link target string exactly as
 * read from the source filesystem (may be relative or absolute).
 */
data class ManifestSymlink(
    @SerializedName("path") val path: String,
    @SerializedName("target") val target: String,
)

/**
 * Root of manifest.json. Field names on disk are snake_case (see @SerializedName).
 */
data class BackupManifest(
    @SerializedName("format_version") val formatVersion: Int = BackupManifest.FORMAT_VERSION,
    @SerializedName("app") val app: String = BackupManifest.APP_NAME,
    @SerializedName("app_version_code") val appVersionCode: Int = 0,
    @SerializedName("created_at") val createdAt: String = BackupManifest.newTimestamp(),
    @SerializedName("device") val device: DeviceInfo? = null,
    @SerializedName("encrypted") val encrypted: Boolean = false,
    @SerializedName("kdf") val kdf: ManifestKdf? = null,
    @SerializedName("apps") val apps: List<ManifestApp> = emptyList(),
    @SerializedName("symlinks") val symlinks: List<ManifestSymlink> = emptyList(),
) {
    companion object {
        /** Backup archive format version handled by this engine. */
        const val FORMAT_VERSION = 1
        const val APP_NAME = "Blacks-BlackBox"

        /** ISO8601 timestamp including timezone offset, e.g. 2026-11-22T12:00:00+0800. */
        fun newTimestamp(): String {
            // SimpleDateFormat on purpose: java.time needs API 26+ without desugaring.
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)
            return fmt.format(Date())
        }
    }
}

/**
 * Per-application entry.json payload, written to apps/{escaped_pkg}/entry.json.
 */
data class EntryInfo(
    @SerializedName("uid") val uid: Int,
    @SerializedName("version_name") val versionName: String?,
    @SerializedName("version_code") val versionCode: Long,
    @SerializedName("signing_cert_sha256") val signingCertSha256: String?,
    @SerializedName("size_bytes") val sizeBytes: Long,
    @SerializedName("file_count") val fileCount: Int,
)

/**
 * Gson based (de)serialization for [BackupManifest] and [EntryInfo], plus the shared hex /
 * sha256 helpers used across the backup engine.
 */
object BackupManifestCodec {
    private val gson = Gson()

    fun serialize(manifest: BackupManifest): ByteArray =
        gson.toJson(manifest).toByteArray(Charsets.UTF_8)

    /**
     * Parses manifest.json bytes.
     *
     * @throws VerifyException when the payload is not a JSON object / structurally broken.
     * @throws UnsupportedFormatException when format_version differs from [BackupManifest.FORMAT_VERSION]
     *         (the message always contains the offending version number).
     */
    fun deserialize(bytes: ByteArray): BackupManifest {
        val text = String(bytes, Charsets.UTF_8)
        val root = try {
            JsonParser.parseString(text)
        } catch (e: Exception) {
            throw VerifyException("manifest.json is not valid JSON: ${e.message}", e)
        }
        if (!root.isJsonObject) {
            throw VerifyException("manifest.json root must be a JSON object")
        }
        val obj = root.asJsonObject
        val version = try {
            obj.get("format_version")?.takeIf { it.isJsonPrimitive }?.asInt
        } catch (e: Exception) {
            throw VerifyException("manifest.json format_version is not a number: ${e.message}", e)
        } ?: throw VerifyException("manifest.json has no format_version field")
        if (version != BackupManifest.FORMAT_VERSION) {
            throw UnsupportedFormatException(
                "manifest format_version=$version is not supported by this engine " +
                    "(supported format_version=${BackupManifest.FORMAT_VERSION}); " +
                    "the archive may come from a newer or older app version"
            )
        }
        return try {
            gson.fromJson(obj, BackupManifest::class.java)
        } catch (e: Exception) {
            throw VerifyException("manifest.json has an unexpected structure: ${e.message}", e)
        }
    }

    fun serializeEntry(entry: EntryInfo): ByteArray =
        gson.toJson(entry).toByteArray(Charsets.UTF_8)

    fun deserializeEntry(bytes: ByteArray): EntryInfo = try {
        gson.fromJson(String(bytes, Charsets.UTF_8), EntryInfo::class.java)
            ?: throw VerifyException("entry.json is empty")
    } catch (e: VerifyException) {
        throw e
    } catch (e: Exception) {
        throw VerifyException("entry.json is malformed: ${e.message}", e)
    }

    fun sha256Hex(bytes: ByteArray): String = hex(
        MessageDigest.getInstance("SHA-256").digest(bytes)
    )

    fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    fun unhex(text: String): ByteArray {
        require(text.length % 2 == 0) { "hex string must have even length: ${text.length}" }
        val out = ByteArray(text.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(text[i * 2], 16)
            val lo = Character.digit(text[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "invalid hex character in input at offset ${i * 2}" }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private const val HEX = "0123456789abcdef"
}
