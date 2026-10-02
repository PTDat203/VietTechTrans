package vn.viettechtrans.app.mt

import android.content.Context

/** Dev build: Google ML Kit Translate as a temporary translator (never evidence). */
object FlavorTranslators {
    fun create(context: Context): Translator? = MlKitTranslator()
}
