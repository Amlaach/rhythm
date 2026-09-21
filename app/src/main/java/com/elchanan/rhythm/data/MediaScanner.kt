package com.elchanan.rhythm.data

import android.content.Context
import android.database.Cursor
import android.media.MediaScannerConnection
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
     * Asks the system to look again at the folders the library already knows.
     *
     * MediaStore only contains what it has been told about. A file copied in
     * over USB, dropped in by a file manager, restored from a backup or
     * written by another app is often not indexed for a long time - sometimes
     * not until the device reboots - and until it is, no amount of rescanning
     * from this side will find it, because there is nothing to find.
     *
     * Best effort by nature: it is a request to a system service, the service
     * decides, and on some versions a directory is walked while on others
     * only a file is. Fired and forgotten at the start of a scan, so anything
     * it does turn up arrives as a MediaStore change a moment later and the
     * observer runs the scan again.
     */
    fun askSystemToIndex(context: Context, folders: Collection<String>) {
        if (folders.isEmpty()) return
        runCatching {
            MediaScannerConnection.scanFile(
                context,
                // Bounded: a library can be filed under hundreds of folders
                // and this is a courtesy, not the mechanism.
                folders.take(MAX_INDEX_REQUESTS).toTypedArray(),
                null,
                null
            )
        }
    }

    /**
     * Every audio file on the device that is not a ringtone, whatever its
     * length and whatever the system thinks it is.
     *
     * The length filter deliberately does not happen here. MediaStore
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

        // Not "is_music != 0".
        //
        // That flag is the system's guess, and on a real phone it is wrong
        // constantly. Anything that arrived through a browser, a messaging
        // app or a file manager - which is most of how music gets onto a
        // phone here - tends to land with every one of these flags at zero,
        // and the library then silently missed it. People reinstalled the app
        // trying to fix a library that was never going to be complete.
        //
        // So: anything the system thinks is music, or a podcast, or an
        // audiobook, or that it has formed no opinion about at all. What is
        // left out is only what it positively identified as a ringtone, a
        // notification or an alarm. The app's own filters - minimum length,
        // excluded folders, the recording heuristic - decide the rest, and
        // unlike this flag they can be seen and turned off.
        val music = MediaStore.Audio.Media.IS_MUSIC
        val podcast = MediaStore.Audio.Media.IS_PODCAST
        val ringtone = MediaStore.Audio.Media.IS_RINGTONE
        val notification = MediaStore.Audio.Media.IS_NOTIFICATION
        val alarm = MediaStore.Audio.Media.IS_ALARM
        val selection = buildString {
            append("$music != 0 OR $podcast != 0")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                append(" OR ${MediaStore.Audio.Media.IS_AUDIOBOOK} != 0")
            }
            append(" OR ($ringtone = 0 AND $notification = 0 AND $alarm = 0)")
        }

        val out = ArrayList<SongEntity>(512)
        // Every attached volume, not just the built in one. Before API 29
        // the "external" view is the primary volume alone, so a memory card
        // was invisible on exactly the devices most likely to have one.
        val seen = HashSet<Long>()
        for (collection in collections(context)) {
            val cursor = query(context, collection, projection, selection)
                // A column this device does not have would fail the whole
                // query, and a scan that returns nothing reads as a library
                // that was emptied. The narrower question is the one every
                // Android has answered since the beginning.
                ?: query(context, collection, projection, FALLBACK_SELECTION)
                ?: continue
            read(cursor, out, seen)
        }
        return out
    }

    private fun query(
        context: Context,
        collection: android.net.Uri,
        projection: List<String>,
        selection: String
    ): Cursor? = runCatching {
        context.contentResolver.query(
            collection, projection.toTypedArray(), selection, null, null
        )
    }.getOrNull()

    /**
     * The audio collections to ask, one per attached volume.
     *
     * On API 29 and up the volume names are enumerable and each is asked in
     * turn; the synthetic "external" view usually covers them all, but asking
     * by name costs nothing and is the only thing that works when it does
     * not. Below that there is only the one view, and it is the primary
     * volume.
     */
    private fun collections(context: Context): List<android.net.Uri> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return listOf(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
        }
        val names = runCatching { MediaStore.getExternalVolumeNames(context) }
            .getOrDefault(emptySet())
        if (names.isEmpty()) return listOf(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
        return names.map { MediaStore.Audio.Media.getContentUri(it) }
    }

    /**
     * @param seen ids already taken. The volumes are asked separately and the
     *   synthetic view overlaps them, so the same song arrives more than once
     *   - and two rows with one id would be one row after the insert, which
     *   is a silent loss rather than a duplicate.
     */
    private fun read(cursor: Cursor, out: MutableList<SongEntity>, seen: MutableSet<Long>) {
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
                val id = c.getLong(idCol)
                if (!seen.add(id)) continue
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
                        id = id,
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
    }

    /** A courtesy to the system service, not a queue to be drained. */
    private const val MAX_INDEX_REQUESTS = 400

    /** What to ask when the fuller question is refused. */
    private const val FALLBACK_SELECTION = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
}
