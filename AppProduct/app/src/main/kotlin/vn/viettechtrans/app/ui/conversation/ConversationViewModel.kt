package vn.viettechtrans.app.ui.conversation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlin.random.Random
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
import vn.viettechtrans.app.history.ConversationStore
import vn.viettechtrans.app.history.StoredConversation
import vn.viettechtrans.app.history.StoredMessage
import vn.viettechtrans.app.mt.Direction
import vn.viettechtrans.app.mt.Lang
import vn.viettechtrans.app.mt.rules.InputDecision
import vn.viettechtrans.app.mt.rules.LangGuess
import vn.viettechtrans.app.mt.rules.RuleNote
import vn.viettechtrans.app.speech.EngineRole
import vn.viettechtrans.app.speech.ListenConfig
import vn.viettechtrans.app.speech.ListenOutcome
import vn.viettechtrans.app.speech.Listener
import vn.viettechtrans.app.turn.ConvEffect
import vn.viettechtrans.app.turn.ConvEvent
import vn.viettechtrans.app.turn.ConvPhase
import vn.viettechtrans.app.turn.ConversationReducer
import vn.viettechtrans.app.turn.ConversationState
import vn.viettechtrans.app.turn.Heard
import vn.viettechtrans.app.turn.MessageStatus
import vn.viettechtrans.app.turn.Party
import vn.viettechtrans.app.turn.TurnTimings

data class ChatMessage(
    val turn: Int,
    val party: Party,
    val source: String,
    val translation: String? = null,
    val status: MessageStatus = MessageStatus.TRANSLATING,
    val interrupted: Boolean = false,
    val edited: Boolean = false,
    val createdAtMs: Long = System.currentTimeMillis(),
    val timings: TurnTimings = TurnTimings(),
    /** From the keyboard (typed, or corrected with ✎): R13 shows only "MT … ms" and never speaks it by itself. */
    val typed: Boolean = false,
    /** Rules of docs/contracts/mt_app_rule_policy_v1.md that fired on this message, shown on its bubble. */
    val notes: List<RuleNote> = emptyList(),
    /** R08: lexicon terms of the source that the translation lost. */
    val checkTerms: List<String> = emptyList(),
)

/** R06: typed text that looks like the other language, waiting for "Đổi sang …" or "Vẫn dịch". */
data class PendingMismatch(val party: Party, val text: String, val guess: LangGuess, val editTurn: Int? = null)

data class ConversationUi(
    /** null until the first message of a new conversation is added. */
    val conversationId: String? = null,
    val title: String? = null,
    val createdAtMs: Long = 0L,
    val messages: List<ChatMessage> = emptyList(),
    val engine: ConversationState = ConversationState(),
    val partial: String = "",
    val level: Float = 0f,
    val notice: String? = null,
    val hasTranslator: Boolean? = null,
    val loading: Set<EngineRole> = emptySet(),
    val mismatch: PendingMismatch? = null,
) {
    val listening: Party? get() = (engine.phase as? ConvPhase.Listening)?.party
    val speaking: Boolean get() = engine.phase is ConvPhase.Speaking
    val processing: Party? get() = (engine.phase as? ConvPhase.Processing)?.party

    /** A message can be corrected when nothing is listening/translating and it is not itself being translated. */
    fun canEdit(m: ChatMessage): Boolean =
        m.status != MessageStatus.TRANSLATING && engine.phase.let { it is ConvPhase.Idle || it is ConvPhase.Speaking } &&
            m.turn !in engine.background

    val full: Boolean get() = messages.size >= ConversationStore.MAX_MESSAGES
}

/**
 * Hội thoại screen: two people, one language button each (A = Vietnamese, B = English), and
 * a chat-app style history: every conversation is saved (text only) and can be reopened,
 * renamed or deleted; "＋" starts a new one. The pure [ConversationReducer] decides; this class
 * runs its effects (listen, translate, speak) through [vn.viettechtrans.app.turn.TurnRunner].
 * Typed and corrected text goes through the text-input rules first ([AppContainer.textRules]).
 * Events are applied on the main thread only.
 */
class ConversationViewModel(private val c: AppContainer) : ViewModel() {
    private val runner = c.turns
    private val store = c.conversations

    private val _ui = MutableStateFlow(ConversationUi())
    val ui: StateFlow<ConversationUi> = _ui.asStateFlow()
    val history = store.summaries

