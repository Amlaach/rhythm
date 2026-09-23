package com.elchanan.rhythm.playback

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.elchanan.rhythm.data.db.SongEntity

object MediaItems {

    private val ALBUM_ART_BASE: Uri = Uri.parse("content://media/external/audio/albumart")

    fun songUri(id: Long): Uri =
        ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)

    fun artworkUri(albumId: Long): Uri =
        ContentUris.withAppendedId(ALBUM_ART_BASE, albumId)

    /**
     * Albums that are only a folder: Android's name for untagged files is
     * the folder they sit in, so a folder of singles becomes one "album"
     * whose picture is one of its songs' covers. Shown for all of them, that
     * is the wrong cover on every other song - so these albums lend theirs to
     * nobody. Set when the library loads; empty until then, which is the old
     * behaviour.
     */
    @Volatile
    var looseAlbums: Set<Long> = emptySet()

    /** A folder-named album holding more than one artist is a folder of singles. */
    fun noteLibrary(songs: List<SongEntity>) {
        looseAlbums = songs.groupBy { it.albumId }.filter { (_, list) ->
            val first = list.first()
            val folder = first.folder.replace('\\', '/').trimEnd('/').substringAfterLast('/')
            val named = first.albumName.isBlank() || first.albumName.startsWith("<") ||
                first.albumName.trim().equals(folder.trim(), ignoreCase = true)
            named && list.map { it.artistKey }.distinct().size > 1
        }.keys
    }

    fun toMediaItem(song: SongEntity): MediaItem {
        val uri = songUri(song.id)
        return MediaItem.Builder()
            .setMediaId(song.id.toString())
            .setUri(uri)
            .setRequestMetadata(
                MediaItem.RequestMetadata.Builder()
                    .setMediaUri(uri)
                    .build()
            )
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artistName)
                    .setAlbumTitle(song.albumName)
                    // The file's own cover, which the player reads from the
                    // track, comes first either way; this is only the fallback.
                    .setArtworkUri(if (song.albumId in looseAlbums) null else artworkUri(song.albumId))
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build()
            )
            .build()
    }
}
