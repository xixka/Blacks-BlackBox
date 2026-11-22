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

import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.EncryptionMethod
import top.niunaijun.blackbox.backup.BackupArchive
import top.niunaijun.blackbox.backup.BackupManifest
import top.niunaijun.blackbox.backup.BackupManifestCodec
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Shared fixtures for the pure-JVM Meta Wolf importer tests.
 * IMPORTANT: no android.* imports anywhere in the test sources.
 *
 * The "fixed mnemonic" is the standard BIP39 test vector mnemonic
 * (entropy 0x000...000). Its derived m/44'/60'/0'/0/0 private key hex is used as the
 * Meta Wolf zip password when building sample archives; TC-U-09 independently pins that
 * derivation to the official BIP39/BIP32 specification vectors.
 */
object YlbTestSupport {

    /** Official BIP39 test vector mnemonic (entropy 000...000). */
    const val FIXED_MNEMONIC =
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    /** Another valid official BIP39 mnemonic (vector 2) - never matches the fixed one. */
    const val OTHER_VALID_MNEMONIC =
        "legal winner thank year wave sausage worth useful legal winner thank yellow"

    /** m/44'/60'/0'/0/0 private key of FIXED_MNEMONIC with empty passphrase (TC-U-09 pinned). */
    const val FIXED_MNEMONIC_KEY_HEX = "1ab42cc412b618bdea3a599e3c9bae199ebf030895b039e9db1e30dafb12b727"

    /** Package name used in the sample archives. */
    const val SAMPLE_PKG = "com.test.app"

    /** Sample payload names/contents (Meta Wolf dataDir relative paths). */
    val SAMPLE_ENTRY_FILES_1_TXT = "app_foza_apks/$SAMPLE_PKG/files/1.txt" to "hello ylb".toByteArray()
    val SAMPLE_ENTRY_HELLO_TXT = "app_foza_apks/$SAMPLE_PKG/files/hello.txt" to "hello virtual world".toByteArray()
    val SAMPLE_ENTRY_APP_DB = "app_foza_apks/$SAMPLE_PKG/databases/app.db" to ByteArray(64) { it.toByte() }
    val SAMPLE_ENTRY_FOZA_BASE_APK = "app_foza_apks/$SAMPLE_PKG/base.apk" to ByteArray(2048) { (it % 251).toByte() }
    val SAMPLE_ENTRY_SPLIT_BASE_APK = "app_split/$SAMPLE_PKG/base.apk" to ByteArray(4096) { (it % 7).toByte() }
    val SAMPLE_ENTRY_SPLIT_CONFIG =
        "app_split/$SAMPLE_PKG/split_config.arm64_v8a.apk" to ByteArray(512) { (it * 3).toByte() }
    val SAMPLE_ENTRY_ORPHAN = "logs/orphan.bin" to "orphan".toByteArray()

    /** Standard sample archive used by the sniff/parse/convert tests. */
    fun standardSampleEntries(): List<Pair<String, ByteArray>> = listOf(
        SAMPLE_ENTRY_FILES_1_TXT,
        SAMPLE_ENTRY_HELLO_TXT,
        SAMPLE_ENTRY_APP_DB,
        SAMPLE_ENTRY_FOZA_BASE_APK,
        SAMPLE_ENTRY_SPLIT_BASE_APK,
        SAMPLE_ENTRY_SPLIT_CONFIG,
        SAMPLE_ENTRY_ORPHAN,
    )

    // ------------------------------------------------------------------ key helpers

    /** m/44'/60'/0'/0/0 private key hex (padded to 64) for [mnemonic] with empty passphrase. */
    fun derivedKeyHex(mnemonic: String): String {
        val seed = Bip39.mnemonicToSeed(mnemonic.toCharArray(), CharArray(0))
        val master = Bip32.masterFromSeed(seed)!!
        val node = Bip32.ckdPriv(master, 44, hardened = true)!!
        val node2 = Bip32.ckdPriv(node, 60, hardened = true)!!
        val node3 = Bip32.ckdPriv(node2, 0, hardened = true)!!
        val node4 = Bip32.ckdPriv(node3, 0, hardened = false)!!
        val node5 = Bip32.ckdPriv(node4, 0, hardened = false)!!
        return node5.key.toString(16).padStart(64, '0')
    }

    /** The zip password a Meta Wolf build would use for FIXED_MNEMONIC (private key hex). */
    fun fixedSamplePassword(): CharArray = derivedKeyHex(FIXED_MNEMONIC).toCharArray()

    // ------------------------------------------------------------------ zip builders

    /** Builds a WinZip AES encrypted zip the way Meta Wolf (zip4j) does. */
    fun createAesZip(
        dir: File,
        name: String,
        password: CharArray,
        entries: List<Pair<String, ByteArray>>,
    ): File {
        val dest = File(dir, name)
        ZipFile(dest, password).use { zf ->
            for ((entryName, bytes) in entries) {
                val params = net.lingala.zip4j.model.ZipParameters()
                params.isEncryptFiles = true
                params.encryptionMethod = EncryptionMethod.AES
                params.aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                params.fileNameInZip = entryName
                zf.addStream(ByteArrayInputStream(bytes), params)
            }
        }
        return dest
    }

    /** Builds a plaintext blackbox-backup v1 style archive (manifest.json + manifest.sha256). */
    fun createV1StyleZip(dir: File, name: String = "v1_backup.zip"): File {
        val manifest = BackupManifest(
            app = BackupManifest.APP_NAME,
            apps = listOf(
                top.niunaijun.blackbox.backup.ManifestApp(
                    packageName = SAMPLE_PKG,
                    userId = 0,
                    versionName = "1.0",
                    versionCode = 1,
                    signingCertSha256 = null,
                    includeApk = false,
                    excludeCache = true,
                    possiblyInconsistent = false,
                    sizeBytes = 10,
                    fileCount = 1,
                ),
            ),
        )
        val manifestBytes = BackupManifestCodec.serialize(manifest)
        val dest = File(dir, name)
        ZipOutputStream(dest.outputStream().buffered(BackupArchive.COPY_BUFFER_SIZE)).use { zos ->
            BackupArchive.writeBytesEntry(zos, BackupArchive.MANIFEST_ENTRY, manifestBytes, null)
            BackupArchive.writeBytesEntry(
                zos,
                BackupArchive.MANIFEST_SHA256_ENTRY,
                BackupManifestCodec.sha256Hex(manifestBytes).toByteArray(Charsets.UTF_8),
                null,
            )
        }
        return dest
    }

    /** Builds a plain (unencrypted, unstructured) zip. */
    fun createPlainZip(dir: File, name: String = "plain.zip"): File {
        val dest = File(dir, name)
        ZipOutputStream(dest.outputStream().buffered()).use { zos ->
            zos.putNextEntry(ZipEntry("some/random/content.txt"))
            zos.write("nothing to see here".toByteArray())
            zos.closeEntry()
        }
        return dest
    }

    /** Builds a WinZip AES zip that has no Meta Wolf path shapes at all. */
    fun createEncryptedForeignZip(dir: File, name: String = "foreign.zip"): File =
        createAesZip(
            dir = dir,
            name = name,
            password = "not-a-wallet".toCharArray(),
            entries = listOf("pictures/cat.png" to "cat".toByteArray()),
        )
}
