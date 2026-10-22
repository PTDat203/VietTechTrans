package vn.viettechtrans.app.mt.marian

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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

    private fun engine(direction: String): MarianEngine {
        val spec = MtPackageFixtures.manifest.directions.getValue(direction)
        val g = spec.generation
        return MarianEngine(
            File(MtPackageFixtures.assetsDir, spec.model).readBytes(),
            MtPackageFixtures.tokenizer(direction),
            GenerationParams(g.numBeams, g.maxLength, g.lengthPenalty, g.decoderStartTokenId, g.eosTokenId),
            spec.inputLengthPolicy.maxInputTokens,
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
