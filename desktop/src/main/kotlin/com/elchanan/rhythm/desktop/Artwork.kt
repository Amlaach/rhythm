package com.elchanan.rhythm.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.loadImageBitmap
import com.elchanan.rhythm.data.db.SongEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Cover art, read out of the files themselves.
 *
 * Nothing is downloaded and nothing is stored: the picture is already inside
 * the mp3, and a copy of it in the database would be the same bytes again for
 * every track on an album. It is read on demand and kept in memory.
 *
 * Keyed by album rather than by song where there is an album, because that is
 * how the art actually varies - twelve tracks of one record hold twelve
 * copies of one picture, and reading the first of them is reading all of them.
 */
object Artwork {

    /**
     * How many pictures to keep decoded.
     *
     * Decoded, not compressed: a 600 by 600 cover is well over a megabyte in
     * memory whatever it weighed on disk, so this is the number that decides
     * how much of the heap the shelves cost. A few hundred covers the screens
     * anyone can actually scroll through.
     */
    private const val MAX = 240

    private val lock = Any()
    private val cache = object : LinkedHashMap<Long, ImageBitmap?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ImageBitmap?>) =
            size > MAX
    }

    private fun keyOf(song: SongEntity): Long =
        if (song.albumId != 0L) song.albumId else song.id

    /** What is already decoded, so a scroll back up does not flicker. */
    fun cached(song: SongEntity): ImageBitmap? = synchronized(lock) { cache[keyOf(song)] }

    /**
     * Reads the picture, or remembers that there is not one.
     *
     * Misses are cached too. Without that, every file with no artwork - which
     * in a library of downloads is most of them - would be opened and parsed
     * again on every single scroll past it.
     */
    fun load(song: SongEntity): ImageBitmap? {
        val key = keyOf(song)
        synchronized(lock) { if (cache.containsKey(key)) return cache[key] }
        val image = read(File(song.path))
        synchronized(lock) { cache[key] = image }
        return image
    }

    private fun read(file: File): ImageBitmap? {
        if (!file.isFile) return null
        return runCatching {
            val bytes = AudioFileIO.read(file).tag?.firstArtwork?.binaryData ?: return null
            if (bytes.isEmpty()) return null
            ByteArrayInputStream(bytes).use { loadImageBitmap(it) }
        }.getOrNull()
    }
}

/**
 * The cover for a song, once it has been read.
 *
 * Returns whatever is already decoded straight away and fills the rest in
 * afterwards, so scrolling shows pictures that are in memory immediately and
 * only waits for the ones that are not.
 */
@Composable
fun rememberArtwork(song: SongEntity?): ImageBitmap? {
    var image by remember(song?.id) { mutableStateOf(song?.let { Artwork.cached(it) }) }
    LaunchedEffect(song?.id) {
        if (image == null && song != null) {
            image = withContext(Dispatchers.IO) { Artwork.load(song) }
        }
    }
    return image
}
