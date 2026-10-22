package vn.viettechtrans.app.mt.marian

/**
 * App post-processing of a decoded translation (DR-07, 2026-10-07); port of `collapse_repeats` in
 * tools/mt/marian_ref.py. The model sometimes loops ("xin chao" -> "Please, please, please, ...",
 * "chế độ độ độ độ độ trễ"): a unit of 1-3 words repeated at least 4 times in a row keeps only its
 * first occurrence. On the four Validation sets this changes 26 of 32 498 outputs, all loops, and
 * chrF++ rises by 0-0.07.
 */
object OutputCleanup {
    private const val MIN_REPEATS = 4
    private const val MAX_UNIT = 3

    /**
     * Words compare by their letters and digits, case-insensitive. A loop that ends the text gives its
     * last punctuation to the kept unit; a trailing ",;:" is dropped. Text without a loop is unchanged.
     */
    fun collapseRepeats(text: String): String {
        val words = text.split(WHITESPACE).filter { it.isNotEmpty() }
        val keys = words.map { w -> w.filter { it.isLetterOrDigit() }.lowercase() }
        val out = ArrayList<String>()
        var collapsed = false
        var i = 0
        while (i < words.size) {
            var matched = false
            for (n in 1..MAX_UNIT) {
                if (i + n > words.size) break
                val unit = keys.subList(i, i + n)
                if (unit.any { it.isEmpty() }) continue
                var reps = 1
                while (i + (reps + 1) * n <= words.size && keys.subList(i + reps * n, i + (reps + 1) * n) == unit) reps++
                if (reps >= MIN_REPEATS) {
                    val kept = words.subList(i, i + n).toMutableList()
                    val end = i + reps * n
                    if (end == words.size) {
                        kept[kept.lastIndex] = kept.last().dropLastWhile { !it.isLetterOrDigit() } +
                            words.last().takeLastWhile { !it.isLetterOrDigit() }
                    }
                    out += kept
                    i = end
                    collapsed = true
                    matched = true
                    break
                }
            }
            if (!matched) out += words[i++]
        }
        return if (collapsed) out.joinToString(" ").trimEnd(',', ';', ':', ' ') else text
    }

    private val WHITESPACE = Regex("\\s+")
}
