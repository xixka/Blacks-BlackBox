package com.onebitmonochrome.blacksbbox.view.main

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.afollestad.materialdialogs.MaterialDialog
import top.niunaijun.blackbox.BlackBoxCore
import com.onebitmonochrome.blacksbbox.R
import com.onebitmonochrome.blacksbbox.app.App
import com.onebitmonochrome.blacksbbox.app.AppFreezeManager
import com.onebitmonochrome.blacksbbox.app.AppManager
import com.onebitmonochrome.blacksbbox.databinding.ActivityMainBinding
import com.onebitmonochrome.blacksbbox.util.Resolution
import com.onebitmonochrome.blacksbbox.util.inflate
import com.onebitmonochrome.blacksbbox.util.toast
import com.onebitmonochrome.blacksbbox.view.apps.AppsFragment
import com.onebitmonochrome.blacksbbox.view.backup.BackupActivity
import com.onebitmonochrome.blacksbbox.view.base.LoadingActivity
import com.onebitmonochrome.blacksbbox.view.fake.FakeManagerActivity
import com.onebitmonochrome.blacksbbox.view.gallery.BlackBoxGalleryActivity
import com.onebitmonochrome.blacksbbox.view.list.ListActivity
import com.onebitmonochrome.blacksbbox.view.spoof.DeviceSpoofingActivity
import com.onebitmonochrome.blacksbbox.view.setting.SettingActivity

class MainActivity : LoadingActivity() {

    private val viewBinding: ActivityMainBinding by inflate()

    companion object {
        private const val TAG = "MainActivity"
        private const val VPN_PERMISSION_REQUEST_CODE = 1002

        fun start(context: Context) {
            val intent = Intent(context, MainActivity::class.java)
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)

            try {
                BlackBoxCore.get().onBeforeMainActivityOnCreate(this)
            } catch (e: Exception) {
                Log.e(TAG, "Error in onBeforeMainActivityOnCreate: ${e.message}")
            }

            setContentView(viewBinding.root)
            initToolbar(viewBinding.toolbarLayout.toolbar, R.string.app_name)
            initView()
            initFab()
            initFreezeFab()

            // Storage permission flow removed: the engine stores everything in the
            // app-specific external dir (BEnvironment uses getExternalFilesDir) and every
            // user-file entry point (APK pick, backup/restore, gallery import) is SAF or
            // content:// based, so no runtime storage permission is ever needed.

            checkVpnPermission()

            try {
                BlackBoxCore.get().onAfterMainActivityOnCreate(this)
            } catch (e: Exception) {
                Log.e(TAG, "Error in onAfterMainActivityOnCreate: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Critical error in onCreate: ${e.message}")

            showErrorDialog(getString(R.string.error_init_failed, e.message))
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            updateFreezeFabState()
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing freeze FAB state: ${e.message}")
        }
    }

