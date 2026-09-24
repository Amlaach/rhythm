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

    /** Whether [noteLibrary] has run in this process. */
    @Volatile
    var noted: Boolean = false
        private set

    /**
     * Which albums may lend their picture to a song without one of its own:
     * only those that look like a real release.
     *
     * A folder of downloads often comes as one "album" - the folder's name,
     * or a tag every file got from the same site - and its picture is one of
     * its songs' covers, so every other song in the folder showed that one
     * song's picture. A real album numbers its tracks; a folder of singles
     * does not. So an album lends its picture only when at least half its
     * songs carry a track number and it is not a folder-named mix of
     * artists. A song on its own lends only to itself, which is harmless.
     *
     * Different track numbers, not just any: a download site stamps its own
     * name as the album and "1" as the track on every file it serves, so a
     * folder of its singles counted as numbered and lent one song's cover to
     * all the rest. Two discs of one album still number more than half of it
     * differently.
     */
    fun noteLibrary(songs: List<SongEntity>) {
        looseAlbums = songs.groupBy { it.albumId }.filter { (_, list) ->
            if (list.size == 1) return@filter false
            val first = list.first()
            val folder = first.folder.replace('\\', '/').trimEnd('/').substringAfterLast('/')
            val unnamed = first.albumName.isBlank() || first.albumName.startsWith("<")
            val folderNamed = first.albumName.trim().equals(folder.trim(), ignoreCase = true)
            val mixed = list.map { it.artistKey }.distinct().size > 1
            val numbered = list.map { it.trackNumber }.filter { it > 0 }.distinct().size * 2 >= list.size
            unnamed || (folderNamed && mixed) || !numbered
        }.keys
        noted = true
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
