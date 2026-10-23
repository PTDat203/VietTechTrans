package vn.viettechtrans.app.mt.rules

import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * Local evidence of the text-rule policy at run time (`files/mt_rules/log.jsonl`, one JSON object per line):
 * R06 "Vẫn dịch" (flag language_mismatch_overridden), R08 lost terms ("lưu ca này vào evidence") and R12 rejected
 * outputs ("ghi lỗi và thông tin package vào log"). Text only, on the phone; Settings shows, copies and clears it,
 * and "Xoá toàn bộ lịch sử" clears it too. The newest [MAX_LINES] entries are kept.
 */
class RuleLog(private val dir: File) {
    private val mutex = Mutex()
    private val file get() = File(dir, "log.jsonl")

    suspend fun append(entry: JsonObject) = io {
        dir.mkdirs()
        val withTime = JsonObject(mapOf("time_utc" to kotlinx.serialization.json.JsonPrimitive(Instant.now().toString())) + entry)
        file.appendText(withTime.toString() + "\n", Charsets.UTF_8)
        val lines = file.readLines(Charsets.UTF_8)
        if (lines.size > MAX_LINES + MAX_LINES / 5) file.writeText(lines.takeLast(MAX_LINES).joinToString("\n", postfix = "\n"), Charsets.UTF_8)
    }

    suspend fun count(): Int = io { if (file.isFile) file.readLines(Charsets.UTF_8).count { it.isNotBlank() } else 0 }

    /** All entries, oldest first (Settings → copy). */
    suspend fun text(): String = io { if (file.isFile) file.readText(Charsets.UTF_8) else "" }

    suspend fun clear() = io { file.delete() }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { mutex.withLock { block() } }

    companion object {
        const val MAX_LINES = 500
    }
}
