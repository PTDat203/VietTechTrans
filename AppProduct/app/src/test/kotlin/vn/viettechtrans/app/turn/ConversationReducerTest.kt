package vn.viettechtrans.app.turn

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.viettechtrans.app.mt.Lang
import vn.viettechtrans.app.turn.ConvEffect.AddMessage
import vn.viettechtrans.app.turn.ConvEffect.MarkInterrupted
import vn.viettechtrans.app.turn.ConvEffect.Notice
import vn.viettechtrans.app.turn.ConvEffect.SetTranslation
import vn.viettechtrans.app.turn.ConvEffect.Speak
import vn.viettechtrans.app.turn.ConvEffect.StartListen
import vn.viettechtrans.app.turn.ConvEffect.StopListen
import vn.viettechtrans.app.turn.ConvEffect.StopSpeak
import vn.viettechtrans.app.turn.ConvEffect.Translate

class ConversationReducerTest {
    private val cut = ConversationConfig(InterruptPolicy.CUT)
    private val queue = ConversationConfig(InterruptPolicy.QUEUE)

    private fun run(cfg: ConversationConfig, vararg events: ConvEvent, start: ConversationState = ConversationState()): Pair<ConversationState, List<ConvEffect>> {
        var s = start
        val all = mutableListOf<ConvEffect>()
        for (e in events) {
            val (n, fx) = ConversationReducer.reduce(s, e, cfg)
            s = n
            all += fx
        }
        return s to all
    }

