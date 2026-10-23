package vn.viettechtrans.app.mt.marian

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parity with `collapse_repeats` in tools/mt/marian_ref.py: `mt_collapse_repeats.json` holds every
 * Validation output it changes (evidence/phase04/mt_export_q8) plus edge cases, with its result.
 */
class OutputCleanupTest {
    @Test
    fun matchesPythonReference() {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("mt_collapse_repeats.json"))
        val cases = Json.parseToJsonElement(stream.use { it.readBytes().decodeToString() }).jsonObject.getValue("cases").jsonArray
        assertTrue(cases.size > 20)
        for (c in cases) {
            val text = c.jsonObject.getValue("text").jsonPrimitive.content
            assertEquals(text, c.jsonObject.getValue("expected").jsonPrimitive.content, OutputCleanup.collapseRepeats(text))
        }
    }

    @Test
    fun loopsFromThePhoneAreCollapsed() {
        assertEquals("Please.", OutputCleanup.collapseRepeats("Please, please, please, please, please, please."))
        assertEquals("No", OutputCleanup.collapseRepeats("No, no, no, no, no, no, no, no,"))
        assertEquals("really really really", OutputCleanup.collapseRepeats("really really really"))
    }

    /** The R11 table of the text-rule policy v1. */
    @Test
    fun policyExamples() {
        assertEquals("Please deploy.", OutputCleanup.collapseRepeats("Please please please please deploy."))
        assertEquals("No no no.", OutputCleanup.collapseRepeats("No no no."))
    }
}
