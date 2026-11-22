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

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.input.input
import com.onebitmonochrome.blacksbbox.R
import com.onebitmonochrome.blacksbbox.app.AppFreezeManager
import com.onebitmonochrome.blacksbbox.app.AppManager
import com.onebitmonochrome.blacksbbox.data.AppsSortKeys
import com.onebitmonochrome.blacksbbox.databinding.ActivityRestoreBinding
import com.onebitmonochrome.blacksbbox.databinding.ViewBackupProgressBinding
import com.onebitmonochrome.blacksbbox.util.inflate
import com.onebitmonochrome.blacksbbox.util.toast
import com.onebitmonochrome.blacksbbox.view.base.LoadingActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.backup.BackupArchive
import top.niunaijun.blackbox.backup.BackupManager
import top.niunaijun.blackbox.backup.BackupManifestCodec
import top.niunaijun.blackbox.backup.BackupManifest
import top.niunaijun.blackbox.backup.HostPrefs
import top.niunaijun.blackbox.backup.OnRestoreProgressListener
import top.niunaijun.blackbox.backup.RestoreResult
import top.niunaijun.blackbox.backup.exception.WrongPasswordException
import java.io.File
import java.io.IOException

/**
 * Restore UI (file driven):
 *  1. ACTION_OPEN_DOCUMENT picks a .zip backup created by BackupActivity; the archive is
 *     copied into cacheDir (the engine needs a real File for its multi-pass reads).
 *  2. The plaintext manifest.json inside the archive is parsed for the preview (created
 *     time, source device, app list with versions/sizes, encryption flag). Parse failures
 *     surface explicitly (corrupted / format-version-too-new dialogs).
 *  3. Target space selection: "same spaces as in the backup" (default) or one concrete
 *     space (all apps map to it via targetUserIdMapping).
 *  4. Encrypted backups prompt for the password (max [BackupUiLogic.MAX_PASSWORD_ATTEMPTS]
 *     attempts; WrongPasswordException triggers the next prompt).
 *  5. Restore runs on Dispatchers.IO; the host_prefs.json from the archive is applied back
 *     to the app stores (sort order / space remarks / freeze flags).
 *
 * After finishing, the main app list refreshes automatically: AppsFragment reloads in
 * onStart, so no extra broadcast mechanism is needed here.
 */
class RestoreActivity : LoadingActivity() {

    private val viewBinding: ActivityRestoreBinding by inflate()

    private val previewAdapter = RestorePreviewAdapter()

    private var archiveFile: File? = null
    private var manifest: BackupManifest? = null
    private var sourceUserIds: List<Int> = emptyList()
    private var spaceIds: List<Int> = emptyList()
    private var passwordAttemptsUsed = 0
    private var pendingPassword: CharArray? = null
    private var isRunning = false

    private var progressDialog: AlertDialog? = null
    private var progressBinding: ViewBackupProgressBinding? = null

    private val openBackupDocument =
            registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                try {
                    if (uri == null) {
                        Log.w(TAG, "User canceled the restore file picker")
                    } else {
                        pickAndParse(uri)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling open-document result: ${e.message}")
                }
            }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(viewBinding.root)
            initToolbar(viewBinding.toolbarLayout.toolbar, R.string.restore_title, true)

            val routedPkg = intent.getStringExtra(EXTRA_PKG)
            val routedUserId = intent.getIntExtra(EXTRA_USER_ID, 0)
            val routedAppName = intent.getStringExtra(EXTRA_APP_NAME)
            Log.i(
                    TAG,
                    "RestoreActivity started: pkg=$routedPkg userId=$routedUserId " +
                            "appName=$routedAppName (restore is file driven; extras are informational)",
            )

            viewBinding.restoreAppsRecycler.layoutManager = LinearLayoutManager(this)
            viewBinding.restoreAppsRecycler.adapter = previewAdapter

