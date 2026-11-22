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

import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.onebitmonochrome.blacksbbox.R
import com.onebitmonochrome.blacksbbox.bean.AppInfo
import com.onebitmonochrome.blacksbbox.data.AppsRepository
import com.onebitmonochrome.blacksbbox.data.AppsSortKeys
import com.onebitmonochrome.blacksbbox.data.AppSortEntry
import com.onebitmonochrome.blacksbbox.view.base.BaseViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.niunaijun.blackbox.backup.BackupManifest
import top.niunaijun.blackbox.backup.HostPrefs
import top.niunaijun.blackbox.backup.exception.BackupException
import top.niunaijun.blackbox.backup.exception.InsufficientSpaceException
import top.niunaijun.blackbox.backup.exception.MissingApkException
import top.niunaijun.blackbox.backup.exception.UnsupportedFormatException
import top.niunaijun.blackbox.backup.exception.VerifyException
import top.niunaijun.blackbox.backup.exception.WrongPasswordException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Result of validating the optional backup password entered twice by the user.
 */
sealed class PasswordValidation {
    /** Both fields blank: no encryption. */
    object None : PasswordValidation()

    /** Both fields equal and non-blank: encrypt with this password. */
    class Valid(val password: CharArray) : PasswordValidation()

    /** Fields differ (or only one is filled): user must fix the input. */
    object Mismatch : PasswordValidation()
}

/**
 * One row of the restore preview list, derived purely from [BackupManifest].
 */
data class RestorePreviewRow(
        val packageName: String,
        val userId: Int,
        val versionName: String?,
        val sizeBytes: Long,
        val includeApk: Boolean,
)

/**
 * Pure, JVM-only UI logic for the backup/restore screens. No android.* calls beyond
 * [Log]/resource ids, so everything here is unit-testable (see BackupUiLogicTest).
 *
 * HostPrefs key conventions used by this app layer (the engine treats host_prefs keys as
 * opaque strings; these families mirror HostPrefs' documented key prefixes):
 *  - "sort:{pkg}@{userId}" -> position index in the global sort order (AppListAll entries
 *    are "pkg@userId" pairs, so the space id is part of the key to avoid cross-space clashes)
 *  - "note:space:{userId}" -> space remark ("Remark$userId" SharedPreferences value; the
 *    "space:" infix makes space-level notes unambiguous against a future per-app "note:{pkg}")
 *  - "frozen:{pkg}@{userId}" -> "true" when the app is excluded from auto-freeze
 */
object BackupUiLogic {
    private const val TAG = "BackupUiLogic"

    const val MAX_PASSWORD_ATTEMPTS = 3
    const val FROZEN_VALUE_TRUE = "true"
    const val KEY_NOTE_SPACE_PREFIX = "note:space:"
    const val BACKUP_FILE_PREFIX = "blackbox_backup_"
    const val BACKUP_FILE_SUFFIX = ".zip"
    private const val BACKUP_TIMESTAMP_PATTERN = "yyyyMMdd_HHmmss"
    private const val MANIFEST_TIMESTAMP_PATTERN = "yyyy-MM-dd'T'HH:mm:ssZ"
    private const val DISPLAY_TIMESTAMP_PATTERN = "yyyy-MM-dd HH:mm"

    private val formatVersionRegex = Regex("format_version=(\\d+)")

    // -----------------------------------------------------------------------------------------
    // Password / file name
    // -----------------------------------------------------------------------------------------

    /**
     * Validates the optional password pair. Blank/blank means "no encryption"; equal
     * non-blank values mean "encrypt"; anything else is a mismatch the user must fix.
     */
    fun checkPasswordPair(first: String?, second: String?): PasswordValidation {
        val f = first ?: ""
        val s = second ?: ""
        return when {
            f.isBlank() && s.isBlank() -> PasswordValidation.None
            f == s -> PasswordValidation.Valid(f.toCharArray())
            else -> PasswordValidation.Mismatch
        }
    }

    /** Suggested SAF file name, e.g. blackbox_backup_20261122_123000.zip. */
    fun backupFileName(nowMillis: Long): String {
        val fmt = SimpleDateFormat(BACKUP_TIMESTAMP_PATTERN, Locale.US)
        return BACKUP_FILE_PREFIX + fmt.format(Date(nowMillis)) + BACKUP_FILE_SUFFIX
    }

    // -----------------------------------------------------------------------------------------
    // Progress stage translation
    // -----------------------------------------------------------------------------------------

    /** Maps an engine stage label ("prepare"/"pack:x"/"install:x"/"unpack"/"symlinks"/"finalize"/"done") to a display string resource. */
    fun stageToLabelRes(stage: String): Int {
        return when {
            stage == "prepare" -> R.string.backup_stage_prepare
            stage.startsWith("pack:") -> R.string.backup_stage_pack
            stage.startsWith("install:") -> R.string.restore_stage_install
            stage == "unpack" -> R.string.restore_stage_unpack
            stage == "symlinks" -> R.string.restore_stage_symlinks
            stage == "finalize" -> R.string.backup_stage_finalize
            stage == "done" -> R.string.restore_stage_done
            else -> R.string.backup_stage_generic
        }
    }

