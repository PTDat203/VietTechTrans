package vn.viettechtrans.app.mt.rules

/**
 * Kinds of technical literal (policy R03 list, R08/R12 "literal bất biến"). [invariant]: the translation must
 * contain it unchanged, otherwise the output is structurally invalid (R12).
 */
enum class LiteralKind(val invariant: Boolean) {
    URL(true),
    EMAIL(true),
    CODE(true),
    HTTP_STATUS(true),
    /** Absolute or explicit paths: /api/users, ~/x, ./run.sh, C:\Windows. */
    PATH(true),
    /** a/b/c: also a slash list ("download/install/remove", "4080/4070Ti/4070"), so not invariant. */
    RELATIVE_PATH(false),
    FILE_NAME(false),
    COMMAND(false),
    IDENTIFIER(false),
    VERSION(false),
    NUMBER(false),
    ACRONYM(false),
    TECHNOLOGY(false),
}

/** A literal in a text: [start]..[end] (exclusive); [text] is what R12 looks for in the output. */
data class Literal(val start: Int, val end: Int, val kind: LiteralKind, val text: String)

/**
 * Finds technical literals in typed text (policy R03, R06, R07, R09, R12). Pure Kotlin, no Android.
 * [spans] lists literals inside any text (non-overlapping, earlier kinds win); [whole] says whether the entire
 * input is one literal (R03: then the model is not called).
 */
class TechLiterals(private val lexicon: RuleLexicon) {

    fun spans(text: String): List<Literal> {
        val taken = ArrayList<Literal>()
        fun add(start: Int, end: Int, kind: LiteralKind) {
            if (end <= start || taken.any { it.start < end && start < it.end }) return
            taken += Literal(start, end, kind, text.substring(start, end))
        }
        fun addTrimmed(m: MatchResult, kind: LiteralKind) = add(m.range.first, m.range.first + trimEnd(m.value).length, kind)

        BACKTICK.findAll(text).forEach { add(it.range.first, it.range.last + 1, LiteralKind.CODE) }
        HTML_TAG.findAll(text).forEach { add(it.range.first, it.range.last + 1, LiteralKind.CODE) }
        // A call is code as a whole, even with a URL or path argument: fetch('/api/users').
        CALL.findAll(text).forEach { m -> if (callOk(m.value)) add(m.range.first, m.range.last + 1, LiteralKind.CODE) }
        EMAIL.findAll(text).forEach { add(it.range.first, it.range.last + 1, LiteralKind.EMAIL) }
        for (re in URLS) re.findAll(text).forEach { addTrimmed(it, LiteralKind.URL) }
        IPV4.findAll(text).forEach { m -> if (ipOk(text, m)) addTrimmed(m, LiteralKind.URL) }
        // Dates before paths: "25/8/2023" is a date, translated as "August 25, 2023".
        DATE.findAll(text).forEach { add(it.range.first, it.range.last + 1, LiteralKind.NUMBER) }
        for (re in PATHS) re.findAll(text).forEach { addTrimmed(it, LiteralKind.PATH) }
        RELATIVE_PATH.findAll(text).forEach { m -> if (m.value.any { it.isLetter() }) addTrimmed(m, LiteralKind.RELATIVE_PATH) }
        FILE_NAME.findAll(text).forEach { m -> if (fileNameOk(m.value)) add(m.range.first, m.range.last + 1, LiteralKind.FILE_NAME) }
        SPECIAL_FILES.findAll(text).forEach { add(it.range.first, it.range.last + 1, LiteralKind.FILE_NAME) }
        for (re in listOf(OPERATOR_TOKEN, ASSIGNMENT)) re.findAll(text).forEach { m ->
            val v = trimEnd(m.value)
            val value = v.substringAfter('=', "")
            val emoticon = re === ASSIGNMENT && (value.isEmpty() || value.length == 1 && value[0].isLetter() || value[0] in ")(]")
            if (v.isNotEmpty() && v.all { it.code < 128 } && !emoticon && !PLUS_NAME.matches(v) && lexicon.wholeTerm(v) == null) {
                add(m.range.first, m.range.first + v.length, LiteralKind.CODE)
            }
        }
        LONG_FLAG.findAll(text).forEach { addTrimmed(it, LiteralKind.CODE) }
        SNAKE.findAll(text).forEach { add(it.range.first, it.range.last + 1, LiteralKind.CODE) }
        CAMEL.findAll(text).forEach { m -> if (camelOk(m.value)) add(m.range.first, m.range.last + 1, LiteralKind.CODE) }
        DOTTED.findAll(text).forEach { add(it.range.first, it.range.last + 1, LiteralKind.CODE) }
        for (re in HTTP_STATUS) re.findAll(text).forEach { m ->
            val g = m.groups[1] ?: return@forEach
            add(g.range.first, g.range.last + 1, LiteralKind.HTTP_STATUS)
        }
        VERSION.findAll(text).forEach { add(it.range.first, it.range.last + 1, LiteralKind.VERSION) }
        for (re in NUMBERS) re.findAll(text).forEach { add(it.range.first, it.range.last + 1, LiteralKind.NUMBER) }
        for (m in lexicon.find(text)) {
            add(m.start, m.end, if (m.kind == RuleLexicon.TermKind.ACRONYM) LiteralKind.ACRONYM else LiteralKind.TECHNOLOGY)
        }
        return taken.sortedBy { it.start }
    }

