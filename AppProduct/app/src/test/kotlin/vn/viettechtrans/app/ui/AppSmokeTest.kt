package vn.viettechtrans.app.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun typeAndSend(text: String, send: Int = R.string.conv_send_vi) {
        compose.onNode(hasContentDescription(str(R.string.conv_type))).performClick()
        compose.waitForIdle()
        if (text.isNotEmpty()) compose.onNode(hasSetTextAction()).performTextInput(text)
        compose.onNode(hasText(str(send))).performClick()
        compose.waitForIdle()
    }

    private fun anyContains(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    /** Policy R03 + R13: a lone term is kept without the model, labelled, with no STT/TTS timing or mic notice. */
    @Test
    fun typedTermIsKeptWithItsLabelAndNoVoiceStatus() {
        typeAndSend("JavaScript")
        compose.waitUntil(timeoutMillis = 10_000) { anyContains("Thuật ngữ/ký hiệu được giữ nguyên") }
        assertEquals(2, compose.onAllNodes(hasText("JavaScript")).fetchSemanticsNodes().size) // source and result
        assertFalse(anyContains("STT"))
        assertFalse(anyContains("TTS"))
        assertFalse(anyContains("Không nghe thấy giọng nói"))
    }

    /** Policy R06: English typed as Vietnamese is not sent until the user answers; "Đổi sang English" sends it as English. */
    @Test
    fun wrongLanguageAsksBeforeSending() {
        typeAndSend("Hello, how are you?")
        compose.waitUntil(timeoutMillis = 10_000) { exists(str(R.string.mismatch_title_en)) }
        assertTrue(exists(str(R.string.mismatch_force)))
        compose.onNode(hasText(str(R.string.mismatch_switch_en))).performClick()
        compose.waitUntil(timeoutMillis = 10_000) { exists("Hello, how are you?") && !exists(str(R.string.mismatch_title_en)) }
    }

    /** Dịch nhanh: R02 on an empty box, then R03 + R13 for a typed term (kept, labelled, no STT/TTS timing). */
    @Test
    fun quickTranslateFollowsTheTextRules() {
        openPanelItem(str(R.string.nav_translate))
        compose.onNode(hasText(str(R.string.action_translate))).performClick()
        compose.waitForIdle()
        assertTrue(exists(str(R.string.input_empty)))
        compose.onNode(hasSetTextAction()).performTextInput("CI/CD")
        compose.onNode(hasText(str(R.string.action_translate))).performClick()
        compose.waitUntil(timeoutMillis = 10_000) { anyContains("Thuật ngữ/ký hiệu được giữ nguyên") }
        assertFalse(anyContains("STT"))
        assertFalse(anyContains("TTS"))
    }

    /** Policy R02: 500 displayed characters can be sent, 501 cannot (the text is kept); nothing typed shows the message. */
    @Test
    fun inputLimitAndEmptyInput() {
        typeAndSend("")
        assertTrue(exists(str(R.string.input_empty)))
        compose.onNode(hasSetTextAction()).performTextInput("a".repeat(501))
        compose.waitForIdle()
        assertTrue(exists(str(R.string.input_too_long)))
        assertTrue(exists(str(R.string.input_counter, 501, 500)))
        compose.onNode(hasText(str(R.string.conv_send_vi))).assertIsNotEnabled()
    }
}
