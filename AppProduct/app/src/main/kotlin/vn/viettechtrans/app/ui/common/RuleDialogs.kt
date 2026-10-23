package vn.viettechtrans.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import vn.viettechtrans.app.R
import vn.viettechtrans.app.mt.Lang
import vn.viettechtrans.app.mt.rules.TextRules

/**
 * Policy R06: the typed text looks like the other language. Nothing is sent until the user picks "Đổi sang …"
 * ([onSwitch]) or "Vẫn dịch" ([onForce]); the app never switches by itself. Dismissing gives the text back.
 */
@Composable
fun MismatchDialog(chosen: Lang, onSwitch: () -> Unit, onForce: () -> Unit, onDismiss: () -> Unit) {
    val looksEnglish = chosen == Lang.VI
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (looksEnglish) R.string.mismatch_title_en else R.string.mismatch_title_vi)) },
        text = {
            Text(
                stringResource(
                    R.string.mismatch_text,
                    stringResource(if (looksEnglish) R.string.lang_vi else R.string.lang_en),
                    stringResource(if (looksEnglish) R.string.lang_en_inline else R.string.lang_vi_inline),
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onSwitch) {
                Text(stringResource(if (looksEnglish) R.string.mismatch_switch_en else R.string.mismatch_switch_vi))
            }
        },
        dismissButton = { TextButton(onClick = onForce) { Text(stringResource(R.string.mismatch_force)) } },
    )
}

/**
 * Policy R02 under an input box: "n/500" counted like the send check (displayed characters after R01), and the
 * policy message when the text is too long or was sent empty. The text itself is never cut.
 */
@Composable
fun InputCounter(text: String, emptyTried: Boolean) {
    val n = TextRules.typedLength(text)
    val tooLong = n > TextRules.MAX_CHARS
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.input_counter, n, TextRules.MAX_CHARS),
            style = MaterialTheme.typography.labelSmall,
            color = if (tooLong) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            tooLong -> Text(stringResource(R.string.input_too_long), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            emptyTried && n == 0 -> Text(stringResource(R.string.input_empty), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
    }
}
