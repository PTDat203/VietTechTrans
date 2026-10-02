package vn.viettechtrans.app.mt

import android.content.Context

/**
 * Picks the translator: the Core/Student MT package when installed (Phase 04),
 * otherwise the flavor's translator (`dev`: ML Kit; `offline`: none).
 */
class TranslatorRegistry(
    private val context: Context,
    private val coreMt: suspend () -> CoreMt,
) {
    @Volatile
    private var resolved = false

    @Volatile
    private var cached: Translator? = null

    suspend fun active(): Translator? {
        if (resolved) return cached
        cached = (coreMt() as? CoreMt.Available)?.translator ?: FlavorTranslators.create(context)
        resolved = true
        return cached
    }
}
