package vn.viettechtrans.app.mt.marian

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.viettechtrans.app.mt.Direction
import vn.viettechtrans.app.mt.rules.OutputCheck
import vn.viettechtrans.app.mt.rules.RuleLexicon
import vn.viettechtrans.app.mt.rules.TextRules

/**
 * Runs the packaged graph with the desktop build of the same ONNX Runtime: the Kotlin
 * tokenizer + beam search must reproduce golden.jsonl (written by tools/mt/marian_ref.py on
 * the same graph) token for token. On a phone the int8 kernels differ (ARM vs x86), so a few
 * golden sentences may differ there; golden.jsonl ships in the package for that check (Phase 05).
 */
class MarianGoldenTest {
    @Test
    fun enToVi() = check("en_to_vi")

    @Test
    fun viToEn() = check("vi_to_en")

    /**
     * Short greetings where the best beam finishes after one word: without the output length cap the
     * search ran to 511 steps (12 s on a phone) for the same "Hello" (tools/mt/marian_ref.py, PyTorch too).
     * The Validation sentence keeps its uncapped translation (HF-style forced EOS at the cap gave "http: ...").
     */
    @Test
    fun shortInputStopsAtTheLengthCap() {
        MtPackageFixtures.requirePackage()
        engine("vi_to_en").use { engine ->
            for (source in listOf("chào bạn", "vâng")) {
                val ids = engine.tokenizer.encode(source)
                val out = engine.generate(ids)
                assertTrue("$source: ${out.steps} steps", out.steps <= GenerationParams.LENGTH_CAP_RATIO * ids.size + GenerationParams.LENGTH_CAP_SLACK)
            }
            // Same as tools/mt/marian_ref.py (app_max_length + prefer_finished + collapse_repeats).
            assertEquals("Hello", engine.translate("chào bạn").text)
            assertEquals("Yes", engine.translate("vâng").text)
            assertEquals("none", engine.translate("không").text)
            assertEquals("Please", engine.translate("xin chao").text)
            assertEquals(
                "Draws a dot and colored rectangles. Written by Jamie Zawinski; 1999.",
                engine.translate("Chương trình này vẽ các hình chữ nhật chấm và có màu. Viết bởi Jamie Zawinski; 1999.").text,
            )
        }
    }

    /**
     * Text-rule policy v1 on the real graph (DR-07 §16): the model gets the text as typed and its output is shown as
     * is, R08 only flags a lost term. Same outputs as tools/mt/marian_ref.py (app policy) on this graph; "không" →
     * "none" and "Javascript" → "Vocabulary" are model errors kept as evidence for Student v2, not patched.
     */
    @Test
    fun policyCasesOnTheRealGraph() {
        MtPackageFixtures.requirePackage()
        val rules = TextRules(RuleLexicon.parse(File("src/main/assets/${RuleLexicon.ASSET}").readText()))
        val cases = mapOf(
            "en_to_vi" to listOf(
                "yes" to "có", "no" to "không", "hello" to "xin chào", "thanks" to "Cảm ơn", "please" to "Làm ơn", "sorry" to "Xin lỗi",
                "Our company is hiring React developers" to "Công ty của chúng tôi đang tuyển dụng các nhà phát triển phản ứng",
            ),
            "vi_to_en" to listOf(
                "ừ" to "Yes", "vâng" to "Yes", "không" to "none", "chào" to "Hello", "cảm ơn" to "Thank you", "xin lỗi" to "Sorry",
                "Tôi đang học Javascript" to "I'm learning Vocabulary",
                "Deploy CI/CD lên staging trước khi merge vào main." to "Delloy CI/CD to staging ahead of merge in the main.",
                // R04 keeps these when typed alone: what the model would have said.
                "production" to "Performion", "database connection pool" to "database concept pool",
            ),
        )
        for ((direction, rows) in cases) {
            engine(direction).use { engine ->
                for ((source, expected) in rows) assertEquals(source, expected, engine.translate(source).text)
            }
        }
        val vi = Direction.VI_TO_EN
        assertEquals(OutputCheck.Valid(listOf("JavaScript")), rules.checkOutput("Tôi đang học Javascript", "I'm learning Vocabulary", vi))
        assertEquals(
            OutputCheck.Valid(listOf("React")),
            rules.checkOutput("Our company is hiring React developers", "Công ty của chúng tôi đang tuyển dụng các nhà phát triển phản ứng", Direction.EN_TO_VI),
        )
        // CI/CD, staging and merge survive; the model misspelled "Deploy", which R08 flags.
        assertEquals(OutputCheck.Valid(listOf("deploy")), rules.checkOutput("Deploy CI/CD lên staging trước khi merge vào main.", "Delloy CI/CD to staging ahead of merge in the main.", vi))
        // R11 on the graph: this Validation sentence makes the model loop ("ứng ứng ứng ứng dụng").
        engine("en_to_vi").use { engine ->
            val r = engine.translate("Optimizing database queries is essential for application responsiveness.")
            assertTrue(r.repeatCleanupApplied)
            assertTrue(r.rawText.length > r.text.length)
        }
    }

