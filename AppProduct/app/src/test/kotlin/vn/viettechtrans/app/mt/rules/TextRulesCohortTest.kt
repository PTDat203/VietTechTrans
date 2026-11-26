package vn.viettechtrans.app.mt.rules

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assume.assumeTrue
import org.junit.Test
import vn.viettechtrans.app.mt.Direction
import vn.viettechtrans.app.mt.MtInputNormalizer
import vn.viettechtrans.app.mt.marian.OutputCleanup

/**
 * The decision of the text rules for every row of the four Validation cohorts (evidence/phase04/mt_export_q4r), so
 * Student v1 and the app can be compared on the rows the app really translates (tools/mt/score_rule_cohort.py).
 * Per row: R01 change, the input decision (R02–R07), the shown output after R11 and the output checks (R12, R08) of
 * the app output, of the Student v1 output and of the reference. Writes build/mt_rules_cohort_rows.jsonl; with
 * -Dvtt.rulesEvidence=<dir> also <dir>/cohort_rows.jsonl.
 */
class TextRulesCohortTest {
    private val rules = TextRules(RuleLexicon.parse(File("src/main/assets/${RuleLexicon.ASSET}").readText()))
    private val dir = File("../evidence/phase04/mt_export_q4r")

    private fun check(source: String, output: String, d: Direction): JsonObject = buildJsonObject {
        when (val c = rules.checkOutput(source, output, d)) {
            is OutputCheck.Invalid -> {
                put("r12", c.reason)
                putJsonArray("missing") { c.missing.forEach { add(JsonPrimitive("${it.kind}:${it.text}")) } }
            }
            is OutputCheck.Valid -> putJsonArray("r08") { c.checkTerms.forEach { add(JsonPrimitive(it)) } }
        }
    }

    @Test
    fun decisionsPerRow() {
        assumeTrue("q4r Validation outputs not present", File(dir, "vi_to_en_selection_validation.predictions.jsonl").isFile)
        val out = StringBuilder()
        for (d in Direction.entries) for (role in listOf("selection_validation", "general_validation")) {
            File(dir, "${d.id}_$role.predictions.jsonl").readLines().filter { it.isNotBlank() }.forEach { l ->
                val o = Json.parseToJsonElement(l).jsonObject
                val source = o.getValue("source").jsonPrimitive.content
                val prediction = o.getValue("prediction").jsonPrimitive.content
                val student = o.getValue("phase03_prediction").jsonPrimitive.content
                val reference = o.getValue("reference").jsonPrimitive.content
                val normalized = MtInputNormalizer.normalize(source)
                val shown = OutputCleanup.collapseRepeats(prediction)
                out.appendLine(
                    buildJsonObject {
                        put("set", "${d.id}/$role")
                        put("row_id", o.getValue("row_id").jsonPrimitive.content)
                        put("r01_changed", normalized != source)
                        when (val x = rules.decide(source, d)) {
                            is InputDecision.Empty -> put("decision", "empty_R02")
                            is InputDecision.TooLong -> put("decision", "too_long_R02")
                            is InputDecision.Keep -> {
                                put("decision", "keep_${x.note.rule}")
                                x.literal?.let { put("literal", it.name) }
                            }
                            is InputDecision.Mismatch -> put("decision", "mismatch_R06")
                            is InputDecision.Translate -> {
                                put("decision", "translate")
                                putJsonArray("notes") { x.notes.forEach { add(JsonPrimitive(it.name)) } }
                            }
                        }
                        put("app_output", shown)
                        put("r11_changed", shown != prediction)
                        put("app_check", check(normalized, shown, d))
                        put("student_check", check(normalized, student, d))
                        put("reference_check", check(normalized, reference, d))
                    }.toString(),
                )
            }
        }
        File("build").mkdirs()
        File("build/mt_rules_cohort_rows.jsonl").writeText(out.toString())
        System.getProperty("vtt.rulesEvidence")?.let { File(it).mkdirs(); File(it, "cohort_rows.jsonl").writeText(out.toString()) }
    }
}
