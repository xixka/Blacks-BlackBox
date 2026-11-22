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

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.backup.exception.BackupException
import top.niunaijun.blackbox.utils.Slog
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * Facade (singleton) of the blackbox-backup v1 engine for the app layer.
 *
 * THREADING CONTRACT (important for the UI layer):
 *  - The actual packing/unpacking work runs on a dedicated single background thread
 *    ("blackbox-backup"), NEVER on the caller's thread.
 *  - [OnBackupProgressListener] / [OnRestoreProgressListener] callbacks are ALWAYS posted to
 *    the MAIN thread, so UI code can update views directly.
 *  - backup()/restore() BLOCK the calling thread until the operation finishes and return the
 *    result directly (or rethrow the failure). Call them from a coroutine/background thread,
 *    not from the main thread.
 *
 * All failures are explicit: per-application problems are listed in
 * [BackupResult.failed]/[RestoreResult.failed]; global problems (insufficient space, wrong
 * password, corrupted archive, unsupported format) are thrown as [BackupException] subclasses.
 */
/**
 * One application selected for backup.
 */
data class BackupRequest(
    val packageName: String,
    val userId: Int,
    /** Include data/app/{pkg}/base.apk + splits/ in the archive (default true). */
    val includeApk: Boolean = true,
    /** Exclude cache/ directories from data sections (default true, recorded in manifest). */
    val excludeCache: Boolean = true,
)

