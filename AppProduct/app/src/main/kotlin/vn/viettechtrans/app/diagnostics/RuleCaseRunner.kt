package vn.viettechtrans.app.diagnostics

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import vn.viettechtrans.app.AppContainer
import vn.viettechtrans.app.mt.CoreMt
import vn.viettechtrans.app.mt.Direction
import vn.viettechtrans.app.mt.MtException
import vn.viettechtrans.app.mt.Translator
import vn.viettechtrans.app.mt.rules.InputDecision
import vn.viettechtrans.app.mt.rules.OutputCheck
import vn.viettechtrans.app.mt.rules.RuleCase
import vn.viettechtrans.app.mt.rules.RuleCases
import vn.viettechtrans.app.mt.rules.TextRules
import vn.viettechtrans.app.speech.nowMs

/**
 * Tier 3 of the policy evidence (docs/contracts/mt_app_rule_policy_v1.md §8, "APK Android sau toàn bộ rule"): every case
 * of assets/text/mt_rule_cases_v1.json through the app's own path (the input rules, the packaged model with R09–R11,
 * the output checks R08/R12), with an R06 question answered as the case says. One JSON line per case with the fields
 * §8 asks for, written to the app's external files dir (`adb pull /sdcard/Android/data/<app>/files/evidence/…`) and
 * summarized for copying. The run-time rule log is not touched.
 */
class RuleCaseRunner(private val c: AppContainer, private val context: Context) {
    class Result(val summary: String, val file: File?)

