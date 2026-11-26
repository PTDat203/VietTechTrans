package vn.viettechtrans.app.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import vn.viettechtrans.app.speech.nowMs

/**
 * Text read from a picture: [text] for the translator (see [OcrText]), [lines] found, recognition time, and for
 * Dịch trên ảnh the picture ([page], at most [ImageTextReader.PAGE_MAX_SIDE] px) with the kept [blocks] in its pixels.
 */
data class OcrResult(
    val text: String,
    val lines: Int,
    val ms: Long,
    val blocks: List<PlacedBlock> = emptyList(),
    val page: Bitmap? = null,
)

/**
 * Dịch ảnh: reads the text of a picture with ML Kit's Latin recognizer, whose model is bundled in the APK
 * (works offline, without Google Play services; Vietnamese is a supported language, DR-10).
 * The recognizer is created for each picture and closed after it, so it holds no memory between uses.
 */
class ImageTextReader(context: Context) {
    private val appContext = context.applicationContext

    suspend fun read(uri: Uri): OcrResult = withContext(Dispatchers.Default) {
        val t0 = nowMs()
        // The app decodes the picture itself (camera photos turned upright from their EXIF orientation), so the
        // corners ML Kit returns are pixels of this very bitmap and the translation can be drawn over it.
        val decoded = decode(uri, OCR_MAX_SIDE) ?: throw IOException("không giải mã được ảnh")
        // ML Kit and the drawing below work on 8-bit ARGB (a wide-gamut photo may decode as RGBA_F16).
        val picture = if (decoded.config == Bitmap.Config.ARGB_8888) decoded else decoded.copy(Bitmap.Config.ARGB_8888, false)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val result = suspendCancellableCoroutine { cont ->
                recognizer.process(InputImage.fromBitmap(picture, 0))
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
            val blocks = result.textBlocks.map { b ->
                OcrBlock(
                    b.lines.map { it.text },
                    b.cornerPoints?.map { Pt(it.x.toFloat(), it.y.toFloat()) }.orEmpty(),
                    // Height of each line box: from its top-left to its bottom-left corner (tilted lines too).
                    b.lines.map { l -> l.cornerPoints?.takeIf { it.size == 4 }?.let { c -> hypot((c[3].x - c[0].x).toFloat(), (c[3].y - c[0].y).toFloat()) } ?: 0f },
                )
            }
            val placed = OcrText.place(blocks)
            val ms = nowMs() - t0
            // Kept for drawing: a smaller copy when the photo is large.
            val side = maxOf(picture.width, picture.height)
            val (page, pageBlocks) = if (side <= PAGE_MAX_SIDE) {
                picture to placed
            } else {
                val f = PAGE_MAX_SIDE.toFloat() / side
                Bitmap.createScaledBitmap(picture, (picture.width * f).roundToInt(), (picture.height * f).roundToInt(), true) to
                    placed.map { b -> b.copy(corners = b.corners.map { Pt(it.x * f, it.y * f) }, lineHeights = b.lineHeights.map { it * f }) }
            }
            OcrResult(placed.joinToString("\n") { it.text }, blocks.sumOf { it.lines.size }, ms, pageBlocks, page)
        } finally {
            recognizer.close()
        }
    }

    /** A small copy of the picture for the screen ([maxSide] px at most), turned upright like the photo. */
    suspend fun preview(uri: Uri, maxSide: Int = 720): Bitmap? = withContext(Dispatchers.IO) { decode(uri, maxSide) }

    /** Decodes the picture at most about [maxSide] px on its longer side (a power-of-two reduction), upright. */
    private fun decode(uri: Uri, maxSide: Int): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder applies the EXIF orientation itself.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(appContext.contentResolver, uri)) { decoder, info, _ ->
                val side = maxOf(info.size.width, info.size.height)
                if (side > maxSide) decoder.setTargetSampleSize((side + maxSide - 1) / maxSide)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val resolver = appContext.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
            val bitmap = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return@runCatching null
            val degrees = resolver.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
            if (degrees == 0f) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true)
        }
    }.getOrNull()

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

        /** Pictures are read at most this size: enough for small print on a phone photo, faster than 12 MP. */
        const val OCR_MAX_SIDE = 3000

        /** The copy kept for Dịch trên ảnh (about 16 MB at 2048 × 2048). */
        const val PAGE_MAX_SIDE = 2048
    }
}