    /**
     * R03: the entire input (simple punctuation around it allowed) is one technical literal, a technology or acronym
     * name, a command line, a code statement, an HTTP status line, or a run of such tokens ("Python 3.12", "HTTP 404").
     * Null for anything else, in particular for any sentence that merely contains a literal ("API returns timeout").
     */
    fun whole(text: String): LiteralKind? {
        val raw = text.trim()
        if (raw.isEmpty()) return null
        // Chat with an emoticon ("haha =))", "ok nha :D") is never a literal, also once its brackets are trimmed.
        if (TOKEN.findAll(raw).any { EMOTICON.matches(it.value) }) return null
        wholeKind(raw)?.let { return it }
        val t = SimplePunctuation.trim(raw)
        if (t.isEmpty() || t == raw) return null
        return wholeKind(t)
    }

    private fun wholeKind(t: String): LiteralKind? {
        if (BACKTICK.matches(t)) return LiteralKind.CODE
        lexicon.wholeTerm(t)?.let { return kindOf(it) }
        if (HTTP_STATUS_LINE.matches(t)) return LiteralKind.HTTP_STATUS
        val tokens = TOKEN.findAll(t).toList()
        if (tokens.size == 1) return token(t)
        command(t, tokens.map { it.value })?.let { return it }
        if (codeStatement(t, tokens)) return LiteralKind.CODE
        // A run of literal tokens only: "Python 3.12", "HTTP 404", "iOS 17", "API, SDK".
        val kinds = tokens.map { tok -> SimplePunctuation.trim(tok.value).let { if (it.isEmpty()) null else token(it) } }
        if (kinds.all { it != null }) return kinds.first()
        return null
    }

    /** One whitespace-free token that is entirely a literal. */
    private fun token(t: String): LiteralKind? {
        lexicon.wholeTerm(t)?.let { return kindOf(it) }
        val full = spans(t).singleOrNull()?.takeIf { it.start == 0 && it.end == t.length }
        if (full != null) {
            // "download/install/remove", "Hình ảnh/Video/Âm thanh": a list of words, not a path to keep.
            if (full.kind == LiteralKind.RELATIVE_PATH && t.split('/').filter { it.isNotEmpty() }.all { seg -> seg.all { it.isLetter() } }) return null
            return full.kind
        }
        if (IDENTIFIER_WHOLE.any { it.matches(t) } && !wordsJoinedByDot(t)) return LiteralKind.IDENTIFIER
        if (SHORT_FLAG.matches(t)) return LiteralKind.CODE
        return null
    }

    /** "Hello.World", "ok.thanks": two words with a missing space, not an identifier like os.path or console.log. */
    private fun wordsJoinedByDot(t: String): Boolean {
        if (t.count { it == '.' } != 1 || t.any { it.isDigit() || it == '_' }) return false
        val parts = t.split('.')
        return parts.any { WordLists.isWord(it.lowercase()) } || parts.all { TITLE_WORD.matches(it) }
    }

