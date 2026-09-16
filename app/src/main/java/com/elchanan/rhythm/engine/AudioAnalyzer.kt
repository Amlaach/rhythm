package com.elchanan.rhythm.engine

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.elchanan.rhythm.data.db.AudioFeatureEntity
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.playback.MediaItems
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * On device audio analysis. Decodes a 30 second excerpt from the middle of a
 * file, runs a short time Fourier transform over it and reduces the result to
 * a small set of numbers that describe how the track actually sounds.
 *
 * Nothing leaves the phone and nothing is looked up anywhere - this is the
 * offline substitute for the acoustic metadata a streaming service would have.
 */
object AudioAnalyzer {

    const val TARGET_SAMPLE_RATE = 22050
    private const val WINDOW = 2048
    private const val HOP = 512
    private const val MEL_BANDS = 26
    private const val MFCC_COUNT = 12

    /** Three probes at these points of the track, ten seconds each. */
    private val PROBE_POINTS = doubleArrayOf(0.18, 0.48, 0.78)
    private const val PROBE_SECONDS = 10
    private const val DECODE_TIMEOUT_US = 8_000L

    // Krumhansl-Schmuckler key profiles
    private val MAJOR_PROFILE = doubleArrayOf(
        6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88
    )
    private val MINOR_PROFILE = doubleArrayOf(
        6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17
    )

    val KEY_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    /**
     * Returns null when the file cannot be decoded - a corrupt file simply
     * stays unanalysed instead of stopping the whole pass.
     */
    fun analyze(context: Context, song: SongEntity): AudioFeatureEntity? {
        val uri = MediaItems.songUri(song.id)
        val windows = ArrayList<WindowStats>(PROBE_POINTS.size)

        for (fraction in PROBE_POINTS) {
            val startUs = probeStart(song.durationMs, fraction)
            val decoded = runCatching {
                decodeMono(context, uri, startUs, PROBE_SECONDS)
            }.getOrNull() ?: continue
            val (raw, sampleRate) = decoded
            if (raw.size < WINDOW * 8) continue
            val (samples, sr) = decimate(raw, sampleRate, TARGET_SAMPLE_RATE)
            val stats = runCatching { windowStats(samples, sr) }.getOrNull() ?: continue
            windows.add(stats)
        }

        if (windows.isEmpty()) return null
        return runCatching { merge(song.id, windows) }.getOrNull()
    }

    /**
     * Where to sample the track.
     *
     * One excerpt from a single point describes that point, not the song: a long
     * intro, a key change or a quiet bridge all read as the whole piece. Three
     * shorter probes spread across the track cover far more of it for the same
     * total decode time, and disagreement between them is itself a useful
     * measure of how varied the song is.
     */
    private fun probeStart(durationMs: Long, fraction: Double): Long {
        if (durationMs <= PROBE_SECONDS * 1000L) return 0L
        val maxStart = durationMs - PROBE_SECONDS * 1000L
        val wanted = (durationMs * fraction).toLong()
        return min(wanted, max(0L, maxStart)) * 1000L
    }

    // -----------------------------------------------------------------------
    // decoding
    // -----------------------------------------------------------------------

