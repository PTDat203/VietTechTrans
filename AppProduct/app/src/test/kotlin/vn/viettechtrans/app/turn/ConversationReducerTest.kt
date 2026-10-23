package vn.viettechtrans.app.turn

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.viettechtrans.app.mt.Lang
import vn.viettechtrans.app.mt.rules.RuleNote
import vn.viettechtrans.app.turn.ConvEffect.AddMessage
import vn.viettechtrans.app.turn.ConvEffect.MarkInterrupted
import vn.viettechtrans.app.turn.ConvEffect.Notice
import vn.viettechtrans.app.turn.ConvEffect.ReplaceSource
import vn.viettechtrans.app.turn.ConvEffect.SetTranslation
import vn.viettechtrans.app.turn.ConvEffect.Speak
import vn.viettechtrans.app.turn.ConvEffect.StartListen
import vn.viettechtrans.app.turn.ConvEffect.StopListen
import vn.viettechtrans.app.turn.ConvEffect.StopSpeak
import vn.viettechtrans.app.turn.ConvEffect.Translate

class ConversationReducerTest {
    private fun run(vararg events: ConvEvent, start: ConversationState = ConversationState()): Pair<ConversationState, List<ConvEffect>> {
        var s = start
        val all = mutableListOf<ConvEffect>()
        for (e in events) {
            val (n, fx) = ConversationReducer.reduce(s, e)
            s = n
            all += fx
        }
        return s to all
    }

    private val spokenTurnOfA = arrayOf(
        ConvEvent.Tap(Party.A),
        ConvEvent.ListenEnded(1, Heard.Text("Xin chào")),
        ConvEvent.Translated(1, MtOutcome.Ok("Hello", 10)),
        ConvEvent.SpeakDone(1),
    )

    @Test
    fun fullTurnOfA() {
        val (s, fx) = run(*spokenTurnOfA)
        assertEquals(ConvPhase.Idle, s.phase)
        assertEquals(
            listOf(
                StartListen(Party.A, 1),
                AddMessage(1, Party.A, "Xin chào"),
                Translate(1, Party.A, "Xin chào"),
                SetTranslation(1, "Hello", MessageStatus.OK, 10),
                Speak(1, 1, Lang.EN, "Hello"),
            ),
            fx,
        )
    }

    @Test
    fun tappingOwnButtonEndsTheTurn() {
        val (s, fx) = run(ConvEvent.Tap(Party.A), ConvEvent.Tap(Party.A))
        assertEquals(ConvPhase.Processing(Party.A, 1), s.phase)
        assertEquals(StopListen(1), fx.last())
    }

    @Test
    fun otherSpeakerCutsTheTurnWhichIsTranslatedButNeverSpoken() {
        val (s, fx) = run(
            ConvEvent.Tap(Party.A),
            ConvEvent.Tap(Party.B),
            ConvEvent.ListenEnded(1, Heard.Text("Câu bị cắt")),
            ConvEvent.Translated(1, MtOutcome.Ok("Cut sentence", 5)),
        )
        assertEquals(ConvPhase.Listening(Party.B, 2), s.phase)
        assertTrue(fx.contains(StopListen(1)))
        assertTrue(fx.contains(StartListen(Party.B, 2)))
        assertTrue(fx.contains(SetTranslation(1, "Cut sentence", MessageStatus.NOT_PLAYED, 5)))
        assertFalse(fx.any { it is Speak })
        assertTrue(s.background.isEmpty())
    }

    @Test
    fun tapWhileSpeakingStopsSpeechBeforeOpeningMic() {
        val (s, fx) = run(
            ConvEvent.Tap(Party.A),
            ConvEvent.ListenEnded(1, Heard.Text("A")),
            ConvEvent.Translated(1, MtOutcome.Ok("A-en", 1)),
            ConvEvent.Tap(Party.B),
        )
        assertEquals(ConvPhase.Listening(Party.B, 2), s.phase)
        assertEquals(listOf(StopSpeak, MarkInterrupted(1), StartListen(Party.B, 2)), fx.takeLast(3))
    }