    @Test
    fun fullTurnOfA() {
        val (s, fx) = run(
            cut,
            ConvEvent.Tap(Party.A),
            ConvEvent.ListenEnded(1, Heard.Text("Xin chào")),
            ConvEvent.Translated(1, MtOutcome.Ok("Hello", 10)),
            ConvEvent.SpeakDone(1),
        )
        assertEquals(ConvPhase.Idle, s.phase)
        assertEquals(
            listOf(
                StartListen(Party.A, 1, false),
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
        val (s, fx) = run(cut, ConvEvent.Tap(Party.A), ConvEvent.Tap(Party.A))
        assertEquals(ConvPhase.Processing(Party.A, 1), s.phase)
        assertEquals(StopListen(1), fx.last())
    }

    @Test
    fun cutWhileListeningMovesTurnToBackgroundAndNeverSpeaksIt() {
        val (s, fx) = run(
            cut,
            ConvEvent.Tap(Party.A),
            ConvEvent.Tap(Party.B),
            ConvEvent.ListenEnded(1, Heard.Text("Câu bị cắt")),
            ConvEvent.Translated(1, MtOutcome.Ok("Cut sentence", 5)),
        )
        assertEquals(ConvPhase.Listening(Party.B, 2, false), s.phase)
        assertTrue(fx.contains(StopListen(1)))
        assertTrue(fx.contains(StartListen(Party.B, 2, false)))
        assertTrue(fx.contains(SetTranslation(1, "Cut sentence", MessageStatus.NOT_PLAYED, 5)))
        assertFalse(fx.any { it is Speak })
        assertTrue(s.background.isEmpty())
    }

    @Test
    fun queueWhileListeningWaitsUntilSpeechEnds() {
        val (s1, fx1) = run(queue, ConvEvent.Tap(Party.A), ConvEvent.Tap(Party.B))
        assertEquals(Party.B, s1.pending)
        assertEquals(listOf<ConvEffect>(StartListen(Party.A, 1, false)), fx1)
        val (s2, fx2) = run(
            queue,
            ConvEvent.ListenEnded(1, Heard.Text("Xin chào")),
            ConvEvent.Translated(1, MtOutcome.Ok("Hello", 1)),
            ConvEvent.SpeakDone(1),
            start = s1,
        )
        assertEquals(ConvPhase.Listening(Party.B, 2, false), s2.phase)
        assertEquals(null, s2.pending)
        assertEquals(StartListen(Party.B, 2, false), fx2.last())
    }

    @Test
    fun queueTapAgainCancelsPending() {
        val (s, _) = run(queue, ConvEvent.Tap(Party.A), ConvEvent.Tap(Party.B), ConvEvent.Tap(Party.B))
        assertEquals(null, s.pending)
    }

    @Test
    fun tapWhileSpeakingStopsSpeechBeforeOpeningMic() {
        val (s, fx) = run(
            cut,
            ConvEvent.Tap(Party.A),
            ConvEvent.ListenEnded(1, Heard.Text("A")),
            ConvEvent.Translated(1, MtOutcome.Ok("A-en", 1)),
            ConvEvent.Tap(Party.B),
        )
        assertEquals(ConvPhase.Listening(Party.B, 2, false), s.phase)
        val tail = fx.takeLast(3)
        assertEquals(listOf(StopSpeak, MarkInterrupted(1), StartListen(Party.B, 2, false)), tail)
    }

    @Test
    fun autoAlternateOpensOtherMicAfterSpeech() {
        val cfg = ConversationConfig(autoAlternate = true)
        val (s, fx) = run(
            cfg,
            ConvEvent.Tap(Party.A),
            ConvEvent.ListenEnded(1, Heard.Text("A")),
            ConvEvent.Translated(1, MtOutcome.Ok("A-en", 1)),
            ConvEvent.SpeakDone(1),
        )
        assertEquals(ConvPhase.Listening(Party.B, 2, true), s.phase)
        assertEquals(StartListen(Party.B, 2, true), fx.last())
        // Silence on an automatic turn ends the alternation without a notice.
        val (s2, fx2) = run(cfg, ConvEvent.ListenEnded(2, Heard.NoSpeech), start = s)
        assertEquals(ConvPhase.Idle, s2.phase)
        assertFalse(fx2.any { it is Notice })
    }

    @Test
    fun manualStopEndsAlternation() {
        val cfg = ConversationConfig(autoAlternate = true)
        val (s, _) = run(
            cfg,
            ConvEvent.Tap(Party.A),
            ConvEvent.ListenEnded(1, Heard.Text("A")),
            ConvEvent.Translated(1, MtOutcome.Ok("A-en", 1)),
            ConvEvent.StopSpeaking,
        )
        assertEquals(ConvPhase.Idle, s.phase)
    }

    @Test
    fun manualNoSpeechShowsNotice() {
        val (s, fx) = run(cut, ConvEvent.Tap(Party.A), ConvEvent.ListenEnded(1, Heard.NoSpeech))
        assertEquals(ConvPhase.Idle, s.phase)
        assertTrue(fx.any { it is Notice })
    }

    @Test
    fun noTranslatorKeepsSourceOnly() {
        val (s, fx) = run(cut, ConvEvent.Tap(Party.B), ConvEvent.ListenEnded(1, Heard.Text("Hi")), ConvEvent.Translated(1, MtOutcome.NoTranslator))
        assertEquals(ConvPhase.Idle, s.phase)
        assertTrue(fx.contains(SetTranslation(1, null, MessageStatus.NO_TRANSLATOR)))
        assertFalse(fx.any { it is Speak })
    }

    @Test
    fun staleEventsAreIgnored() {
        val (s, _) = run(cut, ConvEvent.Tap(Party.A))
        val (s2, fx) = run(cut, ConvEvent.SpeakDone(99), ConvEvent.Translated(42, MtOutcome.Ok("x", 1)), ConvEvent.ListenEnded(7, Heard.Text("y")), start = s)
        assertEquals(s, s2)
        assertTrue(fx.isEmpty())
    }

    @Test
    fun typingWhileSomeoneTalksIsTranslatedButNotSpoken() {
        val (s, fx) = run(
            cut,
            ConvEvent.Tap(Party.A),
            ConvEvent.Typed(Party.B, "Hello"),
            ConvEvent.Translated(2, MtOutcome.Ok("Xin chào", 3)),
        )
        assertEquals(ConvPhase.Listening(Party.A, 1, false), s.phase)
        assertTrue(fx.contains(SetTranslation(2, "Xin chào", MessageStatus.NOT_PLAYED, 3)))
        assertFalse(fx.any { it is Speak })
    }

    @Test
    fun replayOnlyWhenIdleOrSpeaking() {
        val (_, fxListening) = run(cut, ConvEvent.Tap(Party.A), ConvEvent.Replay("x", Lang.EN))
        assertFalse(fxListening.any { it is Speak })
        val (s, fx) = run(cut, ConvEvent.Replay("Hello", Lang.EN))
        assertTrue(s.phase is ConvPhase.Speaking)
        assertEquals(Speak(1, null, Lang.EN, "Hello"), fx.single())
    }

    /** Random event sequences must keep the half-duplex and single-speak invariants. */
    @Test
    fun fuzzInvariants() {
        val rnd = Random(20261002)
        repeat(10_000) { run ->
            val cfg = ConversationConfig(
                policy = if (rnd.nextBoolean()) InterruptPolicy.CUT else InterruptPolicy.QUEUE,
                autoAlternate = rnd.nextBoolean(),
            )
            var s = ConversationState()
            var speaking = false
            var micOpenTurn: Int? = null
            val spokenTurns = mutableSetOf<Int>()
            val cutTurns = mutableSetOf<Int>()
            repeat(40) {
                val turnGuess = rnd.nextInt(1, s.nextTurn + 1)
                val e = when (rnd.nextInt(8)) {
                    0, 1 -> ConvEvent.Tap(if (rnd.nextBoolean()) Party.A else Party.B)
                    2 -> ConvEvent.ListenEnded(
                        turnGuess,
                        when (rnd.nextInt(3)) {
                            0 -> Heard.Text("t$turnGuess")
                            1 -> Heard.NoSpeech
                            else -> Heard.Failed("err")
                        },
                    )
                    3 -> ConvEvent.Translated(turnGuess, if (rnd.nextInt(4) == 0) MtOutcome.Failed("x") else MtOutcome.Ok("tr$turnGuess", 1))
                    4 -> ConvEvent.SpeakDone(rnd.nextInt(1, s.nextSpeak + 1))
                    5 -> ConvEvent.StopSpeaking
                    6 -> ConvEvent.Typed(if (rnd.nextBoolean()) Party.A else Party.B, "typed")
                    else -> ConvEvent.Replay("r", Lang.VI)
                }
                if (e is ConvEvent.SpeakDone && (s.phase as? ConvPhase.Speaking)?.speakId == e.speakId) speaking = false
                val (n, fx) = ConversationReducer.reduce(s, e, cfg)
                for (f in fx) {
                    when (f) {
                        StopSpeak -> speaking = false
                        is StartListen -> {
                            assertFalse("run $run: mic opened while speaking", speaking)
                            micOpenTurn = f.turn
                        }
                        is StopListen -> micOpenTurn = null
                        is Speak -> {
                            assertTrue("run $run: speak while mic open", n.phase !is ConvPhase.Listening)
                            f.turn?.let { t ->
                                assertFalse("run $run: turn $t spoken twice", t in spokenTurns)
                                assertFalse("run $run: cut turn $t spoken", t in cutTurns)
                                spokenTurns += t
                            }
                            speaking = true
                        }
                        else -> Unit
                    }
                }
                cutTurns += n.background.keys
                // At most one listener: Listening is a single phase value; pending never equals the listener.
                (n.phase as? ConvPhase.Listening)?.let { assertTrue("run $run", n.pending != it.party) }
                s = n
            }
        }
    }
}
