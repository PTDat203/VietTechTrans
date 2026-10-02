package vn.viettechtrans.app.speech

import android.os.Process
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import vn.viettechtrans.app.audio.PcmPlayer
import vn.viettechtrans.app.mt.Lang
import vn.viettechtrans.app.text.TtsTextNormalizer

/**
 * Text -> TTS -> speaker. Synthesis (TTS thread) and playback ("audio-out" thread)
 * run in parallel through a queue, so the next sentence is synthesized while the
 * current one plays (no gaps). A new [speak] or [stop] interrupts immediately.
 */
class Speaker(private val engines: SpeechEngines, private val normalizer: TtsTextNormalizer) {
    data class Result(
        /** Start of synthesis -> first samples. */
        val firstAudioMs: Long?,
        val synthMs: Long,
        val audioMs: Long,
        val completed: Boolean,
        val voiceId: String,
    )

    private val player = PcmPlayer()
    private val mutex = Mutex()

    @Volatile
    private var stopRequested = false

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking

    suspend fun speak(text: String, lang: Lang, useLexicon: Boolean = true): Result {
        stop()
        return mutex.withLock {
            stopRequested = false
            val engine = engines.tts(lang)
            if (!engine.lease.acquire()) throw EngineClosedException()
            _speaking.value = true
            try {
                val chunks = normalizer.chunks(normalizer.normalize(text, lang, useLexicon))
                withContext(engines.ttsDispatcher) { synthesizeAndPlay(engine, chunks) }
            } finally {
                engine.lease.releaseUse()
                _speaking.value = false
            }
        }
    }

    fun stop() {
        stopRequested = true
        player.stopNow()
    }

    private fun synthesizeAndPlay(engine: TtsEngine, chunks: List<String>): Result {
        val queue = ArrayBlockingQueue<FloatArray>(64)
        val end = FloatArray(0)
        player.open(engine.sampleRate)
        val writer = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            while (true) {
                val pcm = queue.take()
                if (pcm === end || !player.write(pcm)) break
            }
        }, "audio-out").apply { start() }

        fun enqueue(pcm: FloatArray): Boolean {
            while (!stopRequested && writer.isAlive) {
                if (queue.offer(pcm, 100, TimeUnit.MILLISECONDS)) return true
            }
            return false
        }

        val t0 = nowMs()
        var first: Long? = null
        var samples = 0L
        // Must be a real class, not a lambda: sherpa's JNI looks up
        // `invoke([F)Ljava/lang/Integer;`, which Kotlin 2.x indy lambdas do not have
        // (NoSuchMethodError -> native abort on the first sentence). R8 keeps it via proguard-rules.pro.
        val callback = object : (FloatArray) -> Int {
            override fun invoke(pcm: FloatArray): Int {
                if (first == null) first = nowMs() - t0
                samples += pcm.size
                return if (enqueue(pcm)) 1 else 0
            }
        }
        try {
            for (chunk in chunks) {
                if (stopRequested || !writer.isAlive) break
                engine.tts.generateWithCallback(chunk, 0, 1.0f, callback)
            }
        } finally {
            if (stopRequested) queue.clear()
            while (writer.isAlive && !queue.offer(end, 100, TimeUnit.MILLISECONDS)) {
                if (stopRequested) queue.clear()
            }
        }
        val synthMs = nowMs() - t0
        writer.join(5_000)
        if (!stopRequested) player.drain(engine.sampleRate)
        player.close()
        return Result(first, synthMs, samples * 1000 / engine.sampleRate, !stopRequested, engine.voiceId)
    }
}
