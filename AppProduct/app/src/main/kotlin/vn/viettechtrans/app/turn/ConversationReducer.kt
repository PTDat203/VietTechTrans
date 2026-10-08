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

sealed interface ConvPhase {
    data object Idle : ConvPhase

    data class Listening(val party: Party, val turn: Int) : ConvPhase

    /** Recognizing + translating the foreground turn. */
    data class Processing(val party: Party, val turn: Int) : ConvPhase

    /** [turn] null = replay of an earlier message. */
    data class Speaking(val party: Party?, val turn: Int?, val speakId: Int) : ConvPhase
}

data class ConversationState(
    val phase: ConvPhase = ConvPhase.Idle,
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

    /** The source of an earlier message was corrected (STT misheard, typo): translate and speak it again. */
    data class Edited(val turn: Int, val party: Party, val text: String) : ConvEvent

    /** Another conversation is shown: stop everything; new turns start at [minNextTurn] or later. */
    data class Reset(val minNextTurn: Int) : ConvEvent
}

enum class MessageStatus { TRANSLATING, OK, NOT_PLAYED, NO_TRANSLATOR, FAILED }

sealed interface ConvEffect {
    data class StartListen(val party: Party, val turn: Int) : ConvEffect
    data class StopListen(val turn: Int) : ConvEffect
    data class AddMessage(val turn: Int, val party: Party, val text: String) : ConvEffect

    /** Replace the source of message [turn] by [text] and clear its old translation. */
    data class ReplaceSource(val turn: Int, val text: String) : ConvEffect
    data class Translate(val turn: Int, val party: Party, val text: String) : ConvEffect
    data class SetTranslation(val turn: Int, val text: String?, val status: MessageStatus, val mtMs: Long? = null) : ConvEffect
    data class Speak(val speakId: Int, val turn: Int?, val lang: Lang, val text: String) : ConvEffect
    data object StopSpeak : ConvEffect

    /** Playback of [turn] was cut by someone tapping. */
    data class MarkInterrupted(val turn: Int) : ConvEffect
    data class Notice(val text: String) : ConvEffect
}

/**
 * Pure state machine of the two-person conversation (docs/decisions/DR-05, simplified by
 * DR-09: tapping always cuts the running turn; no waiting queue, no automatic alternation).
 * Half-duplex: the mic never opens while speech is playing (a StopSpeak effect always comes
 * first). Stale events (ids that no longer match) are ignored, so late callbacks — also those
 * of a conversation that was closed — cannot corrupt the state.
 */
object ConversationReducer {
    const val BUSY_NOTICE = "Đợi lượt đang nói hoặc đang dịch xong rồi sửa"

    fun reduce(s: ConversationState, e: ConvEvent): Pair<ConversationState, List<ConvEffect>> = when (e) {
        is ConvEvent.Tap -> onTap(s, e.party)
        is ConvEvent.Typed -> onTyped(s, e)
        is ConvEvent.ListenEnded -> onListenEnded(s, e)
        is ConvEvent.Translated -> onTranslated(s, e)
        is ConvEvent.SpeakDone -> onSpeakDone(s, e)
        ConvEvent.StopSpeaking -> onStopSpeaking(s)
        is ConvEvent.Replay -> onReplay(s, e)
        is ConvEvent.Edited -> onEdited(s, e)
        is ConvEvent.Reset -> onReset(s, e)
    }

    private fun startListening(s: ConversationState, party: Party, effects: List<ConvEffect> = emptyList()): Pair<ConversationState, List<ConvEffect>> {
        val turn = s.nextTurn
        return s.copy(phase = ConvPhase.Listening(party, turn), nextTurn = turn + 1) to (effects + ConvEffect.StartListen(party, turn))
    }

    private fun stopSpeech(p: ConvPhase.Speaking, keepTurn: Int? = null): List<ConvEffect> =
        listOfNotNull(ConvEffect.StopSpeak, p.turn?.takeIf { it != keepTurn }?.let { ConvEffect.MarkInterrupted(it) })

    private fun onTap(s: ConversationState, party: Party): Pair<ConversationState, List<ConvEffect>> = when (val p = s.phase) {
        ConvPhase.Idle -> startListening(s, party)
        // Own button again: end my turn now. The other button: my turn goes on in the background.
        is ConvPhase.Listening ->
            if (p.party == party) {
                s.copy(phase = ConvPhase.Processing(p.party, p.turn)) to listOf(ConvEffect.StopListen(p.turn))
            } else {
                startListening(s.copy(background = s.background + (p.turn to p.party)), party, listOf(ConvEffect.StopListen(p.turn)))
            }
        is ConvPhase.Processing -> startListening(s.copy(background = s.background + (p.turn to p.party)), party)
        is ConvPhase.Speaking -> startListening(s, party, stopSpeech(p))
    }

    private fun onTyped(s: ConversationState, e: ConvEvent.Typed): Pair<ConversationState, List<ConvEffect>> {
        val text = e.text.trim()
        if (text.isEmpty()) return s to emptyList()
        val turn = s.nextTurn
        val next = s.copy(nextTurn = turn + 1)
        val fx = listOf(ConvEffect.AddMessage(turn, e.party, text), ConvEffect.Translate(turn, e.party, text))
        return when (val p = next.phase) {
            ConvPhase.Idle -> next.copy(phase = ConvPhase.Processing(e.party, turn)) to fx
            is ConvPhase.Speaking -> next.copy(phase = ConvPhase.Processing(e.party, turn)) to (stopSpeech(p) + fx)
            // Someone is talking or a turn is processing: show and translate it, but do not speak it.
            else -> next.copy(background = next.background + (turn to e.party)) to fx
        }
    }

