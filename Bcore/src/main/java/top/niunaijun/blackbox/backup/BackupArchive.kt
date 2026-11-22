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

import top.niunaijun.blackbox.backup.exception.BackupException
import top.niunaijun.blackbox.backup.exception.VerifyException
import top.niunaijun.blackbox.backup.exception.WrongPasswordException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.SecretKey

/**
 * Pure-JVM zip layer of the blackbox-backup v1 format. Handles:
 *  - archive entry names and layout constants,
 *  - collision-free escaping of package names into zip directory names,
 *  - streaming pack/unpack of file entries (8KB+ chunks, never whole-file-in-memory),
 *  - optional per-entry AES-GCM encryption ([BackupCrypto]),
 *  - header reading (manifest.json / manifest.sha256) and routed extraction.
 *
 * Symlinks are NOT stored as zip entries; they are recorded in manifest.json "symlinks"
 * (path + target) and rebuilt by the restore flow. See [BackupEngine].
 */
object BackupArchive {
    const val MANIFEST_ENTRY = "manifest.json"
    const val MANIFEST_SHA256_ENTRY = "manifest.sha256"
    const val HOST_PREFS_ENTRY = "host_prefs.json"
    const val APPS_ROOT = "apps/"
    const val ENTRY_JSON = "entry.json"
    const val BASE_APK_NAME = "base.apk"

    const val DATA_USER_SECTION = "data_user"
    const val DATA_USER_DE_SECTION = "data_user_de"
    const val EXT_DATA_SECTION = "ext_data"
    const val EXT_OBB_SECTION = "ext_obb"
    const val SPLITS_SECTION = "splits"

    /** Top-level directory name that is excluded from data sections when excludeCache is on. */
    const val CACHE_DIR_NAME = "cache"

    /** Streaming copy chunk (engine performance red line: >= 8KB, never whole-file buffering). */
    const val COPY_BUFFER_SIZE = 64 * 1024

    // --------------------------------------------------------------------------------------------
    // Package name escaping
    // --------------------------------------------------------------------------------------------

    /**
     * Maps a raw package name to a collision-free zip directory name.
     *
     * Rule: characters [a-zA-Z0-9.] pass through; every other character (including '_')
     * becomes "_" + lowercase-hex-codepoint + "_".
     *
     * This is injective: the output never contains a literal '_' outside escape sequences
     * (a literal '_' in the input becomes "_5f_"), so two different raw names always map to
     * two different escaped names. Examples:
     *   "com.a.b"     -> "com.a.b"
     *   "com.a.b-c"   -> "com.a.b_2d_"
     *   "com.a.b_c"   -> "com.a.b_5f_"
     *   "com.a.b_2d_" -> "com.a.b_5f_2d_5f_"
     */
    fun escapePkg(packageName: String): String {
        val sb = StringBuilder(packageName.length + 8)
        for (ch in packageName) {
            when {
                ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '.' -> sb.append(ch)
                else -> {
                    sb.append('_')
                    sb.append(ch.code.toString(16))
                    sb.append('_')
                }
            }
        }
        return sb.toString()
    }

    /** Inverse of [escapePkg]. Malformed escape sequences are passed through literally. */
    fun unescapePkg(escaped: String): String {
        if (!escaped.contains('_')) return escaped
        val sb = StringBuilder(escaped.length)
        var i = 0
        while (i < escaped.length) {
            val ch = escaped[i]
            if (ch != '_') {
                sb.append(ch)
                i++
                continue
            }
            val close = escaped.indexOf('_', i + 1)
            if (close <= i + 1) {
                // No closing '_' or empty escape: treat as literal.
                sb.append('_')
                i++
                continue
            }
            val hex = escaped.substring(i + 1, close)
            val code = hex.toLongOrNull(16)
            if (code == null || code < 0 || code > Char.MAX_VALUE.code) {
                // Not a valid escape: keep the underscore literally.
                sb.append('_')
                i++
                continue
            }
            sb.append(code.toChar())
            i = close + 1
        }
        return sb.toString()
    }

    /** Root zip directory for an application: apps/{escaped_pkg}/. */
    fun appDirName(packageName: String): String = APPS_ROOT + escapePkg(packageName)

    // --------------------------------------------------------------------------------------------
    // Pack helpers (all streaming)
    // --------------------------------------------------------------------------------------------

