package vn.viettechtrans.app.text

import java.util.Locale
import vn.viettechtrans.app.mt.Lang
import vn.viettechtrans.app.mt.MtInputNormalizer

/**
 * Cleans raw STT output before showing/translating it (plan §"Chuỗi xử lý văn bản" 1):
 * NFC + whitespace, casing by model output style, optional disfluency cleanup
 * (experiment TN-03), and a meaningfulness check (empty / filler-only turns are
 * not saved).
 */
object SttPostProcessor {
    data class Result(val raw: String, val text: String, val meaningful: Boolean)

    const val STYLE_UPPER_NO_PUNCT = "upper_no_punct"

    private val VI = Locale.forLanguageTag("vi")

    /** Standalone fillers removed by cleanup. "ừ", "à", "vâng", "ok", "yes" are real answers and kept. */
    private val FILLERS = mapOf(
        Lang.VI to setOf("ờ", "ờm", "ừm", "ưm", "hừm", "ơ"),
        Lang.EN to setOf("uh", "um", "uhm", "erm", "hmm", "mm"),
    )

    fun process(raw: String, lang: Lang, outputStyle: String, cleanup: Boolean = true): Result {
        var text = MtInputNormalizer.normalize(raw)
        if (outputStyle == STYLE_UPPER_NO_PUNCT) text = text.lowercase(if (lang == Lang.VI) VI else Locale.ENGLISH)
        if (cleanup) text = removeDisfluencies(text, lang)
        text = capitalizeFirst(text)
        return Result(raw = raw, text = text, meaningful = text.any { it.isLetterOrDigit() })
    }

    fun removeDisfluencies(text: String, lang: Lang): String {
        val fillers = FILLERS.getValue(lang)
        var words = text.split(' ').filter { it.isNotEmpty() && key(it) !in fillers }
        words = collapseWordRuns(words)
        for (n in 3 downTo 2) words = collapsePhraseRepeats(words, n)
        return words.joinToString(" ")
    }

    /** A word repeated 3+ times in a row becomes one ("tôi tôi tôi" -> "tôi"); a single repeat stays ("đi đi"). */
    private fun collapseWordRuns(words: List<String>): List<String> {
        val out = ArrayList<String>(words.size)
        var i = 0
        while (i < words.size) {
            var j = i + 1
            while (j < words.size && key(words[j]) == key(words[i])) j++
            if (j - i >= 3) out += words[j - 1] else for (k in i until j) out += words[k]
            i = j
        }
        return out
    }

    /** An n-word phrase repeated back to back keeps one copy ("tôi muốn tôi muốn" -> "tôi muốn"). */
    private fun collapsePhraseRepeats(words: List<String>, n: Int): List<String> {
        val out = words.toMutableList()
        var i = 0
        while (i + 2 * n <= out.size) {
            val same = (0 until n).all { key(out[i + it]) == key(out[i + n + it]) }
            if (same) repeat(n) { out.removeAt(i + n) } else i++
        }
        return out
    }

    private fun key(word: String): String = word.trim { !it.isLetterOrDigit() }.lowercase(VI)

    private fun capitalizeFirst(text: String): String {
        val idx = text.indexOfFirst { it.isLetter() }
        if (idx < 0 || text[idx].isUpperCase()) return text
        return text.substring(0, idx) + text[idx].titlecase(VI) + text.substring(idx + 1)
    }
}
