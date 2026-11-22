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

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Generic key-value structure for host-side per-application preferences that ride along in
 * the backup archive (host_prefs.json). v1 defines well-known key families for sorting,
 * notes and freeze flags; the app layer (later phase) may add arbitrary additional keys.
 *
 * On-disk shape: a flat JSON object mapping key -> string value:
 *   { "sort:com.foo.bar": "1", "note:com.foo.bar": "my note", "frozen:com.foo.bar": "true" }
 */
data class HostPrefs(val entries: Map<String, String> = LinkedHashMap()) {

    fun value(key: String): String? = entries[key]

    companion object {
        const val KEY_SORT_PREFIX = "sort:"
        const val KEY_NOTE_PREFIX = "note:"
        const val KEY_FROZEN_PREFIX = "frozen:"

        fun sortKey(packageName: String) = KEY_SORT_PREFIX + packageName
        fun noteKey(packageName: String) = KEY_NOTE_PREFIX + packageName
        fun frozenKey(packageName: String) = KEY_FROZEN_PREFIX + packageName

        private val gson = Gson()
        private val mapType = object : TypeToken<Map<String, String>>() {}.type

        fun serialize(prefs: HostPrefs): ByteArray =
            gson.toJson(prefs.entries, mapType).toByteArray(Charsets.UTF_8)

        fun deserialize(bytes: ByteArray): HostPrefs {
            val text = String(bytes, Charsets.UTF_8).trim()
            if (text.isEmpty()) return HostPrefs(emptyMap())
            val map: Map<String, String> = gson.fromJson(text, mapType) ?: emptyMap()
            return HostPrefs(map)
        }
    }
}
