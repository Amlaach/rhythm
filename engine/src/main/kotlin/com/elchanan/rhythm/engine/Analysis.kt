package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.db.AudioFeatureEntity

/**
 * Turning decoded audio into the numbers the engine reasons about.
 *
 * Everything here is arithmetic on a float array: the FFT and mel filterbank,
 * the chroma and the key estimate, the onset envelope and the tempo, and the
 * merge of several probes into the single row a song carries. None of it
 * cares where the samples came from.
 *
 * Getting the samples does care, which is why that half is not here. On the
 * phone it is MediaCodec, on a desktop it is javax.sound, and those are the
 * only two pieces of this pipeline that differ between them. Splitting at the
 * float array means the measurements are the same measurements on both - a
 * song analysed on a laptop and the same song analysed on a phone produce the
 * same row, which they would not if this had been copied.
 */
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * On device audio analysis. Decodes short excerpts spread across a file, runs a
 * short time Fourier transform over them and reduces the result to a small set
 * of numbers that describe how the track actually sounds.
 *
 * Nothing leaves the phone and nothing is looked up anywhere - this is the
 * offline substitute for the acoustic metadata a streaming service would have.
 */
object Analysis {

    const val TARGET_SAMPLE_RATE = 22050
    const val WINDOW = 2048
    const val HOP = 512
    private const val MEL_BANDS = 26
    private const val MFCC_COUNT = 12

    private val DEFAULT_FFT by lazy { Fft(WINDOW) }
    private val DEFAULT_HANN_WINDOW by lazy { Dsp.hannWindow(WINDOW) }
    private val DEFAULT_MEL_BANK by lazy { Dsp.melFilterBank(MEL_BANDS, WINDOW, TARGET_SAMPLE_RATE) }
    private val DEFAULT_BIN_PITCH_CLASS by lazy { computeBinPitchClass(WINDOW / 2, TARGET_SAMPLE_RATE) }
    private val DEFAULT_BIN_QUARTER_CLASS by lazy { computeBinQuarterClass(WINDOW / 2, TARGET_SAMPLE_RATE) }

    private fun computeBinPitchClass(bins: Int, sampleRate: Int): IntArray = IntArray(bins) { k ->
        val hz = k.toDouble() * sampleRate / WINDOW
        if (hz < 55.0 || hz > 5000.0) -1
        else {
            val midi = 69.0 + 12.0 * log2(hz / 440.0)
            ((midi.roundToInt() % 12) + 12) % 12
        }
    }

    private fun computeBinQuarterClass(bins: Int, sampleRate: Int): IntArray = IntArray(bins) { k ->
        val hz = k.toDouble() * sampleRate / WINDOW
        if (hz < 55.0 || hz > 5000.0) -1
        else {
            val quarters = 138.0 + 24.0 * log2(hz / 440.0)
            ((quarters.roundToInt() % 24) + 24) % 24
        }
    }

    /**
     * Eight probes at these points of the track, four seconds each.
     *
     * Thirty-two seconds in total, which is what three ten second probes cost,
     * spread over eight places instead of three. The limit on this estimate was
     * never the number of frames - three probes already gave around thirteen
     * hundred - it was coverage. A key change, a long ornamented passage or a
     * bridge that sits outside the mode all read as the whole piece when only
     * three windows are asked, and more frames from those same three windows
     * cannot fix that. Spreading the same budget wider can.
     */
    val PROBE_POINTS =
        doubleArrayOf(0.10, 0.21, 0.32, 0.43, 0.54, 0.65, 0.76, 0.87)
    /**
     * The tempo range the detector will report.
     *
     * Wider than the 55..190 it used to be, because the edges were doing the
     * work that the preference below should be doing: a genuine 190 BPM track
     * was not merely disbelieved, it was unrepresentable.
     */
    private const val MIN_BPM = 45.0
    private const val MAX_BPM = 200.0