    /**
     * R09: 500 characters can be more than 500 source ids ("a." 250 times is 501); without a space to cut at, the
     * run is cut between characters, nothing is lost, and a URL is never cut.
     */
    @Test
    fun overlongInputIsSplitWithoutLoss() {
        MtPackageFixtures.requirePackage()
        val rules = TextRules(RuleLexicon.parse(File("src/main/assets/${RuleLexicon.ASSET}").readText()))
        engine("vi_to_en", maxInputTokens = TextRules.MAX_SOURCE_IDS) { t -> rules.literals.spans(t).map { it.start until it.end } }.use { engine ->
            val dots = "a.".repeat(250)
            assertTrue(engine.tokenizer.encode(dots).size > TextRules.MAX_SOURCE_IDS)
            val parts = engine.segment(dots)
            assertTrue(parts.size > 1)
            assertTrue(parts.all { engine.tokenizer.encode(it).size <= TextRules.MAX_SOURCE_IDS })
            assertEquals(dots, parts.joinToString(""))
            assertEquals(parts.size, engine.translate(dots).segments)
            // Sentences are packed; the URL stays whole in one segment.
            val url = "https://example.com/docs/api/v1/users?id=42"
            val long = ("Mở trang $url để xem tài liệu. " + "Đây là một câu tiếng Việt khá dài để tách đoạn. ".repeat(40)).trim()
            val segs = engine.segment(long)
            assertTrue(segs.size > 1)
            assertTrue(segs.any { url in it })
            assertEquals(long.split(Regex("\\s+")), segs.flatMap { it.split(" ") })
        }
    }

    private fun engine(
        direction: String,
        maxInputTokens: Int = MtPackageFixtures.manifest.directions.getValue(direction).inputLengthPolicy.maxInputTokens,
        protectedRanges: (String) -> List<IntRange> = { emptyList() },
    ): MarianEngine {
        val spec = MtPackageFixtures.manifest.directions.getValue(direction)
        val g = spec.generation
        return MarianEngine(
            File(MtPackageFixtures.assetsDir, spec.model).readBytes(),
            MtPackageFixtures.tokenizer(direction),
            GenerationParams(g.numBeams, g.maxLength, g.lengthPenalty, g.decoderStartTokenId, g.eosTokenId),
            maxInputTokens,
            protectedRanges = protectedRanges,
        )
    }

    private fun check(direction: String) {
        MtPackageFixtures.requirePackage()
        val spec = MtPackageFixtures.manifest.directions.getValue(direction)
        engine(direction).use { engine ->
            for (row in MtPackageFixtures.golden(direction)) {
                val source = MtPackageFixtures.text(row, "source")
                val ids = engine.tokenizer.encode(source)
                assertArrayEquals(source, MtPackageFixtures.ints(row, "input_ids"), ids)
                val out = engine.generate(ids)
                assertArrayEquals(source, MtPackageFixtures.ints(row, "output_ids"), out.ids)
                assertEquals(MtPackageFixtures.text(row, "text"), engine.tokenizer.decode(out.ids))
            }
            // Over-long input: split into segments that each fit, never truncated.
            val long = MtPackageFixtures.golden(direction).joinToString(" ") { MtPackageFixtures.text(it, "source") }.repeat(3)
            val segments = engine.segment(long)
            assertTrue(segments.size > 1)
            assertTrue(segments.all { engine.tokenizer.encode(it).size <= spec.inputLengthPolicy.maxInputTokens })
            assertEquals(long.split(Regex("\\s+")).filter { it.isNotEmpty() }, segments.flatMap { it.split(" ") })
        }
    }
}
