package vn.viettechtrans.app.ui

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
 * JVM (Robolectric) smoke test of the app shell: it starts on the conversation, the ☰ panel
 * opens every other screen, typing a sentence adds it and saves the conversation.
 * Catches crashes before an APK reaches a phone.
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

    private fun exists(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun openPanelItem(label: String) {
        compose.onNode(hasContentDescription(str(R.string.cd_history))).performClick()
        compose.waitForIdle()
        compose.onNode(hasText(label)).performClick()
        compose.waitForIdle()
    }

    private fun back() {
        compose.onNode(hasContentDescription(str(R.string.cd_back))).performClick()
        compose.waitForIdle()
    }

    @Test
    fun startsOnConversationWithBothLanguageButtons() {
        assertTrue(exists(str(R.string.conv_new_title)))
        compose.onNode(hasContentDescription(str(R.string.party_vi_speak))).assertExists()
        compose.onNode(hasContentDescription(str(R.string.party_en_speak))).assertExists()
        compose.onNode(hasContentDescription(str(R.string.conv_new))).assertExists()
    }

    @Test
    fun panelOpensTranslateSettingsAndManual() {
        openPanelItem(str(R.string.nav_translate))
        assertTrue(exists(str(R.string.translate_input_label, str(R.string.lang_vi))))
        compose.onNode(hasContentDescription(str(R.string.translate_swap))).performClick()
        compose.waitForIdle()
        assertTrue(exists(str(R.string.translate_input_label, str(R.string.lang_en))))
        back()

        openPanelItem(str(R.string.nav_settings))
        compose.waitUntil(timeoutMillis = 10_000) { exists(str(R.string.check_title)) }
        back()

        openPanelItem(str(R.string.nav_manual))
        compose.waitUntil(timeoutMillis = 10_000) { exists("Hướng dẫn sử dụng VietTechTrans") }
        back()
        assertTrue(exists(str(R.string.conv_new_title)))
    }

    @Test
    fun typedSentenceIsShownAndSavedInHistory() {
        compose.onNode(hasContentDescription(str(R.string.conv_type))).performClick()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).performTextInput("Xin chào bạn")
        compose.onNode(hasText(str(R.string.conv_send_vi))).performClick()
        compose.waitUntil(timeoutMillis = 10_000) { exists("Xin chào bạn") && !exists(str(R.string.conv_send_vi)) }
        // The conversation is saved and listed in the ☰ panel under its first sentence.
        compose.onNode(hasContentDescription(str(R.string.cd_history))).performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(hasContentDescription(str(R.string.history_item_menu, "Xin chào bạn"))).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
