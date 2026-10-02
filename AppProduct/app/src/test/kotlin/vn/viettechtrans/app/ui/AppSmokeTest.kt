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

    private fun str(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    /** Tabs of the floating pill bar expose their label as the content description. */
    private fun navItem(label: String) = hasContentDescription(label) and isSelectable()

    @Test
    fun everyDestinationOpensAndHelpWorks() {
        for (dest in TopDestination.entries) {
            val label = str(dest.label)
            compose.onNode(navItem(label)).performClick()
            compose.waitForIdle()
            compose.onNode(navItem(label)).assertIsSelected()
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
        compose.onNode(navItem(str(R.string.nav_settings))).performClick()
        val expected = str(R.string.settings_translator_none)
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(hasText(expected)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun translateScreenSwapsDirection() {
        compose.onNode(navItem(str(R.string.nav_translate))).performClick()
        compose.waitForIdle()
        val label = str(R.string.translate_input_label, str(R.string.lang_vi))
        assertTrue(compose.onAllNodes(hasText(label)).fetchSemanticsNodes().isNotEmpty())
        compose.onNode(hasContentDescription(str(R.string.translate_swap))).performClick()
        compose.waitForIdle()
        val swapped = str(R.string.translate_input_label, str(R.string.lang_en))
        assertTrue(compose.onAllNodes(hasText(swapped)).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun conversationIsTheStartScreenWithBothLanguageButtons() {
        compose.onNode(navItem(str(R.string.nav_conversation))).assertIsSelected()
        compose.onNode(hasContentDescription(str(R.string.party_vi_speak))).assertExists()
        compose.onNode(hasContentDescription(str(R.string.party_en_speak))).assertExists()
        // Switch to the face-to-face layout and back.
        compose.onNode(hasContentDescription(str(R.string.conv_layout_split))).performClick()
        compose.waitForIdle()
        compose.onNode(hasContentDescription(str(R.string.conv_layout_chat))).performClick()
        compose.waitForIdle()
    }
}
