package com.elchanan.rhythm.engine

import kotlin.math.max
import kotlin.math.min

/**
 * Pulls a spectrogram apart into what is pitched and what is struck, and finds
 * the beat in what is struck.
 *
 * Everything the analyser measures is currently measured on the whole mix, and
 * the two halves of a mix want opposite things. Harmony wants the drums gone:
 * a snare is broadband noise smeared across every pitch class at once, and it
 * lands in the chroma as a little bit of all twelve, which is exactly the
 * evidence a key detector is trying to weigh. Rhythm wants the opposite - a
 * held chord is not an onset, and a sustained note drifting in and out of tune
 * reads as one.
 *
 * Separating them first makes both measurements cleaner, and it is the single
 * cheapest accuracy win available without a model: no new dependencies, no
 * download, and one extra pass over a spectrogram that has already been
 * computed.
 *
 * The method is median filtering (Fitzgerald, 2010), which is the standard one
 * and holds up well. A harmonic partial is a horizontal ridge - steady in
 * frequency, long in time - so a median along time keeps it and erases
 * transients. A percussive hit is a vertical ridge - brief, spread across
 * frequency - so a median along frequency keeps it and erases the sustained
 * tones. Each bin is then assigned in proportion to which of the two it
 * resembles, rather than being handed to one of them outright, because a real
 * bin is usually some of both.
 */
object Separation {

    data class Split(
        /** Magnitudes with percussion suppressed, for harmony and key. */
        val harmonic: Array<DoubleArray>,
        /** Magnitudes with sustained tones suppressed, for onsets and tempo. */
        val percussive: Array<DoubleArray>
    )

    /**
     * @param spectrogram frames by bins, magnitudes.
     * @param timeRadius median half-window along time. Wider is a stronger
     *   claim that a partial must be sustained to count as harmonic; about
     *   170ms at the analyser's hop is the usual choice.
     * @param freqRadius median half-window along frequency.
     * @param power how sharply a bin is assigned. 2 is the standard soft mask;
     *   higher approaches a hard decision and starts to sound like artefacts in
     *   anything reconstructed, though nothing here is reconstructed.
     */
    fun split(
        spectrogram: Array<DoubleArray>,
        timeRadius: Int = 8,
        freqRadius: Int = 8,
        power: Double = 2.0
    ): Split {
        val frames = spectrogram.size
        if (frames == 0) return Split(spectrogram, spectrogram)
        val bins = spectrogram[0].size

        val harmonicEstimate = medianAlongTime(spectrogram, timeRadius)
        val percussiveEstimate = medianAlongFrequency(spectrogram, freqRadius)

        val harmonic = Array(frames) { DoubleArray(bins) }
        val percussive = Array(frames) { DoubleArray(bins) }

        val isSquare = power == 2.0
        for (t in 0 until frames) {
            for (f in 0 until bins) {
                val hEst = harmonicEstimate[t][f]
                val pEst = percussiveEstimate[t][f]
                val h = if (isSquare) hEst * hEst else Math.pow(hEst, power)
                val p = if (isSquare) pEst * pEst else Math.pow(pEst, power)
                val total = h + p
                if (total <= 1e-12) continue
                val value = spectrogram[t][f]
                harmonic[t][f] = value * (h / total)
                percussive[t][f] = value * (p / total)
            }
        }
        return Split(harmonic, percussive)
    }

    private fun medianAlongTime(s: Array<DoubleArray>, radius: Int): Array<DoubleArray> {
        val frames = s.size
        val bins = s[0].size
        val out = Array(frames) { DoubleArray(bins) }
        val window = DoubleArray(2 * radius + 1)
        val copy = DoubleArray(2 * radius + 1)
        for (f in 0 until bins) {
            for (t in 0 until frames) {
                var n = 0
                val from = max(0, t - radius)
                val to = min(frames - 1, t + radius)
                for (i in from..to) {
                    window[n++] = s[i][f]
                }
                out[t][f] = fastMedianOf(window, copy, n)
            }
        }
        return out
    }

    private fun medianAlongFrequency(s: Array<DoubleArray>, radius: Int): Array<DoubleArray> {
        val frames = s.size
        val bins = s[0].size
        val out = Array(frames) { DoubleArray(bins) }
        val window = DoubleArray(2 * radius + 1)
        val copy = DoubleArray(2 * radius + 1)
        for (t in 0 until frames) {
            val row = s[t]
            for (f in 0 until bins) {
                var n = 0
                val from = max(0, f - radius)
                val to = min(bins - 1, f + radius)
                for (i in from..to) {
                    window[n++] = row[i]
                }
                out[t][f] = fastMedianOf(window, copy, n)
            }
        }
        return out
    }

    /**
     * Median of the first [n] entries. Sorts in-place into [copy] without any
     * heap allocations, saving millions of objects and Quicksort calls per song.
     */
    private fun fastMedianOf(buffer: DoubleArray, copy: DoubleArray, n: Int): Double {
        if (n == 0) return 0.0
        if (n == 1) return buffer[0]
        System.arraycopy(buffer, 0, copy, 0, n)
        for (i in 1 until n) {
            val v = copy[i]
            var j = i - 1
            while (j >= 0 && copy[j] > v) {
                copy[j + 1] = copy[j]
                j--
            }
            copy[j + 1] = v
        }
        val mid = n / 2
        return if (n % 2 == 1) copy[mid] else (copy[mid - 1] + copy[mid]) * 0.5
    }
}

