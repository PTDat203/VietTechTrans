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
import vn.viettechtrans.app.mt.rules.InputDecision
import vn.viettechtrans.app.mt.rules.LangGuess
import vn.viettechtrans.app.mt.rules.RuleNote
import vn.viettechtrans.app.speech.EngineRole
import vn.viettechtrans.app.speech.ListenConfig
import vn.viettechtrans.app.speech.ListenOutcome
import vn.viettechtrans.app.speech.Listener
import vn.viettechtrans.app.text.SttPostProcessor
import vn.viettechtrans.app.turn.MtOutcome
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
    /** The result came from the keyboard (R13: "MT … ms" only, read aloud only on request). */
    val typedResult: Boolean = false,
    /** Rules of the text policy that fired on the result (shown on the result card). */
    val notes: List<RuleNote> = emptyList(),
    val checkTerms: List<String> = emptyList(),
    /** R02: "Dịch" was tapped with nothing to translate. */
    val emptyTried: Boolean = false,
    /** R06: the text looks like the other language; waiting for "Đổi sang …" or "Vẫn dịch". */
    val mismatch: LangGuess? = null,
    /** The text the R06 question is about (the answer applies to it, even if the box changed meanwhile). */
    val mismatchText: String = "",
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
    }

    /** Screen became visible (also when coming back from Hội thoại): keep only this direction's models. */
    fun onShown() = preload(delayMs = PRELOAD_DELAY_MS)

    /** "⇄": reverse the direction; the last translation becomes the new input (like Google Translate, never cut: R02). */
    fun swap() {
        val s = _state.value
        if (s.busy || s.phase == Phase.LISTENING || s.phase == Phase.LOADING) return
        c.speaker.stop()
        _state.update {
            it.copy(
                direction = it.direction.reverse,
                input = it.translation ?: it.input,
                translation = null,
                lastSource = null,
                timings = null,
                notes = emptyList(),
                checkTerms = emptyList(),
                mismatch = null,
                notice = null,
            )
        }
        preload(delayMs = 0)
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
            runCatching { c.translators.retainOnly(setOf(d)) }
        }
    }

    fun setDirection(direction: Direction) {
        val s = _state.value
        if (s.busy || s.phase == Phase.LISTENING || s.phase == Phase.LOADING || direction == s.direction) return
        c.speaker.stop()
        _state.update { it.copy(direction = direction, translation = null, lastSource = null, timings = null, notes = emptyList(), notice = null) }
        preload(delayMs = 0)
    }

    /** R02: the text is kept whatever its length; the counter and the "Dịch" button follow the policy count. */
    fun onInput(text: String) = _state.update { it.copy(input = text, emptyTried = false) }

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
        _state.update { it.copy(phase = Phase.LOADING, partial = "", level = 0f, notice = null, mismatch = null) }
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
                        _state.update { it.copy(input = r.text, partial = "") }
                        runTranslation(r.text, direction, TurnTimings(sttMs = outcome.sttMs), typed = false, inputNotes = emptyList())
                    }
                }
            }
        }
    }

    /** "Dịch" button: the typed (or edited) text in the box, through the text-input rules (R01–R07). */
    fun translateInput(override: Boolean = false, direction: Direction = _state.value.direction, skipMismatch: Boolean = false) {
        if (_state.value.mismatch == null && (override || skipMismatch)) return // the question was answered or dismissed
        val s = _state.value
        if (s.busy) return
        // Typing wins over an open mic: a late transcript must not replace the text being decided on.
        if (s.phase == Phase.LISTENING || s.phase == Phase.LOADING) {
            stopHandle?.stop()
            job?.cancel()
            _state.update { it.copy(phase = Phase.IDLE, partial = "") }
        }
        // R13: a notice of a voice attempt ("Không nghe thấy giọng nói") does not belong to this text.
        _state.update { it.copy(notice = null, mismatch = null) }
        when (val d = c.textRules.decide(s.input, direction, override = override, skipMismatch = skipMismatch)) {
            is InputDecision.Empty -> _state.update { it.copy(emptyTried = true) }
            is InputDecision.TooLong -> Unit // the button is disabled and the counter shows the policy message
            is InputDecision.Mismatch -> _state.update { it.copy(mismatch = d.guess, mismatchText = s.input) }
            is InputDecision.Keep -> {
                c.speaker.stop()
                job?.cancel()
                _state.update {
                    it.copy(
                        phase = Phase.IDLE, translation = d.text, lastSource = d.text, timings = TurnTimings(),
                        typedResult = true, notes = listOf(d.note), checkTerms = emptyList(),
                    )
                }
            }
            is InputDecision.Translate -> {
                c.speaker.stop()
                job?.cancel()
                job = launchSafe { runTranslation(d.text, direction, TurnTimings(), typed = true, inputNotes = d.notes) }
            }
        }
    }

    /** R06 answer: "Đổi sang …" switches the direction and translates; "Vẫn dịch" translates as chosen (logged). */
    fun answerMismatch(switchLanguage: Boolean) {
        val s = _state.value
        val guess = s.mismatch ?: return
        // Answer about the text that was asked about.
        _state.update { it.copy(input = s.mismatchText) }
        if (switchLanguage) {
            // The direction must really change, also if the mic was opened meanwhile: stop it first.
            stopHandle?.stop()
            job?.cancel()
            c.speaker.stop()
            val reversed = s.direction.reverse
            _state.update {
                it.copy(direction = reversed, phase = Phase.IDLE, partial = "", translation = null, lastSource = null, timings = null, notes = emptyList())
            }
            preload(delayMs = 0)
            translateInput(direction = reversed, skipMismatch = true)
        } else {
            launchSafe { c.turns.logOverride(s.mismatchText, s.direction, guess.id) }
            translateInput(override = true)
        }
    }

    fun dismissMismatch() = _state.update { it.copy(mismatch = null) }

    private suspend fun runTranslation(text: String, direction: Direction, timings: TurnTimings, typed: Boolean, inputNotes: List<RuleNote>) {
        _state.update { it.copy(phase = Phase.TRANSLATING, notice = null) }
        val result = c.turns.translate(text, direction) {
            _state.update { it.copy(notice = "Mô hình dịch chưa phản hồi… Có thể bấm Huỷ.") }
        }
        when (result) {
            MtOutcome.NoTranslator ->
                _state.update { it.copy(phase = Phase.IDLE, translation = null, lastSource = text, timings = timings, typedResult = typed, notes = inputNotes) }
            is MtOutcome.Failed ->
                _state.update { it.copy(phase = Phase.IDLE, translation = null, notice = result.message, lastSource = text, timings = null, typedResult = typed, notes = inputNotes) }
            // R12: no translation is shown; the source stays in the box to be corrected.
            is MtOutcome.Invalid ->
                _state.update { it.copy(phase = Phase.IDLE, translation = null, notice = result.message, lastSource = text, timings = null, typedResult = typed, notes = inputNotes) }
            is MtOutcome.Ok -> {
                val withMt = timings.copy(mtMs = result.mtMs)
                _state.update {
                    it.copy(
                        phase = Phase.IDLE, translation = result.text, lastSource = text, timings = withMt, typedResult = typed,
                        notes = (inputNotes + result.notes).distinct(), checkTerms = result.checkTerms,
                    )
                }
                // R13: typed text is read only when the user taps the speaker; a spoken turn is read back at once.
                if (!typed) speak(result.text, direction.target) { first -> _state.update { it.copy(timings = withMt.copy(ttsFirstAudioMs = first)) } }
            }
        }
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
