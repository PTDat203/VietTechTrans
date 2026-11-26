package vn.viettechtrans.app.history

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A saved conversation as plain text for "Chia sẻ" in the ☰ panel: title, date, then each sentence with
 * its speaker and its translation. Messages without a translation (failed, rejected by R12) keep the source only.
 */
object ConversationTranscript {
    fun format(c: StoredConversation, zone: ZoneId = ZoneId.systemDefault()): String = buildString {
        append("VietTechTrans: ").append(c.title).append('\n')
        append(DATE.withZone(zone).format(Instant.ofEpochMilli(c.createdAtMs))).append('\n')
        for (m in c.messages.sortedBy { it.turn }) {
            append('\n')
            append(if (m.party == "B") "[English] " else "[Tiếng Việt] ").append(m.source).append('\n')
            m.translation?.takeIf { it.isNotBlank() }?.let { append("→ ").append(it).append('\n') }
        }
    }.trimEnd()

    private val DATE = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy")
}
