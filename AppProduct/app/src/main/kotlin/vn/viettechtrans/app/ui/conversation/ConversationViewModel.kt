package vn.viettechtrans.app.ui.conversation

import android.content.Context
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
import vn.viettechtrans.app.mt.Lang
import vn.viettechtrans.app.speech.EngineRole
import vn.viettechtrans.app.speech.ListenConfig
import vn.viettechtrans.app.speech.ListenOutcome
import vn.viettechtrans.app.speech.Listener
import vn.viettechtrans.app.turn.ConvEffect
import vn.viettechtrans.app.turn.ConvEvent
import vn.viettechtrans.app.turn.ConvPhase
import vn.viettechtrans.app.turn.ConversationConfig
import vn.viettechtrans.app.turn.ConversationReducer
import vn.viettechtrans.app.turn.ConversationState
import vn.viettechtrans.app.turn.Heard
import vn.viettechtrans.app.turn.InterruptPolicy
import vn.viettechtrans.app.turn.MessageStatus
import vn.viettechtrans.app.turn.Party
import vn.viettechtrans.app.turn.TurnTimings

enum class ConvLayout { CHAT, SPLIT }

data class ChatMessage(
    val turn: Int,
    val party: Party,
    val source: String,
    val translation: String? = null,
    val status: MessageStatus = MessageStatus.TRANSLATING,
    val interrupted: Boolean = false,
    val timings: TurnTimings = TurnTimings(),
)

data class ConversationUi(
    val messages: List<ChatMessage> = emptyList(),
    val engine: ConversationState = ConversationState(),
    val partial: String = "",
    val level: Float = 0f,
    val layout: ConvLayout = ConvLayout.CHAT,
    val autoAlternate: Boolean = false,
    val policy: InterruptPolicy = InterruptPolicy.CUT,
    val rotateTop: Boolean = true,
    val notice: String? = null,
    val hasTranslator: Boolean? = null,
    val loading: Set<EngineRole> = emptySet(),
) {
    val listening: Party? get() = (engine.phase as? ConvPhase.Listening)?.party
    val speaking: Boolean get() = engine.phase is ConvPhase.Speaking
    val processing: Party? get() = (engine.phase as? ConvPhase.Processing)?.party
}

/**
 * Hội thoại screen: two people, one language button each (A = Vietnamese, B = English).
 * The pure [ConversationReducer] decides; this class runs its effects (listen,
 * translate, speak) through [vn.viettechtrans.app.turn.TurnRunner]. Events are
 * applied on the main thread only.
 */
class ConversationViewModel(private val c: AppContainer, context: Context) : ViewModel() {
    private val prefs = context.applicationContext.getSharedPreferences("conversation", Context.MODE_PRIVATE)
    private val runner = c.turns

    private val _ui = MutableStateFlow(
        ConversationUi(
            layout = runCatching { ConvLayout.valueOf(prefs.getString("layout", null) ?: "CHAT") }.getOrDefault(ConvLayout.CHAT),
            autoAlternate = prefs.getBoolean("auto_alternate", false),
            policy = runCatching { InterruptPolicy.valueOf(prefs.getString("policy", null) ?: "CUT") }.getOrDefault(InterruptPolicy.CUT),
            rotateTop = prefs.getBoolean("rotate_top", true),
        ),
    )
    val ui: StateFlow<ConversationUi> = _ui.asStateFlow()

    private val listenHandles = mutableMapOf<Int, Listener.StopHandle>()
    private val turnStt = mutableMapOf<Int, Long>()
    private var preloadJob: Job? = null

    private val errors = CoroutineExceptionHandler { _, e ->
        _ui.update { it.copy(notice = "Lỗi: ${e.message ?: e.javaClass.simpleName}") }
    }

    private fun launchSafe(block: suspend CoroutineScope.() -> Unit): Job = viewModelScope.launch(errors, block = block)

    private val config: ConversationConfig
        get() = _ui.value.let { ConversationConfig(it.policy, it.autoAlternate) }

    init {
        launchSafe { _ui.update { it.copy(hasTranslator = runner.hasTranslator()) } }
        launchSafe { c.speechEngines.loading.collect { l -> _ui.update { it.copy(loading = l) } } }
    }

    /** Screen became visible: keep the 4 models of a conversation and load them in the background. */
    fun onShown() {
        preloadJob?.cancel()
        preloadJob = launchSafe {
            delay(800)
            val all = setOf(EngineRole.STT_VI, EngineRole.STT_EN, EngineRole.TTS_VI, EngineRole.TTS_EN)
            c.speechEngines.retainOnly(all)
            runCatching { c.speechEngines.stt(Lang.VI) }
            runCatching { c.speechEngines.stt(Lang.EN) }
            runCatching { c.speechEngines.tts(Lang.EN) }
            runCatching { c.speechEngines.tts(Lang.VI) }
        }
    }

    // ---- user actions ----
    fun tap(party: Party) = dispatch(ConvEvent.Tap(party))
    fun type(party: Party, text: String) = dispatch(ConvEvent.Typed(party, text))
    fun stopSpeaking() = dispatch(ConvEvent.StopSpeaking)

