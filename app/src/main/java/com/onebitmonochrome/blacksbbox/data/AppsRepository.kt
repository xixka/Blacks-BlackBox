package com.onebitmonochrome.blacksbbox.data

import android.content.pm.ApplicationInfo
import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import androidx.lifecycle.MutableLiveData
import java.io.File
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.utils.AbiUtils
import com.onebitmonochrome.blacksbbox.R
import com.onebitmonochrome.blacksbbox.app.App
import com.onebitmonochrome.blacksbbox.app.AppManager
import com.onebitmonochrome.blacksbbox.bean.AppInfo
import com.onebitmonochrome.blacksbbox.bean.InstalledAppBean
import com.onebitmonochrome.blacksbbox.util.MemoryManager
import com.onebitmonochrome.blacksbbox.util.getString

/**
 * Lightweight per-app entry of the aggregated (all-spaces) list. Pure JVM, used by
 * [AppsSortKeys.aggregateApps] so the aggregation stays unit-testable without Android graphics.
 */
data class AllAppEntry(val packageName: String, val userId: Int, val order: Int)

/**
 * A single sort-key entry: one package scoped to one virtual space. Pure JVM.
 */
data class AppSortEntry(val packageName: String, val userId: Int)

/**
 * Pure helpers for the global sort key. All logic here is JVM-only (no BlackBoxCore, no
 * android.* calls) so it can be unit-tested directly.
 *
 * - New (global) key: "AppListAll", stored in the same "UserRemark" SharedPreferences file.
 *   Entry format: "pkg@userId", entries separated by commas.
 * - Legacy per-user keys: "AppList$userId", containing bare package names ("pkg1,pkg2").
 *   They are migrated into the global key on first read when the global key is empty.
 */
object AppsSortKeys {
    const val GLOBAL_SORT_KEY = "AppListAll"
    const val LEGACY_SORT_KEY_PREFIX = "AppList"
    const val ENTRY_SEPARATOR = ","
    const val USER_ID_SEPARATOR = "@"

    /** Legacy per-user sort key, e.g. "AppList0". */
    fun legacySortKeyFor(userId: Int): String = "$LEGACY_SORT_KEY_PREFIX$userId"

    /** Parses a single "pkg@userId" entry; returns null for malformed input. */
    fun parseSortKey(raw: String): AppSortEntry? {
        if (raw.isBlank()) return null
        val separatorIndex = raw.lastIndexOf(USER_ID_SEPARATOR)
        if (separatorIndex <= 0 || separatorIndex == raw.length - 1) return null
        val pkg = raw.substring(0, separatorIndex).trim()
        if (pkg.isEmpty()) return null
        val userId = raw.substring(separatorIndex + 1).trim().toIntOrNull() ?: return null
        return AppSortEntry(pkg, userId)
    }

