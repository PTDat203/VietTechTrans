package vn.viettechtrans.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrTextTest {
    @Test
    fun linesOfOneBlockFormOneSentence() {
        val text = OcrText.join(listOf(OcrBlock(listOf("Vui lòng khởi động lại máy tính", "và thử lại."))))
        assertEquals("Vui lòng khởi động lại máy tính và thử lại.", text)
    }

    @Test
    fun blocksStaySeparateLines() {
        val text = OcrText.join(listOf(OcrBlock(listOf("Cài đặt")), OcrBlock(listOf("Kết nối không thành công.", "Hãy thử lại."))))
        assertEquals("Cài đặt\nKết nối không thành công. Hãy thử lại.", text)
    }

    @Test
    fun wordHyphenatedAtLineEndIsJoined() {
        assertEquals("Check the configuration file.", OcrText.join(listOf(OcrBlock(listOf("Check the configu-", "ration file.")))))
        // Not a split word: a hyphen before a capital or a digit, or a lone dash, stays.
        assertEquals("Wi- Fi", OcrText.join(listOf(OcrBlock(listOf("Wi-", "Fi")))))
        assertEquals("pages 10 - 12", OcrText.join(listOf(OcrBlock(listOf("pages 10 -", "12")))))
    }

    @Test
    fun blocksWithoutLettersOrDigitsAreDropped() {
        val text = OcrText.join(listOf(OcrBlock(listOf("•", "—")), OcrBlock(listOf("API timeout 504"))))
        assertEquals("API timeout 504", text)
    }

    @Test
    fun whitespaceAndUnicodeFollowR01WithoutChangingCase() {
        // Decomposed "ệ" (e + U+0302 + U+0323) becomes the NFC character; runs of spaces collapse; case is kept.
        val text = OcrText.join(listOf(OcrBlock(listOf("  Kiểm tra   hệ thống  ", "", "API  "))))
        assertEquals("Kiểm tra hệ thống API", text)
    }

    @Test
    fun umlautReadForAVietnameseTildeIsRepaired() {
        // Measured on the test set: "Hãy" was read "Häy" in a photo (DR-10).
        assertEquals("Hãy khởi động lại điện thoại.", OcrText.join(listOf(OcrBlock(listOf("Häy khởi động lại điện thoại.")))))
        // English text keeps its umlauts; so does a block without Vietnamese letters.
        assertEquals("The office in Zürich is closed.", OcrText.join(listOf(OcrBlock(listOf("The office in Zürich is closed.")))))
        assertEquals("Häy", OcrText.repairTildes("Häy"))
    }

    @Test
    fun nothingReadGivesEmptyText() {
        assertEquals("", OcrText.join(emptyList()))
        assertEquals("", OcrText.join(listOf(OcrBlock(emptyList()))))
    }
}
