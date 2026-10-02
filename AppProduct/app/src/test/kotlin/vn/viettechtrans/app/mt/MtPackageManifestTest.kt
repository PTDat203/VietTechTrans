package vn.viettechtrans.app.mt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uses the example of docs/contracts/mt_package_v1.md §9 (copied to test resources). */
class MtPackageManifestTest {
    private val example: String by lazy {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("mt_package_v1.example.json")) {
            "mt_package_v1.example.json missing"
        }
        stream.use { it.readBytes().decodeToString() }
    }

    /** The example with placeholders replaced by plausible values. */
    private fun filledExample(): String = example
        .replace("<yyyy-mm-ddThh:mm:ss+00:00>", "2026-10-01T00:00:00+00:00")
        .replace("\"bytes\": 0", "\"bytes\": 10")
        .replace("<sha256>", "a".repeat(64))

    private fun sizesOf(m: MtPackageManifest): (String) -> Long? {
        val sizes = m.files.associate { it.path to it.bytes }
        return { path -> sizes[path] }
    }

    @Test
    fun exampleParses() {
        val m = MtPackageManifest.parse(example)
        assertEquals("1.0", m.schemaVersion)
        assertEquals(setOf("en_to_vi", "vi_to_en"), m.directions.keys)
        assertEquals("en:", m.directions.getValue("en_to_vi").inputPrefix)
        assertEquals("vi:", m.directions.getValue("en_to_vi").outputControlPrefix)
        assertEquals(512, m.directions.getValue("vi_to_en").generation.maxLength)
        assertEquals(listOf(listOf(50000, 50047)), m.tokenizer.decodeSkipIdRanges)
        assertEquals(70.33, m.directions.getValue("en_to_vi").quality.checkpointItTest!!.chrfPlusPlus, 1e-9)
    }

    @Test
    fun placeholdersAreRejected() {
        val m = MtPackageManifest.parse(example)
        val problems = MtPackageValidator.validate(m, sizesOf(m))
        assertTrue(problems.any { "created_at_utc" in it })
        assertTrue(problems.any { "bytes phải > 0" in it })
        assertTrue(problems.any { "sha256" in it })
    }

    @Test
    fun filledExampleIsValid() {
        val m = MtPackageManifest.parse(filledExample())
        assertEquals(emptyList<String>(), MtPackageValidator.validate(m, sizesOf(m)))
    }

    @Test
    fun missingOrWrongSizedFileIsReported() {
        val m = MtPackageManifest.parse(filledExample())
        val missing = MtPackageValidator.validate(m) { path -> if (path.endsWith(".jsonl")) null else 10L.takeIf { path != "tokenizer/spiece.model" } ?: 1102207L }
        assertTrue(missing.any { "thiếu file" in it })
        val wrongSize = MtPackageValidator.validate(m) { 999L }
        assertTrue(wrongSize.any { "khác manifest" in it })
    }

    @Test
    fun unknownOptionalFieldsAreIgnored() {
        val withExtra = filledExample().replaceFirst("{", "{\n  \"future_field\": {\"x\": 1},")
        val m = MtPackageManifest.parse(withExtra)
        assertEquals(emptyList<String>(), MtPackageValidator.validate(m, sizesOf(m)))
    }

    @Test
    fun missingRequiredFieldFailsParsing() {
        val broken = filledExample().replace("\"package_id\"", "\"package_idx\"")
        assertThrows(IllegalArgumentException::class.java) { MtPackageManifest.parse(broken) }
    }

    @Test
    fun otherMajorVersionIsRejected() {
        val m = MtPackageManifest.parse(filledExample().replace("\"schema_version\": \"1.0\"", "\"schema_version\": \"2.0\""))
        assertTrue(MtPackageValidator.validate(m, sizesOf(m)).any { "schema_version" in it })
    }

    @Test
    fun unsafePathsAreRejected() {
        assertTrue(MtPackageValidator.isSafeRelativePath("en_to_vi/encoder_model.onnx"))
        for (bad in listOf("", "/abs", "../x", "a/../b", "a\\b", "a//b", "./a")) {
            assertFalse(bad, MtPackageValidator.isSafeRelativePath(bad))
        }
    }
}
