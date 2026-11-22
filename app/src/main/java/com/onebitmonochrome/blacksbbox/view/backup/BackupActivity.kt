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
import android.util.Log
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.onebitmonochrome.blacksbbox.R
import com.onebitmonochrome.blacksbbox.app.AppFreezeManager
import com.onebitmonochrome.blacksbbox.app.AppManager
import com.onebitmonochrome.blacksbbox.data.AppsSortKeys
import com.onebitmonochrome.blacksbbox.data.AppSortEntry
import com.onebitmonochrome.blacksbbox.databinding.ActivityBackupBinding
import com.onebitmonochrome.blacksbbox.databinding.ViewBackupProgressBinding
import com.onebitmonochrome.blacksbbox.util.inflate
import com.onebitmonochrome.blacksbbox.util.toast
import com.onebitmonochrome.blacksbbox.view.apps.AppsAdapter
import com.onebitmonochrome.blacksbbox.view.base.LoadingActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.backup.BackupManager
import top.niunaijun.blackbox.backup.BackupRequest
import top.niunaijun.blackbox.backup.BackupResult
import top.niunaijun.blackbox.backup.HostPrefs
import top.niunaijun.blackbox.backup.OnBackupProgressListener
import java.io.IOException

/**
 * Backup UI.
 *
 * Two modes:
 *  - single-app mode: launched with [EXTRA_PKG]/[EXTRA_USER_ID]/[EXTRA_APP_NAME] (AppsFragment
 *    long-press menu). Shows the app info + options and packs exactly this app.
 *  - multi-select mode: launched without extras (MainActivity toolbar menu "Backup & Restore").
 *    Lists every installed app of every space with checkboxes and a bottom action bar.
 *
 * Output goes through Storage Access Framework (ACTION_CREATE_DOCUMENT, mime application/zip).
 * The engine call is blocking, so it runs on Dispatchers.IO; progress callbacks are already
 * posted to the main thread by BackupManager.
 */
class BackupActivity : LoadingActivity() {

    private val viewBinding: ActivityBackupBinding by inflate()

    private val viewModel: BackupViewModel by lazy {
        ViewModelProvider(this)[BackupViewModel::class.java]
    }

    private val backupAdapter = BackupAppAdapter()

    private var isSingleMode = false
    private var pkg: String? = null
    private var userId: Int = 0
    private var appName: String? = null
    private var isRunning = false

    private var progressDialog: AlertDialog? = null
    private var progressBinding: ViewBackupProgressBinding? = null

    private val createBackupDocument =
            registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
                try {
                    if (uri == null) {
                        Log.w(TAG, "User canceled the backup file picker")
                        toast(R.string.backup_canceled)
                    } else {
                        startBackup(uri)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling create-document result: ${e.message}")
                }
            }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(viewBinding.root)
            initToolbar(viewBinding.toolbarLayout.toolbar, R.string.backup_title, true)

            pkg = intent.getStringExtra(EXTRA_PKG)
            userId = intent.getIntExtra(EXTRA_USER_ID, 0)
            appName = intent.getStringExtra(EXTRA_APP_NAME)
            isSingleMode = !pkg.isNullOrBlank()

            applyMode()
            initViews()
            observeViewModel()

