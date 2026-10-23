package vn.viettechtrans.app.turn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Policy R13: keyboard text shows only "MT <n> ms"; a spoken turn the whole line. */
class TurnTimingsTest {
    @Test
    fun typedTextShowsOnlyMt() {
        assertEquals("MT 576 ms", TurnTimings(mtMs = 576).formatTextOnly())
        assertEquals("MT 576 ms", TurnTimings(sttMs = 10, mtMs = 576, ttsFirstAudioMs = 30).formatTextOnly())
        assertNull(TurnTimings().formatTextOnly()) // R03/R04: the model was not called
    }

    @Test
    fun spokenTurnShowsEveryStage() {
        assertEquals("STT 120 ms · MT 576 ms · TTS 300 ms · tổng 996 ms", TurnTimings(120, 576, 300).format())
    }
}
