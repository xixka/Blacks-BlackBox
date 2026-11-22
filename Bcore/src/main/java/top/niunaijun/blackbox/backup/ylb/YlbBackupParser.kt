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
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.model.FileHeader
import net.lingala.zip4j.model.enums.EncryptionMethod
import top.niunaijun.blackbox.backup.BackupArchive
import top.niunaijun.blackbox.backup.BackupLog
import top.niunaijun.blackbox.backup.BackupManifest
import top.niunaijun.blackbox.backup.BackupManifestCodec
import top.niunaijun.blackbox.backup.EntryInfo
import top.niunaijun.blackbox.backup.ManifestApp
import top.niunaijun.blackbox.backup.exception.BackupException
import top.niunaijun.blackbox.backup.exception.WrongPasswordException
import top.niunaijun.blackbox.backup.ylb.YlbPathMapper.YlbEntryType
import top.niunaijun.blackbox.backup.ylb.exception.YlbFormatException
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Arrays
import java.util.LinkedHashMap
import java.util.zip.ZipOutputStream

/**
 * Importer for Meta Wolf ("ylb", Mundo) backups: decrypts the WinZip AES container with the
 * identity wallet private key, extracts the entries, and converts them into a
 * blackbox-backup v1 archive that the regular restore pipeline
 * ([top.niunaijun.blackbox.backup.BackupManager.restore]) can apply to the target space.
 *
 * FORMAT FACTS (docs/ylb-format/RESEARCH.md section 8, verified against V1.0.29):
 *  - F1: the backup is a zip4j WinZip AES encrypted zip (AES-128/192/256, PBKDF2-HMAC-SHA1,
 *    entry level encryption). The zip4j library reads it directly.
 *  - F3: the zip password is the hex string of the identity wallet private key
 *    (ECKeyPair.privateKey.toString(16)); BigInteger hex can drop leading zeros, so three
 *    string forms are tried (raw / padded to 64 / padded to 66).
 *  - F4/F9: the private key comes from the standard BIP39 mnemonic (+ optional passphrase)
 *    through a BIP44 style derivation path; the concrete path is unknown, so a candidate
 *    sequence (m/44'/60'/0'/0/0, slot variants, master fallback) is tried and the WinZip AES
 *    password verifier rejects wrong candidates in milliseconds without false positives.
 *  - F6: zip entry paths are relative to the Meta Wolf host dataDir; they are mapped onto the
 *    v1 layout by [YlbPathMapper].
 *
 * THREADING CONTRACT (same style as BackupManager):
 *  - [sniff], [parse] and [convertToV1] are synchronous, CPU/IO bound, and safe to call from
 *    any single thread; the object holds no mutable state. UI layers must call them from a
 *    background dispatcher (they block: parse may try up to 60 key candidates).
 *  - No Android dependencies; pure JVM, unit tested.
 *
 * FAILURE SEMANTICS (all explicit, never silent):
 *  - invalid mnemonic / not a Meta Wolf container / unsafe entries -> [YlbFormatException]
 *  - all derived key candidates rejected -> [WrongPasswordException] (message carries the
 *    number of tried derivation paths and candidates)
 *  - archive corruption during extraction -> [BackupException]
 *  - entries without an identifiable package name are extracted into `ylb_unknown/` and
 *    reported in [YlbParseResult.unknownEntries]; entries with unsafe paths are reported in
 *    [YlbParseResult.skippedUnsafeEntries] and not extracted.
 *
 * KEY MATERIAL HYGIENE: derived private key hex strings live only in char[] buffers that are
 * zeroed (wiped) as soon as a candidate fails or extraction completes; no key material is
 * logged, returned, or stored in [YlbParseResult].
 */
object YlbBackupParser {

    const val TAG = "YlbImport"

    /** manifest.json "app" marker for converted Meta Wolf imports. */
    const val APP_NAME_YLB_IMPORT = "YlbImport"

    /** Hard limit for the number of extracted entries (zip-bomb guard). */
    const val MAX_ENTRIES = 200_000

    /** Hard limit for the total uncompressed payload size (zip-bomb guard). */
    const val MAX_TOTAL_UNCOMPRESSED_BYTES: Long = 8L * 1024L * 1024L * 1024L