    fun replay(message: ChatMessage) {
        val text = message.translation ?: message.source
        val lang = if (message.translation != null) message.party.other.lang else message.party.lang
        dispatch(ConvEvent.Replay(text, lang))
    }

    fun setLayout(layout: ConvLayout) = save { it.copy(layout = layout) }.also { prefs.edit().putString("layout", layout.name).apply() }
    fun setAutoAlternate(on: Boolean) = save { it.copy(autoAlternate = on) }.also { prefs.edit().putBoolean("auto_alternate", on).apply() }
    fun setPolicy(p: InterruptPolicy) = save { it.copy(policy = p) }.also { prefs.edit().putString("policy", p.name).apply() }
    fun setRotateTop(on: Boolean) = save { it.copy(rotateTop = on) }.also { prefs.edit().putBoolean("rotate_top", on).apply() }
    fun showNotice(text: String) = _ui.update { it.copy(notice = text) }
    fun dismissNotice() = _ui.update { it.copy(notice = null) }

    fun clear() {
        if (_ui.value.engine.phase != ConvPhase.Idle) return
        _ui.update { it.copy(messages = emptyList()) }
    }

    private fun save(f: (ConversationUi) -> ConversationUi) = _ui.update(f)

    // ---- reducer + effects ----
    private fun dispatch(event: ConvEvent) {
        val (next, effects) = ConversationReducer.reduce(_ui.value.engine, event, config)
        _ui.update { it.copy(engine = next) }
        effects.forEach(::run)
    }

    /** Dispatches from a background coroutine on the main thread. */
    private fun post(event: ConvEvent) {
        viewModelScope.launch(errors) { dispatch(event) }
    }

    private fun run(effect: ConvEffect) {
        when (effect) {
            is ConvEffect.StartListen -> startListen(effect)
            is ConvEffect.StopListen -> listenHandles[effect.turn]?.stop()
            is ConvEffect.AddMessage -> _ui.update {
                it.copy(messages = it.messages + ChatMessage(effect.turn, effect.party, effect.text, timings = TurnTimings(sttMs = turnStt[effect.turn])))
            }
            is ConvEffect.Translate -> launchSafe {
                val result = runner.translate(effect.text, effect.party.direction) {
                    _ui.update { it.copy(notice = "Mô hình dịch chưa phản hồi…") }
                }
                post(ConvEvent.Translated(effect.turn, result))
            }
            is ConvEffect.SetTranslation -> updateMessage(effect.turn) {
                it.copy(translation = effect.text, status = effect.status, timings = it.timings.copy(mtMs = effect.mtMs))
            }
            is ConvEffect.Speak -> launchSafe {
                try {
                    val r = runner.speak(effect.text, effect.lang)
                    effect.turn?.let { t -> updateMessage(t) { m -> m.copy(timings = m.timings.copy(ttsFirstAudioMs = r.firstAudioMs)) } }
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    _ui.update { it.copy(notice = "Không đọc được: ${e.message ?: e.javaClass.simpleName}") }
                } finally {
                    post(ConvEvent.SpeakDone(effect.speakId))
                }
            }
            ConvEffect.StopSpeak -> runner.stopSpeaking()
            is ConvEffect.MarkInterrupted -> updateMessage(effect.turn) { it.copy(interrupted = true) }
            is ConvEffect.Notice -> _ui.update { it.copy(notice = effect.text) }
        }
    }

    private fun startListen(e: ConvEffect.StartListen) {
        val handle = Listener.StopHandle()
        listenHandles[e.turn] = handle
        _ui.update { it.copy(partial = "", level = 0f, notice = null) }
        launchSafe {
            runner.awaitMicFree()
            val outcome = runner.listen(
                lang = e.party.lang,
                stop = handle,
                onPartial = { p -> _ui.update { if (it.listening == e.party) it.copy(partial = p) else it } },
                onLevel = { l -> _ui.update { if (it.listening == e.party) it.copy(level = l) else it } },
                cfg = ListenConfig(),
            )
            listenHandles.remove(e.turn)
            val heard = when (outcome) {
                is ListenOutcome.Heard -> {
                    val r = runner.clean(outcome, e.party.lang)
                    turnStt[e.turn] = outcome.sttMs
                    if (r.meaningful) Heard.Text(r.text) else Heard.NoSpeech
                }
                ListenOutcome.NoSpeech -> Heard.NoSpeech
                is ListenOutcome.Failed -> Heard.Failed(outcome.message)
            }
            _ui.update { it.copy(partial = "", level = 0f) }
            post(ConvEvent.ListenEnded(e.turn, heard))
        }
    }

    private fun updateMessage(turn: Int, f: (ChatMessage) -> ChatMessage) = _ui.update { ui ->
        ui.copy(messages = ui.messages.map { if (it.turn == turn) f(it) else it })
    }

    override fun onCleared() {
        listenHandles.values.forEach { it.stop() }
        runner.stopSpeaking()
    }
}
