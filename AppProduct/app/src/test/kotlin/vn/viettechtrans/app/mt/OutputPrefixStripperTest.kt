package vn.viettechtrans.app.mt

import org.junit.Assert.assertEquals
import org.junit.Test

class OutputPrefixStripperTest {
    // Same cases as Core tests/test_phase02_pretrained_baseline.py (direction en_to_vi, prefix "vi:").
    @Test
    fun coreCases() {
        assertEquals("Bản dịch", OutputPrefixStripper.strip("vi: Bản dịch", "vi:"))
        assertEquals("Nội dung có vi: ở giữa", OutputPrefixStripper.strip("Nội dung có vi: ở giữa", "vi:"))
        assertEquals("en: Translation", OutputPrefixStripper.strip("en: Translation", "vi:"))
    }

    @Test
    fun removesAtMostOneWhitespace() {
        assertEquals("Bản", OutputPrefixStripper.strip("vi:Bản", "vi:"))
        assertEquals(" Bản", OutputPrefixStripper.strip("vi:  Bản", "vi:"))
        assertEquals("", OutputPrefixStripper.strip("vi:", "vi:"))
        assertEquals("", OutputPrefixStripper.strip("vi: ", "vi:"))
    }

    @Test
    fun whitespaceFollowsPythonIsspace() {
        assertEquals("X", OutputPrefixStripper.strip("vi:\u0085X", "vi:")) // NEL: Python whitespace
        assertEquals("X", OutputPrefixStripper.strip("vi:　X", "vi:"))
        assertEquals("​X", OutputPrefixStripper.strip("vi:​X", "vi:")) // ZWSP is not whitespace
    }

    @Test
    fun noPrefixConfigured() {
        assertEquals("vi: x", OutputPrefixStripper.strip("vi: x", null))
        assertEquals("vi: x", OutputPrefixStripper.strip("vi: x", ""))
    }
}
