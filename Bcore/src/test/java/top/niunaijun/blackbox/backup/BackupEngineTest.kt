/*
 * Copyright (C) 2026 Blacks-BlackBox contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may not use this file except in compliance with the License.
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.niunaijun.blackbox.backup.BackupTestSupport.backupToBytes
import top.niunaijun.blackbox.backup.BackupTestSupport.buildAppSkeleton
import top.niunaijun.blackbox.backup.BackupTestSupport.engine
import top.niunaijun.blackbox.backup.BackupTestSupport.meta
import top.niunaijun.blackbox.backup.BackupTestSupport.sha256Hex
import top.niunaijun.blackbox.backup.exception.InsufficientSpaceException
import top.niunaijun.blackbox.backup.exception.VerifyException
import top.niunaijun.blackbox.backup.exception.WrongPasswordException
import java.io.ByteArrayInputStream
import java.io.File

/**
 * TC-U-05: injected SpaceChecker returning 0 -> InsufficientSpaceException.
 * TC-U-14: compareSigningCert decision function (mismatch -> warning flag).
 * Plus encrypted end-to-end restore (password roundtrip through BackupManager-level engine flow).
 */
class BackupEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---------------------------------------------------------------- TC-U-05

    @Test
    fun insufficientSpaceThrowsInsufficientSpaceException() {
        val source = tmp.newFolder("src5")
        val vr = File(source, "vr")
        vr.mkdirs()
        buildAppSkeleton(vr, null, "com.test.app", 0)
        // 1MB of data so requiredBytes = ceil(size * 1.1) > 0.
        File(vr, "data/user/0/com.test.app/files").mkdirs()
        File(vr, "data/user/0/com.test.app/files/big.bin").writeBytes(ByteArray(1024 * 1024))

        val packages = BackupTestSupport.FakePackages(mapOf("com.test.app#0" to meta("com.test.app", 0)))
        val engine = BackupEngine(
            roots = BackupTestSupport.FakeRoots(vr, null),
            spaceChecker = { 0L }, // fake checker: no space at all
            packages = packages,
            bridge = BackupTestSupport.FakeBridge(),
            tempDirProvider = { tmp.newFolder("temp5") },
            stopWaitMillis = 0L,
        )

        try {
            engine.backup(
                listOf(BackupRequest("com.test.app", 0)),
                java.io.ByteArrayOutputStream(),
                null, null, null, null,
            )
            fail("expected InsufficientSpaceException")
        } catch (e: InsufficientSpaceException) {
            assertTrue(
                "message must describe the requirement, was: ${e.message}",
                e.message!!.contains("insufficient space"),
            )
        }
    }

    @Test
    fun spaceCheckPassesWhenEnoughRoomIsAvailable() {
        val source = tmp.newFolder("src5b")
        val vr = File(source, "vr")
        vr.mkdirs()
        buildAppSkeleton(vr, null, "com.test.app", 0)

        val packages = BackupTestSupport.FakePackages(mapOf("com.test.app#0" to meta("com.test.app", 0)))
        val engine = BackupEngine(
            roots = BackupTestSupport.FakeRoots(vr, null),
            spaceChecker = { Long.MAX_VALUE }, // plenty of space
            packages = packages,
            bridge = BackupTestSupport.FakeBridge(),
            tempDirProvider = { tmp.newFolder("temp5b") },
            stopWaitMillis = 0L,
        )
        val (bytes, result) = backupToBytes(engine, listOf(BackupRequest("com.test.app", 0)))
        assertEquals(1, result.succeeded.size)
        assertTrue(bytes.isNotEmpty())
    }

    // ---------------------------------------------------------------- TC-U-14

    @Test
    fun compareSigningCertFlagsMismatchAsWarning() {
        val engine = BackupTestSupport.engine(
            BackupTestSupport.FakeRoots(tmp.newFolder("vr14"), null),
            BackupTestSupport.FakePackages(emptyMap()),
            BackupTestSupport.FakeBridge(),
        )
        assertEquals(BackupEngine.CertCheck.MISMATCH, engine.compareSigningCert("sha-backup", "sha-installed"))
        assertEquals(BackupEngine.CertCheck.MATCH, engine.compareSigningCert("sha-a", "sha-a"))
        assertEquals(BackupEngine.CertCheck.MATCH, engine.compareSigningCert("SHA-A", "sha-a")) // case-insensitive hex
        assertEquals(BackupEngine.CertCheck.UNKNOWN, engine.compareSigningCert(null, "sha-installed"))
        assertEquals(BackupEngine.CertCheck.UNKNOWN, engine.compareSigningCert("sha-backup", null))
        assertEquals(BackupEngine.CertCheck.UNKNOWN, engine.compareSigningCert("", ""))
    }

    @Test
    fun restoreMarksSignatureMismatchAsWarningNotFailure() {
        val source = tmp.newFolder("src14")
        val vr = File(source, "vr")
        vr.mkdirs()
        buildAppSkeleton(vr, null, "com.test.app", 0)

        // Backup time: cert A.
        val packagesBackup = BackupTestSupport.FakePackages(mapOf("com.test.app#0" to meta("com.test.app", 0, "certA")))
        val backupEngine = engine(BackupTestSupport.FakeRoots(vr, null), packagesBackup, BackupTestSupport.FakeBridge())
        val (zipBytes, backupResult) = backupToBytes(backupEngine, listOf(BackupRequest("com.test.app", 0)))
        assertEquals("certA", backupResult.manifest!!.apps[0].signingCertSha256)
        val archive = BackupTestSupport.bytesToFile(zipBytes, tmp.newFolder("archives14"))

        // Restore time: device reports cert B -> warning=true, app still restored.
        val dest = tmp.newFolder("dst14")
        val destVr = File(dest, "vr")
        destVr.mkdirs()
        val packagesRestore = BackupTestSupport.FakePackages(mapOf("com.test.app#0" to meta("com.test.app", 0, "certB")))
        val restoreEngine = engine(BackupTestSupport.FakeRoots(destVr, null), packagesRestore, BackupTestSupport.FakeBridge())
        val result = restoreEngine.restore(archive, null, null, null, null)

        assertEquals(0, result.failed.size)
        assertEquals(1, result.succeeded.size)
        assertTrue(result.succeeded[0].signatureWarning)
    }

    // ---------------------------------------------------------------- encrypted end-to-end

    @Test
    fun encryptedArchiveRestoresWithCorrectPassword() {
        val source = tmp.newFolder("srcEnc")
        val vr = File(source, "vr")
        val evr = File(source, "evr")
        vr.mkdirs()
        evr.mkdirs()
        buildAppSkeleton(vr, evr, "com.test.app", 0)

        val packages = BackupTestSupport.FakePackages(mapOf("com.test.app#0" to meta("com.test.app", 0)))
        val backupEngine = engine(BackupTestSupport.FakeRoots(vr, evr), packages, BackupTestSupport.FakeBridge())
        val (zipBytes, backupResult) = backupToBytes(
            backupEngine,
            listOf(BackupRequest("com.test.app", 0)),
            password = "secret123".toCharArray(),
        )
        val encryptedManifest = backupResult.manifest
        assertTrue(encryptedManifest!!.encrypted)
        assertEquals(BackupCrypto.KDF_ALGO, encryptedManifest.kdf!!.algo)
        assertEquals(120000, encryptedManifest.kdf!!.iterations)
        assertEquals(16, encryptedManifest.kdf!!.salt.length / 2)

        val dest = tmp.newFolder("dstEnc")
        val destVr = File(dest, "vr")
        val destEvr = File(dest, "evr")
        destVr.mkdirs()
        destEvr.mkdirs()
        val restoreEngine = engine(BackupTestSupport.FakeRoots(destVr, destEvr), packages, BackupTestSupport.FakeBridge())
        val archive = BackupTestSupport.bytesToFile(zipBytes, tmp.newFolder("archivesEnc"))

        val result = restoreEngine.restore(archive, "secret123".toCharArray(), null, null, null)
        assertEquals(0, result.failed.size)
        assertEquals(
            sha256Hex(File(vr, "data/user/0/com.test.app/files/hello.txt").readBytes()),
            sha256Hex(File(destVr, "data/user/0/com.test.app/files/hello.txt").readBytes()),
        )
    }

    @Test
    fun encryptedArchiveWithoutPasswordFailsExplicitly() {
        val source = tmp.newFolder("srcEnc2")
        val vr = File(source, "vr")
        vr.mkdirs()
        buildAppSkeleton(vr, null, "com.test.app", 0)
        val packages = BackupTestSupport.FakePackages(mapOf("com.test.app#0" to meta("com.test.app", 0)))
        val backupEngine = engine(BackupTestSupport.FakeRoots(vr, null), packages, BackupTestSupport.FakeBridge())
        val (zipBytes, _) = backupToBytes(
            backupEngine,
            listOf(BackupRequest("com.test.app", 0)),
            password = "pw".toCharArray(),
        )
        val archive = BackupTestSupport.bytesToFile(zipBytes, tmp.newFolder("archivesEnc2"))
        val destVr = tmp.newFolder("dstEnc2")
        val restoreEngine = engine(BackupTestSupport.FakeRoots(destVr, null), packages, BackupTestSupport.FakeBridge())

        try {
            restoreEngine.restore(archive, null, null, null, null)
            fail("expected WrongPasswordException")
        } catch (e: WrongPasswordException) {
            // expected: no password provided for encrypted archive
        }
    }

    @Test
    fun tamperedManifestRaisesVerifyException() {
        val source = tmp.newFolder("srcV")
        val vr = File(source, "vr")
        vr.mkdirs()
        buildAppSkeleton(vr, null, "com.test.app", 0)
        val packages = BackupTestSupport.FakePackages(mapOf("com.test.app#0" to meta("com.test.app", 0)))
        val backupEngine = engine(BackupTestSupport.FakeRoots(vr, null), packages, BackupTestSupport.FakeBridge())
        val (zipBytes, _) = backupToBytes(backupEngine, listOf(BackupRequest("com.test.app", 0)))

        // Corrupt manifest.json bytes in place (keep zip structure valid: rewrite the archive).
        val rewritten = rewriteManifest(zipBytes, """{"format_version":1,"apps":[],"tampered":true}""")
        val archive = BackupTestSupport.bytesToFile(rewritten, tmp.newFolder("archivesV"))
        val destVr = tmp.newFolder("dstV")
        val restoreEngine = engine(BackupTestSupport.FakeRoots(destVr, null), packages, BackupTestSupport.FakeBridge())

        try {
            restoreEngine.restore(archive, null, null, null, null)
            fail("expected VerifyException")
        } catch (e: VerifyException) {
            // expected: sha256 mismatch
        }
    }

    @Test
    fun hostPrefsRoundtripThroughArchive() {
        val source = tmp.newFolder("srcHP")
        val vr = File(source, "vr")
        vr.mkdirs()
        buildAppSkeleton(vr, null, "com.test.app", 0)
        val packages = BackupTestSupport.FakePackages(mapOf("com.test.app#0" to meta("com.test.app", 0)))
        val backupEngine = engine(BackupTestSupport.FakeRoots(vr, null), packages, BackupTestSupport.FakeBridge())
        val hostPrefs = HostPrefs(
            linkedMapOf(
                HostPrefs.sortKey("com.test.app") to "3",
                HostPrefs.noteKey("com.test.app") to "restored note",
                HostPrefs.frozenKey("com.test.app") to "false",
            ),
        )
        val (zipBytes, _) = backupToBytes(
            backupEngine,
            listOf(BackupRequest("com.test.app", 0)),
            hostPrefs = hostPrefs,
        )
        val archive = BackupTestSupport.bytesToFile(zipBytes, tmp.newFolder("archivesHP"))
        val destVr = tmp.newFolder("dstHP")
        val restoreEngine = engine(BackupTestSupport.FakeRoots(destVr, null), packages, BackupTestSupport.FakeBridge())

        val result = restoreEngine.restore(archive, null, null, null, null)
        assertEquals(0, result.failed.size)
        assertEquals(hostPrefs.entries, result.hostPrefs!!.entries)
    }

    /**
     * Rewrites the archive replacing the manifest.json entry content (used to simulate
     * corruption without breaking the zip container itself).
     */
    private fun rewriteManifest(originalZip: ByteArray, newManifestJson: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zos ->
            java.util.zip.ZipInputStream(ByteArrayInputStream(originalZip)).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    if (entry.name == BackupArchive.MANIFEST_ENTRY) {
                        BackupTestSupport.putRawEntry(zos, entry.name, newManifestJson.toByteArray(Charsets.UTF_8))
                    } else {
                        zos.putNextEntry(java.util.zip.ZipEntry(entry.name))
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = zis.read(buf)
                            if (n < 0) break
                            zos.write(buf, 0, n)
                        }
                        zos.closeEntry()
                    }
                }
            }
        }
        return out.toByteArray()
    }
}
