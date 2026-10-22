package vn.viettechtrans.app.mt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uses the example of docs/contracts/mt_package_v2.md (copied to test resources). */
class MtPackageManifestTest {
    private val example: String by lazy {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("mt_package_v2.example.json")) {
            "mt_package_v2.example.json missing"
        }
        stream.use { it.readBytes().decodeToString() }
    }

    private fun allPresent(m: MtPackageManifest): (String) -> Boolean {
        val listed = m.files.map { it.path }.toSet()
        return { path -> path in listed }
    }

    @Test
    fun exampleParsesAndIsValid() {
        val m = MtPackageManifest.parse(example)
        assertEquals("2.0", m.schemaVersion)
        assertEquals(setOf("en_to_vi", "vi_to_en"), m.directions.keys)
        val en = m.directions.getValue("en_to_vi")
        assertEquals(">>vie<<", en.inputPrefix)
        assertEquals(4, en.generation.numBeams)
        assertEquals(53684, en.tokenizer.padId)
        assertEquals("", m.directions.getValue("vi_to_en").inputPrefix)
        assertEquals(emptyList<String>(), MtPackageValidator.validate(m, allPresent(m)))
    }

    @Test
    fun missingFileIsReported() {
        val m = MtPackageManifest.parse(example)
        val problems = MtPackageValidator.validate(m) { path -> !path.endsWith("model.onnx") }
        assertEquals(2, problems.count { "thiếu file" in it })
    }

    @Test
    fun decodingTheAppCannotReproduceIsRejected() {
        val greedySampled = example.replaceFirst("\"renormalize_logits\": true", "\"renormalize_logits\": false")
            .replaceFirst("\"early_stopping\": false", "\"early_stopping\": true")
        val m = MtPackageManifest.parse(greedySampled)
        val problems = MtPackageValidator.validate(m, allPresent(m))
        assertTrue(problems.any { "early_stopping" in it })
        assertTrue(problems.any { "renormalize_logits" in it })
    }

    @Test
    fun wrongLanguageControlIsRejected() {
        val m = MtPackageManifest.parse(example.replace("\">>vie<<\"", "\"en:\""))
        assertTrue(MtPackageValidator.validate(m, allPresent(m)).any { "input_prefix" in it })
    }

    @Test
    fun unknownOptionalFieldsAreIgnored() {
        val m = MtPackageManifest.parse(example.replaceFirst("{", "{\n  \"future_field\": {\"x\": 1},"))
        assertEquals(emptyList<String>(), MtPackageValidator.validate(m, allPresent(m)))
    }

    @Test
    fun missingRequiredFieldFailsParsing() {
        val broken = example.replace("\"package_id\"", "\"package_idx\"")
        assertThrows(IllegalArgumentException::class.java) { MtPackageManifest.parse(broken) }
    }

    @Test
    fun otherMajorVersionIsRejected() {
        val m = MtPackageManifest.parse(example.replace("\"schema_version\": \"2.0\"", "\"schema_version\": \"1.0\""))
        assertTrue(MtPackageValidator.validate(m, allPresent(m)).any { "schema_version" in it })
    }

    @Test
    fun unsafePathsAreRejected() {
        assertTrue(MtPackageValidator.isSafeRelativePath("en_to_vi/model.onnx"))
        for (bad in listOf("", "/abs", "../x", "a/../b", "a\\b", "a//b", "./a")) {
            assertFalse(bad, MtPackageValidator.isSafeRelativePath(bad))
        }
    }
}
