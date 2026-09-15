package com.elchanan.rhythm.data

import android.content.Context
import android.database.Cursor
import android.os.Build
import android.provider.MediaStore
import com.elchanan.rhythm.data.db.SongEntity
import java.io.File
import java.util.Locale

/**
 * Reads the device's audio library out of MediaStore.
 * No network, no metadata service - everything the app knows comes from here
 * plus what the user types in later.
 */
object MediaScanner {

    /** Hebrew niqqud / cantillation ranges plus common punctuation we ignore in keys. */
    private val stripRegex = Regex("[\\u0591-\\u05C7\\p{Punct}\\s]+")

    private val collabSeparators = listOf(
        " feat. ", " feat ", " ft. ", " ft ", " featuring ",
        " & ", " / ", " x ", " vs. ", " vs ", ";", " עם "
    )

    fun normalizeKey(raw: String): String {
        val lower = raw.lowercase(Locale.ROOT).trim()
        return stripRegex.replace(lower, " ").trim().ifEmpty { "unknown" }
    }

    /** "יעקב שוואקי feat. מוטי שטיינמץ" -> "יעקב שוואקי" */
    fun primaryArtist(raw: String): String {
        var value = raw.trim()
        val lower = " ${value.lowercase(Locale.ROOT)} "
        var cutAt = value.length
        for (sep in collabSeparators) {
            val idx = lower.indexOf(sep)
            if (idx >= 0) cutAt = minOf(cutAt, idx)
        }
        if (cutAt < value.length) value = value.substring(0, cutAt).trim()
        // a trailing comma list ("א, ב") - keep the first entry only
        val comma = value.indexOf(',')
        if (comma > 1) value = value.substring(0, comma).trim()
        return value.ifEmpty { raw.trim() }
    }

    fun scan(context: Context, minDurationSec: Int): List<SongEntity> {
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

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND " +
            "${MediaStore.Audio.Media.DURATION} >= ?"
        val args = arrayOf((minDurationSec * 1000).toString())

        val out = ArrayList<SongEntity>(512)
        val cursor: Cursor = context.contentResolver.query(
            collection, projection.toTypedArray(), selection, args, null
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
                val primary = primaryArtist(artistDisplay)
                val album = c.getString(albumCol)?.trim().orEmpty().ifEmpty { "ללא אלבום" }
                val folder = File(path).parent ?: ""
                val genre = if (genreCol >= 0) c.getString(genreCol)?.trim()?.ifEmpty { null } else null

                out.add(
                    SongEntity(
                        id = c.getLong(idCol),
                        title = title,
                        titleLower = title.lowercase(Locale.ROOT),
                        artistName = artistDisplay,
                        artistKey = normalizeKey(primary),
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
