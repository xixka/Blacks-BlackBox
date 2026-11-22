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

package com.onebitmonochrome.blacksbbox.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TC-U-12: sort key persistence, legacy migration and key disambiguation.
 * Pure JVM tests: no android.* classes are touched.
 */
class AppsSortKeysTest {

    // --- parseSortKey / parseSortKeys ---

    @Test
    fun `parseSortKey parses pkg@uid`() {
        val entry = AppsSortKeys.parseSortKey("com.example.app@3")
        assertEquals(AppSortEntry("com.example.app", 3), entry)
    }

    @Test
    fun `parseSortKey rejects malformed entries`() {
        assertNull(AppsSortKeys.parseSortKey(""))
        assertNull(AppsSortKeys.parseSortKey("   "))
        assertNull(AppsSortKeys.parseSortKey("com.example.app"))
        assertNull(AppsSortKeys.parseSortKey("@3"))
        assertNull(AppsSortKeys.parseSortKey("com.example.app@"))
        assertNull(AppsSortKeys.parseSortKey("com.example.app@notanumber"))
    }

    @Test
    fun `parseSortKeys dedupes and keeps order`() {
        val entries = AppsSortKeys.parseSortKeys("com.a@0,com.b@1,com.a@0")

        assertEquals(2, entries.size)
        assertEquals(AppSortEntry("com.a", 0), entries[0])
        assertEquals(AppSortEntry("com.b", 1), entries[1])
    }

    @Test
    fun `parseSortKeys ignores malformed fragments`() {
        val entries = AppsSortKeys.parseSortKeys("com.a@0,,junk,com.b@1,")

        assertEquals(2, entries.size)
        assertEquals(AppSortEntry("com.a", 0), entries[0])
        assertEquals(AppSortEntry("com.b", 1), entries[1])
    }

    @Test
    fun `dash and underscore package keys do not clash thanks to userId suffix`() {
        val entries = AppsSortKeys.parseSortKeys("com.a.b-c@0,com.a.b_c@1")

        assertEquals(2, entries.size)
        assertEquals(AppSortEntry("com.a.b-c", 0), entries[0])
        assertEquals(AppSortEntry("com.a.b_c", 1), entries[1])

        // round-trip keeps them distinct
        val raw = AppsSortKeys.serializeSortKeys(entries)
        assertEquals("com.a.b-c@0,com.a.b_c@1", raw)
    }

    @Test
    fun `serialize and parse round-trip is lossless`() {
        val entries = listOf(
                AppSortEntry("com.first.app", 0),
                AppSortEntry("com.second.app", 2),
                AppSortEntry("com.third.app", 10)
        )

        val raw = AppsSortKeys.serializeSortKeys(entries)
        assertEquals("com.first.app@0,com.second.app@2,com.third.app@10", raw)
        assertEquals(entries, AppsSortKeys.parseSortKeys(raw))
    }

    // --- migrateLegacySortKeys ---

    @Test
    fun `legacy bare pkg lists migrate to pkg@uid entries`() {
        val migrated = AppsSortKeys.migrateLegacySortKeys(
                mapOf(
                        0 to "com.legacy.one,com.legacy.two",
                        1 to "com.legacy.three"
                )
        )

        assertEquals(
                listOf(
                        AppSortEntry("com.legacy.one", 0),
                        AppSortEntry("com.legacy.two", 0),
                        AppSortEntry("com.legacy.three", 1)
                ),
                migrated
        )
    }

    @Test
    fun `migration processes users in ascending id order and dedupes`() {
        val migrated = AppsSortKeys.migrateLegacySortKeys(
                mapOf(
                        5 to "com.app.from5",
                        0 to "com.app.from0,com.app.from5"
                )
        )

        assertEquals(
                listOf(
                        AppSortEntry("com.app.from0", 0),
                        AppSortEntry("com.app.from5", 0),
                        AppSortEntry("com.app.from5", 5)
                ),
                migrated
        )
    }

    @Test
    fun `migration skips blanks and trims packages`() {
        val migrated = AppsSortKeys.migrateLegacySortKeys(mapOf(0 to " com.a ,  com.b ,,,"))

        assertEquals(
                listOf(AppSortEntry("com.a", 0), AppSortEntry("com.b", 0)),
                migrated
        )
    }

    @Test
    fun `migration with empty legacy map yields empty list`() {
        assertTrue(AppsSortKeys.migrateLegacySortKeys(emptyMap()).isEmpty())
    }

    @Test
    fun `migrated entries serialize and parse back to the same entries`() {
        val migrated = AppsSortKeys.migrateLegacySortKeys(mapOf(0 to "com.a,com.b", 3 to "com.c"))

        val raw = AppsSortKeys.serializeSortKeys(migrated)
        assertEquals("com.a@0,com.b@0,com.c@3", raw)
        assertEquals(migrated, AppsSortKeys.parseSortKeys(raw))
    }

    // --- updateSortEntries ---

    @Test
    fun `add appends a pkg@uid entry without touching other spaces`() {
        val updated = AppsSortKeys.updateSortEntries(
                listOf(AppSortEntry("com.a", 0)),
                "com.b",
                1,
                true
        )

        assertEquals(
                listOf(AppSortEntry("com.a", 0), AppSortEntry("com.b", 1)),
                updated
        )
    }

    @Test
    fun `remove only drops the exact pkg@uid pair`() {
        val updated = AppsSortKeys.updateSortEntries(
                listOf(AppSortEntry("com.a", 0), AppSortEntry("com.a", 1)),
                "com.a",
                1,
                false
        )

        assertEquals(listOf(AppSortEntry("com.a", 0)), updated)
    }

    @Test
    fun `re-adding an existing entry moves it to the end`() {
        val updated = AppsSortKeys.updateSortEntries(
                listOf(AppSortEntry("com.a", 0), AppSortEntry("com.b", 0)),
                "com.a",
                0,
                true
        )

        assertEquals(
                listOf(AppSortEntry("com.b", 0), AppSortEntry("com.a", 0)),
                updated
        )
    }

    // --- key naming ---

    @Test
    fun `global and legacy keys follow the documented naming`() {
        assertEquals("AppListAll", AppsSortKeys.GLOBAL_SORT_KEY)
        assertEquals("AppList0", AppsSortKeys.legacySortKeyFor(0))
        assertEquals("AppList12", AppsSortKeys.legacySortKeyFor(12))
        assertFalse(AppsSortKeys.GLOBAL_SORT_KEY == AppsSortKeys.legacySortKeyFor(0))
    }

    @Test
    fun `migration and serialization are idempotent`() {
        val legacy = mapOf(0 to "com.a,com.b", 1 to "com.a,com.c", 3 to " , com.d ,")
        val first = AppsSortKeys.migrateLegacySortKeys(legacy)
        val second = AppsSortKeys.migrateLegacySortKeys(legacy)
        assertEquals(first, second)

        val serialized = AppsSortKeys.serializeSortKeys(first)
        val reparsed = AppsSortKeys.parseSortKeys(serialized)
        assertEquals(serialized, AppsSortKeys.serializeSortKeys(reparsed))
    }
}