    /** Upper bound of the candidate sequence: 2 passphrase modes x 10 derivation paths x 3 hex forms. */
    const val MAX_CANDIDATES = 60

    // =========================================================================================
    // Public API
    // =========================================================================================

    /**
     * Probe: is [file] a Meta Wolf backup?
     *
     * Decision logic:
     *  1. Must be a readable zip container (otherwise confidence NONE).
     *  2. MUST NOT contain a plaintext blackbox-backup v1 manifest.json (our own archives
     *     always expose manifest.json in clear text; a Meta Wolf AES zip never does).
     *  3. Must contain WinZip AES encrypted entries (zip4j central directory encryption method
     *     AES, "AE" vendor extra field).
     *  4. Entry paths must be relative, and either carry a known Meta Wolf prefix
     *     (app_foza_apks/ or app_split/) or at least one package-name-like segment.
     *
     * @throws YlbFormatException when the file does not exist.
     */
    fun sniff(file: File): YlbFormatInfo {
        if (!file.isFile) {
            throw YlbFormatException("ylb sniff: file does not exist: ${file.absolutePath}")
        }
        val headers: List<FileHeader> = try {
            ZipFile(file).use { it.fileHeaders }
        } catch (e: Exception) {
            BackupLog.w(TAG, "sniff: not a readable zip container: ${e.message}")
            return YlbFormatInfo(
                isYlbBackup = false,
                confidence = Confidence.NONE,
                totalEntryCount = 0,
                aesEncryptedEntryCount = 0,
                looksLikeBlackBoxV1 = false,
                detectedPrefixes = emptySet(),
                detectedPackages = emptyList(),
                reasons = listOf("not a readable zip container: ${e.message}"),
            )
        }
        val reasons = ArrayList<String>()
        val looksV1 = headers.any { it.fileName == BackupArchive.MANIFEST_ENTRY }
        if (looksV1) {
            reasons.add(
                "contains a plaintext ${BackupArchive.MANIFEST_ENTRY}: this is a blackbox-backup v1 archive, " +
                    "not a Meta Wolf backup",
            )
        }
        val aesCount = headers.count { it.isEncrypted && it.encryptionMethod == EncryptionMethod.AES }
        if (aesCount == 0) {
            reasons.add("no WinZip AES encrypted entries (Meta Wolf backups are fully AES encrypted)")
        }
        val fileEntries = headers.filter { !it.isDirectory }
        val allRelative = fileEntries.all { isRelativePathShape(it.fileName) }
        if (!allRelative) {
            reasons.add("entry paths are not plain relative dataDir paths")
        }
        val prefixes = mutableSetOf<String>()
        if (fileEntries.any { it.fileName.startsWith(YlbPathMapper.PREFIX_FOZA_APKS + "/") }) {
            prefixes.add(YlbPathMapper.PREFIX_FOZA_APKS)
        }
        if (fileEntries.any { it.fileName.startsWith(YlbPathMapper.PREFIX_APP_SPLIT + "/") }) {
            prefixes.add(YlbPathMapper.PREFIX_APP_SPLIT)
        }
        val packages = fileEntries.mapNotNull { YlbPathMapper.findPackageSegment(it.fileName) }.distinct()
        val shapeConfirmed = prefixes.isNotEmpty() || packages.isNotEmpty()
        if (!shapeConfirmed) {
            reasons.add("no Meta Wolf entry shape detected (no known prefix, no package-like segment)")
        }
        val highConfidence = aesCount > 0 && !looksV1 && allRelative && shapeConfirmed
        val candidateOnly = aesCount > 0 && !looksV1 && allRelative && !shapeConfirmed
        val confidence = when {
            highConfidence -> Confidence.HIGH
            candidateOnly -> Confidence.CANDIDATE
            else -> Confidence.NONE
        }
        BackupLog.i(
            TAG,
            "sniff(${file.name}): ylb=$highConfidence confidence=$confidence entries=${headers.size} " +
                "aes=$aesCount v1=$looksV1 prefixes=$prefixes packages=${packages.size}",
        )
        return YlbFormatInfo(
            isYlbBackup = highConfidence,
            confidence = confidence,
            totalEntryCount = headers.size,
            aesEncryptedEntryCount = aesCount,
            looksLikeBlackBoxV1 = looksV1,
            detectedPrefixes = prefixes,
            detectedPackages = packages,
            reasons = reasons,
        )
    }

