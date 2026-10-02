package vn.viettechtrans.app.mt

/**
 * The 29 code points that Python's `str.isspace()` and `re` `\s` treat as whitespace
 * (Python 3.14, Unicode 16). Core's `norm()` collapses exactly these, so the app
 * hard-codes them: Kotlin's `Char.isWhitespace()` misses U+0085 and Java's `(?U)\s`
 * misses U+001C–U+001F. Verified against `core_norm_parity.json`.
 */
object PythonWhitespace {
    val CODEPOINTS: List<Int> = listOf(
        0x09, 0x0A, 0x0B, 0x0C, 0x0D,
        0x1C, 0x1D, 0x1E, 0x1F, 0x20,
        0x85, 0xA0, 0x1680,
        0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005,
        0x2006, 0x2007, 0x2008, 0x2009, 0x200A,
        0x2028, 0x2029, 0x202F, 0x205F, 0x3000,
    )

    fun contains(ch: Char): Boolean = when (ch.code) {
        in 0x09..0x0D, in 0x1C..0x20, 0x85, 0xA0, 0x1680,
        in 0x2000..0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000 -> true
        else -> false
    }
}
