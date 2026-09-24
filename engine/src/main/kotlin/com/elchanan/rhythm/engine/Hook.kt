package com.elchanan.rhythm.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Where a song's chorus is: the moment a taste of it should start from.
 *
 * A chorus is the part a song keeps coming back to, so it is looked for as
 * the stretch of about twenty seconds whose harmony repeats elsewhere in the
 * song most closely, leaning a little towards the louder stretches, since a
 * chorus is usually where the song opens up. When nothing repeats clearly -
 * a through-composed song, a slow one with no chorus - the loudest stretch of
 * the middle of the song stands in, which is still a truer taste of it than
 * its first seconds.
 *
 * The audio is read as it streams ([Frames]): a short window at a time is
 * reduced to its twelve pitch classes and its loudness, every half second,
 * and nothing else is kept, so a six minute song costs a few kilobytes and
 * not the tens of megabytes its samples would.
 */
object Hook {

    /** The rate the audio is brought down to before it is read. */
    private const val RATE = 11025

    private const val WINDOW = 2048

    /** Seconds between readings. */
    const val HOP_SECONDS = 0.5

    /** How long a taste is, and so how long a stretch is compared. */
    const val CLIP_SECONDS = 20.0

    /** The twelve pitch classes and the loudness, every [HOP_SECONDS]. */
    class FrameSet(val chroma: List<DoubleArray>, val energy: DoubleArray)

    /**
     * Reads mono audio as it arrives, at whatever rate it comes in.
     * [add] as many times as there are chunks; [build] at the end.
     */
    class Frames(sourceRate: Int) {
        private val step = maxOf(1, (sourceRate.toDouble() / RATE).roundToInt())
        private val rate = sourceRate.toDouble() / step
        private val hop = (rate * HOP_SECONDS).roundToInt().coerceAtLeast(1)
        private val ring = DoubleArray(WINDOW)
        private var written = 0L
        private var sinceHop = 0
        private var acc = 0.0
        private var accCount = 0
        private var hopEnergy = 0.0
        private val fft = Fft(WINDOW)
        private val window = DoubleArray(WINDOW) { 0.5 - 0.5 * cos(2 * PI * it / (WINDOW - 1)) }
        private val pitchClass = IntArray(WINDOW / 2) { bin ->
            val hz = bin * rate / WINDOW
            if (hz < 65.0 || hz > 2100.0) -1 else (((12 * log2(hz / 440.0)).roundToInt() + 69) % 12 + 12) % 12
        }
        private val chroma = ArrayList<DoubleArray>()
        private val energy = ArrayList<Double>()

        fun add(samples: FloatArray, count: Int = samples.size) {
            for (i in 0 until count) {
                // Brought down by averaging: a crude low-pass, which is all
                // pitch classes below two kilohertz need.
                acc += samples[i]
                accCount++
                if (accCount < step) continue
                val v = acc / accCount
                acc = 0.0
                accCount = 0
                ring[(written % WINDOW).toInt()] = v
                written++
                hopEnergy += v * v
                if (++sinceHop >= hop) {
                    sinceHop = 0
                    read()
                }
            }
        }

        private fun read() {
            val re = DoubleArray(WINDOW)
            val im = DoubleArray(WINDOW)
            val start = written - WINDOW
            for (i in 0 until WINDOW) {
                val at = start + i
                re[i] = if (at < 0) 0.0 else ring[(at % WINDOW).toInt()] * window[i]
            }
            fft.transform(re, im)
            val c = DoubleArray(12)
            for (bin in 1 until WINDOW / 2) {
                val pc = pitchClass[bin]
                if (pc >= 0) c[pc] += sqrt(re[bin] * re[bin] + im[bin] * im[bin])
            }
            chroma.add(c)
            energy.add(sqrt(hopEnergy / hop))
            hopEnergy = 0.0
        }

        fun build(): FrameSet = FrameSet(chroma.toList(), energy.toDoubleArray())
    }