    /**
     * Decrypts and extracts a Meta Wolf backup.
     *
     * Flow: validate mnemonic -> build the candidate password sequence (BIP39 seed per
     * passphrase mode -> BIP32 master -> CKDpriv paths -> private key hex forms) -> trial-open
     * the smallest encrypted entry with each candidate (zip4j verifies the WinZip AES password
     * verifier and the entry HMAC; wrong candidates fail in milliseconds) -> extract every
     * entry under a temp directory next to [file] (`<file>.ylb_extract_*`) and classify it
     * with [YlbPathMapper].
     *
     * @param mnemonic the identity wallet mnemonic (12/15/18/21/24 BIP39 English words).
     * @param password the user password entered in Meta Wolf when the identity/backup was
     *        created; may be null/empty. It is tried both as the BIP39 passphrase and as a
     *        factor that does not participate in derivation.
     * @return the parse result; the caller owns the extraction directory and MUST call
     *         [YlbParseResult.cleanup] when done (typically after [convertToV1]).
     * @throws YlbFormatException invalid mnemonic / not a Meta Wolf backup container.
     * @throws WrongPasswordException no candidate opened the archive (message lists the number
     *         of tried derivation paths and candidates).
     * @throws BackupException archive corrupted mid-extraction.
     */
    fun parse(file: File, mnemonic: CharArray, password: CharArray?): YlbParseResult {
        if (!file.isFile) {
            throw YlbFormatException("ylb parse: file does not exist: ${file.absolutePath}")
        }
        val candidates = buildPasswordCandidates(mnemonic, password)
        if (candidates.candidates.isEmpty()) {
            throw YlbFormatException(
                "no usable key candidates could be derived from the mnemonic " +
                    "(all BIP32 derivations produced invalid scalars)",
            )
        }
        BackupLog.i(
            TAG,
            "parse(${file.name}): ${candidates.candidates.size} candidate password(s) from " +
                "${candidates.derivationPathAttempts} derivation path attempt(s) " +
                "across ${candidates.passphraseModes} passphrase mode(s)",
        )

        val zip = ZipFile(file)
        try {
            val headers = try {
                zip.fileHeaders
            } catch (e: ZipException) {
                throw YlbFormatException("not a readable zip container: ${e.message}", e)
            }
            if (headers.isEmpty()) {
                throw YlbFormatException("zip container has no entries: ${file.name}")
            }
            if (headers.any { it.fileName == BackupArchive.MANIFEST_ENTRY }) {
                throw YlbFormatException(
                    "archive contains a plaintext ${BackupArchive.MANIFEST_ENTRY}: this is a " +
                        "blackbox-backup v1 archive - use BackupManager.restore, not the ylb importer",
                )
            }
            val aesEntries = headers.filter { it.isEncrypted && it.encryptionMethod == EncryptionMethod.AES }
            if (aesEntries.isEmpty()) {
                throw YlbFormatException("archive has no AES-encrypted entries; not a Meta Wolf backup")
            }
            if (headers.size > MAX_ENTRIES) {
                throw YlbFormatException("archive has ${headers.size} entries, above the $MAX_ENTRIES guard limit")
            }
            val totalUncompressed = headers.sumOf { it.uncompressedSize }
            if (totalUncompressed > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                throw YlbFormatException(
                    "archive payload is $totalUncompressed bytes, above the " +
                        "$MAX_TOTAL_UNCOMPRESSED_BYTES guard limit",
                )
            }
            val testHeader = aesEntries.filter { !it.isDirectory }
                .minByOrNull { maxOf(1L, it.uncompressedSize) }
                ?: throw YlbFormatException("archive has no readable encrypted file entry")

            var matched: CandidatePassword? = null
            var lastError: Exception? = null
            for (candidate in candidates.candidates) {
                val opened = try {
                    tryOpenEntry(zip, testHeader, candidate.password)
                } catch (e: Exception) {
                    lastError = e
                    false
                }
                if (opened) {
                    matched = candidate
                    break
                }
                candidate.wipe()
            }
            val found = matched ?: run {
                candidates.wipeAll()
                throw WrongPasswordException(
                    "wrong mnemonic or password: all ${candidates.candidates.size} derived password " +
                        "candidate(s) failed to open the Meta Wolf backup (tried " +
                        "${candidates.derivationPathAttempts} derivation path(s) across " +
                        "${candidates.passphraseModes} passphrase mode(s); last zip4j error: " +
                        "${lastError?.message ?: "password verifier rejected every candidate"}). " +
                        "The mnemonic and password must match the identity wallet that created this backup; " +
                        "if the identity keystore JSON was exported from the original device, it can be " +
                        "used as a fallback",
                )
            }
            BackupLog.i(TAG, "password candidate matched: ${found.label}")
            zip.setPassword(found.password)

            val parentDir = file.absoluteFile.parentFile ?: File(System.getProperty("user.dir", "."))
            val extractRoot = File(parentDir, "${file.name}.ylb_extract_${System.nanoTime()}")
            if (!extractRoot.isDirectory && !extractRoot.mkdirs()) {
                throw BackupException("cannot create ylb extraction directory: ${extractRoot.absolutePath}")
            }
            try {
                return extractAll(zip, headers, extractRoot, found.label)
            } catch (t: Throwable) {
                deleteRecursive(extractRoot)
                throw t
            } finally {
                // Extraction is complete (or failed and cleaned up): wipe all key material.
                found.wipe()
                candidates.wipeAll()
            }
        } finally {
            zip.close()
        }
    }

