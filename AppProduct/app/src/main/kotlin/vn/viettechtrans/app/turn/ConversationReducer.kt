package vn.viettechtrans.app.turn

import vn.viettechtrans.app.mt.Direction
import vn.viettechtrans.app.mt.Lang

/** A = Vietnamese speaker, B = English speaker (no language auto-detection: each button is tied to one language). */
enum class Party(val lang: Lang) {
    A(Lang.VI),
    B(Lang.EN),
    ;

    val other: Party get() = if (this == A) B else A
    val direction: Direction get() = Direction.fromSource(lang)
}

/** What happens when someone taps while another turn is active (sketch "chen 2" / "chen 3"). */
enum class InterruptPolicy { CUT, QUEUE }

data class ConversationConfig(
    val policy: InterruptPolicy = InterruptPolicy.CUT,
    /** After a translation has been spoken, open the mic for the other person automatically. */
    val autoAlternate: Boolean = false,
)

sealed interface ConvPhase {
    data object Idle : ConvPhase

    /** [auto]: opened by auto-alternation (a silent timeout just ends the alternation). */
    data class Listening(val party: Party, val turn: Int, val auto: Boolean) : ConvPhase

    /** Recognizing + translating the foreground turn. */
    data class Processing(val party: Party, val turn: Int) : ConvPhase

    /** [turn] null = replay of an earlier message. */
    data class Speaking(val party: Party?, val turn: Int?, val speakId: Int) : ConvPhase
}

data class ConversationState(
    val phase: ConvPhase = ConvPhase.Idle,
    /** QUEUE policy: who tapped while busy; their mic opens when the current turn is over. */
    val pending: Party? = null,
    /** Turns cut off by another speaker: still recognized/translated/saved, never spoken. */
    val background: Map<Int, Party> = emptyMap(),
    val nextTurn: Int = 1,
    val nextSpeak: Int = 1,
)

/** Recognition result of a turn as seen by the reducer. */
sealed interface Heard {
    data class Text(val text: String) : Heard
    data object NoSpeech : Heard
    data class Failed(val message: String) : Heard
}

sealed interface ConvEvent {
    data class Tap(val party: Party) : ConvEvent
    data class Typed(val party: Party, val text: String) : ConvEvent
    data class ListenEnded(val turn: Int, val result: Heard) : ConvEvent
    data class Translated(val turn: Int, val result: MtOutcome) : ConvEvent
    data class SpeakDone(val speakId: Int) : ConvEvent
    data object StopSpeaking : ConvEvent
    data class Replay(val text: String, val lang: Lang) : ConvEvent
}

enum class MessageStatus { TRANSLATING, OK, NOT_PLAYED, NO_TRANSLATOR, FAILED }

sealed interface ConvEffect {
    data class StartListen(val party: Party, val turn: Int, val auto: Boolean) : ConvEffect
    data class StopListen(val turn: Int) : ConvEffect
    data class AddMessage(val turn: Int, val party: Party, val text: String) : ConvEffect
    data class Translate(val turn: Int, val party: Party, val text: String) : ConvEffect
    data class SetTranslation(val turn: Int, val text: String?, val status: MessageStatus, val mtMs: Long? = null) : ConvEffect
    data class Speak(val speakId: Int, val turn: Int?, val lang: Lang, val text: String) : ConvEffect
    data object StopSpeak : ConvEffect

    /** Playback of [turn] was cut by someone tapping. */
    data class MarkInterrupted(val turn: Int) : ConvEffect
    data class Notice(val text: String) : ConvEffect
}

/**
 * Pure state machine of the two-person conversation (plan "Máy trạng thái lượt nói",
 * docs/decisions/DR-05). Half-duplex: the reducer never asks to open the mic while
 * speech is playing (a StopSpeak effect always comes first). Stale events (ids that
 * no longer match) are ignored, so late callbacks cannot corrupt the state.
 */
object ConversationReducer {
    fun reduce(
        s: ConversationState,
        e: ConvEvent,
        cfg: ConversationConfig,
    ): Pair<ConversationState, List<ConvEffect>> = when (e) {
        is ConvEvent.Tap -> onTap(s, e.party, cfg)
        is ConvEvent.Typed -> onTyped(s, e)
        is ConvEvent.ListenEnded -> onListenEnded(s, e, cfg)
        is ConvEvent.Translated -> onTranslated(s, e, cfg)
        is ConvEvent.SpeakDone -> onSpeakDone(s, e, cfg)
        ConvEvent.StopSpeaking -> onStopSpeaking(s, cfg)
        is ConvEvent.Replay -> onReplay(s, e)
    }

