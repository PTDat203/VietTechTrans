package vn.viettechtrans.app.speech

import android.os.SystemClock
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A complete speech segment found by the VAD: [start] is an absolute sample index of the session. */
data class VoiceSegment(val start: Int, val length: Int)

/** Voice activity detector seen by [ListenSession] (Silero in the app, a fake in JVM tests). */
interface VoiceDetector {
    fun accept(frame: FloatArray)
    fun isSpeech(): Boolean
    fun pollSegments(): List<VoiceSegment>
    fun flush(): List<VoiceSegment>
    fun release()
}

/** Endpointing parameters (plan "Giá trị khởi điểm"; tuned by TN-03). */
data class ListenConfig(
    val sampleRate: Int = SpeechParams.SAMPLE_RATE,
    val endSilenceMs: Int = 1_500,
    val noSpeechTimeoutMs: Int = 6_000,
    val maxTurnMs: Int = 60_000,
    val preRollMs: Int = 400,
    val postRollMs: Int = 200,
    /** 0 disables live partial text. */
    val partialIntervalMs: Int = 500,
)

enum class EndReason { USER_STOP, END_SILENCE, MAX_TURN, NO_SPEECH }

/**
 * Turn logic of one listening session, independent of Android and sherpa:
 * frames in, VAD segments out, endpoint decisions, and which audio to decode.
 * Not thread-safe: call it from one thread.
 */
class ListenSession(private val vad: VoiceDetector, private val cfg: ListenConfig) {
    private var buffer = FloatArray(cfg.sampleRate * 10)
    var samples = 0
        private set

    private var hadSpeech = false
    private var lastSpeechAt = -1
    private var speechStartedAt = -1
    private var lastPartialAt = 0
    private val segments = mutableListOf<VoiceSegment>()

    var lastRms = 0f
        private set

    val elapsedMs: Int get() = samples * 1000 / cfg.sampleRate

    private fun ms(n: Int) = n * cfg.sampleRate / 1000

    /** Adds a frame; returns the end reason when the turn should end automatically. */
    fun accept(frame: FloatArray): EndReason? {
        ensureCapacity(samples + frame.size)
        frame.copyInto(buffer, samples)
        samples += frame.size
        lastRms = rms(frame)

        vad.accept(frame)
        val speech = vad.isSpeech()
        if (speech) {
            // Start of the not-yet-finalized speech region (for live partial text).
            if (speechStartedAt < 0) speechStartedAt = samples - frame.size
            hadSpeech = true
            lastSpeechAt = samples
        }
        segments += vad.pollSegments()

        return when {
            samples >= ms(cfg.maxTurnMs) -> EndReason.MAX_TURN
            !hadSpeech && samples >= ms(cfg.noSpeechTimeoutMs) -> EndReason.NO_SPEECH
            hadSpeech && !speech && samples - lastSpeechAt >= ms(cfg.endSilenceMs) -> EndReason.END_SILENCE
            else -> null
        }
    }

    val heardSpeech: Boolean get() = hadSpeech

    /** Segments completed so far (in order); [takeSegments] removes them. */
    fun takeSegments(): List<VoiceSegment> = segments.toList().also { segments.clear() }

    /** Call once at the end of the turn: returns the remaining segments. */
    fun finish(): List<VoiceSegment> {
        segments += vad.flush()
        return takeSegments()
    }

    /** Audio of [segment] with pre/post roll taken from the session buffer. */
    fun audioOf(segment: VoiceSegment): FloatArray {
        val from = max(0, segment.start - ms(cfg.preRollMs))
        val to = min(samples, segment.start + segment.length + ms(cfg.postRollMs))
        return buffer.copyOfRange(from, max(from, to))
    }

    /**
     * Audio of the segment being spoken right now, when a live partial is due
     * (every [ListenConfig.partialIntervalMs]); null otherwise.
     */
    fun partialAudioIfDue(): FloatArray? {
        if (cfg.partialIntervalMs <= 0 || speechStartedAt < 0) return null
        if (samples - lastPartialAt < ms(cfg.partialIntervalMs)) return null
        lastPartialAt = samples
        val from = max(0, speechStartedAt - ms(cfg.preRollMs))
        return buffer.copyOfRange(from, samples)
    }

    /** The current in-progress segment ended (a final segment covers it now). */
    fun resetPartial() {
        speechStartedAt = if (vad.isSpeech()) samples else -1
    }

    private fun ensureCapacity(n: Int) {
        if (n > buffer.size) buffer = buffer.copyOf(max(n, buffer.size * 2))
    }

    private fun rms(frame: FloatArray): Float {
        var sum = 0.0
        for (s in frame) sum += s * s
        return sqrt(sum / max(1, frame.size)).toFloat()
    }
}

/** Silero VAD of sherpa-onnx behind [VoiceDetector]. */
class SileroVoiceDetector(private val vad: com.k2fsa.sherpa.onnx.Vad) : VoiceDetector {
    override fun accept(frame: FloatArray) = vad.acceptWaveform(frame)
    override fun isSpeech(): Boolean = vad.isSpeechDetected()

    override fun pollSegments(): List<VoiceSegment> {
        val out = mutableListOf<VoiceSegment>()
        while (!vad.empty()) {
            val seg = vad.front()
            out += VoiceSegment(seg.start, seg.samples.size)
            vad.pop()
        }
        return out
    }

    override fun flush(): List<VoiceSegment> {
        vad.flush()
        return pollSegments()
    }

    override fun release() = vad.release()
}

/** Monotonic milliseconds for turn timings. */
fun nowMs(): Long = SystemClock.elapsedRealtime()
