package vn.viettechtrans.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Speed of the voice reading a translation; a slower voice is easier to follow in a conversation. */
enum class SpeechSpeed(val factor: Float) { SLOW(0.8f), NORMAL(1.0f), FAST(1.2f) }

/** Small user choices kept on the device (SharedPreferences "settings"). */
class AppPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _speechSpeed = MutableStateFlow(
        runCatching { SpeechSpeed.valueOf(prefs.getString(KEY_SPEECH_SPEED, null) ?: "") }.getOrDefault(SpeechSpeed.NORMAL),
    )
    val speechSpeed: StateFlow<SpeechSpeed> = _speechSpeed

    fun setSpeechSpeed(speed: SpeechSpeed) {
        prefs.edit().putString(KEY_SPEECH_SPEED, speed.name).apply()
        _speechSpeed.value = speed
    }

    private companion object {
        const val KEY_SPEECH_SPEED = "speech_speed"
    }
}
