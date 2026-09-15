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

class SongArtKeyer : Keyer<SongArt> {
    override fun key(data: SongArt, options: Options): String =
        if (data.songId > 0) "song-art:${data.songId}" else "album-art:${data.albumId}"
}

class SongArtFetcher(
    private val context: Context,
    private val data: SongArt
) : Fetcher {

    override suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {
        val bytes = embeddedPicture() ?: albumThumbnail() ?: return@withContext null
        SourceResult(
            source = ImageSource(Buffer().write(bytes), context),
            mimeType = null,
            dataSource = DataSource.DISK
        )
    }

    private fun embeddedPicture(): ByteArray? {
        if (data.songId <= 0) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, MediaItems.songUri(data.songId))
            retriever.embeddedPicture
        } catch (e: Exception) {
            // Unreadable or tagless file - fall through to the album thumbnail.
            null
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
