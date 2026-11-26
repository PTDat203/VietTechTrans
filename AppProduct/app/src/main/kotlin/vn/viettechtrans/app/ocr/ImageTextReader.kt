package vn.viettechtrans.app.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import vn.viettechtrans.app.speech.nowMs

/** Text read from a picture: [text] for the translator (see [OcrText]), [lines] found, recognition time. */
data class OcrResult(val text: String, val lines: Int, val ms: Long)

/**
 * Dịch ảnh: reads the text of a picture with ML Kit's Latin recognizer, whose model is bundled in the APK
 * (works offline, without Google Play services; Vietnamese is a supported language, DR-10).
 * The recognizer is created for each picture and closed after it, so it holds no memory between uses.
 */
class ImageTextReader(context: Context) {
    private val appContext = context.applicationContext

    suspend fun read(uri: Uri): OcrResult = withContext(Dispatchers.Default) {
        val t0 = nowMs()
        // fromFilePath applies the EXIF rotation of camera photos.
        val image = InputImage.fromFilePath(appContext, uri)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val result = suspendCancellableCoroutine { cont ->
                recognizer.process(image)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
            val blocks = result.textBlocks.map { b -> OcrBlock(b.lines.map { it.text }) }
            OcrResult(OcrText.join(blocks), blocks.sumOf { it.lines.size }, nowMs() - t0)
        } finally {
            recognizer.close()
        }
    }

    /** A small copy of the picture for the screen ([maxSide] px at most), rotated like the photo when the OS can. */
    suspend fun preview(uri: Uri, maxSide: Int = 720): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(appContext.contentResolver, uri)) { decoder, info, _ ->
                    val side = maxOf(info.size.width, info.size.height)
                    if (side > maxSide) decoder.setTargetSampleSize((side + maxSide - 1) / maxSide)
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                appContext.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
                appContext.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                }
            }
        }.getOrNull()
    }

    /** Where the camera app writes a new photo (app cache, shared through the FileProvider of the manifest). */
    fun newPhotoUri(): Uri {
        val dir = File(appContext.cacheDir, PHOTO_DIR).apply { mkdirs() }
        // One photo at a time: the previous one is no longer needed.
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "photo_${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(appContext, "${appContext.packageName}.files", file)
    }

    companion object {
        const val PHOTO_DIR = "photos"
    }
}