    /** Where the tempo preference sits, and how many octaves wide it is. */
    private const val TEMPO_CENTRE = 120.0
    private const val TEMPO_SPREAD = 1.1

    /**
     * How loud what falls between the beats may be, relative to the beats,
     * before the period is taken to be twice too long.
     *
     * Measured on synthetic patterns: at the right period the ratio reached
     * 0.62 at worst, on straight eighth notes; at twice the right period it
     * never fell below 0.77. This is the middle of that gap.
     */
    private const val OFF_BEAT_LIMIT = 0.70

    /** How finely to search for the phase the beats sit on. */
    private const val PHASE_STEPS = 20

    const val PROBE_SECONDS = 4

    /**
     * What each slot of the stored shape vector holds.
     *
     * Beside [merge], which is the code that writes it, so a reader asking for
     * SHAPE_CONTRAST cannot quietly end up with brightRise after someone
     * reorders the array.
     */
    const val SHAPE_DIMS = 6
    const val SHAPE_ENERGY_RISE = 0
    const val SHAPE_ENERGY_SPREAD = 1
    const val SHAPE_BRIGHT_RISE = 2
    const val SHAPE_ONSET_RISE = 3
    const val SHAPE_DRIFT = 4
    const val SHAPE_CONTRAST = 5

    // Krumhansl-Schmuckler key profiles
    private val MAJOR_PROFILE = doubleArrayOf(
        6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88
    )
    private val MINOR_PROFILE = doubleArrayOf(
        6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17
    )

