package com.elchanan.rhythm.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadataRetriever
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.key.Keyer
import coil.request.Options
import coil.size.Dimension
import com.elchanan.rhythm.data.ArtTrim
import com.elchanan.rhythm.playback.MediaItems
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A song's own cover rather than its album's.
 *
 * MediaStore caches one thumbnail per album, so a library of singles - downloads
 * whose album tag is empty all get filed under a single folder-named album -
 * ends up showing the same picture on every row. Reading the embedded picture
 * straight off the track gives each song the cover it actually ships with.
 *
 * Album tiles pass [songId] = -1 and keep using the MediaStore thumbnail, which
 * is the right source when the songs really do belong to one album.
 */
data class SongArt(val songId: Long, val albumId: Long)

/**
 * Whether the song may borrow its album's picture is part of the key: a cover
 * cached before the library marked its album as only a folder would otherwise
 * keep showing that other song's sleeve from the cache.
 */
class SongArtKeyer : Keyer<SongArt> {
    override fun key(data: SongArt, options: Options): String = when {
        data.songId <= 0 -> "album-art:${data.albumId}"
        data.albumId in MediaItems.looseAlbums -> "song-art:${data.songId}:own"
        else -> "song-art:${data.songId}:album"
    }
}

class SongArtFetcher(
    private val context: Context,
    private val data: SongArt,
    private val options: Options
) : Fetcher {

    override suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {
        val own = readTrack()
        // The album's picture only for a track that names an album. Android
        // files an untagged download under an album named after its folder,
        // and that album's picture is some other song's cover - the wrong
        // artwork people saw on singles. Better the app's own mark than a
        // stranger's sleeve.
        val bytes = own.picture
            ?: (if (own.hasAlbum && data.albumId !in MediaItems.looseAlbums) albumThumbnail() else null)
            ?: return@withContext null
        // Decoded here rather than handed to Coil as bytes, so the padding a
        // video thumbnail carries can be cut off before anything draws it -
        // once, for every place a cover appears: tiles, lists, the player,
        // the full-size view and the colours the player takes from it.
        val wanted = maxOf(
            (options.size.width as? Dimension.Pixels)?.px ?: 0,
            (options.size.height as? Dimension.Pixels)?.px ?: 0
        ).takeIf { it > 0 } ?: 1024
        val bitmap = decodeCover(bytes, wanted) ?: return@withContext null
        DrawableResult(
            drawable = BitmapDrawable(context.resources, bitmap),
            isSampled = true,
            dataSource = DataSource.DISK
        )
    }

    private class Track(val picture: ByteArray?, val hasAlbum: Boolean)

    /** The track's own cover, and whether it names an album at all. Album tiles have no track. */
    private fun readTrack(): Track {
        if (data.songId <= 0) return Track(null, hasAlbum = true)
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, MediaItems.songUri(data.songId))
            Track(
                retriever.embeddedPicture,
                hasAlbum = !retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).isNullOrBlank()
            )
        } catch (e: Exception) {
            // Unreadable file - fall through to the album thumbnail, as before.
            Track(null, hasAlbum = true)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun albumThumbnail(): ByteArray? {
        if (data.albumId <= 0) return null
        return runCatching {
            context.contentResolver
                .openInputStream(MediaItems.artworkUri(data.albumId))
                ?.use { it.readBytes() }
        }.getOrNull()
    }

    class Factory : Fetcher.Factory<SongArt> {
        override fun create(data: SongArt, options: Options, imageLoader: ImageLoader): Fetcher =
            SongArtFetcher(options.context, data, options)
    }
}

/**
 * A cover decoded at about [wanted] pixels on its long side, without the
 * bars a video thumbnail was padded out with (see [ArtTrim]).
 *
 * Decoded at no less than 256 even for a small request: the bars are found on
 * the decoded pixels, and a 32 pixel picture has too few of them to tell a bar
 * from the sleeve. A small request is scaled down after the bars are gone.
 */
internal fun decodeCover(bytes: ByteArray, wanted: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val decodeAt = maxOf(wanted, 256)
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    var sample = 1
    while (longest / (sample * 2) >= decodeAt) sample *= 2
    val decoded = BitmapFactory.decodeByteArray(
        bytes, 0, bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample }
    ) ?: return null
    val trimmed = trimPadding(decoded)
    val side = maxOf(trimmed.width, trimmed.height)
    if (wanted >= 128 || side <= wanted) return trimmed
    val scale = wanted.toFloat() / side
    return Bitmap.createScaledBitmap(
        trimmed,
        maxOf(1, (trimmed.width * scale).toInt()),
        maxOf(1, (trimmed.height * scale).toInt()),
        true
    )
}

/** [bitmap] without a video thumbnail's padding bars, or itself when it has none. */
internal fun trimPadding(bitmap: Bitmap): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    // Square pictures are real sleeves; not worth reading their pixels.
    if (w * 10 in h * 9..h * 11) return bitmap
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    val c = ArtTrim.crop(pixels, w, h) ?: return bitmap
    return Bitmap.createBitmap(bitmap, c[0], c[1], c[2] - c[0], c[3] - c[1])
}
