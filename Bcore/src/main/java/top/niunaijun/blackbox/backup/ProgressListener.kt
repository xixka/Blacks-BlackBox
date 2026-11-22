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
 * Progress callback during [BackupEngine.backup].
 *
 * Threading: [BackupEngine] invokes listeners synchronously on the thread that runs the
 * operation; [BackupManager] guarantees listeners are called on the MAIN thread (see its
 * documentation).
 *
 * @param current number of completed applications (0..total)
 * @param total   total number of selected applications
 * @param stage   machine readable stage label, e.g. "prepare", "pack:com.foo.bar", "finalize"
 */
fun interface OnBackupProgressListener {
    fun onProgress(current: Int, total: Int, stage: String)
}

/**
 * Progress callback during [BackupEngine.restore].
 *
 * Same threading contract as [OnBackupProgressListener].
 *
 * @param current number of processed applications (0..total)
 * @param total   total number of applications contained in the backup manifest
 * @param stage   machine readable stage label, e.g. "prepare", "install:com.foo.bar",
 *                "unpack:com.foo.bar", "symlinks", "done"
 */
fun interface OnRestoreProgressListener {
    fun onProgress(current: Int, total: Int, stage: String)
}
