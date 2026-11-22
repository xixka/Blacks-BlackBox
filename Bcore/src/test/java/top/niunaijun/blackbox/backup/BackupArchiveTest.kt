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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.niunaijun.blackbox.backup.BackupTestSupport.backupToBytes
import top.niunaijun.blackbox.backup.BackupTestSupport.buildAppSkeleton
import top.niunaijun.blackbox.backup.BackupTestSupport.createSymlink
import top.niunaijun.blackbox.backup.BackupTestSupport.engine
import top.niunaijun.blackbox.backup.BackupTestSupport.isSymlink
import top.niunaijun.blackbox.backup.BackupTestSupport.meta
import top.niunaijun.blackbox.backup.BackupTestSupport.readSymlink
import top.niunaijun.blackbox.backup.BackupTestSupport.sha256Hex
import top.niunaijun.blackbox.backup.BackupTestSupport.snapshot
import top.niunaijun.blackbox.backup.BackupTestSupport.zipEntryNames
import java.io.File
import java.util.Random

/**
 * TC-U-03: archive round-trip incl. symlink + empty file + Chinese filename + 1MB binary.
 * TC-U-04: cache exclusion.
 * TC-U-06: restore idempotency (same archive restored twice -> identical trees).
 * TC-U-10: collision-free escapePkg.
 */
class BackupArchiveTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun buildEngine(vr: File, evr: File?): Pair<BackupEngine, BackupTestSupport.FakeBridge> {
        val bridge = BackupTestSupport.FakeBridge()
        val packages = BackupTestSupport.FakePackages(mapOf("com.test.app#0" to meta("com.test.app", 0)))
        return engine(BackupTestSupport.FakeRoots(vr, evr), packages, bridge) to bridge
    }

    // ---------------------------------------------------------------- TC-U-03

    @Test
    fun packAndUnpackRoundtripPreservesContentSymlinksAndNames() {
        val osName = System.getProperty("os.name")?.lowercase() ?: ""
        assumeTrue("symlinks need a POSIX filesystem", osName.contains("linux") || osName.contains("mac"))

        val source = tmp.newFolder("src")
        val dest = tmp.newFolder("dst")
        val vr = File(source, "vr")
        val evr = File(source, "evr")
        vr.mkdirs()
        evr.mkdirs()
        buildAppSkeleton(vr, evr, "com.test.app", 0)

        // Extra cases required by TC-U-03.
        val dataDir = File(vr, "data/user/0/com.test.app")
        File(dataDir, "empty.bin").createNewFile() // empty file
        val chineseDir = File(dataDir, "中文目录")
        chineseDir.mkdirs()
        File(chineseDir, "中文文件名.txt").writeText("中文内容测试")
        val randomBytes = ByteArray(1024 * 1024).also { Random(42).nextBytes(it) }
        File(dataDir, "binary_1mb.bin").writeBytes(randomBytes)
        createSymlink(File(dataDir, "link.bin"), "files/hello.txt") // symlink

        val (engine, _) = buildEngine(vr, evr)
        val (zipBytes, backupResult) = backupToBytes(
            engine,
            listOf(BackupManager.BackupRequest("com.test.app", 0)),
        )
        assertEquals(1, backupResult.succeeded.size)
        assertTrue(backupResult.failed.isEmpty())

        // The symlink was recorded in the manifest (never stored as a zip entry).
        val recorded = backupResult.manifest!!.symlinks
        assertEquals(1, recorded.size)
        assertEquals("apps/com.test.app/data_user/link.bin", recorded[0].path)
        assertEquals("files/hello.txt", recorded[0].target)
        assertFalse(zipEntryNames(zipBytes).contains("apps/com.test.app/data_user/link.bin"))

        // Restore into a fresh root set.
        val destVr = File(dest, "vr")
        val destEvr = File(dest, "evr")
        destVr.mkdirs()
        destEvr.mkdirs()
        val (restoreEngine, _) = buildEngine(destVr, destEvr)
        val archive = BackupTestSupport.bytesToFile(zipBytes, tmp.newFolder("archives"))
        val restoreResult = restoreEngine.restore(archive, null, null, null, null)
        assertEquals(0, restoreResult.failed.size)
        assertEquals(1, restoreResult.succeeded.size)

        // Content checks.
        val restoredData = File(destVr, "data/user/0/com.test.app")
        assertEquals(
            sha256Hex(ByteArray(0)),
            sha256Hex(File(restoredData, "empty.bin").readBytes()),
        )
        assertEquals("中文内容测试", File(restoredData, "中文目录/中文文件名.txt").readText())
        assertTrue(
            "1MB binary must survive the roundtrip byte for byte",
            randomBytes.contentEquals(File(restoredData, "binary_1mb.bin").readBytes()),
        )

        // Symlink was rebuilt as an actual symlink with the same target.
        val restoredLink = File(restoredData, "link.bin")
        assertTrue("restored link must be a symlink", isSymlink(restoredLink))
        assertEquals("files/hello.txt", readSymlink(restoredLink))
    }

    // ---------------------------------------------------------------- TC-U-04

    @Test
    fun cacheDirectoriesAreExcludedWhenExcludeCacheIsOn() {
        val source = tmp.newFolder("src4")
        val vr = File(source, "vr")
        val evr = File(source, "evr")
        vr.mkdirs()
        evr.mkdirs()
        buildAppSkeleton(vr, evr, "com.test.app", 0)
        File(vr, "data/user/0/com.test.app/cache/x").mkdirs()
        File(vr, "data/user/0/com.test.app/cache/x/blob.bin").writeText("cache payload")
        File(evr, "storage/emulated/0/Android/data/com.test.app/cache/y").mkdirs()
        File(evr, "storage/emulated/0/Android/data/com.test.app/cache/y/z.bin").writeText("ext cache payload")
        // A "cache" dir nested deeper than the top level must NOT be excluded.
        File(vr, "data/user/0/com.test.app/files/cache").mkdirs()
        File(vr, "data/user/0/com.test.app/files/cache/keep_me.txt").writeText("keep")

        val (engine, _) = buildEngine(vr, evr)
        val (zipBytes, result) = backupToBytes(
            engine,
            listOf(BackupManager.BackupRequest("com.test.app", 0, excludeCache = true)),
        )

        assertTrue(result.manifest!!.apps[0].excludeCache)
        val names = zipEntryNames(zipBytes)
        assertFalse(names.any { it.startsWith("apps/com.test.app/data_user/cache/") })
        assertFalse(names.any { it.startsWith("apps/com.test.app/ext_data/cache/") })
        assertTrue(names.contains("apps/com.test.app/data_user/files/cache/keep_me.txt"))

        // Restore: cache content must not reappear.
        val dest = tmp.newFolder("dst4")
        val destVr = File(dest, "vr")
        val destEvr = File(dest, "evr")
        destVr.mkdirs()
        destEvr.mkdirs()
        val (restoreEngine, _) = buildEngine(destVr, destEvr)
        val archive = BackupTestSupport.bytesToFile(zipBytes, tmp.newFolder("archives4"))
        val restoreResult = restoreEngine.restore(archive, null, null, null, null)
        assertEquals(0, restoreResult.failed.size)
        assertFalse(File(destVr, "data/user/0/com.test.app/cache/x/blob.bin").exists())
        assertFalse(File(destEvr, "storage/emulated/0/Android/data/com.test.app/cache/y/z.bin").exists())
        assertTrue(File(destVr, "data/user/0/com.test.app/files/cache/keep_me.txt").exists())
    }

    // ---------------------------------------------------------------- TC-U-06

    @Test
    fun restoringTheSameArchiveTwiceYieldsAnIdenticalTree() {
        val source = tmp.newFolder("src6")
        val vr = File(source, "vr")
        val evr = File(source, "evr")
        vr.mkdirs()
        evr.mkdirs()
        buildAppSkeleton(vr, evr, "com.test.app", 0)
        val dataDir = File(vr, "data/user/0/com.test.app")
        File(dataDir, "nested/deep").mkdirs()
        File(dataDir, "nested/deep/file.txt").writeText("nested content")

        val (engine, _) = buildEngine(vr, evr)
        val (zipBytes, _) = backupToBytes(engine, listOf(BackupManager.BackupRequest("com.test.app", 0)))
        val archive = BackupTestSupport.bytesToFile(zipBytes, tmp.newFolder("archives6"))

        val dest = tmp.newFolder("dst6")
        val destVr = File(dest, "vr")
        val destEvr = File(dest, "evr")
        destVr.mkdirs()
        destEvr.mkdirs()
        val (restoreEngine, _) = buildEngine(destVr, destEvr)

        val first = restoreEngine.restore(archive, null, null, null, null)
        assertEquals(0, first.failed.size)
        val snapshotAfterFirst = snapshot(destVr) + snapshot(destEvr)

        // Restore a second time into the SAME destination: result must be identical.
        val second = restoreEngine.restore(archive, null, null, null, null)
        assertEquals(0, second.failed.size)
        val snapshotAfterSecond = snapshot(destVr) + snapshot(destEvr)

        assertEquals(snapshotAfterFirst, snapshotAfterSecond)
    }

    // ---------------------------------------------------------------- TC-U-10

    @Test
    fun escapePkgIsCollisionFreeAndReversible() {
        // The naive scheme would map com.a.b-c and com.a.b_c to the same name; ours must not.
        val dash = BackupArchive.escapePkg("com.a.b-c")
        val underscore = BackupArchive.escapePkg("com.a.b_c")
        assertEquals("com.a.b_2d_", dash)
        assertEquals("com.a.b_5f_", underscore)
        assertTrue(dash != underscore)

        // Literal '_' is escaped, so sequences like _2d_ cannot be forged.
        assertEquals("com.a.b_5f_2d_5f_", BackupArchive.escapePkg("com.a.b_2d_"))

        // Plain names pass through.
        assertEquals("com.test.app", BackupArchive.escapePkg("com.test.app"))

        // Round trip over tricky inputs.
        val samples = listOf(
            "com.test.app", "com.a.b-c", "com.a.b_c", "com.a.b_2d_", "com.a.中文", "a", "_", "__", "x-y_z-w",
        )
        for (sample in samples) {
            assertEquals(
                "escape/unescape must be injective for '$sample'",
                sample,
                BackupArchive.unescapePkg(BackupArchive.escapePkg(sample)),
            )
        }

        // The two colliding candidates also produce distinct zip directories when packed.
        val source = tmp.newFolder("src10")
        val vr = File(source, "vr")
        vr.mkdirs()
        for (pkg in listOf("com.a.b-c", "com.a.b_c")) {
            buildAppSkeleton(vr, null, pkg, 0)
        }
        val packages = BackupTestSupport.FakePackages(
            mapOf(
                "com.a.b-c#0" to meta("com.a.b-c", 0),
                "com.a.b_c#0" to meta("com.a.b_c", 0),
            ),
        )
        val eng = engine(
            BackupTestSupport.FakeRoots(vr, null),
            packages,
            BackupTestSupport.FakeBridge(),
        )
        val (zipBytes, result) = backupToBytes(
            eng,
            listOf(
                BackupManager.BackupRequest("com.a.b-c", 0),
                BackupManager.BackupRequest("com.a.b_c", 0),
            ),
        )
        assertEquals(2, result.succeeded.size)
        val names = zipEntryNames(zipBytes)
        assertTrue(names.any { it.startsWith("apps/com.a.b_2d_/") })
        assertTrue(names.any { it.startsWith("apps/com.a.b_5f_/") })
        // Manifest keeps the ORIGINAL package names.
        assertEquals(
            setOf("com.a.b-c", "com.a.b_c"),
            result.manifest!!.apps.map { it.packageName }.toSet(),
        )
    }

    @Test
    fun headerRoundtripReadsManifestAndSha256() {
        val source = tmp.newFolder("srcH")
        val vr = File(source, "vr")
        vr.mkdirs()
        buildAppSkeleton(vr, null, "com.test.app", 0)
        val (engine, _) = buildEngine(vr, null)
        val (zipBytes, _) = backupToBytes(engine, listOf(BackupManager.BackupRequest("com.test.app", 0)))
        val archive = BackupTestSupport.bytesToFile(zipBytes, tmp.newFolder("archivesH"))

        val header = BackupArchive.readHeader(archive)
        assertNotNull(header.manifestSha256Hex)
        assertEquals(
            header.manifestSha256Hex!!.lowercase(),
            BackupManifestCodec.sha256Hex(header.manifestBytes),
        )
        assertTrue(header.entryNames.contains("manifest.json"))
        assertTrue(header.entryNames.contains("manifest.sha256"))
        assertTrue(header.entryNames.contains("apps/com.test.app/entry.json"))
    }
}
