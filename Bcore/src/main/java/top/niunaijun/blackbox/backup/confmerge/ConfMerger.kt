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
package top.niunaijun.blackbox.backup.confmerge

import top.niunaijun.blackbox.backup.exception.BackupException

/**
 * Entry-level merger for configuration files (blackbox-backup v1).
 *
 * REAL conf format note (verified against engine sources):
 * Every system conf file managed by this fork (user.conf, accounts.conf, uid.conf,
 * shared-user.conf, fake-location.conf) is a BINARY Android Parcel file, NOT a text file:
 *  - user.conf         : Parcel.writeTypedList(ArrayList<BUserInfo>)     (BUserManagerService)
 *  - uid.conf          : Parcel.writeInt(mCurrUid) + Parcel.writeMap(pkg -> appId) (Settings)
 *  - accounts.conf     : Parcel of the account list                       (BAccountManagerService)
 *  - shared-user.conf  : Parcel of the SharedUserSetting map              (SharedUserSetting)
 *  - fake-location.conf: Parcel of the global FakeLocation config         (BLocationManagerService)
 *  - xposed-module.conf: declared in BEnvironment but never read/written (dead file in this fork)
 *
 * Per-application settings live in data/app/{pkg}/package.conf (BPackageSettings parcel) and
 * are therefore restored together with the application directory; uid.conf / shared-user.conf
 * entries are re-registered by the package manager service during installPackageAsUser().
 *
 * Because Parcel decoding is impossible on the JVM, the merger is format-pluggable:
 * [ConfCodec] implementations translate a conf file into an ordered key -> value map, and
 * [ConfMerger.merge] performs the actual entry-level merge ("backup wins for the restored
 * keys, everything else in the target file stays untouched - never a whole-file overwrite").
 *
 * Provided codecs:
 *  - [TextLineConfCodec] : line based "key=value" text confs (host prefs, future text confs).
 *  - [JsonMapConfCodec]  : flat JSON object confs (host_prefs.json v1 uses this).
 *  - [ParcelUidConfCodec]: device-only codec for the binary uid.conf format (Parcel).
 */
object ConfMerger {

    /** Statistics of a performed merge. */
    class MergeReport(
        val mergedBytes: ByteArray,
        /** Keys whose value was overwritten by the backup value (existing in both). */
        val replacedKeys: List<String>,
        /** Keys that only existed in the backup and were appended to the target. */
        val addedKeys: List<String>,
        /** Keys of the target that were intentionally left untouched. */
        val preservedKeys: List<String>,
    )

    /**
     * Entry-level merge: for every key in [keysToRestore] the BACKUP value wins; all other
     * entries of the target file are preserved as-is. Entries that only exist in the backup
     * among [keysToRestore] are appended.
     *
     * @param targetRaw  current bytes of the conf file on this device (may be empty/blank).
     * @param backupRaw  bytes of the conf file coming from the backup archive.
     * @param keysToRestore which entries to take from the backup (e.g. only the restored
     *        package's entries). Pass the full backup key set to restore all of them.
     */
    fun merge(
        targetRaw: ByteArray,
        backupRaw: ByteArray,
        keysToRestore: Collection<String>,
        codec: ConfCodec,
    ): MergeReport {
        val targetEntries = try {
            codec.decode(targetRaw)
        } catch (e: Exception) {
            throw BackupException("failed to decode target conf via ${codec.javaClass.simpleName}: ${e.message}", e)
        }
        val backupEntries = try {
            codec.decode(backupRaw)
        } catch (e: Exception) {
            throw BackupException("failed to decode backup conf via ${codec.javaClass.simpleName}: ${e.message}", e)
        }

        val replaced = ArrayList<String>()
        val added = ArrayList<String>()
        val restored = LinkedHashSet(keysToRestore)
        for (key in restored) {
            val value = backupEntries[key] ?: continue // key unknown to backup: keep target value
            if (targetEntries.containsKey(key)) {
                if (targetEntries[key] != value) replaced.add(key)
            } else {
                added.add(key)
            }
            targetEntries[key] = value
        }
        val restoredEffective = restored.filter { backupEntries.containsKey(it) }.toSet()
        val preserved = targetEntries.keys.filterNot { it in restoredEffective }
        val mergedBytes = codec.encode(targetEntries)
        return MergeReport(mergedBytes, replaced, added, preserved)
    }

    /** Convenience: restore every entry present in the backup conf. */
    fun restoreAll(targetRaw: ByteArray, backupRaw: ByteArray, codec: ConfCodec): MergeReport =
        merge(targetRaw, backupRaw, codec.decode(backupRaw).keys, codec)
}

/**
 * Translates a conf file between raw bytes and an ordered key -> value map. Implementations
 * must preserve entry order deterministically (LinkedHashMap semantics).
 */
interface ConfCodec {
    fun decode(raw: ByteArray): LinkedHashMap<String, String>
    fun encode(entries: Map<String, String>): ByteArray
}

/**
 * Codec for line-based text confs: one "key=value" entry per line; blank lines and '#'
 * comments are treated as decoration. Encoding writes entries in map order.
 */
class TextLineConfCodec : ConfCodec {

