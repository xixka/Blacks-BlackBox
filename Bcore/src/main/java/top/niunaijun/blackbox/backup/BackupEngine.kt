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

import top.niunaijun.blackbox.backup.confmerge.ConfMerger
import top.niunaijun.blackbox.backup.confmerge.JsonMapConfCodec
import top.niunaijun.blackbox.backup.exception.BackupException
import top.niunaijun.blackbox.backup.exception.InsufficientSpaceException
import top.niunaijun.blackbox.backup.exception.MissingApkException
import top.niunaijun.blackbox.backup.exception.VerifyException
import top.niunaijun.blackbox.backup.exception.WrongPasswordException
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.ZipOutputStream
import javax.crypto.SecretKey
import kotlin.math.ceil

// =============================================================================================
// Injected engine dependencies (all pure JVM so the engine is unit-testable)
// =============================================================================================

/** Provides the virtual root directories (VR / EVR of BEnvironment on a real device). */
interface DataRootProvider {
    fun virtualRoot(): File
    fun externalVirtualRoot(): File?
}

/** Available space probe for the volume holding a directory (StatFs on a real device). */
fun interface SpaceChecker {
    fun availableBytes(volumeRoot: File): Long
}

/** Metadata of one installed (virtual) application. */
data class AppMeta(
    val packageName: String,
    val userId: Int,
    val appId: Int,
    val versionName: String?,
    val versionCode: Long,
    val signingCertSha256: String?,
)

/** Read-only package manager view. */
interface PackageInfoProvider {
    /** @return null when the package is not installed for [userId]. */
    fun getEntryMeta(packageName: String, userId: Int): AppMeta?

    fun isInstalled(packageName: String, userId: Int): Boolean
}

/** Mutation bridge against the engine services (install / stop / users / process probe). */
interface InstallBridge {
    /** Installs [apk] as a fresh copy for [userId]. @return true on success. */
    fun install(apk: File, userId: Int): Boolean

    /** Best effort stop of all running instances. @return false when the stop failed. */
    fun stopPackage(packageName: String, userId: Int): Boolean

    /** Ensures the (virtual) user exists (creates it when missing). */
    fun ensureUser(userId: Int): Boolean

    /** Best-effort "is anything still running for this package/user?" probe. */
    fun isProcessAlive(packageName: String, userId: Int): Boolean
}

/** Provides a scratch directory for restore-time staging (APK temp copies etc.). */
fun interface TempDirProvider {
    fun tempDir(): File
}

/**
 * Filesystem symlink operations, abstracted because java.nio.file.Files only exists from
 * API 26 on Android while the platform minSdk is 21 (device builds swap in an
 * android.system.Os based implementation).
 */
interface SymlinkSupport {
    fun isSymlink(file: File): Boolean
    fun readSymlinkTarget(file: File): String?

    /** @return true when the link was created. */
    fun createSymlink(link: File, target: String): Boolean
}

/** JVM default implementation (java.nio.file.Files). Also used by unit tests. */
object JavaNioSymlinkSupport : SymlinkSupport {
    override fun isSymlink(file: File): Boolean = Files.isSymbolicLink(file.toPath())

    override fun readSymlinkTarget(file: File): String? = try {
        Files.readSymbolicLink(file.toPath()).toString()
    } catch (e: Exception) {
        null
    }

    override fun createSymlink(link: File, target: String): Boolean = try {
        if (link.exists()) {
            if (!link.delete()) {
                BackupLog.w(BackupLog.TAG_RESTORE, "cannot remove existing path for symlink: ${link.absolutePath}")
            }
        }
        link.parentFile?.mkdirs()
        Files.createSymbolicLink(link.toPath(), Paths.get(target))
        true
    } catch (e: Exception) {
        BackupLog.w(BackupLog.TAG_RESTORE, "failed to create symlink ${link.absolutePath} -> $target: ${e.message}")
        false
    }
}

// =============================================================================================
// Results
// =============================================================================================

/** Machine-readable reason of a per-application failure. */
enum class FailureReason {
    /** Package not installed for the requested user at backup time. */
    NOT_INSTALLED,

    /** installPackageAsUser failed during restore. */
    INSTALL_FAILED,

    /** APK needed but missing (not in archive / not installed on device). */
    MISSING_APK,

    /** Not enough free space on the data volume. */
    SPACE_INSUFFICIENT,

    /** Integrity verification failed. */
    VERIFY_FAILED,

    /** Unexpected IO failure while packing/unpacking. */
    IO_ERROR,

    /** Anything else. */
    UNKNOWN,
}

data class FailureItem(
    val packageName: String,
    val userId: Int,
    val reason: FailureReason,
    val detail: String?,
)

