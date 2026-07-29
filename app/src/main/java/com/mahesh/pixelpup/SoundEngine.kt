package com.mahesh.pixelpup

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Generates every bark/whine/yip at runtime as 16-bit PCM and plays it
 * through a single, reused AudioTrack. No sound assets ship with the app.
 */
class SoundEngine(context: Context) {

    private val sampleRate = 22050
    private var quietMode = false
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val minBufferSize: Int = run {
        val size = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (size > 0) size else 4096
    }

    private val audioTrack: AudioTrack = AudioTrack(
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build(),
        AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build(),
        minBufferSize * 4,
        AudioTrack.MODE_STREAM,
        AudioManager.AUDIO_SESSION_ID_GENERATE
    )

    @Volatile
    private var playThread: Thread? = null

    init {
        try {
            audioTrack.play()
        } catch (e: Exception) {
            // Non-fatal: sound is a nice-to-have, never a crash source.
        }
    }

    fun setQuietMode(enabled: Boolean) {
        quietMode = enabled
    }

    fun play(type: SoundType) {
        if (quietMode) return
        val ringerMode = audioManager.ringerMode
        if (ringerMode == AudioManager.RINGER_MODE_SILENT ||
            ringerMode == AudioManager.RINGER_MODE_VIBRATE
        ) {
            return
        }

        val samples = when (type) {
            SoundType.BARK, SoundType.DOUBLE_BARK -> generateBark()
            SoundType.WHINE -> generateWhine()
            SoundType.YIP -> generateYip()
        }

        playThread?.interrupt()
        val thread = Thread {
            try {
                audioTrack.write(samples, 0, samples.size)
                if (type == SoundType.DOUBLE_BARK) {
                    Thread.sleep(120)
                    audioTrack.write(samples, 0, samples.size)
                }
            } catch (e: Exception) {
                // Ignore: interrupted or device audio hiccup, non-fatal.
            }
        }
        playThread = thread
        thread.start()
    }

    fun release() {
        try {
            playThread?.interrupt()
            audioTrack.stop()
            audioTrack.release()
        } catch (e: Exception) {
            // Already released or never started successfully.
        }
    }

    private fun generateBark(): ShortArray {
        val durationSec = 0.09
        val n = (sampleRate * durationSec).toInt().coerceAtLeast(1)
        val out = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / sampleRate
            val progress = i.toDouble() / n
            val freq = 900.0 - 500.0 * progress
            val envelope = exp(-progress * 5.0)
            val noise = Random.nextDouble(-1.0, 1.0) * 0.4
            val tone = sin(2.0 * PI * freq * t)
            val sample = ((tone * 0.6 + noise) * envelope * Short.MAX_VALUE)
            out[i] = sample.toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    private fun generateWhine(): ShortArray {
        val durationSec = 0.6
        val n = (sampleRate * durationSec).toInt().coerceAtLeast(1)
        val out = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / sampleRate
            val progress = i.toDouble() / n
            val freq = 500.0 + 400.0 * sin(PI * progress)
            val envelope = sin(PI * progress).coerceAtLeast(0.0)
            val sample = sin(2.0 * PI * freq * t) * envelope * Short.MAX_VALUE * 0.5
            out[i] = sample.toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    private fun generateYip(): ShortArray {
        val durationSec = 0.05
        val n = (sampleRate * durationSec).toInt().coerceAtLeast(1)
        val out = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / sampleRate
            val progress = i.toDouble() / n
            val freq = 1400.0
            val envelope = exp(-progress * 8.0)
            val sample = sin(2.0 * PI * freq * t) * envelope * Short.MAX_VALUE * 0.6
            out[i] = sample.toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }
}
