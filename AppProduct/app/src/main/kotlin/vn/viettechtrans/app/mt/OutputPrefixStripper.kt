package vn.viettechtrans.app.mt

/**
 * Output normalization `remove_exact_leading_control_prefix_and_one_delimiter_whitespace`,
 * ported from Core `baseline.PretrainedAdapter._normalize_decoded_output`:
 * if the prediction starts with the control prefix (e.g. "vi:"), drop it and at most
 * ONE following whitespace character (Python `str.isspace`). Nothing else changes.
 */
object OutputPrefixStripper {
    const val POLICY = "remove_exact_leading_control_prefix_and_one_delimiter_whitespace"

    fun strip(prediction: String, controlPrefix: String?): String {
        if (controlPrefix.isNullOrEmpty() || !prediction.startsWith(controlPrefix)) return prediction
        val rest = prediction.substring(controlPrefix.length)
        return if (rest.isNotEmpty() && PythonWhitespace.contains(rest[0])) rest.substring(1) else rest
    }
}