    private fun kindOf(m: RuleLexicon.Match) = if (m.kind == RuleLexicon.TermKind.ACRONYM) LiteralKind.ACRONYM else LiteralKind.TECHNOLOGY

    /** A command line: a known program first ("git", "npm", "./gradlew"), then arguments, no prose word outside quotes. */
    private fun command(t: String, tokens: List<String>): LiteralKind? {
        var first = tokens[0]
        var rest = tokens.drop(1)
        if (first == "sudo" && rest.isNotEmpty()) {
            first = rest[0]
            rest = rest.drop(1)
        }
        // Programs are typed in lowercase: "SFTP Login" is a UI label, "sftp user@host" a command.
        val program = first
        val script = first.startsWith("./") || first.startsWith("../") || SCRIPT.matches(first)
        if (program !in CLI && program !in CLI_AMBIGUOUS && !script) return null
        if (rest.isEmpty()) return null
        // git commit -m "sửa lỗi đăng nhập": the quoted message may be prose.
        val unquoted = QUOTED.replace(t, " ").split(SPACES).filter { it.isNotEmpty() }.drop(if (tokens[0] == "sudo") 2 else 1)
        // "kubectl get pods", "git add .": subcommands are words too, but not prose.
        if (unquoted.any { w -> val b = w.lowercase().trim(*TRIM); (proseWord(w) || b in WordLists.ENGLISH) && b !in SUBCOMMANDS }) return null
        val strongArg = rest.any { a ->
            a.startsWith("-") || a.contains('/') || a.contains('\\') || a == "." || a == ".." || a.contains('=') ||
                a.startsWith("\"") || a.startsWith("'") || a.contains('@') || FILE_NAME.matches(a) || SPECIAL_FILES.matches(a) ||
                TASK.matches(a) // rake db:migrate
        } || program in TOOLS && rest.first().lowercase() in PROGRAM_VERBS // brew install node, cargo build, ng serve
        if (program in CLI_AMBIGUOUS && !strongArg) return null
        return LiteralKind.COMMAND
    }

    /**
     * Code typed alone: "await fetch('/api/users')", "x => x * 2", "SELECT * FROM users", "<div class='a'>". It needs real
     * code syntax (a call, backticks, a tag, an assignment or operator, braces, a final ";"): a title that only contains a
     * camelCase name ("vCard Import Failed", "Verizon myPlan adds…") is prose, and so is any Titlecase word.
     */
    private fun codeStatement(t: String, tokens: List<MatchResult>): Boolean {
        val words = QUOTED.replace(t, " ").split(SPACES).filter { it.isNotEmpty() }
        if (words.any { proseWord(it) && it.lowercase().trim(*TRIM) !in CODE_KEYWORDS }) return false
        if (words.any { EMOTICON.matches(it) }) return false
        if (SQL.containsMatchIn(t)) return true
        val s = spans(t)
        var strong = s.any { it.kind == LiteralKind.CODE && STRONG_CODE.containsMatchIn(it.text) } ||
            t.trimEnd().endsWith(";") || t.contains('{') && t.contains('}')
        var plainWords = 0
        for (tok in tokens) {
            val bare = tok.value.trim(*TRIM)
            val inSpan = s.any { it.start <= tok.range.first && tok.range.last < it.end }
            when {
                bare.isEmpty() || inSpan || bare.lowercase() in CODE_KEYWORDS -> Unit
                STRONG_OPERATOR.matches(bare) -> strong = true
                OPERATORS.matches(bare) -> Unit
                TITLE_WORD.matches(bare) -> return false
                token(bare) != null -> Unit
                IDENT.matches(bare) -> plainWords++
                else -> return false
            }
        }
        // A statement does not end with a dangling operator ("haha =").
        if (OPERATORS.matches(tokens.last().value.trim(*TRIM)) && !t.trimEnd().endsWith(";")) return false
        return strong && plainWords <= 3
    }

