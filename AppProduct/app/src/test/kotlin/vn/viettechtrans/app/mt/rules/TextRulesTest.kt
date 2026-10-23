package vn.viettechtrans.app.mt.rules

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.viettechtrans.app.mt.Direction

/** The mandatory test table of docs/contracts/mt_app_rule_policy_v1.md §8, on the shipped lexicon. */
class TextRulesTest {
    private val rules = TextRules(RuleLexicon.parse(File("src/main/assets/${RuleLexicon.ASSET}").readText()))
    private val vi = Direction.VI_TO_EN
    private val en = Direction.EN_TO_VI

    private fun decide(text: String, d: Direction) = rules.decide(text, d)

    private fun assertKeep(text: String, note: RuleNote, d: Direction = vi) {
        for (dir in listOf(d, d.reverse)) {
            val r = decide(text, dir)
            assertTrue("$text ($dir): $r", r is InputDecision.Keep && r.note == note)
        }
    }

    @Test
    fun r03LiteralsAreKeptWithoutTheModel() {
        for (t in listOf(
            "JavaScript", "Javascript", "API", "404", "https://example.com/a?b=1", "www.php.net", "dev@example.com", "`npm test`",
            "await fetch('/api/users')", "/api/users", "NullPointerException", "CI/CD", "Docker", "v1.2.3", "3.12", "index.html",
            "C:\\Windows\\System32", "git status", "npm install react", "HTTP 404", "404 Not Found", "API?", "“JavaScript”", "user_id",
            "getUserById", "ENOENT", "MAX_RETRIES", "Python 3.12", "x => x * 2", "SELECT * FROM users", "<div>", "--force",
            "kubectl get pods", "docker ps", "git add .", "os.path", "console.log",
            // Programs that are also ordinary words: with a flag, a file or a verb subcommand.
            "brew install node", "cargo build --release", "su -", "zip -r out.zip src", "ng serve", "yarn add react", "bun run dev",
            "kind create cluster", "rake db:migrate",
        )) assertKeep(t, RuleNote.KEPT_LITERAL)
    }

    @Test
    fun r03IsNotUsedForSentences() {
        for ((t, d) in listOf(
            "API returns timeout" to en, "Tôi đang học JavaScript" to vi, "Mở file index.html bằng Chrome" to vi, "go home" to en,
            "python developer" to en, "code review" to en, "react" to en, "Settings -> General" to en, "file(s)" to en,
            "Gọi hàm fetch() để lấy dữ liệu" to vi, "TP.HCM" to vi, "và/hoặc" to vi, "input/output" to en, "Select file from list" to en,
            "bun bo hue ngon" to vi, "brew coffee" to en, "snap photos" to en, "poetry book" to en, "download/install/remove" to en,
            "Hello.World" to en, "Settings->General" to en, "su dung app" to vi, "su dung mat khau" to vi, "bun cha" to vi,
            "kind regards" to en, "zip code" to en, "cargo ship" to en, "python version" to en, "java update" to en,
            "make new friends" to en,
        )) {
            val r = decide(t, d)
            assertTrue("$t: $r", r !is InputDecision.Keep || r.note != RuleNote.KEPT_LITERAL)
        }
    }

    @Test
    fun r04ContextTermsAreKeptWithAWarning() {
        for (t in listOf("timeout", "production", "database connection pool", "Staging", "merge.", "foreign key constraint")) {
            assertKeep(t, RuleNote.CONTEXT_TERM)
        }
    }

    @Test
    fun r05DialogueWordsAreTranslated() {
        for ((t, d) in listOf("yes" to en, "no" to en, "hello" to en, "Thanks!" to en, "ừ" to vi, "cảm ơn" to vi, "Xin lỗi." to vi)) {
            val r = decide(t, d)
            assertTrue("$t: $r", r is InputDecision.Translate && r.notes.isEmpty())
        }
        val r = decide("laptop", en)
        assertTrue("$r", r is InputDecision.Translate && RuleNote.SHORT_INPUT in r.notes)
        // A long run without spaces is not "rất ngắn".
        val run = decide("a.".repeat(250), en)
        assertTrue("$run", run is InputDecision.Translate && RuleNote.SHORT_INPUT !in run.notes)
    }

