package vn.viettechtrans.app.mt

import android.content.Context
import android.content.res.AssetManager
import java.io.FileNotFoundException
import java.io.IOException
import vn.viettechtrans.app.mt.marian.MarianTranslator

/** Result of looking for the MT package in `assets/mt/`. */
sealed interface CoreMt {
    /** No `assets/mt/manifest.json` (tools/mt/package_mt.py not run): the app shows the source text only. */
    data object NotInstalled : CoreMt

    data class Invalid(val reasons: List<String>) : CoreMt

    data class Available(val translator: Translator, val manifest: MtPackageManifest) : CoreMt
}

/** Entry point for the Main-Core MT package (Phase 03 Student, packaged in Phase 04). */
object CoreMtProvider {
    fun open(context: Context): CoreMt {
        val assets = context.applicationContext.assets
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
        val problems = MtPackageValidator.validate(manifest) { path -> exists(assets, "mt/$path") }
        if (problems.isNotEmpty()) return CoreMt.Invalid(problems)
        return CoreMt.Available(MarianTranslator(assets, manifest), manifest)
    }

    private fun exists(assets: AssetManager, path: String): Boolean = try {
        assets.open(path).close()
        true
    } catch (e: IOException) {
        false
    }
}
