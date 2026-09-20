package com.elchanan.rhythm.desktop

import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.engine.Lyrics
import java.io.File

/** A song's words: the plain text, and the timed form when there is one. */
data class Words(val plain: String, val lrc: String)

/**
 * Finding a song's lyrics on a desktop.
 *
 * The same two places the phone looks, in the same order, and the reading of
 * what is found is [Lyrics] in :engine - the ID3 and Vorbis parsing is not
 * repeated here.
 *
 * What is simpler is the looking. On Android a .lrc is not a media file, so
 * reaching one means a folder permission granted through the system picker
 * and a document tree walked to build an index. Here it is the file next to
 * the audio, and there is nothing to ask anyone for.
 */
object SongLyrics {

    /**
     * How much of the file to read when looking for embedded words.
     *
     * ID3v2 sits at the front and Vorbis comments near it, so the whole file
     * never needs reading - which matters, because this runs while something
     * is playing and a lossless album track is tens of megabytes.
     */
    private const val HEADER_BYTES = 3 * 1024 * 1024

    private val SIDECAR = listOf("lrc", "txt")

    fun find(song: SongEntity): Words? {
        val file = File(song.path)
        if (!file.isFile) return null
        sidecar(file)?.let { return it }
        return embedded(file)
    }

    /**
     * A .lrc or .txt beside the audio, which is what most collections carry.
     *
     * Checked first because someone who put a file there did it on purpose,
     * and it should win over whatever a tag happens to say.
     */
    private fun sidecar(file: File): Words? {
        val folder = file.parentFile ?: return null
        for (ext in SIDECAR) {
            val beside = File(folder, "${file.nameWithoutExtension}.$ext")
            if (!beside.isFile) continue
            val text = runCatching { beside.inputStream().use { Lyrics.readText(it) } }
                .getOrNull()
                ?.takeIf { it.isNotBlank() } ?: continue
            // A .txt can hold timestamps and a .lrc can hold none, so what it
            // is is decided by what is in it rather than by its extension.
            val timed = Lyrics.parseLrc(text).isNotEmpty()
            return if (timed) {
                Words(plain = Lyrics.stripTimestamps(text), lrc = text)
            } else {
                Words(plain = text.trim(), lrc = "")
            }
        }
        return null
    }

    private fun embedded(file: File): Words? {
        val head = runCatching {
            file.inputStream().use { stream ->
                val size = minOf(file.length(), HEADER_BYTES.toLong()).toInt()
                val buffer = ByteArray(size)
                var filled = 0
                while (filled < size) {
                    val read = stream.read(buffer, filled, size - filled)
                    if (read <= 0) break
                    filled += read
                }
                if (filled < size) buffer.copyOf(filled) else buffer
            }
        }.getOrNull() ?: return null

        val found = runCatching { Lyrics.parseId3(head) }.getOrNull()
            ?: runCatching { Lyrics.parseVorbis(head) }.getOrNull()
            ?: return null
        val (plain, lrc) = found
        if (plain.isBlank() && lrc.isBlank()) return null
        return Words(plain = plain, lrc = lrc)
    }
}