    private val listenHandles = mutableMapOf<Int, Listener.StopHandle>()
    private val turnStt = mutableMapOf<Int, Long>()
    private var preloadJob: Job? = null
    private var saveJob: Job? = null

    /** Incremented when another conversation is shown; late work of the old one is dropped. */
    private var epoch = 0

    private val errors = CoroutineExceptionHandler { _, e ->
        _ui.update { it.copy(notice = "Lỗi: ${e.message ?: e.javaClass.simpleName}") }
    }

    private fun launchSafe(block: suspend CoroutineScope.() -> Unit): Job = viewModelScope.launch(errors, block = block)

    init {
        launchSafe { _ui.update { it.copy(hasTranslator = runner.hasTranslator()) } }
        launchSafe { c.speechEngines.loading.collect { l -> _ui.update { it.copy(loading = l) } } }
        launchSafe { store.refresh() }
        launchSafe {
            store.removed.collect { r ->
                val id = _ui.value.conversationId
                if (r.all || id != null && id in r.ids) switchTo(null, emptyList(), minNextTurn = 1, saveCurrent = false)
            }
        }
    }

    /** Screen became visible: keep the 4 speech models and both MT directions, load them in the background. */
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
            runCatching { c.translators.retainOnly(Direction.entries.toSet()) }
        }
    }

    // ---- user actions ----
    fun tap(party: Party) {
        val ui = _ui.value
        // The mic can always be stopped; a new turn needs room in the conversation.
        if (ui.full && ui.listening == null) return showNotice(FULL_NOTICE)
        dispatch(ConvEvent.Tap(party))
    }

    /** "Gửi tiếng Việt" / "Send English": the text-input rules decide (R01–R07), then the reducer. */
    fun type(party: Party, text: String) {
        if (_ui.value.full) return showNotice(FULL_NOTICE)
        send(party, c.textRules.decide(text, party.direction), editTurn = null)
    }

    /** "Sửa" on a sent message (like editing a prompt in a chat app): the same rules, then translate it again. */
    fun edit(message: ChatMessage, text: String) {
        val d = c.textRules.decide(text, message.party.direction)
        if (d.text == message.source && message.status == MessageStatus.OK) return
        send(message.party, d, editTurn = message.turn)
    }

    /** R06 answer: "Đổi sang …" ([switchLanguage]) sends it as the other person's language, "Vẫn dịch" as chosen. */
    fun answerMismatch(switchLanguage: Boolean) {
        val m = _ui.value.mismatch ?: return
        _ui.update { it.copy(mismatch = null) }
        val party = if (switchLanguage) m.party.other else m.party
        if (!switchLanguage) launchSafe { runner.logOverride(m.text, m.party.direction, m.guess.id) }
        send(party, c.textRules.decide(m.text, party.direction, override = !switchLanguage, skipMismatch = switchLanguage), m.editTurn)
    }

    /** The R06 question was dismissed: nothing is sent; the screen gives the text back to the box. */
    fun dismissMismatch(): PendingMismatch? = _ui.value.mismatch.also { _ui.update { it.copy(mismatch = null) } }

    private fun send(party: Party, d: InputDecision, editTurn: Int?) {
        when (d) {
            is InputDecision.Empty -> showNotice(EMPTY_NOTICE)
            is InputDecision.TooLong -> showNotice(TOO_LONG_NOTICE)
            is InputDecision.Mismatch -> _ui.update { it.copy(mismatch = PendingMismatch(party, d.text, d.guess, editTurn)) }
            is InputDecision.Keep -> sendText(party, d.text, keep = true, notes = listOf(d.note), editTurn = editTurn)
            is InputDecision.Translate -> sendText(party, d.text, keep = false, notes = d.notes, editTurn = editTurn)
        }
    }

    private fun sendText(party: Party, text: String, keep: Boolean, notes: List<RuleNote>, editTurn: Int?) {
        // R13: a microphone notice ("Không nghe thấy giọng nói") belongs to the voice action that failed, not to this text.
        dismissNotice()
        dispatch(if (editTurn == null) ConvEvent.Typed(party, text, keep, notes) else ConvEvent.Edited(editTurn, party, text, keep, notes))
    }

    fun stopSpeaking() = dispatch(ConvEvent.StopSpeaking)

    fun replay(message: ChatMessage) {
        val text = message.translation ?: message.source
        val lang = if (message.translation != null) message.party.other.lang else message.party.lang
        dispatch(ConvEvent.Replay(text, lang))
    }

    fun showNotice(text: String) = _ui.update { it.copy(notice = text) }
    fun dismissNotice() = _ui.update { it.copy(notice = null) }

    // ---- conversations (history) ----
    fun newConversation() {
        if (_ui.value.conversationId == null && _ui.value.messages.isEmpty()) return
        switchTo(null, emptyList(), minNextTurn = 1)
    }

    fun open(id: String) {
        if (id == _ui.value.conversationId) return
        launchSafe {
            val conv = store.load(id) ?: return@launchSafe showNotice("Không mở được cuộc trò chuyện này")
            val messages = conv.messages.map { it.toChat() }
            switchTo(conv, messages, minNextTurn = (messages.maxOfOrNull { it.turn } ?: 0) + 1)
        }
    }

    fun rename(id: String, title: String) = launchSafe {
        store.rename(id, title)
        if (id == _ui.value.conversationId) _ui.update { it.copy(title = store.load(id)?.title ?: it.title) }
    }

    /** The store reports the deletion back ([ConversationStore.removed]); an open conversation is then closed. */
    fun delete(id: String) = launchSafe {
        if (id == _ui.value.conversationId) saveJob?.cancel()
        store.delete(id)
    }

    private fun switchTo(conv: StoredConversation?, messages: List<ChatMessage>, minNextTurn: Int, saveCurrent: Boolean = true) {
        if (saveCurrent && saveJob?.isActive == true) flushSave() else saveJob?.cancel()
        epoch++
        listenHandles.values.forEach { it.stop() }
        listenHandles.clear()
        dispatch(ConvEvent.Reset(minNextTurn))
        _ui.update {
            it.copy(
                conversationId = conv?.id, title = conv?.title, createdAtMs = conv?.createdAtMs ?: 0L,
                messages = messages, partial = "", level = 0f, notice = null, mismatch = null,
            )
        }
    }

    // ---- reducer + effects ----
    private fun dispatch(event: ConvEvent) {
        val (next, effects) = ConversationReducer.reduce(_ui.value.engine, event)
        _ui.update { it.copy(engine = next) }
        effects.forEach(::run)
    }

    /** Dispatches from a background coroutine on the main thread; drops events of a closed conversation. */
    private fun post(event: ConvEvent, from: Int) {
        viewModelScope.launch(errors) { if (from == epoch) dispatch(event) }
    }

    private fun run(effect: ConvEffect) {
        val at = epoch
        when (effect) {
            is ConvEffect.StartListen -> startListen(effect)
            is ConvEffect.StopListen -> listenHandles[effect.turn]?.stop()
            is ConvEffect.AddMessage -> {
                val now = System.currentTimeMillis()
                _ui.update {
                    // The first message gives a new conversation its id (and file) right away.
                    val id = it.conversationId ?: ConversationStore.newId(now, Random.nextInt())
                    it.copy(
                        conversationId = id,
                        createdAtMs = if (it.conversationId == null) now else it.createdAtMs,
                        messages = it.messages + ChatMessage(
                            effect.turn, effect.party, effect.text, createdAtMs = now,
                            timings = TurnTimings(sttMs = turnStt[effect.turn]), typed = effect.typed, notes = effect.notes,
                        ),
                    )
                }
                scheduleSave()
            }
            is ConvEffect.ReplaceSource -> updateMessage(effect.turn) {
                it.copy(
                    party = effect.party, source = effect.text, translation = null, status = MessageStatus.TRANSLATING,
                    interrupted = false, edited = true, timings = TurnTimings(), typed = true, notes = effect.notes, checkTerms = emptyList(),
                )
            }
            is ConvEffect.Translate -> launchSafe {
                val result = runner.translate(effect.text, effect.party.direction) {
                    if (at == epoch) _ui.update { it.copy(notice = "Mô hình dịch chưa phản hồi…") }
                }
                post(ConvEvent.Translated(effect.turn, result), at)
            }
            is ConvEffect.SetTranslation -> updateMessage(effect.turn) {
                it.copy(
                    translation = effect.text, status = effect.status, timings = it.timings.copy(mtMs = effect.mtMs),
                    notes = (it.notes + effect.notes).distinct(), checkTerms = effect.checkTerms,
                )
            }
            is ConvEffect.Speak -> launchSafe {
                try {
                    val r = runner.speak(effect.text, effect.lang)
                    if (at == epoch) effect.turn?.let { t -> updateMessage(t) { m -> m.copy(timings = m.timings.copy(ttsFirstAudioMs = r.firstAudioMs)) } }
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    if (at == epoch) _ui.update { it.copy(notice = "Không đọc được: ${e.message ?: e.javaClass.simpleName}") }
                } finally {
                    post(ConvEvent.SpeakDone(effect.speakId), at)
                }
            }
            ConvEffect.StopSpeak -> runner.stopSpeaking()
            is ConvEffect.MarkInterrupted -> updateMessage(effect.turn) { it.copy(interrupted = true) }
            is ConvEffect.Notice -> _ui.update { it.copy(notice = effect.text) }
        }
    }

    private fun startListen(e: ConvEffect.StartListen) {
        val at = epoch
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
            if (at == epoch) _ui.update { it.copy(partial = "", level = 0f) }
            post(ConvEvent.ListenEnded(e.turn, heard), at)
        }
    }

    private fun updateMessage(turn: Int, f: (ChatMessage) -> ChatMessage) {
        _ui.update { ui -> ui.copy(messages = ui.messages.map { if (it.turn == turn) f(it) else it }) }
        scheduleSave()
    }

    // ---- persistence ----
    /** Saves shortly after the last change (several effects of one turn arrive together). */
    private fun scheduleSave() {
        if (_ui.value.messages.isEmpty()) return
        saveJob?.cancel()
        saveJob = launchSafe {
            delay(SAVE_DELAY_MS)
            persist(_ui.value)
        }
    }

    /** Writes the conversation being left right away (its pending delayed save is replaced). */
    private fun flushSave() {
        saveJob?.cancel()
        val snapshot = _ui.value
        if (snapshot.messages.isNotEmpty()) launchSafe { persist(snapshot) }
    }

    /** [ui] is a snapshot, so a conversation that was just left is still saved under its own id. */
    private suspend fun persist(ui: ConversationUi) {
        val id = ui.conversationId ?: return
        val now = System.currentTimeMillis()
        val old = store.load(id)
        val title = if (old?.titleEdited == true) old.title else ConversationStore.titleFrom(ui.messages.first().source)
        store.save(
            StoredConversation(
                id = id,
                title = title,
                titleEdited = old?.titleEdited == true,
                createdAtMs = old?.createdAtMs ?: ui.createdAtMs.takeIf { it > 0 } ?: now,
                updatedAtMs = now,
                messages = ui.messages.map { it.toStored() },
            ),
        )
        if (_ui.value.conversationId == id) _ui.update { it.copy(title = title) }
    }

    override fun onCleared() {
        listenHandles.values.forEach { it.stop() }
        runner.stopSpeaking()
    }

    companion object {
        private const val SAVE_DELAY_MS = 400L
        private const val FULL_NOTICE = "Cuộc trò chuyện đã đủ 500 câu. Bấm ＋ để tạo cuộc trò chuyện mới."

        /** Policy R02 messages. */
        const val EMPTY_NOTICE = "Hãy nhập nội dung cần dịch."
        const val TOO_LONG_NOTICE = "Nội dung tối đa 500 ký tự."
    }
}

