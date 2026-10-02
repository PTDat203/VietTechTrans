package vn.viettechtrans.app.turn

/**
 * Per-turn timings shown in the UI ("STT · MT · TTS · tổng") and later written to
 * evidence (definitions: docs/contracts/evidence_app_v1.md). Milliseconds; null = stage not run.
 */
data class TurnTimings(
    /** Capture end -> final transcript. */
    val sttMs: Long? = null,
    /** translate() call (the first ML Kit call also includes the model download check). */
    val mtMs: Long? = null,
    /** Start of synthesis -> first audio samples. */
    val ttsFirstAudioMs: Long? = null,
) {
    /** Capture (or typing) end -> first audio. */
    val totalMs: Long?
        get() = if (sttMs == null && mtMs == null && ttsFirstAudioMs == null) {
            null
        } else {
            (sttMs ?: 0) + (mtMs ?: 0) + (ttsFirstAudioMs ?: 0)
        }

    fun format(): String = listOf(
        "STT" to sttMs, "MT" to mtMs, "TTS" to ttsFirstAudioMs, "tổng" to totalMs,
    ).joinToString(" · ") { (name, ms) -> "$name ${ms?.let { "$it ms" } ?: "–"}" }
}