    /** Writes a single in-memory entry (used for manifest.json / manifest.sha256 / entry.json). */
    fun writeBytesEntry(zos: ZipOutputStream, name: String, bytes: ByteArray, key: SecretKey?) {
        zos.putNextEntry(ZipEntry(name))
        if (key == null) {
            zos.write(bytes)
        } else {
            val es = BackupCrypto.encryptingStream(zos, key, BackupCrypto.newNonce())
            es.write(bytes)
            es.close()
        }
        zos.closeEntry()
    }

    /** Streams a single file into the zip. */
    fun writeFileEntry(zos: ZipOutputStream, name: String, file: File, key: SecretKey?) {
        zos.putNextEntry(ZipEntry(name))
        FileInputStream(file).use { input ->
            if (key == null) {
                copy(input, zos)
            } else {
                val es = BackupCrypto.encryptingStream(zos, key, BackupCrypto.newNonce())
                copy(input, es)
                es.close()
            }
        }
        zos.closeEntry()
    }

    /**
     * Recursively streams [dir] into the zip under [zipPrefix] (must end with '/').
     *
     * @param excludeCacheDirName when non-null, a top-level directory with this name directly
     *        under [dir] is skipped (used for "cache" when excludeCache is enabled).
     * @param skipZipPaths full zip paths (same format as [ManifestSymlink.path]) whose payloads
     *        must not be written; used for symlinked files recorded in the manifest instead.
     * @param onSymlink invoked for every symbolic link encountered during writing (normal flow:
     *        none, because known links are skipped; a link here means the filesystem changed
     *        between the walk phase and the write phase).
     * @return total bytes and file count of what was written, excluding symlinks.
     */
    fun writeDirRecursively(
        zos: ZipOutputStream,
        dir: File,
        zipPrefix: String,
        excludeCacheDirName: String?,
        key: SecretKey?,
        symlinkSupport: SymlinkSupport,
        skipZipPaths: Set<String>,
        onSymlink: (zipPath: String, target: String) -> Unit,
    ): Pair<Long, Int> {
        require(zipPrefix.endsWith('/')) { "zipPrefix must end with '/': $zipPrefix" }
        if (!dir.exists() || !dir.isDirectory) return 0L to 0
        var bytes = 0L
        var count = 0
        val children = dir.listFiles()
        if (children == null) {
            BackupLog.w(BackupLog.TAG_BACKUP, "cannot list directory: ${dir.absolutePath}")
            return 0L to 0
        }
        for (child in children.sortedBy { it.name }) {
            val relative = relativePath(dir, child)
            if (excludeCacheDirName != null && child.isDirectory &&
                relative == excludeCacheDirName && child.parentFile == dir
            ) {
                continue
            }
            val zipPath = zipPrefix + relative
            if (symlinkSupport.isSymlink(child)) {
                val target = symlinkSupport.readSymlinkTarget(child)
                if (target != null) {
                    onSymlink(zipPath, target)
                } else {
                    BackupLog.w(BackupLog.TAG_BACKUP, "symlink without readable target, skipped: $zipPath")
                }
                // Symlink content is never copied.
                continue
            }
            if (child.isDirectory) {
                // The cache exclusion applies only to the ROOT level of each packed tree
                // (data_user/cache, ext_data/cache, ...); a nested "files/cache" directory
                // is regular user content and must be kept.
                val sub = writeDirRecursively(
                    zos, child, "$zipPath/", /* excludeCacheDirName = */ null, key, symlinkSupport, skipZipPaths, onSymlink,
                )
                bytes += sub.first
                count += sub.second
            } else if (child.isFile) {
                if (zipPath in skipZipPaths) continue
                writeFileEntry(zos, zipPath, child, key)
                bytes += child.length()
                count++
            }
        }
        return bytes to count
    }

    // --------------------------------------------------------------------------------------------
    // Unpack helpers (all streaming)
    // --------------------------------------------------------------------------------------------

    /** Header information captured in the first scan of an archive. */
    class Header(
        val manifestBytes: ByteArray,
        val manifestSha256Hex: String?,
        val entryNames: List<String>,
    ) {
        fun hasHostPrefs(): Boolean = entryNames.contains(HOST_PREFS_ENTRY)
    }

