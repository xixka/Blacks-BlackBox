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

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.niunaijun.blackbox.backup.BackupArchive
import top.niunaijun.blackbox.backup.BackupManifestCodec
import top.niunaijun.blackbox.backup.ylb.exception.YlbFormatException
import java.io.File

/**
 * convertToV1: the converted archive must be a valid blackbox-backup v1 archive (read back
 * with BackupArchive.readHeader + BackupManifestCodec) with the ylb import markers.
 */
class YlbConvertToV1Test {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun parseStandardSample(): YlbBackupParser.YlbParseResult {
        val zip = YlbTestSupport.createAesZip(
            tmp.root,
            "mundo_backup.zip",
            YlbTestSupport.fixedSamplePassword(),
            YlbTestSupport.standardSampleEntries(),
        )
        val parsed = YlbBackupParser.parse(zip, YlbTestSupport.FIXED_MNEMONIC.toCharArray(), null)
        // Caller owns cleanup; individual tests call it when done.
        return parsed
    }

    @Test
    fun `converted archive is a valid v1 backup with ylb markers`() {
        val parsed = parseStandardSample()
        try {
            val staging = tmp.newFolder("staging")
            val v1 = YlbBackupParser.convertToV1(parsed, targetUserId = 7, stagingDir = staging)
            assertTrue(v1.isFile)

            val header = BackupArchive.readHeader(v1)
            val manifest = BackupManifestCodec.deserialize(header.manifestBytes)

            assertEquals(1, manifest.formatVersion)
            assertEquals("YlbImport", manifest.app)
            assertFalse(manifest.encrypted)
            assertNull(manifest.kdf)
            assertEquals(1, manifest.apps.size)

            val app = manifest.apps[0]
            assertEquals(YlbTestSupport.SAMPLE_PKG, app.packageName)
            assertEquals(7, app.userId)
            assertTrue(app.possiblyInconsistent)
            assertTrue(app.includeApk)
            assertTrue(app.excludeCache)
            assertNull(app.versionName)
            assertEquals(0L, app.versionCode)
            assertNull(app.signingCertSha256)

            // manifest.sha256 integrity exactly as the restore pipeline verifies it.
            val storedSha = header.manifestSha256Hex
            assertTrue(storedSha != null)
            assertEquals(
                storedSha!!.lowercase(),
                BackupManifestCodec.sha256Hex(header.manifestBytes).lowercase(),
            )
        } finally {
            parsed.cleanup()
        }
    }

    @Test
    fun `converted archive contains the v1 engine layout and payload bytes`() {
        val parsed = parseStandardSample()
        try {
            val v1 = YlbBackupParser.convertToV1(parsed, targetUserId = 0, stagingDir = tmp.newFolder("staging2"))
            val names = v1ZipNames(v1)
            val esc = BackupArchive.escapePkg(YlbTestSupport.SAMPLE_PKG)
            val root = BackupArchive.APPS_ROOT + esc + "/"

            assertTrue(names.contains(root + BackupArchive.ENTRY_JSON))
            // base.apk must come from the APK-typed app_split entry, not the foza staging one.
            val baseApk = BackupArchive.readNamedEntry(v1, root + BackupArchive.BASE_APK_NAME, null)!!
            assertArrayEquals(YlbTestSupport.SAMPLE_ENTRY_SPLIT_BASE_APK.second, baseApk)
            // splits + data_user routing follows the engine restore conventions.
            assertTrue(
                names.contains(root + BackupArchive.SPLITS_SECTION + "/split_config.arm64_v8a.apk"),
            )
            assertTrue(names.contains(root + BackupArchive.DATA_USER_SECTION + "/files/hello.txt"))
            assertTrue(names.contains(root + BackupArchive.DATA_USER_SECTION + "/files/1.txt"))
            assertTrue(names.contains(root + BackupArchive.DATA_USER_SECTION + "/databases/app.db"))
            // Unattributed entries never leak into the v1 archive.
            assertFalse(names.contains("logs/orphan.bin"))
            assertFalse(names.any { it.contains("orphan") })

            val hello =
                BackupArchive.readNamedEntry(v1, root + BackupArchive.DATA_USER_SECTION + "/files/hello.txt", null)!!
            assertArrayEquals(YlbTestSupport.SAMPLE_ENTRY_HELLO_TXT.second, hello)

            // entry.json is readable by the production codec.
            val entryBytes = BackupArchive.readNamedEntry(v1, root + BackupArchive.ENTRY_JSON, null)!!
            val entry = BackupManifestCodec.deserializeEntry(entryBytes)
            assertEquals(0, entry.uid)
            assertEquals(6, entry.fileCount) // base + 1 split + 4 data files
        } finally {
            parsed.cleanup()
        }
    }

    @Test
    fun `conversion without attributable packages fails explicitly`() {
        val entries = listOf(YlbTestSupport.SAMPLE_ENTRY_ORPHAN)
        val zip = YlbTestSupport.createAesZip(
            tmp.root,
            "only_unknown.zip",
            YlbTestSupport.fixedSamplePassword(),
            entries,
        )
        val parsed = YlbBackupParser.parse(zip, YlbTestSupport.FIXED_MNEMONIC.toCharArray(), null)
        try {
            assertEquals(0, parsed.entries.size)
            assertEquals(1, parsed.unknownEntries.size)
            try {
                YlbBackupParser.convertToV1(parsed, targetUserId = 0, stagingDir = tmp.newFolder("staging3"))
                fail("expected YlbFormatException")
            } catch (e: YlbFormatException) {
                assertTrue(e.message!!.contains("nothing to convert"))
            }
        } finally {
            parsed.cleanup()
        }
    }

    @Test
    fun `missing staging dir is created and output file is named for ylb imports`() {
        val parsed = parseStandardSample()
        try {
            val staging = File(tmp.root, "brand_new_staging_dir")
            val v1 = YlbBackupParser.convertToV1(parsed, targetUserId = 0, stagingDir = staging)
            assertTrue(staging.isDirectory)
            assertTrue(v1.name.startsWith("ylb_import_v1_"))
            assertTrue(v1.name.endsWith(".zip"))
        } finally {
            parsed.cleanup()
        }
    }

    private fun v1ZipNames(file: File): List<String> = BackupArchive.readHeader(file).entryNames
}
