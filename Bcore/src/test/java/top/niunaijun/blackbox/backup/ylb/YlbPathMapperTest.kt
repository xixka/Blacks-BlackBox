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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.niunaijun.blackbox.backup.ylb.YlbPathMapper.YlbEntryType

/**
 * TC-U-10b: YlbPathMapper entry path classification (pure function, no IO).
 */
class YlbPathMapperTest {

    @Test
    fun `foza staging entry maps to package data`() {
        val m = YlbPathMapper.map("app_foza_apks/com.a.b/files/x")
        assertEquals("com.a.b", m.packageName)
        assertEquals(YlbEntryType.DATA, m.type)
        assertEquals("files/x", m.relativeToPackage)
        assertTrue(m.reason.contains("app_foza_apks"))
    }

    @Test
    fun `app_split entry maps to apk`() {
        val m = YlbPathMapper.map("app_split/com.a.b/split_config.apk")
        assertEquals("com.a.b", m.packageName)
        assertEquals(YlbEntryType.APK, m.type)
        assertEquals("split_config.apk", m.relativeToPackage)
    }

    @Test
    fun `split base apk maps to apk with base name`() {
        val m = YlbPathMapper.map("app_split/com.a.b/base.apk")
        assertEquals("com.a.b", m.packageName)
        assertEquals(YlbEntryType.APK, m.type)
        assertEquals("base.apk", m.relativeToPackage)
    }

    @Test
    fun `generic entry with package segment maps to data`() {
        val m = YlbPathMapper.map("some/other/dir/com.foo.bar.baz/shared_prefs/settings.xml")
        assertEquals("com.foo.bar.baz", m.packageName)
        assertEquals(YlbEntryType.DATA, m.type)
        assertEquals("shared_prefs/settings.xml", m.relativeToPackage)
    }

    @Test
    fun `package name with underscore is recognized`() {
        val m = YlbPathMapper.map("app_foza_apks/com.foo_bar.baz/files/data")
        assertEquals("com.foo_bar.baz", m.packageName)
        assertEquals(YlbEntryType.DATA, m.type)
    }

    @Test
    fun `entry without package segment is unknown`() {
        val m = YlbPathMapper.map("random_dir/file.bin")
        assertNull(m.packageName)
        assertEquals(YlbEntryType.UNKNOWN, m.type)
        assertTrue(m.reason.isNotEmpty())
    }

    @Test
    fun `split entry without package segment is unknown and never dropped`() {
        val m = YlbPathMapper.map("app_split/base.apk")
        assertNull(m.packageName)
        assertEquals(YlbEntryType.UNKNOWN, m.type)
        // The original segments are preserved so the importer can stage the file for inspection.
        assertEquals("app_split/base.apk", m.segments.joinToString("/"))
    }

    @Test
    fun `file names that merely look like packages are not packages`() {
        assertNull(YlbPathMapper.map("lib/arm64-v8a/libwood.so").packageName)
        assertNull(YlbPathMapper.map("system/framework/classes.dex").packageName)
        assertNull(YlbPathMapper.map("res/drawable/icon.png").packageName)
    }

    @Test
    fun `absolute and traversal paths are unknown with no segments`() {
        val absolute = YlbPathMapper.map("/data/data/com.a.b/x")
        assertEquals(YlbEntryType.UNKNOWN, absolute.type)
        assertTrue(absolute.segments.isEmpty())

        val traversal = YlbPathMapper.map("app_foza_apks/com.a.b/../../../etc/passwd")
        assertEquals(YlbEntryType.UNKNOWN, traversal.type)
        assertTrue(traversal.segments.isEmpty())

        val empty = YlbPathMapper.map("")
        assertEquals(YlbEntryType.UNKNOWN, empty.type)
        assertTrue(empty.segments.isEmpty())
    }

    @Test
    fun `backslash paths are normalized`() {
        val m = YlbPathMapper.map("app_foza_apks\\com.a.b\\files\\x")
        assertEquals("com.a.b", m.packageName)
        assertEquals(YlbEntryType.DATA, m.type)
    }

    @Test
    fun `package detection helper agrees with mapping`() {
        assertEquals("com.a.b", YlbPathMapper.findPackageSegment("app_foza_apks/com.a.b/files/x"))
        assertNull(YlbPathMapper.findPackageSegment("no/packages/here/file.txt"))
    }
}
