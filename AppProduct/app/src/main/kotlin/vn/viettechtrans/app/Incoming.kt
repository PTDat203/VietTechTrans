package vn.viettechtrans.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate

/** What other apps or the launcher hand to the app; each one opens the Dịch nhanh screen. */
sealed interface Incoming {
    /** Text selected in another app ("Dịch" in the selection menu) or shared to the app. */
    data class Text(val text: String) : Incoming

    /** A picture shared to the app: its text is read and translated (Dịch ảnh). */
    data class Image(val uri: Uri) : Incoming

    /** Launcher shortcut "Dịch ảnh": open the picture picker. */
    data object PickImage : Incoming

    /** Launcher shortcut "Dịch nhanh". */
    data object OpenTranslate : Incoming

    companion object {
        const val ACTION_QUICK_TRANSLATE = "vn.viettechtrans.app.action.QUICK_TRANSLATE"
        const val ACTION_IMAGE_TRANSLATE = "vn.viettechtrans.app.action.IMAGE_TRANSLATE"

        /** null for a plain launch (the conversation screen) or an intent without usable content. */
        fun from(intent: Intent?): Incoming? {
            intent ?: return null
            return when (intent.action) {
                Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.takeIf { it.isNotBlank() }?.let(::Text)
                Intent.ACTION_SEND -> {
                    val type = intent.type.orEmpty()
                    when {
                        type.startsWith("image/") -> stream(intent)?.let(::Image)
                        else -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }?.let(::Text)
                    }
                }
                ACTION_QUICK_TRANSLATE -> OpenTranslate
                ACTION_IMAGE_TRANSLATE -> PickImage
                else -> null
            }
        }

        @Suppress("DEPRECATION")
        private fun stream(intent: Intent): Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }
}

/** The last [Incoming] not yet handled; the activity posts, the Dịch nhanh screen takes it once. */
class IncomingContent {
    private val _pending = MutableStateFlow<Incoming?>(null)
    val pending: StateFlow<Incoming?> = _pending

    fun post(item: Incoming) {
        _pending.value = item
    }

    fun take(): Incoming? = _pending.getAndUpdate { null }
}