    @Test
    fun speechEndsInIdleWithoutOpeningAnyMic() {
        val (s, fx) = run(*spokenTurnOfA)
        assertEquals(ConvPhase.Idle, s.phase)
        assertEquals(1, fx.count { it is StartListen })
    }

    @Test
    fun noSpeechShowsNotice() {
        val (s, fx) = run(ConvEvent.Tap(Party.A), ConvEvent.ListenEnded(1, Heard.NoSpeech))
        assertEquals(ConvPhase.Idle, s.phase)
        assertTrue(fx.any { it is Notice })
    }

    @Test
    fun noTranslatorKeepsSourceOnly() {
        val (s, fx) = run(ConvEvent.Tap(Party.B), ConvEvent.ListenEnded(1, Heard.Text("Hi")), ConvEvent.Translated(1, MtOutcome.NoTranslator))
        assertEquals(ConvPhase.Idle, s.phase)
        assertTrue(fx.contains(SetTranslation(1, null, MessageStatus.NO_TRANSLATOR)))
        assertFalse(fx.any { it is Speak })
    }

    @Test
    fun staleEventsAreIgnored() {
        val (s, _) = run(ConvEvent.Tap(Party.A))
        val (s2, fx) = run(ConvEvent.SpeakDone(99), ConvEvent.Translated(42, MtOutcome.Ok("x", 1)), ConvEvent.ListenEnded(7, Heard.Text("y")), start = s)
        assertEquals(s, s2)
        assertTrue(fx.isEmpty())
    }

    @Test
    fun typingWhileSomeoneTalksIsTranslatedButNotSpoken() {
        val (s, fx) = run(ConvEvent.Tap(Party.A), ConvEvent.Typed(Party.B, "Hello"), ConvEvent.Translated(2, MtOutcome.Ok("Xin chào", 3)))
        assertEquals(ConvPhase.Listening(Party.A, 1), s.phase)
        // Keyboard text is never spoken by itself (R13), so it is not "chưa phát" either.
        assertTrue(fx.contains(SetTranslation(2, "Xin chào", MessageStatus.OK, 3)))
        assertFalse(fx.any { it is Speak })
    }

    /** Policy R13: typed text is translated and shown; it is read only when the user taps the speaker. */
    @Test
    fun typedSentenceIsTranslatedButNeverSpokenByItself() {
        val notes = listOf(RuleNote.NO_DIACRITICS)
        val (s, fx) = run(
            ConvEvent.Typed(Party.A, "toi can kiem tra loi API", notes = notes),
            ConvEvent.Translated(1, MtOutcome.Ok("I need to check the API", 5, listOf(RuleNote.CHECK_TERMS), listOf("API"))),
        )
        assertEquals(
            listOf(
                AddMessage(1, Party.A, "toi can kiem tra loi API", typed = true, notes = notes),
                Translate(1, Party.A, "toi can kiem tra loi API"),
                SetTranslation(1, "I need to check the API", MessageStatus.OK, 5, listOf(RuleNote.CHECK_TERMS), listOf("API")),
            ),
            fx,
        )
        assertEquals(ConvPhase.Idle, s.phase)
        assertTrue(s.textTurns.isEmpty())
    }

    /** R03/R04: the text itself is the result; no model, no speech, and nothing else is disturbed. */
    @Test
    fun keptLiteralIsShownWithoutTheModel() {
        val (s0, _) = run(ConvEvent.Tap(Party.A))
        val (s, fx) = run(ConvEvent.Typed(Party.B, "JavaScript", keep = true, notes = listOf(RuleNote.KEPT_LITERAL)), start = s0)
        assertEquals(
            listOf(
                AddMessage(2, Party.B, "JavaScript", typed = true, notes = listOf(RuleNote.KEPT_LITERAL)),
                SetTranslation(2, "JavaScript", MessageStatus.OK),
            ),
            fx,
        )
        assertEquals(ConvPhase.Listening(Party.A, 1), s.phase)
    }