/**
 * Finds where the beats actually fall, rather than only how fast they come.
 *
 * Tempo alone says a track is 120 BPM. It does not say where one is, and every
 * measurement that ought to be taken per beat - what chord is sounding, how the
 * energy moves across a bar - is instead taken every 23 milliseconds, landing
 * wherever it lands. Averaging chroma between beats rather than over a fixed
 * grid is what makes a chord estimate stable, because a chord is a thing that
 * lasts exactly one beat or two.
 *
 * The tracker is Ellis's dynamic programming beat tracker (2007): score every
 * possible set of beat positions by how well each lands on an onset and how
 * evenly they are spaced, and take the best scoring set. It is a small
 * algorithm with a clean guarantee - the result is globally optimal for the
 * objective, not a greedy walk that can be led astray by one loud handclap.
 */
object BeatTracker {

    /**
     * @param onsetEnvelope one value per frame, higher meaning more onset-like.
     * @param framesPerSecond how the envelope maps back to time.
     * @param bpm the tempo estimate to lock the spacing to.
     * @param tightness how strongly spacing is enforced against onset strength.
     *   100 is the published default and behaves well on produced music.
     * @return frame indices of the beats, in order.
     */
    fun track(
        onsetEnvelope: DoubleArray,
        framesPerSecond: Double,
        bpm: Double,
        tightness: Double = 100.0
    ): IntArray {
        val n = onsetEnvelope.size
        if (n < 4 || bpm <= 1.0 || framesPerSecond <= 0.0) return IntArray(0)

        val period = (framesPerSecond * 60.0 / bpm)
        if (period < 1.0 || period > n / 2.0) return IntArray(0)

        val local = normalise(onsetEnvelope)

        // score[i] is the best total score of a beat sequence ending at i, and
        // previous[i] remembers which beat came before it so the winning
        // sequence can be walked back at the end.
        val score = DoubleArray(n)
        val previous = IntArray(n) { -1 }

        val searchFrom = (period * 0.5).toInt().coerceAtLeast(1)
        val searchTo = (period * 2.0).toInt().coerceAtLeast(searchFrom + 1)
        val penalties = DoubleArray(searchTo + 1)
        for (back in searchFrom..searchTo) {
            val ratio = Math.log(back / period)
            penalties[back] = -tightness * ratio * ratio
        }

        for (i in 0 until n) {
            var best = Double.NEGATIVE_INFINITY
            var bestIndex = -1
            for (back in searchFrom..searchTo) {
                val j = i - back
                if (j < 0) break
                val candidate = score[j] + penalties[back]
                if (candidate > best) {
                    best = candidate
                    bestIndex = j
                }
            }
            if (bestIndex < 0) {
                score[i] = local[i]
            } else {
                score[i] = local[i] + best
                previous[i] = bestIndex
            }
        }

        // Start from the best ending in the final stretch, not the global best,
        // so the sequence runs to the end of the track rather than stopping at
        // whichever moment happened to be loudest.
        var end = -1
        var bestEnd = Double.NEGATIVE_INFINITY
        val tailFrom = max(0, n - (period * 2).toInt())
        for (i in tailFrom until n) {
            if (score[i] > bestEnd) {
                bestEnd = score[i]
                end = i
            }
        }
        if (end < 0) return IntArray(0)

        val reversed = ArrayList<Int>(n / max(1, period.toInt()) + 2)
        var cursor = end
        while (cursor >= 0) {
            reversed.add(cursor)
            cursor = previous[cursor]
        }
        reversed.reverse()
        return reversed.toIntArray()
    }

    /** Centres and scales the envelope so tightness means the same on any track. */
    private fun normalise(values: DoubleArray): DoubleArray {
        val mean = values.average()
        var variance = 0.0
        for (v in values) {
            val d = v - mean
            variance += d * d
        }
        val sd = Math.sqrt(variance / max(1, values.size))
        if (sd < 1e-9) return DoubleArray(values.size)
        return DoubleArray(values.size) { (values[it] - mean) / sd }
    }

    /**
     * Averages a per-frame feature between consecutive beats.
     *
     * This is the point of tracking beats at all: one value per beat, aligned
     * to where the music actually changes, instead of a value every 23ms
     * aligned to nothing.
     */
    fun synchronise(
        perFrame: Array<DoubleArray>,
        beats: IntArray
    ): Array<DoubleArray> {
        if (beats.size < 2 || perFrame.isEmpty()) return emptyArray()
        val width = perFrame[0].size
        val out = ArrayList<DoubleArray>(beats.size - 1)
        for (b in 0 until beats.size - 1) {
            val from = beats[b].coerceIn(0, perFrame.size - 1)
            val to = min(beats[b + 1], perFrame.size)
            if (to <= from) continue
            val acc = DoubleArray(width)
            for (t in from until to) {
                val row = perFrame[t]
                for (f in 0 until width) acc[f] += row[f]
            }
            val count = (to - from).toDouble()
            for (f in 0 until width) acc[f] /= count
            out.add(acc)
        }
        return out.toTypedArray()
    }
}
