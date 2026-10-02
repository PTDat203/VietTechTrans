package vn.viettechtrans.app.speech

import android.content.Context
import android.content.res.AssetManager
import java.io.File

/**
 * espeak-ng (phonemizer of the Piper voices) needs its data as real files, not
 * assets. Copies `models/espeak-ng-data` from the APK to app storage once; a stamp
 * with the manifest's sha256_tree triggers a new copy after an app update.
 * This is a local copy, not a download.
 */
object EspeakDataInstaller {
    fun ensure(context: Context, manifest: ModelManifest): File {
        val data = manifest.espeakData ?: error("models/manifest.json không có espeak_data")
        val base = context.noBackupFilesDir
        val target = File(base, "espeak-ng-data")
        val stamp = File(base, "espeak-ng-data.stamp")
        if (target.isDirectory && stamp.isFile && stamp.readText() == data.sha256Tree) return target

        val tmp = File(base, "espeak-ng-data.tmp")
        tmp.deleteRecursively()
        copyAssetTree(context.assets, data.dir, tmp)
        target.deleteRecursively()
        check(tmp.renameTo(target)) { "Không đổi tên được $tmp" }
        stamp.writeText(data.sha256Tree)
        return target
    }

    private fun copyAssetTree(assets: AssetManager, path: String, dest: File) {
        val children = assets.list(path).orEmpty()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            assets.open(path).use { input -> dest.outputStream().use { input.copyTo(it) } }
        } else {
            dest.mkdirs()
            for (name in children) copyAssetTree(assets, "$path/$name", File(dest, name))
        }
    }
}