    override fun decode(raw: ByteArray): LinkedHashMap<String, String> {
        val result = LinkedHashMap<String, String>()
        String(raw, Charsets.UTF_8).lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
            val eq = trimmed.indexOf('=')
            if (eq <= 0) return@forEach // not a key=value entry, ignore
            val key = trimmed.substring(0, eq).trim()
            val value = trimmed.substring(eq + 1).trim()
            result[key] = value
        }
        return result
    }

    override fun encode(entries: Map<String, String>): ByteArray =
        buildString {
            entries.forEach { (key, value) ->
                append(key).append(" = ").append(value).append('\n')
            }
        }.toByteArray(Charsets.UTF_8)

    companion object {
        /**
         * Line-preserving merge for text confs: entries whose key is in [keysToRestore] are
         * overwritten in place with the backup value (comments and untouched lines keep their
         * exact original text); backup-only keys are appended at the end.
         */
        fun mergeText(targetText: String, backupText: String, keysToRestore: Collection<String>): String {
            val backupEntries = TextLineConfCodec().decode(backupText.toByteArray(Charsets.UTF_8))
            val wanted = keysToRestore.toSet()
            // Only apply the backup when it actually carries at least one selected key:
            // a backup that lacks the requested entries must not touch the target at all.
            val backupMatches = wanted.any { it in backupEntries }
            val seen = HashSet<String>()
            val appended = ArrayList<String>()
            val out = StringBuilder(targetText.length + 64)
            targetText.lineSequence().forEach { line ->
                val trimmed = line.trim()
                var handled = false
                if (backupMatches && trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    val eq = trimmed.indexOf('=')
                    if (eq > 0) {
                        val key = trimmed.substring(0, eq).trim()
                        // Every key present in the target is marked as seen so the append
                        // pass below never duplicates a line that is already in the file.
                        seen.add(key)
                        if (key in wanted && backupEntries.containsKey(key)) {
                            out.append(key).append(" = ").append(backupEntries[key]).append('\n')
                            handled = true
                        }
                    }
                }
                if (!handled) {
                    out.append(line).append('\n')
                }
            }
            // When the backup matches, its remaining entries are appended in backup order
            // (restore adds everything the backup carries; target-only lines stay verbatim).
            if (backupMatches) {
                for ((key, value) in backupEntries) {
                    if (seen.add(key)) {
                        appended.add("$key = $value")
                    }
                }
                if (appended.isNotEmpty()) {
                    out.append('\n').append("# entries restored from backup").append('\n')
                    appended.forEach { out.append(it).append('\n') }
                }
            }
            return out.toString().trimEnd('\n') + "\n"
        }
    }
}

/**
 * Codec for flat JSON object confs, e.g. host_prefs.json:
 * { "sort:com.foo": "1", "note:com.bar": "hello", "frozen:com.baz": "true" }.
 */
class JsonMapConfCodec : ConfCodec {

    override fun decode(raw: ByteArray): LinkedHashMap<String, String> {
        val text = String(raw, Charsets.UTF_8).trim()
        val result = LinkedHashMap<String, String>()
        if (text.isEmpty()) return result
        val root = com.google.gson.JsonParser.parseString(text)
        if (!root.isJsonObject) throw BackupException("expected a JSON object conf")
        for ((key, value) in root.asJsonObject.entrySet()) {
            result[key] = when {
                value == null || value.isJsonNull -> ""
                value.isJsonPrimitive -> value.asJsonPrimitive.asString
                else -> value.toString()
            }
        }
        return result
    }

    override fun encode(entries: Map<String, String>): ByteArray {
        val obj = com.google.gson.JsonObject()
        entries.forEach { (key, value) -> obj.addProperty(key, value) }
        return obj.toString().toByteArray(Charsets.UTF_8)
    }
}

/**
 * Device-only codec for the REAL binary format of uid.conf
 * (Parcel.writeInt(mCurrUid) + Parcel.writeMap(pkg -> appId)).
 *
 * Requires android.os.Parcel, therefore it cannot run (and is not exercised) in JVM unit
 * tests; it exists so the engine can do a true entry-level merge of uid.conf on device if a
 * future phase decides to override the install-time re-registration strategy. The current
 * BackupManager restore flow deliberately does NOT overwrite uid.conf (KEEP_HOST) because
 * installPackageAsUser already registers consistent entries.
 *
 * The counter is transported through the reserved key [CURR_UID_KEY].
 */
class ParcelUidConfCodec : ConfCodec {

    override fun decode(raw: ByteArray): LinkedHashMap<String, String> {
        val parcel = android.os.Parcel.obtain()
        return try {
            parcel.unmarshall(raw, 0, raw.size)
            parcel.setDataPosition(0)
            val currUid = parcel.readInt()
            val map = parcel.readHashMap(HashMap::class.java.classLoader)
            val result = LinkedHashMap<String, String>()
            result[CURR_UID_KEY] = currUid.toString()
            map?.forEach { (k, v) -> if (k is String && v != null) result[k] = v.toString() }
            result
        } finally {
            parcel.recycle()
        }
    }

    override fun encode(entries: Map<String, String>): ByteArray {
        val parcel = android.os.Parcel.obtain()
        return try {
            val currUid = entries[CURR_UID_KEY]?.toIntOrNull() ?: 0
            parcel.writeInt(currUid)
            val map = LinkedHashMap<String, Int>()
            entries.forEach { (k, v) -> if (k != CURR_UID_KEY) map[k] = v.toIntOrNull() ?: 0 }
            parcel.writeMap(map)
            parcel.marshall()
        } finally {
            parcel.recycle()
        }
    }

    companion object {
        const val CURR_UID_KEY = "#curr_uid"
    }
}