    @Test
    fun r06WrongDirectionAsksFirst() {
        val r = decide("Hello, how are you?", vi)
        assertTrue("$r", r is InputDecision.Mismatch && r.suggested == en && r.guess == LangGuess.EN_HIGH)
        val back = decide("Xin chào, bạn khỏe không?", en)
        assertTrue("$back", back is InputDecision.Mismatch && back.suggested == vi)
        // A Vietnamese name or dish inside an English sentence is not Vietnamese text.
        for (t in listOf("I love phở in Hà Nội", "I love phở", "Welcome to Việt Nam!", "Thanks, Dũng!", "Do you like bánh mì?", "phở is delicious")) {
            assertTrue(t, decide(t, en) !is InputDecision.Mismatch)
        }
        // English jargon in a Vietnamese sentence does not make it English.
        assertTrue(decide("Tôi cần fix bug trong production server", en) is InputDecision.Mismatch)
        // A dialogue phrase of the other language is clear evidence.
        assertTrue(decide("thank you", vi) is InputDecision.Mismatch)
        // Mixed Vietnamese with English jargon is fine.
        val mixed = decide("Tôi cần deploy API lên production.", vi)
        assertTrue("$mixed", mixed is InputDecision.Translate && RuleNote.LANGUAGE_OVERRIDDEN !in mixed.notes)
        // "Vẫn dịch": translated, flagged.
        val forced = rules.decide("Hello, how are you?", vi, override = true)
        assertTrue("$forced", forced is InputDecision.Translate && RuleNote.LANGUAGE_OVERRIDDEN in forced.notes)
    }

    @Test
    fun r07UnaccentedVietnameseWarns() {
        for (t in listOf("toi can kiem tra loi API", "chao ban", "cam on ban", "toi muon di ga tau.", "An Do co the chan Proton Mail")) {
            val r = decide(t, vi)
            assertTrue("$t: $r", r is InputDecision.Translate && RuleNote.NO_DIACRITICS in r.notes)
        }
        // The same typed as English is caught by R06.
        assertTrue(decide("cam on ban nhe", en) is InputDecision.Mismatch)
        for (t in listOf("database connection pool", "CPU", "https://example.com", "dev@example.com", "`ls -la`", "API timeout 504", "Hello world")) {
            val d = rules.decide(t, vi, override = true)
            assertTrue("$t: $d", d !is InputDecision.Translate || RuleNote.NO_DIACRITICS !in d.notes)
        }
    }

    @Test
    fun r08SentenceIsTranslatedWholeAndLostTermsAreFlagged() {
        val r = decide("Deploy CI/CD lên staging trước khi merge vào main.", vi)
        assertTrue("$r", r is InputDecision.Translate)
        val ok = rules.checkOutput("Deploy CI/CD lên staging trước khi merge vào main.", "Deploy CI/CD to staging before merging into main.", vi)
        assertEquals(OutputCheck.Valid(emptyList()), ok)
        val lost = rules.checkOutput("Tôi đang học Javascript", "I'm learning Vocabulary", vi)
        assertEquals(OutputCheck.Valid(listOf("JavaScript")), lost)
        val spaced = rules.checkOutput("Tôi dùng CI/CD", "I use CI / CD", vi)
        assertEquals(OutputCheck.Valid(emptyList()), spaced)
        // The policy's own case: an English context word of a Vietnamese sentence that the output loses is flagged.
        assertEquals(
            OutputCheck.Valid(listOf("production")),
            rules.checkOutput("Tôi cần deploy API lên production.", "I need to deploy API to the framework.", vi),
        )
        assertEquals(
            OutputCheck.Valid(listOf("deploy")),
            rules.checkOutput("Deploy CI/CD lên staging trước khi merge vào main.", "Delloy CI/CD to staging ahead of merge in the main.", vi),
        )
        // Inflected forms keep the word; in EN→VI a context word may be translated.
        assertEquals(OutputCheck.Valid(emptyList()), rules.checkOutput("Mình đã commit rồi merge branch", "I committed and then merged the branch", vi))
        assertEquals(OutputCheck.Valid(emptyList()), rules.checkOutput("Deploy to production", "Triển khai lên môi trường sản xuất", en))
        assertEquals(OutputCheck.Valid(emptyList()), rules.checkOutput("Bật WiFi", "Turn on Wi-Fi", vi))
    }

    @Test
    fun r12StructurallyBrokenOutputIsRejected() {
        assertTrue(rules.checkOutput("Xin chào", "  ", vi) is OutputCheck.Invalid)
        val url = rules.checkOutput("Xem https://example.com/docs nhé", "See the docs", vi)
        assertTrue("$url", url is OutputCheck.Invalid && (url as OutputCheck.Invalid).missing.single().kind == LiteralKind.URL)
        val status = rules.checkOutput("API trả về lỗi 504", "The API returns error 503", vi)
        assertTrue("$status", status is OutputCheck.Invalid)
        assertEquals(OutputCheck.Valid(emptyList()), rules.checkOutput("API trả về lỗi 504", "The API returns error 504", vi))
        val email = rules.checkOutput("Gửi về dev@example.com", "Send to dev @ example.com", vi)
        assertTrue("$email", email is OutputCheck.Invalid)
        // Code may only gain or lose spaces ("f()" → "f ()"); a broken name is still lost.
        assertEquals(OutputCheck.Valid(emptyList()), rules.checkOutput("Gọi hàm getData() trước", "Call the getData () function first", vi))
        assertTrue(rules.checkOutput("Gọi hàm getData() trước", "Call the get Date () function first", vi) is OutputCheck.Invalid)
        // Not literals at all: a menu path, a Vietnamese "và/hoặc", a date, a 4-part version.
        for ((src, out) in listOf(
            "Vào Settings->General" to "Go to Cài đặt->Chung",
            "Chọn một và /hoặc hai" to "Choose one and/or two",
            "Ra mắt ngày 25/8/2023" to "Launched on August 25, 2023",
            "Cập nhật OxygenOS 14.0.0.304" to "OxygenOS 14.0.304 update",
        )) assertTrue("$src: ${rules.checkOutput(src, out, vi)}", rules.checkOutput(src, out, vi) is OutputCheck.Valid)
    }

