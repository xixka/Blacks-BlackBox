package com.onebitmonochrome.blacksbbox.view.list

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.view.inputmethod.InputMethodManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import cbfg.rvadapter.RVAdapter
import com.ferfalk.simplesearchview.SimpleSearchView
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.core.system.user.BUserInfo
import com.onebitmonochrome.blacksbbox.R
import com.onebitmonochrome.blacksbbox.app.AppManager
import com.onebitmonochrome.blacksbbox.bean.InstalledAppBean
import com.onebitmonochrome.blacksbbox.databinding.ActivityListBinding
import com.onebitmonochrome.blacksbbox.util.InjectionUtil
import com.onebitmonochrome.blacksbbox.util.inflate
import com.onebitmonochrome.blacksbbox.view.base.BaseActivity

class ListActivity : BaseActivity() {

    private val viewBinding: ActivityListBinding by inflate()

    private lateinit var mAdapter: RVAdapter<InstalledAppBean>

    private lateinit var viewModel: ListViewModel

    private var appList: List<InstalledAppBean> = ArrayList()

    private var currentUserId: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(viewBinding.root)

        val users = try {
            BlackBoxCore.get().users
        } catch (e: Exception) {
            Log.e(TAG, "Error loading virtual spaces: ${e.message}")
            emptyList<BUserInfo>()
        }
        currentUserId = resolveInitialUserId(users)

        initToolbar(viewBinding.toolbarLayout.toolbar, R.string.installed_app, true)

        mAdapter =
                RVAdapter<InstalledAppBean>(this, ListAdapter())
                        .bind(viewBinding.recyclerView)
                        .setItemClickListener { _, item, _ -> finishWithResult(item.packageName) }

        viewBinding.recyclerView.layoutManager = LinearLayoutManager(this)

