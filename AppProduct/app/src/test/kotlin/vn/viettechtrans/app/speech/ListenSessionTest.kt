package vn.viettechtrans.app.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fake VAD driven by a script: frame index -> speech flag; emits a segment when speech ends. */
private class ScriptedVad(private val speechFrames: Set<Int>, private val frame: Int = 512) : VoiceDetector {
    private var index = 0
    private var speaking = false
    private var start = -1
    private val pending = mutableListOf<VoiceSegment>()

    override fun accept(frame: FloatArray) {
        val now = index in speechFrames
        if (now && !speaking) start = index * this.frame
        if (!now && speaking) pending += VoiceSegment(start, index * this.frame - start)
        speaking = now
        index++
    }

    override fun isSpeech() = speaking
    override fun pollSegments() = pending.toList().also { pending.clear() }
    override fun flush(): List<VoiceSegment> {
        if (speaking) pending += VoiceSegment(start, index * frame - start)
        speaking = false
        return pollSegments()
    }

    override fun release() = Unit
}

class ListenSessionTest {
    private val frame = FloatArray(512) { 0.1f }
    private val framesPerSecond = 16_000 / 512.0

    private fun frames(seconds: Double) = (seconds * framesPerSecond).toInt()

    @Test
    fun endsAfterSilenceFollowingSpeech() {
        val speech = (frames(1.0) until frames(2.0)).toSet()
        val s = ListenSession(ScriptedVad(speech), ListenConfig())
        var reason: EndReason? = null
        var i = 0
        while (reason == null && i < frames(10.0)) {
            reason = s.accept(frame)
            i++
        }
        assertEquals(EndReason.END_SILENCE, reason)
        // speech ended at 2.0 s, end-of-turn silence 1.5 s
        assertEquals(3.5, i / framesPerSecond, 0.1)
        assertTrue(s.heardSpeech)
        assertEquals(1, s.takeSegments().size + s.finish().size)
    }

    @Test
    fun noSpeechTimesOut() {
        val s = ListenSession(ScriptedVad(emptySet()), ListenConfig())
        var reason: EndReason? = null
        var i = 0
        while (reason == null) {
            reason = s.accept(frame)
            i++
        }
        assertEquals(EndReason.NO_SPEECH, reason)
        assertEquals(6.0, i / framesPerSecond, 0.1)
    }

    @Test
    fun hesitationShorterThanEndSilenceDoesNotEndTurn() {
        val speech = ((frames(0.5) until frames(1.5)) + (frames(2.5) until frames(3.5))).toSet() // 1 s pause
        val s = ListenSession(ScriptedVad(speech), ListenConfig())
        for (i in 0 until frames(3.5)) assertNull("frame $i", s.accept(frame))
    }

    @Test
    fun maxTurnEndsLongSpeech() {
        val speech = (0 until frames(70.0)).toSet()
        val s = ListenSession(ScriptedVad(speech), ListenConfig(maxTurnMs = 5_000))
        var reason: EndReason? = null
        while (reason == null) reason = s.accept(frame)
        assertEquals(EndReason.MAX_TURN, reason)
    }

    @Test
    fun segmentAudioIncludesPreAndPostRoll() {
        val speech = (frames(1.0) until frames(2.0)).toSet()
        val s = ListenSession(ScriptedVad(speech), ListenConfig())
        repeat(frames(3.0)) { s.accept(frame) }
        val seg = s.takeSegments().single()
        val audio = s.audioOf(seg)
        // 1 s of speech + 0.4 s pre-roll + 0.2 s post-roll (frame rounding tolerance)
        assertEquals(1.6, audio.size / 16_000.0, 0.05)
    }

    @Test
    fun partialAudioOnlyWhileSpeakingAndThrottled() {
        val speech = (frames(1.0) until frames(3.0)).toSet()
        val s = ListenSession(ScriptedVad(speech), ListenConfig(partialIntervalMs = 500))
        var partials = 0
        for (i in 0 until frames(2.9)) {
            s.accept(frame)
            if (s.partialAudioIfDue() != null) partials++
        }
        // speech from 1.0 s to 2.9 s, one partial every 0.5 s
        assertTrue("partials=$partials", partials in 3..5)
        val disabled = ListenSession(ScriptedVad(speech), ListenConfig(partialIntervalMs = 0))
        repeat(frames(2.0)) { disabled.accept(frame) }
        assertNull(disabled.partialAudioIfDue())
    }

    @Test
    fun finishFlushesOngoingSpeech() {
        val speech = (frames(0.5) until frames(10.0)).toSet()
        val s = ListenSession(ScriptedVad(speech), ListenConfig())
        repeat(frames(2.0)) { s.accept(frame) }
        val segs = s.finish()
        assertEquals(1, segs.size)
        assertNotNull(s.audioOf(segs[0]))
    }
}
