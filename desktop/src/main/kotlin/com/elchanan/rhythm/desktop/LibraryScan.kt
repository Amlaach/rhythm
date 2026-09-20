package com.elchanan.rhythm.desktop

import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.engine.Names
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Finding the music on a Windows machine.
 *
 * This is the desktop's answer to MediaScanner. There is no MediaStore here -
 * nothing has already walked the disk and read the tags - so the walk and the
 * tag reading are ours. Everything downstream is unchanged: the rows this
 * produces are [SongEntity], the artist names go through the same [Names] the
 * phone uses, and the recommender cannot tell the difference.
 */
object LibraryScan {

    /**
     * Extensions worth opening. Deliberately a list rather than "anything
     * jaudiotagger will accept": a folder of music also holds artwork,
     * playlists and stray text files, and opening each one to find out is
     * slow enough to notice on a library of thousands.
     */
    private val AUDIO = setOf(
        "mp3", "m4a", "m4b", "aac", "flac", "ogg", "oga", "opus", "wav", "wma", "aiff", "aif"
    )

    /** Folders that are never music, whatever is in them. */
    private val SKIP = setOf(
        "\$recycle.bin", "system volume information", "windows", "program files",
        "program files (x86)", "appdata", "node_modules", ".git"
    )

    init {
        // jaudiotagger logs a warning for every frame it does not recognise,
        // which on a real library is thousands of lines of console noise
        // about tags nobody asked it to read.
        Logger.getLogger("org.jaudiotagger").level = Level.SEVERE
    }

    /**
     * A stable id for a file, standing in for the one MediaStore hands out.
     *
     * It has to survive a rescan, because every rating, play count and
     * analysis row in the database is keyed on it - an id that changed
     * between scans would silently orphan everything the user has built up.
     * The path is the only thing about a file that is both stable and unique,
     * so the id is a 64 bit FNV-1a hash of it.
     *
     * Moving a file therefore loses its history, which is the same behaviour
     * as on the phone and for the same reason.
     */
    fun idOf(path: String): Long {
        var h = -3750763034362895579L // FNV-1a 64 bit offset basis
        for (ch in path) {
            h = h xor ch.code.toLong()
            h *= 1099511628211L
        }
        // Keep it positive: ids are compared and sorted all over the engine,
        // and a negative one is a surprise nobody needs.
        return h and Long.MAX_VALUE
    }

    /**
     * Every audio file under [roots], with whatever its tags say.
     *
     * Unreadable tags are not a reason to drop a file. A track with a broken
     * header is still a track, and the filename usually carries the title
     * anyway - which is exactly the case the phone handles by keeping files
     * of unknown length rather than filtering them out in the query.
     */
    /**
     * @param minDurationSec files shorter than this are not music. Ringtones,
     *   notification sounds and the two second remains of a failed download
     *   all sit in the same folders as the music, and every one of them takes
     *   a place on a shelf. A file whose length could not be read is kept -
     *   an unreadable header is not evidence of anything.
     * @param excluded folders to walk past, by absolute path. Matched by
     *   prefix, so excluding a folder excludes what is under it.
     */
    fun scan(
        roots: List<File>,
        minDurationSec: Int = 0,
        excluded: List<String> = emptyList()
    ): List<SongEntity> {
        val out = ArrayList<SongEntity>()
        val seen = HashSet<String>()
        val skip = excluded.filter { it.isNotBlank() }.map { File(it).absolutePath }
        for (root in roots) walk(root, out, seen, skip)
        if (minDurationSec <= 0) return out
        val floor = minDurationSec * 1000L
        return out.filter { it.durationMs <= 0L || it.durationMs >= floor }
    }

    private fun walk(
        dir: File,
        out: MutableList<SongEntity>,
        seen: MutableSet<String>,
        excluded: List<String>
    ) {
        if (!dir.isDirectory || dir.name.lowercase() in SKIP) return
        val here = dir.absolutePath
        if (excluded.any { here == it || here.startsWith(it + File.separator) }) return
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) {
                walk(child, out, seen, excluded)
            } else if (child.extension.lowercase() in AUDIO) {
                val path = child.absolutePath
                if (seen.add(path)) out.add(read(child))
            }
        }
    }

    private fun read(file: File): SongEntity {
        val path = file.absolutePath
        var title = file.nameWithoutExtension
        var artist = ""
        var album = ""
        var genre: String? = null
        var year = 0
        var track = 0
        var durationMs = 0L

        runCatching {
            val audio = AudioFileIO.read(file)
            durationMs = audio.audioHeader.trackLength * 1000L
            val tag = audio.tag ?: return@runCatching
            tag.getFirst(FieldKey.TITLE)?.trim()?.takeIf { it.isNotEmpty() }?.let { title = it }
            tag.getFirst(FieldKey.ARTIST)?.trim()?.let { artist = it }
            tag.getFirst(FieldKey.ALBUM)?.trim()?.let { album = it }
            tag.getFirst(FieldKey.GENRE)?.trim()?.takeIf { it.isNotEmpty() }?.let { genre = it }
            year = tag.getFirst(FieldKey.YEAR)?.take(4)?.toIntOrNull() ?: 0
            track = tag.getFirst(FieldKey.TRACK)?.substringBefore('/')?.toIntOrNull() ?: 0
        }

        val folder = file.parentFile?.absolutePath.orEmpty()
        val primary = Names.primaryArtist(artist)
        return SongEntity(
            id = idOf(path),
            title = title,
            titleLower = title.lowercase(),
            artistName = artist,
            artistKey = Names.normalizeKey(primary),
            albumName = album,
            // Albums have no id of their own off a disk, so one is derived
            // from the name the same way a song's is from its path.
            albumId = if (album.isEmpty()) 0L else idOf(Names.normalizeKey(album)),
            durationMs = durationMs,
            trackNumber = track,
            year = year,
            genre = genre,
            path = path,
            folder = folder,
            dateAddedSec = file.lastModified() / 1000L,
            sizeBytes = file.length()
        )
    }
}