    /** Parses a serialized sort key ("pkg@uid,pkg@uid,.."), deduplicated, order preserved. */
    fun parseSortKeys(raw: String): List<AppSortEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        val seen = linkedSetOf<String>()
        val result = mutableListOf<AppSortEntry>()
        raw.split(ENTRY_SEPARATOR).forEach { piece ->
            val entry = parseSortKey(piece) ?: return@forEach
            if (seen.add(sortEntryKey(entry.packageName, entry.userId))) {
                result.add(entry)
            }
        }
        return result
    }

    /** Serializes entries back to the persisted format. */
    fun serializeSortKeys(entries: Collection<AppSortEntry>): String =
            entries.joinToString(ENTRY_SEPARATOR) { "${it.packageName}${USER_ID_SEPARATOR}${it.userId}" }

    /**
     * Migrates legacy per-user bare-package lists ("AppList$userId" -> "pkg1,pkg2") into global
     * "pkg@userId" entries. Users are processed in ascending id order; duplicates removed.
     */
    fun migrateLegacySortKeys(legacyKeys: Map<Int, String>): List<AppSortEntry> {
        val result = mutableListOf<AppSortEntry>()
        legacyKeys.keys.sorted().forEach { userId ->
            val raw = legacyKeys[userId] ?: return@forEach
            raw.split(ENTRY_SEPARATOR).forEach { piece ->
                val pkg = piece.trim()
                if (pkg.isNotEmpty()) {
                    val entry = AppSortEntry(pkg, userId)
                    if (!result.any { it.packageName == entry.packageName && it.userId == entry.userId }) {
                        result.add(entry)
                    }
                }
            }
        }
        return result
    }

    /**
     * Flattens per-space package lists into one aggregated list, in user order, each entry
     * tagged with its space id and carrying a stable insertion order for stable sorting.
     */
    fun aggregateApps(users: List<Int>, perUserApps: Map<Int, List<String>>): List<AllAppEntry> {
        val result = mutableListOf<AllAppEntry>()
        var order = 0
        users.forEach { userId ->
            perUserApps[userId]?.forEach { pkg ->
                val trimmed = pkg.trim()
                if (trimmed.isNotEmpty()) {
                    result.add(AllAppEntry(trimmed, userId, order))
                    order++
                }
            }
        }
        return result
    }

    /**
     * Sorts the aggregated list: entries present in [sortEntries] come first in their stored
     * order (matched by exact "pkg@userId" pair, so "com.a.b-c" and "com.a.b_c" never clash);
     * the rest keep their input order after them.
     */
    fun sortAllApps(apps: List<AllAppEntry>, sortEntries: List<AppSortEntry>): List<AllAppEntry> {
        if (apps.isEmpty() || sortEntries.isEmpty()) return apps
        val orderMap = HashMap<String, Int>()
        sortEntries.forEachIndexed { index, entry ->
            val key = sortEntryKey(entry.packageName, entry.userId)
            if (!orderMap.containsKey(key)) {
                orderMap[key] = index
            }
        }
        return apps.sortedWith(Comparator { a, b ->
            val orderA = orderMap[sortEntryKey(a.packageName, a.userId)] ?: Int.MAX_VALUE
            val orderB = orderMap[sortEntryKey(b.packageName, b.userId)] ?: Int.MAX_VALUE
            if (orderA != orderB) orderA - orderB else a.order - b.order
        })
    }

    /** Pure add/remove of one "pkg@userId" entry, preserving order. */
    fun updateSortEntries(
            entries: List<AppSortEntry>,
            pkg: String,
            userId: Int,
            isAdd: Boolean
    ): List<AppSortEntry> {
        val without = entries.filterNot { it.packageName == pkg && it.userId == userId }
        if (!isAdd) {
            return without
        }
        return without + AppSortEntry(pkg, userId)
    }

    private fun sortEntryKey(pkg: String, userId: Int): String = "$pkg$USER_ID_SEPARATOR$userId"
}


class AppsRepository {
    val TAG: String = "AppsRepository"
    private var mInstalledList = mutableListOf<AppInfo>()

    private fun safeLoadAppLabel(applicationInfo: ApplicationInfo): String {
        return try {
            BlackBoxCore.getPackageManager().getApplicationLabel(applicationInfo).toString()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load label for ${applicationInfo.packageName}: ${e.message}")
            applicationInfo.packageName
        }
    }