            viewBinding.chooseFileButton.setOnClickListener {
                try {
                    openBackupDocument.launch(
                            arrayOf("application/zip", "application/octet-stream")
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error launching file picker: ${e.message}")
                    toast(getString(R.string.backup_error_generic, e.message ?: ""))
                }
            }

            viewBinding.startRestoreButton.setOnClickListener { onRestoreClicked() }
        } catch (e: Exception) {
            Log.e(TAG, "Error in RestoreActivity onCreate: ${e.message}")
            toast(getString(R.string.backup_error_generic, e.message ?: ""))
        }
    }

    // -----------------------------------------------------------------------------------------
    // Pick + preview
    // -----------------------------------------------------------------------------------------

    private fun pickAndParse(uri: Uri) {
        try {
            if (isRunning) {
                Log.w(TAG, "Restore already running; ignoring file pick")
                return
            }
            showLoading()
            lifecycleScope.launch {
                var parseError: Throwable? = null
                try {
                    val file = withContext(Dispatchers.IO) { copyToCache(uri) }
                    val parsed = withContext(Dispatchers.IO) { parseArchive(file) }
                    // Only keep the file once the manifest parsed: a stale manifest must
                    // never be paired with a different archive.
                    archiveFile = file
                    val spaces = withContext(Dispatchers.IO) { loadSpaces() }
                    withContext(Dispatchers.Main) {
                        hideLoading()
                        showPreview(parsed, spaces)
                    }
                } catch (e: Throwable) {
                    parseError = e
                    Log.e(TAG, "Restore preview failed: ${e.message}")
                }
                if (parseError != null) {
                    withContext(Dispatchers.Main) {
                        hideLoading()
                        handleGlobalFailure(parseError!!)
                    }
                }
            }
        } catch (e: Exception) {
            hideLoading()
            Log.e(TAG, "Error picking restore file: ${e.message}")
            toast(getString(R.string.backup_error_generic, e.message ?: ""))
        }
    }

    /** Copies the SAF uri content into cacheDir (engine reads need a real File). */
    private fun copyToCache(uri: Uri): File {
        val target = File(cacheDir, "blackbox_restore_${System.currentTimeMillis()}.zip")
        try {
            if (target.isFile) {
                target.delete()
            }
            val input = contentResolver.openInputStream(uri)
                    ?: throw IOException(getString(R.string.restore_file_read_failed))
            input.use { source ->
                target.outputStream().use { out ->
                    source.copyTo(out, BackupManager.COPY_BUFFER)
                }
            }
            return target
        } catch (e: Exception) {
            target.delete()
            Log.e(TAG, "Failed copying backup to cache: ${e.message}")
            throw e
        }
    }

    /**
     * Reads the manifest for the preview. manifest.json / manifest.sha256 stay plaintext in
     * the archive (even for encrypted backups), so no password is needed at this point.
     */
    private fun parseArchive(file: File): BackupManifest {
        val header = BackupArchive.readHeader(file)
        return BackupManifestCodec.deserialize(header.manifestBytes)
    }

    /** Loads the existing virtual spaces with display labels. */
    private fun loadSpaces(): List<Pair<Int, String>> {
        return try {
            BlackBoxCore.get().users.map { user ->
                val remark = try {
                    AppManager.mRemarkSharedPreferences
                            .getString("Remark${user.id}", null)
                            ?.takeIf { it.isNotBlank() }
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading remark for space ${user.id}: ${e.message}")
                    null
                }
                val fallback = user.name?.takeIf { it.isNotBlank() } ?: "User ${user.id}"
                user.id to (remark ?: fallback)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed loading spaces: ${e.message}")
            emptyList()
        }
    }

    private fun showPreview(parsed: BackupManifest, spaces: List<Pair<Int, String>>) {
        try {
            manifest = parsed
            sourceUserIds = parsed.apps.map { it.userId }.distinct()

            viewBinding.restoreCreatedAtText.text = getString(
                    R.string.restore_created_at,
                    BackupUiLogic.formatTimestamp(parsed.createdAt) ?: parsed.createdAt,
            )
            viewBinding.restoreDeviceText.text = getString(
                    R.string.restore_device,
                    parsed.device?.model ?: "?",
            )
            viewBinding.restoreEncryptedText.text =
                    getString(if (parsed.encrypted) R.string.restore_encrypted else R.string.restore_not_encrypted)
            viewBinding.restoreAppCountText.text =
                    getString(R.string.restore_app_count, parsed.apps.size)
            previewAdapter.setRows(BackupUiLogic.previewRows(parsed))

            initSpaceSpinner(spaces)

            viewBinding.introSection.visibility = View.GONE
            viewBinding.previewSection.visibility = View.VISIBLE
            Log.i(
                    TAG,
                    "Preview ready: ${parsed.apps.size} app(s), encrypted=${parsed.encrypted}, " +
                            "spaces=${spaces.size}",
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error showing preview: ${e.message}")
            toast(getString(R.string.backup_error_generic, e.message ?: ""))
        }
    }

    private fun initSpaceSpinner(spaces: List<Pair<Int, String>>) {
        try {
            val options = mutableListOf(getString(R.string.restore_target_as_backup))
            spaceIds = spaces.map { it.first }
            spaces.forEach { (_, label) -> options.add(label) }
            if (spaces.isEmpty()) {
                Log.w(TAG, "No virtual spaces available; only the default target is offered")
                toast(R.string.restore_users_unavailable)
            }
            val adapter = ArrayAdapter(
                    this,
                    android.R.layout.simple_spinner_item,
                    options,
            )
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            viewBinding.targetSpaceSpinner.adapter = adapter
            viewBinding.targetSpaceSpinner.setSelection(0)
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing space spinner: ${e.message}")
        }
    }

    // -----------------------------------------------------------------------------------------
    // Restore flow
    // -----------------------------------------------------------------------------------------

    private fun onRestoreClicked() {
        try {
            val currentManifest = manifest
            if (currentManifest == null || archiveFile == null) {
                Log.w(TAG, "Restore clicked without a parsed backup")
                toast(R.string.restore_file_read_failed)
                return
            }
            if (isRunning) {
                Log.w(TAG, "Restore already running; ignoring duplicate click")
                return
            }
            val mapping = selectedMapping()
            Log.i(TAG, "Restore requested: mapping=$mapping encrypted=${currentManifest.encrypted}")
            if (currentManifest.encrypted) {
                showPasswordDialog()
            } else {
                runRestore(null, mapping)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error preparing restore: ${e.message}")
            toast(getString(R.string.backup_error_generic, e.message ?: ""))
        }
    }

    /** Null mapping = "restore into the spaces recorded in the backup". */
    private fun selectedMapping(): Map<Int, Int>? {
        return try {
            val position = viewBinding.targetSpaceSpinner.selectedItemPosition
            if (position <= 0) {
                null
            } else {
                val targetUserId = spaceIds.getOrNull(position - 1) ?: return null
                BackupUiLogic.buildTargetUserIdMapping(sourceUserIds, targetUserId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving target mapping: ${e.message}")
            null
        }
    }

    private fun showPasswordDialog() {
        try {
            val attempt = passwordAttemptsUsed + 1
            pendingPassword = null
            MaterialDialog(this).show {
                title(res = R.string.restore_password_title)
                message(
                        text = getString(
                                R.string.restore_password_message,
                                attempt,
                                BackupUiLogic.MAX_PASSWORD_ATTEMPTS,
                        )
                )
                input(
                        hintRes = R.string.restore_password_title,
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                ) { _, text ->
                    pendingPassword = text.toString().toCharArray()
                }
                positiveButton(res = R.string.done) {
                    try {
                        val mapping = selectedMapping()
                        val password = pendingPassword
                        runRestore(password, mapping)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error starting restore after password: ${e.message}")
                    }
                }
                negativeButton(res = R.string.cancel) {
                    Log.w(TAG, "Password dialog dismissed by user")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing password dialog: ${e.message}")
            toast(getString(R.string.backup_error_generic, e.message ?: ""))
        }
    }

    private fun runRestore(password: CharArray?, mapping: Map<Int, Int>?) {
        try {
            val source = archiveFile
            if (source == null || !source.isFile) {
                Log.e(TAG, "Restore aborted: archive file is missing")
                toast(R.string.restore_file_read_failed)
                return
            }
            if (isRunning) {
                Log.w(TAG, "Restore already running; dropping new request")
                return
            }
            isRunning = true
            showProgressDialog()

            val hostPrefsTarget = File(cacheDir, "blackbox_host_prefs_restore.json")
            try {
                if (hostPrefsTarget.isFile) {
                    hostPrefsTarget.delete()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not delete stale host prefs target: ${e.message}")
            }

            lifecycleScope.launch {
                var result: RestoreResult? = null
                var failure: Throwable? = null
                try {
                    result = withContext(Dispatchers.IO) {
                        // Blocking call on purpose: BackupManager runs it on its own worker
                        // thread and posts progress callbacks back to the main thread.
                        BackupManager.get().restore(
                                this@RestoreActivity,
                                source,
                                password,
                                mapping,
                                hostPrefsTarget,
                                restoreProgressListener,
                        )
                    }
                } catch (e: Throwable) {
                    failure = e
                    Log.e(TAG, "Restore failed: ${e.message}")
                } finally {
                    password?.fill('\u0000')
                    pendingPassword = null
                }

                if (isFinishing || isDestroyed) {
                    return@launch
                }

                if (failure is WrongPasswordException) {
                    dismissProgressDialog()
                    isRunning = false
                    passwordAttemptsUsed++
                    if (passwordAttemptsUsed >= BackupUiLogic.MAX_PASSWORD_ATTEMPTS) {
                        Log.e(TAG, "Restore aborted: too many wrong password attempts")
                        AlertDialog.Builder(this@RestoreActivity)
                                .setTitle(R.string.restore_password_title)
                                .setMessage(R.string.restore_password_attempts_exhausted)
                                .setPositiveButton(R.string.done, null)
                                .show()
                    } else {
                        Log.w(TAG, "Wrong password; attempt ${passwordAttemptsUsed + 1}")
                        showPasswordDialog()
                    }
                    return@launch
                }

                dismissProgressDialog()
                isRunning = false
                when {
                    failure != null -> handleGlobalFailure(failure!!)
                    result != null -> {
                        var prefsApplied = 0
                        var prefsError: String? = null
                        try {
                            prefsApplied = withContext(Dispatchers.IO) {
                                applyHostPrefs(hostPrefsTarget)
                            }
                        } catch (e: Exception) {
                            prefsError = e.message ?: ""
                            Log.e(TAG, "Applying host prefs failed: ${e.message}")
                        }
                        showRestoreResult(result!!, prefsApplied, prefsError)
                    }
                }
            }
        } catch (e: Exception) {
            isRunning = false
            dismissProgressDialog()
            Log.e(TAG, "Error starting restore: ${e.message}")
            toast(getString(R.string.backup_error_generic, e.message ?: ""))
        }
    }

    private val restoreProgressListener = OnRestoreProgressListener { current, total, stage ->
        try {
            updateProgressDialog(current, total, stage)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating restore progress: ${e.message}")
        }
    }

    /**
     * Applies the host_prefs.json written by the engine (via the hostPrefsTarget overload)
     * back to the app stores:
     *  - "sort:{pkg}@{userId}" entries -> global sort key "AppListAll"
     *  - "note:space:{userId}" entries -> "Remark$userId" space remarks
     *  - "frozen:{pkg}@{userId}" entries -> AppFreezeManager auto-freeze exclusions
     * Returns the number of applied entries, 0 when the archive had no prefs, -1 on failure.
     */
    private fun applyHostPrefs(hostPrefsTarget: File): Int {
        try {
            if (!hostPrefsTarget.isFile) {
                Log.i(TAG, "Archive carries no host_prefs.json; nothing to apply")
                return 0
            }
            val prefs = HostPrefs.deserialize(hostPrefsTarget.readBytes())
            val restoredSort = BackupUiLogic.parseRestoredSortEntries(prefs.entries)
            val restoredRemarks = BackupUiLogic.parseRestoredSpaceRemarks(prefs.entries)
            val restoredFrozen = BackupUiLogic.parseRestoredFrozenApps(prefs.entries)
            if (restoredSort.isEmpty() && restoredRemarks.isEmpty() && restoredFrozen.isEmpty()) {
                Log.i(TAG, "host_prefs.json has no known keys; nothing to apply")
                return 0
            }

            val sp = AppManager.mRemarkSharedPreferences
            if (restoredSort.isNotEmpty()) {
                val currentRaw = try {
                    sp.getString(AppsSortKeys.GLOBAL_SORT_KEY, null)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed reading current sort key: ${e.message}")
                    null
                }
                val merged = BackupUiLogic.mergeSortEntries(
                        restoredSort,
                        AppsSortKeys.parseSortKeys(currentRaw ?: ""),
                )
                try {
                    sp.edit()
                            .putString(
                                    AppsSortKeys.GLOBAL_SORT_KEY,
                                    AppsSortKeys.serializeSortKeys(merged),
                            )
                            .apply()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed writing restored sort key: ${e.message}")
                    return -1
                }
            }

            restoredRemarks.forEach { (spaceUserId, remark) ->
                try {
                    sp.edit().putString("Remark$spaceUserId", remark).apply()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed writing remark for space $spaceUserId: ${e.message}")
                }
            }

            restoredFrozen.forEach { entry ->
                try {
                    AppFreezeManager.setExcludedFromAutoFreeze(entry.packageName, entry.userId, true)
                } catch (e: Exception) {
                    Log.e(
                            TAG,
                            "Failed restoring freeze flag for ${entry.packageName}@${entry.userId}: ${e.message}",
                    )
                }
            }

            try {
                hostPrefsTarget.delete()
            } catch (e: Exception) {
                Log.w(TAG, "Could not delete host prefs target: ${e.message}")
            }

            val applied = restoredSort.size + restoredRemarks.size + restoredFrozen.size
            Log.i(TAG, "Applied $applied host prefs entry(s)")
            return applied
        } catch (e: Exception) {
            Log.e(TAG, "applyHostPrefs failed: ${e.message}")
            return -1
        }
    }

    // -----------------------------------------------------------------------------------------
    // Dialogs
    // -----------------------------------------------------------------------------------------

    private fun showProgressDialog() {
        try {
            dismissProgressDialog()
            val binding = ViewBackupProgressBinding.inflate(layoutInflater)
            progressBinding = binding
            binding.progressBar.isIndeterminate = true
            binding.progressNoteText.text = getString(R.string.restore_progress_cannot_cancel)
            progressDialog = AlertDialog.Builder(this)
                    .setTitle(R.string.restore_progress_title)
                    .setView(binding.root)
                    .setCancelable(false)
                    .show()
        } catch (e: Exception) {
            Log.e(TAG, "Error showing progress dialog: ${e.message}")
        }
    }

    private fun updateProgressDialog(current: Int, total: Int, stage: String) {
        if (isFinishing || isDestroyed) return
        val binding = progressBinding ?: return
        try {
            binding.progressBar.isIndeterminate = false
            binding.progressBar.max = if (total > 0) total else 100
            binding.progressBar.progress = current.coerceIn(0, binding.progressBar.max)
            binding.progressCountText.text = "$current / ${if (total > 0) total else "?"}"
            binding.progressStageText.text = stageText(stage)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating progress views: ${e.message}")
        }
    }

    private fun dismissProgressDialog() {
        try {
            progressDialog?.dismiss()
        } catch (e: Exception) {
            Log.e(TAG, "Error dismissing progress dialog: ${e.message}")
        } finally {
            progressDialog = null
            progressBinding = null
        }
    }

    private fun stageText(stage: String): String {
        val res = BackupUiLogic.stageToLabelRes(stage)
        val arg = BackupUiLogic.stageArg(stage)
        return if (arg != null) getString(res, arg) else getString(res)
    }

    private fun showRestoreResult(result: RestoreResult, prefsApplied: Int, prefsError: String?) {
        try {
            val ok = result.succeeded.size
            val failed = result.failed
            val message = StringBuilder(getString(R.string.backup_done_summary, ok, failed.size))
            result.succeeded.forEach { restored ->
                if (restored.signatureWarning) {
                    message.append("\n")
                    message.append(restored.packageName)
                    message.append("@")
                    message.append(restored.userId)
                    message.append(": ")
                    message.append(getString(R.string.restore_signature_warning))
                }
            }
            if (failed.isNotEmpty()) {
                message.append("\n")
                failed.forEach { item ->
                    message.append("\n")
                    message.append(item.packageName)
                    message.append("@")
                    message.append(item.userId)
                    message.append(": ")
                    message.append(getString(BackupUiLogic.failureReasonRes(item.reason)))
                    item.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                        message.append(" (")
                        message.append(detail)
                        message.append(")")
                    }
                }
            }
            if (prefsApplied > 0) {
                message.append("\n\n")
                message.append(getString(R.string.restore_prefs_applied))
            } else if (prefsError != null) {
                message.append("\n\n")
                message.append(getString(R.string.restore_prefs_apply_failed, prefsError))
            }

            AlertDialog.Builder(this)
                    .setTitle(R.string.restore_done_title)
                    .setMessage(message.toString())
                    .setPositiveButton(R.string.done, null)
                    .show()
            toast(
                    if (failed.isEmpty()) {
                        getString(R.string.backup_all_success, ok)
                    } else {
                        getString(R.string.backup_done_summary, ok, failed.size)
                    }
            )
            Log.i(TAG, "Restore finished: ok=$ok failed=${failed.size} prefsApplied=$prefsApplied")
        } catch (e: Exception) {
            Log.e(TAG, "Error showing restore result: ${e.message}")
        }
    }

    private fun handleGlobalFailure(e: Throwable) {
        Log.e(TAG, "Global restore failure: ${e.message}")
        try {
            AlertDialog.Builder(this)
                    .setTitle(R.string.restore_title)
                    .setMessage(errorTextFor(e))
                    .setPositiveButton(R.string.done, null)
                    .show()
        } catch (dialogError: Exception) {
            Log.e(TAG, "Error showing failure dialog: ${dialogError.message}")
            toast(errorTextFor(e))
        }
    }

    private fun errorTextFor(e: Throwable): String {
        return when (BackupUiLogic.errorResFor(e)) {
            R.string.backup_error_unsupported_format ->
                getString(
                        R.string.backup_error_unsupported_format,
                        BackupUiLogic.extractFormatVersion(e.message) ?: -1,
                )
            R.string.backup_error_generic ->
                getString(R.string.backup_error_generic, e.message ?: "")
            else -> getString(BackupUiLogic.errorResFor(e))
        }
    }

    override fun onDestroy() {
        try {
            dismissProgressDialog()
            archiveFile?.let { file ->
                if (file.isFile) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onDestroy: ${e.message}")
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RestoreActivity"

        const val EXTRA_PKG = "pkg"
        const val EXTRA_USER_ID = "userId"
        const val EXTRA_APP_NAME = "appName"

        /**
         * Kept for the Phase 1 stub contract (extras keys unchanged). Restore itself is file
         * driven; the extras are informational only and may be used by later phases (e.g. the
         * gallery/remote-import flow) to preselect context.
         */
        fun start(context: Context, pkg: String, userId: Int, appName: String?) {
            try {
                val intent = Intent(context, RestoreActivity::class.java)
                intent.putExtra(EXTRA_PKG, pkg)
                intent.putExtra(EXTRA_USER_ID, userId)
                intent.putExtra(EXTRA_APP_NAME, appName)
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Error starting RestoreActivity: ${e.message}")
            }
        }
    }
}
