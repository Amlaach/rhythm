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
import kotlin.math.max
import kotlin.math.min

/**
 * Getting audio off an Android device and into [Analysis].
 *
 * This is the half of the pipeline that needs a platform: MediaCodec decodes
 * the probes and YAMNet labels them. Everything measured from those samples
 * is [Analysis]'s work over in :engine, shared unchanged with the desktop
 * build, which does the same job through javax.sound instead.
 */
object AudioAnalyzer {

    private const val DECODE_TIMEOUT_US = 8_000L

    /**
     * Returns null when the file cannot be decoded - a corrupt file simply
     * stays unanalysed instead of stopping the whole pass.
     */
    fun analyze(context: Context, song: SongEntity): AudioFeatureEntity? {
        val uri = MediaItems.songUri(song.id)
        val windows = ArrayList<Analysis.WindowStats>(Analysis.PROBE_POINTS.size)
        // The same probes at the rate the model was trained on. Decoding once
        // and resampling twice is far cheaper than decoding the file again.
        val forTagging = ArrayList<FloatArray>(Analysis.PROBE_POINTS.size)

        for (fraction in Analysis.PROBE_POINTS) {
            val startUs = Analysis.probeStart(song.durationMs, fraction)
            val decoded = runCatching {
                decodeMono(context, uri, startUs, Analysis.PROBE_SECONDS)
            }.getOrNull() ?: continue
            val (raw, sampleRate) = decoded
            if (raw.size < Analysis.WINDOW * 8) continue
            val (samples, sr) = Analysis.decimate(raw, sampleRate, Analysis.TARGET_SAMPLE_RATE)
            val stats = runCatching { Analysis.windowStats(samples, sr) }.getOrNull() ?: continue
            windows.add(stats)
            runCatching {
                forTagging.add(Analysis.decimate(raw, sampleRate, AudioTagger.SAMPLE_RATE).first)
            }
        }

        if (windows.isEmpty()) return null
        val merged = runCatching { Analysis.merge(song.id, windows) }.getOrNull() ?: return null

        // Tagging is best effort. A device where the model will not load, or a
        // build that ships without it, still gets every measured feature - the
        // track is simply left without labels rather than left unanalysed.
        val tags = runCatching {
            val tagger = tagger(context) ?: return@runCatching ""
            val waveform = Analysis.concat(forTagging)
            val scores = tagger.scores(waveform) ?: return@runCatching ""
            AudioTags.compress(scores)
        }.getOrDefault("")

        return if (tags.isEmpty()) merged else merged.copy(tags = tags)
    }

    @Volatile
    private var tagger: AudioTagger? = null

    @Volatile
    private var taggerAttempted = false

    /**
     * Loads the model on first use and keeps it.
     *
     * Building an interpreter means mapping four megabytes and allocating its
     * working memory; doing that per song would cost more than the inference.
     * A failure is remembered too, so a device that cannot load it does not
     * retry once per track for the length of the library.
     */
    private fun tagger(context: Context): AudioTagger? {
        if (taggerAttempted) return tagger
        synchronized(this) {
            if (!taggerAttempted) {
                taggerAttempted = true
                tagger = AudioTagger.create(context.applicationContext)
            }
        }
        return tagger
    }

    /** Frees the model once a pass is over. */
    fun releaseTagger() {
        synchronized(this) {
            tagger?.close()
            tagger = null
            taggerAttempted = false
        }
    }

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
}
