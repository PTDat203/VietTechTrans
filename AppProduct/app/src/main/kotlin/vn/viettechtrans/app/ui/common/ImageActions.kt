package vn.viettechtrans.app.ui.common

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import vn.viettechtrans.app.R

/** Sharing the translated picture (Dịch trên ảnh) with an app the user picks. */
object ImageActions {
    /** Shared pictures live in the app cache only until the next share. */
    const val SHARED_DIR = "shared"

    suspend fun share(context: Context, picture: Bitmap) {
        val file = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, SHARED_DIR).apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            File(dir, "VietTechTrans_${System.currentTimeMillis()}.jpg").also { f ->
                f.outputStream().use { picture.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(
                Intent.createChooser(send, context.getString(R.string.image_overlay_share))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.share_no_app, Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * The translated picture on the whole screen: pinch to zoom, drag to move, double tap to fit again.
 * "Ảnh gốc" shows the original picture in the same place, to compare.
 */
@Composable
fun ShowImageDialog(translated: Bitmap, original: Bitmap?, onShare: () -> Unit, onDismiss: () -> Unit) {
    var showOriginal by remember { mutableStateOf(false) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(modifier = Modifier.fillMaxSize().background(Color.Black).padding(vertical = 16.dp)) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 6f)
                            offset = if (scale == 1f) Offset.Zero else offset + pan
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = {
                            scale = 1f
                            offset = Offset.Zero
                        })
                    },
                contentAlignment = Alignment.Center,
            ) {
                val shown = if (showOriginal && original != null) original else translated
                Image(
                    bitmap = shown.asImageBitmap(),
                    contentDescription = stringResource(if (shown === translated) R.string.image_overlay_title else R.string.image_preview),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
                )
            }
            Text(
                stringResource(R.string.image_overlay_zoom_hint),
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (original != null) {
                    PictureChoice(stringResource(R.string.image_overlay_translated), selected = !showOriginal) { showOriginal = false }
                    PictureChoice(stringResource(R.string.image_overlay_original), selected = showOriginal) { showOriginal = true }
                }
                Box(Modifier.weight(1f))
                TextButton(onClick = onShare) {
                    Text(stringResource(R.string.action_share), color = Color.White, style = MaterialTheme.typography.titleMedium)
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_close), color = Color.White, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun PictureChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            labelColor = Color.White,
            selectedContainerColor = Color.White,
            selectedLabelColor = Color.Black,
        ),
    )
}
