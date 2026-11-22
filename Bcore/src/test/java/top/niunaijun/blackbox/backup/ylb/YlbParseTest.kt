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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.niunaijun.blackbox.backup.exception.WrongPasswordException
import top.niunaijun.blackbox.backup.ylb.exception.YlbFormatException
import java.io.File

/**
 * TC-U-08b: parse() round trip with the candidate derivation sequence.
 */
class YlbParseTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun buildSample(): Pair<File, List<Pair<String, ByteArray>>> {
        val entries = YlbTestSupport.standardSampleEntries()
        val zip = YlbTestSupport.createAesZip(
            tmp.root,
            "mundo_backup.zip",
            YlbTestSupport.fixedSamplePassword(),
            entries,
        )
        return zip to entries
    }

    // ---------------------------------------------------------------- happy path

    @Test
    fun `parse extracts every entry with matching content (password participates)`() {
        val (zip, entries) = buildSample()
        val parsed = YlbBackupParser.parse(
            zip,
            YlbTestSupport.FIXED_MNEMONIC.toCharArray(),
            "app-password".toCharArray(),
        )
        try {
            val mappedExpected = entries.filter { it.first != YlbTestSupport.SAMPLE_ENTRY_ORPHAN.first }
            assertEquals(
                mappedExpected.map { it.first }.toSet(),
                parsed.entries.map { it.entryPath }.toSet(),
            )
            for (expected in mappedExpected) {
                val extracted = parsed.entries.first { it.entryPath == expected.first }
                assertTrue("content mismatch for ${expected.first}", extracted.file.readBytes().contentEquals(expected.second))
                assertEquals(expected.second.size.toLong(), extracted.sizeBytes)
            }
            assertEquals(listOf(YlbTestSupport.SAMPLE_PKG), parsed.packages)
            // One unknown entry (logs/orphan.bin) must be staged and reported explicitly.
            assertEquals(1, parsed.unknownEntries.size)
            assertEquals("logs/orphan.bin", parsed.unknownEntries[0].entryPath)
            assertTrue(
                parsed.unknownEntries[0].file.absolutePath.replace('\\', '/')
                    .contains(YlbPathMapper.UNKNOWN_DIR),
            )
            assertEquals(0, parsed.skippedUnsafeEntries.size)
            // The matched candidate label names the derivation path but never key material.
            assertTrue(parsed.matchedDerivation.contains("m/44'/60'/0'/0/0"))
            assertFalse(parsed.matchedDerivation.contains(YlbTestSupport.FIXED_MNEMONIC_KEY_HEX))
        } finally {
            assertTrue(parsed.cleanup())
            assertFalse(parsed.extractionRoot.exists())
        }
    }

    @Test
    fun `parse works without a user password`() {
        val (zip, entries) = buildSample()
        val parsed = YlbBackupParser.parse(zip, YlbTestSupport.FIXED_MNEMONIC.toCharArray(), null)
        try {
            assertEquals(entries.size, parsed.entries.size + parsed.unknownEntries.size)
            assertEquals(entries.map { it.first }.toSet(), (parsed.entries + parsed.unknownEntries).map { it.entryPath }.toSet())
        } finally {
            parsed.cleanup()
        }
    }

    @Test
    fun `parse skips unsafe entries explicitly`() {
        val entries = listOf(
            "app_foza_apks/com.safe.app/files/ok.txt" to "fine".toByteArray(),
            "app_foza_apks/com.safe.app/../escape.txt" to "bad".toByteArray(),
        )
        val zip = YlbTestSupport.createAesZip(
            tmp.root,
            "unsafe.zip",
            YlbTestSupport.fixedSamplePassword(),
            entries,
        )
        val parsed = YlbBackupParser.parse(zip, YlbTestSupport.FIXED_MNEMONIC.toCharArray(), null)
        try {
            assertEquals(1, parsed.entries.size)
            assertEquals(1, parsed.skippedUnsafeEntries.size)
            assertTrue(parsed.skippedUnsafeEntries[0].entryPath.contains(".."))
            assertFalse(File(parsed.extractionRoot, "escape.txt").exists())
        } finally {
            parsed.cleanup()
        }
    }

    // ---------------------------------------------------------------- failures

    @Test
    fun `wrong mnemonic fails with counted derivation attempts`() {
        val (zip, _) = buildSample()
        try {
            YlbBackupParser.parse(
                zip,
                YlbTestSupport.OTHER_VALID_MNEMONIC.toCharArray(),
                "app-password".toCharArray(),
            )
            fail("expected WrongPasswordException")
        } catch (e: WrongPasswordException) {
            val message = e.message!!
            // 2 passphrase modes x 10 derivation paths = 20 path attempts are listed.
            assertTrue(message.contains("20"))
            assertTrue(message.contains("derivation path"))
            assertTrue(message.contains("candidate"))
            assertTrue(message.contains("wrong mnemonic or password"))
        }
    }

    @Test
    fun `wrong password-only mnemonic (no password) lists 10 paths`() {
        val (zip, _) = buildSample()
        try {
            YlbBackupParser.parse(zip, YlbTestSupport.OTHER_VALID_MNEMONIC.toCharArray(), null)
            fail("expected WrongPasswordException")
        } catch (e: WrongPasswordException) {
            assertTrue(e.message!!.contains("10"))
        }
    }

    @Test
    fun `invalid word count fails fast with YlbFormatException`() {
        val (zip, _) = buildSample()
        try {
            YlbBackupParser.parse(
                zip,
                "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon".toCharArray(),
                null,
            )
            fail("expected YlbFormatException")
        } catch (e: YlbFormatException) {
            assertTrue(e.message!!.contains("word count"))
        }
    }

    @Test
    fun `own v1 backup archive is refused instead of misparsed`() {
        val v1 = YlbTestSupport.createV1StyleZip(tmp.root)
        try {
            YlbBackupParser.parse(v1, YlbTestSupport.FIXED_MNEMONIC.toCharArray(), null)
            fail("expected YlbFormatException")
        } catch (e: YlbFormatException) {
            assertTrue(e.message!!.contains("BackupManager.restore"))
        }
    }

    @Test
    fun `plain zip without AES entries is refused`() {
        val plain = YlbTestSupport.createPlainZip(tmp.root)
        try {
            YlbBackupParser.parse(plain, YlbTestSupport.FIXED_MNEMONIC.toCharArray(), null)
            fail("expected YlbFormatException")
        } catch (e: YlbFormatException) {
            assertTrue(e.message!!.contains("no AES-encrypted entries"))
        }
    }
}
