package vn.viettechtrans.app.text

/**
 * Vietnamese typed without diacritics ("chao ban", "xin chao") is outside what the MT model was
 * trained on: it translates it wrongly ("chao ban" -> "Order") or repeats a word, and so does the
 * Phase 03 checkpoint itself. STT always writes diacritics, so this only concerns typed text; the
 * app still translates and shows [NOTICE].
 */
object VietnameseDiacritics {
    const val NOTICE = "Câu tiếng Việt không có dấu nên dễ bị dịch sai. Hãy bật bàn phím tiếng Việt và gõ có dấu."

    private val WORD_BREAK = Regex("[\\s\\p{Punct}]+")

    /** At least two words with letters, and no letter outside ASCII (every Vietnamese diacritic, and đ, is). */
    fun missing(text: String): Boolean {
        val words = text.split(WORD_BREAK).count { w -> w.any { it.isLetter() } }
        return words >= 2 && text.none { it.isLetter() && it.code > 0x7F }
    }
}
