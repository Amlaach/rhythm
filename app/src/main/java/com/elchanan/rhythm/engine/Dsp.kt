package com.elchanan.rhythm.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Everything numeric that the audio analyser needs, written from scratch so
 * the app pulls in no DSP dependency at all.
 */

/** In place iterative radix-2 FFT with cached twiddle factors. */
class Fft(private val n: Int) {

    private val cosTable = DoubleArray(n / 2)
    private val sinTable = DoubleArray(n / 2)

    init {
        require(n > 0 && (n and (n - 1)) == 0) { "FFT size must be a power of two" }
        for (i in 0 until n / 2) {
            cosTable[i] = cos(2.0 * PI * i / n)
            sinTable[i] = sin(2.0 * PI * i / n)
        }
    }

    fun transform(re: DoubleArray, im: DoubleArray) {
        // bit reversal permutation
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
            var k = n / 2
            while (k <= j) {
                j -= k
                k /= 2
            }
            j += k
        }
        // butterflies
        var len = 2
        while (len <= n) {
            val step = n / len
            val half = len / 2
            var i = 0
            while (i < n) {
                for (m in 0 until half) {
                    val idx = m * step
                    val wr = cosTable[idx]
                    val wi = -sinTable[idx]
                    val a = i + m
                    val b = a + half
                    val tr = re[b] * wr - im[b] * wi
                    val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                }
                i += len
            }
            len = len shl 1
        }
    }
}

object Dsp {

    fun hannWindow(size: Int): DoubleArray =
        DoubleArray(size) { 0.5 - 0.5 * cos(2.0 * PI * it / (size - 1)) }

    fun hzToMel(hz: Double): Double = 2595.0 * log10(1.0 + hz / 700.0)

    fun melToHz(mel: Double): Double = 700.0 * (Math.pow(10.0, mel / 2595.0) - 1.0)

    /**
     * Triangular mel filterbank as a list of (startBin, weights) pairs, so the
     * inner loop only touches the bins a filter actually covers.
     */
    fun melFilterBank(
        bands: Int,
        fftSize: Int,
        sampleRate: Int,
        lowHz: Double = 40.0,
        highHz: Double = 8000.0
    ): Array<Pair<Int, DoubleArray>> {
        val nyquist = sampleRate / 2.0
        val top = minOf(highHz, nyquist - 1.0)
        val lowMel = hzToMel(lowHz)
        val highMel = hzToMel(top)
        val points = DoubleArray(bands + 2) { melToHz(lowMel + (highMel - lowMel) * it / (bands + 1)) }
        val binOf = { hz: Double -> (hz * fftSize / sampleRate).toInt().coerceIn(0, fftSize / 2) }

        return Array(bands) { b ->
            val left = binOf(points[b])
            val center = binOf(points[b + 1])
            val right = binOf(points[b + 2]).coerceAtLeast(center + 1)
            val width = right - left
            val weights = DoubleArray(max(1, width)) { i ->
                val bin = left + i
                when {
                    bin < center -> if (center == left) 1.0 else (bin - left).toDouble() / (center - left)
                    else -> if (right == center) 1.0 else (right - bin).toDouble() / (right - center)
                }.coerceAtLeast(0.0)
            }
            left to weights
        }
    }

    /** DCT-II, used to turn log mel energies into MFCCs. */
    fun dct(input: DoubleArray, coefficients: Int): DoubleArray {
        val n = input.size
        return DoubleArray(coefficients) { k ->
            var sum = 0.0
            for (i in 0 until n) sum += input[i] * cos(PI * k * (i + 0.5) / n)
            sum / n
        }
    }

    fun mean(values: DoubleArray): Double = if (values.isEmpty()) 0.0 else values.sum() / values.size

    fun stdDev(values: DoubleArray): Double {
        if (values.size < 2) return 0.0
        val m = mean(values)
        var acc = 0.0
        for (v in values) acc += (v - m) * (v - m)
        return sqrt(acc / (values.size - 1))
    }

    /**
     * Autocorrelation of an onset envelope over a lag range, normalised so the
     * result is comparable between songs of different loudness.
     */
    fun autocorrelation(signal: DoubleArray, minLag: Int, maxLag: Int): DoubleArray {
        val out = DoubleArray(maxLag + 1)
        val n = signal.size
        var energy = 0.0
        for (v in signal) energy += v * v
        if (energy < 1e-12) return out
        for (lag in minLag..maxLag) {
            if (lag >= n) break
            var sum = 0.0
            for (i in 0 until n - lag) sum += signal[i] * signal[i + lag]
            out[lag] = sum / energy
        }
        return out
    }

    /** Subtract a moving average and half wave rectify - standard onset cleanup. */
    fun rectifyAgainstLocalMean(signal: DoubleArray, radius: Int): DoubleArray {
        val n = signal.size
        val out = DoubleArray(n)
        for (i in 0 until n) {
            val from = max(0, i - radius)
            val to = minOf(n - 1, i + radius)
            var sum = 0.0
            for (k in from..to) sum += signal[k]
            val local = sum / (to - from + 1)
            out[i] = max(0.0, signal[i] - local)
        }
        return out
    }

    fun geometricMean(values: DoubleArray): Double {
        if (values.isEmpty()) return 0.0
        var logSum = 0.0
        for (v in values) logSum += ln(v + 1e-12)
        return exp(logSum / values.size)
    }
}
