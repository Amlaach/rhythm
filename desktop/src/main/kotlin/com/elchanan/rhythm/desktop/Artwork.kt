package com.elchanan.rhythm.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.res.loadImageBitmap
import com.elchanan.rhythm.data.ArtTrim
import com.elchanan.rhythm.data.db.SongEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.IRect
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Cover art, read out of the files themselves.
 *
 * Nothing is downloaded and nothing is stored: the picture is already inside
 * the mp3, and a copy of it in the database would be the same bytes again for
 * every track on an album. It is read on demand and kept in memory.
 *
 * Keyed by song, not by album. Keying by album read one track and showed its
 * picture on all the others, and an "album" is not always a record: a folder
 * of singles with one album name showed one song's cover on every song in
 * it, and a first track with no picture left the rest blank too.
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

    private fun keyOf(song: SongEntity): Long = song.id

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
            trimPadding(ByteArrayInputStream(bytes).use { loadImageBitmap(it) })
        }.getOrNull()
    }

    /**
     * The cover without the bars a video thumbnail was padded out with, the
     * same cut the phone makes (see [ArtTrim]): shown whole they framed the
     * sleeve in a colour that had nothing to do with it, and cropped to a
     * square they left slivers of it at the sides.
     */
    private fun trimPadding(image: ImageBitmap): ImageBitmap {
        val w = image.width
        val h = image.height
        // Square pictures are real sleeves; not worth reading their pixels.
        if (w * 10 in h * 9..h * 11) return image
        val pixels = IntArray(w * h)
        image.readPixels(pixels, 0, 0, w, h)
        val c = ArtTrim.crop(pixels, w, h) ?: return image
        val part = Bitmap()
        if (!image.asSkiaBitmap().extractSubset(part, IRect.makeLTRB(c[0], c[1], c[2], c[3]))) return image
        return part.asComposeImageBitmap()
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