    private fun proseWord(word: String): Boolean {
        val w = word.trim(*TRIM)
        if (w.isEmpty()) return false
        if (w.any { it.isLetter() && it.code > 0x7F }) return true // a Vietnamese (or other accented) word
        return w.lowercase() in PROSE
    }

    /** "a.m" (10 a.m.) is not a file; "main.c", "stdio.h" are. */
    private fun fileNameOk(value: String): Boolean {
        val dot = value.lastIndexOf('.')
        return dot <= 0 || value.length - dot - 1 > 1 || dot >= 2
    }

    /**
     * "file(s)", "user(s)", the virus "A(H5N1)" and UI text with placeholders "Remove(%s provides %s)" are prose;
     * "foo()", "fetch('/api')", "f(x)", "max(a, b)" are code.
     */
    private fun callOk(value: String): Boolean {
        val open = value.indexOf('(')
        val name = value.substring(0, open)
        val args = value.substring(open + 1, value.length - 1)
        if (name.length == 1 && name[0].isUpperCase()) return false
        if (args.lowercase() in setOf("s", "es", "tm", "r", "c")) return false
        if (QUOTED.replace(args, "").any { it.isLetter() && it.code > 127 }) return false
        val proseArgs = args.contains(' ') && args.none { it in "'\",\"`" } && args.split(SPACES).any { w -> w.length >= 4 && w.all { it.isLetter() } }
        return !proseArgs
    }

    /** An IPv4 address, not a 4-part version ("OxygenOS 14.0.0.304"): octets ≤ 255 and a port, a path, a private range or an IP word before it. */
    private fun ipOk(text: String, m: MatchResult): Boolean {
        val core = m.value.substringBefore(':').substringBefore('/')
        val octets = core.split('.').map { it.toIntOrNull() ?: return false }
        if (octets.any { it > 255 }) return false
        if (m.value.length > core.length) return true
        val o = octets
        if (o[0] == 10 || o[0] == 127 || o[0] == 192 && o[1] == 168 || o[0] == 172 && o[1] in 16..31 || o[0] == 0) return true
        return IP_WORDS.containsMatchIn(text.substring(maxOf(0, m.range.first - 24), m.range.first))
    }

    /** "getUserById", "useState"; not short units (kWh), brands written like iPhone/eBay, or a listed technology (jQuery). */
    private fun camelOk(value: String): Boolean = value.length >= 5 && lexicon.wholeTerm(value) == null && !APPLE_STYLE.matches(value)

