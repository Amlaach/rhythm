package com.elchanan.rhythm.data

import android.content.Context
import android.database.Cursor
import android.os.Build
import android.provider.MediaStore
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.engine.Names
import java.io.File
import java.util.Locale

/**
 * Reads the device's audio library out of MediaStore.
 * No network, no metadata service - everything the app knows comes from here
 * plus what the user types in later.
 */
object MediaScanner {

    /**
     * Every audio file the device says is music, whatever its length.
     *
     * The length filter deliberately does not happen here any more. MediaStore
     * inserts a row the moment it notices a file and fills the metadata in
     * afterwards, so for a while a real song has a duration of zero or none at
     * all - and `DURATION >= 45000` is false for both, in SQL where a
     * comparison against null is never true. A library still being indexed was
     * therefore scanned as though most of it did not exist.
     *
     * Deciding what to keep is the repository's job, where a file of unknown
     * length can be kept rather than silently dropped, and where each filter
     * can say how much it removed.
     */
    fun scan(context: Context): List<SongEntity> {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        val projection = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.SIZE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            projection.add(MediaStore.Audio.Media.GENRE)
        }

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"

        val out = ArrayList<SongEntity>(512)
        val cursor: Cursor = context.contentResolver.query(
            collection, projection.toTypedArray(), selection, null, null
        ) ?: return out

        cursor.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val trackCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val yearCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val dataCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val addedCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val genreCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                c.getColumnIndex(MediaStore.Audio.Media.GENRE)
            } else -1

            while (c.moveToNext()) {
                val path = c.getString(dataCol) ?: ""
                val rawTitle = c.getString(titleCol)?.trim().orEmpty()
                val title = if (rawTitle.isNotEmpty()) rawTitle else {
                    File(path).nameWithoutExtension.ifEmpty { "ללא שם" }
                }
                val rawArtist = c.getString(artistCol)?.trim().orEmpty()
                val artistDisplay = when {
                    rawArtist.isEmpty() -> "אמן לא ידוע"
                    rawArtist == "<unknown>" -> "אמן לא ידוע"
                    else -> rawArtist
                }
                val primary = Names.primaryArtist(artistDisplay)
                val album = c.getString(albumCol)?.trim().orEmpty().ifEmpty { "ללא אלבום" }
                val folder = File(path).parent ?: ""
                val genre = if (genreCol >= 0) c.getString(genreCol)?.trim()?.ifEmpty { null } else null

                out.add(
                    SongEntity(
                        id = c.getLong(idCol),
                        title = title,
                        titleLower = title.lowercase(Locale.ROOT),
                        artistName = artistDisplay,
                        artistKey = Names.normalizeKey(primary),
                        albumName = album,
                        albumId = c.getLong(albumIdCol),
                        durationMs = c.getLong(durCol),
                        trackNumber = c.getInt(trackCol),
                        year = c.getInt(yearCol),
                        genre = genre,
                        path = path,
                        folder = folder,
                        dateAddedSec = c.getLong(addedCol),
                        sizeBytes = c.getLong(sizeCol)
                    )
                )
            }
        }
        return out
    }
}
