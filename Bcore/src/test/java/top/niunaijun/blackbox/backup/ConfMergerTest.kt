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
package top.niunaijun.blackbox.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.niunaijun.blackbox.backup.confmerge.ConfMerger
import top.niunaijun.blackbox.backup.confmerge.JsonMapConfCodec
import top.niunaijun.blackbox.backup.confmerge.TextLineConfCodec

/**
 * TC-U-13: entry-level conf merge. The REAL system conf format in this engine is binary
 * Parcel (see ConfMerger KDoc), so the merge semantics are validated on the pluggable codecs:
 * the line-based text codec (string-level assertions, other spaces / packages untouched) and
 * the JSON map codec used for host_prefs.json.
 */
class ConfMergerTest {

    // ---------------------------------------------------------------- TC-U-13 (text)

    @Test
    fun textConfMergeOverridesOnlyTheSelectedEntries() {
        val target = """
            # user conf entries (sample in the documented key=value text form)
            space.workspace.0.name = Main Space
            pkg.com.foo.appid = 10042
            pkg.com.bar.appid = 10043
            pkg.com.bar.flags = multiuser
        """.trimIndent() + "\n"

        val backup = """
            # conf snapshot taken on the old device
            pkg.com.foo.appid = 10044
            pkg.com.new.appid = 10045
        """.trimIndent() + "\n"

        val merged = TextLineConfCodec.mergeText(target, backup, setOf("pkg.com.foo.appid"))

        // Backup wins for the restored entry...
        assertTrue(merged.contains("pkg.com.foo.appid = 10044"))
        assertFalse(merged.contains("pkg.com.foo.appid = 10042"))
        // ...every other target entry is preserved verbatim...
        assertTrue(merged.contains("space.workspace.0.name = Main Space"))
        assertTrue(merged.contains("pkg.com.bar.appid = 10043"))
        assertTrue(merged.contains("pkg.com.bar.flags = multiuser"))
        // ...comments survive...
        assertTrue(merged.contains("# user conf entries (sample in the documented key=value text form)"))
        // ...and backup-only entries are appended (restore adds everything the backup carries).
        assertTrue(merged.contains("pkg.com.new.appid = 10045"))
    }

    @Test
    fun textConfMergeIsIdempotentWhenAppliedTwice() {
        val target = "pkg.com.foo.appid = 10042" + "\n" + "keep.me = yes" + "\n"
        val backup =
                "pkg.com.foo.appid = 10044" + "\n" + "pkg.com.new.appid = 10045" + "\n"
        val once = TextLineConfCodec.mergeText(target, backup, setOf("pkg.com.foo.appid"))
        val twice = TextLineConfCodec.mergeText(once, backup, setOf("pkg.com.foo.appid"))
        assertEquals(once, twice)
    }

    @Test
    fun textConfMergeOfUnknownKeyDoesNothing() {
        val target = "pkg.com.foo.appid = 10042\npkg.com.bar.appid = 10043\n"
        val backup = "pkg.com.other.appid = 99999\n"
        val merged = TextLineConfCodec.mergeText(target, backup, setOf("pkg.com.missing.appid"))
        // The key is not carried by the backup: target stays untouched.
        assertEquals("pkg.com.foo.appid = 10042\npkg.com.bar.appid = 10043\n", merged)
    }

    @Test
    fun textConfMergeAllBackupKeysWhenRequested() {
        val target = "pkg.com.foo.appid = 10042\nkeep.me = yes\n"
        val backup = "pkg.com.foo.appid = 77777\npkg.com.new.appid = 88888\n"
        val merged = TextLineConfCodec.mergeText(
            target, backup,
            TextLineConfCodec().decode(backup.toByteArray(Charsets.UTF_8)).keys,
        )
        assertTrue(merged.contains("pkg.com.foo.appid = 77777"))
        assertTrue(merged.contains("pkg.com.new.appid = 88888"))
        assertTrue(merged.contains("keep.me = yes"))
        assertFalse(merged.contains("10042"))
    }

    @Test
    fun codecMergeReportsReplacedAddedAndPreserved() {
        val codec = TextLineConfCodec()
        val target = "a = 1\nb = 2\nc = 3".toByteArray(Charsets.UTF_8)
        val backup = "a = 10\nd = 40".toByteArray(Charsets.UTF_8)
        val report = ConfMerger.merge(target, backup, listOf("a", "d"), codec)

        assertEquals(listOf("a"), report.replacedKeys)
        assertEquals(listOf("d"), report.addedKeys)
        assertTrue(report.preservedKeys.contains("b"))
        assertTrue(report.preservedKeys.contains("c"))
        val mergedText = String(report.mergedBytes, Charsets.UTF_8)
        assertTrue(mergedText.contains("a = 10"))
        assertTrue(mergedText.contains("b = 2"))
        assertTrue(mergedText.contains("c = 3"))
        assertTrue(mergedText.contains("d = 40"))
    }

    // ---------------------------------------------------------------- TC-U-13 (json / host prefs)

    @Test
    fun jsonMapConfMergeForHostPrefsKeepsForeignEntries() {
        val codec = JsonMapConfCodec()
        val target = """
            {"sort:com.foo": "1", "note:com.foo": "stays", "frozen:com.bar": "true"}
        """.trimIndent().toByteArray(Charsets.UTF_8)
        val backup = """
            {"sort:com.foo": "5", "note:com.newpkg": "from backup"}
        """.trimIndent().toByteArray(Charsets.UTF_8)

        val report = ConfMerger.restoreAll(target, backup, codec)
        val merged = String(report.mergedBytes, Charsets.UTF_8)

        // backup wins for its keys
        assertTrue(merged.contains("\"sort:com.foo\":\"5\""))
        assertTrue(merged.contains("\"note:com.newpkg\":\"from backup\""))
        // device-only entries preserved
        assertTrue(merged.contains("\"note:com.foo\":\"stays\""))
        assertTrue(merged.contains("\"frozen:com.bar\":\"true\""))
        assertEquals(listOf("sort:com.foo"), report.replacedKeys)
        assertEquals(listOf("note:com.newpkg"), report.addedKeys)
    }

    @Test
    fun jsonMapCodecRoundtrip() {
        val codec = JsonMapConfCodec()
        val original = mapOf("a" to "1", "b" to "中文值", "c" to "")
        val restored = codec.decode(codec.encode(original))
        assertEquals(original, restored.toMap())
    }
}
