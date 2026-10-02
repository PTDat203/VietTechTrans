package vn.viettechtrans.app.ui

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import vn.viettechtrans.app.MainActivity
import vn.viettechtrans.app.R

/**
 * JVM (Robolectric) smoke test of the M0 shell: the app starts, every top-level
 * destination opens, the "?" button opens the manual and Settings resolves the
 * translator status. Catches crashes before an APK reaches a phone.
 *
 * Conscrypt is off: Windows Application Control on the dev machine blocks the
 * native DLL Robolectric extracts to %TEMP%, and these tests need no crypto.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class AppSmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun str(id: Int) = compose.activity.getString(id)

    @Test
    fun everyDestinationOpensAndHelpWorks() {
        for (dest in TopDestination.entries) {
            val label = str(dest.label)
            compose.onNode(hasText(label) and isSelectable()).performClick()
            compose.waitForIdle()
            compose.onNode(hasText(label) and isSelectable()).assertIsSelected()
            // Screen title uses the same text as the navigation label (a button may reuse it too).
            assertTrue(label, compose.onAllNodes(hasText(label) and !isSelectable()).fetchSemanticsNodes().isNotEmpty())
        }
        compose.onNode(hasContentDescription(str(R.string.cd_help))).performClick()
        compose.waitForIdle()
        val manual = str(R.string.nav_manual)
        assertTrue(compose.onAllNodes(hasText(manual) and !isSelectable()).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun settingsShowsMissingMtPackage() {
        compose.onNode(hasText(str(R.string.nav_settings)) and isSelectable()).performClick()
        val expected = str(R.string.settings_translator_none)
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(hasText(expected)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun translateScreenHasBothDirections() {
        compose.onNode(hasText(str(R.string.dir_vi_to_en)) and isSelectable()).assertIsSelected()
        compose.onNode(hasText(str(R.string.dir_en_to_vi)) and isSelectable()).performClick()
        compose.onNode(hasText(str(R.string.dir_en_to_vi)) and isSelectable()).assertIsSelected()
    }
}
