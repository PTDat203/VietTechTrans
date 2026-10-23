package vn.viettechtrans.app.mt.rules

import java.text.BreakIterator
import vn.viettechtrans.app.mt.Direction
import vn.viettechtrans.app.mt.Lang
import vn.viettechtrans.app.mt.MtInputNormalizer

/**
 * A rule that fired on one message, shown on its bubble (policy R13: "cảnh báo rule đã kích hoạt") and saved with
 * it. [text] is the policy's wording where the policy gives one.
 */
enum class RuleNote(val rule: String, val text: String) {
    KEPT_LITERAL("R03", "Thuật ngữ/ký hiệu được giữ nguyên"),
    CONTEXT_TERM("R04", "Từ kỹ thuật ngắn có thể có nhiều nghĩa. Hãy nhập cả câu nếu cần dịch theo ngữ cảnh."),
    SHORT_INPUT("R05", "Nội dung rất ngắn; kết quả có thể thiếu ngữ cảnh"),
    LANGUAGE_UNCERTAIN("R06", "Không nhận ra rõ ngôn ngữ của câu; app dịch theo chiều bạn đã chọn."),
    LANGUAGE_OVERRIDDEN("R06", "Đã chọn \"Vẫn dịch\" dù ngôn ngữ có vẻ không khớp chiều dịch"),
    NO_DIACRITICS("R07", "Câu tiếng Việt không dấu có thể dịch sai. Hãy thêm dấu nếu có thể."),
    CHECK_TERMS("R08", "Cần kiểm tra thuật ngữ"),
    REPEATS_COLLAPSED("R11", "Đã gộp cụm từ lặp trong bản dịch"),
    ;

    companion object {
        fun parse(names: List<String>): List<RuleNote> = names.mapNotNull { n -> entries.firstOrNull { it.name == n } }
    }
}

/** What to do with a typed message (policy order R01 → R07). */
sealed interface InputDecision {
    /** Text after R01. */
    val text: String

    /** R02: nothing left after normalization. */
    data class Empty(override val text: String) : InputDecision

    /** R02: more than [TextRules.MAX_CHARS] displayed characters; sending is locked, the text is kept. */
    data class TooLong(override val text: String, val length: Int) : InputDecision

    /** R03/R04: shown as typed, the model is not called. */
    data class Keep(override val text: String, val note: RuleNote, val literal: LiteralKind? = null) : InputDecision

    /** R06: the text looks like the other language; ask before sending ("Đổi sang …" or "Vẫn dịch"). */
    data class Mismatch(override val text: String, val guess: LangGuess, val suggested: Direction) : InputDecision

    /** Call the model; [notes] are the input warnings (R05 short input, R06 uncertain/overridden, R07). */
    data class Translate(override val text: String, val notes: List<RuleNote>, val guess: LangGuess) : InputDecision
}

/** Result of the output checks of one translation (R08, R12). */
sealed interface OutputCheck {
    /** [checkTerms]: lexicon terms of the source that the output lost (R08); the output is still shown as is. */
    data class Valid(val checkTerms: List<String>) : OutputCheck

    /** R12: empty output, or an invariant literal of the source (URL, email, code, HTTP status, path) is missing. */
    data class Invalid(val reason: String, val missing: List<Literal>) : OutputCheck
}

/**
 * The text-input rules of docs/contracts/mt_app_rule_policy_v1.md (R01–R08, R12), around the model and never inside it:
 * no rule changes the model, its generation settings or a translation.
 */
class TextRules(val lexicon: RuleLexicon) {
    val literals = TechLiterals(lexicon)
    val languages = LanguageGuesser(literals)

    /**
     * R01 → R07 for [raw] typed for [direction]. [override]: the user already answered "Vẫn dịch" to the R06 question;
     * [skipMismatch]: the user switched direction from that question (do not ask again).
     */
    fun decide(raw: String, direction: Direction, override: Boolean = false, skipMismatch: Boolean = false): InputDecision {
        val text = MtInputNormalizer.normalize(raw) // R01
        val length = displayLength(text)
        if (length == 0) return InputDecision.Empty(text) // R02
        if (length > MAX_CHARS) return InputDecision.TooLong(text, length)

        literals.whole(text)?.let { return InputDecision.Keep(text, RuleNote.KEPT_LITERAL, it) } // R03
        if (lexicon.isContextTerm(text)) return InputDecision.Keep(text, RuleNote.CONTEXT_TERM) // R04

        val english = direction.source == Lang.EN
        if (lexicon.isDialogueWord(text, direction)) return InputDecision.Translate(text, emptyList(), if (english) LangGuess.EN_HIGH else LangGuess.VI_HIGH) // R05
        val notes = ArrayList<RuleNote>()
        // "Một từ": one token of word length; a 500-character run without spaces is not "rất ngắn".
        val bare = SimplePunctuation.trim(text)
        val oneWord = bare.split(SPACES).count { it.isNotEmpty() } == 1 && displayLength(bare) <= SHORT_WORD_MAX
        if (oneWord) notes += RuleNote.SHORT_INPUT

        // R06: a dialogue word of the other direction is clear evidence too ("thanks" typed as Vietnamese).
        val evidence = languages.evidence(text)
        val guess = if (lexicon.isDialogueWord(text, direction.reverse)) {
            if (english) LangGuess.VI_HIGH else LangGuess.EN_HIGH
        } else {
            languages.guess(evidence)
        }
        val mismatch = english && guess == LangGuess.VI_HIGH || !english && guess == LangGuess.EN_HIGH
        when {
            mismatch && override -> notes += RuleNote.LANGUAGE_OVERRIDDEN
            mismatch && !skipMismatch -> return InputDecision.Mismatch(text, guess, direction.reverse)
            guess == LangGuess.UNCERTAIN && !oneWord && languages.hintsOtherLanguage(evidence, english) -> notes += RuleNote.LANGUAGE_UNCERTAIN
        }
        if (!english && languages.unaccentedVietnamese(text)) notes += RuleNote.NO_DIACRITICS // R07
        return InputDecision.Translate(text, notes, guess)
    }

