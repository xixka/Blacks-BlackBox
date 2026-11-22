/*
 * Copyright 2026 Black's BlackBox contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.onebitmonochrome.blacksbbox.view.backup

import com.onebitmonochrome.blacksbbox.data.AppSortEntry
import com.onebitmonochrome.blacksbbox.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import top.niunaijun.blackbox.backup.BackupManifest
import top.niunaijun.blackbox.backup.BackupManifestCodec
import top.niunaijun.blackbox.backup.DeviceInfo
import top.niunaijun.blackbox.backup.ManifestApp
import top.niunaijun.blackbox.backup.FailureReason
import top.niunaijun.blackbox.backup.exception.InsufficientSpaceException
import top.niunaijun.blackbox.backup.exception.MissingApkException
import top.niunaijun.blackbox.backup.exception.UnsupportedFormatException
import top.niunaijun.blackbox.backup.exception.VerifyException
import top.niunaijun.blackbox.backup.exception.WrongPasswordException
import java.util.TimeZone

/**
 * Pure JVM tests for the backup/restore UI logic (no android.* calls, no Robolectric).
 */
class BackupUiLogicTest {

    private var originalTimeZone: TimeZone? = null

    @Before
    fun setUp() {
        originalTimeZone = TimeZone.getDefault()
    }

    @After
    fun tearDown() {
        originalTimeZone?.let { TimeZone.setDefault(it) }
    }

    // -----------------------------------------------------------------------------------------
    // Password validation
    // -----------------------------------------------------------------------------------------

    @Test
    fun `checkPasswordPair treats blank pair as no encryption`() {
        assertEquals(PasswordValidation.None, BackupUiLogic.checkPasswordPair("", ""))
        assertEquals(PasswordValidation.None, BackupUiLogic.checkPasswordPair(null, null))
        assertEquals(PasswordValidation.None, BackupUiLogic.checkPasswordPair("   ", "  "))
    }

    @Test
    fun `checkPasswordPair accepts equal non-blank passwords`() {
        val result = BackupUiLogic.checkPasswordPair("secret", "secret")
        assertTrue(result is PasswordValidation.Valid)
        assertEquals("secret", String((result as PasswordValidation.Valid).password))
    }

    @Test
    fun `checkPasswordPair rejects mismatched or half-filled passwords`() {
        assertEquals(PasswordValidation.Mismatch, BackupUiLogic.checkPasswordPair("a", "b"))
        assertEquals(PasswordValidation.Mismatch, BackupUiLogic.checkPasswordPair("a", null))
        assertEquals(PasswordValidation.Mismatch, BackupUiLogic.checkPasswordPair(null, "b"))
        assertEquals(PasswordValidation.Mismatch, BackupUiLogic.checkPasswordPair("", "b"))
        assertEquals(PasswordValidation.Mismatch, BackupUiLogic.checkPasswordPair("a", ""))
    }

    // -----------------------------------------------------------------------------------------
    // Suggested file name
    // -----------------------------------------------------------------------------------------

