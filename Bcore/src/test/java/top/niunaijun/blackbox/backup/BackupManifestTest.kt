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
import top.niunaijun.blackbox.backup.exception.UnsupportedFormatException
import top.niunaijun.blackbox.backup.exception.VerifyException

/**
 * TC-U-01: BackupManifest serialization round-trip (field-by-field equality, including the
 * empty apps list and null kdf boundaries).
 * TC-U-02: format_version=999 -> UnsupportedFormatException with the version in the message.
 */
class BackupManifestTest {

    // ---------------------------------------------------------------- TC-U-01

    @Test
    fun `manifest roundtrip keeps every field`() {
        val manifest = BackupManifest(
            formatVersion = BackupManifest.FORMAT_VERSION,
            app = BackupManifest.APP_NAME,
            appVersionCode = 460,
            createdAt = "2026-11-22T12:00:00+0800",
            device = DeviceInfo(model = "Pixel 8", androidSdk = 34),
            encrypted = true,
            kdf = ManifestKdf(
                algo = BackupCrypto.KDF_ALGO,
                salt = "00112233445566778899aabbccddeeff",
                iterations = 120000,
                keyBits = 256,
                nonceBytes = 12,
            ),
            apps = listOf(
                ManifestApp(
                    packageName = "com.example.app",
                    userId = 0,
                    versionName = "1.2.3",
                    versionCode = 123,
                    signingCertSha256 = "deadbeef",
                    includeApk = true,
                    excludeCache = true,
                    possiblyInconsistent = false,
                    sizeBytes = 4096,
                    fileCount = 12,
                ),
                ManifestApp(
                    packageName = "com.example.another",
                    userId = 11,
                    versionName = null,
                    versionCode = 42,
                    signingCertSha256 = null,
                    includeApk = false,
                    excludeCache = false,
                    possiblyInconsistent = true,
                    sizeBytes = 0,
                    fileCount = 0,
                ),
            ),
            symlinks = listOf(ManifestSymlink(path = "apps/com.example.app/data_user/lnk", target = "files/hello.txt")),
        )

        val bytes = BackupManifestCodec.serialize(manifest)
        val restored = BackupManifestCodec.deserialize(bytes)

        assertEquals(manifest.formatVersion, restored.formatVersion)
        assertEquals(manifest.app, restored.app)
        assertEquals(manifest.appVersionCode, restored.appVersionCode)
        assertEquals(manifest.createdAt, restored.createdAt)
        assertEquals(manifest.device, restored.device)
        assertEquals(manifest.encrypted, restored.encrypted)
        assertEquals(manifest.kdf, restored.kdf)
        assertEquals(manifest.symlinks, restored.symlinks)
        assertEquals(manifest.apps.size, restored.apps.size)
        for (i in manifest.apps.indices) {
            assertEquals(manifest.apps[i], restored.apps[i])
        }
    }

    @Test
    fun `manifest roundtrip with empty apps and null kdf`() {
        val manifest = BackupManifest(
            appVersionCode = 0,
            device = DeviceInfo(model = null, androidSdk = 21),
            encrypted = false,
            kdf = null,
            apps = emptyList(),
            symlinks = emptyList(),
        )

        val bytes = BackupManifestCodec.serialize(manifest)
        val restored = BackupManifestCodec.deserialize(bytes)

        assertEquals(manifest, restored)
        assertTrue(restored.apps.isEmpty())
        assertEquals(null, restored.kdf)
    }

    @Test
    fun `serialized manifest uses the snake_case v1 field names`() {
        val json = String(
            BackupManifestCodec.serialize(
                BackupManifest(
                    apps = listOf(
                        ManifestApp(
                            packageName = "com.a", userId = 0, versionName = "1", versionCode = 1,
                            signingCertSha256 = null, includeApk = true, excludeCache = true,
                            possiblyInconsistent = false, sizeBytes = 1, fileCount = 1,
                        ),
                    ),
                ),
            ),
            Charsets.UTF_8,
        )
        assertTrue(json.contains("\"format_version\":1"))
        assertTrue(json.contains("\"app_version_code\""))
        assertTrue(json.contains("\"created_at\""))
        assertTrue(json.contains("\"package_name\":\"com.a\""))
        assertTrue(json.contains("\"user_id\":0"))
        assertTrue(json.contains("\"include_apk\""))
        assertTrue(json.contains("\"exclude_cache\""))
        assertTrue(json.contains("\"possibly_inconsistent\""))
        assertTrue(json.contains("\"size_bytes\""))
        assertTrue(json.contains("\"file_count\""))
    }

    @Test
    fun `entry info roundtrip`() {
        val entry = EntryInfo(
            uid = 10042, versionName = "9.9", versionCode = 909, signingCertSha256 = "abc", sizeBytes = 77, fileCount = 5,
        )
        val restored = BackupManifestCodec.deserializeEntry(BackupManifestCodec.serializeEntry(entry))
        assertEquals(entry, restored)
    }

    // ---------------------------------------------------------------- TC-U-02

    @Test
    fun `format_version 999 is rejected with the version in the message`() {
        val json = """
            {
              "format_version": 999,
              "app": "Blacks-BlackBox",
              "apps": [],
              "symlinks": []
            }
        """.trimIndent()

        try {
            BackupManifestCodec.deserialize(json.toByteArray(Charsets.UTF_8))
            fail("expected UnsupportedFormatException")
        } catch (e: UnsupportedFormatException) {
            assertTrue(
                "message must contain the unsupported version number, was: ${e.message}",
                e.message!!.contains("999"),
            )
        }
    }

    @Test
    fun `format_version 2 is rejected as well`() {
        val json = """{"format_version": 2, "apps": []}"""
        try {
            BackupManifestCodec.deserialize(json.toByteArray(Charsets.UTF_8))
            fail("expected UnsupportedFormatException")
        } catch (e: UnsupportedFormatException) {
            assertTrue(e.message!!.contains("2"))
        }
    }

    @Test
    fun `malformed manifest raises VerifyException`() {
        try {
            BackupManifestCodec.deserialize("this is not json".toByteArray(Charsets.UTF_8))
            fail("expected VerifyException")
        } catch (e: VerifyException) {
            // expected
        }
    }

    @Test
    fun `missing format_version raises VerifyException`() {
        val json = """{"app": "Blacks-BlackBox", "apps": []}"""
        try {
            BackupManifestCodec.deserialize(json.toByteArray(Charsets.UTF_8))
            fail("expected VerifyException")
        } catch (e: VerifyException) {
            // expected
        }
    }
}
