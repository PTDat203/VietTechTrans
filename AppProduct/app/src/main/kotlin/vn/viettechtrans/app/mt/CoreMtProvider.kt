package vn.viettechtrans.app.mt

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.content.res.AssetManager
import java.io.FileNotFoundException
import java.io.IOException

/** Result of looking for an MT package in `assets/mt/`. */
sealed interface CoreMt {
    /** No `assets/mt/manifest.json`: the offline build shows the source text only. */
    data object NotInstalled : CoreMt

    data class Invalid(val reasons: List<String>) : CoreMt

    data class Available(val translator: Translator) : CoreMt
}

/**
 * Entry point for the Core/Student MT package (Phase 04). Until Phase 04 adds
 * `CoreMtTranslator`, a valid package is still reported as not usable.
 */
object CoreMtProvider {
    fun open(context: Context): CoreMt {
        val assets = context.assets
        val text = try {
            assets.open(MtPackageManifest.ASSET_PATH).use { it.readBytes().decodeToString() }
        } catch (e: FileNotFoundException) {
            return CoreMt.NotInstalled
        } catch (e: IOException) {
            return CoreMt.Invalid(listOf("Không đọc được ${MtPackageManifest.ASSET_PATH}: ${e.message}"))
        }
        val manifest = try {
            MtPackageManifest.parse(text)
        } catch (e: IllegalArgumentException) { // includes SerializationException
            return CoreMt.Invalid(listOf("manifest.json sai định dạng: ${e.message}"))
        }
        val problems = MtPackageValidator.validate(manifest) { path -> assetSize(assets, "mt/$path") }
        if (problems.isNotEmpty()) return CoreMt.Invalid(problems)
        // Phase 04: return CoreMt.Available(CoreMtTranslator(context, manifest))
        return CoreMt.Invalid(listOf("CoreMtTranslator chưa có (Phase 04)"))
    }

    /** Size of an asset; works for uncompressed (openFd) and compressed assets. */
    private fun assetSize(assets: AssetManager, path: String): Long? {
        try {
            assets.openFd(path).use { fd ->
                if (fd.length != AssetFileDescriptor.UNKNOWN_LENGTH) return fd.length
            }
        } catch (e: IOException) {
            // Compressed assets cannot be opened as a file descriptor; count the stream below.
        }
        return try {
            assets.open(path).use { stream ->
                var total = 0L
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    total += read
                }
                total
            }
        } catch (e: IOException) {
            null
        }
    }
}
