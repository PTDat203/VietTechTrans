package vn.viettechtrans.app.mt

import android.content.Context
import android.content.res.AssetManager
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest
import vn.viettechtrans.app.mt.marian.MarianTranslator

/** Result of looking for the MT package in `assets/mt/`. */
sealed interface CoreMt {
    /** No `assets/mt/manifest.json` (tools/mt/package_mt.py not run): the app shows the source text only. */
    data object NotInstalled : CoreMt

    data class Invalid(val reasons: List<String>) : CoreMt

    /** [manifestSha256]: SHA-256 of the manifest bytes, written into rule logs and evidence (policy §8). */
    data class Available(val translator: Translator, val manifest: MtPackageManifest, val manifestSha256: String = "") : CoreMt
}

/** Entry point for the Main-Core MT package (Phase 03 Student, packaged in Phase 04). */
object CoreMtProvider {
    /** [protectedRanges]: literal ranges the R09 segmentation must not split. */
    fun open(context: Context, protectedRanges: (String) -> List<IntRange> = { emptyList() }): CoreMt {
        val assets = context.applicationContext.assets
        val bytes = try {
            assets.open(MtPackageManifest.ASSET_PATH).use { it.readBytes() }
        } catch (e: FileNotFoundException) {
            return CoreMt.NotInstalled
        } catch (e: IOException) {
            return CoreMt.Invalid(listOf("Không đọc được ${MtPackageManifest.ASSET_PATH}: ${e.message}"))
        }
        val manifest = try {
            MtPackageManifest.parse(bytes.decodeToString())
        } catch (e: IllegalArgumentException) { // includes SerializationException
            return CoreMt.Invalid(listOf("manifest.json sai định dạng: ${e.message}"))
        }
        val problems = MtPackageValidator.validate(manifest) { path -> exists(assets, "mt/$path") }
        if (problems.isNotEmpty()) return CoreMt.Invalid(problems)
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return CoreMt.Available(MarianTranslator(assets, manifest, protectedRanges = protectedRanges), manifest, sha)
    }

    private fun exists(assets: AssetManager, path: String): Boolean = try {
        assets.open(path).close()
        true
    } catch (e: IOException) {
        false
    }
}