    @Test
    fun `backupFileName formats timestamp pattern`() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        assertEquals(
                "blackbox_backup_19700101_000000.zip",
                BackupUiLogic.backupFileName(0L),
        )
        // 2026-11-22 12:34:56 UTC
        assertEquals(
                "blackbox_backup_20261122_123456.zip",
                BackupUiLogic.backupFileName(1795350896000L),
        )
    }

    @Test
    fun `backupFileName is deterministic and shape-stable in any timezone`() {
        val now = System.currentTimeMillis()
        val first = BackupUiLogic.backupFileName(now)
        assertEquals(first, BackupUiLogic.backupFileName(now))
        assertTrue(first.matches(Regex("blackbox_backup_\\d{8}_\\d{6}\\.zip")))
        assertTrue(first.startsWith("blackbox_backup_"))
        assertTrue(first.endsWith(".zip"))
    }

    // -----------------------------------------------------------------------------------------
    // Failure reason mapping
    // -----------------------------------------------------------------------------------------

    @Test
    fun `failureReasonRes maps every reason to a distinct resource`() {
        val all = listOf(
                FailureReason.NOT_INSTALLED,
                FailureReason.INSTALL_FAILED,
                FailureReason.MISSING_APK,
                FailureReason.SPACE_INSUFFICIENT,
                FailureReason.VERIFY_FAILED,
                FailureReason.IO_ERROR,
                FailureReason.UNKNOWN,
        )
        val mapped = all.map { BackupUiLogic.failureReasonRes(it) }
        assertEquals(all.size, mapped.toSet().size)
        assertEquals(R.string.backup_failure_not_installed, mapped[0])
        assertEquals(R.string.backup_failure_install_failed, mapped[1])
        assertEquals(R.string.backup_failure_missing_apk, mapped[2])
        assertEquals(R.string.backup_failure_space_insufficient, mapped[3])
        assertEquals(R.string.backup_failure_verify_failed, mapped[4])
        assertEquals(R.string.backup_failure_io_error, mapped[5])
        assertEquals(R.string.backup_failure_unknown, mapped[6])
    }

    // -----------------------------------------------------------------------------------------
    // Global error mapping
    // -----------------------------------------------------------------------------------------

    @Test
    fun `errorResFor maps engine exceptions to user messages`() {
        assertEquals(
                R.string.backup_error_unsupported_format,
                BackupUiLogic.errorResFor(UnsupportedFormatException("format_version=2")),
        )
        assertEquals(
                R.string.backup_error_verify,
                BackupUiLogic.errorResFor(VerifyException("sha mismatch")),
        )
        assertEquals(
                R.string.backup_error_wrong_password,
                BackupUiLogic.errorResFor(WrongPasswordException("bad magic")),
        )
        assertEquals(
                R.string.backup_error_space,
                BackupUiLogic.errorResFor(InsufficientSpaceException("no space")),
        )
        assertEquals(
                R.string.backup_error_missing_apk,
                BackupUiLogic.errorResFor(MissingApkException("no apk")),
        )
        assertEquals(
                R.string.backup_error_generic,
                BackupUiLogic.errorResFor(IllegalStateException("boom")),
        )
    }

    @Test
    fun `extractFormatVersion parses the offending version`() {
        assertEquals(
                2,
                BackupUiLogic.extractFormatVersion(
                        "manifest format_version=2 is not supported by this engine"
                ),
        )
        assertEquals(
                10,
                BackupUiLogic.extractFormatVersion(
                        "manifest format_version=10 is not supported by this engine"
                ),
        )
        assertNull(BackupUiLogic.extractFormatVersion(null))
        assertNull(BackupUiLogic.extractFormatVersion(""))
        assertNull(BackupUiLogic.extractFormatVersion("no version in here"))
    }

    // -----------------------------------------------------------------------------------------
    // HostPrefs <-> app stores
    // -----------------------------------------------------------------------------------------

    @Test
    fun `buildHostPrefs and parse round trip preserve sort remarks and freeze flags`() {
        val hostPrefs = BackupUiLogic.buildHostPrefs(
                sortRaw = "com.a@0,com.b@1,com.a@0",
                spaceRemarks = mapOf(1 to "工作空间", 2 to ""),
                frozenApps = listOf(AppSortEntry("com.c", 0)),
        )

        val sortEntries = BackupUiLogic.parseRestoredSortEntries(hostPrefs.entries)
        assertEquals(
                listOf(AppSortEntry("com.a", 0), AppSortEntry("com.b", 1)),
                sortEntries,
        )

        val remarks = BackupUiLogic.parseRestoredSpaceRemarks(hostPrefs.entries)
        assertEquals(mapOf(1 to "工作空间"), remarks)

        val frozen = BackupUiLogic.parseRestoredFrozenApps(hostPrefs.entries)
        assertEquals(listOf(AppSortEntry("com.c", 0)), frozen)
    }

    @Test
    fun `parseRestoredSortEntries sorts by index and pushes unreadable indexes last`() {
        val entries = mapOf(
                "sort:com.b@1" to "junk",
                "sort:com.a@0" to "2",
                "note:space:0" to "ignored here",
        )
        val restored = BackupUiLogic.parseRestoredSortEntries(entries)
        assertEquals(listOf(AppSortEntry("com.a", 0), AppSortEntry("com.b", 1)), restored)
    }

    @Test
    fun `parseRestoredFrozenApps only accepts true values and valid keys`() {
        val restored = BackupUiLogic.parseRestoredFrozenApps(
                mapOf(
                        "frozen:com.a@0" to "true",
                        "frozen:com.b@1" to "false",
                        "frozen:com.c@2" to "TRUE",
                        "frozen:junk" to "true",
                )
        )
        assertEquals(listOf(AppSortEntry("com.a", 0)), restored)
    }

    @Test
    fun `mergeSortEntries keeps restored order first then appends unseen current entries`() {
        val restored = listOf(
                AppSortEntry("com.b", 0),
                AppSortEntry("com.a", 0),
        )
        val current = listOf(
                AppSortEntry("com.a", 0),
                AppSortEntry("com.c", 1),
        )
        assertEquals(
                listOf(
                        AppSortEntry("com.b", 0),
                        AppSortEntry("com.a", 0),
                        AppSortEntry("com.c", 1),
                ),
                BackupUiLogic.mergeSortEntries(restored, current),
        )
    }

    @Test
    fun `buildTargetUserIdMapping returns null for default target`() {
        assertNull(BackupUiLogic.buildTargetUserIdMapping(listOf(0, 2), null))
        assertEquals(
                mapOf(0 to 5, 2 to 5),
                BackupUiLogic.buildTargetUserIdMapping(listOf(0, 2), 5),
        )
        assertEquals(emptyMap<Int, Int>(), BackupUiLogic.buildTargetUserIdMapping(emptyList(), 3))
    }

    // -----------------------------------------------------------------------------------------
    // Manifest preview
    // -----------------------------------------------------------------------------------------

    private fun manifestApp(
            pkg: String,
            userId: Int,
            version: String?,
            includeApk: Boolean,
            sizeBytes: Long,
    ): ManifestApp = ManifestApp(
            packageName = pkg,
            userId = userId,
            versionName = version,
            versionCode = 42L,
            signingCertSha256 = null,
            includeApk = includeApk,
            excludeCache = true,
            possiblyInconsistent = false,
            sizeBytes = sizeBytes,
            fileCount = 3,
    )

    @Test
    fun `previewRows projects manifest apps`() {
        val manifest = BackupManifest(
                device = DeviceInfo(model = "Pixel 8", androidSdk = 34),
                apps = listOf(
                        manifestApp("com.a", 0, "1.2.3", true, 1536L),
                        manifestApp("com.b", 2, null, false, 1024L * 1024L),
                ),
        )
        val rows = BackupUiLogic.previewRows(manifest)
        assertEquals(2, rows.size)
        assertEquals("com.a", rows[0].packageName)
        assertEquals(0, rows[0].userId)
        assertEquals("1.2.3", rows[0].versionName)
        assertEquals(1536L, rows[0].sizeBytes)
        assertTrue(rows[0].includeApk)
        assertNull(rows[1].versionName)
        assertEquals(2, rows[1].userId)
        assertTrue(!rows[1].includeApk)
    }

    @Test
    fun `manifest codec round trip keeps preview data`() {
        val manifest = BackupManifest(
                device = DeviceInfo(model = "Pixel 8", androidSdk = 34),
                apps = listOf(manifestApp("com.a", 0, "1.0", true, 512L)),
        )
        val bytes = BackupManifestCodec.serialize(manifest)
        val parsed = BackupManifestCodec.deserialize(bytes)
        assertEquals(BackupManifest.FORMAT_VERSION, parsed.formatVersion)
        assertEquals("Pixel 8", parsed.device?.model)
        assertEquals(1, parsed.apps.size)
        assertEquals("com.a", parsed.apps[0].packageName)
        assertEquals(0, parsed.apps[0].userId)
        assertTrue(parsed.apps[0].includeApk)
        assertEquals(512L, parsed.apps[0].sizeBytes)

        val rows = BackupUiLogic.previewRows(parsed)
        assertEquals(1, rows.size)
        assertEquals("com.a", rows[0].packageName)
    }

    // -----------------------------------------------------------------------------------------
    // Timestamp / size formatting
    // -----------------------------------------------------------------------------------------

    @Test
    fun `formatTimestamp renders manifest timestamps`() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        assertEquals(
                "2026-11-22 12:00",
                BackupUiLogic.formatTimestamp("2026-11-22T12:00:00+0000"),
        )
        assertNull(BackupUiLogic.formatTimestamp(null))
        assertNull(BackupUiLogic.formatTimestamp(""))
        assertNull(BackupUiLogic.formatTimestamp("not a date"))
    }

    @Test
    fun `formatSize renders human readable units`() {
        assertEquals("0 B", BackupUiLogic.formatSize(0L))
        assertEquals("512 B", BackupUiLogic.formatSize(512L))
        assertEquals("1.0 KB", BackupUiLogic.formatSize(1024L))
        assertEquals("1.5 KB", BackupUiLogic.formatSize(1536L))
        assertEquals("1.0 MB", BackupUiLogic.formatSize(1024L * 1024L))
        assertEquals("1.0 GB", BackupUiLogic.formatSize(1024L * 1024L * 1024L))
        assertEquals("0 B", BackupUiLogic.formatSize(-5L))
    }

    // -----------------------------------------------------------------------------------------
    // Stage labels
    // -----------------------------------------------------------------------------------------

    @Test
    fun `stageToLabelRes covers all engine stages`() {
        assertEquals(R.string.backup_stage_prepare, BackupUiLogic.stageToLabelRes("prepare"))
        assertEquals(R.string.backup_stage_pack, BackupUiLogic.stageToLabelRes("pack:com.a"))
        assertEquals(
                R.string.restore_stage_install,
                BackupUiLogic.stageToLabelRes("install:com.a"),
        )
        assertEquals(R.string.restore_stage_unpack, BackupUiLogic.stageToLabelRes("unpack"))
        assertEquals(R.string.restore_stage_symlinks, BackupUiLogic.stageToLabelRes("symlinks"))
        assertEquals(R.string.backup_stage_finalize, BackupUiLogic.stageToLabelRes("finalize"))
        assertEquals(R.string.restore_stage_done, BackupUiLogic.stageToLabelRes("done"))
        assertEquals(R.string.backup_stage_generic, BackupUiLogic.stageToLabelRes("mystery"))
    }

    @Test
    fun `stageArg extracts package names from stage labels`() {
        assertEquals("com.example.app", BackupUiLogic.stageArg("pack:com.example.app"))
        assertEquals("com.example.app", BackupUiLogic.stageArg("install:com.example.app"))
        assertNull(BackupUiLogic.stageArg("prepare"))
        assertNull(BackupUiLogic.stageArg("unpack"))
        assertNull(BackupUiLogic.stageArg("finalize"))
    }

    // -----------------------------------------------------------------------------------------
    // Password attempt limit contract
    // -----------------------------------------------------------------------------------------

    @Test
    fun `password attempts are limited`() {
        assertTrue(BackupUiLogic.MAX_PASSWORD_ATTEMPTS >= 2)
        assertEquals(3, BackupUiLogic.MAX_PASSWORD_ATTEMPTS)
    }
}
