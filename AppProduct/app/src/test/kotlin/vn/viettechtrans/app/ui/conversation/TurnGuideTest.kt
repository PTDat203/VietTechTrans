package vn.viettechtrans.app.ui.conversation

import org.junit.Assert.assertEquals
import org.junit.Test
import vn.viettechtrans.app.turn.ConvPhase
import vn.viettechtrans.app.turn.Party

class TurnGuideTest {
    private fun msg(turn: Int, party: Party) = ChatMessage(turn = turn, party = party, source = "s$turn")

    @Test
    fun emptyConversationAsksSomeoneToStart() {
        assertEquals(Guide(GuideKind.START, null, null), guideOf(ConvPhase.Idle, emptyList()))
    }

    @Test
    fun eachStepOfATurnNamesTheSpeaker() {
        assertEquals(Guide(GuideKind.LISTENING, Party.A, GuideStep.LISTEN), guideOf(ConvPhase.Listening(Party.A, 1), emptyList()))
        assertEquals(Guide(GuideKind.PROCESSING, Party.B, GuideStep.TRANSLATE), guideOf(ConvPhase.Processing(Party.B, 2), emptyList()))
        assertEquals(Guide(GuideKind.SPEAKING, Party.A, GuideStep.SPEAK), guideOf(ConvPhase.Speaking(Party.A, 1, 7), emptyList()))
        // "Nghe lại" of an earlier message belongs to nobody's turn.
        assertEquals(Guide(GuideKind.REPLAY, null, GuideStep.SPEAK), guideOf(ConvPhase.Speaking(null, null, 8), emptyList()))
    }

    @Test
    fun afterATurnTheOtherPersonIsNext() {
        // The newest message is the one with the highest turn, whatever the list order.
        val messages = listOf(msg(1, Party.A), msg(3, Party.B), msg(2, Party.A))
        assertEquals(Guide(GuideKind.NEXT, Party.A, null), guideOf(ConvPhase.Idle, messages))
        assertEquals(Guide(GuideKind.NEXT, Party.B, null), guideOf(ConvPhase.Idle, listOf(msg(1, Party.A))))
    }
}
