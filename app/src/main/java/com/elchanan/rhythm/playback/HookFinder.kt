package com.elchanan.rhythm.playback

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.engine.Hook
import android.os.Process
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Where each song's taste starts - its chorus, found by [Hook] - worked out
 * once and remembered.
 *
 * The song is decoded as it streams and handed to [Hook.Frames] a buffer at a
 * time; nothing of the audio is kept. The answers are kept in a small file of
 * "song=milliseconds" lines, so a song is read once in its life and the
 * samples screen can start straight on the chorus the next time.
 */
object HookFinder {

    /** Raised when the way hooks are found changes, so old answers are found again. */
    // 2: the start is brought back to where the chorus begins.
    private const val VERSION = 2

    private val lock = Any()
    private var known: MutableMap<Long, Long>? = null

    private fun file(context: Context) = File(context.filesDir, "hooks-v$VERSION.txt")

    private fun load(context: Context): MutableMap<Long, Long> {
        known?.let { return it }
        val map = HashMap<Long, Long>()
        // Answers from an earlier way of finding them are only in the way.
        runCatching {
            for (old in 1 until VERSION) File(context.filesDir, "hooks-v$old.txt").delete()
        }
        runCatching {
            file(context).takeIf { it.isFile }?.forEachLine { line ->
                val eq = line.indexOf('=')
                if (eq > 0) {
                    val id = line.substring(0, eq).toLongOrNull()
                    val ms = line.substring(eq + 1).toLongOrNull()
                    if (id != null && ms != null) map[id] = ms
                }
            }
        }
        known = map
        return map
    }

    /** Already worked out, without reading anything. */
    fun cached(context: Context, songId: Long): Long? = synchronized(lock) { load(context)[songId] }

    /**
     * Where [song]'s taste starts, in milliseconds. Found the first time and
     * remembered; a third of the way in when the song cannot be read or is
     * too short to tell.
     */
    suspend fun find(context: Context, song: SongEntity): Long {
        cached(context, song.id)?.let { return it }
        return withContext(Dispatchers.IO) { findNow(context, song) }
    }

    /** [find], on the calling thread. */
    private fun findNow(context: Context, song: SongEntity): Long {
        cached(context, song.id)?.let { return it }
        val found = runCatching { scan(context, song) }.getOrNull()
        val ms = found?.let { (it * 1000).toLong() } ?: (song.durationMs / 3)
        synchronized(lock) {
            load(context)[song.id] = ms
            runCatching { file(context).appendText("${song.id}=$ms\n") }
        }
        return ms
    }

    /**
     * One thread of its own, at background priority, for choruses found
     * ahead of time: the first tastes a few moments after the app opens, and
     * the next ten while someone is tasting. It gives way to everything the
     * listener is doing, and works through one song at a time.
     */
    private val ahead = Executors.newSingleThreadExecutor { job ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            job.run()
        }, "hooks").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private val aheadScope = CoroutineScope(SupervisorJob() + ahead)
    private var aheadJob: Job? = null

    /**
     * Finds the choruses of [songs], in order, before they are needed. A new
     * call replaces the one before: what matters is always what is next now.
     */
    fun prefetch(context: Context, songs: List<SongEntity>) {
        val app = context.applicationContext
        aheadJob?.cancel()
        aheadJob = aheadScope.launch {
            for (song in songs) {
                if (!isActive) break
                if (cached(app, song.id) == null) findNow(app, song)
            }
        }
    }

    private fun scan(context: Context, song: SongEntity): Double? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, MediaItems.songUri(song.id), null)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i
                    format = f
                    break
                }
            }
            if (track < 0 || format == null) return null
            extractor.selectTrack(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            var rate = intOf(format, MediaFormat.KEY_SAMPLE_RATE, 44100)
            var channels = intOf(format, MediaFormat.KEY_CHANNEL_COUNT, 2)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var frames: Hook.Frames? = null
            var chunk = FloatArray(8192)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var guard = 0
            while (!outputDone && guard < 400_000) {
                guard++
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)
                        val read = if (buffer == null) -1 else extractor.readSampleData(buffer, 0)
                        if (read < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, read, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        rate = intOf(of, MediaFormat.KEY_SAMPLE_RATE, rate)
                        channels = intOf(of, MediaFormat.KEY_CHANNEL_COUNT, channels)
                        encoding = if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else {
                            AudioFormat.ENCODING_PCM_16BIT
                        }
                    }
                    outIndex >= 0 -> {
                        val buffer = codec.getOutputBuffer(outIndex)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val target = frames ?: Hook.Frames(rate).also { frames = it }
                            val needed = info.size
                            if (chunk.size < needed) chunk = FloatArray(needed)
                            val n = mono(buffer, channels, encoding, chunk)
                            target.add(chunk, n)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            return frames?.build()?.let { Hook.find(it) }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /** The buffer as mono samples in [out]; how many. */
    private fun mono(buffer: ByteBuffer, channels: Int, encoding: Int, out: FloatArray): Int {
        buffer.order(ByteOrder.nativeOrder())
        val ch = maxOf(1, channels)
        var n = 0
        when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val fb = buffer.asFloatBuffer()
                val count = fb.remaining() / ch
                for (i in 0 until count) {
                    var sum = 0f
                    for (c in 0 until ch) sum += fb.get()
                    if (n < out.size) out[n++] = sum / ch
                }
            }
            AudioFormat.ENCODING_PCM_8BIT -> {
                val count = buffer.remaining() / ch
                for (i in 0 until count) {
                    var sum = 0f
                    for (c in 0 until ch) sum += (buffer.get().toInt() - 128) / 128f
                    if (n < out.size) out[n++] = sum / ch
                }
            }
            else -> {
                val sb = buffer.asShortBuffer()
                val count = sb.remaining() / ch
                for (i in 0 until count) {
                    var sum = 0f
                    for (c in 0 until ch) sum += sb.get() / 32768f
                    if (n < out.size) out[n++] = sum / ch
                }
            }
        }
        return n
    }

    private fun intOf(format: MediaFormat, key: String, fallback: Int): Int =
        if (format.containsKey(key)) runCatching { format.getInteger(key) }.getOrDefault(fallback) else fallback

    private const val TIMEOUT_US = 8_000L
}