            if (isSingleMode) {
                Log.i(TAG, "Single-app backup mode: pkg=$pkg userId=$userId appName=$appName")
                bindSingleAppInfo()
            } else {
                Log.i(TAG, "Multi-select backup mode")
                showLoading()
                viewModel.loadAllApps()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in BackupActivity onCreate: ${e.message}")
            toast(getString(R.string.backup_error_generic, e.message ?: ""))
        }
    }

    private fun applyMode() {
        if (isSingleMode) {
            viewBinding.appInfoCard.visibility = View.VISIBLE
            viewBinding.startBackupButton.visibility = View.VISIBLE
            viewBinding.appsSection.visibility = View.GONE
            viewBinding.bottomBar.visibility = View.GONE
        } else {
            viewBinding.appInfoCard.visibility = View.GONE
            viewBinding.startBackupButton.visibility = View.GONE
            viewBinding.appsSection.visibility = View.VISIBLE
            viewBinding.bottomBar.visibility = View.VISIBLE
        }
    }

    private fun initViews() {
        try {
            viewBinding.appsRecycler.layoutManager = LinearLayoutManager(this)
            viewBinding.appsRecycler.adapter = backupAdapter

            backupAdapter.onSelectionChanged = { count ->
                try {
                    viewBinding.selectedCountText.text = getString(R.string.backup_selected_count, count)
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating selection count: ${e.message}")
                }
            }

            viewBinding.selectAllButton.setOnClickListener {
                try {
                    if (backupAdapter.itemCount > 0 &&
                            backupAdapter.selectedCount() >= backupAdapter.itemCount
                    ) {
                        backupAdapter.clearSelection()
                    } else {
                        backupAdapter.selectAll()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error toggling select all: ${e.message}")
                }
            }

            viewBinding.backupActionButton.setOnClickListener { onBackupClicked() }
            viewBinding.startBackupButton.setOnClickListener { onBackupClicked() }

            viewBinding.restoreEntryButton.setOnClickListener {
                try {
                    val intent = Intent(this, RestoreActivity::class.java)
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Error launching RestoreActivity: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in initViews: ${e.message}")
        }
    }

    private fun observeViewModel() {
        try {
            viewModel.appsLiveData.observe(this) { apps ->
                if (apps == null) return@observe
                hideLoading()
                backupAdapter.setItems(apps)
                val empty = apps.isEmpty()
                viewBinding.emptyText.visibility = if (empty) View.VISIBLE else View.GONE
                viewBinding.appsHeaderText.visibility = if (empty) View.GONE else View.VISIBLE
            }
            viewModel.appsErrorLiveData.observe(this) { message ->
                if (message == null) return@observe
                hideLoading()
                Log.e(TAG, "App list load failed: $message")
                toast(getString(R.string.backup_error_generic, message))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error observing ViewModel: ${e.message}")
        }
    }

    private fun bindSingleAppInfo() {
        try {
            val targetPkg = pkg.orEmpty()
            viewBinding.appNameText.text = appName?.takeIf { it.isNotBlank() } ?: targetPkg
            viewBinding.appPkgText.text = targetPkg
            viewBinding.appSpaceTagText.text = AppsAdapter.resolveUserTag(userId)
        } catch (e: Exception) {
            Log.e(TAG, "Error binding single app info: ${e.message}")
        }
    }

    // -----------------------------------------------------------------------------------------
    // Backup flow
    // -----------------------------------------------------------------------------------------

    private fun onBackupClicked() {
        try {
            if (isRunning) {
                Log.w(TAG, "Backup already running; ignoring duplicate click")
                return
            }
            val validation = BackupUiLogic.checkPasswordPair(
                    viewBinding.passwordInput.text?.toString(),
                    viewBinding.passwordConfirmInput.text?.toString(),
            )
            if (validation is PasswordValidation.Mismatch) {
                Log.w(TAG, "Password confirmation mismatch")
                viewBinding.passwordConfirmLayout.error = getString(R.string.backup_password_mismatch)
                toast(R.string.backup_password_mismatch)
                return
            }
            viewBinding.passwordConfirmLayout.error = null
            createBackupDocument.launch(BackupUiLogic.backupFileName(System.currentTimeMillis()))
        } catch (e: Exception) {
            Log.e(TAG, "Error preparing backup: ${e.message}")
            toast(getString(R.string.backup_error_generic, e.message ?: ""))
        }
    }

    private fun buildRequests(): List<BackupRequest> {
        val includeApk = viewBinding.includeApkSwitch.isChecked
        val excludeCache = viewBinding.excludeCacheSwitch.isChecked
        return if (isSingleMode) {
            val targetPkg = pkg.orEmpty()
            if (targetPkg.isBlank()) {
                emptyList()
            } else {
                listOf(BackupRequest(targetPkg, userId, includeApk, excludeCache))
            }
        } else {
            backupAdapter.selectedApps().map {
                BackupRequest(it.packageName, it.userId, includeApk, excludeCache)
            }
        }
    }

    private fun startBackup(uri: Uri) {
        try {
            if (isRunning) {
                Log.w(TAG, "Backup already running; dropping new request")
                return
            }
            val validation = BackupUiLogic.checkPasswordPair(
                    viewBinding.passwordInput.text?.toString(),
                    viewBinding.passwordConfirmInput.text?.toString(),
            )
            val passwordChars = when (validation) {
                is PasswordValidation.Mismatch -> {
                    Log.w(TAG, "Password mismatch at backup start")
                    toast(R.string.backup_password_mismatch)
                    return
                }
                is PasswordValidation.Valid -> validation.password
                PasswordValidation.None -> null
            }

            val requests = buildRequests()
            if (requests.isEmpty()) {
                Log.w(TAG, "No apps selected for backup")
                toast(R.string.backup_no_selection)
                return
            }

            isRunning = true
            Log.i(TAG, "Starting backup of ${requests.size} app(s) to $uri")
            showProgressDialog()

            lifecycleScope.launch {
                var result: BackupResult? = null
                var failure: Throwable? = null
                try {
                    val hostPrefs = withContext(Dispatchers.IO) { collectHostPrefs(requests) }
                    result = withContext(Dispatchers.IO) {
                        val output = contentResolver.openOutputStream(uri, "w")
                                ?: throw IOException(getString(R.string.backup_output_open_failed))
                        // Blocking call on purpose: BackupManager runs it on its own worker
                        // thread and posts progress callbacks back to the main thread.
                        BackupManager.get().backup(
                                this@BackupActivity,
                                requests,
                                output,
                                passwordChars,
                                hostPrefs,
                                backupProgressListener,
                        )
                    }
                } catch (e: Throwable) {
                    failure = e
                    Log.e(TAG, "Backup failed: ${e.message}")
                } finally {
                    passwordChars?.fill('\u0000')
                }

                if (isFinishing || isDestroyed) {
                    return@launch
                }
                dismissProgressDialog()
                isRunning = false
                when {
                    failure != null -> showErrorDialog(failure!!)
                    result != null -> showBackupResult(result!!)
                }
            }
        } catch (e: Exception) {
            isRunning = false
            dismissProgressDialog()
            Log.e(TAG, "Error starting backup: ${e.message}")
            toast(getString(R.string.backup_error_generic, e.message ?: ""))
        }
    }

    private val backupProgressListener = OnBackupProgressListener { current, total, stage ->
        try {
            updateProgressDialog(current, total, stage)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating backup progress: ${e.message}")
        }
    }

    /**
     * Collects the host-side preferences (global sort order, space remarks, per-app
     * auto-freeze exclusion flags) that ride along in the archive as host_prefs.json.
     * Must be called off the main thread (SharedPreferences/BlackBoxCore access).
     */
    private fun collectHostPrefs(
            requests: List<BackupRequest>,
    ): HostPrefs {
        val sortRaw = try {
            AppManager.mRemarkSharedPreferences.getString(AppsSortKeys.GLOBAL_SORT_KEY, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed reading global sort key: ${e.message}")
            null
        }
        val spaceRemarks = try {
            BlackBoxCore.get().users.associate { user ->
                user.id to (AppManager.mRemarkSharedPreferences
                        .getString("Remark${user.id}", null) ?: "")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed reading space remarks: ${e.message}")
            emptyMap()
        }
        val frozenApps = requests
                .filter { AppFreezeManager.isExcludedFromAutoFreeze(it.packageName, it.userId) }
                .map { AppSortEntry(it.packageName, it.userId) }
        Log.i(
                TAG,
                "collectHostPrefs: sortEntries=${sortRaw?.length ?: 0} chars, " +
                        "remarks=${spaceRemarks.size}, frozen=${frozenApps.size}",
        )
        return BackupUiLogic.buildHostPrefs(sortRaw, spaceRemarks, frozenApps)
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
            progressDialog = AlertDialog.Builder(this)
                    .setTitle(R.string.backup_progress_title)
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

    private fun showBackupResult(result: BackupResult) {
        try {
            val ok = result.succeeded.size
            val failed = result.failed
            val message = StringBuilder(getString(R.string.backup_done_summary, ok, failed.size))
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
            AlertDialog.Builder(this)
                    .setTitle(R.string.backup_done_title)
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
        } catch (e: Exception) {
            Log.e(TAG, "Error showing backup result: ${e.message}")
        }
    }

    private fun showErrorDialog(e: Throwable) {
        try {
            AlertDialog.Builder(this)
                    .setTitle(R.string.backup_title)
                    .setMessage(errorTextFor(e))
                    .setPositiveButton(R.string.done, null)
                    .show()
        } catch (dialogError: Exception) {
            Log.e(TAG, "Error showing error dialog: ${dialogError.message}")
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
        } catch (e: Exception) {
            Log.e(TAG, "Error in onDestroy: ${e.message}")
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BackupActivity"

        const val EXTRA_PKG = "pkg"
        const val EXTRA_USER_ID = "userId"
        const val EXTRA_APP_NAME = "appName"

        /**
         * Single-app backup entry point (AppsFragment long-press menu). Extras keys are kept
         * identical to the Phase 1 stub contract.
         */
        fun start(context: Context, pkg: String, userId: Int, appName: String?) {
            try {
                val intent = Intent(context, BackupActivity::class.java)
                intent.putExtra(EXTRA_PKG, pkg)
                intent.putExtra(EXTRA_USER_ID, userId)
                intent.putExtra(EXTRA_APP_NAME, appName)
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Error starting BackupActivity: ${e.message}")
            }
        }
    }
}
