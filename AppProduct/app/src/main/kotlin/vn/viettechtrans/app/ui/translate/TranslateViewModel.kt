package vn.viettechtrans.app.ui.translate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import vn.viettechtrans.app.AppContainer
import vn.viettechtrans.app.mt.Direction
import vn.viettechtrans.app.mt.Lang
import vn.viettechtrans.app.mt.MtException
import vn.viettechtrans.app.mt.MtInputNormalizer
import vn.viettechtrans.app.speech.EngineRole
import vn.viettechtrans.app.speech.ListenConfig
import vn.viettechtrans.app.speech.ListenOutcome
import vn.viettechtrans.app.speech.Listener
import vn.viettechtrans.app.speech.nowMs
import vn.viettechtrans.app.text.SttPostProcessor
import vn.viettechtrans.app.turn.TurnTimings

enum class Phase { IDLE, LOADING, LISTENING, RECOGNIZING, TRANSLATING, SPEAKING }

data class TranslateUiState(
    val direction: Direction = Direction.VI_TO_EN,
    /** Load time of each model already in RAM (ms), shown under the mic. */
    val loadedMs: Map<EngineRole, Long> = emptyMap(),
    val input: String = "",
    val phase: Phase = Phase.IDLE,
    val partial: String = "",
    val level: Float = 0f,
    val translation: String? = null,
    /** Source text that produced [translation] (or the last transcript when there is no translator). */
    val lastSource: String? = null,
    val timings: TurnTimings? = null,
    val notice: String? = null,
    /** null while checking. */
    val hasTranslator: Boolean? = null,
    val loading: Set<EngineRole> = emptySet(),
) {
    /** Recognizing or translating: the source text must not change. Loading/listening still allow typing. */
    val busy: Boolean get() = phase == Phase.RECOGNIZING || phase == Phase.TRANSLATING
}

/** Dịch screen: one person, voice or typing, CUT semantics (a new tap interrupts speech). */
class TranslateViewModel(private val c: AppContainer) : ViewModel() {
    companion object {
        const val MAX_TYPED_CHARS = 1000
        const val MT_WATCHDOG_MS = 10_000L

        /** Let the first frames render before parsing tens of MB of models. */
        const val PRELOAD_DELAY_MS = 1_500L
    }

    private val _state = MutableStateFlow(TranslateUiState())
    val state: StateFlow<TranslateUiState> = _state.asStateFlow()

    private var job: Job? = null
    private var preloadJob: Job? = null
    private var stopHandle: Listener.StopHandle? = null

    /** Any unexpected error in a screen job becomes a notice instead of closing the app. */
    private val errors = CoroutineExceptionHandler { _, e ->
        _state.update { it.copy(phase = Phase.IDLE, partial = "", notice = "Lỗi: ${e.message ?: e.javaClass.simpleName}") }
    }

    private fun launchSafe(block: suspend CoroutineScope.() -> Unit): Job = viewModelScope.launch(errors, block = block)

    init {
        launchSafe {
            val t = c.translators.active()
            _state.update { it.copy(hasTranslator = t != null) }
        }
        launchSafe { c.speechEngines.loading.collect { l -> _state.update { it.copy(loading = l) } } }
        launchSafe { c.speechEngines.loaded.collect { l -> _state.update { it.copy(loadedMs = l) } } }
        launchSafe { c.speaker.speaking.collect { s -> if (!s) _state.update { if (it.phase == Phase.SPEAKING) it.copy(phase = Phase.IDLE) else it } } }
        preload(delayMs = PRELOAD_DELAY_MS)
    }

    /**
     * Keeps only the models of this direction (STT of the source, TTS of the target)
     * and loads them in the background, one at a time, at low priority.
     */
    private fun preload(delayMs: Long) {
        val d = _state.value.direction
        preloadJob?.cancel()
        preloadJob = launchSafe {
            delay(delayMs)
            c.speechEngines.retainOnly(setOf(EngineRole.stt(d.source), EngineRole.tts(d.target)))
            runCatching { c.speechEngines.stt(d.source) }
            runCatching { c.speechEngines.tts(d.target) }
        }
    }

    fun setDirection(direction: Direction) {
        val s = _state.value
        if (s.busy || s.phase == Phase.LISTENING || s.phase == Phase.LOADING || direction == s.direction) return
        c.speaker.stop()
        _state.update { it.copy(direction = direction, translation = null, lastSource = null, timings = null, notice = null) }
        preload(delayMs = 0)
    }

    fun onInput(text: String) {
        if (text.length <= MAX_TYPED_CHARS) _state.update { it.copy(input = text) }
    }

    fun showNotice(text: String) = _state.update { it.copy(notice = text) }
    fun dismissNotice() = _state.update { it.copy(notice = null) }