    private fun startListening(
        s: ConversationState,
        party: Party,
        auto: Boolean,
        effects: List<ConvEffect> = emptyList(),
    ): Pair<ConversationState, List<ConvEffect>> {
        val turn = s.nextTurn
        return s.copy(phase = ConvPhase.Listening(party, turn, auto), nextTurn = turn + 1, pending = null) to
            (effects + ConvEffect.StartListen(party, turn, auto))
    }

    private fun onTap(s: ConversationState, party: Party, cfg: ConversationConfig): Pair<ConversationState, List<ConvEffect>> =
        when (val p = s.phase) {
            ConvPhase.Idle -> startListening(s, party, auto = false)

            is ConvPhase.Listening -> when {
                // Own button again: end my turn now.
                p.party == party -> s.copy(phase = ConvPhase.Processing(p.party, p.turn)) to listOf(ConvEffect.StopListen(p.turn))
                cfg.policy == InterruptPolicy.CUT -> {
                    val bg = s.copy(background = s.background + (p.turn to p.party))
                    startListening(bg, party, auto = false, listOf(ConvEffect.StopListen(p.turn)))
                }
                else -> togglePending(s, party)
            }

            is ConvPhase.Processing -> when {
                cfg.policy == InterruptPolicy.CUT -> {
                    val bg = s.copy(background = s.background + (p.turn to p.party))
                    startListening(bg, party, auto = false)
                }
                else -> togglePending(s, party)
            }

            is ConvPhase.Speaking -> when {
                p.turn == null || cfg.policy == InterruptPolicy.CUT -> {
                    val fx = buildList {
                        add(ConvEffect.StopSpeak)
                        p.turn?.let { add(ConvEffect.MarkInterrupted(it)) }
                    }
                    startListening(s, party, auto = false, fx)
                }
                else -> togglePending(s, party)
            }
        }

    private fun togglePending(s: ConversationState, party: Party) =
        s.copy(pending = if (s.pending == party) null else party) to emptyList<ConvEffect>()

    private fun onTyped(s: ConversationState, e: ConvEvent.Typed): Pair<ConversationState, List<ConvEffect>> {
        val text = e.text.trim()
        if (text.isEmpty()) return s to emptyList()
        val turn = s.nextTurn
        val next = s.copy(nextTurn = turn + 1)
        val fx = listOf(ConvEffect.AddMessage(turn, e.party, text), ConvEffect.Translate(turn, e.party, text))
        return when (val p = next.phase) {
            ConvPhase.Idle -> next.copy(phase = ConvPhase.Processing(e.party, turn)) to fx
            is ConvPhase.Speaking ->
                next.copy(phase = ConvPhase.Processing(e.party, turn)) to
                    (listOfNotNull(ConvEffect.StopSpeak, p.turn?.let { ConvEffect.MarkInterrupted(it) }) + fx)
            // Someone is talking or a turn is processing: show and translate it, but do not speak it.
            else -> next.copy(background = next.background + (turn to e.party)) to fx
        }
    }

    private fun afterTurn(s: ConversationState, party: Party?, cfg: ConversationConfig, allowAuto: Boolean): Pair<ConversationState, List<ConvEffect>> {
        val idle = s.copy(phase = ConvPhase.Idle)
        s.pending?.let { return startListening(idle, it, auto = false) }
        if (allowAuto && cfg.autoAlternate && party != null) return startListening(idle, party.other, auto = true)
        return idle to emptyList()
    }