    private class Samples {
        var data = FloatArray(1 shl 16)
        var size = 0
        fun add(v: Float) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }
        fun trimmed(): FloatArray = data.copyOf(size)
    }

    private fun decodeMono(
        context: Context,
        uri: Uri,
        startUs: Long,
        seconds: Int
    ): Pair<FloatArray, Int>? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var track = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    track = i
                    inputFormat = f
                    break
                }
            }
            val format = inputFormat ?: return null
            if (track < 0) return null
            extractor.selectTrack(track)
            if (startUs > 0) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            var sampleRate = intOrDefault(format, MediaFormat.KEY_SAMPLE_RATE, 44100)
            var channels = intOrDefault(format, MediaFormat.KEY_CHANNEL_COUNT, 2)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

            val out = Samples()
            var wanted = sampleRate * seconds
            val info = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false
            var guard = 0

            while (!sawOutputEos && out.size < wanted && guard < 40_000) {
                guard++
                if (!sawInputEos) {
                    val inIndex = codec.dequeueInputBuffer(DECODE_TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)
                        val read = if (buffer == null) -1 else extractor.readSampleData(buffer, 0)
                        if (read < 0) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, read, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, DECODE_TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        sampleRate = intOrDefault(of, MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        channels = intOrDefault(of, MediaFormat.KEY_CHANNEL_COUNT, channels)
                        pcmEncoding = if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else {
                            AudioFormat.ENCODING_PCM_16BIT
                        }
                        wanted = sampleRate * seconds
                    }

                    outIndex >= 0 -> {
                        val buffer = codec.getOutputBuffer(outIndex)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            appendMono(buffer, channels, pcmEncoding, out)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
                    }
                }
            }
            if (out.size == 0) return null
            return out.trimmed() to sampleRate
        } catch (_: Throwable) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun intOrDefault(format: MediaFormat, key: String, fallback: Int): Int =
        if (format.containsKey(key)) runCatching { format.getInteger(key) }.getOrDefault(fallback)
        else fallback

    private fun appendMono(buffer: ByteBuffer, channels: Int, encoding: Int, out: Samples) {
        buffer.order(ByteOrder.nativeOrder())
        val ch = max(1, channels)
        when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val fb = buffer.asFloatBuffer()
                val frames = fb.remaining() / ch
                for (i in 0 until frames) {
                    var sum = 0f
                    for (c in 0 until ch) sum += fb.get()
                    out.add(sum / ch)
                }
            }

            AudioFormat.ENCODING_PCM_8BIT -> {
                val frames = buffer.remaining() / ch
                for (i in 0 until frames) {
                    var sum = 0f
                    for (c in 0 until ch) sum += (buffer.get().toInt() - 128) / 128f
                    out.add(sum / ch)
                }
            }

            else -> {
                val sb = buffer.asShortBuffer()
                val frames = sb.remaining() / ch
                for (i in 0 until frames) {
                    var sum = 0f
                    for (c in 0 until ch) sum += sb.get() / 32768f
                    out.add(sum / ch)
                }
            }
        }
    }

    /** Cheap integer decimation with a box filter; good enough at these rates. */
    private fun decimate(input: FloatArray, sampleRate: Int, target: Int): Pair<FloatArray, Int> {
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

    // -----------------------------------------------------------------------
    // feature extraction
    // -----------------------------------------------------------------------

    /** Everything one probe can say on its own, before the probes are combined. */
    private class WindowStats(
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
    private fun merge(songId: Long, windows: List<WindowStats>): AudioFeatureEntity {
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

    private fun windowStats(samples: FloatArray, sampleRate: Int): WindowStats {
        val fft = Fft(WINDOW)
        val window = Dsp.hannWindow(WINDOW)
        val bank = Dsp.melFilterBank(MEL_BANDS, WINDOW, sampleRate)
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

        // pitch class of every fft bin, computed once
        val binPitchClass = IntArray(bins) { k ->
            val hz = k.toDouble() * sampleRate / WINDOW
            if (hz < 55.0 || hz > 5000.0) -1
            else {
                val midi = 69.0 + 12.0 * log2(hz / 440.0)
                ((midi.roundToInt() % 12) + 12) % 12
            }
        }

        // The same thing at quarter tone resolution. Twelve bins cannot express
        // the neutral second and third that define Rast and Bayati - the pitch
        // simply rounds to its nearest semitone and the mode disappears. Twenty
        // four bins keep it.
        val binQuarterClass = IntArray(bins) { k ->
            val hz = k.toDouble() * sampleRate / WINDOW
            if (hz < 55.0 || hz > 5000.0) -1
            else {
                val quarters = 138.0 + 24.0 * log2(hz / 440.0)
                ((quarters.roundToInt() % 24) + 24) % 24
            }
        }
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
        }

        val meanRms = Dsp.mean(rms)
        val dynamics = if (meanRms > 1e-9) (Dsp.stdDev(rms) / meanRms).coerceIn(0.0, 4.0) else 0.0

        // tempo from the onset envelope
        val frameRate = sampleRate.toDouble() / HOP
        val onset = Dsp.rectifyAgainstLocalMean(flux, 8)
        val (bpm, confidence) = detectTempo(onset, frameRate)
        val onsetRate = countPeaks(onset) / (frameCount / frameRate)

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
     * Autocorrelation of the onset envelope over the 55..190 BPM range, with an
     * octave check so a half time reading gets folded back up.
     */
    private fun detectTempo(onset: DoubleArray, frameRate: Double): Pair<Double, Double> {
        if (onset.size < 64) return 0.0 to 0.0
        val minLag = max(2, (frameRate * 60.0 / 190.0).toInt())
        val maxLag = min(onset.size / 2, (frameRate * 60.0 / 55.0).toInt())
        if (maxLag <= minLag) return 0.0 to 0.0

        val ac = Dsp.autocorrelation(onset, minLag, maxLag)
        var bestLag = -1
        var bestValue = 0.0
        var sum = 0.0
        var count = 0
        for (lag in minLag..maxLag) {
            sum += ac[lag]
            count++
            if (ac[lag] > bestValue) {
                bestValue = ac[lag]
                bestLag = lag
            }
        }
        if (bestLag <= 0 || count == 0) return 0.0 to 0.0

        var bpm = 60.0 * frameRate / bestLag
        // fold a suspiciously slow reading up an octave if the half lag also peaks
        if (bpm < 80.0) {
            val halfLag = bestLag / 2
            if (halfLag >= minLag && ac[halfLag] > bestValue * 0.6) bpm *= 2.0
        }
        if (bpm > 175.0) bpm /= 2.0

        val average = sum / count
        val confidence = if (average > 1e-9) ((bestValue / average - 1.0) / 2.0).coerceIn(0.0, 1.0) else 0.0
        return bpm to confidence
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

    fun keyLabel(key: Int, mode: Int): String = when {
        key < 0 -> "לא זוהה"
        mode == 1 -> "${KEY_NAMES[key]} מז'ור"
        mode == 0 -> "${KEY_NAMES[key]} מינור"
        else -> KEY_NAMES[key]
    }

    /**
     * Prefers the modal name over "major"/"minor" when the estimate was clear.
     * "D אהבה רבה" tells a listener here far more than "D מינור" does.
     */
    fun modeLabel(f: AudioFeatureEntity): String {
        if (f.musicalKey < 0) return "לא זוהה"
        val mode = MusicalMode.byOrdinalOrNull(f.scaleMode)
        if (mode == null || f.scaleConfidence < 0.2f) return keyLabel(f.musicalKey, f.mode)
        return "${KEY_NAMES[f.musicalKey]} ${mode.label}"
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
            timbreVar = zeros
        )
    }

    fun parseVector(csv: String, expected: Int): DoubleArray {
        val parts = csv.split(',')
        return DoubleArray(expected) { parts.getOrNull(it)?.toDoubleOrNull() ?: 0.0 }
    }
}
