package vn.viettechtrans.app.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import androidx.core.content.ContextCompat
import kotlin.math.max

/**
 * 16 kHz mono PCM16 capture with AudioSource.VOICE_RECOGNITION (Android CDD: no
 * noise suppression/AGC by default, so the STT model sees the raw signal).
 * Delivers 512-sample float frames (32 ms, the Silero VAD window) on its own thread.
 */
class MicRecorder(private val context: Context) {
    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME = 512
    }

    @Volatile
    private var running = false
    private var thread: Thread? = null

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /**
     * Starts capture; [onFrame] receives frames in order on the capture thread,
     * [onError] is called once if the microphone cannot be opened.
     */
    @SuppressLint("MissingPermission")
    fun start(onFrame: (FloatArray) -> Unit, onError: (String) -> Unit) {
        check(!running) { "MicRecorder already running" }
        if (!hasPermission()) {
            onError("Chưa có quyền micro")
            return
        }
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) {
            onError("Máy không hỗ trợ thu âm 16 kHz mono")
            return
        }
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minBuffer, SAMPLE_RATE / 5 * 2), // >= 200 ms
            )
        } catch (e: Exception) {
            onError("Không mở được micro: ${e.message}")
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            onError("Không mở được micro (đang bị ứng dụng khác dùng?)")
            return
        }
        running = true
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val pcm = ShortArray(FRAME)
            try {
                record.startRecording()
                while (running) {
                    var filled = 0
                    while (filled < FRAME && running) {
                        val n = record.read(pcm, filled, FRAME - filled)
                        if (n < 0) {
                            running = false
                            onError("Lỗi đọc micro ($n)")
                            break
                        }
                        filled += n
                    }
                    if (filled == FRAME) {
                        onFrame(FloatArray(FRAME) { pcm[it] / 32768f })
                    }
                }
            } catch (e: Exception) {
                onError("Lỗi micro: ${e.message}")
            } finally {
                runCatching { record.stop() }
                record.release()
                running = false
            }
        }, "audio-in").apply { start() }
    }

    /** Stops capture and waits for the capture thread to finish (no more frames after this returns). */
    fun stop() {
        running = false
        thread?.join(1_000)
        thread = null
    }
}
