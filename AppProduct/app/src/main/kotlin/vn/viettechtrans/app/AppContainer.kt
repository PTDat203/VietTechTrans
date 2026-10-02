package vn.viettechtrans.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import vn.viettechtrans.app.audio.MicRecorder
import vn.viettechtrans.app.mt.CoreMt
import vn.viettechtrans.app.mt.CoreMtProvider
import vn.viettechtrans.app.mt.TranslatorRegistry
import vn.viettechtrans.app.speech.Listener
import vn.viettechtrans.app.speech.Speaker
import vn.viettechtrans.app.speech.SpeechEngines
import vn.viettechtrans.app.text.TtsTextNormalizer
import vn.viettechtrans.app.turn.TurnRunner

/** Build facts shown in Settings → Giới thiệu and written into evidence. */
data class BuildInfo(
    val flavor: String,
    val buildType: String,
    val versionName: String,
    val versionCode: Int,
    val gitSha: String,
    val gitDirty: Boolean,
    val offlineBuild: Boolean,
)

/** Manual dependency container (no DI framework; see DR-01). */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val buildInfo = BuildInfo(
        flavor = BuildConfig.FLAVOR,
        buildType = BuildConfig.BUILD_TYPE,
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
        gitSha = BuildConfig.GIT_SHA,
        gitDirty = BuildConfig.GIT_DIRTY,
        offlineBuild = BuildConfig.OFFLINE_BUILD,
    )

    @Volatile
    private var coreMtCache: CoreMt? = null

    /** Looks for the Phase 04 MT package in assets (off the main thread). */
    suspend fun coreMt(): CoreMt = coreMtCache ?: withContext(Dispatchers.IO) {
        CoreMtProvider.open(appContext).also { coreMtCache = it }
    }

    val speechEngines = SpeechEngines(appContext)
    val mic = MicRecorder(appContext)
    val listener = Listener(speechEngines, mic)

    val ttsNormalizer: TtsTextNormalizer by lazy {
        val tsv = appContext.assets.open(TtsTextNormalizer.LEXICON_ASSET).use { it.readBytes().decodeToString() }
        TtsTextNormalizer(TtsTextNormalizer.parseLexicon(tsv))
    }
    val speaker: Speaker by lazy { Speaker(speechEngines, ttsNormalizer) }

    val translators = TranslatorRegistry(appContext) { coreMt() }

    /** Listen → clean → translate → speak, shared by the Dịch and Hội thoại screens. */
    val turns: TurnRunner by lazy { TurnRunner(this) }
}
