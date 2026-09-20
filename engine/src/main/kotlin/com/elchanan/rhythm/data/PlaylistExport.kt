package com.elchanan.rhythm.data

import com.elchanan.rhythm.data.db.SongEntity

/**
 * Writes lists out as M3U8, so they can be taken somewhere else.
 *
 * The point is that the lists this app makes are not only the ones the user
 * typed in. The mixes, the daily mixes and the mood filters are worked out
 * here and exist nowhere else, and without a way to write them down they are
 * locked to this install - which is a poor thing to do to someone's own
 * library.
 *
 * Extended M3U rather than plain, because the `#EXTINF` line carries the title
 * and artist and so the file still says something useful when a path no longer
 * resolves. UTF-8 with the .m3u8 extension: plain .m3u has no agreed encoding
 * and Hebrew titles come back as rubbish often enough that it is not worth the
 * compatibility it buys.
 */
// In :engine because it is text in and text out: an m3u written on a phone
// has to be the same m3u written on a desktop, or the format stops being the
// thing that moves a library between them.
object PlaylistExport {

    const val EXTENSION = "m3u8"
    const val MIME = "audio/x-mpegurl"

    /**
     * @param name what the list is called, written into the file as a comment.
     * @param songs in the order they should play.
     */
    fun write(name: String, songs: List<SongEntity>): String {
        val out = StringBuilder(64 + songs.size * 80)
        out.append("#EXTM3U\n")
        out.append("#PLAYLIST:").append(oneLine(name)).append('\n')
        for (song in songs) {
            val seconds = if (song.durationMs > 0) song.durationMs / 1000 else -1
            out.append("#EXTINF:")
                .append(seconds)
                .append(',')
                .append(oneLine(song.artistName))
                .append(" - ")
                .append(oneLine(song.title))
                .append('\n')
            out.append(song.path).append('\n')
        }
        return out.toString()
    }

    /**
     * A file name that will survive being written to any of the places a phone
     * might put it.
     *
     * Playlists are named by the user and by the engine, and both produce
     * things no file system wants: slashes in "רוק/פופ", quotes in a mix named
     * after a song, and trailing dots, which Windows silently drops and then
     * cannot open the file again.
     */
    fun fileName(name: String): String {
        val cleaned = name
            .map { if (it in ILLEGAL || it.code < 0x20) ' ' else it }
            .joinToString("")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trimEnd('.')
        val safe = cleaned.ifBlank { "playlist" }
        // Long names are a problem on their own: most file systems stop at 255
        // bytes, and Hebrew is two bytes a letter.
        val trimmed = if (safe.length > 80) safe.take(80).trim() else safe
        return "$trimmed.$EXTENSION"
    }

    /** Makes a name unique within a folder, the way a file manager would. */
    fun uniqueName(name: String, taken: MutableSet<String>): String {
        val base = fileName(name)
        if (taken.add(base.lowercase())) return base
        val stem = base.removeSuffix(".$EXTENSION")
        var n = 2
        while (true) {
            val candidate = "$stem ($n).$EXTENSION"
            if (taken.add(candidate.lowercase())) return candidate
            n++
        }
    }

    private val ILLEGAL = charArrayOf(
        '/', '\\', ':', '*', '?', '"', '<', '>', '|', '\n', '\r', '\t'
    )

    /** A newline inside a comment would end the comment and start a path. */
    private fun oneLine(text: String): String =
        text.replace('\n', ' ').replace('\r', ' ').trim()
}
