package vn.viettechtrans.app.speech

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.viettechtrans.app.audio.MicRecorder
import vn.viettechtrans.app.mt.Lang

/** Result of one listening turn. Timings in milliseconds. */
sealed interface ListenOutcome {
    data class Heard(
        val raw: String,
        val outputStyle: String,
        val audioMs: Int,
        val endReason: EndReason,
        /** Capture end -> final text. */
        val sttMs: Long,
        val firstPartialMs: Long?,
        val modelId: String,
    ) : ListenOutcome

    data object NoSpeech : ListenOutcome
    data class Failed(val message: String) : ListenOutcome
}

/**
 * Mic -> Silero VAD -> STT for one turn ("simulated streaming"): completed VAD
 * segments are decoded as they finish; the segment being spoken is re-decoded
 * every [ListenConfig.partialIntervalMs] for live text. All session state lives on
 * one "vad" thread; decoding runs on the STT thread of the language.
 */
class Listener(private val engines: SpeechEngines, private val mic: MicRecorder) {
    private val vadDispatcher = singleThread("vad")

    /** Set to end the current turn (button tapped again, or another speaker cuts in). */
    class StopHandle {
        internal val requested = AtomicBoolean(false)
        fun stop() = requested.set(true)
    }

    suspend fun listen(
        lang: Lang,
        cfg: ListenConfig,
        stop: StopHandle,
        onPartial: (String) -> Unit,
        onLevel: (Float) -> Unit,
    ): ListenOutcome {
        val stt = try {
            engines.stt(lang)
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return ListenOutcome.Failed("Không tải được mô hình nhận dạng: ${e.message ?: e.javaClass.simpleName}")
        }
        // Keep the model for the whole turn (a screen change cannot free it mid-turn).
        if (!stt.lease.acquire()) return ListenOutcome.Failed("Mô hình nhận dạng vừa được giải phóng; thử lại")
        return try {
            listenWith(stt, cfg, stop, onPartial, onLevel)
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            ListenOutcome.Failed("Lỗi khi nhận dạng: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            stt.lease.releaseUse()
        }
    }

    private suspend fun listenWith(
        stt: SttEngine,
        cfg: ListenConfig,
        stop: StopHandle,
        onPartial: (String) -> Unit,
        onLevel: (Float) -> Unit,
    ): ListenOutcome = coroutineScope {
        val vad = try {
            withContext(vadDispatcher) { SileroVoiceDetector(engines.newVad()) }
        } catch (e: Exception) {
            return@coroutineScope ListenOutcome.Failed("Không tải được VAD: ${e.message}")
        }
        val session = ListenSession(vad, cfg)
        val frames = Channel<FloatArray>(Channel.UNLIMITED)
        val micError = AtomicReference<String?>(null)
        val finals = sortedMapOf<Int, String>() // segment start -> text (touched on the vad thread only)
        val decodes = mutableListOf<Deferred<Unit>>()
        var partialJob: Job? = null
        var firstPartialMs: Long? = null
        val started = nowMs()
        var endReason = EndReason.USER_STOP

        fun shownText(partial: String): String =
            (finals.values + partial).filter { it.isNotBlank() }.joinToString(" ")

        // Decodes are children of the outer scope (not of the withContext below) so the
        // capture loop can end without waiting for them; they resume on the vad thread.
        val scope = this
        var frameCount = 0
        mic.start(onFrame = { frames.trySend(it) }, onError = { micError.set(it); frames.close() })
        try {
            withContext(vadDispatcher) {
                for (frame in frames) {
                    if (stop.requested.get()) break
                    val reason = session.accept(frame)
                    // ~10 UI updates per second is enough for the level ring.
                    if (frameCount++ % 3 == 0) onLevel(session.lastRms)

                    val completed = session.takeSegments()
                    if (completed.isNotEmpty()) {
                        for (seg in completed) {
                            val audio = session.audioOf(seg)
                            decodes += scope.async(vadDispatcher) {
                                val text = stt.decode(audio)
                                finals[seg.start] = text
                                onPartial(shownText(""))
                            }
                        }
                        session.resetPartial()
                    }

                    if (partialJob?.isActive != true && decodes.all { it.isCompleted }) {
                        session.partialAudioIfDue()?.let { audio ->
                            partialJob = scope.launch(vadDispatcher) {
                                val text = stt.decode(audio)
                                if (firstPartialMs == null) firstPartialMs = nowMs() - started
                                onPartial(shownText(text))
                            }
                        }
                    }
                    if (reason != null) {
                        endReason = reason
                        break
                    }
                }
            }
        } finally {
            withContext(Dispatchers.IO) { mic.stop() }
            frames.close()
        }
        micError.get()?.let {
            withContext(vadDispatcher) { vad.release() }
            return@coroutineScope ListenOutcome.Failed(it)
        }

        val captureEnd = nowMs()
        val heard = withContext(vadDispatcher) {
            partialJob?.cancel()
            for (seg in session.finish()) {
                val audio = session.audioOf(seg)
                decodes += scope.async(vadDispatcher) { finals[seg.start] = stt.decode(audio) }
            }
            decodes.awaitAll()
            vad.release()
            session.heardSpeech
        }
        val raw = finals.values.filter { it.isNotBlank() }.joinToString(" ")
        if (!heard || raw.isBlank()) return@coroutineScope ListenOutcome.NoSpeech
        ListenOutcome.Heard(
            raw = raw,
            outputStyle = stt.outputStyle,
            audioMs = session.elapsedMs,
            endReason = endReason,
            sttMs = nowMs() - captureEnd,
            firstPartialMs = firstPartialMs,
            modelId = stt.modelId,
        )
    }
}