    private fun checkVpnPermission() {
        try {
            val vpnEnabled = try {
                AppManager.mBlackBoxLoader.useVpnNetwork()
            } catch (e: Exception) {
                Log.e(TAG, "Error reading VPN setting: ${e.message}")
                false
            }

            if (android.os.Build.VERSION.SDK_INT >= 34) {
                if (vpnEnabled) {
                    Log.w(TAG, "VPN setting enabled on SDK>=34; forcing disable (unsupported)")
                    try {
                        AppManager.mBlackBoxLoader.invalidUseVpnNetwork(false)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error disabling VPN setting: ${e.message}")
                    }
                }
                return
            }

            if (!vpnEnabled) {
                Log.d(TAG, "VPN setting disabled; skipping VPN permission")
                return
            }

            val vpnIntent = VpnService.prepare(this)
            if (vpnIntent != null) {
                // Per UX requirement: only prompt for VPN permission when user explicitly
                // enables the toggle in Settings. If permission is missing at startup,
                // disable the toggle and let the user re-enable it to trigger the prompt.
                Log.w(TAG, "VPN setting enabled but permission missing; disabling setting")
                try {
                    AppManager.mBlackBoxLoader.invalidUseVpnNetwork(false)
                } catch (e: Exception) {
                    Log.e(TAG, "Error disabling VPN setting: ${e.message}")
                }
            } else {

                Log.d(TAG, "VPN permission already granted")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking VPN permission: ${e.message}")
        }
    }

    private val vpnPermissionResult =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                try {
                    if (result.resultCode == RESULT_OK) {
                        Log.d(TAG, "VPN permission granted!")

                    } else {
                        Log.w(TAG, "VPN permission denied by user")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling VPN permission result: ${e.message}")
                }
            }

    private fun showErrorDialog(message: String) {
        try {
            MaterialDialog(this).show {
                title(text = getString(R.string.error_title))
                message(text = message)
                positiveButton(text = getString(R.string.error_ok)) { finish() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing error dialog: ${e.message}")
            finish()
        }
    }

    private fun initView() {
        try {
            val fragment = AppsFragment.newInstance()
            supportFragmentManager
                    .beginTransaction()
                    .replace(R.id.fragment_container, fragment)
                    .commit()
        } catch (e: Exception) {
            Log.e(TAG, "Error in initView: ${e.message}")
        }
    }

    private fun initFab() {
        try {
            viewBinding.fab.setOnClickListener {
                try {
                    // No single "current" space anymore: ListActivity picks the last used
                    // space (or the first one) and lets the user switch via its selector.
                    val intent = Intent(this, ListActivity::class.java)
                    apkPathResult.launch(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Error launching ListActivity: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in initFab: ${e.message}")
        }
    }

    private fun initFreezeFab() {
        try {
            updateFreezeFabState()
            viewBinding.freezeFab.setOnClickListener {
                try {
                    if (AppFreezeManager.isGlobalAutoFreezeEnabled()) {
                        AppFreezeManager.setGlobalAutoFreezeEnabled(false)
                        updateFreezeFabState()
                        toast(R.string.auto_freeze_master_disabled)
                    } else {
                        AlertDialog.Builder(this)
                                .setTitle(R.string.auto_freeze_toggle_title)
                                .setMessage(R.string.auto_freeze_toggle_message)
                                .setPositiveButton(R.string.enable) { _, _ ->
                                    AppFreezeManager.setGlobalAutoFreezeEnabled(true)
                                    updateFreezeFabState()
                                    toast(R.string.auto_freeze_master_enabled)
                                }
                                .setNegativeButton(R.string.cancel, null)
                                .show()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error toggling auto-freeze: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in initFreezeFab: ${e.message}")
        }
    }

    private fun updateFreezeFabState() {
        val enabled = AppFreezeManager.isGlobalAutoFreezeEnabled()
        viewBinding.freezeFab.setImageResource(
                if (enabled) R.drawable.ic_snowflake else R.drawable.ic_snowflake_off
        )
        viewBinding.freezeFab.imageTintList = null
        viewBinding.freezeFab.backgroundTintList =
                ColorStateList.valueOf(
                        ContextCompat.getColor(
                                this,
                                if (enabled) {
                                    R.color.fab_tint
                                } else {
                                    R.color.surface_variant
                                }
                        )
                )
        viewBinding.freezeFab.contentDescription =
                getString(
                        if (enabled) {
                            R.string.auto_freeze_toggle_on
                        } else {
                            R.string.auto_freeze_toggle_off
                        }
                )
    }

    fun showFloatButton(show: Boolean) {
        try {
            val tranY: Float = Resolution.convertDpToPixel(120F, App.getContext())
            val time = 200L
            if (show) {
                viewBinding.fab.animate().translationY(0f).alpha(1f).setDuration(time).start()
                viewBinding.freezeFab
                        .animate()
                        .translationY(0f)
                        .alpha(1f)
                        .setDuration(time)
                        .start()
            } else {
                viewBinding.fab.animate().translationY(tranY).alpha(0f).setDuration(time).start()
                viewBinding.freezeFab
                        .animate()
                        .translationY(tranY)
                        .alpha(0f)
                        .setDuration(time)
                        .start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in showFloatButton: ${e.message}")
        }
    }

    /**
     * Shows a dialog listing every virtual space (with its remark) and invokes [onSelected]
     * with the chosen space id. Used for menu entries that used to rely on a single
     * "current user" and now need an explicit space choice.
     */
    private fun withSelectedSpace(onSelected: (Int) -> Unit) {
        try {
            val users = BlackBoxCore.get().users
            if (users.isEmpty()) {
                Log.w(TAG, "withSelectedSpace: no virtual spaces available")
                toast(R.string.choose_space)
                return
            }

            val labels = users.map { user ->
                try {
                    AppManager.mRemarkSharedPreferences
                            .getString("Remark${user.id}", null)
                            ?.takeIf { it.isNotBlank() }
                            ?: getString(R.string.default_user_name, user.id)
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading remark for space ${user.id}: ${e.message}")
                    getString(R.string.default_user_name, user.id)
                }
            }.toTypedArray()

            AlertDialog.Builder(this)
                    .setTitle(R.string.choose_space)
                    .setItems(labels) { _, which ->
                        try {
                            users.getOrNull(which)?.let { onSelected(it.id) }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error handling selected space: ${e.message}")
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
        } catch (e: Exception) {
            Log.e(TAG, "Error showing space selection dialog: ${e.message}")
        }
    }

    private val apkPathResult =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                try {
                    if (it.resultCode == RESULT_OK) {
                        it.data?.let { data ->
                            val userId = data.getIntExtra("userID", 0)
                            val source = data.getStringExtra("source")
                            if (source != null) {
                                val targetFragment =
                                        supportFragmentManager.findFragmentById(R.id.fragment_container) as? AppsFragment
                                if (targetFragment != null) {
                                    targetFragment.installApk(source, userId)
                                } else {
                                    Log.w(TAG, "AppsFragment not found; dropping install result")
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling APK path result: ${e.message}")
                }
            }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        try {
            menuInflater.inflate(R.menu.menu_main, menu)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error creating options menu: ${e.message}")
            return false
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        try {
            when (item.itemId) {
                R.id.main_setting -> {
                    SettingActivity.start(this)
                }
                R.id.fake_location -> {
                    withSelectedSpace { userId ->
                        try {
                            val intent = Intent(this, FakeManagerActivity::class.java)
                            intent.putExtra("userID", userId)
                            startActivity(intent)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error launching FakeManagerActivity: ${e.message}")
                        }
                    }
                }
                R.id.device_spoofing -> {
                    withSelectedSpace { userId ->
                        try {
                            DeviceSpoofingActivity.start(this, userId)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error launching DeviceSpoofingActivity: ${e.message}")
                        }
                    }
                }
                R.id.blackbox_gallery -> {
                    withSelectedSpace { userId ->
                        try {
                            BlackBoxGalleryActivity.start(this, userId)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error launching BlackBoxGalleryActivity: ${e.message}")
                        }
                    }
                }
                R.id.backup_restore -> {
                    try {
                        val intent = Intent(this, BackupActivity::class.java)
                        startActivity(intent)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error launching BackupActivity: ${e.message}")
                    }
                }
            }

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error handling menu item selection: ${e.message}")
            return false
        }
    }
}