    /** Package name carried by "pack:x" / "install:x" stages, null otherwise. */
    fun stageArg(stage: String): String? {
        return if (stage.startsWith("pack:") || stage.startsWith("install:")) {
            stage.substringAfter(':')
        } else {
            null
        }
    }

    // -----------------------------------------------------------------------------------------
    // Failure reasons / global errors
    // -----------------------------------------------------------------------------------------

    /** Maps a per-app [FailureReason][top.niunaijun.blackbox.backup.FailureReason] to its display string resource. */
    fun failureReasonRes(reason: top.niunaijun.blackbox.backup.FailureReason): Int {
        return when (reason) {
            top.niunaijun.blackbox.backup.FailureReason.NOT_INSTALLED -> R.string.backup_failure_not_installed
            top.niunaijun.blackbox.backup.FailureReason.INSTALL_FAILED -> R.string.backup_failure_install_failed
            top.niunaijun.blackbox.backup.FailureReason.MISSING_APK -> R.string.backup_failure_missing_apk
            top.niunaijun.blackbox.backup.FailureReason.SPACE_INSUFFICIENT -> R.string.backup_failure_space_insufficient
            top.niunaijun.blackbox.backup.FailureReason.VERIFY_FAILED -> R.string.backup_failure_verify_failed
            top.niunaijun.blackbox.backup.FailureReason.IO_ERROR -> R.string.backup_failure_io_error
            top.niunaijun.blackbox.backup.FailureReason.UNKNOWN -> R.string.backup_failure_unknown
        }
    }

    /** Maps a global engine failure to its display string resource. */
    fun errorResFor(t: Throwable): Int {
        return when (t) {
            is UnsupportedFormatException -> R.string.backup_error_unsupported_format
            is VerifyException -> R.string.backup_error_verify
            is WrongPasswordException -> R.string.backup_error_wrong_password
            is InsufficientSpaceException -> R.string.backup_error_space
            is MissingApkException -> R.string.backup_error_missing_apk
            is BackupException -> R.string.backup_error_generic
            else -> R.string.backup_error_generic
        }
    }

    /**
     * Extracts the offending format_version from an UnsupportedFormatException message,
     * e.g. "... format_version=2 is not supported ..." -> 2. Null when not present.
     */
    fun extractFormatVersion(message: String?): Int? {
        if (message.isNullOrBlank()) return null
        return formatVersionRegex.find(message)?.groupValues?.get(1)?.toIntOrNull()
    }

    // -----------------------------------------------------------------------------------------
    // HostPrefs <-> app stores (sort: / note:space: / frozen: families)
    // -----------------------------------------------------------------------------------------

    /**
     * Builds the HostPrefs payload attached to a backup archive from the app's stores:
     * the global sort key ("AppListAll"), the per-space remarks ("Remark$userId") and the
     * per-app auto-freeze exclusion flags of the selected apps.
     */
    fun buildHostPrefs(
            sortRaw: String?,
            spaceRemarks: Map<Int, String>,
            frozenApps: List<AppSortEntry>,
    ): HostPrefs {
        val entries = LinkedHashMap<String, String>()
        AppsSortKeys.parseSortKeys(sortRaw ?: "").forEachIndexed { index, entry ->
            entries[sortKeyOf(entry.packageName, entry.userId)] = index.toString()
        }
        spaceRemarks.forEach { (userId, remark) ->
            if (remark.isNotBlank()) {
                entries["$KEY_NOTE_SPACE_PREFIX$userId"] = remark
            }
        }
        frozenApps.forEach { entry ->
            entries[frozenKeyOf(entry.packageName, entry.userId)] = FROZEN_VALUE_TRUE
        }
        return HostPrefs(entries)
    }

    /** Restores the global sort entries from a host_prefs map, ordered by their stored index. */
    fun parseRestoredSortEntries(entries: Map<String, String>): List<AppSortEntry> {
        return entries.mapNotNull { (key, value) ->
            if (!key.startsWith(HostPrefs.KEY_SORT_PREFIX)) return@mapNotNull null
            val parsed = AppsSortKeys.parseSortKey(key.removePrefix(HostPrefs.KEY_SORT_PREFIX))
                    ?: return@mapNotNull null
            parsed to (value.trim().toIntOrNull() ?: Int.MAX_VALUE)
        }
                .sortedBy { it.second }
                .map { it.first }
    }