    fun micTapped() {
        when (_state.value.phase) {
            Phase.LISTENING, Phase.LOADING -> stopHandle?.stop()
            Phase.RECOGNIZING, Phase.TRANSLATING -> Unit
            Phase.IDLE, Phase.SPEAKING -> startListening()
        }
    }

    private fun startListening() {
        c.speaker.stop()
        job?.cancel()
        val handle = Listener.StopHandle()
        stopHandle = handle
        val direction = _state.value.direction
        _state.update { it.copy(phase = Phase.LOADING, partial = "", level = 0f, notice = null) }
        job = launchSafe {
            val outcome = c.listener.listen(
                lang = direction.source,
                cfg = ListenConfig(),
                stop = handle,
                onPartial = { p -> _state.update { it.copy(partial = p) } },
                onLevel = { l ->
                    _state.update { if (it.phase == Phase.LOADING) it.copy(phase = Phase.LISTENING, level = l) else it.copy(level = l) }
                },
            )
            _state.update { it.copy(phase = Phase.RECOGNIZING, level = 0f) }
            when (outcome) {
                is ListenOutcome.Failed -> _state.update { it.copy(phase = Phase.IDLE, partial = "", notice = outcome.message) }
                ListenOutcome.NoSpeech -> _state.update { it.copy(phase = Phase.IDLE, partial = "", notice = "Không nghe thấy giọng nói") }
                is ListenOutcome.Heard -> {
                    val r = SttPostProcessor.process(outcome.raw, direction.source, outcome.outputStyle)
                    if (!r.meaningful) {
                        _state.update { it.copy(phase = Phase.IDLE, partial = "", notice = "Không nhận ra nội dung") }
                    } else {
                        _state.update { it.copy(input = r.text.take(MAX_TYPED_CHARS), partial = "") }
                        runTranslation(r.text, direction, TurnTimings(sttMs = outcome.sttMs))
                    }
                }
            }
        }
    }

    /** "Dịch" button: translate the (typed or edited) text in the box. */
    fun translateInput() {
        val text = _state.value.input
        if (text.isBlank() || _state.value.busy) return
        c.speaker.stop()
        job?.cancel()
        val direction = _state.value.direction
        job = launchSafe { runTranslation(text, direction, TurnTimings()) }
    }

    private suspend fun runTranslation(text: String, direction: Direction, timings: TurnTimings) {
        val translator = c.translators.active()
        if (translator == null) {
            _state.update { it.copy(phase = Phase.IDLE, translation = null, lastSource = text, timings = timings) }
            return
        }
        _state.update { it.copy(phase = Phase.TRANSLATING, notice = null) }
        val watchdog = launchSafe {
            delay(MT_WATCHDOG_MS)
            _state.update { it.copy(notice = "Mô hình dịch chưa phản hồi… Có thể bấm Huỷ.") }
        }
        val t0 = nowMs()
        val translated = try {
            translator.prepare(setOf(direction))
            translator.translate(direction, MtInputNormalizer.normalize(text)).text
        } catch (e: MtException) {
            _state.update { it.copy(phase = Phase.IDLE, notice = e.message, lastSource = text) }
            return
        } finally {
            watchdog.cancel()
        }
        val withMt = timings.copy(mtMs = nowMs() - t0)
        _state.update { it.copy(translation = translated, lastSource = text, timings = withMt, notice = null) }
        speak(translated, direction.target) { first -> _state.update { it.copy(timings = withMt.copy(ttsFirstAudioMs = first)) } }
    }

    fun playTranslation() {
        val s = _state.value
        val text = s.translation ?: return
        job?.cancel()
        job = launchSafe { speak(text, s.direction.target) {} }
    }

    fun playSource() {
        val s = _state.value
        val text = s.input.ifBlank { s.lastSource ?: "" }
        if (text.isBlank()) return
        job?.cancel()
        job = launchSafe { speak(text, s.direction.source) {} }
    }

    fun stopSpeaking() = c.speaker.stop()

    /** Abandons a slow translation (the native call cannot be interrupted; its result is dropped). */
    fun cancelTranslation() {
        if (_state.value.phase != Phase.TRANSLATING) return
        job?.cancel()
        _state.update { it.copy(phase = Phase.IDLE, notice = null) }
    }

    private suspend fun speak(text: String, lang: Lang, onFirstAudio: (Long?) -> Unit) {
        _state.update { it.copy(phase = Phase.SPEAKING) }
        try {
            val r = c.speaker.speak(text, lang)
            onFirstAudio(r.firstAudioMs)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            _state.update { it.copy(notice = "Không đọc được: ${e.message ?: e.javaClass.simpleName}") }
        } finally {
            _state.update { if (it.phase == Phase.SPEAKING) it.copy(phase = Phase.IDLE) else it }
        }
    }

    override fun onCleared() {
        stopHandle?.stop()
        c.speaker.stop()
    }
}
