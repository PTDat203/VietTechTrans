package vn.viettechtrans.app.mt.rules

import java.io.File
import java.text.Normalizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import vn.viettechtrans.app.mt.Direction
import vn.viettechtrans.app.mt.MtInputNormalizer

/**
 * How often each rule fires on the four Validation sets with the packaged q4r outputs
 * (evidence/phase04/mt_export_q4r): the false-alarm budget of R03/R04/R06/R07/R12 on real IT sentences, and R08.
 * Writes build/mt_rules_calibration.json and every triggered case to build/mt_rules_calibration_cases.jsonl;
 * with -Dvtt.rulesEvidence=<dir> the summary also goes into that directory.
 */
class TextRulesCalibrationTest {
    private val rules = TextRules(RuleLexicon.parse(File("src/main/assets/${RuleLexicon.ASSET}").readText()))
    private val dir = File("../evidence/phase04/mt_export_q4r")

    private class Row(val source: String, val prediction: String, val reference: String)

    private fun rows(direction: String, role: String): List<Row> =
        File(dir, "${direction}_$role.predictions.jsonl").readLines().filter { it.isNotBlank() }.map { l ->
            val o = Json.parseToJsonElement(l).jsonObject
            Row(o.getValue("source").jsonPrimitive.content, o.getValue("prediction").jsonPrimitive.content, o.getValue("reference").jsonPrimitive.content)
        }

    private fun stripDiacritics(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").replace('đ', 'd').replace('Đ', 'D')

    @Test
    fun ratesOnValidation() {
        assumeTrue("q4r Validation outputs not present", File(dir, "vi_to_en_selection_validation.predictions.jsonl").isFile)
        val report = LinkedHashMap<String, JsonObject>()
        val cases = StringBuilder()
        for (d in Direction.entries) for (role in listOf("selection_validation", "general_validation")) {
            val data = rows(d.id, role)
            val counts = LinkedHashMap<String, Int>()
            fun inc(k: String) { counts[k] = (counts[k] ?: 0) + 1 }
            fun case(kind: String, r: Row, detail: String = "", text: String = r.source) {
                cases.appendLine(
                    buildJsonObject {
                        put("set", "${d.id}/$role"); put("kind", kind); put("text", text); put("detail", detail)
                        put("prediction", r.prediction); put("reference", r.reference)
                    }.toString(),
                )
            }
            for (r in data) {
                when (val x = rules.decide(r.source, d)) {
                    is InputDecision.Keep -> {
                        inc("keep_${x.note.rule}")
                        if (r.reference.trim() == r.source.trim()) inc("keep_${x.note.rule}_reference_identical")
                        case("keep_${x.note.rule}", r, x.literal?.name.orEmpty())
                    }
                    is InputDecision.Mismatch -> { inc("mismatch"); case("mismatch", r, x.guess.id) }
                    is InputDecision.TooLong -> inc("too_long")
                    is InputDecision.Empty -> inc("empty")
                    is InputDecision.Translate -> {
                        inc("translate")
                        x.notes.forEach { inc("note_${it.name}") }
                        if (RuleNote.NO_DIACRITICS in x.notes) case("R07_on_original", r)
                        if (RuleNote.LANGUAGE_UNCERTAIN in x.notes) case("uncertain", r)
                    }
                }
                inc("guess_${rules.languages.guess(r.source).id}")
                // The same source typed for the other direction: should be caught (R06).
                if (rules.decide(r.source, d.reverse) is InputDecision.Mismatch) inc("wrong_direction_caught")
                if (d == Direction.VI_TO_EN) {
                    val plain = stripDiacritics(r.source)
                    if (plain != r.source) {
                        inc("unaccented_rows")
                        when (val p = rules.decide(plain, d)) {
                            is InputDecision.Mismatch -> { inc("unaccented_mismatch"); case("unaccented_mismatch", r, text = plain) }
                            is InputDecision.Translate -> if (RuleNote.NO_DIACRITICS in p.notes) inc("unaccented_R07") else case("unaccented_no_R07", r, text = plain)
                            // Kept without the model once the diacritics are gone: counted and listed with the other cases.
                            is InputDecision.Keep -> { inc("unaccented_keep_${p.note.rule}"); case("unaccented_keep_${p.note.rule}", r, p.literal?.name.orEmpty(), text = plain) }
                            is InputDecision.TooLong, is InputDecision.Empty -> inc("unaccented_other")
                        }
                    }
                }
                when (val c = rules.checkOutput(MtInputNormalizer.normalize(r.source), r.prediction, d)) {
                    is OutputCheck.Invalid -> {
                        inc("R12_${c.reason}")
                        c.missing.map { it.kind }.distinct().forEach { inc("R12_$it") }
                        case("R12", r, c.missing.joinToString(" | ") { "${it.kind}:${it.text}" })
                    }
                    is OutputCheck.Valid -> if (c.checkTerms.isNotEmpty()) {
                        inc("R08")
                        case("R08", r, c.checkTerms.joinToString(", "))
                    }
                }
                if (rules.lexicon.find(r.source).isNotEmpty()) inc("rows_with_terms")
            }
            report["${d.id}/$role"] = buildJsonObject {
                put("rows", data.size)
                counts.toSortedMap().forEach { (k, v) -> put(k, v) }
            }
        }
        val summary = buildJsonObject {
            put("lexicon_version", rules.lexicon.version)
            put("data", "evidence/phase04/mt_export_q4r/*.predictions.jsonl (source, app q4r prediction, reference)")
            put("sets", JsonObject(report))
        }
        val json = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), summary)
        File("build").mkdirs()
        File("build/mt_rules_calibration.json").writeText(json + "\n")
        File("build/mt_rules_calibration_cases.jsonl").writeText(cases.toString())
        System.getProperty("vtt.rulesEvidence")?.let { out ->
            File(out).mkdirs()
            File(out, "calibration.json").writeText(json + "\n")
            File(out, "calibration_cases.jsonl").writeText(cases.toString())
        }
        println(json)
        // Budgets (DR-07 §16): natural sentences (General Validation) never trigger R03/R04 or the R06 question;
        // the IT set may, for its UI strings, identifiers and rows in the wrong language.
        for ((k, v) in report) {
            val n = v.getValue("rows").jsonPrimitive.content.toInt()
            fun c(key: String) = v[key]?.jsonPrimitive?.content?.toInt() ?: 0
            val general = k.endsWith("general_validation")
            assertTrue("$k R03 keeps too much", c("keep_R03") * 1000 <= n * (if (general) 1 else 15))
            assertTrue("$k R04 on a whole row", c("keep_R04") * 1000 <= n * (if (general) 1 else 2))
            assertTrue("$k R06 asks on the right language", c("mismatch") * 1000 <= n * (if (general) 2 else 10))
            assertTrue("$k R12 rejects too much", c("R12_lost_literal") * 1000 <= n * (if (general) 2 else 10))
            if (k.startsWith("vi_to_en")) {
                // R07: no warning on Vietnamese typed with diacritics; Vietnamese with every diacritic removed is caught.
                assertTrue("$k R07 on text with diacritics", c("note_NO_DIACRITICS") * 1000 <= n * 2)
                assertTrue("$k R07 recall", c("unaccented_R07") * 100 >= c("unaccented_rows") * 90)
                assertTrue("$k R06 on unaccented Vietnamese", c("unaccented_mismatch") * 1000 <= c("unaccented_rows") * 3)
            }
        }
    }
}
