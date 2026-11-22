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
package top.niunaijun.blackbox.backup.ylb.exception

import top.niunaijun.blackbox.backup.exception.BackupException

/**
 * Failure of the Meta Wolf (ylb) backup importer that is caused by the *input* being invalid
 * for the ylb import flow: not a Meta Wolf backup at all, an unsupported/corrupt container, an
 * invalid mnemonic, or entries that cannot be mapped onto the blackbox-backup v1 layout.
 *
 * Wrong mnemonic/password combinations (the archive opens for no derived key candidate) are
 * reported as [top.niunaijun.blackbox.backup.exception.WrongPasswordException] instead, so the
 * UI can distinguish "try again with your mnemonic" from "this file cannot be imported".
 */
class YlbFormatException(message: String, cause: Throwable? = null) : BackupException(message, cause)