class BackupManager private constructor() {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "blackbox-backup").apply { isDaemon = false }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        // Route engine logs into logcat with the conventional TAGs.
        BackupLog.sink = object : BackupLog.Sink {
            override fun log(tag: String, priority: Int, message: String) {
                when (priority) {
                    BackupLog.PRIORITY_DEBUG -> Slog.d(tag, message)
                    BackupLog.PRIORITY_INFO -> Slog.i(tag, message)
                    BackupLog.PRIORITY_WARN -> Slog.w(tag, message)
                    BackupLog.PRIORITY_ERROR -> Slog.e(tag, message)
                    else -> Slog.i(tag, message)
                }
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------------------------

    /**
     * Packs the selected applications into [targetStream] (the stream is closed by the engine).
     *
     * @param password optional; when non-empty the archive contents are AES-256-GCM encrypted
     * @param listener progress callback, invoked on the MAIN thread
     * @throws BackupException (or subclass) on global failures
     */
    @JvmOverloads
    fun backup(
        context: Context,
        requests: List<BackupRequest>,
        targetStream: OutputStream,
        password: CharArray? = null,
        listener: OnBackupProgressListener? = null,
    ): BackupResult = backupInternal(
        context, requests, targetStream, password, null, listener,
    )

    /**
     * Same as [backup] but lets the app layer attach its host preferences (sorting / notes /
     * freeze flags) which are stored as host_prefs.json inside the archive.
     */
    fun backup(
        context: Context,
        requests: List<BackupRequest>,
        targetStream: OutputStream,
        password: CharArray?,
        hostPrefs: HostPrefs?,
        listener: OnBackupProgressListener?,
    ): BackupResult = backupInternal(
        context, requests, targetStream, password, hostPrefs, listener,
    )

    /**
     * Restores applications from [sourceStream] (any InputStream; it is copied to a temp file
     * in cacheDir first, in streaming fashion, then processed from there).
     *
     * @param targetUserIdMapping optional source-user -> target-user remap; a missing entry keeps the original user id
     * @param listener progress callback, invoked on the MAIN thread
     * @throws BackupException (or subclass) on global failures
     */
    @JvmOverloads
    fun restore(
        context: Context,
        sourceStream: InputStream,
        password: CharArray? = null,
        targetUserIdMapping: Map<Int, Int>? = null,
        listener: OnRestoreProgressListener? = null,
    ): RestoreResult {
        val tempFile = File(context.cacheDir, "blackbox_restore_${System.currentTimeMillis()}.zip")
        try {
            tempFile.outputStream().buffered(COPY_BUFFER).use { out ->
                sourceStream.copyTo(out, COPY_BUFFER)
            }
            return restoreInternal(context, tempFile, password, targetUserIdMapping, null, listener)
        } finally {
            tempFile.delete()
        }
    }

    /** Same as [restore] but from an already available archive file (no stream copy). */
    @JvmOverloads
    fun restore(
        context: Context,
        sourceFile: File,
        password: CharArray? = null,
        targetUserIdMapping: Map<Int, Int>? = null,
        listener: OnRestoreProgressListener? = null,
    ): RestoreResult = restoreInternal(
        context, sourceFile, password, targetUserIdMapping, null, listener,
    )

    /**
     * Same as [restore] but lets the app layer receive the merged host_prefs.json at
     * [hostPrefsTarget]: when the file already exists, a backup-vs-device ENTRY-LEVEL merge
     * is performed (backup wins for the keys it carries, all other entries preserved).
     */
    fun restore(
        context: Context,
        sourceFile: File,
        password: CharArray?,
        targetUserIdMapping: Map<Int, Int>?,
        hostPrefsTarget: File?,
        listener: OnRestoreProgressListener?,
    ): RestoreResult = restoreInternal(
        context, sourceFile, password, targetUserIdMapping, hostPrefsTarget, listener,
    )

    // -----------------------------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------------------------

    private fun backupInternal(
        context: Context,
        requests: List<BackupRequest>,
        targetStream: OutputStream,
        password: CharArray?,
        hostPrefs: HostPrefs?,
        listener: OnBackupProgressListener?,
    ): BackupResult {
        val proxiedListener = listener?.let { l ->
            OnBackupProgressListener { current, total, stage ->
                mainHandler.post { l.onProgress(current, total, stage) }
            }
        }
        return submit {
            engine().backup(
                requests = requests,
                target = targetStream,
                password = password,
                hostPrefs = hostPrefs,
                hostInfo = buildHostInfo(context),
                listener = proxiedListener,
            )
        }
    }

    private fun restoreInternal(
        context: Context,
        sourceFile: File,
        password: CharArray?,
        targetUserIdMapping: Map<Int, Int>?,
        hostPrefsTarget: File?,
        listener: OnRestoreProgressListener?,
    ): RestoreResult {
        val proxiedListener = listener?.let { l ->
            OnRestoreProgressListener { current, total, stage ->
                mainHandler.post { l.onProgress(current, total, stage) }
            }
        }
        return submit {
            engine().restore(
                source = sourceFile,
                password = password,
                targetUserIdMapping = targetUserIdMapping,
                hostPrefsTarget = hostPrefsTarget,
                listener = proxiedListener,
            )
        }
    }

    private fun <T> submit(block: () -> T): T {
        val future = executor.submit(Callable(block))
        return try {
            future.get()
        } catch (e: ExecutionException) {
            val cause = e.cause ?: e
            if (cause is RuntimeException) throw cause else throw IllegalStateException(cause)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw BackupException("backup operation interrupted", e)
        }
    }

    private fun engine(): BackupEngine = BackupEngine(
        roots = AndroidDataRootProvider,
        spaceChecker = AndroidSpaceChecker,
        packages = AndroidPackageInfoProvider(),
        bridge = AndroidInstallBridge(),
        tempDirProvider = AndroidTempDirProvider(BlackBoxCore.getContext()),
        symlinkSupport = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            JavaNioSymlinkSupport
        } else {
            OsSymlinkSupport
        },
        stopWaitMillis = BackupEngine.DEFAULT_STOP_WAIT_MILLIS,
    )

    private fun buildHostInfo(context: Context): HostInfo {
        val versionCode = try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pi.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pi.versionCode
            }
        } catch (t: Throwable) {
            Slog.w(TAG, "cannot read host version code: ${t.message}")
            0
        }
        return HostInfo(model = Build.MODEL, androidSdk = Build.VERSION.SDK_INT, appVersionCode = versionCode)
    }

    companion object {
        const val TAG = "Backup"
        const val COPY_BUFFER = 64 * 1024

        @Volatile
        private var sInstance: BackupManager? = null

        @JvmStatic
        fun get(): BackupManager = sInstance ?: synchronized(this) {
            sInstance ?: BackupManager().also { sInstance = it }
        }
    }
}
