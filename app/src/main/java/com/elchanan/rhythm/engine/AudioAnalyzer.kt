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

        val starts = Analysis.PROBE_POINTS.map { Analysis.probeStart(song.durationMs, it) }.distinct().sorted()
        val allDecoded = runCatching { decodeProbesMono(context, uri, starts, Analysis.PROBE_SECONDS) }.getOrDefault(emptyMap())

        for (startUs in starts) {
            val decoded = allDecoded[startUs] ?: runCatching {
                decodeMono(context, uri, startUs, Analysis.PROBE_SECONDS)
            }.getOrNull() ?: continue
            val (raw, sampleRate) = decoded
            if (raw.size < Analysis.WINDOW * 8) continue
            val (samples, sr) = Analysis.decimate(raw, sampleRate, Analysis.TARGET_SAMPLE_RATE)
            val stats = runCatching { Analysis.windowStats(samples, sr) }.getOrNull() ?: continue
            windows.add(stats)
            runCatching {
                forTagging.add(Analysis.resampleMono(raw, sampleRate, AudioTagger.SAMPLE_RATE))
            }
        }

        if (windows.isEmpty()) return null
        val merged = runCatching { Analysis.merge(song.id, windows) }.getOrNull() ?: return null

        // Tagging is best effort. A device where the model will not load, or a
        // build that ships without it, still gets every measured feature - the
        // track is simply left without labels rather than left unanalysed.
        val heard = runCatching {
            val probes = Analysis.concat(forTagging)
            synchronized(yamnetLock) { tagger(context)?.listen(probes) }
        }.getOrNull()
        val tags = heard?.let { runCatching { AudioTags.compress(it.scores) }.getOrNull() }.orEmpty()
        // Always something: a print, or the mark that one was attempted. An
        // empty print is what queues a song for another pass, so leaving it
        // empty after a failure would queue it for ever.
        val print = heard?.print?.let { runCatching { SoundPrint.pack(it) }.getOrNull() }
            ?: SoundPrint.TRIED

        // The music model reads the same probes, each on its own - a patch
        // that ran across the seam between two probes would be a splice of two
        // moments of the song that never sounded together.
        //
        // A build without the model leaves the print empty rather than marked
        // tried: the queue only asks for music prints when the model is there,
        // so the songs wait for a build that has it instead of being written
        // off by one that does not.
        val available = musicAvailable(context)
        val music = if (!available) null else runCatching {
            synchronized(musicLock) { musicTagger(context)?.listen(forTagging) }
        }.getOrNull()
        val musicPrint = when {
            !available -> ""
            else -> music?.let { runCatching { MusicPrint.pack(it.print) }.getOrNull() } ?: MusicPrint.TRIED
        }
        val musicMoods = music?.let { MusicMoods.encode(it.moods) }.orEmpty()

        return merged.copy(tags = tags, soundPrint = print, musicPrint = musicPrint, musicMoods = musicMoods)
    }

    @Volatile
    private var hasMusicModel: Boolean? = null

    /** Whether this build ships the music model. Asked once; an apk's assets do not change. */
    fun musicAvailable(context: Context): Boolean = hasMusicModel ?: runCatching {
        context.assets.list("music")?.contains("effnet.tflite") == true
    }.getOrDefault(false).also { hasMusicModel = it }

    @Volatile
    private var music: MusicTagger? = null

    @Volatile
    private var musicAttempted = false

    private fun musicTagger(context: Context): MusicTagger? {
        if (musicAttempted) return music
        synchronized(this) {
            if (!musicAttempted) {
                musicAttempted = true
                music = MusicTagger.create(context.applicationContext, threads)
            }
        }
        return music
    }

    /**
     * Only the music model, for a song whose other measurements are already
     * stored: the probes are decoded and resampled for it and nothing else is
     * computed. Null when the file will not decode now.
     */
    fun addMusic(context: Context, song: SongEntity, existing: AudioFeatureEntity): AudioFeatureEntity? {
        if (!musicAvailable(context)) return existing
        val uri = MediaItems.songUri(song.id)
        val starts = Analysis.PROBE_POINTS.map { Analysis.probeStart(song.durationMs, it) }.distinct().sorted()
        val allDecoded = runCatching { decodeProbesMono(context, uri, starts, Analysis.PROBE_SECONDS) }.getOrDefault(emptyMap())
        val probes = ArrayList<FloatArray>(starts.size)
        for (startUs in starts) {
            val decoded = allDecoded[startUs] ?: runCatching {
                decodeMono(context, uri, startUs, Analysis.PROBE_SECONDS)
            }.getOrNull() ?: continue
            val (raw, sampleRate) = decoded
            if (raw.size < Analysis.WINDOW * 8) continue
            runCatching { probes.add(Analysis.resampleMono(raw, sampleRate, MusicMel.SAMPLE_RATE)) }
        }
        if (probes.isEmpty()) return null
        val music = runCatching { synchronized(musicLock) { musicTagger(context)?.listen(probes) } }.getOrNull()
        val print = music?.let { runCatching { MusicPrint.pack(it.print) }.getOrNull() } ?: MusicPrint.TRIED
        return existing.copy(musicPrint = print, musicMoods = music?.let { MusicMoods.encode(it.moods) }.orEmpty())
    }

    /**
     * One song at a time through each model.
     *
     * The fast pass works on several songs at once: decoding and measuring
     * share nothing, but an interpreter is not safe to call from two threads,
     * so each model takes its songs in turn - while the next song is already
     * being decoded. Closing a model takes its lock too, so it is never
     * closed under a song still in it. Always taken in this order - the
     * YAMNet lock, the music lock, then the object - so no two threads can
     * each hold what the other waits for.
     */
    private val yamnetLock = Any()
    private val musicLock = Any()

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
                tagger = AudioTagger.create(context.applicationContext, threads)
            }
        }
        return tagger
    }

    /** How many threads the models are built with. See [useThreads]. */
    @Volatile
    private var threads = 2

    /**
     * Threads for the models: two, and more while the phone charges.
     *
     * Two keeps a phone in someone's hand responsive, and on battery it is
     * also the cheaper pace. On the charger the pass may take more of the
     * phone - the first run over a large library is hours, and that is
     * where it is usually left to run. Three on a phone of four to seven
     * cores, four on eight or more; never the whole phone, so the screen and
     * the player keep a core of their own. The pass still runs at
     * background priority either way.
     */
    fun threadsFor(charging: Boolean): Int {
        if (!charging) return 2
        val cores = Runtime.getRuntime().availableProcessors()
        return when {
            cores >= 8 -> 4
            cores >= 4 -> 3
            else -> 2
        }
    }

    /**
     * Sets the thread count for the models; ones already open with another
     * count are closed and reopen with the new one on the next song.
     * Threads divide the same arithmetic between cores - what the models
     * compute does not depend on how many there are.
     */
    fun useThreads(count: Int) {
        if (count == threads) return
        synchronized(yamnetLock) {
            synchronized(musicLock) {
                synchronized(this) {
                    if (count == threads) return
                    threads = count
                    closeModels()
                }
            }
        }
    }

    /**
     * Threads for the models in the fast pass: what [threadsFor] gives on
     * the charger, charger or not.
     */
    fun fastThreads(): Int = threadsFor(charging = true)

    /**
     * How many songs the fast pass works on at once: one decoding while
     * another is in a model, and a third on phones with the cores for it.
     */
    fun fastSongs(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        return when {
            cores >= 8 -> 3
            cores >= 4 -> 2
            else -> 1
        }
    }

    /** Frees the model once a pass is over. */
    fun releaseTagger() {
        synchronized(yamnetLock) {
            synchronized(musicLock) {
                synchronized(this) { closeModels() }
            }
        }
    }

    private fun closeModels() {
        tagger?.close()
        tagger = null
        taggerAttempted = false
        music?.close()
        music = null
        musicAttempted = false
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

    /**
     * Decodes multiple probe points with a single MediaExtractor and MediaCodec instance.
     * Reusing the codec across all probes saves hundreds of milliseconds and massive IPC overhead per song.
     */
    internal fun decodeProbesMono(
        context: Context,
        uri: Uri,
        startsUs: List<Long>,
        seconds: Int
    ): Map<Long, Pair<FloatArray, Int>> {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val results = HashMap<Long, Pair<FloatArray, Int>>(startsUs.size)
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
            val format = inputFormat ?: return emptyMap()
            if (track < 0) return emptyMap()
            extractor.selectTrack(track)

            val mime = format.getString(MediaFormat.KEY_MIME) ?: return emptyMap()
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            var sampleRate = intOrDefault(format, MediaFormat.KEY_SAMPLE_RATE, 44100)
            var channels = intOrDefault(format, MediaFormat.KEY_CHANNEL_COUNT, 2)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            val info = MediaCodec.BufferInfo()

            for (startUs in startsUs) {
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                codec.flush()

                val out = Samples()
                var wanted = sampleRate * seconds
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
                if (out.size > 0) {
                    results[startUs] = out.trimmed() to sampleRate
                }
            }
            return results
        } catch (_: Throwable) {
            return results
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /** Mono samples from [startUs] on, for [seconds]; also used by the player to find silence at the end. */
    internal fun decodeMono(
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
