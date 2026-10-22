package vn.viettechtrans.app.mt.marian

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Kotlin tokenizer must give exactly the ids Hugging Face `MarianTokenizer` gave in
 * Phase 03 (edge cases, all General Validation sources, every 4th IT Validation source),
 * and decode generated ids to the same text.
 */
class MarianTokenizerParityTest {
    @Test
    fun enToVi() = check("en_to_vi")

    @Test
    fun viToEn() = check("vi_to_en")

    private fun check(direction: String) {
        MtPackageFixtures.requirePackage()
        val tok = MtPackageFixtures.tokenizer(direction)
        val mismatches = mutableListOf<String>()
        var count = 0
        for (row in MtPackageFixtures.fixture(direction)) {
            count++
            if ("ids" in row) {
                val text = MtPackageFixtures.text(row, "text")
                val expected = MtPackageFixtures.ints(row, "ids")
                val actual = tok.encode(text)
                if (!expected.contentEquals(actual)) mismatches += "encode ${text.take(60)}: hf=${expected.toList()} kt=${actual.toList()}"
            } else {
                val expected = MtPackageFixtures.text(row, "text")
                val actual = tok.decode(MtPackageFixtures.ints(row, "decode_ids"))
                if (expected != actual) mismatches += "decode: hf=$expected kt=$actual"
            }
        }
        assertEquals("$direction: ${mismatches.size}/$count mismatches\n" + mismatches.take(8).joinToString("\n"), 0, mismatches.size)
    }
}
