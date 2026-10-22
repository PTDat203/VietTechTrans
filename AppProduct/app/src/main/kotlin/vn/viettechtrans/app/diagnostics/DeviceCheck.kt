package vn.viettechtrans.app.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.StatFs
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import vn.viettechtrans.app.AppContainer
import vn.viettechtrans.app.mt.CoreMt
import vn.viettechtrans.app.mt.Direction
import vn.viettechtrans.app.mt.Lang
import vn.viettechtrans.app.mt.MtInputNormalizer
import vn.viettechtrans.app.mt.Translator
import vn.viettechtrans.app.mt.marian.MarianTranslator
import vn.viettechtrans.app.speech.EngineRole
import vn.viettechtrans.app.speech.nowMs

/**
 * "Kiểm tra máy này" (Settings): measures this phone with the app's own models, offline,
 * so a phone without USB debugging can still report its numbers (copy the text):
 * device facts (chip, CPU features that decide the int8 kernels), model load times, MT speed on the
 * packaged golden sentences (and whether the output matches the PC reference) with 2 threads as in the
 * app and with 4 threads for comparison, a TTS→STT loopback per language, RAM.
 * Verdict against docs/bao_cao/thong_so_may_toi_thieu.md.
 */
class DeviceCheck(private val c: AppContainer, private val context: Context) {
    suspend fun run(progress: (String) -> Unit): String {
        val out = StringBuilder()
        fun line(s: String) {
            out.appendLine(s)
            progress(out.toString())
        }

        val am = context.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val freeBytes = StatFs(context.filesDir.absolutePath).availableBytes
        line("Máy: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.SUPPORTED_ABIS.firstOrNull()} · ${Runtime.getRuntime().availableProcessors()} nhân")
        line("RAM: tổng ${gb(mem.totalMem)}, còn trống ${gb(mem.availMem)} · Bộ nhớ trong còn ${gb(freeBytes)}")
        val cpu = cpuLine()
        line(cpu)

        // Speech models, loaded one by one from scratch.
        val speech = c.speechEngines
        speech.releaseAll()
        val loads = mutableListOf<String>()
        for ((role, load) in listOf<Pair<EngineRole, suspend () -> Long>>(
            EngineRole.STT_VI to { speech.stt(Lang.VI).loadMs },
            EngineRole.STT_EN to { speech.stt(Lang.EN).loadMs },
            EngineRole.TTS_VI to { speech.tts(Lang.VI).loadMs },
            EngineRole.TTS_EN to { speech.tts(Lang.EN).loadMs },
        )) {
            line("Đang nạp ${role.name}…")
            loads += "${role.name} ${sec(runCatching { load() }.getOrElse { -1 })}"
        }

        // Translation models.
        val core = c.coreMt() as? CoreMt.Available
        val translator = c.translators.active()
        val mtLines = mutableListOf<String>()
        if (core == null || translator == null) {
            mtLines += "Dịch: chưa có gói mô hình dịch trong APK"
        } else {
            translator.release(Direction.entries.toSet())
            for (d in Direction.entries) {
                line("Đang nạp mô hình dịch ${d.label}…")
                val t0 = nowMs()
                translator.prepare(setOf(d))
                loads += "MT ${d.label} ${sec(nowMs() - t0)}"
                val golden = readGolden(core.manifest.directions.getValue(d.id).goldenFile)
                mtLines += timeMt(translator, d, golden, "2 luồng") { line(it) }
            }
            // Same sentences with 4 threads, one direction at a time (the app's engines are freed first).
            translator.release(Direction.entries.toSet())
            val four = MarianTranslator(context.assets, core.manifest, threads = 4)
            try {
                for (d in Direction.entries) {
                    four.prepare(setOf(d))
                    val golden = readGolden(core.manifest.directions.getValue(d.id).goldenFile)
                    mtLines += timeMt(four, d, golden, "4 luồng") { line(it) }
                    four.release(setOf(d))
                }
            } finally {
                four.close()
            }
            translator.prepare(Direction.entries.toSet())
        }

        // Loopback: TTS reads a sentence, STT recognizes the audio (no microphone needed).
        val loop = mutableListOf<String>()
        for ((lang, text) in listOf(Lang.VI to LOOP_VI, Lang.EN to LOOP_EN)) {
            line("Đọc rồi nghe lại (${lang.name})…")
            runCatching {
                val t0 = nowMs()
                val (samples, rate) = speech.synthesize(lang, text)
                val ttsMs = nowMs() - t0
                val audioMs = samples.size * 1000L / rate
                val t1 = nowMs()
                val heard = speech.stt(lang).decode(samples, rate)
                val sttMs = nowMs() - t1
                loop += "${lang.name}: đọc ${ttsMs} ms cho ${sec(audioMs)} âm thanh (RTF ${rtf(ttsMs, audioMs)}), " +
                    "nhận dạng ${sttMs} ms (RTF ${rtf(sttMs, audioMs)}) → \"$heard\""
            }.onFailure { loop += "${lang.name}: lỗi ${it.message}" }
        }

        val pssMb = withContext(Dispatchers.Default) { Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss / 1024 }
        out.clear()
        out.appendLine("Máy: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.SUPPORTED_ABIS.firstOrNull()} · ${Runtime.getRuntime().availableProcessors()} nhân")
        out.appendLine("RAM: tổng ${gb(mem.totalMem)}, còn trống ${gb(mem.availMem)} · Bộ nhớ trong còn ${gb(freeBytes)}")
        out.appendLine(cpu)
        out.appendLine("Nạp mô hình: ${loads.joinToString(" · ")}")
        mtLines.forEach { out.appendLine(it) }
        loop.forEach { out.appendLine("Đọc → nghe lại $it") }
        out.appendLine("RAM của app khi đủ 6 mô hình: $pssMb MB (PSS)")
        out.appendLine("Kết luận: ${verdict(mem.totalMem, freeBytes)}")
        out.appendLine("Bản app ${c.buildInfo.versionName} · ${c.buildInfo.gitSha}")
        progress(out.toString())
        return out.toString()
    }

