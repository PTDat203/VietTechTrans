package vn.viettechtrans.app.mt

/**
 * The translator: the Main-Core MT package in assets/mt/, or none when it was not built
 * (the app then shows the source text only; there is no online fallback).
 */
class TranslatorRegistry(private val coreMt: suspend () -> CoreMt) {
    @Volatile
    private var resolved = false

    @Volatile
    private var cached: Translator? = null

    suspend fun active(): Translator? {
        if (resolved) return cached
        cached = (coreMt() as? CoreMt.Available)?.translator
        resolved = true
        return cached
    }

    /** Keeps only [directions] loaded (screen-based RAM policy, like SpeechEngines.retainOnly). */
    suspend fun retainOnly(directions: Set<Direction>) {
        val t = active() ?: return
        t.release(Direction.entries.toSet() - directions)
        if (directions.isNotEmpty()) t.prepare(directions)
    }

    /** Frees every loaded direction (app in background / low memory); no-op before first use. */
    fun releaseAll() {
        cached?.release(Direction.entries.toSet())
    }
}
