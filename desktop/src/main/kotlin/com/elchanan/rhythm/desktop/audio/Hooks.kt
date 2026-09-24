package com.elchanan.rhythm.desktop.audio

import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.engine.Hook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem

/**
 * Where each song's taste starts on Windows: the phone's HookFinder, with
 * javax.sound in place of MediaCodec. The same Hook reads the same pitch
 * classes and loudness, so a song is tasted from the same place on both.
 *
 * The song is decoded as it streams and nothing of the audio is kept. The
 * answers go into a small file of "song=milliseconds" lines beside the
 * library, so a song is read once in its life.
 */
object Hooks {

    /** Raised with the phone's, when the way hooks are found changes. */
    private const val VERSION = 2

    private val lock = Any()
    private var known: MutableMap<Long, Long>? = null
    private var dir: File? = null

    /** Where the answers are kept; set once, from the store's folder. */
    fun init(folder: File) {
        dir = folder
    }

    private fun file(): File? = dir?.let { File(it, "hooks-v$VERSION.txt") }

    private fun load(): MutableMap<Long, Long> {
        known?.let { return it }
        val map = HashMap<Long, Long>()
        runCatching {
            file()?.takeIf { it.isFile }?.forEachLine { line ->
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

    fun cached(songId: Long): Long? = synchronized(lock) { load()[songId] }

    /** Where [song]'s taste starts; a third of the way in when it cannot be read. */
    suspend fun find(song: SongEntity): Long {
        cached(song.id)?.let { return it }
        return withContext(Dispatchers.IO) { findNow(song) }
    }

    private fun findNow(song: SongEntity): Long {
        cached(song.id)?.let { return it }
        val found = runCatching { scan(File(song.path)) }.getOrNull()
        val ms = found?.let { (it * 1000).toLong() } ?: (song.durationMs / 3)
        synchronized(lock) {
            load()[song.id] = ms
            runCatching { file()?.appendText("${song.id}=$ms\n") }
        }
        return ms
    }

    /** One low-priority thread for choruses found ahead, one song at a time. */
    private val ahead = Executors.newSingleThreadExecutor { job ->
        Thread(job, "hooks").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }.asCoroutineDispatcher()

    private val aheadScope = CoroutineScope(SupervisorJob() + ahead)
    private var aheadJob: Job? = null

    /** Finds the choruses of [songs] before they are needed; a new call replaces the last. */
    fun prefetch(songs: List<SongEntity>) {
        aheadJob?.cancel()
        aheadJob = aheadScope.launch {
            for (song in songs) {
                if (!isActive) break
                if (cached(song.id) == null) findNow(song)
            }
        }
    }

    private fun scan(file: File): Double? {
        val encoded = AudioSystem.getAudioInputStream(file)
        val base = encoded.format
        val rate = if (base.sampleRate > 0f) base.sampleRate else 44_100f
        val channels = if (base.channels > 0) base.channels else 2
        val target = AudioFormat(AudioFormat.Encoding.PCM_SIGNED, rate, 16, channels, channels * 2, rate, false)
        val pcm = runCatching { AudioSystem.getAudioInputStream(target, encoded) }.getOrNull()
        if (pcm == null) {
            runCatching { encoded.close() }
            return null
        }
        val frames = Hook.Frames(rate.toInt())
        pcm.use { stream ->
            val frameSize = channels * 2
            val buffer = ByteArray(8192 * frameSize)
            val mono = FloatArray(8192)
            while (true) {
                val read = stream.read(buffer, 0, buffer.size)
                if (read <= 0) break
                val count = read / frameSize
                var at = 0
                for (f in 0 until count) {
                    var sum = 0
                    for (c in 0 until channels) {
                        val lo = buffer[at].toInt() and 0xFF
                        val hi = buffer[at + 1].toInt()
                        sum += (hi shl 8) or lo
                        at += 2
                    }
                    mono[f] = sum / (channels * 32768f)
                }
                frames.add(mono, count)
            }
        }
        return Hook.find(frames.build())
    }
}