    /**
     * The second the taste should start at, or null when there is too little
     * of the song to tell - the caller then starts a third of the way in.
     */
    fun find(frames: FrameSet): Double? {
        val n = frames.chroma.size
        val w = (CLIP_SECONDS / HOP_SECONDS).toInt()
        if (n < w * 3) return null

        // Each reading as a direction, so a louder chord is the same chord.
        val c = frames.chroma.map { v ->
            val norm = sqrt(v.sumOf { it * it })
            if (norm < 1e-9) DoubleArray(12) else DoubleArray(12) { v[it] / norm }
        }
        // Frame against frame, summed along each diagonal, so that how alike
        // two stretches are is one subtraction.
        val sums = Array(n + 1) { FloatArray(n + 1) }
        for (i in 0 until n) {
            for (j in 0 until n) {
                var dot = 0.0
                for (k in 0 until 12) dot += c[i][k] * c[j][k]
                sums[i + 1][j + 1] = sums[i][j] + dot.toFloat()
            }
        }
        fun alike(t: Int, u: Int): Double = (sums[t + w][u + w] - sums[t][u]).toDouble() / w

        val loud = frames.energy
        val mean = loud.average().takeIf { it > 1e-12 } ?: return null
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + loud[i] / mean
        fun loudness(t: Int) = (prefix[t + w] - prefix[t]) / w

        // Not the first tenth of the song or the last stretch: an intro and
        // an ending repeat each other often enough and are not the song.
        val first = (n * 0.1).toInt()
        val last = n - w - (n * 0.05).toInt()
        if (last <= first) return null
        var best = -1.0
        var bestAt = -1
        var bestRepeat = 0.0
        for (t in first..last) {
            // The two closest other occurrences, each at least a stretch away
            // from this one and from each other.
            var top = 0.0
            var topAt = -1
            for (u in 0..n - w) if (kotlin.math.abs(u - t) >= w) {
                val a = alike(t, u)
                if (a > top) { top = a; topAt = u }
            }
            var second = 0.0
            for (u in 0..n - w) if (kotlin.math.abs(u - t) >= w && kotlin.math.abs(u - topAt) >= w) {
                val a = alike(t, u)
                if (a > second) second = a
            }
            val repeat = (top + second) / 2
            val score = repeat + 0.15 * ln(1.0 + loudness(t))
            if (score > best) { best = score; bestAt = t; bestRepeat = repeat }
        }
        if (bestAt < 0) return null
        // Every stretch of a chorus longer than a taste matches as well as its
        // start does, and so does every stretch of a verse and chorus that
        // come back together, so the one picked can be well inside the part.
        // Its start is looked for a little around it: the moment the song
        // opens up - which is how a chorus arrives - from where the taste
        // still comes back elsewhere and is no quieter. Not the moment the
        // harmony changes most: inside a chorus every change of chord is a
        // bigger change than the one into it. Where the song never gets
        // clearly louder, the stretch picked stays.
        fun startOf(at: Int): Int {
            val k = (EDGE_SECONDS / HOP_SECONDS).toInt()
            fun rise(s: Int): Double {
                var before = 0.0
                var after = 0.0
                for (i in s - k until s) before += loud[i]
                for (i in s until s + k) after += loud[i]
                return ln((after + 1e-9) / (before + 1e-9))
            }
            fun repeats(s: Int): Boolean {
                for (u in 0..n - w) if (kotlin.math.abs(u - s) >= w && alike(s, u) >= REPEATS) return true
                return false
            }
            val from = maxOf(first, k, at - (BACK_SECONDS / HOP_SECONDS).toInt())
            val to = minOf(last, n - k, at + (AHEAD_SECONDS / HOP_SECONDS).toInt())
            val floor = loudness(at) * 0.95
            var best = at
            var bestRise = RISE
            for (s in from..to) {
                val r = rise(s)
                if (r > bestRise && loudness(s) >= floor && repeats(s)) { bestRise = r; best = s }
            }
            return best
        }
        val at = if (bestRepeat >= REPEATS) {
            startOf(bestAt)
        } else {
            // Nothing comes back clearly: the loudest stretch of the middle.
            (first..last).maxByOrNull { loudness(it) } ?: bestAt
        }
        // A beat early, so the taste does not start on the chorus's first word.
        return maxOf(0.0, at * HOP_SECONDS - 0.5)
    }

    /** Seconds on each side of a moment that tell whether the song opens up there. */
    private const val EDGE_SECONDS = 4.0

    /** How far back from the stretch picked the start of its part can be. */
    private const val BACK_SECONDS = 10.0

    private const val AHEAD_SECONDS = 3.0

    /** How much louder, as a log of the ratio, the song has to get for a part to be taken to start there. */
    private const val RISE = 0.15

    /** How alike two stretches have to be, on average, to count as the same music coming back. */
    private const val REPEATS = 0.8
}
