package vn.viettechtrans.app.ocr

import vn.viettechtrans.app.mt.MtInputNormalizer

/** One text block found in a picture: its lines top to bottom, as the recognizer returns them. */
data class OcrBlock(val lines: List<String>)

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
    fun join(blocks: List<OcrBlock>): String = blocks
        .map { repairTildes(joinLines(it.lines)) }
        .filter { block -> block.any { it.isLetterOrDigit() } }
        .joinToString("\n")

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
    private fun isVietnameseLetter(c: Char): Boolean = c in '\u1EA0'..'\u1EF9' || c in "ĂăĐđƠơƯư"

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