    private fun safeLoadAppIcon(
            applicationInfo: ApplicationInfo
    ): android.graphics.drawable.Drawable? {
        return try {
            if (MemoryManager.shouldSkipIconLoading()) {
                Log.w(
                        TAG,
                        "Memory usage high (${MemoryManager.getMemoryUsagePercentage()}%), skipping icon for ${applicationInfo.packageName}"
                )
                return null
            }

            val icon = BlackBoxCore.getPackageManager().getApplicationIcon(applicationInfo)

            if (icon is android.graphics.drawable.BitmapDrawable) {
                val bitmap = icon.bitmap
                if (bitmap.width > 96 || bitmap.height > 96) {
                    try {
                        val scaledBitmap =
                                android.graphics.Bitmap.createScaledBitmap(bitmap, 96, 96, true)
                        android.graphics.drawable.BitmapDrawable(
                                BlackBoxCore.getPackageManager()
                                        .getResourcesForApplication(applicationInfo.packageName),
                                scaledBitmap
                        )
                    } catch (e: Exception) {
                        Log.w(
                                TAG,
                                "Failed to scale icon for ${applicationInfo.packageName}: ${e.message}"
                        )
                        icon
                    }
                } else {
                    icon
                }
            } else {
                icon
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load icon for ${applicationInfo.packageName}: ${e.message}")
            null
        }
    }

    fun previewInstallList() {
        try {
            synchronized(mInstalledList) {
                val installedApplications: List<ApplicationInfo> =
                        BlackBoxCore.getPackageManager().getInstalledApplications(0)
                val installedList = mutableListOf<AppInfo>()

                for (installedApplication in installedApplications) {
                    try {
                        val file = File(installedApplication.sourceDir)

                        if ((installedApplication.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
                                continue

                        if (!AbiUtils.isSupport(file)) continue

                        if (BlackBoxCore.get().isBlackBoxApp(installedApplication.packageName)) {
                            Log.d(
                                    TAG,
                                    "Filtering out BlackBox app: ${installedApplication.packageName}"
                            )
                            continue
                        }

                        val isXpModule = false

                        val info =
                                AppInfo(
                                        safeLoadAppLabel(installedApplication),
                                        safeLoadAppIcon(
                                                installedApplication
                                        ),
                                        installedApplication.packageName,
                                        installedApplication.sourceDir,
                                        isXpModule
                                )
                        installedList.add(info)
                    } catch (e: Exception) {
                        Log.e(
                                TAG,
                                "Error processing app ${installedApplication.packageName}: ${e.message}"
                            )
                    }
                }
                this.mInstalledList.clear()
                this.mInstalledList.addAll(installedList)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in previewInstallList: ${e.message}")
        }
    }

    fun getInstalledAppList(
            userID: Int,
            loadingLiveData: MutableLiveData<Boolean>,
            appsLiveData: MutableLiveData<List<InstalledAppBean>>
    ) {
        try {
            loadingLiveData.postValue(true)
            synchronized(mInstalledList) {
                val blackBoxCore = BlackBoxCore.get()
                Log.d(TAG, mInstalledList.joinToString(","))
                val newInstalledList =
                        mInstalledList.map {
                            InstalledAppBean(
                                    it.name,
                                    it.icon,
                                    it.packageName,
                                    it.sourceDir,
                                    blackBoxCore.isInstalled(it.packageName, userID)
                            )
                        }
                appsLiveData.postValue(newInstalledList)
                loadingLiveData.postValue(false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in getInstalledAppList: ${e.message}")
            loadingLiveData.postValue(false)
            appsLiveData.postValue(emptyList())
        }
    }

    /**
     * Aggregates installed apps of ALL virtual spaces into one list. Each [AppInfo] carries the
     * space id ([AppInfo.userId]); ordering is driven by the global sort key ("AppListAll"),
     * migrating legacy per-user keys ("AppList$userId") on first read when needed.
     */
    fun getAllInstalledAppList(appsLiveData: MutableLiveData<List<AppInfo>>) {
        try {
            if (MemoryManager.isMemoryCritical()) {
                Log.w(
                        TAG,
                        "Memory critical (${MemoryManager.getMemoryUsagePercentage()}%), forcing garbage collection"
                )
                MemoryManager.forceGarbageCollectionIfNeeded()
            }

            val blackBoxCore = BlackBoxCore.get()
            val users = blackBoxCore.users.map { it.id }
            if (users.isEmpty()) {
                Log.w(TAG, "getAllInstalledAppList: no virtual spaces found, showing empty list")
                appsLiveData.postValue(emptyList())
                return
            }

            val perUserApplications = mutableMapOf<Int, List<ApplicationInfo>>()
            users.forEach { userId ->
                val list = fetchInstalledApplicationsWithRetry(blackBoxCore, userId)
                if (list != null) {
                    perUserApplications[userId] = list
                } else {
                    Log.e(
                            TAG,
                            "getAllInstalledAppList: failed to load apps for userId=$userId after retries, skipping this space"
                    )
                }
            }

            // Aggregation + sorting are pure functions (unit-testable, no BlackBoxCore calls).
            val pkgLists = perUserApplications.mapValues { (_, list) ->
                list.mapNotNull { it?.packageName }
            }
            val aggregated = AppsSortKeys.aggregateApps(users, pkgLists)
            val sortEntries = loadGlobalSortEntriesOrNull() ?: emptyList()
            val sorted = AppsSortKeys.sortAllApps(aggregated, sortEntries)

            val infoLookup = HashMap<Pair<String, Int>, ApplicationInfo>()
            perUserApplications.forEach { (userId, list) ->
                list.forEach { applicationInfo ->
                    val pkg = applicationInfo?.packageName ?: return@forEach
                    if (pkg.isNotBlank()) {
                        infoLookup[Pair(pkg, userId)] = applicationInfo
                    }
                }
            }

            val appInfoList = mutableListOf<AppInfo>()

            sorted.forEachIndexed { index, entry ->
                try {
                    if (index > 0 && index % 25 == 0 && MemoryManager.isMemoryCritical()) {
                        Log.w(TAG, "Memory critical during processing, forcing GC")
                        MemoryManager.forceGarbageCollectionIfNeeded()
                    }

                    val applicationInfo =
                            infoLookup[Pair(entry.packageName, entry.userId)]
                                    ?: return@forEachIndexed

                    appInfoList.add(
                            AppInfo(
                                    safeLoadAppLabel(applicationInfo),
                                    safeLoadAppIcon(applicationInfo),
                                    entry.packageName,
                                    applicationInfo.sourceDir ?: "",
                                    false,
                                    entry.userId
                            )
                    )

                    if (index > 0 && index % 50 == 0) {
                        Log.d(
                                TAG,
                                "getAllInstalledAppList: processed $index/${sorted.size} apps - ${MemoryManager.getMemoryInfo()}"
                        )
                    }
                } catch (e: Exception) {
                    Log.e(
                            TAG,
                            "getAllInstalledAppList: error processing entry (${entry.packageName}@${entry.userId}): ${e.message}"
                    )
                }
            }

            Log.d(
                    TAG,
                    "getAllInstalledAppList: aggregated ${appInfoList.size} apps across ${users.size} space(s)"
            )

            try {
                appsLiveData.postValue(appInfoList)
            } catch (e: Exception) {
                Log.e(TAG, "getAllInstalledAppList: error posting to LiveData: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in getAllInstalledAppList: ${e.message}")
            try {
                appsLiveData.postValue(emptyList())
            } catch (e2: Exception) {
                Log.e(TAG, "getAllInstalledAppList: error posting empty list: ${e2.message}")
            }
        }
    }

    private fun fetchInstalledApplicationsWithRetry(
            blackBoxCore: BlackBoxCore,
            userId: Int
    ): List<ApplicationInfo>? {
        var applicationList: List<ApplicationInfo>? = null
        var retryCount = 0
        val maxRetries = 3

        while (applicationList == null && retryCount < maxRetries) {
            try {
                applicationList = blackBoxCore.getInstalledApplications(0, userId)
                if (applicationList == null) {
                    Log.w(
                            TAG,
                            "fetchInstalledApplicationsWithRetry: attempt ${retryCount + 1} returned null for userId=$userId, retrying..."
                    )
                    retryCount++
                    Thread.sleep(100)
                }
            } catch (e: Exception) {
                Log.e(
                        TAG,
                        "fetchInstalledApplicationsWithRetry: error on attempt ${retryCount + 1} for userId=$userId: ${e.message}"
                )
                retryCount++
                if (retryCount < maxRetries) {
                    Thread.sleep(200)
                }
            }
        }

        return applicationList
    }

    fun installApk(source: String, userId: Int, resultLiveData: MutableLiveData<String>) {
        try {
            val normalizedSource = normalizeApkSource(source)
            val parsedUri = runCatching { Uri.parse(normalizedSource) }.getOrNull()
            val scheme = parsedUri?.scheme
            val looksLikeApkPath =
                    normalizedSource.endsWith(".apk", ignoreCase = true) ||
                            normalizedSource.contains(".apk?", ignoreCase = true) ||
                            normalizedSource.endsWith(".apks", ignoreCase = true) ||
                            normalizedSource.contains(".apks?", ignoreCase = true)
            val localFile = if (scheme.isNullOrEmpty()) File(normalizedSource) else null
            val isLocalFilePath = localFile?.exists() == true || looksLikeApkPath
            val isContentOrFileUri = scheme == ContentResolver.SCHEME_CONTENT || scheme == ContentResolver.SCHEME_FILE

            Log.i(
                TAG,
                "installApk: userId=$userId scheme=${scheme ?: "<none>"} " +
                    "isContentOrFileUri=$isContentOrFileUri isLocalFilePath=$isLocalFilePath " +
                    "source=$normalizedSource"
            )

            if (normalizedSource.contains("blackbox") ||
                            normalizedSource.contains("niunaijun") ||
                            normalizedSource.contains("vspace") ||
                            normalizedSource.contains("virtual")
            ) {
                try {
                    val blackBoxCore = BlackBoxCore.get()
                    val hostPackageName = BlackBoxCore.getHostPkg()

                    // For local file paths we can pre-check the archive; for content/file Uris the core
                    // install path will still reject the host package after parsing.
                    if (isLocalFilePath) {
                        val file = localFile ?: File(normalizedSource)
                        if (file.exists()) {
                            val packageInfo =
                                    BlackBoxCore.getPackageManager()
                                            .getPackageArchiveInfo(file.absolutePath, 0)
                            if (packageInfo != null && packageInfo.packageName == hostPackageName) {
                                resultLiveData.postValue(
                                        getString(R.string.install_self_blocked)
                                )
                                return
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not verify if this is BlackBox app: ${e.message}")
                }
            }

            val blackBoxCore = BlackBoxCore.get()

            val installResult = when {
                isContentOrFileUri && parsedUri != null -> {
                    blackBoxCore.installPackageAsUser(parsedUri, userId)
                }

                isLocalFilePath -> {
                    val file = localFile ?: File(normalizedSource)
                    blackBoxCore.installPackageAsUser(file, userId)
                }

                else -> {
                    // Treat as package-name cloning (installed-on-host -> install-by-system).
                    blackBoxCore.installPackageAsUser(normalizedSource, userId)
                }
            }

            if (installResult.success) {
                updateAppSortList(userId, installResult.packageName, true)
                resultLiveData.postValue(getString(R.string.install_success))
            } else {
                resultLiveData.postValue(getString(R.string.install_fail, installResult.msg))
            }
            scanUser()
        } catch (e: Exception) {
            Log.e(TAG, "Error installing APK: ${e.message}")
            resultLiveData.postValue(
                    App.getContext().getString(R.string.install_exception_fail, e.message)
            )
        }
    }

    private fun normalizeApkSource(source: String): String {
        val s = source.trim()
        if (s.isEmpty()) return s

        // Some pickers/providers return malformed forms like:
        // - "content:/authority/..." (missing second slash)
        // - "/content:/authority/..." (File.absolutePath of the malformed form)
        if (s.startsWith("/content:/")) {
            return "content://" + s.removePrefix("/content:/")
        }
        if (s.startsWith("content:/") && !s.startsWith("content://")) {
            return "content://" + s.removePrefix("content:/")
        }
        if (s.startsWith("/file:/")) {
            return "file://" + s.removePrefix("/file:/")
        }
        if (s.startsWith("file:/") && !s.startsWith("file://")) {
            return "file://" + s.removePrefix("file:/")
        }
        return s
    }

    fun unInstall(packageName: String, userID: Int, resultLiveData: MutableLiveData<String>) {
        try {
            BlackBoxCore.get().uninstallPackageAsUser(packageName, userID)
            updateAppSortList(userID, packageName, false)
            scanUser()
            resultLiveData.postValue(getString(R.string.uninstall_success))
        } catch (e: Exception) {
            Log.e(TAG, "Error uninstalling APK: ${e.message}")
            resultLiveData.postValue(
                    App.getContext().getString(R.string.uninstall_exception_fail, e.message)
            )
        }
    }

    fun launchApk(packageName: String, userId: Int, launchLiveData: MutableLiveData<Boolean>) {
        try {
            val result = BlackBoxCore.get().launchApk(packageName, userId)
            launchLiveData.postValue(result)
        } catch (e: Exception) {
            Log.e(TAG, "Error launching APK: ${e.message}")
            launchLiveData.postValue(false)
        }
    }

    fun clearApkData(packageName: String, userID: Int, resultLiveData: MutableLiveData<String>) {
        try {
            BlackBoxCore.get().clearPackage(packageName, userID)
            resultLiveData.postValue(getString(R.string.clear_success))
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing APK data: ${e.message}")
            resultLiveData.postValue(
                    App.getContext().getString(R.string.clear_exception_fail, e.message)
            )
        }
    }

    private fun scanUser() {
        try {
            val blackBoxCore = BlackBoxCore.get()
            val userList = blackBoxCore.users

            if (userList.isEmpty()) {
                return
            }

            val id = userList.last().id

            if (blackBoxCore.getInstalledApplications(0, id).isEmpty()) {
                blackBoxCore.deleteUser(id)
                AppManager.mRemarkSharedPreferences.edit().apply {
                    remove("Remark$id")
                    remove("AppList$id")
                    apply()
                }
                // Drop stale global sort entries bound to the deleted space.
                val globalEntries = loadGlobalSortEntriesOrNull()
                if (globalEntries != null) {
                    persistGlobalSortEntries(globalEntries.filterNot { it.userId == id })
                }
                scanUser()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in scanUser: ${e.message}")
        }
    }

    /**
     * Loads global sort entries and migrates legacy per-user keys ("AppList$userId") into the
     * global key when the global key is empty/absent. Legacy keys are kept on disk so a downgrade
     * stays functional. Returns null on failure so callers never wipe data on a transient error.
     */
    private fun loadGlobalSortEntriesOrNull(): List<AppSortEntry>? {
        return try {
            val sp = AppManager.mRemarkSharedPreferences
            val raw = sp.getString(AppsSortKeys.GLOBAL_SORT_KEY, null)
            if (!raw.isNullOrBlank()) {
                return AppsSortKeys.parseSortKeys(raw)
            }

            val legacyKeys = mutableMapOf<Int, String>()
            sp.all.keys.forEach { key ->
                if (key == null || key == AppsSortKeys.GLOBAL_SORT_KEY) return@forEach
                if (!key.startsWith(AppsSortKeys.LEGACY_SORT_KEY_PREFIX)) return@forEach
                val userId =
                        key.removePrefix(AppsSortKeys.LEGACY_SORT_KEY_PREFIX)
                                .trim()
                                .toIntOrNull()
                                ?: return@forEach
                val value = sp.getString(key, null)
                if (!value.isNullOrEmpty()) {
                    legacyKeys[userId] = value
                }
            }

            if (legacyKeys.isEmpty()) {
                emptyList()
            } else {
                val migrated = AppsSortKeys.migrateLegacySortKeys(legacyKeys)
                Log.i(
                        TAG,
                        "Migrated ${migrated.size} legacy sort entries into ${AppsSortKeys.GLOBAL_SORT_KEY}"
                )
                persistGlobalSortEntries(migrated)
                migrated
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading global sort entries: ${e.message}")
            null
        }
    }

    private fun persistGlobalSortEntries(entries: List<AppSortEntry>) {
        try {
            AppManager.mRemarkSharedPreferences.edit().apply {
                putString(AppsSortKeys.GLOBAL_SORT_KEY, AppsSortKeys.serializeSortKeys(entries))
                apply()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error persisting global sort entries: ${e.message}")
        }
    }

    private fun updateAppSortList(userID: Int, pkg: String, isAdd: Boolean) {
        try {
            val current = loadGlobalSortEntriesOrNull()
            if (current == null) {
                Log.w(TAG, "Skipping sort list update; global sort entries unavailable")
                return
            }
            persistGlobalSortEntries(AppsSortKeys.updateSortEntries(current, pkg, userID, isAdd))
        } catch (e: Exception) {
            Log.e(TAG, "Error updating app sort list: ${e.message}")
        }
    }

    fun updateApkOrder(dataList: List<AppInfo>) {
        try {
            val entries =
                    dataList
                            .map { AppSortEntry(it.packageName, it.userId) }
                            .distinctBy { it.packageName to it.userId }
            persistGlobalSortEntries(entries)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating APK order: ${e.message}")
        }
    }
}