    private fun onEdited(s: ConversationState, e: ConvEvent.Edited): Pair<ConversationState, List<ConvEffect>> {
        val text = e.text.trim()
        if (text.isEmpty() || e.turn >= s.nextTurn) return s to emptyList()
        if (e.turn in s.background) return s to listOf(ConvEffect.Notice(BUSY_NOTICE))
        val fx = listOf(ConvEffect.ReplaceSource(e.turn, text), ConvEffect.Translate(e.turn, e.party, text))
        return when (val p = s.phase) {
            ConvPhase.Idle -> s.copy(phase = ConvPhase.Processing(e.party, e.turn)) to fx
            is ConvPhase.Speaking -> s.copy(phase = ConvPhase.Processing(e.party, e.turn)) to (stopSpeech(p, keepTurn = e.turn) + fx)
            // Mic open or a translation running: the user edits again when it is over.
            else -> s to listOf(ConvEffect.Notice(BUSY_NOTICE))
        }
    }

    private fun onListenEnded(s: ConversationState, e: ConvEvent.ListenEnded): Pair<ConversationState, List<ConvEffect>> {
        val bgParty = s.background[e.turn]
        if (bgParty != null) {
            return when (val r = e.result) {
                is Heard.Text -> s to listOf(ConvEffect.AddMessage(e.turn, bgParty, r.text), ConvEffect.Translate(e.turn, bgParty, r.text))
                else -> s.copy(background = s.background - e.turn) to emptyList()
            }
        }
        val party = when (val p = s.phase) {
            is ConvPhase.Listening -> p.party.takeIf { p.turn == e.turn }
            is ConvPhase.Processing -> p.party.takeIf { p.turn == e.turn }
            else -> null
        } ?: return s to emptyList() // stale
        val idle = s.copy(phase = ConvPhase.Idle)
        return when (val r = e.result) {
            is Heard.Text -> s.copy(phase = ConvPhase.Processing(party, e.turn)) to
                listOf(ConvEffect.AddMessage(e.turn, party, r.text), ConvEffect.Translate(e.turn, party, r.text))
            Heard.NoSpeech -> idle to listOf(ConvEffect.Notice("Không nghe thấy giọng nói"))
            is Heard.Failed -> idle to listOf(ConvEffect.Notice(r.message))
        }
    }

    private fun onTranslated(s: ConversationState, e: ConvEvent.Translated): Pair<ConversationState, List<ConvEffect>> {
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
        val idle = s.copy(phase = ConvPhase.Idle)
        return when (r) {
            is MtOutcome.Ok -> {
                val id = s.nextSpeak
                s.copy(phase = ConvPhase.Speaking(p.party, p.turn, id), nextSpeak = id + 1) to listOf(
                    ConvEffect.SetTranslation(e.turn, r.text, MessageStatus.OK, r.mtMs),
                    ConvEffect.Speak(id, e.turn, p.party.other.lang, r.text),
                )
            }
            MtOutcome.NoTranslator -> idle to listOf(ConvEffect.SetTranslation(e.turn, null, MessageStatus.NO_TRANSLATOR))
            is MtOutcome.Failed -> idle to listOf(ConvEffect.SetTranslation(e.turn, null, MessageStatus.FAILED), ConvEffect.Notice(r.message))
        }
    }

    private fun onSpeakDone(s: ConversationState, e: ConvEvent.SpeakDone): Pair<ConversationState, List<ConvEffect>> {
        val p = s.phase as? ConvPhase.Speaking
        if (p == null || p.speakId != e.speakId) return s to emptyList() // stale (already stopped/replaced)
        return s.copy(phase = ConvPhase.Idle) to emptyList()
    }

    private fun onStopSpeaking(s: ConversationState): Pair<ConversationState, List<ConvEffect>> {
        val p = s.phase as? ConvPhase.Speaking ?: return s to emptyList()
        return s.copy(phase = ConvPhase.Idle) to stopSpeech(p)
    }

    private fun onReplay(s: ConversationState, e: ConvEvent.Replay): Pair<ConversationState, List<ConvEffect>> {
        val p = s.phase
        if (p !is ConvPhase.Idle && p !is ConvPhase.Speaking) return s to emptyList() // mic open or processing
        val id = s.nextSpeak
        val fx = (if (p is ConvPhase.Speaking) stopSpeech(p) else emptyList()) + ConvEffect.Speak(id, null, e.lang, e.text)
        return s.copy(phase = ConvPhase.Speaking(null, null, id), nextSpeak = id + 1) to fx
    }

    private fun onReset(s: ConversationState, e: ConvEvent.Reset): Pair<ConversationState, List<ConvEffect>> {
        val fx = when (val p = s.phase) {
            is ConvPhase.Listening -> listOf(ConvEffect.StopListen(p.turn))
            is ConvPhase.Speaking -> listOf(ConvEffect.StopSpeak)
            else -> emptyList()
        }
        // Turn ids keep growing, so late results of the old conversation can never match a new turn.
        return ConversationState(nextTurn = maxOf(s.nextTurn, e.minNextTurn), nextSpeak = s.nextSpeak) to fx
    }
}
