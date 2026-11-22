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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TC-U-11: pure aggregation of the single-page app list (all virtual spaces -> one list).
 * These tests are pure JVM: no android.* classes are touched.
 */
class AppsAggregationTest {

    @Test
    fun `aggregate two spaces with two apps each yields four entries with correct userIds`() {
        val users = listOf(0, 1)
        val perUserApps = mapOf(
                0 to listOf("com.a.alpha", "com.a.beta"),
                1 to listOf("com.b.gamma", "com.b.delta")
        )

        val result = AppsSortKeys.aggregateApps(users, perUserApps)

        assertEquals(4, result.size)
        assertEquals(
                listOf(
                        "com.a.alpha" to 0,
                        "com.a.beta" to 0,
                        "com.b.gamma" to 1,
                        "com.b.delta" to 1
                ),
                result.map { it.packageName to it.userId }
        )
    }

    @Test
    fun `aggregate keeps user order and blank packages are skipped`() {
        val users = listOf(2, 0)
        val perUserApps = mapOf(
                0 to listOf("com.only.zero"),
                2 to listOf("com.two.first", "", "   ", "com.two.second")
        )

        val result = AppsSortKeys.aggregateApps(users, perUserApps)

        assertEquals(3, result.size)
        assertEquals("com.two.first", result[0].packageName)
        assertEquals(2, result[0].userId)
        assertEquals("com.two.second", result[1].packageName)
        assertEquals(2, result[1].userId)
        assertEquals("com.only.zero", result[2].packageName)
        assertEquals(0, result[2].userId)
    }

    @Test
    fun `aggregate with missing user entry yields no entries for that user`() {
        val users = listOf(0, 5)
        val perUserApps = mapOf(0 to listOf("com.a"))

        val result = AppsSortKeys.aggregateApps(users, perUserApps)

        assertEquals(1, result.size)
        assertEquals(0, result[0].userId)
    }

    @Test
    fun `same package in two spaces produces two distinct entries`() {
        val users = listOf(0, 1)
        val perUserApps = mapOf(
                0 to listOf("com.same.app"),
                1 to listOf("com.same.app")
        )

        val result = AppsSortKeys.aggregateApps(users, perUserApps)

        assertEquals(2, result.size)
        assertTrue(result.any { it.packageName == "com.same.app" && it.userId == 0 })
        assertTrue(result.any { it.packageName == "com.same.app" && it.userId == 1 })
    }

    @Test
    fun `sortAllApps puts sorted entries first and keeps unknown entries in input order`() {
        val apps = listOf(
                AllAppEntry("com.new.one", 0, 0),
                AllAppEntry("com.pinned.second", 0, 1),
                AllAppEntry("com.new.two", 1, 2),
                AllAppEntry("com.pinned.first", 1, 3)
        )
        val sortEntries = listOf(
                AppSortEntry("com.pinned.first", 1),
                AppSortEntry("com.pinned.second", 0)
        )

        val sorted = AppsSortKeys.sortAllApps(apps, sortEntries)

        assertEquals(
                listOf(
                        "com.pinned.first" to 1,
                        "com.pinned.second" to 0,
                        "com.new.one" to 0,
                        "com.new.two" to 1
                ),
                sorted.map { it.packageName to it.userId }
        )
    }

    @Test
    fun `sortAllApps with same package in different spaces matches exact userId pair`() {
        val apps = listOf(
                AllAppEntry("com.same.app", 0, 0),
                AllAppEntry("com.same.app", 1, 1)
        )
        // Only the space-1 copy is pinned: it must move to the front while the space-0
        // copy keeps its (unpinned) input position right after it.
        val sortEntries = listOf(AppSortEntry("com.same.app", 1))

        val sorted = AppsSortKeys.sortAllApps(apps, sortEntries)

        assertEquals(1, sorted[0].userId)
        assertEquals(0, sorted[1].userId)
    }
}
