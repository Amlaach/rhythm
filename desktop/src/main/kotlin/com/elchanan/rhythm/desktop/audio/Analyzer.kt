package com.elchanan.rhythm.desktop.audio

import com.elchanan.rhythm.data.db.AudioFeatureEntity
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.engine.Analysis
import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem

/**
 * The desktop's half of audio analysis: getting samples off a file.
 *
 * Everything measured from those samples - the FFT, the chroma, the key, the
 * tempo, the merge of the probes into one row - is [Analysis] in :engine, the
 * same code the phone runs. The only thing that differs between the two
 * platforms is these few dozen lines, because MediaCodec exists on one and
 * javax.sound on the other. A song analysed here and the same song analysed
 * on a phone produce the same row.
 *
 * One thing is missing rather than different: there is no tagger. YAMNet runs
 * through TensorFlow Lite, whose Android artifact does not load here, so the
 * tags field stays empty. The engine already handles that - a song with
 * measurements and no tags is a song it can still place, and the style
 * learner takes either half.
 */
object Analyzer {

    /**
     * Measures one song, or returns null if nothing could be decoded.
     *
     * A file that will not open is not an error worth stopping a pass for:
     * the library is full of files nobody has checked, and one of them being
     * broken should cost that one song and nothing else.
     */
    fun analyze(song: SongEntity): AudioFeatureEntity? {
        val file = File(song.path)
        if (!file.isFile) return null

        val windows = ArrayList<Analysis.WindowStats>(Analysis.PROBE_POINTS.size)
        for (fraction in Analysis.PROBE_POINTS) {
            val startUs = Analysis.probeStart(song.durationMs, fraction)
            val decoded = runCatching {
                decodeMono(file, startUs / 1000L, Analysis.PROBE_SECONDS)
            }.getOrNull() ?: continue
            val (raw, sampleRate) = decoded
            if (raw.size < Analysis.WINDOW * 8) continue
            val (samples, sr) = Analysis.decimate(raw, sampleRate, Analysis.TARGET_SAMPLE_RATE)
            val stats = runCatching { Analysis.windowStats(samples, sr) }.getOrNull() ?: continue
            windows.add(stats)
        }

        if (windows.isEmpty()) return null
        return runCatching { Analysis.merge(song.id, windows) }.getOrNull()
    }

    /**
     * A few seconds of mono audio from part way into a file.
     *
     * Channels are averaged here rather than asked of the converter: a
     * stereo to mono conversion is one javax.sound providers frequently
     * decline, and being refused a probe because a file happens to be stereo
     * would leave most of a library unanalysed.
     *
     * Seeking is a skip over the decoded stream. Exact for constant bitrate
     * and close otherwise, which is all a probe needs - it is asking what a
     * song sounds like a third of the way through, not at a timestamp.
     */
    private fun decodeMono(file: File, fromMs: Long, seconds: Int): Pair<FloatArray, Int>? {
        val encoded = AudioSystem.getAudioInputStream(file)
        val base = encoded.format
        val rate = if (base.sampleRate > 0f) base.sampleRate else 44_100f
        val channels = if (base.channels > 0) base.channels else 2
        val target = AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED, rate, 16, channels, channels * 2, rate, false
        )
        val pcm = runCatching { AudioSystem.getAudioInputStream(target, encoded) }.getOrNull()
        if (pcm == null) {
            runCatching { encoded.close() }
            return null
        }

        pcm.use { stream ->
            val frameSize = channels * 2
            var toSkip = (fromMs / 1000.0 * rate).toLong() * frameSize
            while (toSkip > 0) {
                val skipped = stream.skip(toSkip)
                if (skipped <= 0) break
                toSkip -= skipped
            }

            val wantedFrames = (rate * seconds).toInt()
            val buffer = ByteArray(wantedFrames * frameSize)
            var filled = 0
            while (filled < buffer.size) {
                val read = stream.read(buffer, filled, buffer.size - filled)
                if (read <= 0) break
                filled += read
            }
            val frames = filled / frameSize
            if (frames <= 0) return null

            val out = FloatArray(frames)
            var at = 0
            for (f in 0 until frames) {
                var sum = 0
                for (c in 0 until channels) {
                    // Little endian, signed, as the target format asks for.
                    val lo = buffer[at].toInt() and 0xFF
                    val hi = buffer[at + 1].toInt()
                    sum += (hi shl 8) or lo
                    at += 2
                }
                out[f] = sum / (channels * 32768f)
            }
            return out to rate.toInt()
        }
    }
}