    /**
     * Converts a parsed Meta Wolf backup into a blackbox-backup v1 zip archive.
     *
     * Layout per package (v1 engine conventions, see BackupArchive/BackupEngine):
     *  - `apps/{escaped_pkg}/entry.json`
     *  - `apps/{escaped_pkg}/base.apk` (when an APK named base.apk exists; APK-typed entries
     *    from app_split win over data-typed entries from app_foza_apks)
     *  - `apps/{escaped_pkg}/splits/<name>` (all other APK payloads)
     *  - `apps/{escaped_pkg}/data_user/...` (all data entries of that package)
     *  - root `manifest.json` + `manifest.sha256` (app = "YlbImport", possibly_inconsistent =
     *    true because the source format carries no consistency guarantee, include_apk = whether
     *    a base.apk was discovered, encrypted = false, user_id = [targetUserId]).
     *
     * Entries without a package name ([YlbParseResult.unknownEntries]) are NOT written into the
     * v1 archive (they cannot be attributed); they remain reported in the parse result.
     *
     * @param targetUserId the virtual user id recorded in the manifest (apps[].user_id).
     * @param stagingDir directory that receives the produced zip file (created when missing).
     * @return the produced v1 archive file; hand it to
     *         `BackupManager.get().restore(context, v1File, null, null, null)` to apply it.
     * @throws YlbFormatException when nothing is convertible or two entries collide on the
     *         same v1 target path.
     */
    fun convertToV1(parsed: YlbParseResult, targetUserId: Int, stagingDir: File): File {
        val byPackage = LinkedHashMap<String, MutableList<YlbEntry>>()
        for (entry in parsed.entries) {
            val pkg = entry.packageName ?: continue
            byPackage.getOrPut(pkg) { ArrayList() }.add(entry)
        }
        if (byPackage.isEmpty()) {
            throw YlbFormatException(
                "nothing to convert: no package could be attributed from the ${parsed.entries.size} " +
                    "parsed entries (${parsed.unknownEntries.size} unknown, " +
                    "${parsed.skippedUnsafeEntries.size} skipped-unsafe)",
            )
        }
        if (!stagingDir.isDirectory && !stagingDir.mkdirs()) {
            throw BackupException("cannot create staging directory: ${stagingDir.absolutePath}")
        }
        val plans = byPackage.map { (pkg, list) -> buildPackagePlan(pkg, list, targetUserId) }
        val manifest = BackupManifest(
            app = APP_NAME_YLB_IMPORT,
            encrypted = false,
            kdf = null,
            device = null,
            apps = plans.map { it.manifestApp },
            symlinks = emptyList(),
        )
        val manifestBytes = BackupManifestCodec.serialize(manifest)
        val outFile = File(stagingDir, "ylb_import_v1_${System.currentTimeMillis()}.zip")
        try {
            ZipOutputStream(BufferedOutputStream(outFile.outputStream(), BackupArchive.COPY_BUFFER_SIZE)).use { zos ->
                BackupArchive.writeBytesEntry(zos, BackupArchive.MANIFEST_ENTRY, manifestBytes, null)
                BackupArchive.writeBytesEntry(
                    zos,
                    BackupArchive.MANIFEST_SHA256_ENTRY,
                    BackupManifestCodec.sha256Hex(manifestBytes).toByteArray(Charsets.UTF_8),
                    null,
                )
                for (plan in plans) {
                    writeAppSection(zos, plan)
                }
            }
        } catch (e: YlbFormatException) {
            outFile.delete()
            throw e
        } catch (e: Exception) {
            outFile.delete()
            throw BackupException("failed to write converted v1 archive ${outFile.name}: ${e.message}", e)
        }
        BackupLog.i(
            TAG,
            "convertToV1(${parsed.sourceFile.name}): ${plans.size} package(s), target user $targetUserId, " +
                "output ${outFile.length()} bytes -> ${outFile.absolutePath}",
        )
        return outFile
    }