    suspend fun run(progress: (String) -> Unit): Result {
        val cases = withContext(Dispatchers.IO) {
            RuleCases.parse(Json.parseToJsonElement(context.assets.open(RuleCases.ASSET).use { it.readBytes().decodeToString() }))
        }
        val core = c.coreMt() as? CoreMt.Available
        val translator = c.translators.active()
        val stamp = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        val lines = StringBuilder()
        var asExpected = 0
        val checks = ArrayList<String>()
        for ((i, k) in cases.withIndex()) {
            progress("Case ${i + 1}/${cases.size}: ${k.id}")
            val (line, ok, check) = runCase(k, core, translator, stamp)
            if (ok) asExpected++
            check?.let { checks += it }
            lines.appendLine(line)
        }
        val file = withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "evidence").apply { mkdirs() }
                File(dir, "mt_rules_v1_$stamp.jsonl").apply { writeText(lines.toString(), Charsets.UTF_8) }
            }.getOrNull()
        }
        val summary = buildString {
            appendLine("Bộ case rule dịch v1: $asExpected/${cases.size} case đúng quyết định của policy (R02–R07)")
            checks.forEach { appendLine(it) }
            appendLine("Gói ${core?.manifest?.packageId ?: "không có"} · lexicon ${c.textRules.lexicon.version} · ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine(file?.let { "Đã lưu: ${it.absolutePath}" } ?: "Không lưu được file kết quả")
        }
        progress(summary)
        return Result(summary, file)
    }

    /** One case: its JSON line, whether the input decision is the expected one, and the output check line if any. */
    private suspend fun runCase(k: RuleCase, core: CoreMt.Available?, translator: Translator?, stamp: String): Triple<String, Boolean, String?> {
        val rules = c.textRules
        val first = rules.decide(k.input, k.direction)
        var direction = k.direction
        var final = first
        if (first is InputDecision.Mismatch && k.answer != null) {
            direction = if (k.answer == "switch") k.direction.reverse else k.direction
            final = rules.decide(k.input, direction, override = k.answer == "force", skipMismatch = k.answer == "switch")
        }
        val kind = kindOf(first)
        val ok = kind == k.expect && (first !is InputDecision.Keep || first.note.rule == k.rule) &&
            (first !is InputDecision.Translate || first.notes.map { it.name }.containsAll(k.notes) && first.notes.none { it.name in k.noNotes })
        var checkLine: String? = null
        val fields = buildJsonObject {
            put("id", k.id)
            put("rule", k.rule)
            put("direction_chosen", k.direction.id)
            put("expect", k.expect)
            put("decision", kind)
            put("as_expected", ok)
            if (first is InputDecision.Mismatch) put("language_guess", first.guess.id)
            k.answer?.let { put("answer", it); put("direction_final", direction.id) }
            // R06 "Vẫn dịch" is logged with this flag in the app (TurnRunner.logOverride); the case run records it too.
            if (first is InputDecision.Mismatch && k.answer == "force") put("language_mismatch_overridden", true)
            put("input_normalized", final.text)
            put("display_chars", TextRules.displayLength(final.text))
            put("notes_input", JsonArray(notesOf(final).map(::JsonPrimitive)))
            when (final) {
                is InputDecision.Keep -> put("output", final.text)
                is InputDecision.Translate -> if (translator == null) put("output", null as String?) else {
                    checkLine = translate(this, k, final.text, direction, translator)
                }
                else -> Unit
            }
            facts(this, core, direction, stamp)
        }
        return Triple(fields.toString(), ok, checkLine)
    }

    /** Model call and output checks of one case, as TurnRunner.translate does them (without the run-time log). */
    private suspend fun translate(o: JsonObjectBuilder, k: RuleCase, text: String, direction: Direction, translator: Translator): String? {
        val (r, ms) = try {
            translator.prepare(setOf(direction))
            val t0 = nowMs()
            translator.translate(direction, text) to nowMs() - t0
        } catch (e: MtException) {
            o.put("error", e.message ?: e.javaClass.simpleName)
            return k.check?.let { "${k.id} $it: lỗi bộ dịch" }
        }
        o.put("source_ids", JsonArray(r.sourceIds.map { seg -> JsonArray(seg.map { JsonPrimitive(it) }) }))
        o.put("source_ids_count", r.sourceIds.sumOf { it.size })
        o.put("segment_count", r.sourceIds.size)
        o.put("output_raw", r.rawText ?: r.text)
        o.put("output", r.text)
        o.put("repeat_cleanup_applied", r.repeatCleanupApplied)
        o.put("mt_ms", ms)
        val check = c.textRules.checkOutput(text, r.text, direction)
        when (check) {
            is OutputCheck.Invalid -> {
                o.put("output_check", "R12_invalid")
                o.put("missing", JsonArray(check.missing.map { JsonPrimitive("${it.kind.name}:${it.text}") }))
            }
            is OutputCheck.Valid -> {
                o.put("output_check", if (check.checkTerms.isEmpty()) "valid" else "R08_check_terms")
                o.put("check_terms", JsonArray(check.checkTerms.map(::JsonPrimitive)))
            }
        }
        return k.check?.let { "${k.id} $it: " + outcome(it, r.sourceIds.size, r.repeatCleanupApplied, check) }
    }

    /** Package, lexicon, app and device facts (§8: package ID, manifest/model hash). */
    private fun facts(o: JsonObjectBuilder, core: CoreMt.Available?, direction: Direction, stamp: String) {
        o.put("package_id", core?.manifest?.packageId)
        o.put("package_version", core?.manifest?.packageVersion)
        o.put("manifest_sha256", core?.manifestSha256)
        o.put("model_sha256", core?.manifest?.directions?.get(direction.id)?.let { s -> core.manifest.files.firstOrNull { it.path == s.model }?.sha256 })
        o.put("lexicon_version", c.textRules.lexicon.version)
        o.put("app_version", c.buildInfo.versionName)
        o.put("git_sha", c.buildInfo.gitSha)
        o.put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        o.put("abi", Build.SUPPORTED_ABIS.firstOrNull())
        o.put("is_emulator", Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu") || Build.PRODUCT.contains("sdk"))
        o.put("run", stamp)
    }

    private fun kindOf(d: InputDecision) = when (d) {
        is InputDecision.Keep -> "keep"
        is InputDecision.Translate -> "translate"
        is InputDecision.Mismatch -> "mismatch"
        is InputDecision.TooLong -> "too_long"
        is InputDecision.Empty -> "empty"
    }

    private fun notesOf(d: InputDecision) = when (d) {
        is InputDecision.Keep -> listOf(d.note.name)
        is InputDecision.Translate -> d.notes.map { it.name }
        else -> emptyList()
    }

    private fun outcome(check: String, segments: Int, collapsed: Boolean, out: OutputCheck): String = when (check) {
        "R09" -> "$segments đoạn"
        "R11" -> if (collapsed) "đã gộp lặp" else "mô hình không lặp"
        "R08" -> (out as? OutputCheck.Valid)?.checkTerms?.let { if (it.isEmpty()) "giữ đủ thuật ngữ" else "cần kiểm tra ${it.joinToString()}" } ?: "R12"
        "R12" -> if (out is OutputCheck.Invalid) "bản dịch không hợp lệ (${out.missing.joinToString { it.text }})" else "literal còn nguyên"
        else -> ""
    }
}
