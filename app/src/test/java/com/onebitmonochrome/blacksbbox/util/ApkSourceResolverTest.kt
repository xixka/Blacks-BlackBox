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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the APK source classification (no android.* calls).
 *
 * The storage-permission removal contract lives here: SAF/content URIs and app-private
 * local paths must classify as installable WITHOUT any runtime storage permission, while
 * package-name cloning stays a separate kind.
 */
class ApkSourceResolverTest {

    // -----------------------------------------------------------------------------------------
    // normalize
    // -----------------------------------------------------------------------------------------

    @Test
    fun `normalize keeps well formed content uri untouched`() {
        val uri = "content://com.example.provider/root/abs/app.apk"
        assertEquals(uri, ApkSourceResolver.normalize(uri))
    }

    @Test
    fun `normalize keeps well formed file uri untouched`() {
        val uri = "file:///storage/emulated/0/app.apk"
        assertEquals(uri, ApkSourceResolver.normalize(uri))
    }

    @Test
    fun `normalize repairs missing second slash`() {
        assertEquals(
                "content://com.example/root/app.apk",
                ApkSourceResolver.normalize("content:/com.example/root/app.apk"),
        )
        // Historical rule: "file:/dir/app.apk" becomes "file://dir/app.apk" (authority-style).
        assertEquals(
                "file://dir/app.apk",
                ApkSourceResolver.normalize("file:/dir/app.apk"),
        )
        // Well-formed file URIs pass through unchanged.
        assertEquals(
                "file://dir/app.apk",
                ApkSourceResolver.normalize("file://dir/app.apk"),
        )
    }

    @Test
    fun `normalize repairs absolute path of malformed scheme form`() {
        assertEquals(
                "content://com.example/root/app.apk",
                ApkSourceResolver.normalize("/content:/com.example/root/app.apk"),
        )
        assertEquals(
                "file:///dir/app.apk",
                ApkSourceResolver.normalize("/file:/dir/app.apk"),
        )
    }

    @Test
    fun `normalize trims surrounding whitespace`() {
        assertEquals(
                "content://com.example/app.apk",
                ApkSourceResolver.normalize("  content://com.example/app.apk  "),
        )
    }

    @Test
    fun `normalize maps blank and null to empty string`() {
        assertEquals("", ApkSourceResolver.normalize(null))
        assertEquals("", ApkSourceResolver.normalize(""))
        assertEquals("", ApkSourceResolver.normalize("   "))
    }

    @Test
    fun `normalize leaves plain package names and paths untouched`() {
        assertEquals("com.example.app", ApkSourceResolver.normalize("com.example.app"))
        assertEquals("/data/app/base.apk", ApkSourceResolver.normalize("/data/app/base.apk"))
    }

    // -----------------------------------------------------------------------------------------
    // looksLikeApkPath
    // -----------------------------------------------------------------------------------------

    @Test
    fun `looksLikeApkPath accepts apk and apks suffixes`() {
        assertTrue(ApkSourceResolver.looksLikeApkPath("/dir/app.apk"))
        assertTrue(ApkSourceResolver.looksLikeApkPath("/dir/bundle.apks"))
        assertTrue(ApkSourceResolver.looksLikeApkPath("/dir/APP.APK"))
    }

    @Test
    fun `looksLikeApkPath accepts suffix followed by query params`() {
        assertTrue(ApkSourceResolver.looksLikeApkPath("content://host/app.apk?alt=media"))
        assertTrue(ApkSourceResolver.looksLikeApkPath("content://host/bundle.apks?token=1"))
    }

    @Test
    fun `looksLikeApkPath rejects non apk strings`() {
        assertFalse(ApkSourceResolver.looksLikeApkPath("com.example.app"))
        assertFalse(ApkSourceResolver.looksLikeApkPath("/dir/app.bin"))
        assertFalse(ApkSourceResolver.looksLikeApkPath(""))
        // ".apk" only as a middle fragment without a following '?' is not an apk path.
        assertFalse(ApkSourceResolver.looksLikeApkPath("/dir/app.apk.bak"))
    }

    // -----------------------------------------------------------------------------------------
    // classify
    // -----------------------------------------------------------------------------------------

    @Test
    fun `classify returns ContentUri for content scheme`() {
        val source = "content://com.example.provider/app.apk"
        val kind = ApkSourceResolver.classify(source, localFileExists = false)
        assertEquals(ApkSource.ContentUri(source), kind)
    }

    @Test
    fun `classify returns FileUri for file scheme`() {
        val source = "file:///dir/app.apk"
        val kind = ApkSourceResolver.classify(source, localFileExists = false)
        assertEquals(ApkSource.FileUri(source), kind)
    }

    @Test
    fun `classify accepts normalized malformed scheme form as content uri`() {
        // classify() documents a NORMALIZED input; callers run normalize() first.
        val normalized = ApkSourceResolver.normalize("content:/com.example/app.apk")
        assertEquals("content://com.example/app.apk", normalized)
        val kind = ApkSourceResolver.classify(normalized, false)
        assertEquals(ApkSource.ContentUri("content://com.example/app.apk"), kind)
    }

    @Test
    fun `classify returns LocalPath for apk-suffixed plain path`() {
        val kind = ApkSourceResolver.classify("/data/app/base.apk", localFileExists = false)
        assertEquals(ApkSource.LocalPath("/data/app/base.apk"), kind)
    }

    @Test
    fun `classify returns LocalPath when file exists even without apk suffix`() {
        val kind = ApkSourceResolver.classify("/data/local/tmp/payload", localFileExists = true)
        assertEquals(ApkSource.LocalPath("/data/local/tmp/payload"), kind)
    }

    @Test
    fun `classify returns PackageName for non existing non apk plain string`() {
        val kind = ApkSourceResolver.classify("com.example.app", localFileExists = false)
        assertEquals(ApkSource.PackageName("com.example.app"), kind)
    }

    @Test
    fun `classify keeps existing behavior for non content uri with apk suffix`() {
        // Historical quirk preserved: "http://...apk" (scheme other than content/file) fell
        // into the local-path branch of the old when-block.
        val kind = ApkSourceResolver.classify("http://host/app.apk", localFileExists = false)
        assertEquals(ApkSource.LocalPath("http://host/app.apk"), kind)
    }

    @Test
    fun `classify handles empty string as package name`() {
        val kind = ApkSourceResolver.classify("", localFileExists = false)
        assertEquals(ApkSource.PackageName(""), kind)
    }

    // -----------------------------------------------------------------------------------------
    // Permission-free contract (the storage permission removal)
    // -----------------------------------------------------------------------------------------

    @Test
    fun `saf content uris and app private paths are installable without storage permission`() {
        val kinds = listOf(
                ApkSourceResolver.classify("content://com.example/app.apk", false),
                ApkSourceResolver.classify("file:///dir/app.apk", false),
                ApkSourceResolver.classify("/data/app/base.apk", false),
        )
        kinds.forEach { kind ->
            assertTrue(
                    "kind=$kind must be installable without runtime storage permission",
                    ApkSourceResolver.isInstallableWithoutStoragePermission(kind),
            )
        }
    }

    @Test
    fun `package name cloning is not a file source`() {
        val kind = ApkSourceResolver.classify("com.example.app", false)
        assertFalse(ApkSourceResolver.isInstallableWithoutStoragePermission(kind))
    }
}