data class BackupedApp(val packageName: String, val userId: Int)

data class BackupResult(
    val succeeded: List<BackupedApp>,
    val failed: List<FailureItem>,
    /** The manifest that was written into the archive (null when backup failed early). */
    val manifest: BackupManifest?,
)

data class RestoredApp(
    val packageName: String,
    val userId: Int,
    val sourceUserId: Int,
    /** true when the installed signature differs from the backed-up one (warning, not failure). */
    val signatureWarning: Boolean,
)

data class RestoreResult(
    val succeeded: List<RestoredApp>,
    val failed: List<FailureItem>,
    /** host_prefs.json payload from the archive, when present. */
    val hostPrefs: HostPrefs?,
)

/** Host-side info stamped into the manifest at backup time. */
data class HostInfo(
    val model: String?,
    val androidSdk: Int,
    val appVersionCode: Int,
)

/**
 * The backup/restore engine core (blackbox-backup v1). Pure JVM: Android specifics are
 * injected through [DataRootProvider]/[SpaceChecker]/[PackageInfoProvider]/[InstallBridge]/
 * [TempDirProvider]/[SymlinkSupport], so the whole flow runs unchanged under JUnit.
 *
 * Threading: methods execute synchronously on the calling thread; progress listeners are
 * invoked synchronously. [BackupManager] adds the background thread + main-thread callbacks.
 */
