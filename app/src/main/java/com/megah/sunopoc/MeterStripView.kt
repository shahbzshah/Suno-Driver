package com.megah.sunopoc

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import kotlin.random.Random

/**
 * The VU-meter strip from the Stitch design — alive, and synced to the
 * music as closely as the platform honestly allows.
 *
 * Suno plays takes through an <audio> element inside the WebView. The app
 * cannot tap that audio stream (browser security would mute playback if
 * it tried), so the driver polls the audio element itself ~5x/second:
 * whether it is playing and its exact playback position. The meter then
 * runs a beat grid at the channel's tempo, phase-locked to that real
 * playback clock — it dances while a song plays, pulses on the beat,
 * and settles to a gentle idle the moment the music pauses or stops.
 *
 * Tempo: 104 bpm for DeepSoundRealm (the channel's lane), 60 bpm slow
 * breathing for StillMind. Set via [setTempo].
 */
class MeterStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val green = 0xFF4DE082.toInt()
    private val amber = 0xFFF59E0B.toInt()
    private val dark = 0xFF3A352F.toInt()

    private val count = 28
    private val levels = FloatArray(count) { 0.25f }
    private val idleTargets = FloatArray(count) { 0.25f }

    @Volatile private var playing = false
    @Volatile private var songPositionSec = 0.0
    @Volatile private var positionStampNs = 0L
    @Volatile private var tempoBpm = 104.0

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            step()
            invalidate()
            handler.postDelayed(this, FRAME_MS)
        }
    }

    /** Called from the driver's beat reports (already on the UI thread). */
    fun setPlayback(isPlaying: Boolean, positionSec: Double) {
        playing = isPlaying
        songPositionSec = positionSec
        positionStampNs = System.nanoTime()
    }

    fun setTempo(bpm: Double) {
        tempoBpm = bpm
    }

    /** Estimated live position of the song, advanced from the last report. */
    private fun estimatedPosition(): Double {
        if (!playing || positionStampNs == 0L) return songPositionSec
        val elapsed = (System.nanoTime() - positionStampNs) / 1e9
        return songPositionSec + elapsed
    }

    private fun step() {
        if (playing) {
            val beatInterval = 60.0 / tempoBpm
            val pos = estimatedPosition()
            for (i in 0 until count) {
                // Each bar fires a fraction after its neighbour, so the
                // pulse travels across the strip like a real meter bridge.
                val phase = ((pos / beatInterval) - i * 0.018).mod(1.0)
                val pulse = Math.pow(1.0 - phase, 3.0).toFloat()
                val centreBias = 1f - kotlin.math.abs(i - count * 0.55f) / count
                val jitter = if (Random.nextFloat() < 0.06f) Random.nextFloat() * 0.12f else 0f
                val target = (0.14f + 0.86f * pulse * (0.5f + 0.5f * centreBias) + jitter)
                    .coerceIn(0.08f, 1f)
                levels[i] += (target - levels[i]) * BEAT_EASE
            }
        } else {
            // Idle: quiet breathing so the strip still feels alive, softly.
            for (i in 0 until count) {
                if (Random.nextFloat() < IDLE_RETARGET) {
                    idleTargets[i] = 0.12f + Random.nextFloat() * 0.28f
                }
                levels[i] += (idleTargets[i] - levels[i]) * IDLE_EASE
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        handler.post(tick)
    }

    override fun onDetachedFromWindow() {
        running = false
        handler.removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val gap = 3f * resources.displayMetrics.density
        val segW = (width - gap * (count - 1)) / count
        val greenEnd = (count * 0.55f).toInt()
        val amberEnd = (count * 0.82f).toInt()
        for (i in 0 until count) {
            paint.color = when {
                i < greenEnd -> green
                i < amberEnd -> amber
                else -> dark
            }
            val h = height * levels[i].coerceIn(0.05f, 1f)
            val left = i * (segW + gap)
            canvas.drawRoundRect(left, height - h, left + segW, height.toFloat(), 2f, 2f, paint)
        }
    }

    companion object {
        private const val FRAME_MS = 66L
        private const val BEAT_EASE = 0.45f
        private const val IDLE_EASE = 0.08f
        private const val IDLE_RETARGET = 0.05f
    }
}