    /**
     * First-pass scan: reads manifest.json + manifest.sha256 and records all entry names.
     * Other entry payloads are skipped (streamed through, never buffered).
     */
    fun readHeader(source: File): Header {
        ZipInputStream(source.inputStream().buffered(COPY_BUFFER_SIZE)).use { zis ->
            var manifestBytes: ByteArray? = null
            var manifestSha: String? = null
            val names = ArrayList<String>()
            while (true) {
                val entry = zis.nextEntry ?: break
                val name = entry.name
                names.add(name)
                when (name) {
                    MANIFEST_ENTRY -> manifestBytes = readCurrentEntryBytes(zis)
                    MANIFEST_SHA256_ENTRY -> manifestSha = String(readCurrentEntryBytes(zis), Charsets.UTF_8).trim()
                    else -> skipCurrentEntry(zis)
                }
            }
            val manifest = manifestBytes
                ?: throw VerifyException("backup archive has no $MANIFEST_ENTRY")
            return Header(manifest, manifestSha, names)
        }
    }

    /** Reads the current zip entry fully into memory. Only for small entries (manifest, prefs). */
    fun readCurrentEntryBytes(zis: ZipInputStream): ByteArray {
        val out = ByteArrayOutputStream()
        copy(zis, out)
        return out.toByteArray()
    }

    /**
     * Single-pass routed extraction over the whole archive.
     *
     * @param router maps a zip entry name to a destination file, or null to skip the entry
     *        (caller keeps full control over path mapping).
     * @param key decryption key when the archive is encrypted, null otherwise.
     * @param onEntryError optional per-entry error sink. When non-null, ordinary IO failures
     *        for a single entry are reported there and the pass continues; integrity errors
     *        ([VerifyException], [WrongPasswordException]) always propagate. When null, any
     *        entry error aborts the whole pass with a [BackupException].
     * @return number of files written.
     */
    fun extractRouted(
        source: File,
        router: (String) -> File?,
        key: SecretKey?,
        onEntryError: ((name: String, error: Exception) -> Unit)? = null,
    ): Int {
        var written = 0
        ZipInputStream(source.inputStream().buffered(COPY_BUFFER_SIZE)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val name = entry.name
                try {
                    val dest = router(name)
                    if (dest == null) {
                        skipCurrentEntry(zis)
                        continue
                    }
                    dest.parentFile?.let { parent ->
                        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
                            throw BackupException("cannot create destination directory: ${parent.absolutePath}")
                        }
                    }
                    FileOutputStream(dest).use { fos ->
                        if (key == null) {
                            copy(zis, fos)
                        } else {
                            val ds = BackupCrypto.decryptingStream(zis, key)
                            copy(ds, fos)
                            ds.close()
                        }
                    }
                    written++
                } catch (e: VerifyException) {
                    throw e
                } catch (e: WrongPasswordException) {
                    throw e
                } catch (e: Exception) {
                    if (onEntryError != null) {
                        onEntryError(name, e)
                    } else {
                        throw BackupException("failed to write zip entry '$name': ${e.message}", e)
                    }
                }
            }
        }
        return written
    }

    /** Reads one named entry (small payloads only) with optional decryption. Returns null if absent. */
    fun readNamedEntry(source: File, name: String, key: SecretKey?): ByteArray? {
        ZipInputStream(source.inputStream().buffered(COPY_BUFFER_SIZE)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: return null
                if (entry.name == name) {
                    val raw = readCurrentEntryBytes(zis)
                    if (key == null) return raw
                    val plain = BackupCrypto.decryptingStream(raw.inputStream(), key)
                    return plain.use { it.readBytes() }
                }
                skipCurrentEntry(zis)
            }
        }
    }

    /** Consumes the remaining bytes of the current entry (streaming, no buffering). */
    fun skipCurrentEntry(zis: ZipInputStream) {
        val buf = ByteArray(COPY_BUFFER_SIZE)
        while (zis.read(buf) != -1) {
            // drain
        }
    }

    fun copy(input: InputStream, output: OutputStream) {
        val buf = ByteArray(COPY_BUFFER_SIZE)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            output.write(buf, 0, n)
        }
        output.flush()
    }

    /** Slash-separated path of [child] relative to [parent] (or just its name if unrelated). */
    fun relativePath(parent: File, child: File): String {
        val parentPath = parent.absolutePath.let { if (it.endsWith(File.separatorChar)) it else it + File.separatorChar }
        val childPath = child.absolutePath
        if (childPath.startsWith(parentPath)) {
            return childPath.substring(parentPath.length).replace(File.separatorChar, '/')
        }
        return child.name
    }
}
