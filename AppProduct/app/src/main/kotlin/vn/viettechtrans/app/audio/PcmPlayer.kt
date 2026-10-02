package vn.viettechtrans.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.max

/**
 * Streams float PCM (mono) from TTS to the speaker. [write] blocks while the
 * buffer is full; call it from the synthesis thread. [stopNow] can be called
 * from any thread and makes pending writes return.
 */
class PcmPlayer {
    private var track: AudioTrack? = null
    private var framesWritten = 0L

    @Volatile
    var stopped = false
        private set

    fun open(sampleRate: Int) {
        close()
        stopped = false
        framesWritten = 0
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(max(minBuffer, sampleRate * 4 / 2)) // >= 0.5 s
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.play() }
    }

    /** Returns false once [stopNow] was called. */
    fun write(samples: FloatArray): Boolean {
        val t = track ?: return false
        if (stopped) return false
        var offset = 0
        while (offset < samples.size && !stopped) {
            val n = t.write(samples, offset, samples.size - offset, AudioTrack.WRITE_BLOCKING)
            if (n <= 0) return false
            offset += n
        }
        framesWritten += offset
        return !stopped
    }

    /** Blocks until everything written has been played (or [stopNow]). */
    fun drain(sampleRate: Int) {
        val t = track ?: return
        val deadline = System.currentTimeMillis() + framesWritten * 1000 / sampleRate + 1_000
        while (!stopped && t.playbackHeadPosition < framesWritten && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
    }

    fun stopNow() {
        stopped = true
        track?.let { runCatching { it.pause(); it.flush() } }
    }

    fun close() {
        track?.let { runCatching { it.stop() }; it.release() }
        track = null
    }
}
