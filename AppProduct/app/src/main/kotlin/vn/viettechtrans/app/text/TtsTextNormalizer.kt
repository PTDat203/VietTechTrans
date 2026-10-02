package vn.viettechtrans.app.text

import java.util.Locale
import vn.viettechtrans.app.mt.Lang
import vn.viettechtrans.app.mt.MtInputNormalizer

/**
 * Prepares text for the Piper voices (plan §"Chuỗi xử lý văn bản" 3).
 * Vietnamese: IT terms from the lexicon (experiment TN-07), all-caps acronyms
 * spelled with English letter names, "3.12" -> "3 chấm 12", path/identifier
 * separators to spaces. English: "3.12" -> "3 point 12", camelCase/snake_case split.
 */
class TtsTextNormalizer(private val lexiconVi: Map<String, String>) {
    companion object {
        const val LEXICON_ASSET = "text/tts_lexicon_vi.tsv"
        const val MAX_CHUNK_WORDS = 25

        private val VERSION = Regex("""(\d+)\.(\d+)""")
        /** Whole Latin-script tokens only: "VI" inside "VIỆT" is not a token. */
        private val LATIN_TOKEN = Regex("""(?<![\p{L}\p{N}])[A-Za-z][A-Za-z0-9+#\-]*(?![\p{L}\p{N}])""")
        private val CAMEL = Regex("""(?<=[a-z])(?=[A-Z])""")
        private val SEPARATORS = Regex("""[_/\\]+""")
        private val SENTENCE_END = Regex("""(?<=[.!?;:])\s+|\n+""")

        /** English letter names written for the Vietnamese voice. */
        private val LETTERS_VI = mapOf(
            'A' to "ây", 'B' to "bi", 'C' to "xi", 'D' to "đi", 'E' to "i", 'F' to "ép", 'G' to "gi",
            'H' to "ết", 'I' to "ai", 'J' to "giây", 'K' to "cây", 'L' to "eo", 'M' to "em", 'N' to "en",
            'O' to "âu", 'P' to "pi", 'Q' to "kiu", 'R' to "a", 'S' to "ét", 'T' to "ti", 'U' to "iu",
            'V' to "vi", 'W' to "đắp liu", 'X' to "ích", 'Y' to "oai", 'Z' to "dét",
        )

        /** Parses `term<TAB>pronunciation` lines; '#' starts a comment. Keys are lowercase. */
        fun parseLexicon(tsv: String): Map<String, String> = tsv.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size >= 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                    parts[0].trim().lowercase(Locale.ROOT) to parts[1].trim()
                } else {
                    null
                }
            }
            .toMap()
    }

    fun normalize(text: String, lang: Lang, useLexicon: Boolean = true): String {
        var t = MtInputNormalizer.normalize(text)
        return when (lang) {
            Lang.VI -> {
                t = VERSION.replace(t) { "${it.groupValues[1]} chấm ${it.groupValues[2]}" }
                t = SEPARATORS.replace(t, " ")
                LATIN_TOKEN.replace(t) { m ->
                    val token = m.value
                    val fromLexicon = if (useLexicon) lexiconVi[token.lowercase(Locale.ROOT)] else null
                    when {
                        fromLexicon != null -> fromLexicon
                        useLexicon && token.length in 2..6 && token.all { it in 'A'..'Z' } ->
                            token.map { LETTERS_VI.getValue(it) }.joinToString(" ")
                        else -> token
                    }
                }
            }
            Lang.EN -> {
                t = VERSION.replace(t) { "${it.groupValues[1]} point ${it.groupValues[2]}" }
                t = SEPARATORS.replace(t, " ")
                CAMEL.replace(t, " ")
            }
        }.replace(Regex(" {2,}"), " ").trim()
    }

    /**
     * Sentences, then pieces of at most [MAX_CHUNK_WORDS] words; each piece is one
     * synthesis call. The first sentence is split at its first comma so the first
     * audio starts sooner.
     */
    fun chunks(text: String): List<String> {
        val sentences = SENTENCE_END.split(text).map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        if (sentences.isNotEmpty()) {
            val firstComma = sentences[0].indexOf(", ")
            if (firstComma > 0 && sentences[0].substring(0, firstComma).count { it == ' ' } >= 2) {
                val head = sentences[0].substring(0, firstComma + 1)
                val tail = sentences[0].substring(firstComma + 2)
                sentences[0] = head
                if (tail.isNotBlank()) sentences.add(1, tail)
            }
        }
        return sentences.flatMap { sentence ->
            val words = sentence.split(' ').filter { it.isNotEmpty() }
            words.chunked(MAX_CHUNK_WORDS).map { it.joinToString(" ") }
        }
    }
}
