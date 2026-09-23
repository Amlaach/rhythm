package com.elchanan.rhythm.ui.components

import android.content.Context
import android.media.MediaMetadataRetriever
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.key.Keyer
import coil.request.Options
import com.elchanan.rhythm.playback.MediaItems
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Buffer

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
    private val data: SongArt
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
        SourceResult(
            source = ImageSource(Buffer().write(bytes), context),
            mimeType = null,
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
            SongArtFetcher(options.context, data)
    }
}