    /**
     * R12 then R08 on the translation [output] of [source] (both as given to / returned by the model). Applied to
     * every translation, typed or spoken: it only rejects structurally broken output and flags lost terms.
     */
    fun checkOutput(source: String, output: String, direction: Direction): OutputCheck {
        if (output.isBlank()) return OutputCheck.Invalid("empty_output", emptyList())
        val missing = literals.spans(source).filter { it.kind.invariant && !invariantPresent(it, output) }
        if (missing.isNotEmpty()) return OutputCheck.Invalid("lost_literal", missing)
        val english = direction.source == Lang.EN
        val lost = lexicon.find(source).map { it.term }.distinct().filter { !termPresent(it, output) }.toMutableList()
        // An English IT word inside a Vietnamese sentence should reach the English output (inflected or not): the policy's
        // own R08 case is "staging" turning into "stash". In EN→VI such a word may be translated, so it is not checked.
        if (!english) {
            lexicon.findContext(source).filter { !inflectedPresent(it, output) }.forEach { if (it !in lost) lost += it }
        }
        return OutputCheck.Valid(lost)
    }

    /**
     * URL, e-mail and path must appear exactly (a space breaks them); code may only gain or lose spaces ("f()" =
     * "f ()", which the model writes systematically); an HTTP status must be the same 3-digit number.
     */
    private fun invariantPresent(l: Literal, output: String): Boolean = when (l.kind) {
        LiteralKind.HTTP_STATUS -> Regex("(?<![\\p{N}])${Regex.escape(l.text)}(?![\\p{N}])").containsMatchIn(output)
        LiteralKind.CODE -> squash(output).contains(squash(l.text))
        // A host name may change case ("Google.com" → "google.com"); a space still breaks it.
        LiteralKind.URL, LiteralKind.EMAIL -> output.contains(l.text, ignoreCase = true)
        else -> output.contains(l.text)
    }

    private fun squash(s: String) = s.filterNot { it.isWhitespace() }

    /**
     * [term] (a lowercase context word or phrase) with an English ending of its last word, so inflections and the usual
     * compounds count (commit → committed, log → logged, merge → merging, web → website for "trang web"), but another
     * word does not ("log" is not "login").
     */
    private fun inflectedPresent(term: String, output: String): Boolean {
        val words = term.split(' ')
        val last = words.last()
        val forms = buildList {
            add(Regex.escape(last) + "(?:s|es|ed|d|ing|er|ers|'s|’s|site|sites|page|pages|hook|hooks|server|app|apps)?")
            add(Regex.escape(last + last.last()) + "(?:ed|ing|er|ers)")
            if (last.endsWith("e")) add(Regex.escape(last.dropLast(1)) + "(?:ing|ed|ion|er|ers)")
            if (last.endsWith("y")) add(Regex.escape(last.dropLast(1)) + "(?:ies|ied)")
        }
        val head = words.dropLast(1).joinToString("") { Regex.escape(it) + "[\\s-]+" }
        return Regex("(?<![\\p{L}\\p{N}_])$head(?:${forms.joinToString("|")})(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE).containsMatchIn(output)
    }

    /**
     * A lexicon term is kept when it appears case-insensitively on word boundaries; a space or hyphen may come and go
     * inside it ("WiFi" = "Wi-Fi", "CI/CD" = "CI / CD", "Node.js" = "Node. js", "React Native" = "React-Native").
     */
    private fun termPresent(term: String, output: String): Boolean {
        val pattern = buildString {
            append("(?<![\\p{L}\\p{N}_])")
            term.forEachIndexed { i, c ->
                if (i > 0) append(if (c in ".-/+#" || term[i - 1] in ".-/+#") "\\s?" else "[\\s-]?")
                if (c == ' ' || c == '-') append("[\\s-]?") else append(Regex.escape(c.toString()))
            }
            // Plural, possessive or verb endings ("APIs", "Docker's", "Dockerized") still keep the term.
            append("(?:s|es|'s|’s|ed|ing|ized|izing)?(?![\\p{L}\\p{N}_])")
        }
        return Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(output)
    }

    companion object {
        /** R02. */
        const val MAX_CHARS = 500

        /** R09: source ids per model call (below the 512 positions of Marian). */
        const val MAX_SOURCE_IDS = 500

        /** R05 "một từ": a single token of at most this many displayed characters. */
        const val SHORT_WORD_MAX = 40

        private val SPACES = Regex("\\s+")

        /**
         * R02 count: displayed characters (extended grapheme clusters: an emoji, "ế" or a flag is one) of the text
         * after R01. The input box counter uses the same function.
         */
        fun displayLength(normalized: String): Int {
            if (normalized.isEmpty()) return 0
            val it = BreakIterator.getCharacterInstance()
            it.setText(normalized)
            var n = 0
            while (it.next() != BreakIterator.DONE) n++
            return n
        }

        /** Counter of an input box: R01 then the R02 count. */
        fun typedLength(raw: String): Int = displayLength(MtInputNormalizer.normalize(raw))
    }
}