private fun ChatMessage.toStored() = StoredMessage(
    turn = turn,
    party = party.name,
    source = source,
    translation = translation,
    status = status.name,
    interrupted = interrupted,
    edited = edited,
    createdAtMs = createdAtMs,
    sttMs = timings.sttMs,
    mtMs = timings.mtMs,
    ttsFirstAudioMs = timings.ttsFirstAudioMs,
    input = if (typed) StoredMessage.INPUT_TEXT else StoredMessage.INPUT_VOICE,
    notes = notes.map { it.name },
    checkTerms = checkTerms,
)

/** A message saved while it was still translating (app closed) is shown as failed: "Sửa" translates it again. */
private fun StoredMessage.toChat() = ChatMessage(
    turn = turn,
    party = if (party == "B") Party.B else Party.A,
    source = source,
    translation = translation,
    status = runCatching { MessageStatus.valueOf(status) }.getOrDefault(MessageStatus.FAILED)
        .let { if (it == MessageStatus.TRANSLATING) MessageStatus.FAILED else it },
    interrupted = interrupted,
    edited = edited,
    createdAtMs = createdAtMs,
    timings = TurnTimings(sttMs = sttMs, mtMs = mtMs, ttsFirstAudioMs = ttsFirstAudioMs),
    // Files written before 2026-10-23 have no input field: a spoken turn always has an STT time, a typed one never.
    typed = input == StoredMessage.INPUT_TEXT || input == null && sttMs == null,
    notes = RuleNote.parse(notes),
    checkTerms = checkTerms,
)
