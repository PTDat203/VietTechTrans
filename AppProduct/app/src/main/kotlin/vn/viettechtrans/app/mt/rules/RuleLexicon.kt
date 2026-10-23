package vn.viettechtrans.app.mt.rules

import java.text.Normalizer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import vn.viettechtrans.app.mt.Direction

/**
 * The reviewed runtime lexicon of the text-rule policy v1 (docs/contracts/mt_app_rule_policy_v1.md §7,
 * `assets/text/mt_rule_lexicon_v1.json`). Three groups that never overlap:
 *  - preserve_exact: acronyms (exact case, optional plural "s": APIs), technologies (any case: JavaScript =
 *    javascript), technologies_case_sensitive (only as listed: "React", because "react" is an ordinary word);
 *  - keep_with_context_warning: short technical words/phrases kept as typed with a warning when they are the
 *    whole input (R04);
 *  - short_dialogue_words: everyday words per direction that are still translated (R05).
 * The lexicon never changes a translation: it only decides when not to call the model (R03/R04) and which
 * terms the output check looks for (R08).
 */
class RuleLexicon(file: File) {
    @Serializable
    class File(
        @SerialName("schema_version") val schemaVersion: String,
        @SerialName("lexicon_version") val lexiconVersion: String,
        @SerialName("preserve_exact") val preserveExact: PreserveExact,
        @SerialName("keep_with_context_warning") val keepWithContextWarning: List<String>,
        @SerialName("short_dialogue_words") val shortDialogueWords: Map<String, List<String>>,
    )

    @Serializable
    class PreserveExact(
        val acronyms: List<String>,
        val technologies: List<String>,
        @SerialName("technologies_case_sensitive") val technologiesCaseSensitive: List<String> = emptyList(),
        val patterns: List<String> = emptyList(),
    )

    enum class TermKind { ACRONYM, TECHNOLOGY }

    /** A lexicon term found in a text; [start]..[end] (exclusive) as typed, [term] as listed. */
    class Match(val start: Int, val end: Int, val term: String, val kind: TermKind)

    val version: String = file.lexiconVersion
    val acronyms: List<String> = file.preserveExact.acronyms
    val technologies: List<String> = file.preserveExact.technologies
    val technologiesCaseSensitive: List<String> = file.preserveExact.technologiesCaseSensitive

    private val acronymSet = acronyms.toSet()
    private val techByLower = technologies.associateBy { it.lowercase() }
    private val techCaseSensitiveSet = technologiesCaseSensitive.toSet()
    private val contextSet = file.keepWithContextWarning.map(::key).toSet()
    private val dialogue: Map<String, Set<String>> = file.shortDialogueWords.mapValues { (_, v) -> v.map(::key).toSet() }

    /** Longest first, so "React Native" wins over "React" and "ASP.NET" over ".NET". */
    private val exactPattern: Regex? = alternation(acronyms.map { "${Regex.escape(it)}(?:s)?" } + technologiesCaseSensitive.map(Regex::escape))
    private val anyCasePattern: Regex? = alternation(technologies.map(Regex::escape), RegexOption.IGNORE_CASE)
    private val contextPattern: Regex? = alternation(
        file.keepWithContextWarning.map { t -> t.split(' ').joinToString("\\s+") { Regex.escape(it) } },
        RegexOption.IGNORE_CASE,
    )
    private val contextByKey = file.keepWithContextWarning.associateBy(::key)

    init {
        val groups = listOf(
            "acronyms" to acronyms, "technologies" to technologies, "technologies_case_sensitive" to technologiesCaseSensitive,
            "keep_with_context_warning" to file.keepWithContextWarning,
        ) + file.shortDialogueWords.map { (d, v) -> "short_dialogue_words.$d" to v }
        val seen = HashMap<String, String>()
        for ((group, terms) in groups) {
            for (t in terms) {
                require(t.isNotBlank() && t == t.trim()) { "Lexicon: blank or untrimmed term in $group" }
                // Dialogue words may repeat across directions ("ok"); everything else must be in exactly one group.
                val k = (if (group.startsWith("short_dialogue_words")) "$group:" else "") + key(t)
                seen.put(k, group)?.let { other -> error("Lexicon: \"$t\" is in both $other and $group") }
            }
        }
    }

    /** The whole [text] (already trimmed of simple punctuation) is one lexicon term. */
    fun wholeTerm(text: String): Match? {
        val t = text.trim()
        if (t.isEmpty()) return null
        if (t in acronymSet || (t.endsWith("s") && t.dropLast(1) in acronymSet)) return Match(0, t.length, t.removeSuffix("s").takeIf { it in acronymSet } ?: t, TermKind.ACRONYM)
        if (t in techCaseSensitiveSet) return Match(0, t.length, t, TermKind.TECHNOLOGY)
        techByLower[t.lowercase()]?.let { return Match(0, t.length, it, TermKind.TECHNOLOGY) }
        return null
    }

