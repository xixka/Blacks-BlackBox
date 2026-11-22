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

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.StatFs
import android.system.ErrnoException
import android.system.Os
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.core.env.BEnvironment
import top.niunaijun.blackbox.core.system.user.BUserHandle
import top.niunaijun.blackbox.fake.frameworks.BPackageManager
import top.niunaijun.blackbox.fake.frameworks.BUserManager
import top.niunaijun.blackbox.utils.Slog
import java.io.File
import java.security.MessageDigest

/**
 * Android-bound implementations of the pure engine interfaces. Everything in this file is
 * device-only (uses BlackBoxCore / BEnvironment / android APIs) and therefore NOT covered by
 * the JVM unit tests, which inject their own fakes.
 */
object AndroidDataRootProvider : DataRootProvider {
    override fun virtualRoot(): File = BEnvironment.getVirtualRoot()

    override fun externalVirtualRoot(): File? = BEnvironment.getExternalVirtualRoot()
}

/** StatFs based probe for the volume holding [volumeRoot] (the virtual root on device). */
object AndroidSpaceChecker : SpaceChecker {
    override fun availableBytes(volumeRoot: File): Long = try {
        StatFs(volumeRoot.absolutePath).availableBytes
    } catch (t: Throwable) {
        // Explicit failure instead of reporting a bogus "plenty of space" value.
        throw top.niunaijun.blackbox.backup.exception.BackupException(
            "cannot read free space for volume ${volumeRoot.absolutePath}: ${t.message}", t,
        )
    }
}

/**
 * Package metadata provider backed by BPackageManager. Signing certificates are hashed with
 * SHA-256 over the first signature's raw bytes (GET_SIGNATURES works on every supported API
 * level because PackageManagerCompat.generatePackageInfo fills pi.signatures for it).
 */
class AndroidPackageInfoProvider : PackageInfoProvider {

    override fun getEntryMeta(packageName: String, userId: Int): AppMeta? {
        val flags = PackageManager.GET_META_DATA or PackageManager.GET_SIGNATURES
        val pi = try {
            BPackageManager.get().getPackageInfo(packageName, flags, userId)
        } catch (t: Throwable) {
            Slog.w(TAG, "getPackageInfo($packageName, u$userId) failed: ${t.message}")
            null
        } ?: return null
        val appId = try {
            pi.applicationInfo?.uid?.let { BUserHandle.getAppId(it) } ?: -1
        } catch (t: Throwable) {
            -1
        }
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pi.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            pi.versionCode.toLong()
        }
        val certSha256 = pi.signatures?.firstOrNull()?.toByteArray()?.let { sha256Hex(it) }
        return AppMeta(
            packageName = packageName,
            userId = userId,
            appId = appId,
            versionName = pi.versionName,
            versionCode = versionCode,
            signingCertSha256 = certSha256,
        )
    }

    override fun isInstalled(packageName: String, userId: Int): Boolean = try {
        BPackageManager.get().isInstalled(packageName, userId)
    } catch (t: Throwable) {
        Slog.w(TAG, "isInstalled($packageName, u$userId) failed: ${t.message}")
        false
    }

    companion object {
        const val TAG = "Backup"

        fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return BackupManifestCodec.hex(digest)
        }
    }
}

/**
 * Install/stop/user bridge backed by BlackBoxCore + BPackageManager + BUserManager.
 * All methods are best effort and report failures through their boolean result plus logs.
 */
class AndroidInstallBridge : InstallBridge {

    override fun install(apk: File, userId: Int): Boolean = try {
        val result = BlackBoxCore.get().installPackageAsUser(apk, userId)
        val ok = result?.success == true
        if (!ok) {
            Slog.e(TAG, "install(${apk.name}, u$userId) failed: ${result?.msg}")
        }
        ok
    } catch (t: Throwable) {
        Slog.e(TAG, "install(${apk.name}, u$userId) threw: ${t.message}")
        false
    }

    override fun stopPackage(packageName: String, userId: Int): Boolean = try {
        BlackBoxCore.get().stopPackage(packageName, userId)
        true
    } catch (t: Throwable) {
        Slog.w(TAG, "stopPackage($packageName, u$userId) failed: ${t.message}")
        false
    }

    override fun ensureUser(userId: Int): Boolean = try {
        // createUser returns the existing user when it already exists, null when the
        // user-manager service is unavailable.
        BUserManager.get().createUser(userId) != null
    } catch (t: Throwable) {
        Slog.w(TAG, "ensureUser($userId) failed: ${t.message}")
        false
    }

    /**
     * Best effort probe: virtual app processes run as host processes whose process name starts
     * with the virtual package name (or a "pkg:" suffixed private process). Used only for the
     * manifest "possibly_inconsistent" flag, so false negatives are acceptable.
     */
    override fun isProcessAlive(packageName: String, userId: Int): Boolean = try {
        val am = BlackBoxCore.getContext().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val processes = am.runningAppProcesses ?: emptyList()
        processes.any { info ->
            val name = info.processName ?: return@any false
            name == packageName || name.startsWith("$packageName:") || name.startsWith("$packageName.")
        }
    } catch (t: Throwable) {
        Slog.w(TAG, "isProcessAlive($packageName, u$userId) failed: ${t.message}")
        false
    }

    companion object {
        const val TAG = "Backup"
    }
}

/** Temp staging provider rooted in the host cache dir. */
class AndroidTempDirProvider(context: Context) : TempDirProvider {
    private val dir = File(context.cacheDir, "blackbox_backup_tmp")

    override fun tempDir(): File {
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            throw top.niunaijun.blackbox.backup.exception.BackupException(
                "cannot create temp directory: ${dir.absolutePath}",
            )
        }
        return dir
    }
}

/**
 * android.system.Os based symlink support for devices below API 26 (java.nio.file.Files is
 * API 26+). The NIO implementation [JavaNioSymlinkSupport] is used on API 26+.
 */
object OsSymlinkSupport : SymlinkSupport {

    override fun isSymlink(file: File): Boolean = try {
        // readlink(2) succeeds only on symlinks; regular files/dirs fail with EINVAL.
        Os.readlink(file.absolutePath)
        true
    } catch (e: ErrnoException) {
        false
    } catch (e: Exception) {
        false
    }

    override fun readSymlinkTarget(file: File): String? = try {
        Os.readlink(file.absolutePath)
    } catch (e: Exception) {
        null
    }

    override fun createSymlink(link: File, target: String): Boolean = try {
        if (link.exists() && !link.delete()) {
            Slog.w(TAG, "cannot remove existing path for symlink: ${link.absolutePath}")
        }
        link.parentFile?.mkdirs()
        // POSIX symlink(2) argument order: (target, linkpath).
        Os.symlink(target, link.absolutePath)
        true
    } catch (e: ErrnoException) {
        Slog.w(TAG, "symlink($target -> ${link.absolutePath}) failed: errno=${e.errno}")
        false
    } catch (e: Exception) {
        Slog.w(TAG, "symlink($target -> ${link.absolutePath}) failed: ${e.message}")
        false
    }


    private const val TAG = "Restore"
}
