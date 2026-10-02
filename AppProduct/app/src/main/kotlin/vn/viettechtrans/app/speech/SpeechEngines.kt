package vn.viettechtrans.app.speech

import android.content.Context
import android.os.Process
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import vn.viettechtrans.app.mt.Lang

/** Starting values of the plan (written into evidence later; tuned by TN experiments). */
object SpeechParams {
    const val SAMPLE_RATE = 16_000
    const val VAD_THRESHOLD = 0.5f
    const val VAD_MIN_SILENCE_S = 0.6f
    const val VAD_MIN_SPEECH_S = 0.25f
    const val VAD_MAX_SPEECH_S = 20f // Kotlin default 5 s splits sentences
    const val STT_THREADS = 2

    /** TTS speed decides whether speech plays smoothly: use up to 4 cores (half of them). */
    val TTS_THREADS: Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
}

internal fun singleThread(name: String): CoroutineDispatcher =
    Executors.newSingleThreadExecutor { r -> Thread(r, name).apply { isDaemon = true } }.asCoroutineDispatcher()

/** Model loading runs at background priority so the UI stays responsive while it parses ~60–80 MB files. */
private inline fun <T> backgroundPriority(block: () -> T): T {
    val tid = Process.myTid()
    val old = Process.getThreadPriority(tid)
    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
    try {
        return block()
    } finally {
        Process.setThreadPriority(old)
    }
}

/**
 * Reference count around a native object: [close] is deferred until no user holds
 * it, and a closed handle refuses new users, so native memory is never freed while
 * a decode or synthesis runs (that would crash in native code).
 */
open class NativeLease(private val onClose: () -> Unit) {
    private var users = 0
    private var closeRequested = false
    var closed = false
        private set

    fun acquire(): Boolean = synchronized(this) {
        if (closed || closeRequested) false else { users++; true }
    }

    fun releaseUse() {
        val closeNow = synchronized(this) {
            users--
            closeRequested && users == 0 && !closed && run { closed = true; true }
        }
        if (closeNow) onClose()
    }

    fun requestClose() {
        val closeNow = synchronized(this) {
            closeRequested = true
            users == 0 && !closed && run { closed = true; true }
        }
        if (closeNow) onClose()
    }

    val inUse: Boolean get() = synchronized(this) { users > 0 }
}

class EngineClosedException : IllegalStateException("Mô hình đã được giải phóng để tiết kiệm RAM; thử lại")

/** One loaded recognizer. sherpa objects are not thread-safe: every call runs on [dispatcher]. */
class SttEngine internal constructor(
    val lang: Lang,
    val modelId: String,
    /** "upper_no_punct" (zipformer-vi) or "cased_punct" (moonshine). */
    val outputStyle: String,
    private val recognizer: OfflineRecognizer,
    private val dispatcher: CoroutineDispatcher,
    val loadMs: Long,
) {
    val lease = NativeLease { recognizer.release() }

    suspend fun decode(samples: FloatArray, sampleRate: Int = SpeechParams.SAMPLE_RATE): String {
        if (!lease.acquire()) throw EngineClosedException()
        try {
            return withContext(dispatcher) {
                val stream = recognizer.createStream()
                try {
                    stream.acceptWaveform(samples, sampleRate)
                    recognizer.decode(stream)
                    recognizer.getResult(stream).text.trim()
                } finally {
                    stream.release()
                }
            }
        } finally {
            lease.releaseUse()
        }
    }
}

/** One loaded Piper/VITS voice; synthesis runs on the shared TTS thread (espeak-ng has global state). */
class TtsEngine internal constructor(
    val lang: Lang,
    val voiceId: String,
    internal val tts: OfflineTts,
    val sampleRate: Int,
    val loadMs: Long,
) {
    val lease = NativeLease { tts.release() }
}

enum class EngineRole(val manifestRole: String) {
    STT_VI(ModelManifest.STT_VI), STT_EN(ModelManifest.STT_EN),
    TTS_VI(ModelManifest.TTS_VI), TTS_EN(ModelManifest.TTS_EN),
    ;

    companion object {
        fun stt(lang: Lang) = if (lang == Lang.VI) STT_VI else STT_EN
        fun tts(lang: Lang) = if (lang == Lang.VI) TTS_VI else TTS_EN
    }
}

/**
 * Loads the bundled speech models on demand (from APK assets, never from the
 * network). Loading is serialized because a load briefly needs about twice the
 * file size in RAM. Screens declare what they keep with [retainOnly]; everything
 * else is freed once idle (RAM policy for ~4 GB phones).
 */
class SpeechEngines(private val context: Context) {
    val manifest: ModelManifest by lazy { ModelManifest.load(context.assets) }

    private val sttThreads = mapOf(Lang.VI to singleThread("stt-vi"), Lang.EN to singleThread("stt-en"))
    val ttsDispatcher: CoroutineDispatcher = singleThread("tts")

    private val loadMutex = Mutex()
    private val stt = mutableMapOf<Lang, SttEngine>()
    private val tts = mutableMapOf<Lang, TtsEngine>()

    private val _loading = MutableStateFlow<Set<EngineRole>>(emptySet())

    /** Roles currently being loaded (UI shows "Đang nạp mô hình…"). */
    val loading: StateFlow<Set<EngineRole>> = _loading

    private val _loaded = MutableStateFlow<Map<EngineRole, Long>>(emptyMap())

    /** Loaded roles with their load time in ms. */
    val loaded: StateFlow<Map<EngineRole, Long>> = _loaded