    // =========================================================================================
    // Result / info types
    // =========================================================================================

    /** Sniff confidence levels. */
    enum class Confidence { NONE, CANDIDATE, HIGH }

    /** Sniff verdict with the collected evidence (machine and human readable). */
    class YlbFormatInfo(
        /** true only at confidence HIGH. */
        val isYlbBackup: Boolean,
        val confidence: Confidence,
        val totalEntryCount: Int,
        val aesEncryptedEntryCount: Int,
        /** true when the archive exposes a plaintext blackbox-backup v1 manifest.json. */
        val looksLikeBlackBoxV1: Boolean,
        /** Known Meta Wolf prefixes seen in entry paths (app_foza_apks / app_split). */
        val detectedPrefixes: Set<String>,
        /** Package names detected in entry paths (positive signals). */
        val detectedPackages: List<String>,
        /** Human readable evidence/reasons (why yes / why no). */
        val reasons: List<String>,
    )

    /** One extracted zip entry with its [YlbPathMapper] classification. */
    class YlbEntry(
        /** Original zip entry path (Meta Wolf dataDir relative). */
        val entryPath: String,
        /** Extracted file on disk (under entries/ or ylb_unknown/ of the extraction root). */
        val file: File,
        /** Owning package name; null when type == UNKNOWN. */
        val packageName: String?,
        val type: YlbEntryType,
        val sizeBytes: Long,
        /** Classification reason (explicit reporting). */
        val reason: String,
        /** Path after the package segment, '/'-joined ("" when the entry IS the package root). */
        val relativeToPackage: String,
    )

    /** Entry that was NOT extracted because its path was unsafe (reported, never silent). */
    class SkippedEntry(val entryPath: String, val reason: String)

    /**
     * Result of [parse]. The caller MUST call [cleanup] when done with the files
     * (typically after [convertToV1]).
     */
    class YlbParseResult(
        val sourceFile: File,
        /** Directory holding entries/ (mapped) and ylb_unknown/ (unattributed) subtrees. */
        val extractionRoot: File,
        /** Successfully extracted entries with an identified package (DATA / APK). */
        val entries: List<YlbEntry>,
        /** Extracted but unattributed entries (staged under ylb_unknown/), reported explicitly. */
        val unknownEntries: List<YlbEntry>,
        /** Entries rejected because of unsafe paths (not extracted), reported explicitly. */
        val skippedUnsafeEntries: List<SkippedEntry>,
        /** Number of pure directory entries (not extracted). */
        val directoryEntryCount: Int,
        /** Which derivation candidate matched, WITHOUT any key material. */
        val matchedDerivation: String,
    ) {
        /** Distinct package names among [entries], in archive order. */
        val packages: List<String>
            get() = entries.mapNotNull { it.packageName }.distinct()

        /** Deletes the whole extraction tree. @return true when the directory is gone. */
        fun cleanup(): Boolean {
            deleteRecursive(extractionRoot)
            return !extractionRoot.exists()
        }
    }

    // =========================================================================================
    // Candidate generation (mnemonic -> password candidates)
    // =========================================================================================

    /** One derivation step: child index (without hardened bit) + hardened flag. */
    private class Step(val index: Int, val hardened: Boolean)

    /** A BIP32 derivation path with its display label. */
    private class DerivationPath(val label: String, val steps: List<Step>)

