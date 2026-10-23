package vn.viettechtrans.app.turn

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import vn.viettechtrans.app.AppContainer
import vn.viettechtrans.app.mt.CoreMt
import vn.viettechtrans.app.mt.Direction
import vn.viettechtrans.app.mt.Lang
import vn.viettechtrans.app.mt.MtException
import vn.viettechtrans.app.mt.MtInputNormalizer
import vn.viettechtrans.app.mt.TranslationResult
import vn.viettechtrans.app.mt.rules.OutputCheck
import vn.viettechtrans.app.mt.rules.RuleNote
import vn.viettechtrans.app.speech.ListenConfig
import vn.viettechtrans.app.speech.ListenOutcome
import vn.viettechtrans.app.speech.Listener
import vn.viettechtrans.app.speech.Speaker
import vn.viettechtrans.app.speech.nowMs
import vn.viettechtrans.app.text.SttPostProcessor

/** Result of the translation step of one turn. */
sealed interface MtOutcome {
    /** [notes]: output rules that fired (R08 lost terms, R11 repeats collapsed); [checkTerms]: the terms R08 lost. */
    data class Ok(
        val text: String,
        val mtMs: Long,
        val notes: List<RuleNote> = emptyList(),
        val checkTerms: List<String> = emptyList(),
    ) : MtOutcome

    data object NoTranslator : MtOutcome
    data class Failed(val message: String) : MtOutcome

    /** R12: the model answered but the output is structurally invalid (empty, or lost a URL/e-mail/code/HTTP status). */
    data class Invalid(val message: String, val mtMs: Long) : MtOutcome
}

/**
 * The steps of one turn shared by the Dịch and Hội thoại screens:
 * listen (mic → VAD → STT), clean the transcript, translate, speak.
 */
class TurnRunner(private val c: AppContainer) {
    companion object {
        const val MT_WATCHDOG_MS = 10_000L

        /** Policy R12 message. */
        const val INVALID_OUTPUT = "Không thể tạo bản dịch hợp lệ. Hãy thử lại hoặc nhập câu rõ hơn."
    }

    val mic get() = c.mic

    suspend fun hasTranslator(): Boolean = c.translators.active() != null

    suspend fun listen(
        lang: Lang,
        stop: Listener.StopHandle,
        onPartial: (String) -> Unit,
        onLevel: (Float) -> Unit,
        cfg: ListenConfig = ListenConfig(),
    ): ListenOutcome = c.listener.listen(lang, cfg, stop, onPartial, onLevel)

    fun clean(heard: ListenOutcome.Heard, lang: Lang): SttPostProcessor.Result =
        SttPostProcessor.process(heard.raw, lang, heard.outputStyle)

    /**
     * Normalizes the input (R01), runs the active translator, then the output checks of the text-rule policy:
     * R12 rejects a structurally invalid output, R08 flags lost lexicon terms (the output itself is never changed).
     * Both are logged to [AppContainer.ruleLog]. Calls [onSlow] after 10 s.
     */
    suspend fun translate(text: String, direction: Direction, onSlow: () -> Unit = {}): MtOutcome = coroutineScope {
        val translator = c.translators.active() ?: return@coroutineScope MtOutcome.NoTranslator
        val watchdog = launch {
            delay(MT_WATCHDOG_MS)
            onSlow()
        }
        val t0 = nowMs()
        val source = MtInputNormalizer.normalize(text)
        try {
            translator.prepare(setOf(direction))
            val r = translator.translate(direction, source)
            val ms = nowMs() - t0
            when (val check = c.textRules.checkOutput(source, r.text, direction)) {
                is OutputCheck.Invalid -> {
                    log("R12_invalid_output", direction, source, r, ms) {
                        put("reason", check.reason)
                        put("missing", JsonArray(check.missing.map { JsonPrimitive("${it.kind.name}:${it.text}") }))
                    }
                    MtOutcome.Invalid(INVALID_OUTPUT, ms)
                }
                is OutputCheck.Valid -> {
                    if (check.checkTerms.isNotEmpty()) {
                        log("R08_check_terms", direction, source, r, ms) { put("terms", JsonArray(check.checkTerms.map(::JsonPrimitive))) }
                    }
                    val notes = listOfNotNull(
                        RuleNote.CHECK_TERMS.takeIf { check.checkTerms.isNotEmpty() },
                        RuleNote.REPEATS_COLLAPSED.takeIf { r.repeatCleanupApplied },
                    )
                    MtOutcome.Ok(r.text, ms, notes, check.checkTerms)
                }
            }
        } catch (e: MtException) {
            MtOutcome.Failed(e.message ?: "Lỗi bộ dịch")
        } finally {
            watchdog.cancel()
        }
    }

    /** R06 "Vẫn dịch": the flag language_mismatch_overridden (policy: "ghi cờ … vào log test"). */
    suspend fun logOverride(text: String, direction: Direction, guess: String) = safely {
        c.ruleLog.append(
            base("R06_language_mismatch_overridden", direction, MtInputNormalizer.normalize(text)).plus(
                mapOf("language_guess" to JsonPrimitive(guess), "language_mismatch_overridden" to JsonPrimitive(true)),
            ).let(::JsonObject),
        )
    }

    /** The evidence log never decides a translation: a full or broken disk only loses the log entry. */
    private suspend fun safely(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("TurnRunner", "rule log: ${e.message}")
        }
    }

    private suspend fun log(event: String, direction: Direction, source: String, r: TranslationResult, ms: Long, extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = safely {
        val fields = buildJsonObject {
            put("output_raw", r.rawText ?: r.text)
            put("output", r.text)
            put("repeat_cleanup_applied", r.repeatCleanupApplied)
            put("source_ids_count", r.details["input_tokens"] ?: -1)
            put("segment_count", r.details["segments"] ?: -1)
            put("mt_ms", ms)
            extra()
        }
        c.ruleLog.append(JsonObject(base(event, direction, source) + fields))
    }

    /** Package and app facts of every log entry (policy §8: package ID, manifest/model hash). */
    private suspend fun base(event: String, direction: Direction, source: String): Map<String, kotlinx.serialization.json.JsonElement> {
        val core = c.coreMt() as? CoreMt.Available
        val spec = core?.manifest?.directions?.get(direction.id)
        return buildJsonObject {
            put("event", event)
            put("direction", direction.id)
            put("input", source)
            put("package_id", core?.manifest?.packageId)
            put("package_version", core?.manifest?.packageVersion)
            put("manifest_sha256", core?.manifestSha256)
            put("model_sha256", spec?.let { s -> core.manifest.files.firstOrNull { it.path == s.model }?.sha256 })
            put("lexicon_version", c.textRules.lexicon.version)
            put("app_version", c.buildInfo.versionName)
            put("git_sha", c.buildInfo.gitSha)
        }
    }

    suspend fun speak(text: String, lang: Lang): Speaker.Result = c.speaker.speak(text, lang)

    fun stopSpeaking() = c.speaker.stop()

    /** The microphone is shared: wait until the previous capture thread has released it. */
    suspend fun awaitMicFree(timeoutMs: Long = 2_000) {
        val deadline = nowMs() + timeoutMs
        while (c.mic.isRunning && nowMs() < deadline) delay(10)
    }
}
