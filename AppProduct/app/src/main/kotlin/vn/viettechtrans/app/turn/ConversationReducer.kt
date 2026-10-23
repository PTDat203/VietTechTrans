package vn.viettechtrans.app.turn

import vn.viettechtrans.app.mt.Direction
import vn.viettechtrans.app.mt.Lang
import vn.viettechtrans.app.mt.rules.RuleNote

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
    /** Turns from the keyboard (typed or corrected) being translated: never spoken automatically (policy R13). */
    val textTurns: Set<Int> = emptySet(),
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

    /**
     * Typed text after the input rules (docs/contracts/mt_app_rule_policy_v1.md R01-R07). [keep]: R03/R04 decided not to
     * call the model, the text itself is the result. [notes]: the input rules that fired.
     */
    data class Typed(val party: Party, val text: String, val keep: Boolean = false, val notes: List<RuleNote> = emptyList()) : ConvEvent
    data class ListenEnded(val turn: Int, val result: Heard) : ConvEvent
    data class Translated(val turn: Int, val result: MtOutcome) : ConvEvent
    data class SpeakDone(val speakId: Int) : ConvEvent
    data object StopSpeaking : ConvEvent
    data class Replay(val text: String, val lang: Lang) : ConvEvent

    /**
     * The source of an earlier message was corrected (STT misheard, typo): translate it again, the input rules already
     * applied as for [Typed]. [party] may differ from the message's (R06 "Đổi sang …" after pressing the wrong language).
     * Not spoken automatically: it is keyboard text (R13).
     */
    data class Edited(
        val turn: Int,
        val party: Party,
        val text: String,
        val keep: Boolean = false,
        val notes: List<RuleNote> = emptyList(),
    ) : ConvEvent

    /** Another conversation is shown: stop everything; new turns start at [minNextTurn] or later. */
    data class Reset(val minNextTurn: Int) : ConvEvent
}

/** INVALID: the model answered but R12 rejected the output (no translation is shown). */
enum class MessageStatus { TRANSLATING, OK, NOT_PLAYED, NO_TRANSLATOR, FAILED, INVALID }

sealed interface ConvEffect {
    data class StartListen(val party: Party, val turn: Int) : ConvEffect
    data class StopListen(val turn: Int) : ConvEffect
    /** [typed]: from the keyboard (R13: timing "MT … ms" only, no automatic speech); [notes]: input rules that fired. */
    data class AddMessage(val turn: Int, val party: Party, val text: String, val typed: Boolean = false, val notes: List<RuleNote> = emptyList()) : ConvEffect

    /** Replace the source of message [turn] by [text] (said by [party]) and clear its old translation. */
    data class ReplaceSource(val turn: Int, val text: String, val party: Party, val notes: List<RuleNote> = emptyList()) : ConvEffect
    data class Translate(val turn: Int, val party: Party, val text: String) : ConvEffect

    /** [notes]/[checkTerms]: output rules that fired (R08 lost terms, R11 repeats collapsed). */
    data class SetTranslation(
        val turn: Int,
        val text: String?,
        val status: MessageStatus,
        val mtMs: Long? = null,
        val notes: List<RuleNote> = emptyList(),
        val checkTerms: List<String> = emptyList(),
    ) : ConvEffect
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
        val add = ConvEffect.AddMessage(turn, e.party, text, typed = true, notes = e.notes)
        // R03/R04: the text is the result; nothing to translate or speak, whatever else is going on.
        if (e.keep) return s.copy(nextTurn = turn + 1) to listOf(add, ConvEffect.SetTranslation(turn, text, MessageStatus.OK))
        val next = s.copy(nextTurn = turn + 1, textTurns = s.textTurns + turn)
        val fx = listOf(add, ConvEffect.Translate(turn, e.party, text))
        return when (val p = next.phase) {
            ConvPhase.Idle -> next.copy(phase = ConvPhase.Processing(e.party, turn)) to fx
            is ConvPhase.Speaking -> next.copy(phase = ConvPhase.Processing(e.party, turn)) to (stopSpeech(p) + fx)
            // Someone is talking or a turn is processing: show and translate it in the background.
            else -> next.copy(background = next.background + (turn to e.party)) to fx
        }
    }

    private fun onEdited(s: ConversationState, e: ConvEvent.Edited): Pair<ConversationState, List<ConvEffect>> {
        val text = e.text.trim()
        if (text.isEmpty() || e.turn >= s.nextTurn) return s to emptyList()
        if (e.turn in s.background) return s to listOf(ConvEffect.Notice(BUSY_NOTICE))
        val replace = ConvEffect.ReplaceSource(e.turn, text, e.party, e.notes)
        if (e.keep) {
            val kept = listOf(replace, ConvEffect.SetTranslation(e.turn, text, MessageStatus.OK))
            return when (val p = s.phase) {
                ConvPhase.Idle -> s to kept
                is ConvPhase.Speaking -> s.copy(phase = ConvPhase.Idle) to (stopSpeech(p, keepTurn = e.turn) + kept)
                else -> s to listOf(ConvEffect.Notice(BUSY_NOTICE))
            }
        }
        val fx = listOf(replace, ConvEffect.Translate(e.turn, e.party, text))
        val next = s.copy(textTurns = s.textTurns + e.turn)
        return when (val p = s.phase) {
            ConvPhase.Idle -> next.copy(phase = ConvPhase.Processing(e.party, e.turn)) to fx
            is ConvPhase.Speaking -> next.copy(phase = ConvPhase.Processing(e.party, e.turn)) to (stopSpeech(p, keepTurn = e.turn) + fx)
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
        val keyboard = e.turn in s.textTurns
        fun result(status: MessageStatus): ConvEffect.SetTranslation = when (r) {
            is MtOutcome.Ok -> ConvEffect.SetTranslation(e.turn, r.text, status, r.mtMs, r.notes, r.checkTerms)
            MtOutcome.NoTranslator -> ConvEffect.SetTranslation(e.turn, null, MessageStatus.NO_TRANSLATOR)
            is MtOutcome.Failed -> ConvEffect.SetTranslation(e.turn, null, MessageStatus.FAILED)
            is MtOutcome.Invalid -> ConvEffect.SetTranslation(e.turn, null, MessageStatus.INVALID, r.mtMs)
        }
        val notice = when (r) {
            is MtOutcome.Failed -> ConvEffect.Notice(r.message)
            is MtOutcome.Invalid -> ConvEffect.Notice(r.message)
            else -> null
        }
        if (e.turn in s.background) {
            // Keyboard text is never spoken automatically, so it is not "chưa phát" either.
            val bg = s.copy(background = s.background - e.turn, textTurns = s.textTurns - e.turn)
            return bg to listOfNotNull(result(if (keyboard) MessageStatus.OK else MessageStatus.NOT_PLAYED), notice.takeIf { r is MtOutcome.Invalid })
        }
        val p = s.phase as? ConvPhase.Processing
        if (p == null || p.turn != e.turn) return s to emptyList() // stale
        val idle = s.copy(phase = ConvPhase.Idle, textTurns = s.textTurns - e.turn)
        // R13: keyboard text is translated, and spoken only when the user taps the speaker.
        if (r is MtOutcome.Ok && !keyboard) {
            val id = s.nextSpeak
            return s.copy(phase = ConvPhase.Speaking(p.party, p.turn, id), nextSpeak = id + 1) to listOf(
                result(MessageStatus.OK),
                ConvEffect.Speak(id, e.turn, p.party.other.lang, r.text),
            )
        }
        return idle to listOfNotNull(result(MessageStatus.OK), notice)
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
