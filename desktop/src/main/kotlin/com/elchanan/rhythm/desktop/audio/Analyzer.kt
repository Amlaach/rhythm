package com.elchanan.rhythm.desktop.audio

import com.elchanan.rhythm.data.db.AudioFeatureEntity
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.engine.Analysis
import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
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
        val windows = runCatching { probe(file, song.durationMs) }.getOrNull().orEmpty()
        if (windows.isEmpty()) return null
        return runCatching { Analysis.merge(song.id, windows) }.getOrNull()
    }

    /**
     * The eight probes, from one pass over the file.
     *
     * This used to open the file once per probe and skip to each one in
     * turn, which is what the phone does - except that the phone can seek.
     * Here the decoder cannot: skipping an AudioInputStream decodes the audio
     * and throws it away, so reaching the probe at 87% meant decoding 87% of
     * the song. Eight probes that way came to more than four times the length
     * of the track, per track, and a library took hours.
     *
     * One pass forward instead. The probes are in ascending order, so the
     * stream is only ever skipped from where it already is to where the next
     * one starts, and the whole file is read at most once - and not even that,
     * since it stops after the last probe. Same eight measurements, roughly a
     * fifth of the decoding.
     *
     * A file of unknown length puts every probe at zero; those collapse to
     * one rather than measuring the same four seconds eight times.
     */
    private fun probe(file: File, durationMs: Long): List<Analysis.WindowStats> {
        val starts = Analysis.PROBE_POINTS
            .map { Analysis.probeStart(durationMs, it) / 1000L }
            .distinct()
            .sorted()
        if (starts.isEmpty()) return emptyList()

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
            return emptyList()
        }

        val out = ArrayList<Analysis.WindowStats>(starts.size)
        pcm.use { stream ->
            val frameSize = channels * 2
            val wantedFrames = (rate * Analysis.PROBE_SECONDS).toInt()
            val buffer = ByteArray(wantedFrames * frameSize)
            val scratch = ByteArray(SCRATCH_BYTES)
            // Where the stream head is, in frames from the start.
            var atFrame = 0L

            for (startMs in starts) {
                val wantFrame = (startMs / 1000.0 * rate).toLong()
                if (!skipTo(stream, wantFrame - atFrame, frameSize, scratch)) break
                atFrame = maxOf(atFrame, wantFrame)

                var filled = 0
                while (filled < buffer.size) {
                    val read = stream.read(buffer, filled, buffer.size - filled)
                    if (read <= 0) break
                    filled += read
                }
                val frames = filled / frameSize
                if (frames <= 0) break
                atFrame += frames

                val raw = toMono(buffer, frames, channels)
                if (raw.size < Analysis.WINDOW * 8) continue
                val (samples, sr) =
                    Analysis.decimate(raw, rate.toInt(), Analysis.TARGET_SAMPLE_RATE)
                val stats = runCatching { Analysis.windowStats(samples, sr) }.getOrNull()
                if (stats != null) out.add(stats)
            }
        }
        return out
    }

    /**
     * Moves the head forward by a number of frames.
     *
     * By reading and throwing away rather than by skip(). The decoding
     * happens either way - these are converted streams and there is nothing
     * to seek over - but skip() on several of the SPI providers walks the
     * stream in very small steps, and on one of them a byte at a time, which
     * costs more than the decoding it is skipping. A read into a scratch
     * buffer asks for a lot at once and lets the provider decide.
     *
     * @return false when the stream ran out, which ends the pass: every
     *   remaining probe is further in than this one.
     */
    private fun skipTo(
        stream: AudioInputStream,
        frames: Long,
        frameSize: Int,
        scratch: ByteArray
    ): Boolean {
        if (frames <= 0) return true
        var left = frames * frameSize
        while (left > 0) {
            val want = minOf(left, scratch.size.toLong()).toInt()
            val read = stream.read(scratch, 0, want)
            if (read <= 0) return false
            left -= read
        }
        return true
    }

    /** Big enough that a skip is a few dozen reads rather than thousands. */
    private const val SCRATCH_BYTES = 256 * 1024

    /**
     * Channels averaged here rather than asked of the converter: a stereo to
     * mono conversion is one javax.sound providers frequently decline, and
     * being refused a probe because a file happens to be stereo would leave
     * most of a library unanalysed.
     */
    private fun toMono(buffer: ByteArray, frames: Int, channels: Int): FloatArray {
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
        return out
    }
}