    /** R12: an invalid output is not shown as a translation; the source stays for "Sửa". */
    @Test
    fun invalidOutputIsRejected() {
        val (s, fx) = run(ConvEvent.Typed(Party.A, "Xem https://example.com"), ConvEvent.Translated(1, MtOutcome.Invalid("bad", 4)))
        assertEquals(listOf(SetTranslation(1, null, MessageStatus.INVALID, 4), Notice("bad")), fx.drop(2))
        assertEquals(ConvPhase.Idle, s.phase)
    }

    @Test
    fun replayOnlyWhenIdleOrSpeaking() {
        val (_, fxListening) = run(ConvEvent.Tap(Party.A), ConvEvent.Replay("x", Lang.EN))
        assertFalse(fxListening.any { it is Speak })
        val (s, fx) = run(ConvEvent.Replay("Hello", Lang.EN))
        assertTrue(s.phase is ConvPhase.Speaking)
        assertEquals(Speak(1, null, Lang.EN, "Hello"), fx.single())
    }

    @Test
    fun editedSentenceIsTranslatedAgainButNotSpoken() {
        val (s0, _) = run(*spokenTurnOfA)
        val (s, fx) = run(
            ConvEvent.Edited(1, Party.A, " Xin chào bạn "),
            ConvEvent.Translated(1, MtOutcome.Ok("Hello friend", 7)),
            start = s0,
        )
        assertEquals(
            listOf(
                ReplaceSource(1, "Xin chào bạn", Party.A),
                Translate(1, Party.A, "Xin chào bạn"),
                SetTranslation(1, "Hello friend", MessageStatus.OK, 7),
            ),
            fx,
        )
        assertEquals(ConvPhase.Idle, s.phase)
    }

    /** R06 "Đổi sang English" on a correction: the message becomes the English speaker's. */
    @Test
    fun correctionCanChangeTheSpeaker() {
        val (s0, _) = run(*spokenTurnOfA)
        val (_, fx) = run(ConvEvent.Edited(1, Party.B, "Hello"), start = s0)
        assertEquals(listOf(ReplaceSource(1, "Hello", Party.B), Translate(1, Party.B, "Hello")), fx)
    }

    @Test
    fun editingWhileSpeakingStopsSpeechButDoesNotMarkTheEditedTurn() {
        val (s0, _) = run(ConvEvent.Tap(Party.A), ConvEvent.ListenEnded(1, Heard.Text("A")), ConvEvent.Translated(1, MtOutcome.Ok("A-en", 1)))
        val (_, fx) = run(ConvEvent.Edited(1, Party.A, "B"), start = s0)
        assertEquals(listOf(StopSpeak, ReplaceSource(1, "B", Party.A), Translate(1, Party.A, "B")), fx)
    }

    @Test
    fun editingWhileListeningOrForUnknownTurnIsRefused() {
        val (s0, _) = run(*spokenTurnOfA)
        val (s1, fx1) = run(ConvEvent.Tap(Party.B), ConvEvent.Edited(1, Party.A, "x"), start = s0)
        assertEquals(ConvPhase.Listening(Party.B, 2), s1.phase)
        assertEquals(Notice(ConversationReducer.BUSY_NOTICE), fx1.last())
        val (s2, fx2) = run(ConvEvent.Edited(99, Party.A, "x"), start = s0)
        assertEquals(s0, s2)
        assertTrue(fx2.isEmpty())
    }