    fun isContextTerm(text: String): Boolean = key(text) in contextSet

    fun isDialogueWord(text: String, direction: Direction): Boolean = key(text) in dialogue[direction.id].orEmpty()

    /**
     * Lexicon terms in [text], on word boundaries, longest first and not overlapping. A case-sensitive technology is
     * found only with its listed capitalization ("React", not "react"), also at the start of an English sentence, where
     * the model most often turns it into the ordinary word.
     */
    fun find(text: String): List<Match> {
        val out = ArrayList<Match>()
        exactPattern?.findAll(text)?.forEach { m ->
            val typed = m.value
            val acronym = typed in acronymSet || typed.dropLast(1) in acronymSet && typed.endsWith("s")
            out += Match(m.range.first, m.range.last + 1, if (acronym) typed.removeSuffix("s").takeIf { it in acronymSet } ?: typed else typed,
                if (acronym) TermKind.ACRONYM else TermKind.TECHNOLOGY)
        }
        anyCasePattern?.findAll(text)?.forEach { m ->
            val start = m.range.first
            val end = m.range.last + 1
            if (out.none { it.start < end && start < it.end }) out += Match(start, end, techByLower.getValue(m.value.lowercase()), TermKind.TECHNOLOGY)
        }
        return out.sortedBy { it.start }
    }

    /** keep_with_context_warning words and phrases inside [text] (as listed, longest first, not overlapping). */
    fun findContext(text: String): List<String> =
        contextPattern?.findAll(text)?.mapNotNull { contextByKey[key(it.value)] }?.distinct()?.toList().orEmpty()

    companion object {
        const val ASSET = "text/mt_rule_lexicon_v1.json"

        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): RuleLexicon = RuleLexicon(json.decodeFromString(File.serializer(), text))

        /** Comparison key of R04/R05: NFC, lowercase, single spaces, simple punctuation trimmed. */
        fun key(text: String): String =
            Normalizer.normalize(SimplePunctuation.trim(text), Normalizer.Form.NFC).lowercase().split(SPACES).filter { it.isNotEmpty() }.joinToString(" ")

        private val SPACES = Regex("\\s+")

        /** No letter, digit or "_" right before or after (Unicode letters, so Vietnamese words are whole words). */
        private fun alternation(parts: List<String>, vararg options: RegexOption): Regex? {
            if (parts.isEmpty()) return null
            val ordered = parts.sortedWith(compareBy<String>({ -it.length }, { it }))
            return Regex("(?<![\\p{L}\\p{N}_])(?:${ordered.joinToString("|")})(?![\\p{L}\\p{N}_])", options.toSet())
        }
    }
}

/** Simple punctuation around a whole input that R03/R04/R05 ignore ("API?", "“JavaScript”", "Cảm ơn!"). */
object SimplePunctuation {
    private const val EDGE = ".,!?;:…\"'“”‘’«»()[]{}"

    /** Strips [EDGE] characters from both ends, but keeps a leading "." or "./" that starts a name (.NET, ./run.sh, .gitignore). */
    fun trim(text: String): String {
        var start = 0
        var end = text.length
        while (start < end && (text[start].isWhitespace() || text[start] in EDGE && !startsName(text, start))) start++
        while (end > start && (text[end - 1].isWhitespace() || text[end - 1] in EDGE && !endsName(text, start, end))) end--
        return text.substring(start, end)
    }

    private fun startsName(text: String, i: Int): Boolean =
        text[i] == '.' && i + 1 < text.length && (text[i + 1].isLetterOrDigit() || text[i + 1] == '/' || text[i + 1] == '.')

    /** "C++." keeps "++"; a closing ")" of "fetch()" is kept when its "(" is inside. */
    private fun endsName(text: String, start: Int, end: Int): Boolean {
        val c = text[end - 1]
        if (c == ')') return text.substring(start, end - 1).count { it == '(' } > text.substring(start, end - 1).count { it == ')' }
        if (c == ']') return text.substring(start, end - 1).count { it == '[' } > text.substring(start, end - 1).count { it == ']' }
        if (c == '}') return text.substring(start, end - 1).count { it == '{' } > text.substring(start, end - 1).count { it == '}' }
        if (c == '"' || c == '\'') return text.substring(start, end - 1).count { it == c } % 2 == 1
        return false
    }
}
