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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.niunaijun.blackbox.backup.ylb.YlbBackupParser.Confidence
import java.io.File

/**
 * TC-U-08: sniff() format detection.
 *  - a WinZip AES zip with Meta Wolf entry shapes (password = private key hex of the fixed
 *    mnemonic) is recognized;
 *  - our own blackbox-backup v1 archives (plaintext manifest.json) are NEVER recognized;
 *  - random / foreign zips are not recognized.
 */
class YlbSniffTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `meta wolf sample zip is recognized with high confidence`() {
        val zip = YlbTestSupport.createAesZip(
            tmp.root,
            "mundo_backup.zip",
            YlbTestSupport.fixedSamplePassword(),
            YlbTestSupport.standardSampleEntries(),
        )
        val info = YlbBackupParser.sniff(zip)
        assertTrue(info.isYlbBackup)
        assertEquals(Confidence.HIGH, info.confidence)
        assertEquals(YlbTestSupport.standardSampleEntries().size, info.totalEntryCount)
        assertEquals(YlbTestSupport.standardSampleEntries().size, info.aesEncryptedEntryCount)
        assertFalse(info.looksLikeBlackBoxV1)
        assertTrue(info.detectedPrefixes.contains("app_foza_apks"))
        assertTrue(info.detectedPrefixes.contains("app_split"))
        assertTrue(info.detectedPackages.contains(YlbTestSupport.SAMPLE_PKG))
        assertTrue(info.reasons.isEmpty())
    }

    @Test
    fun `own v1 backup zip is never recognized as meta wolf`() {
        val zip = YlbTestSupport.createV1StyleZip(tmp.root)
        val info = YlbBackupParser.sniff(zip)
        assertFalse(info.isYlbBackup)
        assertEquals(Confidence.NONE, info.confidence)
        assertTrue(info.looksLikeBlackBoxV1)
        assertTrue(info.reasons.any { it.contains("manifest.json") })
    }

    @Test
    fun `random plain zip is not recognized`() {
        val zip = YlbTestSupport.createPlainZip(tmp.root)
        val info = YlbBackupParser.sniff(zip)
        assertFalse(info.isYlbBackup)
        assertEquals(Confidence.NONE, info.confidence)
        assertEquals(0, info.aesEncryptedEntryCount)
    }

    @Test
    fun `encrypted foreign zip stays a candidate but is not meta wolf`() {
        val zip = YlbTestSupport.createEncryptedForeignZip(tmp.root)
        val info = YlbBackupParser.sniff(zip)
        assertFalse(info.isYlbBackup)
        assertEquals(Confidence.CANDIDATE, info.confidence)
        assertEquals(1, info.aesEncryptedEntryCount)
        assertTrue(info.reasons.any { it.contains("no Meta Wolf entry shape") })
    }

    @Test
    fun `non zip file is reported as none instead of throwing`() {
        val garbage = tmp.newFile("garbage.bin")
        garbage.writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x50, 0x4b, 0x03, 0x04))
        val info = YlbBackupParser.sniff(garbage)
        assertFalse(info.isYlbBackup)
        assertEquals(Confidence.NONE, info.confidence)
        assertTrue(info.reasons.first().contains("not a readable zip container"))
    }

    @Test
    fun `missing file throws`() {
        try {
            YlbBackupParser.sniff(File(tmp.root, "does_not_exist.zip"))
            org.junit.Assert.fail("expected YlbFormatException")
        } catch (e: top.niunaijun.blackbox.backup.ylb.exception.YlbFormatException) {
            assertTrue(e.message!!.contains("does not exist"))
        }
    }
}
