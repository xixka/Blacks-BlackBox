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
package top.niunaijun.blackbox.backup.ylb

/**
 * Pure classifier that maps a Meta Wolf backup zip entry path onto the blackbox-backup v1
 * layout (format facts F5/F6, docs/ylb-format/RESEARCH.md section 8).
 *
 * Meta Wolf zip entries are paths relative to the Meta Wolf host dataDir. Expected shapes
 * (positive signals, NOT hard requirements - the prefix list is never asserted):
 *
 *  - `app_foza_apks/<pkg>/...` -> the clone staging area: everything under the package
 *    directory becomes `data_user/...` of that package in the v1 archive.
 *  - `app_split/<pkg>/...` (or APK-named files under app_split without a package segment)
 *    -> split/base APK candidates used for installation.
 *  - any other relative path that contains a package-name-like segment (e.g. `com.a.b`,
 *    `com.foo_bar.baz`) -> attributed to that package as data.
 *  - entries where no package can be identified -> [YlbEntryType.UNKNOWN]; the importer
 *    extracts them into a `ylb_unknown/` directory and reports them explicitly (never
 *    silently dropped).
 *
 * Thread safety: stateless pure functions.
 */
object YlbPathMapper {

    /** Classification outcome of an entry path. */
    enum class YlbEntryType {
        /** Clone application data; maps to the v1 `data_user` section. */
        DATA,

        /** APK payload (base or split); maps to the v1 base.apk / splits section. */
        APK,

        /** No package name could be identified; goes to ylb_unknown/ and is reported. */
        UNKNOWN,
    }

    /** Result of mapping one entry path. */
    class Mapping(
        /** Owning package name, or null when unidentifiable (then type == UNKNOWN). */
        val packageName: String?,
        /** Entry classification. */
        val type: YlbEntryType,
        /**
         * Path segments after the package segment, joined with '/' ("" when the entry IS the
         * package directory). This is the relative path used inside the v1 archive section.
         */
        val relativeToPackage: String,
        /** Validated path segments of the entry (safe for filesystem use). */
        val segments: List<String>,
        /** Human readable explanation of the classification decision (for reporting). */
        val reason: String,
    )

    /** Known Meta Wolf staging directory prefixes (positive signals only, never asserted). */
    const val PREFIX_FOZA_APKS = "app_foza_apks"
    const val PREFIX_APP_SPLIT = "app_split"

    /** Directory (under the parse extraction root) where unattributed entries are staged. */
    const val UNKNOWN_DIR = "ylb_unknown"

    private const val MAX_SEGMENTS = 128

    // Matches Android package names: >= 2 dot separated segments; the first segment starts
    // with a letter; segments may contain digits and underscores (Meta Wolf escapes
    // characters of the original package name with underscores).
    private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+$")

    // File extensions that disqualify a dot-containing segment from being a package name
    // (e.g. "libwood.so" would otherwise match the regex above).
    private val FILE_EXT_BLOCKLIST =
        setOf(
            "apk", "aab", "so", "dex", "odex", "vdex", "art", "jar", "zip", "db", "db-wal",
            "db-shm", "xml", "json", "dat", "bin", "txt", "log", "properties", "config",
            "png", "jpg", "jpeg", "webp", "gif", "ttf", "otf", "list", "sha256", "md5", "sig",
        )

    /**
     * Maps one Meta Wolf zip entry path.
     *
     * The path must be relative (no leading '/'), use '/' separators (a '\\' is normalized
     * first), and must not contain path traversal ("..") - violating entries classify as
     * [YlbEntryType.UNKNOWN] with empty [Mapping.segments] so the importer can skip them
     * explicitly.
     */
    fun map(entryPath: String): Mapping {
        val normalized = entryPath.trim().replace('\\', '/')
        if (normalized.isEmpty()) {
            return unknown(emptyList(), "empty entry path")
        }
        if (normalized.startsWith('/') || normalized.contains(':') || normalized.contains('\u0000')) {
            return unknown(emptyList(), "absolute or invalid entry path")
        }
        val segments = normalized.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) {
            return unknown(emptyList(), "empty entry path")
        }
        if (segments.size > MAX_SEGMENTS || segments.any { it == ".." || it == "." }) {
            return unknown(emptyList(), "unsafe entry path (traversal or excessive depth)")
        }

        // Known-prefix shapes first (positive identification, soft rules).
        if (segments[0] == PREFIX_FOZA_APKS) {
            segments.getOrNull(1)?.takeIf { isPackageNameLike(it) }?.let { pkg ->
                return Mapping(
                    packageName = pkg,
                    type = YlbEntryType.DATA,
                    relativeToPackage = joinRest(segments, 2),
                    segments = segments,
                    reason = "Meta Wolf clone staging prefix ($PREFIX_FOZA_APKS) with package segment",
                )
            }
        }
        if (segments[0] == PREFIX_APP_SPLIT) {
            val pkgIndex = packageSegmentIndex(segments, 1)
            if (pkgIndex >= 0) {
                return Mapping(
                    packageName = segments[pkgIndex],
                    type = YlbEntryType.APK,
                    relativeToPackage = joinRest(segments, pkgIndex + 1),
                    segments = segments,
                    reason = "Meta Wolf split APK prefix ($PREFIX_APP_SPLIT) with package segment",
                )
            }
            // APK payload without an attributable package: must not be silently dropped.
            return unknown(segments, "$PREFIX_APP_SPLIT entry without an identifiable package segment")
        }

        // Generic fallback: first package-like segment in the path owns the entry as data.
        val pkgIndex = packageSegmentIndex(segments, 0)
        if (pkgIndex >= 0) {
            return Mapping(
                packageName = segments[pkgIndex],
                type = YlbEntryType.DATA,
                relativeToPackage = joinRest(segments, pkgIndex + 1),
                segments = segments,
                reason = "package-like segment '${segments[pkgIndex]}' found in entry path",
            )
        }
        return unknown(segments, "no package-like segment found in entry path")
    }

    /**
     * Scans [segments] from [fromIndex] for the first package-name-like segment.
     * @return its index, or -1 when none matches.
     */
    fun packageSegmentIndex(segments: List<String>, fromIndex: Int): Int {
        for (i in fromIndex until segments.size) {
            if (isPackageNameLike(segments[i])) return i
        }
        return -1
    }

    /** Convenience overload of [packageSegmentIndex] for tests and sniffing. */
    fun findPackageSegment(entryPath: String): String? {
        val mapping = map(entryPath)
        return mapping.packageName
    }

    /**
     * Whether a single path segment looks like an Android package name
     * (e.g. "com.a.b", "com.foo_bar.baz"). File names with package-like shapes
     * ("libwood.so", "classes.dex") are excluded via a file-extension blocklist.
     */
    fun isPackageNameLike(segment: String): Boolean {
        if (segment.length < 5 || segment.length > 200) return false
        if (!PACKAGE_NAME.matches(segment)) return false
        val lastExt = segment.substringAfterLast('.', "").lowercase()
        if (lastExt in FILE_EXT_BLOCKLIST) return false
        return true
    }

    private fun joinRest(segments: List<String>, fromIndex: Int): String =
        segments.drop(fromIndex).joinToString("/")

    private fun unknown(segments: List<String>, reason: String) = Mapping(
        packageName = null,
        type = YlbEntryType.UNKNOWN,
        relativeToPackage = "",
        segments = segments,
        reason = reason,
    )
}
