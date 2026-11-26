package vn.viettechtrans.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun keptBlocksKeepTheirPlaceInTheSameOrderAsTheLines() {
        val box = listOf(Pt(10f, 20f), Pt(200f, 20f), Pt(200f, 80f), Pt(10f, 80f))
        val blocks = listOf(
            OcrBlock(listOf("Cài đặt"), box),
            OcrBlock(listOf("•"), box),
            OcrBlock(listOf("Kết nối không thành công.", "Hãy thử lại."), box),
        )
        val placed = OcrText.place(blocks)
        assertEquals(listOf("Cài đặt", "Kết nối không thành công. Hãy thử lại."), placed.map { it.text })
        assertEquals(listOf(1, 2), placed.map { it.lineCount })
        assertEquals(box, placed[1].corners)
        assertEquals(OcrText.join(blocks), placed.joinToString("\n") { it.text })
    }

    @Test
    fun translationLinesBelongToBlocksOnlyWhileTheBoxHasOneLinePerBlock() {
        assertEquals(
            listOf("Software update", "The new version is ready."),
            OcrText.linesForBlocks("Cập nhật phần mềm\nPhiên bản mới đã sẵn sàng.", "Software update\nThe new version is ready.", 2),
        )
        // A word corrected in the box: still one line per block.
        assertEquals(listOf("Unable to connect to the server."), OcrText.linesForBlocks("Không thể kết nối tới máy chủ.", "Unable to connect to the server.", 1))
        // Lines joined or split by the user: the blocks are unknown.
        assertNull(OcrText.linesForBlocks("Cập nhật phần mềm Phiên bản mới đã sẵn sàng.", "Software update The new version is ready.", 2))
        assertNull(OcrText.linesForBlocks("a\nb\nc", "a\nb\nc", 2))
        assertNull(OcrText.linesForBlocks("", "", 0))
    }

    @Test
    fun overlayColoursComeFromTheBackgroundAndTheLetters() {
        val white = 0xFFFFFFFF.toInt()
        val ink = 0xFF202030.toInt()
        // 80 % background, 20 % letters.
        val (bg, fg) = OverlayColors.pick(IntArray(100) { if (it % 5 == 0) ink else white })
        assertEquals(white, bg)
        assertEquals(ink, fg)
        // White letters on a blue button.
        val blue = 0xFF1A73E8.toInt()
        val (bg2, fg2) = OverlayColors.pick(IntArray(100) { if (it % 4 == 0) white else blue })
        assertEquals(blue, bg2)
        assertEquals(white, fg2)
        // No letters stand out (a blurred block): plain black on a light background.
        val grey = 0xFFE0E0E0.toInt()
        assertEquals(OverlayColors.BLACK, OverlayColors.pick(IntArray(50) { grey }).second)
    }

    @Test
    fun nothingReadGivesEmptyText() {
        assertEquals("", OcrText.join(emptyList()))
        assertEquals("", OcrText.join(listOf(OcrBlock(emptyList()))))
    }
}
