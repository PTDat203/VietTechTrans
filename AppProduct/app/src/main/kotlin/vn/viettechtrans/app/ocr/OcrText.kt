package vn.viettechtrans.app.ocr

import vn.viettechtrans.app.mt.MtInputNormalizer

/** A point of the picture, in pixels. */
data class Pt(val x: Float, val y: Float)

/**
 * One text block found in a picture: its lines top to bottom, as the recognizer returns them, and its four
 * corners (top-left, top-right, bottom-right, bottom-left in reading direction, so a tilted block has tilted
 * corners). [corners] is empty when the recognizer gives none.
 */
data class OcrBlock(val lines: List<String>, val corners: List<Pt> = emptyList(), val lineHeights: List<Float> = emptyList())

/**
 * A block kept for translation: its text (one line of [OcrText.join]), where it is, how many lines it had, and the
 * lines as read with the height of each line box (0 when unknown): together they tell the size of its letters.
 */
data class PlacedBlock(
    val text: String,
    val corners: List<Pt>,
    val lineCount: Int,
    val lines: List<String> = emptyList(),
    val lineHeights: List<Float> = emptyList(),
)

/**
 * Turns recognized lines into the text the translator receives (DR-10). The recognizer breaks a sentence
 * wherever the picture wraps it, and Marian translates a broken sentence as two, so:
 * - the lines of one block are one paragraph, joined with a space;
 * - a word split by a hyphen at the end of a line ("configu-" + "ration") is joined back;
 * - blocks stay on separate lines (headings, buttons and paragraphs are separate sentences);
 * - blocks without a letter or digit (lines, bullets, icons read as symbols) are dropped;
 * - in a Vietnamese block, umlauts are put back as tildes ([repairTildes]).
 * Nothing else is changed: no spelling fix, no case change (policy R01). A wrong tone mark ("kết nôi" for
 * "kết nối") cannot be told from a real word, so it stays for the user to correct in the box.
 */
object OcrText {
    fun join(blocks: List<OcrBlock>): String = place(blocks).joinToString("\n") { it.text }

    /** The blocks [join] keeps, in the same order as its lines. */
    fun place(blocks: List<OcrBlock>): List<PlacedBlock> = blocks.mapNotNull { b ->
        val text = repairTildes(joinLines(b.lines))
        if (text.any { it.isLetterOrDigit() }) PlacedBlock(text, b.corners, b.lines.count { it.isNotBlank() }, b.lines, b.lineHeights) else null
    }

    /**
     * Dịch trên ảnh: the translation of each block. The translator gets one line per block and translates the lines
     * one by one (TurnRunner), so line i of the translation belongs to block i, as long as the box still has one
     * line per block: the user may correct words, but after joining or splitting lines the blocks are unknown (null).
     */
    fun linesForBlocks(source: String, translation: String, blocks: Int): List<String>? {
        val sourceLines = source.split('\n').count { it.isNotBlank() }
        val out = translation.split('\n').map { it.trim() }
        return if (blocks > 0 && sourceLines == blocks && out.size == blocks) out else null
    }

    /**
     * ML Kit's Latin model reads some Vietnamese tildes as German umlaut dots ("Häy" for "Hãy" on the Dịch ảnh
     * test set, DR-10). Vietnamese has no ä, ö, ü, ë, ï, ÿ, so in a block that has Vietnamese letters they are
     * the tilde letters; a block without Vietnamese letters ("Zürich" in English text) is left as read.
     */
    fun repairTildes(block: String): String {
        if (block.none(::isVietnameseLetter) || block.none { it in UMLAUT_TO_TILDE }) return block
        return block.map { UMLAUT_TO_TILDE[it] ?: it }.joinToString("")
    }

    /** Letters only Vietnamese uses: ă, đ, ơ, ư and the toned vowels of Latin Extended Additional (U+1EA0–U+1EF9). */
    private fun isVietnameseLetter(c: Char): Boolean = c in 'Ạ'..'ỹ' || c in "ĂăĐđƠơƯư"

    private val UMLAUT_TO_TILDE = mapOf(
        'ä' to 'ã', 'ö' to 'õ', 'ü' to 'ũ', 'ë' to 'ẽ', 'ï' to 'ĩ', 'ÿ' to 'ỹ',
        'Ä' to 'Ã', 'Ö' to 'Õ', 'Ü' to 'Ũ', 'Ë' to 'Ẽ', 'Ï' to 'Ĩ', 'Ÿ' to 'Ỹ',
    )

    private fun joinLines(lines: List<String>): String {
        val out = StringBuilder()
        for (raw in lines) {
            val line = MtInputNormalizer.normalize(raw)
            if (line.isEmpty()) continue
            if (out.isEmpty()) {
                out.append(line)
            } else if (out.length >= 2 && out.last() == '-' && out[out.length - 2].isLetter() && line.first().isLowerCase()) {
                out.setLength(out.length - 1)
                out.append(line)
            } else {
                out.append(' ').append(line)
            }
        }
        return out.toString()
    }
}

/**
 * Colours for painting a translation over a block (Dịch trên ảnh): the background to cover the original letters
 * and a text colour that stands out from it. Pure ARGB arithmetic, so it is tested on the JVM.
 */
object OverlayColors {
    const val WHITE = -0x1 // 0xFFFFFFFF
    const val BLACK = -0x1000000 // 0xFF000000

    /** Brightness difference below which the read text colour is too faint to use: plain black or white instead. */
    private const val MIN_CONTRAST = 90

    /**
     * Pixels of a tight box around text are mostly background: the background is the per-channel median of the pixels
     * near the median brightness (a median, so the soft edges of the letters do not tint a white page grey); the
     * letters are the tenth of the pixels furthest from it in brightness.
     */
    fun pick(pixels: IntArray): Pair<Int, Int> {
        if (pixels.isEmpty()) return WHITE to BLACK
        val lum = IntArray(pixels.size) { luma(pixels[it]) }
        val median = lum.sorted()[lum.size / 2]
        val bg = median(pixels.filterIndexed { i, _ -> kotlin.math.abs(lum[i] - median) <= 24 })
        val bgL = luma(bg)
        val far = pixels.indices.sortedByDescending { kotlin.math.abs(lum[it] - bgL) }.take(maxOf(1, pixels.size / 10))
        val fg = average(far.map { pixels[it] })
        val text = if (kotlin.math.abs(luma(fg) - bgL) >= MIN_CONTRAST) fg else if (bgL > 140) BLACK else WHITE
        return bg to text
    }

    /** Rec. 601 brightness, 0–255. */
    fun luma(argb: Int): Int = (299 * (argb shr 16 and 0xFF) + 587 * (argb shr 8 and 0xFF) + 114 * (argb and 0xFF)) / 1000

    private fun median(pixels: List<Int>): Int {
        if (pixels.isEmpty()) return WHITE
        fun channel(shift: Int) = pixels.map { it shr shift and 0xFF }.sorted()[pixels.size / 2]
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun average(pixels: List<Int>): Int {
        if (pixels.isEmpty()) return WHITE
        var r = 0L
        var g = 0L
        var b = 0L
        for (p in pixels) {
            r += p shr 16 and 0xFF
            g += p shr 8 and 0xFF
            b += p and 0xFF
        }
        val n = pixels.size
        return (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
    }
}