        initSearchView()
        initSpaceSelector(users)
        initViewModel()
    }

    private fun resolveInitialUserId(users: List<BUserInfo>): Int {
        return try {
            val requested = intent.getIntExtra("userID", -1)
            if (requested >= 0 && users.any { it.id == requested }) {
                requested
            } else {
                val lastUsed = AppManager.mRemarkSharedPreferences
                        .getInt(LAST_USED_USER_ID_KEY, -1)
                if (lastUsed >= 0 && users.any { it.id == lastUsed }) {
                    Log.d(TAG, "Using last used space: userId=$lastUsed")
                    lastUsed
                } else {
                    users.firstOrNull()?.id ?: 0
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving initial space: ${e.message}")
            users.firstOrNull()?.id ?: 0
        }
    }

    /**
     * Adds a space switcher below the toolbar when more than one virtual space exists.
     * The layout XML is untouched: the Spinner is inserted programmatically into the root
     * LinearLayout at index 1 (right under the toolbar/search area).
     */
    private fun initSpaceSelector(users: List<BUserInfo>) {
        if (users.size <= 1) {
            Log.d(TAG, "Single virtual space; hiding space selector")
            return
        }
        try {
            val root = viewBinding.root as? LinearLayout
            if (root == null) {
                Log.w(TAG, "Root view is not a LinearLayout; skipping space selector")
                return
            }

            val labels = users.map { user ->
                try {
                    AppManager.mRemarkSharedPreferences
                            .getString("Remark${user.id}", null)
                            ?.takeIf { it.isNotBlank() }
                            ?: "User ${user.id}"
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading remark for space ${user.id}: ${e.message}")
                    "User ${user.id}"
                }
            }

            val spinner = Spinner(this)
            val spinnerAdapter =
                    ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
            spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinner.adapter = spinnerAdapter

            val selectedIndex = users.indexOfFirst { it.id == currentUserId }
            if (selectedIndex >= 0) {
                spinner.setSelection(selectedIndex, false)
            }

            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                        parent: AdapterView<*>?,
                        view: View?,
                        position: Int,
                        id: Long
                ) {
                    try {
                        val selected = users.getOrNull(position) ?: return
                        if (selected.id == currentUserId) {
                            return
                        }
                        Log.d(TAG, "Switching install space: userId=${selected.id}")
                        currentUserId = selected.id
                        saveLastUsedUserId(selected.id)
                        viewModel.getInstallAppList(currentUserId)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error switching space: ${e.message}")
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {
                }
            }

            val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            )
            val margin = (8 * resources.displayMetrics.density).toInt()
            lp.setMargins(margin, margin, margin, margin)
            root.addView(spinner, 1, lp)
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing space selector: ${e.message}")
        }
    }

    private fun saveLastUsedUserId(userId: Int) {
        try {
            AppManager.mRemarkSharedPreferences.edit().putInt(LAST_USED_USER_ID_KEY, userId).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving last used space: ${e.message}")
        }
    }

    private fun initSearchView() {
        viewBinding.searchView.setOnQueryTextListener(
                object : SimpleSearchView.OnQueryTextListener {
                    override fun onQueryTextChange(newText: String): Boolean {
                        filterApp(newText)
                        return true
                    }

                    override fun onQueryTextCleared(): Boolean {
                        return true
                    }

                    override fun onQueryTextSubmit(query: String): Boolean {
                        return true
                    }
                }
        )
    }

    private fun initViewModel() {
        viewModel =
                ViewModelProvider(this, InjectionUtil.getListFactory())
                        .get(ListViewModel::class.java)
        viewModel.getInstallAppList(currentUserId)
        saveLastUsedUserId(currentUserId)
        viewBinding.toolbarLayout.toolbar.setTitle(R.string.installed_app)

        viewModel.loadingLiveData.observe(this) {
            if (it) {
                viewBinding.stateView.showLoading()
            } else {
                viewBinding.stateView.showContent()
            }
        }

        viewModel.appsLiveData.observe(this) {
            if (it != null) {
                this.appList = it
                viewBinding.searchView.setQuery("", false)
                filterApp("")
                if (it.isNotEmpty()) {
                    viewBinding.stateView.showContent()
                    viewModel.previewInstalledList()
                } else {
                    viewBinding.stateView.showEmpty()
                }
            }
        }
    }

    private fun filterApp(newText: String) {
        val newList =
                this.appList.filter {
                    it.name.contains(newText, true) or it.packageName.contains(newText, true)
                }
        mAdapter.setItems(newList)
    }

    private val openDocumentedResult =
            registerForActivityResult(ActivityResultContracts.GetContent()) {
                it?.run {
                    val raw = it.toString()
                    val fixed =
                            when {
                                raw.startsWith("/content:/") -> "content://" + raw.removePrefix("/content:/")
                                raw.startsWith("content:/") && !raw.startsWith("content://") -> "content://" + raw.removePrefix("content:/")
                                raw.startsWith("/file:/") -> "file://" + raw.removePrefix("/file:/")
                                raw.startsWith("file:/") && !raw.startsWith("file://") -> "file://" + raw.removePrefix("/file:/")
                                else -> raw
                            }
                    finishWithResult(fixed)
                }
            }

    private fun finishWithResult(source: String) {
        // Always return the space the user selected in this activity, so MainActivity can
        // route the install result to the right space even without an original "userID" extra.
        intent.putExtra("userID", currentUserId)
        intent.putExtra("source", source)
        setResult(Activity.RESULT_OK, intent)
        val imm: InputMethodManager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        window.peekDecorView()?.run { imm.hideSoftInputFromWindow(windowToken, 0) }
        finish()
    }

    override fun onBackPressed() {
        if (viewBinding.searchView.isSearchOpen) {
            viewBinding.searchView.closeSearch()
        } else {
            super.onBackPressed()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.list_choose) {
            // Allow .apk and bundle-based installers (.apks) to be selectable. We validate at install time.
            openDocumentedResult.launch("*/*")
        }
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_list, menu)
        val item = menu!!.findItem(R.id.list_search)
        viewBinding.searchView.setMenuItem(item)

        return true
    }

    override fun onStop() {
        super.onStop()
        viewModel.loadingLiveData.postValue(true)
        viewModel.loadingLiveData.removeObservers(this)
        viewModel.appsLiveData.postValue(null)
        viewModel.appsLiveData.removeObservers(this)
    }

    companion object {
        private const val TAG = "ListActivity"
        private const val LAST_USED_USER_ID_KEY = "LastUsedUserId"

        fun start(context: Context) {
            val intent = Intent(context, ListActivity::class.java)
            context.startActivity(intent)
        }
    }
}