    /** Average / slowest ms per sentence, decoder ms per step (beam search), matches with the PC output. */
    private suspend fun timeMt(
        translator: Translator,
        d: Direction,
        golden: List<Pair<String, String>>,
        label: String,
        line: (String) -> Unit,
    ): String {
        val times = mutableListOf<Long>()
        var match = 0
        var steps = 0L
        var decoderMs = 0L
        golden.forEachIndexed { i, (source, expected) ->
            line("Dịch ${d.label} ($label): câu ${i + 1}/${golden.size}…")
            val t = nowMs()
            val r = translator.translate(d, MtInputNormalizer.normalize(source))
            times += nowMs() - t
            steps += r.details["decode_steps"] ?: 0
            decoderMs += r.details["decoder_ms"] ?: 0
            if (r.text == expected) match++
        }
        if (times.isEmpty()) return "Dịch ${d.label} ($label): không có câu mẫu"
        val perStep = if (steps > 0) String.format(Locale.ROOT, "%.1f", decoderMs.toDouble() / steps) else "–"
        return "Dịch ${d.label} ($label, ${times.size} câu): trung bình ${times.average().toLong()} ms, chậm nhất ${times.max()} ms, " +
            "$perStep ms/bước, giống bản mẫu PC $match/${times.size}"
    }

    /** Chip and the ARM features ONNX Runtime's int8 kernels use (dotprod = asimddp, i8mm); max clock per core. */
    private suspend fun cpuLine(): String = withContext(Dispatchers.IO) {
        val soc = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE
        val features = runCatching {
            File("/proc/cpuinfo").readLines().firstOrNull { it.startsWith("Features") || it.startsWith("flags") }
                ?.substringAfter(':')?.trim()?.split(' ')?.toSet().orEmpty()
        }.getOrDefault(emptySet())
        val wanted = listOf("asimddp", "i8mm", "asimdhp", "sve", "avx2", "avx512_vnni", "avxvnni")
        val has = wanted.filter { it in features }.ifEmpty { listOf("không có dotprod/i8mm") }
        val clocks = (0 until Runtime.getRuntime().availableProcessors()).mapNotNull { i ->
            runCatching { File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong() / 1000 }.getOrNull()
        }
        val layout = clocks.groupingBy { it }.eachCount().entries.sortedByDescending { it.key }
            .joinToString(" + ") { "${it.value}×${it.key} MHz" }.ifEmpty { "không đọc được" }
        "CPU: $soc · $layout · ${has.joinToString(" ")}"
    }

    private suspend fun readGolden(path: String): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        context.assets.open("mt/$path").bufferedReader().readLines().filter { it.isNotBlank() }.map { l ->
            val o = Json.parseToJsonElement(l).jsonObject
            o.getValue("source").jsonPrimitive.content to o.getValue("text").jsonPrimitive.content
        }
    }

    private val Direction.label get() = if (this == Direction.EN_TO_VI) "Anh→Việt" else "Việt→Anh"

    companion object {
        /** docs/bao_cao/thong_so_may_toi_thieu.md */
        const val MIN_RAM_BYTES = 3L * 1000 * 1000 * 1000
        const val RECOMMENDED_RAM_BYTES = 4L * 1000 * 1000 * 1000
        const val MIN_FREE_BYTES = 500L * 1000 * 1000

        private const val LOOP_VI = "Vui lòng khởi động lại máy tính và thử lại."
        private const val LOOP_EN = "Please restart your computer and try again."

        /** RAM is compared in decimal GB: a "4 GB" phone reports about 3.6-3.8 GiB usable. */
        fun verdict(totalRam: Long, freeBytes: Long): String = when {
            totalRam < MIN_RAM_BYTES * 85 / 100 -> "DƯỚI cấu hình tối thiểu (RAM dưới 3 GB): app có thể bị hệ thống đóng ở màn Hội thoại"
            freeBytes < MIN_FREE_BYTES -> "Bộ nhớ trong còn ít (dưới 500 MB): lịch sử và cập nhật có thể lỗi"
            totalRam < RECOMMENDED_RAM_BYTES * 85 / 100 -> "ĐẠT tối thiểu (3 GB); nên đóng app khác khi dùng Hội thoại"
            else -> "ĐẠT cấu hình khuyến nghị (từ 4 GB RAM)"
        }

        private fun gb(bytes: Long) = String.format(Locale.ROOT, "%.1f GB", bytes / 1e9)
        private fun sec(ms: Long) = if (ms < 0) "lỗi" else String.format(Locale.ROOT, "%.1f s", ms / 1000.0)
        private fun rtf(ms: Long, audioMs: Long) = if (audioMs <= 0) "–" else String.format(Locale.ROOT, "%.2f", ms.toDouble() / audioMs)
    }
}
