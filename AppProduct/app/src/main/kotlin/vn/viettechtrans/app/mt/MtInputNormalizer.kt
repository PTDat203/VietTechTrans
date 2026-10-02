package vn.viettechtrans.app.mt

import java.text.Normalizer

/**
 * Input normalization applied by the pipeline before ANY translator
 * (policy `phase01_nfc_whitespace_v1`, docs/contracts/mt_package_v1.md §5.1).
 *
 * Equals Core's `build_phase01.norm()` (NFC, collapse Python whitespace runs to one
 * space, strip) after deleting the characters Phase 01 rejected: Core `CONTROL`
 * minus Python whitespace, plus U+FFFD. Phase 01 dropped such rows; an app cannot
 * reject user input, so it deletes the characters instead.
 */
object MtInputNormalizer {
    const val ID = "phase01_nfc_whitespace_v1"

    /** U+0000–0008, U+000E–001B, U+007F–0084, U+0086–009F, U+FFFD (56 code points). */
    val DELETED_CODEPOINTS: List<Int> =
        (0x00..0x08) + (0x0E..0x1B) + (0x7F..0x84) + (0x86..0x9F) + listOf(0xFFFD)

    fun isDeleted(ch: Char): Boolean = when (ch.code) {
        in 0x00..0x08, in 0x0E..0x1B, in 0x7F..0x84, in 0x86..0x9F, 0xFFFD -> true
        else -> false
    }

    fun normalize(text: String): String {
        val kept = StringBuilder(text.length)
        for (ch in text) {
            if (!isDeleted(ch)) kept.append(ch)
        }
        val nfc = Normalizer.normalize(kept, Normalizer.Form.NFC)
        val out = StringBuilder(nfc.length)
        var pendingSpace = false
        for (ch in nfc) {
            if (PythonWhitespace.contains(ch)) {
                pendingSpace = true
            } else {
                if (pendingSpace && out.isNotEmpty()) out.append(' ')
                out.append(ch)
                pendingSpace = false
            }
        }
        return out.toString()
    }
}