    /** A single trial password with a key-material-free label. */
    internal class CandidatePassword(val label: String, val password: CharArray) {
        fun wipe() {
            Arrays.fill(password, ' ')
        }
    }

    internal class CandidateList(
        val candidates: List<CandidatePassword>,
        val derivationPathAttempts: Int,
        val passphraseModes: Int,
    ) {
        fun wipeAll() {
            candidates.forEach { it.wipe() }
        }
    }

    private const val MODE_USER_PASSPHRASE = "passphrase=user password"
    private const val MODE_NO_PASSPHRASE = "passphrase=<empty>"

    /**
     * The candidate CKDpriv paths (format fact 3c): standard BIP44 ETH path, account slot
     * variants for slot 0..3 with change index 0 and 1, and the master key itself as fallback.
     */
    private val DERIVATION_PATHS: List<DerivationPath> = buildList {
        add(DerivationPath("m/44'/60'/0'/0/0", standardPath(slot = 0, change = 0)))
        for (slot in 0..3) {
            add(DerivationPath("m/44'/60'/$slot'/0/0", standardPath(slot = slot, change = 0)))
        }
        for (slot in 0..3) {
            add(DerivationPath("m/44'/60'/$slot'/1/0", standardPath(slot = slot, change = 1)))
        }
        add(DerivationPath("m (master key, no derivation)", emptyList()))
    }

    private fun standardPath(slot: Int, change: Int): List<Step> = listOf(
        Step(44, hardened = true),
        Step(60, hardened = true),
        Step(slot, hardened = true),
        Step(change, hardened = false),
        Step(0, hardened = false),
    )

    /**
     * Builds the ordered candidate password sequence (format facts 3a-3d). Invalid BIP32
     * results (IL = 0 or >= n) skip their candidate with a log; duplicates collapse.
     * Maximum [MAX_CANDIDATES] passwords.
     *
     * @throws YlbFormatException when the mnemonic is invalid (word count / word list / checksum).
     */
    internal fun buildPasswordCandidates(mnemonic: CharArray, password: CharArray?): CandidateList {
        Bip39.validate(mnemonic)
        val modes = ArrayList<Pair<String, CharArray>>(2)
        if (password == null || password.isEmpty()) {
            modes.add(MODE_NO_PASSPHRASE to CharArray(0))
        } else {
            modes.add(MODE_USER_PASSPHRASE to password.copyOf())
            modes.add(MODE_NO_PASSPHRASE to CharArray(0))
        }
        val seen = HashSet<String>()
        val out = ArrayList<CandidatePassword>()
        for ((modeLabel, passphrase) in modes) {
            val seed = Bip39.mnemonicToSeed(mnemonic, passphrase)
            val master = Bip32.masterFromSeed(seed)
            Arrays.fill(passphrase, ' ')
            Arrays.fill(seed, 0)
            if (master == null) {
                BackupLog.w(TAG, "mode '$modeLabel': seed yields an invalid BIP32 master key, skipping")
                continue
            }
            for (path in DERIVATION_PATHS) {
                val node = derivePath(master, path)
                if (node == null) {
                    BackupLog.w(TAG, "mode '$modeLabel' path '${path.label}': invalid derived scalar, skipping")
                    continue
                }
                for (hex in Bip32.privateKeyHexForms(node.key)) {
                    if (seen.add(hex)) {
                        out.add(
                            CandidatePassword("$modeLabel | ${path.label} | hex length ${hex.length}", hex.toCharArray()),
                        )
                    }
                }
            }
        }
        return CandidateList(out, modes.size * DERIVATION_PATHS.size, modes.size)
    }

    private fun derivePath(master: Bip32.Node, path: DerivationPath): Bip32.Node? {
        var node: Bip32.Node = master
        for (step in path.steps) {
            node = Bip32.ckdPriv(node, step.index, step.hardened) ?: return null
        }
        return node
    }

    // =========================================================================================
    // zip4j trial + extraction
    // =========================================================================================