    fun concat(parts: List<FloatArray>): FloatArray {
        var total = 0
        for (p in parts) total += p.size
        val out = FloatArray(total)
        var at = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, at, p.size)
            at += p.size
        }
        return out
    }

    // -------------------------------------------------------------------------
    // the tagging model, created once for a whole pass
    // -------------------------------------------------------------------------

    /**
     * Where to sample the track.
     *
     * One excerpt from a single point describes that point, not the song: a long
     * intro, a key change or a quiet bridge all read as the whole piece. Several
     * shorter probes spread across the track cover far more of it for the same
     * total decode time, and disagreement between them is itself a useful
     * measure of how varied the song is.
     *
     * The points stop short of both ends. An intro often has no tonal content at
     * all and an outro is usually fading, so a probe at either would measure the
     * production rather than the music.
     */
    fun probeStart(durationMs: Long, fraction: Double): Long {
        if (durationMs <= PROBE_SECONDS * 1000L) return 0L
        val maxStart = durationMs - PROBE_SECONDS * 1000L
        val wanted = (durationMs * fraction).toLong()
        return min(wanted, max(0L, maxStart)) * 1000L
    }

    // -----------------------------------------------------------------------
    // decoding
    // -----------------------------------------------------------------------

    /** Cheap integer decimation with a box filter; good enough at these rates. */
    fun decimate(input: FloatArray, sampleRate: Int, target: Int): Pair<FloatArray, Int> {
        val factor = (sampleRate.toDouble() / target).roundToInt()
        if (factor <= 1) return input to sampleRate
        val outSize = input.size / factor
        val out = FloatArray(outSize)
        for (i in 0 until outSize) {
            var sum = 0f
            val base = i * factor
            for (k in 0 until factor) sum += input[base + k]
            out[i] = sum / factor
        }
        return out to (sampleRate / factor)
    }

    /** Produce samples at the model's requested rate, including 44.1 -> 16 kHz. */
    fun resampleMono(input: FloatArray, sampleRate: Int, targetRate: Int): FloatArray {
        require(sampleRate > 0 && targetRate > 0)
        if (input.isEmpty() || sampleRate == targetRate) return input
        val count = (input.size.toLong() * targetRate / sampleRate).toInt()
        val output = FloatArray(count)
        val step = sampleRate.toDouble() / targetRate
        if (step < 1.0) {
            for (i in output.indices) {
                val at = i * step
                val left = at.toInt().coerceAtMost(input.lastIndex)
                val right = (left + 1).coerceAtMost(input.lastIndex)
                output[i] = (input[left] + (input[right] - input[left]) * (at - left)).toFloat()
            }
        } else {
            // Average each output interval, including its fractional edge samples.
            for (i in output.indices) {
                val start = i * step
                val end = ((i + 1) * step).coerceAtMost(input.size.toDouble())
                var at = start
                var sum = 0.0
                while (at < end) {
                    val index = at.toInt().coerceAtMost(input.lastIndex)
                    val next = min(end, index + 1.0)
                    sum += input[index] * (next - at)
                    at = next
                }
                output[i] = (sum / (end - start)).toFloat()
            }
        }
        return output
    }

    // -----------------------------------------------------------------------
    // feature extraction
    // -----------------------------------------------------------------------

    /** Everything one probe can say on its own, before the probes are combined. */
    class WindowStats(
        val energy: Double,
        val brightness: Double,
        val flatness: Double,
        val dynamics: Double,
        val bpm: Double,
        val bpmConfidence: Double,
        val onsetRate: Double,
        /** unnormalised, so probes can be summed before normalising */
        val chroma: DoubleArray,
        /** the same at quarter tone resolution, for the maqam family */
        val chroma24: DoubleArray,
        val mfccMean: DoubleArray,
        val mfccVar: DoubleArray
    )

    /**
     * Combines the probes, and records how much they disagreed.
     *
     * The disagreement is the point. Averaged features describe what a track is
     * made of but say nothing about how it moves, so a piece that builds from a
     * whisper to a full choir and one that holds the same level throughout can
     * average out identically. The shape vector carries that difference.
     */
    fun merge(songId: Long, windows: List<WindowStats>): AudioFeatureEntity {
        require(windows.isNotEmpty()) { "Cannot merge an empty list of windows for song $songId" }
        val n = windows.size

        val chromaTotal = DoubleArray(12)
        for (w in windows) for (i in 0 until 12) chromaTotal[i] += w.chroma[i]
        val chromaSum = chromaTotal.sum()
        val chromaNorm =
            if (chromaSum > 1e-9) DoubleArray(12) { chromaTotal[it] / chromaSum } else DoubleArray(12)
        val quarterTotal = DoubleArray(24)
        for (w in windows) for (i in 0 until 24) quarterTotal[i] += w.chroma24[i]
        val quarterSum = quarterTotal.sum()
        val quarterNorm =
            if (quarterSum > 1e-9) DoubleArray(24) { quarterTotal[it] / quarterSum }
            else DoubleArray(24)

        // The modal estimate is the authority on the tonic; major/minor is kept
        // only so the rest of the app, which still thinks in two modes, keeps
        // working unchanged.
        val estimate = ModeDetector.detect(chromaNorm, quarterNorm)
        val key = estimate?.key ?: detectKey(chromaNorm).first
        val mode = when {
            estimate == null -> detectKey(chromaNorm).second
            estimate.mode.brightFamily -> 1
            else -> 0
        }
        val rotated = DoubleArray(12) { chromaNorm[(it + max(0, key)) % 12] }
        val rotated24 = DoubleArray(24) { quarterNorm[(it + max(0, key) * 2) % 24] }

        val timbre = DoubleArray(MFCC_COUNT) { c -> windows.sumOf { it.mfccMean[c] } / n }
        val timbreVar = DoubleArray(MFCC_COUNT) { c -> windows.sumOf { it.mfccVar[c] } / n }

        // tempo comes from the single most confident probe rather than an
        // average: a wrong estimate averaged with a right one is just wrong.
        val bestTempo = windows.maxByOrNull { it.bpmConfidence } ?: windows.first()

        val energies = windows.map { it.energy }
        val meanEnergy = energies.average()
        val energySpread =
            if (meanEnergy > 1e-9) (Dsp.stdDev(energies.toDoubleArray()) / meanEnergy) else 0.0
        val energyRise = if (n >= 2 && meanEnergy > 1e-9) {
            (energies.last() - energies.first()) / meanEnergy
        } else 0.0

        val brightnesses = windows.map { it.brightness }
        val brightRise = if (n >= 2) brightnesses.last() - brightnesses.first() else 0.0

        val onsets = windows.map { it.onsetRate }
        val meanOnset = onsets.average()
        val onsetRise = if (n >= 2 && meanOnset > 1e-9) {
            (onsets.last() - onsets.first()) / meanOnset
        } else 0.0

        // how far the timbre travels between probes, averaged over the hops
        var drift = 0.0
        for (i in 1 until n) {
            var acc = 0.0
            for (c in 0 until MFCC_COUNT) {
                val d = windows[i].mfccMean[c] - windows[i - 1].mfccMean[c]
                acc += d * d
            }
            drift += sqrt(acc)
        }
        if (n > 1) drift /= (n - 1)

        val contrast = if (meanEnergy > 1e-9) {
            (energies.max() - energies.min()) / meanEnergy
        } else 0.0

        // Order fixed by the SHAPE_ constants below; anything reading a slot
        // by number reads them, so the two cannot drift apart.
        val shape = doubleArrayOf(
            energyRise, energySpread, brightRise, onsetRise, drift, contrast
        )

        return AudioFeatureEntity(
            songId = songId,
            analyzedAt = System.currentTimeMillis(),
            bpm = bestTempo.bpm.toFloat(),
            bpmConfidence = bestTempo.bpmConfidence.toFloat(),
            musicalKey = key,
            mode = mode,
            energy = meanEnergy.toFloat(),
            brightness = brightnesses.average().toFloat(),
            flatness = windows.map { it.flatness }.average().toFloat(),
            dynamics = windows.map { it.dynamics }.average().toFloat(),
            onsetRate = meanOnset.toFloat(),
            chroma = rotated.joinToString(",") { "%.5f".format(it) },
            timbre = timbre.joinToString(",") { "%.5f".format(it) },
            timbreVar = timbreVar.joinToString(",") { "%.5f".format(it) },
            shape = shape.joinToString(",") { "%.5f".format(it) },
            scaleMode = estimate?.mode?.ordinal ?: -1,
            scaleConfidence = (estimate?.confidence ?: 0.0).toFloat(),
            chroma24 = rotated24.joinToString(",") { "%.5f".format(it) }
        )
    }

    fun windowStats(samples: FloatArray, sampleRate: Int): WindowStats {
        val fft = if (sampleRate == TARGET_SAMPLE_RATE) DEFAULT_FFT else Fft(WINDOW)
        val window = DEFAULT_HANN_WINDOW
        val bank = if (sampleRate == TARGET_SAMPLE_RATE) DEFAULT_MEL_BANK else Dsp.melFilterBank(MEL_BANDS, WINDOW, sampleRate)
        val bins = WINDOW / 2

        val frameCount = max(1, (samples.size - WINDOW) / HOP)
        val rms = DoubleArray(frameCount)
        val centroid = DoubleArray(frameCount)
        val flatness = DoubleArray(frameCount)
        val flux = DoubleArray(frameCount)
        val chroma = DoubleArray(12)
        val mfccSums = DoubleArray(MFCC_COUNT)
        val mfccSquares = DoubleArray(MFCC_COUNT)

        val re = DoubleArray(WINDOW)
        val im = DoubleArray(WINDOW)
        val magnitude = DoubleArray(bins)
        val previous = DoubleArray(bins)
        // Kept so the separation can run over the whole probe afterwards. At
        // four seconds and this hop it is under two megabytes, freed as soon as
        // the probe is done.
        val spectrogram = Array(frameCount) { DoubleArray(bins) }

        // pitch class of every fft bin, computed once
        val binPitchClass = if (sampleRate == TARGET_SAMPLE_RATE) DEFAULT_BIN_PITCH_CLASS else computeBinPitchClass(bins, sampleRate)
        val binQuarterClass = if (sampleRate == TARGET_SAMPLE_RATE) DEFAULT_BIN_QUARTER_CLASS else computeBinQuarterClass(bins, sampleRate)
        val chroma24 = DoubleArray(24)

        for (frame in 0 until frameCount) {
            val offset = frame * HOP
            for (i in 0 until WINDOW) {
                re[i] = samples[offset + i] * window[i]
                im[i] = 0.0
            }
            var energy = 0.0
            for (i in 0 until WINDOW) energy += re[i] * re[i]
            rms[frame] = sqrt(energy / WINDOW)

            fft.transform(re, im)

            var magSum = 0.0
            var weighted = 0.0
            for (k in 0 until bins) {
                val m = sqrt(re[k] * re[k] + im[k] * im[k])
                magnitude[k] = m
                magSum += m
                weighted += m * k
                val pc = binPitchClass[k]
                if (pc >= 0) chroma[pc] += m
                val qc = binQuarterClass[k]
                if (qc >= 0) chroma24[qc] += m
            }
            centroid[frame] = if (magSum > 1e-9) (weighted / magSum) / bins else 0.0
            flatness[frame] = if (magSum > 1e-9) {
                Dsp.geometricMean(magnitude) / (magSum / bins)
            } else 0.0

            var positiveFlux = 0.0
            for (k in 0 until bins) {
                val d = magnitude[k] - previous[k]
                if (d > 0) positiveFlux += d
            }
            flux[frame] = positiveFlux
            System.arraycopy(magnitude, 0, previous, 0, bins)

            // mel -> log -> dct
            val melEnergies = DoubleArray(MEL_BANDS)
            for (b in 0 until MEL_BANDS) {
                val (start, weights) = bank[b]
                var acc = 0.0
                for (i in weights.indices) {
                    val bin = start + i
                    if (bin < bins) acc += magnitude[bin] * weights[i]
                }
                melEnergies[b] = ln(acc + 1e-10)
            }
            val mfcc = Dsp.dct(melEnergies, MFCC_COUNT + 1)
            for (c in 0 until MFCC_COUNT) {
                val v = mfcc[c + 1]
                mfccSums[c] += v
                mfccSquares[c] += v * v
            }
            System.arraycopy(magnitude, 0, spectrogram[frame], 0, bins)
        }

        // Harmony and rhythm are then measured on opposite halves of the sound.
        // Beforehand both were read off the whole mix, and each was being asked
        // to ignore the other: a snare is broadband, so it deposits a little
        // energy in all twelve pitch classes at once and quietly flattens the
        // very distribution the key detector weighs; a held chord is not an
        // onset but drifts enough to look like a stream of them.
        runCatching {
            // Only the bins that feed a measurement are separated. Chroma stops
            // at 5 kHz and onsets carry no useful information above it either,
            // so filtering the top half of the spectrum is work whose result is
            // then thrown away - and this filter is the most expensive thing in
            // the analyser, several times the cost of the transform that
            // produced the spectrogram.
            val usefulBins = ((5000.0 * WINDOW / sampleRate).toInt() + 1).coerceIn(1, bins)
            val band = Array(frameCount) { f ->
                java.util.Arrays.copyOf(spectrogram[f], usefulBins)
            }
            val split = Separation.split(band)
            java.util.Arrays.fill(chroma, 0.0)
            java.util.Arrays.fill(chroma24, 0.0)
            for (frame in 0 until frameCount) {
                val row = split.harmonic[frame]
                for (k in 0 until usefulBins) {
                    val m = row[k]
                    if (m <= 0.0) continue
                    val pc = binPitchClass[k]
                    if (pc >= 0) chroma[pc] += m
                    val qc = binQuarterClass[k]
                    if (qc >= 0) chroma24[qc] += m
                }
            }
            // And the onset envelope from the percussive half only.
            java.util.Arrays.fill(previous, 0.0)
            for (frame in 0 until frameCount) {
                val row = split.percussive[frame]
                var positive = 0.0
                for (k in 0 until usefulBins) {
                    val d = row[k] - previous[k]
                    if (d > 0) positive += d
                }
                flux[frame] = positive
                System.arraycopy(row, 0, previous, 0, usefulBins)
            }
        }

        val meanRms = Dsp.mean(rms)
        val dynamics = if (meanRms > 1e-9) (Dsp.stdDev(rms) / meanRms).coerceIn(0.0, 4.0) else 0.0

        // tempo from the onset envelope
        val frameRate = sampleRate.toDouble() / HOP
        val onset = Dsp.rectifyAgainstLocalMean(flux, 8)
        val (bpm, confidence) = detectTempo(onset, frameRate)
        val onsetRate = countPeaks(onset) / (frameCount / frameRate)

        // Beats, and chroma averaged between them rather than over a fixed
        // grid. A chord lasts a beat or two; sampling it every 23 milliseconds
        // measures the same chord many times and the changes between chords
        // hardly at all, which is what smears a key estimate.
        runCatching {
            val beats = BeatTracker.track(onset, frameRate, bpm)
            if (beats.size >= 4) {
                val perFrame = Array(frameCount) { f ->
                    val row = spectrogram[f]
                    val acc = DoubleArray(12)
                    for (k in 0 until bins) {
                        val pc = binPitchClass[k]
                        if (pc >= 0) acc[pc] += row[k]
                    }
                    acc
                }
                val perBeat = BeatTracker.synchronise(perFrame, beats)
                if (perBeat.isNotEmpty()) {
                    java.util.Arrays.fill(chroma, 0.0)
                    for (beat in perBeat) {
                        // Each beat contributes equally, so a long held note
                        // cannot outvote a bar full of changes.
                        val total = beat.sum()
                        if (total <= 1e-9) continue
                        for (pc in 0 until 12) chroma[pc] += beat[pc] / total
                    }
                }
            }
        }

        val timbre = DoubleArray(MFCC_COUNT) { mfccSums[it] / frameCount }
        val timbreVar = DoubleArray(MFCC_COUNT) {
            val mean = timbre[it]
            sqrt(max(0.0, mfccSquares[it] / frameCount - mean * mean))
        }

        return WindowStats(
            energy = meanRms,
            brightness = Dsp.mean(centroid),
            flatness = Dsp.mean(flatness),
            dynamics = dynamics,
            bpm = bpm,
            bpmConfidence = confidence,
            onsetRate = onsetRate,
            chroma = chroma,
            chroma24 = chroma24,
            mfccMean = timbre,
            mfccVar = timbreVar
        )
    }

    /**
     * Correlates the chroma vector against the twelve rotations of the major
     * and minor profiles and keeps the best fit.
     */
    private fun detectKey(chroma: DoubleArray): Pair<Int, Int> {
        if (chroma.sum() < 1e-9) return -1 to -1
        var bestKey = -1
        var bestMode = -1
        var bestScore = -2.0
        for (rotation in 0 until 12) {
            val rotated = DoubleArray(12) { chroma[(it + rotation) % 12] }
            val major = correlation(rotated, MAJOR_PROFILE)
            val minor = correlation(rotated, MINOR_PROFILE)
            if (major > bestScore) {
                bestScore = major; bestKey = rotation; bestMode = 1
            }
            if (minor > bestScore) {
                bestScore = minor; bestKey = rotation; bestMode = 0
            }
        }
        return if (bestScore < 0.2) (-1 to -1) else (bestKey to bestMode)
    }

    private fun correlation(a: DoubleArray, b: DoubleArray): Double {
        val ma = Dsp.mean(a)
        val mb = Dsp.mean(b)
        var num = 0.0
        var da = 0.0
        var db = 0.0
        for (i in a.indices) {
            val x = a[i] - ma
            val y = b[i] - mb
            num += x * y
            da += x * x
            db += y * y
        }
        return if (da < 1e-12 || db < 1e-12) 0.0 else num / sqrt(da * db)
    }

    /**
     * The tempo of the onset envelope, in beats per minute, with a confidence.
     *
     * Three parts, in order of how much trouble each one caused.
     *
     * The autocorrelation is normalised by how many terms actually overlapped
     * at each lag. Dividing the sum by the whole signal's energy - which is
     * what the shared helper does, correctly, for its own callers - scores a
     * long lag on fewer products against the same denominator, so slow tempi
     * are quietly marked down and the comparison between lags is not a fair
     * one.
     *
     * Then a mild preference for ordinary tempi, log-normal around 120 BPM.
     * Without it a peak at the length of a bar wins whenever it happens to be
     * a shade taller than the peak at the beat.
     *
     * Then the octave, which is the part an autocorrelation cannot settle by
     * itself: a beat and a half-beat repeat at the same period, so both show a
     * peak and nothing in the correlation says which one the music is counted
     * in. What does say is the accents - at the real beat the notes on it are
     * louder than whatever falls between them. [offBeatRatio] measures exactly
     * that. Against synthetic patterns with a known tempo the ratio came out
     * at most 0.62 at the true period and at least 0.77 at twice it, a gap
     * wide enough to cut down the middle.
     *
     * The previous version instead halved anything above 175 BPM outright,
     * which meant no track could ever be reported as faster than that: 180 BPM
     * came back as 60 and 190 as 95.
     */
    private fun detectTempo(onset: DoubleArray, frameRate: Double): Pair<Double, Double> {
        val n = onset.size
        if (n < 128) return 0.0 to 0.0
        val minLag = max(2, (frameRate * 60.0 / MAX_BPM).roundToInt())
        val maxLag = min(n / 3, (frameRate * 60.0 / MIN_BPM).roundToInt())
        if (maxLag <= minLag + 1) return 0.0 to 0.0

        val mean = Dsp.mean(onset)
        val centred = DoubleArray(n) { onset[it] - mean }
        var energy = 0.0
        for (v in centred) energy += v * v
        if (energy < 1e-12) return 0.0 to 0.0
        val perSample = energy / n

        val correlation = DoubleArray(maxLag + 1)
        for (lag in minLag..maxLag) {
            var sum = 0.0
            for (i in 0 until n - lag) sum += centred[i] * centred[i + lag]
            correlation[lag] = sum / (n - lag) / perSample
        }

        var bestLag = -1
        var bestScore = Double.NEGATIVE_INFINITY
        var total = 0.0
        var counted = 0
        for (lag in minLag..maxLag) {
            total += correlation[lag]
            counted++
            val bpm = 60.0 * frameRate / lag
            val octaves = log2(bpm / TEMPO_CENTRE)
            val spread = octaves / TEMPO_SPREAD
            val score = correlation[lag] * exp(-0.5 * spread * spread)
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        if (bestLag <= 0 || counted == 0) return 0.0 to 0.0
        val peak = correlation[bestLag]

        // Sub-frame refinement. At 180 BPM a beat is under thirty frames long,
        // so a whole frame of error is already several BPM.
        val below = correlation[max(minLag, bestLag - 1)]
        val here = correlation[bestLag]
        val above = correlation[min(maxLag, bestLag + 1)]
        val curvature = below - 2 * here + above
        val nudge = if (abs(curvature) > 1e-12) {
            (0.5 * (below - above) / curvature).coerceIn(-1.0, 1.0)
        } else {
            0.0
        }

        var period = bestLag + nudge
        // Twice, so a period four times too long can still come back. More
        // than that and the correction is doing more work than the evidence
        // supports.
        repeat(2) {
            if (period / 2.0 < minLag) return@repeat
            if (offBeatRatio(onset, period) > OFF_BEAT_LIMIT) period /= 2.0
        }
        if (period <= 0.0) return 0.0 to 0.0

        val average = total / counted
        val confidence = if (average > 1e-9) {
            ((peak / average - 1.0) / 2.0).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        return (60.0 * frameRate / period) to confidence
    }

    /**
     * How loud the midpoint between beats is, next to the beats themselves.
     *
     * Near zero when the period is the real one and the music puts its notes
     * on the beat; near one when the period is twice too long, because then
     * the "midpoint" is itself a beat. Eighth notes push it up to about 0.6,
     * which is why the threshold is not simply a half.
     *
     * The period is a fraction of a frame rather than a whole number, and each
     * position is read with its neighbours either side. Sampling single frames
     * at a rounded period was accurate enough at 60 BPM and useless at 190,
     * where a beat is twenty-seven frames apart and the rounding walks off the
     * beat within a couple of bars.
     */
    private fun offBeatRatio(onset: DoubleArray, period: Double): Double {
        val n = onset.size
        if (period < 4.0 || n < 8) return 1.0

        fun readAt(position: Double): Double {
            val i = position.roundToInt()
            if (i < 1 || i >= n - 1) return 0.0
            return onset[i] + 0.5 * onset[i - 1] + 0.5 * onset[i + 1]
        }

        // Which phase the beats are actually on.
        var bestPhase = 0.0
        var bestSum = -1.0
        for (step in 0 until PHASE_STEPS) {
            val phase = period * step / PHASE_STEPS
            var sum = 0.0
            var t = phase
            while (t < n - 1) {
                sum += readAt(t)
                t += period
            }
            if (sum > bestSum) {
                bestSum = sum
                bestPhase = phase
            }
        }

        var on = 0.0
        var onCount = 0
        var t = bestPhase
        while (t < n - 1) {
            on += readAt(t)
            onCount++
            t += period
        }
        var off = 0.0
        var offCount = 0
        t = bestPhase + period / 2.0
        while (t < n - 1) {
            off += readAt(t)
            offCount++
            t += period
        }
        if (onCount == 0 || offCount == 0) return 1.0
        val onMean = on / onCount
        if (onMean <= 1e-12) return 1.0
        return (off / offCount) / onMean
    }

    private fun countPeaks(signal: DoubleArray): Double {
        if (signal.size < 3) return 0.0
        val threshold = Dsp.mean(signal) + 0.8 * Dsp.stdDev(signal)
        var peaks = 0
        for (i in 1 until signal.size - 1) {
            if (signal[i] > threshold && signal[i] >= signal[i - 1] && signal[i] > signal[i + 1]) peaks++
        }
        return peaks.toDouble()
    }

    /**
     * Placeholder row for a file that could not be decoded, so the analysis
     * pass does not retry it forever. bpm 0 keeps it out of every tempo based
     * shelf and out of the acoustic space in practice.
     */
    fun blankFor(songId: Long): AudioFeatureEntity {
        val zeros = (0 until 12).joinToString(",") { "0.00000" }
        return AudioFeatureEntity(
            songId = songId,
            analyzedAt = System.currentTimeMillis(),
            bpm = 0f,
            bpmConfidence = 0f,
            musicalKey = -1,
            mode = -1,
            energy = 0f,
            brightness = 0f,
            flatness = 0f,
            dynamics = 0f,
            onsetRate = 0f,
            chroma = zeros,
            timbre = zeros,
            timbreVar = zeros,
            // A file that would not decode will not give up a print either,
            // and must not be queued for one on every pass.
            soundPrint = SoundPrint.TRIED,
            musicPrint = MusicPrint.TRIED
        )
    }
}
