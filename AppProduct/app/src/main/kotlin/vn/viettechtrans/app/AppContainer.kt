package vn.viettechtrans.app

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import vn.viettechtrans.app.audio.MicRecorder
import vn.viettechtrans.app.history.ConversationStore
import vn.viettechtrans.app.mt.CoreMt
import vn.viettechtrans.app.mt.CoreMtProvider
import vn.viettechtrans.app.mt.TranslatorRegistry
import vn.viettechtrans.app.mt.rules.RuleLexicon
import vn.viettechtrans.app.mt.rules.RuleLog
import vn.viettechtrans.app.mt.rules.TextRules
import vn.viettechtrans.app.speech.Listener
import vn.viettechtrans.app.speech.Speaker
import vn.viettechtrans.app.speech.SpeechEngines
import vn.viettechtrans.app.text.TtsTextNormalizer
import vn.viettechtrans.app.turn.TurnRunner

/** Build facts shown in Settings → Giới thiệu and written into evidence. */
data class BuildInfo(
    val buildType: String,
    val versionName: String,
    val versionCode: Int,
    val gitSha: String,
    val gitDirty: Boolean,
)

/** Manual dependency container (no DI framework; see DR-01). */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val buildInfo = BuildInfo(
        buildType = BuildConfig.BUILD_TYPE,
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
        gitSha = BuildConfig.GIT_SHA,
        gitDirty = BuildConfig.GIT_DIRTY,
    )

    @Volatile
    private var coreMtCache: CoreMt? = null

    /** Looks for the MT package in assets (off the main thread). */
    suspend fun coreMt(): CoreMt = coreMtCache ?: withContext(Dispatchers.IO) {
        CoreMtProvider.open(appContext) { text -> textRules.literals.spans(text).map { it.start until it.end } }.also { coreMtCache = it }
    }

    /** Text-input rules of docs/contracts/mt_app_rule_policy_v1.md with the reviewed lexicon (assets/text). */
    val textRules: TextRules by lazy {
        TextRules(RuleLexicon.parse(appContext.assets.open(RuleLexicon.ASSET).use { it.readBytes().decodeToString() }))
    }

    /** Run-time evidence of R06 overrides, R08 lost terms and R12 rejected outputs. */
    val ruleLog = RuleLog(File(appContext.filesDir, "mt_rules"))

    val speechEngines = SpeechEngines(appContext)
    val mic = MicRecorder(appContext)
    val listener = Listener(speechEngines, mic)

    val ttsNormalizer: TtsTextNormalizer by lazy {
        val tsv = appContext.assets.open(TtsTextNormalizer.LEXICON_ASSET).use { it.readBytes().decodeToString() }
        TtsTextNormalizer(TtsTextNormalizer.parseLexicon(tsv))
    }
    val speaker: Speaker by lazy { Speaker(speechEngines, ttsNormalizer) }

    val translators = TranslatorRegistry { coreMt() }

    /** Saved conversations (text only), like a chat app's history. */
    val conversations = ConversationStore(File(appContext.filesDir, "conversations"))

    /** Listen → clean → translate → speak, shared by the Dịch and Hội thoại screens. */
    val turns: TurnRunner by lazy { TurnRunner(this) }
}