    /** Restores the per-space remarks ("note:space:{userId}") from a host_prefs map. */
    fun parseRestoredSpaceRemarks(entries: Map<String, String>): Map<Int, String> {
        val out = LinkedHashMap<Int, String>()
        entries.forEach { (key, value) ->
            if (!key.startsWith(KEY_NOTE_SPACE_PREFIX)) return@forEach
            val userId = key.removePrefix(KEY_NOTE_SPACE_PREFIX).trim().toIntOrNull() ?: return@forEach
            if (value.isNotBlank()) {
                out[userId] = value
            }
        }
        return out
    }

    /** Restores the frozen (auto-freeze excluded) apps from a host_prefs map. */
    fun parseRestoredFrozenApps(entries: Map<String, String>): List<AppSortEntry> {
        return entries.mapNotNull { (key, value) ->
            if (value != FROZEN_VALUE_TRUE) return@mapNotNull null
            if (!key.startsWith(HostPrefs.KEY_FROZEN_PREFIX)) return@mapNotNull null
            AppsSortKeys.parseSortKey(key.removePrefix(HostPrefs.KEY_FROZEN_PREFIX))
        }
    }

    /**
     * Merges restored sort entries over the currently stored ones: restored entries come
     * first in backup order, then current entries that the backup does not mention (so apps
     * installed after the backup keep their positions at the end instead of disappearing).
     */
    fun mergeSortEntries(
            restored: List<AppSortEntry>,
            current: List<AppSortEntry>,
    ): List<AppSortEntry> {
        val seen = LinkedHashSet<AppSortEntry>()
        val out = ArrayList<AppSortEntry>()
        restored.forEach { if (seen.add(it)) out.add(it) }
        current.forEach { if (seen.add(it)) out.add(it) }
        return out
    }

    /**
     * Builds the source-user -> target-user mapping for a restore into one chosen space.
     * Null [targetUserId] means "restore into the spaces recorded in the backup" (no mapping).
     */
    fun buildTargetUserIdMapping(
            sourceUserIds: Collection<Int>,
            targetUserId: Int?,
    ): Map<Int, Int>? {
        if (targetUserId == null) return null
        val mapping = LinkedHashMap<Int, Int>()
        sourceUserIds.forEach { mapping[it] = targetUserId }
        return mapping
    }

    private fun sortKeyOf(packageName: String, userId: Int): String =
            HostPrefs.KEY_SORT_PREFIX + packageName + AppsSortKeys.USER_ID_SEPARATOR + userId

    private fun frozenKeyOf(packageName: String, userId: Int): String =
            HostPrefs.KEY_FROZEN_PREFIX + packageName + AppsSortKeys.USER_ID_SEPARATOR + userId

    // -----------------------------------------------------------------------------------------
    // Manifest preview helpers
    // -----------------------------------------------------------------------------------------

    /** Maps manifest apps to preview rows (pure projection, unit-testable). */
    fun previewRows(manifest: BackupManifest): List<RestorePreviewRow> {
        return manifest.apps.map { app ->
            RestorePreviewRow(
                    packageName = app.packageName,
                    userId = app.userId,
                    versionName = app.versionName,
                    sizeBytes = app.sizeBytes,
                    includeApk = app.includeApk,
            )
        }
    }

    /** Formats the ISO8601 manifest timestamp ("2026-11-22T12:00:00+0800") for display. */
    fun formatTimestamp(isoTimestamp: String?): String? {
        if (isoTimestamp.isNullOrBlank()) return null
        return try {
            val parsed = SimpleDateFormat(MANIFEST_TIMESTAMP_PATTERN, Locale.US).parse(isoTimestamp)
                    ?: return null
            SimpleDateFormat(DISPLAY_TIMESTAMP_PATTERN, Locale.US).format(parsed)
        } catch (e: Exception) {
            Log.w(TAG, "formatTimestamp failed for '$isoTimestamp': ${e.message}")
            null
        }
    }

    /** Human readable size, e.g. "0 B", "512 B", "1.5 KB", "2.0 MB". */
    fun formatSize(bytes: Long): String {
        if (bytes < 0) return formatSize(0)
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.1f GB", mb / 1024.0)
    }
}

/**
 * ViewModel backing BackupActivity: loads the aggregated (all-spaces) installed app list
 * for multi-select backup. The heavy lifting happens on Dispatchers.IO; failures surface
 * through [appsErrorLiveData] (never swallowed silently).
 */
class BackupViewModel : BaseViewModel() {

    companion object {
        private const val TAG = "BackupViewModel"
    }

    private val repository = AppsRepository()

    val appsLiveData = MutableLiveData<List<AppInfo>>()
    val appsErrorLiveData = MutableLiveData<String>()

    fun loadAllApps() {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    repository.getAllInstalledAppList(appsLiveData)
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadAllApps failed: ${e.message}")
                appsErrorLiveData.postValue(e.message ?: "")
                appsLiveData.postValue(emptyList())
            }
        }
    }
}
