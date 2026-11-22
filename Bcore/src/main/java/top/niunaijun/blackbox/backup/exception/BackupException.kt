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
package top.niunaijun.blackbox.backup.exception

/**
 * Base class of every failure that the backup engine reports. All exception paths of the
 * backup/restore flows must surface as one of these subclasses (never silently swallowed).
 */
open class BackupException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Thrown when the volume holding the virtual root does not have enough free space. */
class InsufficientSpaceException(message: String) : BackupException(message)

/** Thrown when an encrypted entry cannot be decrypted (wrong password or corrupted payload). */
class WrongPasswordException(message: String, cause: Throwable? = null) : BackupException(message, cause)

/** Thrown when a backup archive declares a format_version newer (or invalid vs) than supported. */
class UnsupportedFormatException(message: String) : BackupException(message)

/** Thrown when integrity verification fails (manifest.sha256 mismatch, malformed manifest, bad entry magic). */
class VerifyException(message: String, cause: Throwable? = null) : BackupException(message, cause)

/** Thrown when an APK required for restore is missing from the archive or from the device. */
class MissingApkException(message: String) : BackupException(message)