    private fun onListenEnded(s: ConversationState, e: ConvEvent.ListenEnded, cfg: ConversationConfig): Pair<ConversationState, List<ConvEffect>> {
        val bgParty = s.background[e.turn]
        if (bgParty != null) {
            return when (val r = e.result) {
                is Heard.Text -> s to listOf(ConvEffect.AddMessage(e.turn, bgParty, r.text), ConvEffect.Translate(e.turn, bgParty, r.text))
                else -> s.copy(background = s.background - e.turn) to emptyList()
            }
        }
        val p = s.phase
        val current = when (p) {
            is ConvPhase.Listening -> p.takeIf { it.turn == e.turn }?.let { it.party to it.auto }
            is ConvPhase.Processing -> p.takeIf { it.turn == e.turn }?.let { it.party to false }
            else -> null
        } ?: return s to emptyList() // stale
        val (party, auto) = current
        return when (val r = e.result) {
            is Heard.Text -> s.copy(phase = ConvPhase.Processing(party, e.turn)) to
                listOf(ConvEffect.AddMessage(e.turn, party, r.text), ConvEffect.Translate(e.turn, party, r.text))
            Heard.NoSpeech -> {
                val (next, fx) = afterTurn(s, party, cfg, allowAuto = false)
                next to (if (auto) fx else listOf(ConvEffect.Notice("Không nghe thấy giọng nói")) + fx)
            }
            is Heard.Failed -> {
                val (next, fx) = afterTurn(s, party, cfg, allowAuto = false)
                next to (listOf(ConvEffect.Notice(r.message)) + fx)
            }
        }
    }

    private fun onTranslated(s: ConversationState, e: ConvEvent.Translated, cfg: ConversationConfig): Pair<ConversationState, List<ConvEffect>> {
        val r = e.result
        if (e.turn in s.background) {
            val (text, status, ms) = when (r) {
                is MtOutcome.Ok -> Triple(r.text, MessageStatus.NOT_PLAYED, r.mtMs)
                MtOutcome.NoTranslator -> Triple(null, MessageStatus.NO_TRANSLATOR, null)
                is MtOutcome.Failed -> Triple(null, MessageStatus.FAILED, null)
            }
            return s.copy(background = s.background - e.turn) to listOf(ConvEffect.SetTranslation(e.turn, text, status, ms))
        }
        val p = s.phase as? ConvPhase.Processing
        if (p == null || p.turn != e.turn) return s to emptyList() // stale
        return when (r) {
            is MtOutcome.Ok -> {
                val id = s.nextSpeak
                s.copy(phase = ConvPhase.Speaking(p.party, p.turn, id), nextSpeak = id + 1) to listOf(
                    ConvEffect.SetTranslation(e.turn, r.text, MessageStatus.OK, r.mtMs),
                    ConvEffect.Speak(id, e.turn, p.party.other.lang, r.text),
                )
            }
            MtOutcome.NoTranslator -> {
                val (next, fx) = afterTurn(s, p.party, cfg, allowAuto = true)
                next to (listOf(ConvEffect.SetTranslation(e.turn, null, MessageStatus.NO_TRANSLATOR)) + fx)
            }
            is MtOutcome.Failed -> {
                val (next, fx) = afterTurn(s, p.party, cfg, allowAuto = false)
                next to (listOf(ConvEffect.SetTranslation(e.turn, null, MessageStatus.FAILED), ConvEffect.Notice(r.message)) + fx)
            }
        }
    }

    private fun onSpeakDone(s: ConversationState, e: ConvEvent.SpeakDone, cfg: ConversationConfig): Pair<ConversationState, List<ConvEffect>> {
        val p = s.phase as? ConvPhase.Speaking
        if (p == null || p.speakId != e.speakId) return s to emptyList() // stale (already stopped/replaced)
        return afterTurn(s, p.party, cfg, allowAuto = p.turn != null)
    }

    private fun onStopSpeaking(s: ConversationState, cfg: ConversationConfig): Pair<ConversationState, List<ConvEffect>> {
        val p = s.phase as? ConvPhase.Speaking ?: return s to emptyList()
        // A manual stop ends auto-alternation; a queued speaker still gets the turn.
        val (next, fx) = afterTurn(s, p.party, cfg, allowAuto = false)
        return next to (listOfNotNull(ConvEffect.StopSpeak, p.turn?.let { ConvEffect.MarkInterrupted(it) }) + fx)
    }

    private fun onReplay(s: ConversationState, e: ConvEvent.Replay): Pair<ConversationState, List<ConvEffect>> {
        val p = s.phase
        if (p !is ConvPhase.Idle && p !is ConvPhase.Speaking) return s to emptyList() // mic open or processing
        val id = s.nextSpeak
        val fx = buildList {
            if (p is ConvPhase.Speaking) {
                add(ConvEffect.StopSpeak)
                p.turn?.let { add(ConvEffect.MarkInterrupted(it)) }
            }
            add(ConvEffect.Speak(id, null, e.lang, e.text))
        }
        return s.copy(phase = ConvPhase.Speaking(null, null, id), nextSpeak = id + 1, pending = null) to fx
    }
}
