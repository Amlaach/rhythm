package com.elchanan.rhythm.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * The mel spectrogram Discogs-EffNet was trained on, reproduced exactly.
 *
 * A model only understands the input it learned from. Essentia feeds EffNet
 * with its TensorflowInputMusiCNN front end, and anything that differs - a
 * different mel scale, filter height, log base - hands the model a picture it
 * has never seen, and it answers confidently and wrongly. So this is a
 * transcription of that front end, checked against Essentia's own output on
 * the same signal (see the reference fixture the model converter writes):
 *
 *  - 16 kHz mono, frames of 512 samples every 256
 *  - Hann window, magnitude spectrum, 257 bins
 *  - 96 triangular bands on the Slaney mel scale from 0 to 8000 Hz, triangles
 *    linear in Hz, each normalised to unit area, over the power spectrum
 *  - log10(1 + 10000 x)
 *
 * The model then reads patches of 128 frames, about two seconds.
 */
class MusicMel(
    /** Whether the first frame starts at the first sample or is centred on it. */
    private val startFromZero: Boolean = true,
    private val normalisation: Normalisation = Normalisation.UNIT_TRI
) {

    enum class Normalisation { UNIT_TRI, UNIT_SUM, UNIT_MAX }

    private val fft = Fft(FRAME)
    private val window = DoubleArray(FRAME) { 0.5 - 0.5 * cos(2.0 * PI * it / (FRAME - 1)) }
    private val filters: Array<Pair<Int, DoubleArray>> = bank()

    /** Every frame of the signal, [frames][96]. */
    fun frames(signal: FloatArray): Array<FloatArray> {
        val out = ArrayList<FloatArray>()
        var start = if (startFromZero) 0 else -FRAME / 2
        // Essentia's frame generator stops once a frame would begin past the end.
        val last = if (startFromZero) signal.size - FRAME else signal.size - 1
        val re = DoubleArray(FRAME)
        val im = DoubleArray(FRAME)
        val power = DoubleArray(BINS)
        while (start <= last) {
            for (i in 0 until FRAME) {
                val at = start + i
                re[i] = if (at in signal.indices) signal[at] * window[i] else 0.0
                im[i] = 0.0
            }
            fft.transform(re, im)
            for (k in 0 until BINS) power[k] = re[k] * re[k] + im[k] * im[k]
            val bands = FloatArray(BANDS)
            for (b in 0 until BANDS) {
                val (first, weights) = filters[b]
                var e = 0.0
                for (i in weights.indices) e += weights[i] * power[first + i]
                bands[b] = log10(1.0 + SCALE * e).toFloat()
            }
            out.add(bands)
            start += HOP
        }
        return out.toTypedArray()
    }

    /** Patches of [PATCH] frames every [PATCH_HOP], as the model reads them; the partial tail is dropped. */
    fun patches(frames: Array<FloatArray>, hop: Int = PATCH_HOP): List<Array<FloatArray>> {
        if (frames.size < PATCH) return emptyList()
        val count = (frames.size - PATCH) / hop + 1
        return List(count) { p -> Array(PATCH) { frames[p * hop + it] } }
    }

    private fun bank(): Array<Pair<Int, DoubleArray>> {
        val lowMel = slaney(0.0)
        val highMel = slaney(HIGH_HZ)
        val edges = DoubleArray(BANDS + 2) { unslaney(lowMel + (highMel - lowMel) * it / (BANDS + 1)) }
        val binHz = SAMPLE_RATE / 2.0 / (BINS - 1)
        return Array(BANDS) { b ->
            val f0 = edges[b]
            val f1 = edges[b + 1]
            val f2 = edges[b + 2]
            val weights = DoubleArray(BINS) { k ->
                val f = k * binHz
                when {
                    f > f0 && f <= f1 -> (f - f0) / (f1 - f0)
                    f > f1 && f < f2 -> (f2 - f) / (f2 - f1)
                    else -> 0.0
                }
            }
            when (normalisation) {
                Normalisation.UNIT_TRI -> {
                    val h = 2.0 / (f2 - f0)
                    for (k in weights.indices) weights[k] *= h
                }
                Normalisation.UNIT_SUM -> {
                    val s = weights.sum()
                    if (s > 0) for (k in weights.indices) weights[k] /= s
                }
                Normalisation.UNIT_MAX -> Unit
            }
            val first = weights.indexOfFirst { it > 0.0 }.coerceAtLeast(0)
            val lastNonZero = weights.indexOfLast { it > 0.0 }.coerceAtLeast(first)
            first to weights.copyOfRange(first, lastNonZero + 1)
        }
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val FRAME = 512
        const val HOP = 256
        const val BINS = FRAME / 2 + 1
        const val BANDS = 96
        const val PATCH = 128
        const val PATCH_HOP = 62
        private const val HIGH_HZ = 8000.0
        private const val SCALE = 10000.0

        // Slaney's mel scale: linear to 1 kHz, logarithmic above.
        private const val F_SP = 200.0 / 3.0
        private const val BREAK_HZ = 1000.0
        private const val BREAK_MEL = BREAK_HZ / F_SP
        private val LOG_STEP = ln(6.4) / 27.0

        fun slaney(hz: Double): Double =
            if (hz < BREAK_HZ) hz / F_SP else BREAK_MEL + ln(hz / BREAK_HZ) / LOG_STEP

        fun unslaney(mel: Double): Double =
            if (mel < BREAK_MEL) mel * F_SP else BREAK_HZ * kotlin.math.exp(LOG_STEP * (mel - BREAK_MEL))

        /** The deterministic test signal the model converter also builds. */
        fun testSignal(seconds: Int = 30): FloatArray = FloatArray(SAMPLE_RATE * seconds) { n ->
            val t = n.toDouble() / SAMPLE_RATE
            (0.30 * kotlin.math.sin(2 * PI * 220.0 * t) +
                0.20 * kotlin.math.sin(2 * PI * 660.0 * t) * (0.5 + 0.5 * kotlin.math.sin(2 * PI * 2.0 * t)) +
                0.10 * kotlin.math.sin(2 * PI * 3000.0 * t) +
                0.15 * kotlin.math.sin(2 * PI * (100.0 + 40.0 * t) * t)).toFloat()
        }

        internal fun rms(a: FloatArray): Double = sqrt(a.sumOf { it.toDouble() * it } / a.size)
    }
}
