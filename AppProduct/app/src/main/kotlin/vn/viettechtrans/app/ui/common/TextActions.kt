package vn.viettechtrans.app.ui.common

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import vn.viettechtrans.app.R

/** Copy, share and paste plain text. Sharing hands the text to an app the user picks; the app itself has no network. */
object TextActions {
    fun copy(context: Context, text: String) {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("VietTechTrans", text))
        // Android 13+ shows its own confirmation for clipboard writes.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, R.string.action_copied, Toast.LENGTH_SHORT).show()
        }
    }

    fun share(context: Context, text: String, title: String? = null) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            title?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
        }
        try {
            context.startActivity(Intent.createChooser(send, context.getString(R.string.action_share)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.share_no_app, Toast.LENGTH_SHORT).show()
        }
    }

    /** Text on the clipboard, or null when it holds none. */
    fun paste(context: Context): String? {
        val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(context)?.toString()?.takeIf { it.isNotBlank() }
    }
}

/**
 * The translation in large letters on the whole screen, to show the person in front ("Phóng to").
 * "Xoay" turns it upside down for someone sitting across the table. Tap anywhere or "Đóng" to close.
 */
@Composable
fun ShowTextDialog(text: String, onDismiss: () -> Unit) {
    var flipped by rememberSaveable { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(24.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text,
                    color = Color.White,
                    fontSize = if (text.length <= 60) 40.sp else if (text.length <= 160) 30.sp else 22.sp,
                    lineHeight = if (text.length <= 60) 48.sp else if (text.length <= 160) 38.sp else 30.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.rotate(if (flipped) 180f else 0f).verticalScroll(rememberScrollState()),
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { flipped = !flipped }) {
                    Text(stringResource(R.string.show_rotate), color = Color.White, style = MaterialTheme.typography.titleMedium)
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_close), color = Color.White, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}
