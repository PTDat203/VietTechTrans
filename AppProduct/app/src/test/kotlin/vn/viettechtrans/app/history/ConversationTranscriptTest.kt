package vn.viettechtrans.app.history

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationTranscriptTest {
    private fun msg(turn: Int, party: String, source: String, translation: String?) =
        StoredMessage(turn = turn, party = party, source = source, translation = translation, status = "OK", createdAtMs = 0)

    @Test
    fun speakersAndTranslationsInTurnOrder() {
        val c = StoredConversation(
            id = "c1", title = "Máy chủ", createdAtMs = 1_767_225_600_000, updatedAtMs = 0,
            messages = listOf(
                msg(2, "B", "Can you check the database connection?", "Bạn có thể kiểm tra kết nối cơ sở dữ liệu không?"),
                msg(1, "A", "Tôi không kết nối được với máy chủ", "I can't connect to the server"),
                msg(3, "A", "Câu bị từ chối", null),
            ),
        )
        val expected = """
            VietTechTrans: Máy chủ
            07:00 01/01/2026

            [Tiếng Việt] Tôi không kết nối được với máy chủ
            → I can't connect to the server

            [English] Can you check the database connection?
            → Bạn có thể kiểm tra kết nối cơ sở dữ liệu không?

            [Tiếng Việt] Câu bị từ chối
        """.trimIndent()
        assertEquals(expected, ConversationTranscript.format(c, ZoneId.of("Asia/Ho_Chi_Minh")))
    }
}
