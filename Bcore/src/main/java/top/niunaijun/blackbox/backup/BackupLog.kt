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

/**
 * Small logging facade for the backup engine.
 *
 * The engine core (BackupEngine / BackupArchive / BackupCrypto / ConfMerger) must stay free of
 * Android runtime dependencies so it can be unit tested on the JVM; therefore it logs through
 * this pluggable sink instead of android.util.Log (or Slog).
 *
 * [BackupManager] installs a sink backed by Slog, so on a real device everything shows up in
 * logcat with the conventional TAGs: "Backup" and "Restore".
 */
object BackupLog {
    const val TAG_BACKUP = "Backup"
    const val TAG_RESTORE = "Restore"

    /** Priority constants, aligned with android.util.Log so sinks can map them directly. */
    const val PRIORITY_DEBUG = 3
    const val PRIORITY_INFO = 4
    const val PRIORITY_WARN = 5
    const val PRIORITY_ERROR = 6

    interface Sink {
        fun log(tag: String, priority: Int, message: String)
    }

    @Volatile
    var sink: Sink = DefaultSink

    fun d(tag: String, message: String) = dispatch(tag, PRIORITY_DEBUG, message)

    fun i(tag: String, message: String) = dispatch(tag, PRIORITY_INFO, message)

    fun w(tag: String, message: String) = dispatch(tag, PRIORITY_WARN, message)

    fun e(tag: String, message: String) = dispatch(tag, PRIORITY_ERROR, message)

    private fun dispatch(tag: String, priority: Int, message: String) {
        try {
            sink.log(tag, priority, message)
        } catch (ignored: Throwable) {
            // Logging must never break the backup flow. This is the only place where an
            // exception is deliberately ignored, and it is a logger failure, not engine state.
        }
    }

    private object DefaultSink : Sink {
        override fun log(tag: String, priority: Int, message: String) {
            val level = when (priority) {
                PRIORITY_DEBUG -> "DEBUG"
                PRIORITY_INFO -> "INFO"
                PRIORITY_WARN -> "WARN"
                PRIORITY_ERROR -> "ERROR"
                else -> "LOG"
            }
            System.err.println("[BackupLog/$tag/$level] $message")
        }
    }
}
