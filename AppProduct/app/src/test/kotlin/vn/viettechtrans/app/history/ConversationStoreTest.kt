package vn.viettechtrans.app.history

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConversationStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun conv(id: String, updated: Long, vararg sources: String) = StoredConversation(
        id = id,
        title = ConversationStore.titleFrom(sources.first()),
        createdAtMs = 1,
        updatedAtMs = updated,
        messages = sources.mapIndexed { i, s -> StoredMessage(turn = i + 1, party = "A", source = s, translation = "t$i", status = "OK", createdAtMs = 1) },
    )

    @Test
    fun saveLoadListNewestFirst() = runBlocking {
        val dir = tmp.newFolder("conversations")
        val store = ConversationStore(dir)
        val a = conv(ConversationStore.newId(1_000, 1), 10, "Xin chào", "Bạn khoẻ không?")
        val b = conv(ConversationStore.newId(2_000, 2), 20, "Hello")
        store.save(a)
        store.save(b)
        assertEquals(a, store.load(a.id))
        // A fresh store (app restarted) reads the same list from disk.
        val reopened = ConversationStore(dir).also { it.refresh() }
        assertEquals(listOf(b.id, a.id), reopened.summaries.value.map { it.id })
        assertEquals(2, reopened.summaries.value.last().messageCount)
        assertFalse(dir.listFiles()!!.any { it.name.endsWith(".tmp") })
    }

    @Test
    fun renameKeepsTitleAndDeleteRemovesFileForGood() = runBlocking {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        val a = conv(ConversationStore.newId(1_000, 3), 10, "Câu đầu")
        store.save(a)
        store.rename(a.id, "  Hỏi đường ở ga  ")
        val renamed = store.load(a.id)!!
        assertEquals("Hỏi đường ở ga", renamed.title)
        assertTrue(renamed.titleEdited)
        store.delete(a.id)
        assertNull(store.load(a.id))
        assertTrue(store.summaries.value.isEmpty())
        // A save that was already on its way does not bring it back.
        store.save(a)
        assertNull(store.load(a.id))
    }

    @Test
    fun deleteAllAndDamagedFilesAreHandled() = runBlocking {
        val dir = tmp.newFolder("d")
        val store = ConversationStore(dir)
        store.save(conv(ConversationStore.newId(1_000, 4), 10, "Một"))
        File(dir, "c20260101-000000000-0000.json").writeText("{ not json")
        store.refresh()
        assertEquals(1, store.summaries.value.size)
        assertTrue(store.sizeBytes() > 0)
        store.deleteAll()
        assertTrue(store.summaries.value.isEmpty())
        assertEquals(0L, store.sizeBytes())
    }

    @Test
    fun titleAndIdRules() {
        assertEquals("Xin chào", ConversationStore.titleFrom("  Xin chào\nDòng hai"))
        val long = "a".repeat(100)
        assertEquals(ConversationStore.TITLE_MAX_CHARS, ConversationStore.titleFrom(long).length)
        assertTrue(ConversationStore.titleFrom(long).endsWith("…"))
        assertTrue(ConversationStore.newId(1_759_800_000_000, 0xabcd).matches(Regex("c[0-9]{8}-[0-9]{9}-abcd")))
    }
}
