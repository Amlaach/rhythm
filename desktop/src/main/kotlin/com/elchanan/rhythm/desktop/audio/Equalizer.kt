package com.elchanan.rhythm.desktop.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * A six band equaliser, written rather than asked for.
 *
 * On the phone there is nothing to write: android.media.audiofx.Equalizer
 * attaches to an audio session and the operating system does the work. A JVM
 * has no such thing - javax.sound hands over raw PCM and a line to pour it
 * into - so the filtering has to happen here, between the decoder and the
 * speaker, on every sample.
 *
 * Six peaking biquads per channel, coefficients from the RBJ cookbook. State
 * is carried between buffers, which is the whole reason this is an object
 * with a lifetime rather than a function: a filter restarted every buffer
 * would click at every boundary, sixty times a second.
 */
class Equalizer {

    companion object {
        /** Centre frequencies, the same six the phone's preset bands use. */
        val FREQUENCIES = doubleArrayOf(60.0, 170.0, 350.0, 1000.0, 3500.0, 10000.0)

        /** How far a band can be moved, in decibels. */
        const val MAX_DB = 12f

        /**
         * Bandwidth of each filter.
         *
         * A little under one octave: wide enough that six bands cover the
         * spectrum without gaps between them, narrow enough that moving one
         * does not drag its neighbours along with it.
         */
        private const val Q = 1.1

        /** How fast a moved slider is allowed to take effect, in dB per buffer. */
        private const val GLIDE_DB = 0.35f
    }

    @Volatile
    var enabled: Boolean = false

    private val target = FloatArray(FREQUENCIES.size)
    private val applied = FloatArray(FREQUENCIES.size)

    private var sampleRate = 0
    private var channels = 0

    // Coefficients per band, and two samples of history per band per channel.
    private var b0 = DoubleArray(0)
    private var b1 = DoubleArray(0)
    private var b2 = DoubleArray(0)
    private var a1 = DoubleArray(0)
    private var a2 = DoubleArray(0)
    private var x1 = Array(0) { DoubleArray(0) }
    private var x2 = Array(0) { DoubleArray(0) }
    private var y1 = Array(0) { DoubleArray(0) }
    private var y2 = Array(0) { DoubleArray(0) }

    fun gain(band: Int): Float = target.getOrElse(band) { 0f }

    fun setGain(band: Int, db: Float) {
        if (band !in target.indices) return
        target[band] = db.coerceIn(-MAX_DB, MAX_DB)
    }

    fun reset() {
        for (i in target.indices) target[i] = 0f
    }

    /**
     * Sizes the filter for a stream that is about to play.
     *
     * Called per track rather than once, because the sample rate and the
     * channel count are properties of the file and both change the
     * coefficients.
     */
    fun prepare(sampleRate: Int, channels: Int) {
        this.sampleRate = sampleRate
        this.channels = channels.coerceAtLeast(1)
        val n = FREQUENCIES.size
        b0 = DoubleArray(n); b1 = DoubleArray(n); b2 = DoubleArray(n)
        a1 = DoubleArray(n); a2 = DoubleArray(n)
        x1 = Array(n) { DoubleArray(this.channels) }
        x2 = Array(n) { DoubleArray(this.channels) }
        y1 = Array(n) { DoubleArray(this.channels) }
        y2 = Array(n) { DoubleArray(this.channels) }
        for (i in 0 until n) {
            applied[i] = target[i]
            coefficients(i, applied[i])
        }
    }

    /**
     * Filters one buffer of 16 bit little endian PCM, in place.
     *
     * In place because the alternative is allocating a second buffer sixty
     * times a second for the length of every song.
     */
    fun process(buffer: ByteArray, length: Int) {
        if (!enabled || sampleRate <= 0 || channels <= 0) return
        glide()

        val frameBytes = channels * 2
        val frames = length / frameBytes
        var at = 0
        for (f in 0 until frames) {
            for (c in 0 until channels) {
                val lo = buffer[at].toInt() and 0xFF
                val hi = buffer[at + 1].toInt()
                var sample = ((hi shl 8) or lo).toDouble()

                for (band in FREQUENCIES.indices) {
                    val x0 = sample
                    val out = b0[band] * x0 + b1[band] * x1[band][c] + b2[band] * x2[band][c] -
                        a1[band] * y1[band][c] - a2[band] * y2[band][c]
                    x2[band][c] = x1[band][c]
                    x1[band][c] = x0
                    y2[band][c] = y1[band][c]
                    y1[band][c] = out
                    sample = out
                }

                // Boosting can take a sample past what sixteen bits hold, and
                // a wrapped integer is not a quiet fault - it is a crack loud
                // enough to hear across the room.
                val clipped = sample.coerceIn(-32768.0, 32767.0).toInt()
                buffer[at] = (clipped and 0xFF).toByte()
                buffer[at + 1] = ((clipped shr 8) and 0xFF).toByte()
                at += 2
            }
        }
    }

    /**
     * Walks the applied gains towards where the sliders are.
     *
     * A band that jumped straight to its new value would rebuild its filter
     * mid-waveform and put a step in the signal, which is audible. A third of
     * a decibel per buffer reaches any setting in well under a second and
     * cannot be heard arriving.
     */
    private fun glide() {
        for (i in target.indices) {
            val gap = target[i] - applied[i]
            if (abs(gap) < 0.001f) continue
            applied[i] += gap.coerceIn(-GLIDE_DB, GLIDE_DB)
            coefficients(i, applied[i])
        }
    }

    /** Peaking EQ, from the Audio EQ Cookbook, normalised by a0. */
    private fun coefficients(band: Int, db: Float) {
        val a = 10.0.pow(db / 40.0)
        val w0 = 2.0 * PI * FREQUENCIES[band] / sampleRate
        val alpha = sin(w0) / (2.0 * Q)
        val cosW0 = cos(w0)

        val a0 = 1.0 + alpha / a
        b0[band] = (1.0 + alpha * a) / a0
        b1[band] = (-2.0 * cosW0) / a0
        b2[band] = (1.0 - alpha * a) / a0
        a1[band] = (-2.0 * cosW0) / a0
        a2[band] = (1.0 - alpha / a) / a0
    }
}
