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
                    .setArtworkUri(artworkUri(song.albumId))
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build()
            )
            .build()
    }
}
