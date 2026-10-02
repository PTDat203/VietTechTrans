package vn.viettechtrans.app.turn

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import vn.viettechtrans.app.AppContainer
import vn.viettechtrans.app.mt.Direction
import vn.viettechtrans.app.mt.Lang
import vn.viettechtrans.app.mt.MtException
import vn.viettechtrans.app.mt.MtInputNormalizer
import vn.viettechtrans.app.speech.ListenConfig
import vn.viettechtrans.app.speech.ListenOutcome
import vn.viettechtrans.app.speech.Listener
import vn.viettechtrans.app.speech.Speaker
import vn.viettechtrans.app.speech.nowMs
import vn.viettechtrans.app.text.SttPostProcessor

/** Result of the translation step of one turn. */
sealed interface MtOutcome {
    data class Ok(val text: String, val mtMs: Long) : MtOutcome
    data object NoTranslator : MtOutcome
    data class Failed(val message: String) : MtOutcome
}

/**
 * The steps of one turn shared by the Dịch and Hội thoại screens:
 * listen (mic → VAD → STT), clean the transcript, translate, speak.
 */
class TurnRunner(private val c: AppContainer) {
    companion object {
        const val MT_WATCHDOG_MS = 10_000L
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

    /** Normalizes the input (Phase 01 policy), runs the active translator, calls [onSlow] after 10 s. */
    suspend fun translate(text: String, direction: Direction, onSlow: () -> Unit = {}): MtOutcome = coroutineScope {
        val translator = c.translators.active() ?: return@coroutineScope MtOutcome.NoTranslator
        val watchdog = launch {
            delay(MT_WATCHDOG_MS)
            onSlow()
        }
        val t0 = nowMs()
        try {
            translator.prepare(setOf(direction))
            val out = translator.translate(direction, MtInputNormalizer.normalize(text)).text
            MtOutcome.Ok(out, nowMs() - t0)
        } catch (e: MtException) {
            MtOutcome.Failed(e.message ?: "Lỗi bộ dịch")
        } finally {
            watchdog.cancel()
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
