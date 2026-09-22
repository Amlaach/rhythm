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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reads the device's audio library out of MediaStore.
 * No network, no metadata service - everything the app knows comes from here
 * plus what the user types in later.
 */
object MediaScanner {

    /**
     * Audio files that are sitting on the storage and are not in MediaStore.
     *
     * This is the whole of "I added songs and the player does not see them".
     * MediaStore is not the disk; it is a list the system keeps, and it is
     * only updated when something tells it to. An app writing a file tells
     * it. `adb push`, a card written in a card reader, a restored backup, a
     * file manager that does not bother and a great deal of sideloading all
     * do not - and the file then sits there, perfectly playable, invisible
     * to every app on the phone including this one. No amount of rescanning
     * finds it, because a rescan asks MediaStore and MediaStore has never
     * heard of it.
     *
     * So this asks the disk instead. Anything it finds that MediaStore has
     * no row for is handed to indexNow below, which is the one way to get a
     * file into MediaStore from outside.
     *
     * Bounded on purpose - depth, count, and the directories it refuses to
     * enter. A walk of a 128GB card must not be what a scan costs.
     */
    fun unindexedFiles(context: Context, known: Set<String>): List<String> {
        val out = ArrayList<String>()
        val visited = HashSet<String>()
        for (root in storageRoots(context)) {
            walk(root, known, out, visited, 0)
            if (out.size >= MAX_INDEX_REQUESTS) break
        }
        return out
    }

    /**
     * Where to look: every volume the app can see, not just the built in one.
     *
     * getExternalFilesDirs is the reliable way to enumerate volumes without
     * the permissions StorageManager wants - each entry is this app's own
     * folder on a volume, and the volume root is the part before /Android/.
     * The built in storage is the first entry and a memory card the next, so
     * one call covers both, on every version. Which matters here, because a
     * card is exactly where a large sideloaded library tends to live.
     *
     * Deliberately not getExternalStorageDirectory: it answers for the built
     * in volume only, and has been deprecated since Android 10.
     */
    private fun storageRoots(context: Context): List<File> {
        val roots = LinkedHashSet<File>()
        runCatching { context.getExternalFilesDirs(null) }.getOrNull()?.forEach { dir ->
            val path = dir?.absolutePath ?: return@forEach
            val cut = path.indexOf("/Android/")
            if (cut > 0) roots.add(File(path.substring(0, cut)))
        }
        // Resolved, because /sdcard and /storage/emulated/0 are the same
        // place under two names and MediaStore records one of them. Walking
        // the other produces paths that match nothing it knows, and every
        // file on the phone then looks new on every single scan.
        return roots
            .map { runCatching { it.canonicalFile }.getOrDefault(it) }
            .distinct()
            .filter { runCatching { it.isDirectory }.getOrDefault(false) }
    }

    private fun walk(
        dir: File,
        known: Set<String>,
        out: MutableList<String>,
        visited: MutableSet<String>,
        depth: Int
    ) {
        if (depth > MAX_DEPTH || out.size >= MAX_INDEX_REQUESTS) return

        // By canonical path, so a volume mounted at two places and a symlink
        // that points back up are both walked once rather than forever.
        val here = runCatching { dir.canonicalPath }.getOrNull() ?: return
        if (!visited.add(here)) return

        val entries = runCatching { dir.listFiles() }.getOrNull() ?: return

        // .nomedia is the established way of saying "nothing in here is
        // media". Respecting it is not politeness: it is what keeps a
        // WhatsApp audio cache or a game's sound effects out of a library.
        if (entries.any { it.name == ".nomedia" }) return

        for (entry in entries) {
            if (out.size >= MAX_INDEX_REQUESTS) return
            val name = entry.name
            if (name.startsWith(".")) continue
            if (runCatching { entry.isDirectory }.getOrDefault(false)) {
                // Other apps' private storage. Unreadable since Android 11
                // and nothing anyone chose to put there anyway.
                if (name == "Android") continue
                walk(entry, known, out, visited, depth + 1)
            } else {
                val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                if (ext !in AUDIO_EXTENSIONS) continue
                val path = entry.absolutePath
                if (path in known) continue
                out.add(path)
            }
        }
    }

    /**
     * Puts files into MediaStore, and waits for it to happen.
     *
     * The old code passed folders here, which does nothing: scanFile scans
     * the files it is given and does not walk a directory it is handed. It
     * also only ever passed folders the library already had a song in - so a
     * brand new folder could never be indexed, because it could only be
     * indexed once it had a song in the library, and it could only get a song
     * into the library by being indexed. Nothing new ever arrived.
     *
     * Waiting matters too. The old call was fired and forgotten on the theory
     * that the change observer would notice and scan again, which leaves the
     * person watching a scan that finishes and reports nothing new. Here the
     * scan waits, and the songs are there when it says it is done.
     */
    fun indexNow(context: Context, paths: List<String>): Int {
        if (paths.isEmpty()) return 0
        val batch = paths.take(MAX_INDEX_REQUESTS)
        val remaining = CountDownLatch(batch.size)
        val indexed = AtomicInteger(0)

        val started = runCatching {
            MediaScannerConnection.scanFile(context, batch.toTypedArray(), null) { _, uri ->
                if (uri != null) indexed.incrementAndGet()
                remaining.countDown()
            }
        }.isSuccess
        if (!started) return 0

        // Bounded, because this is a system service that can simply not call
        // back - and a scan that never returns is worse than one that misses
        // a file. Anything still outstanding when the time is up is picked up
        // by the next scan, by which point the system has usually finished.
        val seconds = (WAIT_BASE_SEC + batch.size / FILES_PER_SEC).coerceAtMost(WAIT_MAX_SEC)
        runCatching { remaining.await(seconds, TimeUnit.SECONDS) }
        return indexed.get()
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
                    rawArtist.isEmpty() -> Names.UNKNOWN_ARTIST
                    rawArtist == "<unknown>" -> Names.UNKNOWN_ARTIST
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

    /** How many files one scan will hand over. The rest follow on the next. */
    private const val MAX_INDEX_REQUESTS = 2000

    /** Deep enough for anyone's filing, shallow enough to stay quick. */
    private const val MAX_DEPTH = 12

    private const val WAIT_BASE_SEC = 5L
    private const val FILES_PER_SEC = 25
    private const val WAIT_MAX_SEC = 90L

    /**
     * Extensions worth handing to the scanner.
     *
     * By extension rather than by asking what a file is, because asking means
     * opening every file on the volume. The system decides properly once the
     * file reaches it; a wrong guess here costs one rejected scan request.
     */
    private val AUDIO_EXTENSIONS = setOf(
        "mp3", "m4a", "m4b", "aac", "flac", "ogg", "oga", "opus", "wav",
        "wma", "aif", "aiff", "ape", "mpc", "wv", "amr", "mka", "dsf"
    )

    /** What to ask when the fuller question is refused. */
    private const val FALLBACK_SELECTION = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
}
