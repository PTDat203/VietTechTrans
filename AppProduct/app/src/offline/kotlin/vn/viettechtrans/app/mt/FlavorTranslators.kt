package vn.viettechtrans.app.mt

import android.content.Context

/** Offline build: no fallback translator. Without an MT package the app shows the source text only. */
object FlavorTranslators {
    fun create(context: Context): Translator? = null
}