class BackupEngine(
    private val roots: DataRootProvider,
    private val spaceChecker: SpaceChecker,
    private val packages: PackageInfoProvider,
    private val bridge: InstallBridge,
    private val tempDirProvider: TempDirProvider,
    private val symlinkSupport: SymlinkSupport = JavaNioSymlinkSupport,
    private val stopWaitMillis: Long = DEFAULT_STOP_WAIT_MILLIS,
) {

    // -----------------------------------------------------------------------------------------
    // Backup
    // -----------------------------------------------------------------------------------------

    /**
     * Packs the selected applications into a streaming zip archive on [target].
     *
     * Flow (engine spec §4.2): per app stop instances -> walk data dirs (sizes + symlinks) ->
     * global space check -> write manifest.json / manifest.sha256 (+ optional host_prefs.json)
     * -> stream per-app sections -> per-app entry.json.
     *
     * @throws InsufficientSpaceException when free space < total data size * 1.1
     * @throws BackupException on unrecoverable IO problems
     */
    fun backup(
        requests: List<BackupRequest>,
        target: OutputStream,
        password: CharArray?,
        hostPrefs: HostPrefs?,
        hostInfo: HostInfo?,
        listener: OnBackupProgressListener?,
    ): BackupResult {
        val tag = BackupLog.TAG_BACKUP
        BackupLog.i(tag, "backup: ${requests.size} app(s), encrypted=${password != null}")
        val vr = roots.virtualRoot()
        if (!vr.isDirectory && !vr.mkdirs()) {
            throw BackupException("virtual root is not available: ${vr.absolutePath}")
        }
        val evr = roots.externalVirtualRoot()
        val total = requests.size
        listener?.onProgress(0, total, STAGE_PREPARE)

        val planned = ArrayList<PlannedApp>()
        val failures = ArrayList<FailureItem>()
        val symlinks = ArrayList<ManifestSymlink>()
        var grandTotalBytes = 0L

        for ((index, request) in requests.withIndex()) {
            listener?.onProgress(index, total, STAGE_PACK + request.packageName)
            val meta = packages.getEntryMeta(request.packageName, request.userId)
            if (meta == null) {
                val msg = "package ${request.packageName} is not installed for user ${request.userId}"
                BackupLog.w(tag, msg)
                failures.add(FailureItem(request.packageName, request.userId, FailureReason.NOT_INSTALLED, msg))
                continue
            }

            // 1. Stop running instances (best effort; still live process => possibly_inconsistent).
            val stopOk = try {
                bridge.stopPackage(request.packageName, request.userId)
            } catch (t: Throwable) {
                BackupLog.w(tag, "stopPackage(${request.packageName}, u${request.userId}) failed: ${t.message}")
                false
            }
            if (stopWaitMillis > 0) {
                try {
                    Thread.sleep(stopWaitMillis)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            val stillAlive = if (stopOk) bridge.isProcessAlive(request.packageName, request.userId) else true
            val possiblyInconsistent = !stopOk || stillAlive
            if (possiblyInconsistent) {
                BackupLog.w(
                    tag,
                    "app ${request.packageName} u${request.userId} may be inconsistent (stopOk=$stopOk alive=$stillAlive)",
                )
            }

            // 2. Resolve source directories (BEnvironment layout relative to the injected roots).
            val dataUser = File(vr, "data/user/${request.userId}/${request.packageName}")
            val dataUserDe = File(vr, "data/user_de/${request.userId}/${request.packageName}")
            val appDir = File(vr, "data/app/${request.packageName}")
            val baseApk = File(appDir, BackupArchive.BASE_APK_NAME)
            val splits = File(appDir, BackupArchive.SPLITS_SECTION)
            val extData = evr?.let { File(it, "storage/emulated/${request.userId}/Android/data/${request.packageName}") }
            val extObb = evr?.let { File(it, "storage/emulated/${request.userId}/Android/obb/${request.packageName}") }

            if (request.includeApk && !baseApk.isFile) {
                val msg = "includeApk=true but base.apk is missing under ${appDir.absolutePath}"
                BackupLog.e(tag, msg)
                failures.add(FailureItem(request.packageName, request.userId, FailureReason.MISSING_APK, msg))
                continue
            }

            // 3. Walk for sizes / file counts / symlinks (metadata only, no data copy).
            val stats = SectionStats()
            val appSymlinks = ArrayList<ManifestSymlink>()
            val zipPrefix = BackupArchive.appDirName(request.packageName) + "/"
            collectSectionStats(
                dataUser, "$zipPrefix${BackupArchive.DATA_USER_SECTION}/", request.excludeCache, appSymlinks, stats,
            )
            collectSectionStats(
                dataUserDe, "$zipPrefix${BackupArchive.DATA_USER_DE_SECTION}/", request.excludeCache, appSymlinks, stats,
            )
            if (request.includeApk) {
                stats.bytes += baseApk.length()
                stats.files += 1
                collectSectionStats(splits, "$zipPrefix${BackupArchive.SPLITS_SECTION}/", false, appSymlinks, stats)
            }
            collectSectionStats(
                extData, "$zipPrefix${BackupArchive.EXT_DATA_SECTION}/", request.excludeCache, appSymlinks, stats,
            )
            collectSectionStats(
                extObb, "$zipPrefix${BackupArchive.EXT_OBB_SECTION}/", request.excludeCache, appSymlinks, stats,
            )
            grandTotalBytes += stats.bytes
            symlinks.addAll(appSymlinks)

            planned.add(
                PlannedApp(
                    request = request,
                    meta = meta,
                    dataUser = dataUser,
                    dataUserDe = dataUserDe,
                    baseApk = baseApk,
                    splits = splits,
                    extData = extData,
                    extObb = extObb,
                    possiblyInconsistent = possiblyInconsistent,
                    sizeBytes = stats.bytes,
                    fileCount = stats.files,
                    symlinks = appSymlinks,
                ),
            )
        }

        // 4. Disk space check on the volume hosting the virtual root.
        val requiredBytes = ceil(grandTotalBytes * SPACE_SAFETY_FACTOR).toLong()
        val availableBytes = spaceChecker.availableBytes(vr)
        if (availableBytes < requiredBytes) {
            throw InsufficientSpaceException(
                "insufficient space for backup: need $requiredBytes bytes " +
                    "(data $grandTotalBytes B x $SPACE_SAFETY_FACTOR safety factor), " +
                    "available $availableBytes bytes on ${vr.absolutePath}",
            )
        }

        // 5. Manifest (complete before the first zip entry is written).
        val encrypted = password != null
        val salt = if (encrypted) BackupCrypto.newSalt() else null
        val key: SecretKey? = if (encrypted) BackupCrypto.deriveKey(password!!, salt!!) else null
        val manifest = BackupManifest(
            appVersionCode = hostInfo?.appVersionCode ?: 0,
            device = hostInfo?.let { DeviceInfo(it.model, it.androidSdk) },
            encrypted = encrypted,
            kdf = salt?.let {
                ManifestKdf(
                    algo = BackupCrypto.KDF_ALGO,
                    salt = BackupManifestCodec.hex(it),
                    iterations = BackupCrypto.DEFAULT_ITERATIONS,
                    keyBits = BackupCrypto.KEY_BITS,
                    nonceBytes = BackupCrypto.NONCE_LENGTH_BYTES,
                )
            },
            apps = planned.map { p ->
                ManifestApp(
                    packageName = p.request.packageName,
                    userId = p.request.userId,
                    versionName = p.meta.versionName,
                    versionCode = p.meta.versionCode,
                    signingCertSha256 = p.meta.signingCertSha256,
                    includeApk = p.request.includeApk,
                    excludeCache = p.request.excludeCache,
                    possiblyInconsistent = p.possiblyInconsistent,
                    sizeBytes = p.sizeBytes,
                    fileCount = p.fileCount,
                )
            },
            symlinks = symlinks,
        )
        val manifestBytes = BackupManifestCodec.serialize(manifest)
        val manifestSha256 = BackupManifestCodec.sha256Hex(manifestBytes)

        // 6. Stream the archive.
        val succeeded = ArrayList<BackupedApp>()
        ZipOutputStream(BufferedOutputStream(target, BackupArchive.COPY_BUFFER_SIZE)).use { zos ->
            // Manifest entries stay plaintext so the archive stays listable/inspectable.
            BackupArchive.writeBytesEntry(zos, BackupArchive.MANIFEST_ENTRY, manifestBytes, null)
            BackupArchive.writeBytesEntry(
                zos, BackupArchive.MANIFEST_SHA256_ENTRY, manifestSha256.toByteArray(Charsets.UTF_8), null,
            )
            hostPrefs?.let {
                BackupArchive.writeBytesEntry(zos, BackupArchive.HOST_PREFS_ENTRY, HostPrefs.serialize(it), key)
            }
            for ((index, plan) in planned.withIndex()) {
                val pkg = plan.request.packageName
                try {
                    writeAppEntries(zos, plan, key)
                    succeeded.add(BackupedApp(pkg, plan.request.userId))
                    listener?.onProgress(index + 1, total, STAGE_PACK + pkg)
                } catch (e: Exception) {
                    BackupLog.e(tag, "packing app $pkg u${plan.request.userId} failed: ${e.message}")
                    failures.add(
                        FailureItem(pkg, plan.request.userId, FailureReason.IO_ERROR, "packing failed: ${e.message}"),
                    )
                }
            }
            listener?.onProgress(total, total, STAGE_FINALIZE)
        }
        BackupLog.i(
            tag,
            "backup done: ok=${succeeded.size} failed=${failures.size} symlinks=${symlinks.size} bytes=$grandTotalBytes",
        )
        return BackupResult(succeeded, failures, manifest)
    }

    // -----------------------------------------------------------------------------------------
    // Restore
    // -----------------------------------------------------------------------------------------

    /**
     * Restores applications from an archive file.
     *
     * Flow (engine spec §4.3): parse manifest -> verify manifest.sha256 -> per app (install
     * APK via temp copy when included, otherwise require installed) -> stop instances ->
     * clear target dirs -> streamed unpack (single pass) -> rebuild symlinks -> optional
     * host_prefs entry-level merge into [hostPrefsTarget].
     *
     * @param source the backup archive file (kept as File so several streaming passes are possible)
     * @param targetUserIdMapping optional source user -> target user remap; a missing entry keeps the original user id
     * @param hostPrefsTarget when non-null, host_prefs.json is written (or entry-level merged when the file already exists)
     * @throws VerifyException on manifest/sha corruption
     * @throws UnsupportedFormatException on unsupported format_version
     * @throws WrongPasswordException when a password is required or wrong
     */
    fun restore(
        source: File,
        password: CharArray?,
        targetUserIdMapping: Map<Int, Int>?,
        hostPrefsTarget: File?,
        listener: OnRestoreProgressListener?,
    ): RestoreResult {
        val tag = BackupLog.TAG_RESTORE
        if (!source.isFile) {
            throw BackupException("backup archive does not exist: ${source.absolutePath}")
        }
        BackupLog.i(tag, "restore: ${source.absolutePath} encrypted=${password != null}")

        // 1. Header pass + integrity verification (spec §4.3 step 1).
        val header = BackupArchive.readHeader(source)
        val actualSha = BackupManifestCodec.sha256Hex(header.manifestBytes)
        val storedSha = header.manifestSha256Hex
            ?: throw VerifyException("backup archive has no ${BackupArchive.MANIFEST_SHA256_ENTRY}")
        if (!storedSha.equals(actualSha, ignoreCase = true)) {
            throw VerifyException(
                "manifest integrity check failed: manifest.sha256=$storedSha but computed sha256=$actualSha",
            )
        }
        val manifest = BackupManifestCodec.deserialize(header.manifestBytes)
        val apps = manifest.apps
        val total = apps.size
        listener?.onProgress(0, total, STAGE_PREPARE)

        // 2. Key derivation.
        val key: SecretKey? = if (manifest.encrypted) {
            val pwd = password
                ?: throw WrongPasswordException("the backup is encrypted but no password was provided")
            val kdf = manifest.kdf
                ?: throw VerifyException("encrypted backup is missing the kdf descriptor in the manifest")
            BackupCrypto.deriveKey(pwd, BackupManifestCodec.unhex(kdf.salt), kdf.iterations, kdf.keyBits)
        } else {
            null
        }

        val vr = roots.virtualRoot()
        val evr = roots.externalVirtualRoot()
        val mapping = targetUserIdMapping ?: emptyMap()
        val tempRoot = tempDirProvider.tempDir()
        if (!tempRoot.isDirectory && !tempRoot.mkdirs()) {
            throw BackupException("cannot create restore temp directory: ${tempRoot.absolutePath}")
        }

        val succeeded = ArrayList<RestoredApp>()
        val failures = ArrayList<FailureItem>()
        val plans = LinkedHashMap<String, AppRestorePlan>()
        val appsByEsc = apps.associateBy { BackupArchive.escapePkg(it.packageName) }

        // 3. Extract included APKs into temp staging (single scan over the archive).
        val apkTempDirs = HashMap<String, File>()
        if (apps.any { it.includeApk }) {
            val apkRouter: (String) -> File? = { name ->
                routeEntry(name) { esc, rel ->
                    val app = appsByEsc[esc] ?: return@routeEntry null
                    if (!app.includeApk) return@routeEntry null
                    val tempDir = apkTempDirs.getOrPut(esc) {
                        File(tempRoot, "apk_${esc}_u${app.userId}_${absTouch()}")
                    }
                    if (rel == BackupArchive.BASE_APK_NAME) {
                        File(tempDir, BackupArchive.BASE_APK_NAME)
                    } else if (rel.startsWith("${BackupArchive.SPLITS_SECTION}/")) {
                        safeResolve(tempDir, rel)
                    } else {
                        null
                    }
                }
            }
            try {
                BackupArchive.extractRouted(source, apkRouter, key)
            } catch (e: Exception) {
                // APK staging is a hard prerequisite: abort early with the precise error.
                cleanupAll(apkTempDirs.values)
                throw e
            }
        }

        // 4. Per app: install (or verify installed), stop, ensure user, clear targets.
        for ((index, app) in apps.withIndex()) {
            val pkg = app.packageName
            val sourceUserId = app.userId
            val targetUserId = mapping[sourceUserId] ?: sourceUserId
            listener?.onProgress(index, total, STAGE_INSTALL + pkg)
            try {
                val esc = BackupArchive.escapePkg(pkg)
                if (app.includeApk) {
                    val base = apkTempDirs[esc]?.let { File(it, BackupArchive.BASE_APK_NAME) }
                    if (base == null || !base.isFile) {
                        throw MissingApkException("archive declares includeApk for $pkg but no base.apk entry exists")
                    }
                    val installed = try {
                        bridge.install(base, targetUserId)
                    } catch (t: Throwable) {
                        BackupLog.e(tag, "install($pkg, u$targetUserId) threw: ${t.message}")
                        false
                    }
                    if (!installed) {
                        failures.add(
                            FailureItem(pkg, targetUserId, FailureReason.INSTALL_FAILED, "installPackageAsUser failed"),
                        )
                        continue
                    }
                } else if (!packages.isInstalled(pkg, targetUserId)) {
                    failures.add(
                        FailureItem(
                            pkg, targetUserId, FailureReason.MISSING_APK,
                            "archive has no APK for $pkg and it is not installed for user $targetUserId",
                        ),
                    )
                    continue
                }

                // Stop running instances before wiping/replacing data (best effort).
                val stopOk = try {
                    bridge.stopPackage(pkg, targetUserId)
                } catch (t: Throwable) {
                    BackupLog.w(tag, "restore: stopPackage($pkg, u$targetUserId) failed: ${t.message}")
                    false
                }
                if (!stopOk) {
                    BackupLog.w(tag, "restore: proceeding for $pkg although stop failed (data dirs will be cleared)")
                }
                if (!bridge.ensureUser(targetUserId)) {
                    BackupLog.w(tag, "restore: ensureUser($targetUserId) failed; continuing")
                }

                // Clear target directories first => idempotent unpack (spec §4.3 step 3).
                val dataUser = File(vr, "data/user/$targetUserId/$pkg")
                val dataUserDe = File(vr, "data/user_de/$targetUserId/$pkg")
                val extData = evr?.let { File(it, "storage/emulated/$targetUserId/Android/data/$pkg") }
                val extObb = evr?.let { File(it, "storage/emulated/$targetUserId/Android/obb/$pkg") }
                deleteRecursive(dataUser)
                deleteRecursive(dataUserDe)
                extData?.let { deleteRecursive(it) }
                extObb?.let { deleteRecursive(it) }

                plans[esc] = AppRestorePlan(app, targetUserId, dataUser, dataUserDe, extData, extObb)
            } catch (e: MissingApkException) {
                failures.add(FailureItem(pkg, targetUserId, FailureReason.MISSING_APK, e.message))
            } catch (e: Exception) {
                BackupLog.e(tag, "restore prep failed for $pkg u$sourceUserId: ${e.message}")
                failures.add(FailureItem(pkg, targetUserId, FailureReason.IO_ERROR, "restore prep failed: ${e.message}"))
            }
        }
        cleanupAll(apkTempDirs.values)

        // 5. Single scan: unpack data sections for all successfully prepared apps.
        if (plans.isNotEmpty()) {
            listener?.onProgress(plans.size, total, STAGE_UNPACK)
            val errorCounts = HashMap<String, Int>()
            val dataRouter: (String) -> File? = { name ->
                routeEntry(name) { esc, rel ->
                    val plan = plans[esc] ?: return@routeEntry null
                    routeSectionRelative(plan, rel)
                }
            }
            try {
                BackupArchive.extractRouted(source, dataRouter, key) { name, error ->
                    val esc = name.removePrefix(BackupArchive.APPS_ROOT).substringBefore('/')
                    errorCounts[esc] = (errorCounts[esc] ?: 0) + 1
                    BackupLog.w(tag, "entry '$name' failed to unpack: ${error.message}")
                }
                for ((esc, count) in errorCounts) {
                    val plan = plans[esc] ?: continue
                    failures.add(
                        FailureItem(
                            plan.app.packageName, plan.targetUserId, FailureReason.IO_ERROR,
                            "$count archive entry(ies) failed to unpack",
                        ),
                    )
                }
                for ((esc, plan) in plans) {
                    if (errorCounts.containsKey(esc)) continue
                    val certCheck = compareSigningCert(
                        plan.app.signingCertSha256,
                        packages.getEntryMeta(plan.app.packageName, plan.targetUserId)?.signingCertSha256,
                    )
                    if (certCheck == CertCheck.MISMATCH) {
                        BackupLog.w(
                            tag,
                            "signature mismatch for ${plan.app.packageName}: backupSha=${plan.app.signingCertSha256}",
                        )
                    }
                    succeeded.add(
                        RestoredApp(
                            packageName = plan.app.packageName,
                            userId = plan.targetUserId,
                            sourceUserId = plan.app.userId,
                            signatureWarning = certCheck == CertCheck.MISMATCH,
                        ),
                    )
                }
            } catch (e: VerifyException) {
                throw e
            } catch (e: WrongPasswordException) {
                throw e
            } catch (e: Exception) {
                BackupLog.e(tag, "data unpack pass failed: ${e.message}")
                throw BackupException("data unpack pass failed: ${e.message}", e)
            }
        }

        // 6. Rebuild symbolic links recorded in the manifest (existing targets removed first).
        if (manifest.symlinks.isNotEmpty()) {
            listener?.onProgress(total, total, STAGE_SYMLINKS)
            for (link in manifest.symlinks) {
                val esc = link.path.removePrefix(BackupArchive.APPS_ROOT).substringBefore('/')
                val plan = plans[esc] ?: continue
                val dest = routeEntry(link.path) { _, rel -> routeSectionRelative(plan, rel) }
                if (dest == null) {
                    BackupLog.w(tag, "cannot route symlink path '${link.path}', skipped")
                    continue
                }
                if (!symlinkSupport.createSymlink(dest, link.target)) {
                    BackupLog.w(tag, "symlink rebuild failed: ${dest.absolutePath} -> ${link.target}")
                }
            }
        }

        // 7. host_prefs.json: parse and optionally entry-level merge into the target file.
        var hostPrefs: HostPrefs? = null
        if (header.hasHostPrefs()) {
            val bytes = BackupArchive.readNamedEntry(source, BackupArchive.HOST_PREFS_ENTRY, key)
            if (bytes != null) {
                try {
                    hostPrefs = HostPrefs.deserialize(bytes)
                } catch (e: Exception) {
                    BackupLog.w(tag, "host_prefs.json is malformed, ignoring: ${e.message}")
                }
                if (hostPrefsTarget != null) {
                    try {
                        if (hostPrefsTarget.isFile) {
                            val report = ConfMerger.restoreAll(
                                hostPrefsTarget.readBytes(), bytes, JsonMapConfCodec(),
                            )
                            hostPrefsTarget.writeBytes(report.mergedBytes)
                            BackupLog.i(
                                tag,
                                "host_prefs merged: replaced=${report.replacedKeys.size} added=${report.addedKeys.size}",
                            )
                        } else {
                            hostPrefsTarget.parentFile?.mkdirs()
                            hostPrefsTarget.writeBytes(bytes)
                        }
                    } catch (e: Exception) {
                        BackupLog.w(tag, "host_prefs write/merge failed: ${e.message}")
                    }
                }
            }
        }

        listener?.onProgress(total, total, STAGE_DONE)
        BackupLog.i(tag, "restore done: ok=${succeeded.size} failed=${failures.size}")
        return RestoreResult(succeeded, failures, hostPrefs)
    }

    // -----------------------------------------------------------------------------------------
    // Signature comparison (pure, unit-tested)
    // -----------------------------------------------------------------------------------------

    enum class CertCheck { MATCH, MISMATCH, UNKNOWN }

    /**
     * Pure signature comparison used by the restore flow: a mismatch is a WARNING on the
     * restored app, never a hard failure. Unknown on either side yields [CertCheck.UNKNOWN].
     */
    fun compareSigningCert(backupSha256: String?, installedSha256: String?): CertCheck = when {
        backupSha256.isNullOrEmpty() || installedSha256.isNullOrEmpty() -> CertCheck.UNKNOWN
        backupSha256.equals(installedSha256, ignoreCase = true) -> CertCheck.MATCH
        else -> CertCheck.MISMATCH
    }

    // -----------------------------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------------------------

    private class PlannedApp(
        val request: BackupRequest,
        val meta: AppMeta,
        val dataUser: File,
        val dataUserDe: File,
        val baseApk: File,
        val splits: File,
        val extData: File?,
        val extObb: File?,
        val possiblyInconsistent: Boolean,
        val sizeBytes: Long,
        val fileCount: Int,
        val symlinks: List<ManifestSymlink>,
    )

    private class AppRestorePlan(
        val app: ManifestApp,
        val targetUserId: Int,
        val dataUser: File,
        val dataUserDe: File,
        val extData: File?,
        val extObb: File?,
    )

    private class SectionStats {
        var bytes = 0L
        var files = 0
    }

    private fun collectSectionStats(
        dir: File?,
        zipPrefix: String,
        excludeCache: Boolean,
        symlinks: MutableList<ManifestSymlink>,
        stats: SectionStats,
    ) {
        if (dir == null || !dir.isDirectory) return
        walkStats(dir, zipPrefix, if (excludeCache) BackupArchive.CACHE_DIR_NAME else null, symlinks, stats)
    }

    private fun walkStats(
        dir: File,
        zipPrefix: String,
        excludeCacheDirName: String?,
        symlinks: MutableList<ManifestSymlink>,
        stats: SectionStats,
    ) {
        val children = dir.listFiles()
        if (children == null) {
            BackupLog.w(BackupLog.TAG_BACKUP, "cannot list directory: ${dir.absolutePath}")
            return
        }
        for (child in children) {
            val relative = BackupArchive.relativePath(dir, child)
            if (excludeCacheDirName != null && child.isDirectory && relative == excludeCacheDirName) {
                continue
            }
            val zipPath = zipPrefix + relative
            if (symlinkSupport.isSymlink(child)) {
                val target = symlinkSupport.readSymlinkTarget(child)
                if (target != null) {
                    symlinks.add(ManifestSymlink(zipPath, target))
                } else {
                    BackupLog.w(BackupLog.TAG_BACKUP, "symlink without readable target, skipped: $zipPath")
                }
                continue
            }
            if (child.isDirectory) {
                walkStats(child, "$zipPath/", excludeCacheDirName, symlinks, stats)
            } else if (child.isFile) {
                stats.bytes += child.length()
                stats.files += 1
            }
        }
    }

    private fun writeAppEntries(zos: ZipOutputStream, plan: PlannedApp, key: SecretKey?) {
        val appZipRoot = BackupArchive.appDirName(plan.request.packageName) + "/"
        val excludeCache = if (plan.request.excludeCache) BackupArchive.CACHE_DIR_NAME else null
        val skipZipPaths = plan.symlinks.map { it.path }.toSet()
        val onUnexpectedSymlink = { zipPath: String, target: String ->
            BackupLog.w(BackupLog.TAG_BACKUP, "new symlink appeared during packing, skipped: $zipPath -> $target")
            Unit
        }
        // Data sections.
        BackupArchive.writeDirRecursively(
            zos, plan.dataUser, "$appZipRoot${BackupArchive.DATA_USER_SECTION}/",
            excludeCache, key, symlinkSupport, skipZipPaths, onUnexpectedSymlink,
        )
        BackupArchive.writeDirRecursively(
            zos, plan.dataUserDe, "$appZipRoot${BackupArchive.DATA_USER_DE_SECTION}/",
            excludeCache, key, symlinkSupport, skipZipPaths, onUnexpectedSymlink,
        )
        // APK (base + splits).
        if (plan.request.includeApk && plan.baseApk.isFile) {
            BackupArchive.writeFileEntry(zos, appZipRoot + BackupArchive.BASE_APK_NAME, plan.baseApk, key)
        }
        if (plan.request.includeApk && plan.splits.isDirectory) {
            BackupArchive.writeDirRecursively(
                zos, plan.splits, "$appZipRoot${BackupArchive.SPLITS_SECTION}/",
                null, key, symlinkSupport, skipZipPaths, onUnexpectedSymlink,
            )
        }
        // External storage sections (written only when they exist).
        plan.extData?.let {
            BackupArchive.writeDirRecursively(
                zos, it, "$appZipRoot${BackupArchive.EXT_DATA_SECTION}/",
                excludeCache, key, symlinkSupport, skipZipPaths, onUnexpectedSymlink,
            )
        }
        plan.extObb?.let {
            BackupArchive.writeDirRecursively(
                zos, it, "$appZipRoot${BackupArchive.EXT_OBB_SECTION}/",
                excludeCache, key, symlinkSupport, skipZipPaths, onUnexpectedSymlink,
            )
        }
        // Per-app metadata.
        val entry = EntryInfo(
            uid = plan.meta.appId,
            versionName = plan.meta.versionName,
            versionCode = plan.meta.versionCode,
            signingCertSha256 = plan.meta.signingCertSha256,
            sizeBytes = plan.sizeBytes,
            fileCount = plan.fileCount,
        )
        BackupArchive.writeBytesEntry(
            zos, appZipRoot + BackupArchive.ENTRY_JSON, BackupManifestCodec.serializeEntry(entry), key,
        )
    }

    /**
     * Splits "apps/{esc}/relative" style zip paths; only entries under [BackupArchive.APPS_ROOT]
     * reach [handler], everything else (manifest.json etc.) maps to null.
     */
    private inline fun routeEntry(name: String, handler: (esc: String, relative: String) -> File?): File? {
        if (!name.startsWith(BackupArchive.APPS_ROOT)) return null
        val rest = name.substring(BackupArchive.APPS_ROOT.length)
        val slash = rest.indexOf('/')
        if (slash <= 0) return null
        val esc = rest.substring(0, slash)
        val relative = rest.substring(slash + 1)
        if (relative.isEmpty()) return null
        return handler(esc, relative)
    }

    private fun routeSectionRelative(plan: AppRestorePlan, relative: String): File? {
        val section = relative.substringBefore('/')
        val sectionDir: File? = when (section) {
            BackupArchive.DATA_USER_SECTION -> plan.dataUser
            BackupArchive.DATA_USER_DE_SECTION -> plan.dataUserDe
            BackupArchive.EXT_DATA_SECTION -> plan.extData
            BackupArchive.EXT_OBB_SECTION -> plan.extObb
            else -> return null
        }
        if (sectionDir == null) {
            BackupLog.w(
                BackupLog.TAG_RESTORE,
                "external storage root unavailable on this device, cannot route '$relative'",
            )
            return null
        }
        return safeResolve(sectionDir, relative.substringAfter('/'))
    }

    /**
     * Resolves [relative] under [baseDir], rejecting path traversal (".."), absolute paths and
     * backslashes so hostile zip entry names cannot escape the destination directory.
     */
    private fun safeResolve(baseDir: File, relative: String): File? {
        if (relative.isEmpty() || relative.startsWith("/") || relative.contains('\\')) return null
        var file = baseDir
        for (part in relative.split('/')) {
            if (part.isEmpty() || part == ".") continue
            if (part == "..") return null
            file = File(file, part)
        }
        return if (file == baseDir) null else file
    }

    private fun deleteRecursive(file: File?) {
        if (file == null || !file.exists()) return
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursive(it) }
        }
        if (!file.delete() && file.exists()) {
            BackupLog.w(BackupLog.TAG_RESTORE, "failed to delete ${file.absolutePath}")
        }
    }

    private fun cleanupAll(files: Collection<File>) {
        files.forEach { deleteRecursive(it) }
    }

    private fun absTouch(): Long = System.currentTimeMillis()

    companion object {
        /** How long to wait after stopPackage before continuing (engine spec §4.2). */
        const val DEFAULT_STOP_WAIT_MILLIS = 1000L

        /** Space requirement safety factor (engine spec §4.2). */
        const val SPACE_SAFETY_FACTOR = 1.1

        const val STAGE_PREPARE = "prepare"
        const val STAGE_PACK = "pack:"
        const val STAGE_INSTALL = "install:"
        const val STAGE_UNPACK = "unpack"
        const val STAGE_SYMLINKS = "symlinks"
        const val STAGE_FINALIZE = "finalize"
        const val STAGE_DONE = "done"
    }
}
