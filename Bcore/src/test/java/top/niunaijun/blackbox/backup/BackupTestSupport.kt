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

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Shared fakes + helpers for the pure-JVM backup engine tests.
 * IMPORTANT: no android.* imports anywhere in the test sources.
 */
object BackupTestSupport {

    // ---------------------------------------------------------------- fakes

    class FakeRoots(val vr: File, val evr: File?) : DataRootProvider {
        override fun virtualRoot(): File = vr
        override fun externalVirtualRoot(): File? = evr
    }

    class FakePackages(private val metas: Map<String, AppMeta>) : PackageInfoProvider {
        override fun getEntryMeta(packageName: String, userId: Int): AppMeta? =
            metas["$packageName#$userId"]

        override fun isInstalled(packageName: String, userId: Int): Boolean =
            metas.containsKey("$packageName#$userId")
    }

    class FakeBridge : InstallBridge {
        val installed = mutableListOf<Pair<String, Int>>()
        val stopped = mutableListOf<Pair<String, Int>>()
        val ensuredUsers = mutableListOf<Int>()
        var installResult = true
        var stopResult = true
        var ensureUserResult = true
        var processAlive = false

        override fun install(apk: File, userId: Int): Boolean {
            installed.add(apk.name to userId)
            return installResult
        }

        override fun stopPackage(packageName: String, userId: Int): Boolean {
            stopped.add(packageName to userId)
            return stopResult
        }

        override fun ensureUser(userId: Int): Boolean {
            ensuredUsers.add(userId)
            return ensureUserResult
        }

        override fun isProcessAlive(packageName: String, userId: Int): Boolean = processAlive
    }

    // ---------------------------------------------------------------- builders

    /** Builds the standard BEnvironment-like directory skeleton under [vr]/[evr]. */
    fun buildAppSkeleton(
        vr: File,
        evr: File?,
        pkg: String,
        userId: Int,
        appId: Int = 10042,
        includeApk: Boolean = true,
    ) {
        File(vr, "data/user/$userId/$pkg/files").mkdirs()
        File(vr, "data/user/$userId/$pkg/files/hello.txt").writeText("hello virtual world")
        File(vr, "data/user/$userId/$pkg/databases").mkdirs()
        File(vr, "data/user/$userId/$pkg/databases/app.db").writeBytes(ByteArray(64) { it.toByte() })
        File(vr, "data/user_de/$userId/$pkg").mkdirs()
        File(vr, "data/user_de/$userId/$pkg/de_file.txt").writeText("device encrypted")
        if (includeApk) {
            File(vr, "data/app/$pkg").mkdirs()
            File(vr, "data/app/$pkg/base.apk").writeBytes(ByteArray(2048) { (it % 251).toByte() })
        }
        if (evr != null) {
            File(evr, "storage/emulated/$userId/Android/data/$pkg/files").mkdirs()
            File(evr, "storage/emulated/$userId/Android/data/$pkg/files/ext.txt").writeText("external")
            File(evr, "storage/emulated/$userId/Android/obb/$pkg").mkdirs()
            File(evr, "storage/emulated/$userId/Android/obb/$pkg/main.obb").writeBytes(ByteArray(128) { 7 })
        }
    }

    fun meta(pkg: String, userId: Int, certSha: String? = "deadbeef") = AppMeta(
        packageName = pkg,
        userId = userId,
        appId = 10042,
        versionName = "1.2.3",
        versionCode = 123,
        signingCertSha256 = certSha,
    )

    fun engine(roots: DataRootProvider, packages: PackageInfoProvider, bridge: InstallBridge): BackupEngine =
        BackupEngine(
            roots = roots,
            spaceChecker = { Long.MAX_VALUE }, // effectively unlimited space
            packages = packages,
            bridge = bridge,
            tempDirProvider = { File(roots.virtualRoot(), "_test_temp").apply { mkdirs() } },
            symlinkSupport = JavaNioSymlinkSupport,
            stopWaitMillis = 0L,
        )

    fun backupToBytes(
        engine: BackupEngine,
        requests: List<BackupRequest>,
        password: CharArray? = null,
        hostPrefs: HostPrefs? = null,
        hostInfo: HostInfo? = HostInfo("test-device", 33, 460),
    ): Pair<ByteArray, BackupResult> {
        val out = ByteArrayOutputStream()
        val result = engine.backup(requests, out, password, hostPrefs, hostInfo, null)
        return out.toByteArray() to result
    }

    fun bytesToFile(bytes: ByteArray, dir: File, name: String = "backup.zip"): File {
        val file = File(dir, name)
        file.writeBytes(bytes)
        return file
    }

    fun zipEntryNames(bytes: ByteArray): List<String> {
        val names = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                names.add(entry.name)
                val buf = ByteArray(8192)
                while (zis.read(buf) != -1) {
                    // drain
                }
            }
        }
        return names
    }

    /** Writes a raw (already compressed) zip entry into [zos]. */
    fun putRawEntry(zos: ZipOutputStream, name: String, bytes: ByteArray) {
        zos.putNextEntry(ZipEntry(name))
        zos.write(bytes)
        zos.closeEntry()
    }

    // ---------------------------------------------------------------- assertions

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4]).append("0123456789abcdef"[v and 0x0F])
        }
        return sb.toString()
    }

    /**
     * Recursive tree snapshot: relative path -> content hash ("dir:<n>" for directories,
     * "symlink:<target>" for symbolic links). Used for the idempotency comparison (TC-U-06).
     */
    fun snapshot(root: File): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        collect(root, root, out)
        return out
    }

    private fun collect(root: File, current: File, out: MutableMap<String, String>) {
        if (BackupTestSupport.isSymlink(current)) {
            out[relative(root, current)] = "symlink:" + readSymlink(current)
            return
        }
        if (current.isDirectory) {
            out[relative(root, current)] = "dir"
            val children = current.listFiles() ?: return
            for (child in children.sortedBy { it.name }) {
                collect(root, child, out)
            }
        } else if (current.isFile) {
            out[relative(root, current)] = "file:" + sha256Hex(current.readBytes())
        }
    }

    fun isSymlink(file: File): Boolean = java.nio.file.Files.isSymbolicLink(file.toPath())

    fun readSymlink(file: File): String =
        java.nio.file.Files.readSymbolicLink(file.toPath()).toString()

    fun createSymlink(link: File, target: String) {
        link.parentFile?.mkdirs()
        java.nio.file.Files.createSymbolicLink(link.toPath(), java.nio.file.Paths.get(target))
    }

    fun relative(root: File, file: File): String =
        file.absolutePath.removePrefix(root.absolutePath.trimEnd(File.separatorChar) + File.separatorChar)
            .replace(File.separatorChar, '/')
}