    @Test
    fun resetStopsEverythingAndDropsLateResultsOfTheOldConversation() {
        val (s0, _) = run(ConvEvent.Tap(Party.A), ConvEvent.Typed(Party.B, "typed in background"))
        val (s, fx) = run(
            ConvEvent.Reset(minNextTurn = 1),
            ConvEvent.ListenEnded(1, Heard.Text("late")),
            ConvEvent.Translated(2, MtOutcome.Ok("late", 1)),
            start = s0,
        )
        assertEquals(listOf<ConvEffect>(StopListen(1)), fx)
        assertEquals(ConvPhase.Idle, s.phase)
        assertTrue(s.background.isEmpty())
        // New turns never reuse ids of the old conversation.
        assertEquals(3, s.nextTurn)
        assertEquals(10, run(ConvEvent.Reset(minNextTurn = 10), start = s).first.nextTurn)
    }

    /** Random event sequences must keep the half-duplex and single-speak invariants. */
    @Test
    fun fuzzInvariants() {
        val rnd = Random(20261007)
        repeat(10_000) { run ->
            var s = ConversationState()
            var speaking = false
            val spokenTurns = mutableSetOf<Int>()
            val cutTurns = mutableSetOf<Int>()
            val keyboardTurns = mutableSetOf<Int>()
            repeat(40) {
                val turnGuess = rnd.nextInt(1, s.nextTurn + 1)
                val e = when (rnd.nextInt(10)) {
                    0, 1 -> ConvEvent.Tap(if (rnd.nextBoolean()) Party.A else Party.B)
                    2 -> ConvEvent.ListenEnded(
                        turnGuess,
                        when (rnd.nextInt(3)) {
                            0 -> Heard.Text("t$turnGuess")
                            1 -> Heard.NoSpeech
                            else -> Heard.Failed("err")
                        },
                    )
                    3 -> ConvEvent.Translated(
                        turnGuess,
                        when (rnd.nextInt(6)) {
                            0 -> MtOutcome.Failed("x")
                            1 -> MtOutcome.Invalid("bad", 1)
                            else -> MtOutcome.Ok("tr$turnGuess", 1)
                        },
                    )
                    4 -> ConvEvent.SpeakDone(rnd.nextInt(1, s.nextSpeak + 1))
                    5 -> ConvEvent.StopSpeaking
                    6 -> ConvEvent.Typed(if (rnd.nextBoolean()) Party.A else Party.B, "typed", keep = rnd.nextInt(4) == 0)
                    7 -> ConvEvent.Edited(turnGuess, if (rnd.nextBoolean()) Party.A else Party.B, "edited", keep = rnd.nextInt(4) == 0)
                    8 -> if (rnd.nextInt(5) == 0) ConvEvent.Reset(rnd.nextInt(1, 50)) else ConvEvent.Replay("r", Lang.VI)
                    else -> ConvEvent.Replay("r", Lang.VI)
                }
                if (e is ConvEvent.SpeakDone && (s.phase as? ConvPhase.Speaking)?.speakId == e.speakId) speaking = false
                val (n, fx) = ConversationReducer.reduce(s, e)
                assertTrue("run $run: turn ids never go back", n.nextTurn >= s.nextTurn)
                for (f in fx) {
                    when (f) {
                        StopSpeak -> speaking = false
                        is StartListen -> assertFalse("run $run: mic opened while speaking", speaking)
                        is ReplaceSource -> {
                            // A corrected sentence is keyboard text: it is never spoken automatically (R13).
                            spokenTurns -= f.turn
                            cutTurns -= f.turn
                            keyboardTurns += f.turn
                        }
                        is AddMessage -> if (f.typed) keyboardTurns += f.turn
                        is Speak -> {
                            assertTrue("run $run: speak while mic open", n.phase !is ConvPhase.Listening)
                            f.turn?.let { t ->
                                assertFalse("run $run: turn $t spoken twice", t in spokenTurns)
                                assertFalse("run $run: cut turn $t spoken", t in cutTurns)
                                assertFalse("run $run: keyboard turn $t spoken by itself", t in keyboardTurns)
                                spokenTurns += t
                            }
                            speaking = true
                        }
                        else -> Unit
                    }
                }
                if (e is ConvEvent.Reset) {
                    speaking = false
                    cutTurns.clear()
                }
                cutTurns += n.background.keys
                s = n
            }
        }
    }
}