    fun isLoaded(role: EngineRole): Boolean = role in _loaded.value

    suspend fun stt(lang: Lang): SttEngine {
        synchronized(stt) { stt[lang] }?.let { return it }
        val role = EngineRole.stt(lang)
        return loadMutex.withLock {
            synchronized(stt) { stt[lang] } ?: tracked(role) {
                withContext(sttThreads.getValue(lang)) { backgroundPriority { createStt(lang) } }
            }.also { engine ->
                synchronized(stt) { stt[lang] = engine }
                _loaded.update { it + (role to engine.loadMs) }
            }
        }
    }

    suspend fun tts(lang: Lang): TtsEngine {
        synchronized(tts) { tts[lang] }?.let { return it }
        val role = EngineRole.tts(lang)
        return loadMutex.withLock {
            synchronized(tts) { tts[lang] } ?: tracked(role) {
                val dataDir = withContext(Dispatchers.IO) { EspeakDataInstaller.ensure(context, manifest) }
                withContext(ttsDispatcher) { backgroundPriority { createTts(lang, dataDir.absolutePath) } }
            }.also { engine ->
                synchronized(tts) { tts[lang] = engine }
                _loaded.update { it + (role to engine.loadMs) }
            }
        }
    }

    /** A fresh VAD per listening session (0.6 MB model, cheap to create). Use it from one thread only. */
    fun newVad(): Vad {
        val role = manifest.role(ModelManifest.VAD)
        return Vad(
            assetManager = context.assets,
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = role.file("model"),
                    threshold = SpeechParams.VAD_THRESHOLD,
                    minSilenceDuration = SpeechParams.VAD_MIN_SILENCE_S,
                    minSpeechDuration = SpeechParams.VAD_MIN_SPEECH_S,
                    windowSize = 512,
                    maxSpeechDuration = SpeechParams.VAD_MAX_SPEECH_S,
                ),
                sampleRate = SpeechParams.SAMPLE_RATE,
                numThreads = 1,
            ),
        )
    }

    /** Frees every model not in [keep] (freed when no longer in use). */
    fun retainOnly(keep: Set<EngineRole>) {
        val dropStt = synchronized(stt) {
            stt.keys.filter { EngineRole.stt(it) !in keep }.mapNotNull { stt.remove(it) }
        }
        val dropTts = synchronized(tts) {
            tts.keys.filter { EngineRole.tts(it) !in keep }.mapNotNull { tts.remove(it) }
        }
        dropStt.forEach { it.lease.requestClose() }
        dropTts.forEach { it.lease.requestClose() }
        val dropped = dropStt.map { EngineRole.stt(it.lang) } + dropTts.map { EngineRole.tts(it.lang) }
        if (dropped.isNotEmpty()) _loaded.update { it - dropped.toSet() }
    }

    /** Low-memory / background: free all models (in-use ones once their work ends). */
    fun releaseAll() = retainOnly(emptySet())

    private suspend fun <T> tracked(role: EngineRole, block: suspend () -> T): T {
        _loading.update { it + role }
        try {
            return block()
        } finally {
            _loading.update { it - role }
        }
    }

    private fun createStt(lang: Lang): SttEngine {
        val started = SystemClock.elapsedRealtime()
        val role = manifest.role(EngineRole.stt(lang).manifestRole)
        val modelConfig = when (role.text("kind")) {
            "offline_transducer" -> OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = role.file("encoder"),
                    decoder = role.file("decoder"),
                    joiner = role.file("joiner"),
                ),
                tokens = role.file("tokens"),
                modelType = "transducer",
                numThreads = SpeechParams.STT_THREADS,
            )
            "offline_moonshine_v2" -> OfflineModelConfig(
                moonshine = OfflineMoonshineModelConfig(
                    encoder = role.file("encoder"),
                    mergedDecoder = role.file("merged_decoder"),
                ),
                tokens = role.file("tokens"),
                numThreads = SpeechParams.STT_THREADS,
            )
            else -> error("Loại STT chưa hỗ trợ: ${role.text("kind")}")
        }
        val recognizer = OfflineRecognizer(
            assetManager = context.assets,
            config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SpeechParams.SAMPLE_RATE, featureDim = 80),
                modelConfig = modelConfig,
                decodingMethod = "greedy_search",
            ),
        )
        return SttEngine(
            lang = lang,
            modelId = role.artifactId,
            outputStyle = role.text("output_style") ?: "cased_punct",
            recognizer = recognizer,
            dispatcher = sttThreads.getValue(lang),
            loadMs = SystemClock.elapsedRealtime() - started,
        )
    }

    private fun createTts(lang: Lang, dataDir: String): TtsEngine {
        val started = SystemClock.elapsedRealtime()
        val role = manifest.role(EngineRole.tts(lang).manifestRole)
        val engine = OfflineTts(
            assetManager = context.assets,
            config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = role.file("model"),
                        tokens = role.file("tokens"),
                        dataDir = dataDir,
                        noiseScale = role.float("noise_scale", 0.667f),
                        noiseScaleW = role.float("noise_scale_w", 0.8f),
                        lengthScale = role.float("length_scale", 1.0f),
                    ),
                    numThreads = SpeechParams.TTS_THREADS,
                ),
                maxNumSentences = 1,
            ),
        )
        return TtsEngine(
            lang = lang,
            voiceId = role.artifactId,
            tts = engine,
            sampleRate = engine.sampleRate(),
            loadMs = SystemClock.elapsedRealtime() - started,
        )
    }
}
