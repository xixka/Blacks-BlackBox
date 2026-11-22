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

package com.onebitmonochrome.blacksbbox.util

/**
 * Pure classification of APK install sources. JVM-only (no android.* calls) so the
 * "SAF instead of direct file path" decision stays unit-testable.
 *
 * Context: the host app no longer requests any runtime storage permission
 * (MANAGE_EXTERNAL_STORAGE was removed). Every user-file entry point must therefore be
 * readable without one:
 * - content:// URIs come from SAF pickers (GetContent/OpenDocument) or share intents and
 *   carry a temporary read grant -> always installable.
 * - file:// URIs are only produced by legacy senders; the engine reads them through the
 *   ContentResolver path and they work for app-owned / sender-granted locations.
 * - Local paths are only ever app-private dirs (getExternalFilesDir), which need no
 *   permission on any API level.
 * - Anything else is treated as a host package name for clone-install.
 */
sealed class ApkSource {
    /** content:// URI — SAF-backed, readable without any storage permission. */
    data class ContentUri(val uri: String) : ApkSource()

    /** file:// URI — legacy sender; kept working via the engine's URI install path. */
    data class FileUri(val uri: String) : ApkSource()

    /** Local file path (app-private external dir or cache). No permission required. */
    data class LocalPath(val path: String) : ApkSource()

    /** Host package name -> clone-install through the engine. */
    data class PackageName(val name: String) : ApkSource()
}

object ApkSourceResolver {

    private const val SCHEME_CONTENT = "content"
    private const val SCHEME_FILE = "file"
    private val SCHEME_PATTERN = Regex("^[A-Za-z][A-Za-z0-9+.\\-]*$")

    /**
     * Fixes malformed scheme forms that some pickers/providers produce:
     * - "content:/authority/..."  (missing second slash)
     * - "/content:/authority/..." (File.absolutePath of the malformed form)
     * - same for file://
     * Returns a trimmed, scheme-corrected string; null/blank input becomes "".
     */
    fun normalize(raw: String?): String {
        if (raw == null) return ""
        val s = raw.trim()
        if (s.isEmpty()) return s
        if (s.startsWith("/content:/")) return "content://" + s.removePrefix("/content:/")
        if (s.startsWith("content:/") && !s.startsWith("content://")) {
            return "content://" + s.removePrefix("content:/")
        }
        // Absolute local path form: "/file:/dir/app.apk" lost its leading slash to the
        // "/file:/" prefix, so restore it as a proper absolute file URI (three slashes).
        if (s.startsWith("/file:/")) return "file:///" + s.removePrefix("/file:/")
        if (s.startsWith("file:/") && !s.startsWith("file://")) {
            return "file://" + s.removePrefix("file:/")
        }
        return s
    }

    /**
     * Loose ".apk"/".apks" shape check (suffix or suffix followed by query params, e.g.
     * "...apk?alt=media"). Mirror of the historical installApk heuristic.
     */
    fun looksLikeApkPath(normalized: String): Boolean {
        val s = normalized
        return s.endsWith(".apk", ignoreCase = true) ||
                s.contains(".apk?", ignoreCase = true) ||
                s.endsWith(".apks", ignoreCase = true) ||
                s.contains(".apks?", ignoreCase = true)
    }

    /**
     * Classifies a normalized source string. [localFileExists] is supplied by the caller
     * (File(...).exists()) to keep this function pure; callers pass false for anything
     * containing a scheme. Priority matches the historical installApk when-block:
     * content/file URIs first, then local paths, otherwise package-name clone.
     */
    fun classify(normalized: String, localFileExists: Boolean): ApkSource {
        val scheme = schemeOf(normalized)
        return when {
            scheme == SCHEME_CONTENT -> ApkSource.ContentUri(normalized)
            scheme == SCHEME_FILE -> ApkSource.FileUri(normalized)
            looksLikeApkPath(normalized) || (localFileExists && scheme == null) ->
                ApkSource.LocalPath(normalized)
            else -> ApkSource.PackageName(normalized)
        }
    }

    /**
     * The permission-free contract: SAF/content URIs and local app-private paths are both
     * readable without any runtime storage permission; only they may bypass SAF.
     */
    fun isInstallableWithoutStoragePermission(kind: ApkSource): Boolean = when (kind) {
        is ApkSource.ContentUri -> true
        is ApkSource.FileUri -> true
        is ApkSource.LocalPath -> true
        is ApkSource.PackageName -> false
    }

    /** Extracts the scheme of "scheme://..."; returns null when there is no valid scheme. */
    private fun schemeOf(s: String): String? {
        val index = s.indexOf("://")
        if (index <= 0) return null
        val scheme = s.substring(0, index)
        return if (SCHEME_PATTERN.matches(scheme)) scheme.lowercase() else null
    }
}
