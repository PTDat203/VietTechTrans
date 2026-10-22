package vn.viettechtrans.app.history

import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One saved message (text only; "Nghe lại" re-synthesizes it). */
@Serializable
data class StoredMessage(
    val turn: Int,
    /** "A" = Vietnamese speaker, "B" = English speaker. */
    val party: String,
    val source: String,
    val translation: String? = null,
    /** MessageStatus name. */
    val status: String,
    val interrupted: Boolean = false,
    /** The source was corrected after sending (STT misheard or a typo) and translated again. */
    val edited: Boolean = false,
    val createdAtMs: Long,
    val sttMs: Long? = null,
    val mtMs: Long? = null,
    val ttsFirstAudioMs: Long? = null,
)

@Serializable
data class StoredConversation(
    val id: String,
    val title: String,
    /** The user renamed it; the title no longer follows the first message. */
    val titleEdited: Boolean = false,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val messages: List<StoredMessage>,
)

data class ConversationSummary(
    val id: String,
    val title: String,
    val updatedAtMs: Long,
    val messageCount: Int,
)

/**
 * Conversation history like a chat app's chat list: one JSON file per conversation in
 * `files/conversations/`, text only (no audio). Writes go to a temporary file renamed over
 * the old one, so a crash never leaves a half-written conversation. All file work runs on
 * the IO dispatcher behind one mutex.
 */
class ConversationStore(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val _summaries = MutableStateFlow<List<ConversationSummary>>(emptyList())

    /** Deleted ids: a save that was already on its way must not bring them back. */
    private val deleted = mutableSetOf<String>()

    private val _removed = MutableSharedFlow<Removal>(extraBufferCapacity = 8)

    /** Deletions (also from Settings), so an open conversation that was deleted is closed. */
    val removed: SharedFlow<Removal> = _removed.asSharedFlow()

    /** [all]: "Xoá toàn bộ lịch sử", which also covers a conversation not saved yet. */
    data class Removal(val ids: Set<String>, val all: Boolean)

    /** Newest first. Filled by [refresh] and kept current by every write. */
    val summaries: StateFlow<List<ConversationSummary>> = _summaries.asStateFlow()

    suspend fun refresh() = io {
        val list = files().mapNotNull { f -> read(f)?.let(::summaryOf) }
        _summaries.value = list.sortedByDescending { it.updatedAtMs }
    }

    suspend fun load(id: String): StoredConversation? = io { read(fileOf(id)) }

    suspend fun save(conversation: StoredConversation) = io {
        if (conversation.id in deleted) return@io
        dir.mkdirs()
        val target = fileOf(conversation.id)
        val tmp = File(dir, "${conversation.id}.json.tmp")
        tmp.writeText(json.encodeToString(StoredConversation.serializer(), conversation), Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw IOException("Không ghi được ${target.name}")
        }
        _summaries.value = (_summaries.value.filter { it.id != conversation.id } + summaryOf(conversation))
            .sortedByDescending { it.updatedAtMs }
    }

    suspend fun rename(id: String, title: String) {
        val c = load(id) ?: return
        save(c.copy(title = title.trim().take(TITLE_MAX_CHARS).ifEmpty { c.title }, titleEdited = true))
    }

    suspend fun delete(id: String) = io {
        deleted += id
        fileOf(id).delete()
        _summaries.value = _summaries.value.filter { it.id != id }
        _removed.tryEmit(Removal(setOf(id), all = false))
    }

    suspend fun deleteAll() = io {
        val ids = _summaries.value.map { it.id }.toSet()
        deleted += ids
        files().forEach { it.delete() }
        _summaries.value = emptyList()
        _removed.tryEmit(Removal(ids, all = true))
    }

    /** Bytes used by saved conversations (Settings shows it). */
    suspend fun sizeBytes(): Long = io { files().sumOf { it.length() } }

    private fun files(): List<File> = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.toList().orEmpty()

    private fun fileOf(id: String): File {
        require(ID_RE.matches(id)) { "Bad conversation id: $id" }
        return File(dir, "$id.json")
    }

    private fun read(f: File): StoredConversation? = try {
        if (f.isFile) json.decodeFromString(StoredConversation.serializer(), f.readText(Charsets.UTF_8)) else null
    } catch (e: Exception) {
        null // a damaged file is skipped instead of breaking the whole list
    }

    private fun summaryOf(c: StoredConversation) = ConversationSummary(c.id, c.title, c.updatedAtMs, c.messages.size)

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { mutex.withLock { block() } }

    companion object {
        /** Limits written in the user manual (huong_dan_vi.md, "Giới hạn"). */
        const val MAX_MESSAGES = 500
        const val TITLE_MAX_CHARS = 60

        private val ID_RE = Regex("c[0-9]{8}-[0-9]{9}-[0-9a-f]{4}")
        private val ID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS").withZone(ZoneId.systemDefault())

        /** e.g. c20261007-161502123-3fa9: sortable, readable, unique enough for one device. */
        fun newId(nowMs: Long, random: Int): String =
            "c${ID_TIME.format(Instant.ofEpochMilli(nowMs))}-${"%04x".format(random and 0xFFFF)}"

        /** Title from the first message: first line, at most [TITLE_MAX_CHARS] characters. */
        fun titleFrom(text: String): String {
            val line = text.trim().lineSequence().firstOrNull().orEmpty().trim()
            return if (line.length <= TITLE_MAX_CHARS) line else line.take(TITLE_MAX_CHARS - 1).trimEnd() + "…"
        }
    }
}