    /**
     * Trial-open: sets [password] and fully drains the smallest encrypted entry. zip4j checks
     * the 2-byte WinZip AES password verifier on the first read (wrong password -> ZipException
     * immediately) and the HMAC-SHA1 authentication code at the end of the entry (covers the
     * ~1/65536 verifier false positives). Only a fully verified entry returns true.
     */
    private fun tryOpenEntry(zip: ZipFile, header: FileHeader, password: CharArray): Boolean {
        zip.setPassword(password)
        try {
            zip.getInputStream(header).use { input ->
                val buffer = ByteArray(BackupArchive.COPY_BUFFER_SIZE)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                }
            }
            return true
        } catch (e: ZipException) {
            BackupLog.d(TAG, "candidate rejected by zip4j: ${e.message}")
            return false
        } catch (e: IOException) {
            BackupLog.d(TAG, "candidate rejected (io/hmac mismatch): ${e.message}")
            return false
        }
    }

    /** Extracts every header into [extractRoot] and classifies it via [YlbPathMapper]. */
    private fun extractAll(
        zip: ZipFile,
        headers: List<FileHeader>,
        extractRoot: File,
        matchedLabel: String,
    ): YlbParseResult {
        val mappedRoot = File(extractRoot, "entries")
        val unknownRoot = File(extractRoot, YlbPathMapper.UNKNOWN_DIR)
        val entries = ArrayList<YlbEntry>()
        val unknownEntries = ArrayList<YlbEntry>()
        val skipped = ArrayList<SkippedEntry>()
        var directoryCount = 0

        for (header in headers) {
            val rawName = header.fileName ?: continue
            if (header.isDirectory || rawName.endsWith("/")) {
                directoryCount++
                continue
            }
            val mapping = YlbPathMapper.map(rawName)
            if (mapping.segments.isEmpty()) {
                skipped.add(SkippedEntry(rawName, mapping.reason))
                BackupLog.w(TAG, "skipping unsafe ylb entry '$rawName': ${mapping.reason}")
                continue
            }
            val baseDir = if (mapping.type == YlbEntryType.UNKNOWN) unknownRoot else mappedRoot
            val dest = safeChild(baseDir, mapping.segments)
            if (dest == null) {
                skipped.add(SkippedEntry(rawName, "path rejected by the extraction safety check"))
                BackupLog.w(TAG, "skipping ylb entry '$rawName': extraction path rejected")
                continue
            }
            dest.parentFile?.let { parent ->
                if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
                    throw BackupException("cannot create ylb extraction directory: ${parent.absolutePath}")
                }
            }
            try {
                zip.getInputStream(header).use { input ->
                    FileOutputStream(dest).use { output ->
                        input.copyTo(output, BackupArchive.COPY_BUFFER_SIZE)
                    }
                }
            } catch (e: Exception) {
                throw BackupException("failed to extract ylb entry '$rawName': ${e.message}", e)
            }
            val entry = YlbEntry(
                entryPath = rawName,
                file = dest,
                packageName = mapping.packageName,
                type = mapping.type,
                sizeBytes = dest.length(),
                reason = mapping.reason,
                relativeToPackage = mapping.relativeToPackage,
            )
            if (mapping.type == YlbEntryType.UNKNOWN) {
                unknownEntries.add(entry)
                BackupLog.w(
                    TAG,
                    "unattributed ylb entry staged under ${YlbPathMapper.UNKNOWN_DIR}/: '$rawName' (${mapping.reason})",
                )
            } else {
                entries.add(entry)
            }
        }
        BackupLog.i(
            TAG,
            "extracted ${entries.size} mapped + ${unknownEntries.size} unknown entries " +
                "($directoryCount directories, ${skipped.size} skipped-unsafe) via $matchedLabel",
        )
        return YlbParseResult(
            sourceFile = File(zip.file.absolutePath),
            extractionRoot = extractRoot,
            entries = entries,
            unknownEntries = unknownEntries,
            skippedUnsafeEntries = skipped,
            directoryEntryCount = directoryCount,
            matchedDerivation = matchedLabel,
        )
    }

    // =========================================================================================
    // v1 conversion (single source of truth: PackagePlan drives manifest + section writing)
    // =========================================================================================

    /** Per-package conversion plan; drives both the manifest and the zip section. */
    private class PackagePlan(
        val packageName: String,
        val baseApk: YlbEntry?,
        val dataEntries: List<YlbEntry>,
        val splitEntries: List<YlbEntry>,
        val targetUserId: Int,
    ) {
        val manifestApp: ManifestApp = ManifestApp(
            packageName = packageName,
            userId = targetUserId,
            versionName = null,
            versionCode = 0L,
            signingCertSha256 = null,
            includeApk = baseApk != null,
            excludeCache = true,
            possiblyInconsistent = true,
            sizeBytes = (listOfNotNull(baseApk) + dataEntries + splitEntries).sumOf { it.sizeBytes },
            fileCount = (if (baseApk != null) 1 else 0) + dataEntries.size + splitEntries.size,
        )
    }

    private fun buildPackagePlan(pkg: String, list: List<YlbEntry>, targetUserId: Int): PackagePlan {
        // Prefer APK-typed base.apk (app_split) over a data-typed one (app_foza_apks staging).
        val base = list.filter { it.file.name.equals("base.apk", ignoreCase = true) }
            .minByOrNull { if (it.type == YlbEntryType.APK) 0 else 1 }
        val dataEntries = ArrayList<YlbEntry>()
        val splitEntries = ArrayList<YlbEntry>()
        for (entry in list) {
            if (entry === base) continue
            when (entry.type) {
                YlbEntryType.APK -> splitEntries.add(entry)
                YlbEntryType.DATA ->
                    if (entry.relativeToPackage.isEmpty()) {
                        BackupLog.w(
                            TAG,
                            "data entry '${entry.entryPath}' has no relative part after its package " +
                                "segment; not representable in the v1 layout, skipped",
                        )
                    } else {
                        dataEntries.add(entry)
                    }
                YlbEntryType.UNKNOWN -> Unit // never attributed; reported in the parse result
            }
        }
        return PackagePlan(pkg, base, dataEntries, splitEntries, targetUserId)
    }

    private fun writeAppSection(zos: ZipOutputStream, plan: PackagePlan) {
        val root = BackupArchive.APPS_ROOT + BackupArchive.escapePkg(plan.packageName) + "/"
        val usedTargets = HashSet<String>()
        BackupArchive.writeBytesEntry(
            zos,
            root + BackupArchive.ENTRY_JSON,
            BackupManifestCodec.serializeEntry(
                EntryInfo(
                    uid = 0, // unknown in the ylb source format (informational field only)
                    versionName = null,
                    versionCode = 0L,
                    signingCertSha256 = null,
                    sizeBytes = plan.manifestApp.sizeBytes,
                    fileCount = plan.manifestApp.fileCount,
                ),
            ),
            null,
        )
        plan.baseApk?.let { writeEntry(zos, root + BackupArchive.BASE_APK_NAME, it, usedTargets) }
        for (entry in plan.splitEntries) {
            writeEntry(zos, root + BackupArchive.SPLITS_SECTION + "/" + entry.file.name, entry, usedTargets)
        }
        for (entry in plan.dataEntries) {
            writeEntry(zos, root + BackupArchive.DATA_USER_SECTION + "/" + entry.relativeToPackage, entry, usedTargets)
        }
    }

    private fun writeEntry(zos: ZipOutputStream, target: String, entry: YlbEntry, usedTargets: MutableSet<String>) {
        if (!usedTargets.add(target)) {
            throw YlbFormatException(
                "v1 target collision: ylb entry '${entry.entryPath}' maps to '$target' which is already taken",
            )
        }
        BackupArchive.writeFileEntry(zos, target, entry.file, null)
    }

    // =========================================================================================
    // Misc helpers
    // =========================================================================================

    /** Plain relative path shape check used by [sniff] (before any decrypting). */
    private fun isRelativePathShape(name: String?): Boolean {
        if (name.isNullOrEmpty()) return false
        if (name.startsWith("/") || name.startsWith("\\")) return false
        if (name.contains('\\')) return false
        if (name.contains(':')) return false
        return true
    }

    /** Resolves validated segments under [base]; double-guards against traversal. */
    private fun safeChild(base: File, segments: List<String>): File? {
        if (segments.isEmpty()) return null
        var file = base
        for (segment in segments) {
            if (segment.isEmpty() || segment == "." || segment == ".." ||
                segment.contains('\\') || segment.contains(':') || segment.contains('\u0000')
            ) {
                return null
            }
            file = File(file, segment)
        }
        return file
    }

    internal fun deleteRecursive(file: File?) {
        if (file == null || !file.exists()) return
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursive(it) }
        }
        if (!file.delete() && file.exists()) {
            BackupLog.w(TAG, "failed to delete ${file.absolutePath}")
        }
    }
}
