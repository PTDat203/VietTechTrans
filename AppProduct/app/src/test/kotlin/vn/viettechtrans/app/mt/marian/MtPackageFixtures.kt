package vn.viettechtrans.app.mt.marian

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import vn.viettechtrans.app.mt.MtPackageManifest

/**
 * The packaged MT (app/src/main/assets/mt, built by tools/mt/package_mt.py) and its Hugging Face
 * fixtures (.cache/mt/fixtures). Neither is in git, like the speech models: tests using them are
 * skipped on a clean clone. Unit tests run with the app module as working directory.
 */
object MtPackageFixtures {
    val assetsDir = File("src/main/assets/mt")
    private val fixturesDir = File("../.cache/mt/fixtures")

    val manifest: MtPackageManifest by lazy { MtPackageManifest.parse(File(assetsDir, "manifest.json").readText()) }

    fun requirePackage() = assumeTrue(
        "MT package missing: run python -I tools/mt/package_mt.py",
        File(assetsDir, "manifest.json").isFile && fixturesDir.isDirectory,
    )

    fun tokenizer(direction: String): MarianTokenizer {
        val spec = manifest.directions.getValue(direction)
        val t = spec.tokenizer
        return MarianTokenizer(
            SentencePiece.load(File(assetsDir, t.sourceSpm).readBytes()),
            MarianTokenizer.readVocab(File(assetsDir, t.vocab).readBytes()),
            spec.inputPrefix,
            eosId = t.eosId,
            unkId = t.unkId,
            padId = t.padId,
        )
    }

    fun lines(file: File): List<JsonObject> = file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }

    fun fixture(direction: String): List<JsonObject> = lines(File(fixturesDir, "${direction}_tokenizer.jsonl"))

    fun golden(direction: String): List<JsonObject> = lines(File(assetsDir, manifest.directions.getValue(direction).goldenFile))

    fun ints(o: JsonObject, key: String): IntArray = o.getValue(key).jsonArray.map { it.jsonPrimitive.int }.toIntArray()

    fun text(o: JsonObject, key: String): String = o.getValue(key).jsonPrimitive.content
}
