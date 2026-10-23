package vn.viettechtrans.app.mt.rules

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import vn.viettechtrans.app.mt.Direction

/**
 * The fixed case set of the text-rule policy (§8 "Bộ test bắt buộc"), `assets/text/mt_rule_cases_v1.json`, written by
 * tools/mt/build_rule_cases.py. [expect] is the input-side outcome (keep, translate, mismatch, too_long, empty);
 * [answer] is what the runners do after an R06 question ("switch" or "force"); [check] names the output rule to read
 * in the evidence (R08, R09, R11, R12).
 */
data class RuleCase(
    val id: String,
    val rule: String,
    val direction: Direction,
    val input: String,
    val expect: String,
    val notes: List<String>,
    /** RuleNote names that must not be among the decision's notes (the policy's negative examples). */
    val noNotes: List<String>,
    val answer: String?,
    val check: String?,
)

object RuleCases {
    const val ASSET = "text/mt_rule_cases_v1.json"

    fun parse(root: JsonElement): List<RuleCase> = root.jsonObject.getValue("cases").jsonArray.map { e ->
        val o = e.jsonObject
        RuleCase(
            id = o.getValue("id").jsonPrimitive.content,
            rule = o.getValue("rule").jsonPrimitive.content,
            direction = Direction.fromId(o.getValue("direction").jsonPrimitive.content),
            input = o.getValue("input").jsonPrimitive.content,
            expect = o.getValue("expect").jsonPrimitive.content,
            notes = o["notes"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
            noNotes = o["no_notes"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
            answer = o["answer"]?.jsonPrimitive?.content,
            check = o["check"]?.jsonPrimitive?.content,
        )
    }
}
