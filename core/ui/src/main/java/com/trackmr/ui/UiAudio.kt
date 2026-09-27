package com.trackmr.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Tiny synthesized UI sounds (no assets): soft ticks for hover, a bright click for pinch
 * selections, a chime for opening panels. Played from a background thread.
 */
object UiAudio {
    private const val RATE = 44100
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "TrackMR-UiAudio").apply { priority = Thread.NORM_PRIORITY } }
    @Volatile var enabled = true
    @Volatile var volume = 0.5f
    private var lastHoverNs = 0L

    private val clickPcm by lazy { synth(0.045f) { t -> sin(2 * PI * 1800 * t) * exp(-t * 120) * 0.6 + sin(2 * PI * 900 * t) * exp(-t * 90) * 0.3 } }
    private val hoverPcm by lazy { synth(0.02f) { t -> sin(2 * PI * 2600 * t) * exp(-t * 260) * 0.18 } }
    private val openPcm by lazy { synth(0.28f) { t -> (sin(2 * PI * 660 * t) + sin(2 * PI * 990 * t) * 0.6 + sin(2 * PI * 1320 * t) * 0.3) * exp(-t * 11) * 0.25 } }
    private val closePcm by lazy { synth(0.18f) { t -> (sin(2 * PI * 880 * t * (1 - t)) * 0.4) * exp(-t * 16) } }
    private val keyPcm by lazy { synth(0.03f) { t -> sin(2 * PI * 1400 * t) * exp(-t * 180) * 0.35 } }

    private val tracks = HashMap<ShortArray, AudioTrack>()

    private fun synth(seconds: Float, f: (Double) -> Double): ShortArray {
        val n = (RATE * seconds).toInt()
        return ShortArray(n) { i ->
            val t = i.toDouble() / RATE
            (f(t).coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
        }
    }

    private fun play(pcm: () -> ShortArray) {
        if (!enabled) return
        exec.execute {
            try {
                val data = pcm()
                val track = tracks.getOrPut(data) {
                    AudioTrack.Builder()
                        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                        .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setBufferSizeInBytes(data.size * 2)
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build().also { it.write(data, 0, data.size) }
                }
                track.setVolume(volume)
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
                track.reloadStaticData()
                track.play()
            } catch (_: Exception) { }
        }
    }

    fun click() = play { clickPcm }
    fun key() = play { keyPcm }
    fun open() = play { openPcm }
    fun close() = play { closePcm }
    fun hover() {
        val now = System.nanoTime()
        if (now - lastHoverNs < 60_000_000L) return
        lastHoverNs = now
        play { hoverPcm }
    }
}