    /** Chat-like inputs found by the review: none of them is a technical literal or a wrong-language case. */
    @Test
    fun chatInputsAreNotLiterals() {
        val valid = OutputCheck.Valid(emptyList())
        // Emoticons, arrows between Vietnamese words, a Vietnamese gloss in brackets, a missing space after a period.
        assertEquals(valid, rules.checkOutput("Ok nha=))", "Ok =))", vi))
        assertEquals(valid, rules.checkOutput("Bước 1=>Bước 2 nhé", "Step 1=>Step 2", vi))
        assertEquals(valid, rules.checkOutput("Máy cần thêm RAM(bộ nhớ) mới chạy được", "The machine needs more RAM (memory) to run", vi))
        assertEquals(valid, rules.checkOutput("Xong roi.co the nghi", "Done, can rest", vi))
        // A host name may change case.
        assertEquals(valid, rules.checkOutput("Vào Google.com tìm thử", "Go to google.com and search", vi))
        for (t in listOf("haha =))", "npm install failed again", "docker build works now")) {
            val d = decide(t, en)
            assertTrue("$t: $d", d !is InputDecision.Keep || d.note != RuleNote.KEPT_LITERAL)
        }
        // Short unaccented Vietnamese made of English homographs is not English.
        for (t in listOf("to di day", "on roi", "no di dau roi")) assertTrue(t, decide(t, vi) !is InputDecision.Mismatch)
        // R07 with English jargon around.
        val jargon = decide("deploy len production bi loi timeout", vi)
        assertTrue("$jargon", jargon is InputDecision.Translate && RuleNote.NO_DIACRITICS in jargon.notes)
        // R08 also at the start of an English sentence; "log" is not kept by "login".
        assertEquals(OutputCheck.Valid(listOf("React")), rules.checkOutput("React is a JavaScript library", "Phản ứng là một thư viện JavaScript", en))
        assertEquals(OutputCheck.Valid(listOf("log")), rules.checkOutput("Kiểm tra log giúp mình", "Check the login for me", vi))
        assertEquals(valid, rules.checkOutput("Kiểm tra log giúp mình", "Check the logs for me", vi))
    }

    /** The fixed case set of §8 (assets/text/mt_rule_cases_v1.json, also run by the APK and tools/mt/run_rule_tiers.py). */
    @Test
    fun fixedCaseSetDecisions() {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(File("src/main/assets/${RuleCases.ASSET}").readText())
        val cases = RuleCases.parse(root)
        assertTrue(cases.size >= 39)
        for (c in cases) {
            val d = rules.decide(c.input, c.direction)
            val kind = when (d) {
                is InputDecision.Keep -> "keep"
                is InputDecision.Translate -> "translate"
                is InputDecision.Mismatch -> "mismatch"
                is InputDecision.TooLong -> "too_long"
                is InputDecision.Empty -> "empty"
            }
            assertEquals("${c.id}: $d", c.expect, kind)
            if (d is InputDecision.Keep) assertEquals(c.id, c.rule, d.note.rule)
            if (d is InputDecision.Translate) {
                assertTrue("${c.id}: $d", d.notes.map { it.name }.containsAll(c.notes))
                assertTrue("${c.id}: $d", d.notes.none { it.name in c.noNotes })
            }
        }
    }

    @Test
    fun r02LengthIsCountedInDisplayedCharacters() {
        assertTrue(decide("   \n ", vi) is InputDecision.Empty)
        val five = "a".repeat(495) + " 😀ế🇻🇳" // 499 displayed characters (one space)
        assertEquals(499, TextRules.typedLength(five))
        assertTrue(decide("a".repeat(500), vi) !is InputDecision.TooLong)
        val r = decide("a".repeat(501), vi)
        assertTrue("$r", r is InputDecision.TooLong && r.length == 501)
        // R01 collapses spaces before counting.
        assertEquals(3, TextRules.typedLength("  a  \n b "))
    }
}