    companion object {
        /** Word characters of any script: a Vietnamese letter next to ASCII letters blocks a match ("hàm(x)" is not "m(x)"). */
        private const val W = "\\p{L}\\p{N}_"

        /** ASCII word characters, written out: Android's regex engine (ICU) makes the w class match Vietnamese letters too, the JVM does not. */
        private const val AW = "A-Za-z0-9_"
        private val SPACES = Regex("\\s+")
        private val TOKEN = Regex("\\S+")
        private val TRIM = ".,!?;:…\"'“”‘’()[]{}".toCharArray()

        private const val URL_CHAR = "[^\\s<>\"'`]"
        private val BACKTICK = Regex("```[\\s\\S]+?```|`[^`\\n]+`")
        private val HTML_TAG = Regex("</?[A-Za-z][A-Za-z0-9-]*(?:\\s+[^<>\\n]*)?/?>")
        private val EMAIL = Regex(
            "(?<![$W.+%-])[A-Za-z0-9][A-Za-z0-9._%+-]*@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?" +
                "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)*\\.[A-Za-z]{2,24}(?![$W-])",
        )
        private const val TLD = "com|net|org|io|dev|app|edu|gov|info|tech|cloud|xyz|site|online|store|uk|us|jp|cn|kr|fr|ru|au|biz"
        private val URLS = listOf(
            Regex("(?i)(?<![$W.])(?:https?|ftps?|sftp|wss?|file)://$URL_CHAR+"),
            Regex("(?i)(?<![$W.])www\\.$URL_CHAR+"),
            Regex("(?<![$W.])localhost(?::\\d{1,5})?(?:/$URL_CHAR*)?(?![$W])"),
            Regex("(?<![$W.@/-])(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+(?:$TLD)(?::\\d{1,5})?(?:/$URL_CHAR*)?(?![$W-])"),
            // .vn: "chinhphu.gov.vn", "abc.vn/tin" (not "nha.vn" typed for "nhà. Vn…").
            Regex("(?<![$W.@/-])(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.){2,}vn(?::\\d{1,5})?(?:/$URL_CHAR*)?(?![$W-])|(?<![$W.@/-])[A-Za-z0-9-]{2,}\\.vn/$URL_CHAR*"),
        )
        private val IPV4 = Regex("(?<![$W.])\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d{1,5})?(?:/$URL_CHAR*)?(?![$W.])")
        private val IP_WORDS = Regex("(?iu)(?:\\bip|address|địa\\s+chỉ|host|server|máy\\s+chủ|ping|dns|gateway)\\W*$")
        private val DATE = Regex("(?<![$W.,:/-])\\d{1,4}[-/]\\d{1,2}[-/]\\d{1,4}(?![$W/])")
        /**
         * Absolute paths need two segments ("/api/users", not a lone "/word" of prose) and may not run into a letter of
         * any script ("và /hoặc" is not "/ho"); home and relative ones ("~/x", "./run.sh") need one.
         */
        private val PATHS = listOf(
            Regex("(?<![$W./~:\\\\-])/[$AW.@%+~-]+(?:/[$AW.@%+~-]+)+/?(?![$W])"),
            Regex("(?<![$W./~-])(?:~|\\.\\.?)(?:/[$AW.@%+~-]+)+/?(?![$W])"),
            Regex("(?<![$W])[A-Za-z]:\\\\(?:[^\\\\/:*?\"<>|\\s]+\\\\?)*"),
            Regex("(?<![$W])[A-Za-z]:/(?:[^\\\\/:*?\"<>|\\s]+/?)*"),
        )
        private val RELATIVE_PATH = Regex("(?<![$W./~:@-])[$AW.-]+(?:/[$AW.-]+){2,}/?(?![$W])")
        private const val EXT = "html|htm|css|scss|sass|less|js|mjs|cjs|jsx|ts|tsx|vue|svelte|json|jsonl|xml|yml|yaml|toml|ini|cfg|conf|env|properties|" +
            "md|txt|csv|tsv|log|py|ipynb|java|kt|kts|gradle|groovy|scala|c|h|cpp|cc|cxx|hpp|cs|go|rs|rb|php|swift|m|mm|dart|lua|pl|r|" +
            "sh|bash|zsh|bat|cmd|ps1|sql|db|sqlite|proto|graphql|tf|plist|apk|aab|ipa|exe|msi|dll|so|dylib|jar|war|class|zip|rar|7z|tar|gz|tgz|" +
            "bz2|xz|iso|dmg|deb|rpm|png|jpg|jpeg|gif|bmp|svg|webp|ico|pdf|doc|docx|xls|xlsx|ppt|pptx|odt|mp3|mp4|wav|flac|ogg|avi|mov|mkv|" +
            "webm|ttf|otf|woff|woff2|onnx|pt|pth|h5|pkl|safetensors|bin|lock|whl"
        private val FILE_NAME = Regex("(?<![$W./\\\\@-])\\.?[$AW-]+(?:\\.[$AW-]+)*\\.(?i:$EXT)(?![$W-])")
        private val SPECIAL_FILES = Regex(
            "(?<![$W./\\\\-])(?:Dockerfile|Makefile|Jenkinsfile|Procfile|Gemfile|Podfile|Vagrantfile|Rakefile|Brewfile|Caddyfile|" +
                "\\.(?:gitignore|gitattributes|gitmodules|env|envrc|bashrc|zshrc|npmrc|nvmrc|editorconfig|dockerignore|prettierrc|eslintrc|babelrc|htaccess))(?![$W-])",
        )
        private val CALL = Regex("(?<![$W.$])[A-Za-z_$][${AW}$]*(?:\\.[A-Za-z_$][${AW}$]*)*\\((?:[^()\\n]|\\([^()\\n]*\\)){0,80}\\)")
        /** Not "->": prose writes menu paths with it ("Settings->General", "Cài đặt->Chung"). */
        private const val OPS = "===|!==|==|!=|=>|::|&&|\\|\\||\\+\\+|\\+=|-=|\\*=|/=|<=|>=|<<|>>"
        /** A whole whitespace-free token with an operator next to an identifier character: a==b, x=>x*2, std::vector. */
        private val OPERATOR_TOKEN = Regex("(?<!\\S)(?=\\S*(?:[$AW)\\]'\"](?:$OPS)|(?:$OPS)[$AW(\\['\"]))\\S+")
        private val PLUS_NAME = Regex("[A-Za-z]\\+\\+\\d*") // C++, C++17 are names
        private val ASSIGNMENT = Regex("(?<![$W-])[A-Za-z_][$AW.-]*=[^\\s=]\\S*")
        private val LONG_FLAG = Regex("(?<![$W-])--[A-Za-z][$AW-]*(?:=\\S+)?")
        private val SHORT_FLAG = Regex("-[A-Za-z]{1,3}")
        private val SNAKE = Regex("(?<![$W.$-])_*[A-Za-z][A-Za-z0-9]*(?:_+[A-Za-z0-9]+)+_*(?![$W-])")
        private val CAMEL = Regex("(?<![$W.$-])[a-z][a-z0-9]*(?:[A-Z][a-z0-9]+)+[A-Z]?(?![$W-])")
        private val DOTTED = Regex("(?<![$W.$])[A-Za-z_][${AW}$]+(?:\\.[A-Za-z_][${AW}$]+){2,}(?![$W(])")
        private val APPLE_STYLE = Regex("[ie][A-Z][A-Za-z]*")
        private val IDENT = Regex("[A-Za-z_$][${AW}$]*")
        private const val ERRNO = "ENOENT|EACCES|EPERM|EEXIST|ENOTDIR|EISDIR|EINVAL|EMFILE|ENOSPC|EPIPE|ECONNREFUSED|ECONNRESET|ECONNABORTED|" +
            "ETIMEDOUT|EADDRINUSE|EADDRNOTAVAIL|EHOSTUNREACH|ENETUNREACH|ENOTFOUND|EAI_AGAIN|EBUSY|EAGAIN|ENOMEM|EROFS|ENOTEMPTY|ENOTSUP|EPROTO"
        private val IDENTIFIER_WHOLE = listOf(
            Regex("[A-Z][a-z0-9]+(?:[A-Z][a-z0-9]*)+"), // PascalCase with two or more humps: NullPointerException, HttpClient
            Regex("[A-Za-z]+(?:Exception|Error)"),
            Regex("(?:$ERRNO)"),
            Regex("[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+"), // constants: MAX_RETRIES
            Regex("(?=[^.]*[a-z])[A-Za-z_][$AW]+\\.(?=[$AW]*[a-z])[A-Za-z_][$AW]+"), // os.path, user.name (not TP.HCM)
            Regex("[a-z][a-z0-9]*(?:[A-Z][a-z0-9]+)+[A-Z]?"), // camelCase (iPhone alone is kept too)
        )
        private val VERSION = Regex("(?<![$W.])[vV]?\\d+(?:\\.\\d+){1,3}(?:[-+][0-9A-Za-z]+(?:\\.[0-9A-Za-z]+)*)?(?![$W])|(?<![$W.])[vV]\\d+(?![$W.])")
        private val NUMBERS = listOf(
            Regex("(?<![$W.,:])\\d{1,2}:\\d{2}(?::\\d{2})?(?![$W:])"),
            Regex("(?<![$W.,])[+-]?\\d+(?:[.,]\\d+)*(?:%|‰)?(?![$W])"),
        )
        private const val REASON = "Continue|Switching Protocols|OK|Created|Accepted|No Content|Partial Content|Moved Permanently|Found|See Other|" +
            "Not Modified|Temporary Redirect|Permanent Redirect|Bad Request|Unauthorized|Payment Required|Forbidden|Not Found|" +
            "Method Not Allowed|Not Acceptable|Request Timeout|Conflict|Gone|Payload Too Large|URI Too Long|Unsupported Media Type|" +
            "Too Many Requests|Internal Server Error|Not Implemented|Bad Gateway|Service Unavailable|Gateway Timeout|HTTP Version Not Supported"
        private val HTTP_STATUS_LINE = Regex("(?i:HTTP(?:/\\d(?:\\.\\d)?)?\\s+)?[1-5]\\d{2}(?:\\s+(?i:$REASON))?")
        /** Group 1 = the status code, found with "HTTP", a reason phrase, or a word such as "lỗi", "error", "returns", "timeout". */
        private val HTTP_STATUS = listOf(
            Regex("(?i)(?<![$W])HTTP(?:/\\d(?:\\.\\d)?)?\\s*([1-5]\\d{2})(?![\\d.,]*\\d)(?![$W])"),
            Regex("(?<![$W.,])([1-5]\\d{2})\\s+(?i:$REASON)(?![$W])"),
            Regex("(?iu)(?<![$W.,])([1-5]\\d{2}|[1-5]xx)\\s*(?:errors?|lỗi|response|status)(?![$W])"),
            Regex(
                "(?iu)(?<![$W])(?:status(?:\\s+code)?|code|error|errors|returns?|returned|got|response|timeout|timed\\s+out|lỗi|mã(?:\\s+lỗi)?|trả\\s+về|báo)" +
                    "\\s*(?:code\\s*|lỗi\\s*|là\\s*|:\\s*)?(?<![$W.,])([1-5]\\d{2}|[1-5]xx)(?![\\d.,]*\\d)(?![$W])",
            ),
        )
        private val QUOTED = Regex("\"[^\"]*\"|'[^']*'|`[^`]*`")
        private val SQL = Regex(
            "^(?:SELECT\\s.+\\sFROM\\s|INSERT\\s+INTO\\s|UPDATE\\s+\\S+\\s+SET\\s|DELETE\\s+FROM\\s|CREATE\\s+(?:TABLE|INDEX|DATABASE)\\s|" +
                "ALTER\\s+TABLE\\s|DROP\\s+(?:TABLE|DATABASE)\\s)",
        )
        private val OPERATORS = Regex("[=+\\-*/%<>!&|^~?:;,.]+")
        /** A code span that is real syntax (call, backticks, tag, assignment, operator, flag), not just a camelCase name. */
        private val STRONG_CODE = Regex("[(`<=]|^--|$OPS")
        private val TITLE_WORD = Regex("[A-Z][a-z]+")
        /** :) :D ;) =)) :P xD ^^ <3 (not "XP", "x86"). */
        private val EMOTICON = Regex("[:;=8][-^']?[)(DPpOo3\\]\\[/|]+|[xX]D+|\\^\\^|<3|T_T|=+[)(]+")
        /** Operators that only code uses between spaces ("x => x * 2"); not "->", which prose uses for menus ("Settings -> General"). */
        private val STRONG_OPERATOR = Regex("=>|==|===|!=|!==|:=|=|\\+=|-=|\\*=|/=|;|&&|\\|\\|")
        private val SCRIPT = Regex("[$AW.-]+\\.(?:sh|py|bat|cmd|ps1|js|rb|pl)")

        /** Programs that start a command line; none of them is an English or Vietnamese word. */
        private val CLI = (
            "git npm npx pnpm pip pip3 pipx conda python3 py deno javac kotlinc gradle gradlew mvn docker docker-compose podman kubectl " +
                "helm k9s minikube ansible aws gcloud az firebase vercel netlify heroku " +
                "cd ls ll dir cp mv rm rmdir mkdir chmod chown chgrp ln pwd grep egrep rg sed awk wget ssh scp rsync sftp " +
                "unzip gzip gunzip sudo apt-get dpkg dnf choco winget flatpak systemctl journalctl " +
                "adb fastboot sdkmanager avdmanager xcodebuild xcrun rustc rustup dotnet nuget " +
                "gofmt gcc g++ clang clang++ cmake nvm volta jupyter pytest tsc eslint " +
                "webpack vite ipconfig ifconfig netstat nslookup traceroute tracert nmap telnet ps htop killall " +
                "uname whoami unset powershell pwsh zsh regedit taskkill tasklist"
            ).split(' ').toSet()

        /**
         * Programs whose name is also an ordinary English or unaccented Vietnamese word ("bun" bún, "su", "snap", "brew", "cargo",
         * "poetry") or a language name: a command only with a flag, path, file, "." or quoted argument.
         */
        private val CLI_AMBIGUOUS = (
            "code make cat echo find sort head tail top man date time test open start set type help more less which where watch ping " +
                "service run serve build go free source export net kill touch df du emulator python node java ruby perl php dart flutter " +
                "rails ionic expo ant yarn bun poetry kind terraform vagrant curl tar zip su apt yum pacman brew scoop snap pod cargo " +
                "composer gem bundle rake ninja jest mocha prettier ng bash sh cmd"
            ).split(' ').toSet()

        /**
         * Ambiguous programs that take a verb subcommand, for which that verb is enough ("brew install node", "cargo build");
         * not "test run", "build test", nor "python version" or "java update", which are prose.
         */
        private val TOOLS = (
            "yarn bun poetry kind terraform vagrant apt yum brew scoop snap pod cargo composer gem bundle rake ninja ng dart flutter " +
                "rails ionic expo ant go make"
            ).split(' ').toSet()

        /** First arguments that make an ambiguous tool a command ("brew install node", "bun run dev", "ng serve"). */
        private val PROGRAM_VERBS = (
            "install uninstall i ci add remove rm run build test init new create serve start update upgrade list ls search info exec " +
                "publish generate g login logout push pull clone fetch commit status deploy dev watch lint format version help"
            ).split(' ').toSet()
        private val TASK = Regex("[a-z][a-z0-9_-]*:[a-z0-9_:-]+")

        /** Subcommands and arguments that are words but not prose ("kubectl get pods", "git add ."). */
        private val SUBCOMMANDS = (
            "get set run list show add remove install uninstall start stop restart status describe apply delete create logs log build " +
                "push pull commit checkout init update upgrade exec config version help login logout test clone fetch merge rebase diff " +
                "reset tag branch switch restore stash info search link publish serve dev watch lint format deploy rollout scale expose " +
                "edit patch label up down images volume network inspect prune system context use all"
            ).split(' ').toSet()

        /** Words that make a line prose rather than code or a command (any accented letter does too). */
        private val PROSE = (
            "a an the is are was were be been am to of and or but in on at for with from by as this that these those it its i you we they " +
                "he she my your our their me us them please how what why when where which who can could should would will shall may might must " +
                "do does did not no yes if then so just also very there here have has had get got about into than toi ban minh la va cua de cho " +
                "khong co duoc nay khi thi voi trong mot cac nhung se da dang roi nhe oi nha vay sao gi nao"
            ).split(' ').toSet()

        /** Keywords that may appear in a code line typed alone. */
        private val CODE_KEYWORDS = (
            "if else elif for while do return import from as in is not and or def class function fn func const let var val new await async " +
                "try catch except finally throw raise public private protected static void int long float double bool boolean string char " +
                "null nil none true false this self select insert update delete where join into values set order group by limit create table " +
                "drop alter print println echo yield lambda with pass break continue switch case default package interface extends implements"
            ).split(' ').toSet()

        /** Trailing sentence punctuation and unbalanced closing brackets are not part of a URL, path or code token. */
        fun trimEnd(value: String): String {
            var v = value
            while (v.isNotEmpty()) {
                val c = v.last()
                v = when {
                    c in ".,;:!?'\"" -> v.dropLast(1)
                    c == ')' && v.count { it == '(' } < v.count { it == ')' } -> v.dropLast(1)
                    c == ']' && v.count { it == '[' } < v.count { it == ']' } -> v.dropLast(1)
                    c == '}' && v.count { it == '{' } < v.count { it == '}' } -> v.dropLast(1)
                    else -> return v
                }
            }
            return v
        }
    }
}
