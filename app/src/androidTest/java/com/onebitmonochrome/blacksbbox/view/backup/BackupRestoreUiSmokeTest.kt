/*
 * Copyright 2026 Black's BlackBox contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.onebitmonochrome.blacksbbox.view.backup

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.onebitmonochrome.blacksbbox.R
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke tests for the backup/restore activities (on-device / emulator only).
 * They only exercise the UI states that do NOT require BlackBox services or the
 * Storage Access Framework picker, so they stay stable in CI.
 */
@RunWith(AndroidJUnit4::class)
class BackupRestoreUiSmokeTest {

    @Test
    fun singleAppBackupModeShowsRoutedAppInfo() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, BackupActivity::class.java).apply {
            putExtra(BackupActivity.EXTRA_PKG, "com.example.smoke")
            putExtra(BackupActivity.EXTRA_USER_ID, 3)
            putExtra(BackupActivity.EXTRA_APP_NAME, "Smoke App")
        }
        ActivityScenario.launch<BackupActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val appName = activity.findViewById<TextView>(R.id.app_name_text)
                assertEquals("Smoke App", appName.text.toString())

                // Options default states: include APK on, exclude cache on.
                assertTrue(activity.findViewById<View>(R.id.include_apk_switch).isEnabled)
                assertTrue(activity.findViewById<View>(R.id.exclude_cache_switch).isEnabled)
            }
        }
    }

    @Test
    fun mismatchedPasswordsAreRejectedWithoutOpeningSaf() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, BackupActivity::class.java).apply {
            putExtra(BackupActivity.EXTRA_PKG, "com.example.smoke")
            putExtra(BackupActivity.EXTRA_USER_ID, 0)
            putExtra(BackupActivity.EXTRA_APP_NAME, "Smoke App")
        }
        ActivityScenario.launch<BackupActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val password = activity.findViewById<TextInputEditText>(R.id.password_input)
                val confirm = activity.findViewById<TextInputEditText>(R.id.password_confirm_input)
                val confirmLayout =
                        activity.findViewById<TextInputLayout>(R.id.password_confirm_layout)

                password.setText("abc")
                confirm.setText("xyz")
                assertNull(confirmLayout.error)
                activity.findViewById<View>(R.id.start_backup_button).performClick()
                assertNotNull("Mismatch must surface as an explicit field error", confirmLayout.error)
            }
        }
    }

    @Test
    fun restoreActivityStartsInIntroState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ActivityScenario.launch<RestoreActivity>(
                Intent(context, RestoreActivity::class.java)
        ).use { scenario ->
            scenario.onActivity { activity ->
                val chooseButton = activity.findViewById<View>(R.id.choose_file_button)
                assertNotNull(chooseButton)
                assertTrue(chooseButton.isShown)
                val preview = activity.findViewById<View>(R.id.preview_section)
                assertEquals(View.GONE, preview.visibility)
            }
        }
    }
}
