package com.elchanan.rhythm.data

import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.engine.Names
import java.util.Locale

/**
 * Brings playlists over from YouTube Music.
 *
 * YouTube Music has no export of its own. What there is, is Google Takeout
 * ("YouTube and YouTube Music"), and what it writes is awkward in a way worth
 * knowing:
 *
 *  - each playlist is a CSV of video ids and nothing else - `playlists/<name>-videos.csv`
 *    (older exports: a few lines about the playlist, then `Video Id,Time Added`)
 *  - the names go with the ids in other files: `music library songs.csv` for
 *    songs added to the library, and the watch history (JSON or HTML) for
 *    everything played
 *
 * So the whole export is read at once - the zip itself, or any of its files -
 * and ids are turned into titles from wherever a title turns up. A CSV that
 * already has titles (the converters people use, TuneMyMusic and the like)
 * is taken as it is. Matching is by title and artist, since a video id means
 * nothing on the device.
 *
 * In :engine because it is text in and songs out, and the phone and Windows
 * must read the same export the same way.
 */
object YouTubeMusicImport {

    data class Track(val title: String, val artist: String)

    /** A playlist as the export has it: entries by video id, or already by title. */
    data class Playlist(val name: String, val videoIds: List<String>, val tracks: List<Track>)

    data class Imported(val name: String, val songs: List<SongEntity>, val missing: Int)

    /** Watch histories can run to hundreds of megabytes; the first part names plenty. */
    const val HISTORY_MAX_CHARS = 24_000_000

    /** Gathers the files of one export, in any order. */
    class Collector {
        val playlists = ArrayList<Playlist>()
        val known = HashMap<String, Track>()

        /** Whether a file in the zip is worth reading at all. */
        fun wants(path: String): Boolean {
            val lower = path.lowercase(Locale.ROOT)
            return lower.endsWith(".csv") || isHistory(lower)
        }

        fun add(path: String, content: String) {
            val lower = path.lowercase(Locale.ROOT)
            val text = content.removePrefix("﻿")
            when {
                isHistory(lower) && lower.endsWith(".json") -> readHistoryJson(text, known)
                isHistory(lower) -> readHistoryHtml(text, known)
                lower.endsWith(".csv") -> readCsv(path, text)
            }
        }

        private fun isHistory(lower: String) =
            (lower.endsWith(".json") || lower.endsWith(".html")) && lower.contains("history")

        private fun readCsv(path: String, text: String) {
            val lines = text.lines()
            val name = playlistName(path)
            // Takeout's own playlists: find the row that heads the video ids.
            val idHeader = lines.take(20).indexOfFirst { line ->
                splitRow(line).any { it.lowercase(Locale.ROOT) == "video id" }
            }
            if (idHeader >= 0) {
                val header = splitRow(lines[idHeader]).map { it.lowercase(Locale.ROOT) }
                val idCol = header.indexOf("video id")
                val titleCol = header.indexOfFirst { it == "song title" || it == "title" }
                val artistCols = header.indices.filter { header[it].startsWith("artist") }
                val ids = ArrayList<String>()
                for (line in lines.drop(idHeader + 1)) {
                    if (line.isBlank()) continue
                    val row = splitRow(line)
                    val id = row.getOrNull(idCol)?.takeIf { VIDEO_ID.matches(it) } ?: continue
                    ids.add(id)
                    if (titleCol >= 0) {
                        val title = row.getOrNull(titleCol).orEmpty()
                        val artist = artistCols.mapNotNull { row.getOrNull(it)?.takeIf(String::isNotBlank) }
                            .joinToString(" & ")
                        if (title.isNotBlank()) known[id] = Track(title, artist)
                    }
                }
                // A playlist is ids and when each was added - two columns, or
                // it sits in the playlists folder. The library's list of songs
                // and the comments carry video ids too, and are not playlists.
                val isPlaylist = path.lowercase(Locale.ROOT).contains("playlists/") ||
                    (titleCol < 0 && header.size <= 3)
                if (isPlaylist && ids.isNotEmpty()) playlists.add(Playlist(name, ids, emptyList()))
                return
            }
            // A converter's CSV: titles and artists, maybe many playlists in one file.
            val headerAt = lines.indexOfFirst { it.isNotBlank() }
            if (headerAt < 0) return
            val header = splitRow(lines[headerAt]).map { it.lowercase(Locale.ROOT) }
            val titleCol = firstOf(header, listOf("track name", "song title", "track", "song", "title", "name"))
            if (titleCol < 0) return
            val artistCol = firstOf(header, listOf("artist name", "artist name(s)", "artists", "artist"))
            val listCol = firstOf(header, listOf("playlist name", "playlist"))
            val byList = LinkedHashMap<String, MutableList<Track>>()
            for (line in lines.drop(headerAt + 1)) {
                if (line.isBlank()) continue
                val row = splitRow(line)
                val title = row.getOrNull(titleCol).orEmpty()
                if (title.isBlank()) continue
                val artist = if (artistCol >= 0) row.getOrNull(artistCol).orEmpty() else ""
                val list = if (listCol >= 0) row.getOrNull(listCol).orEmpty().ifBlank { name } else name
                byList.getOrPut(list) { ArrayList() }.add(Track(title, artist))
            }
            for ((list, tracks) in byList) playlists.add(Playlist(list, emptyList(), tracks))
        }
    }

    /** Every playlist in the export matched against the library, in the export's order. */
    fun match(collector: Collector, library: List<SongEntity>): List<Imported> {
        val matcher = Matcher(library)
        return collector.playlists.map { list ->
            val tracks = list.tracks + list.videoIds.map { id -> collector.known[id] }
                .map { it ?: Track("", "") }
            var missing = 0
            val songs = ArrayList<SongEntity>(tracks.size)
            for (track in tracks) {
                val song = if (track.title.isBlank()) null else matcher.find(track)
                if (song == null) missing++ else songs.add(song)
            }
            Imported(list.name, songs, missing)
        }
    }

    /**
     * Finds a song by what YouTube calls it.
     *
     * Title and artist first; then the title alone when only one song has it;
     * then a video title in the "Artist - Title" shape, both ways round; and
     * last a library title found whole inside the video's title ("Title
     * (Official Video)", "Watched Title") - only when the song's artist is
     * named there too, so a short title cannot claim everything.
     */
    internal class Matcher(library: List<SongEntity>) {
        private val byTitleAndArtist = HashMap<String, SongEntity>()
        private val byTitle = HashMap<String, MutableList<SongEntity>>()
        private val titled = ArrayList<Pair<String, SongEntity>>()

        init {
            for (song in library) {
                val title = key(cleanTitle(song.title))
                byTitleAndArtist.putIfAbsent(title + "|" + artistKey(song.artistName), song)
                byTitle.getOrPut(title) { ArrayList() }.add(song)
                if (title.length >= 3 && title != "unknown") titled.add(" $title " to song)
            }
            titled.sortByDescending { it.first.length }
        }

        fun find(track: Track): SongEntity? {
            val artist = cleanArtist(track.artist)
            val title = cleanTitle(track.title)
            byKey(title, artist)?.let { return it }
            val dash = DASH.split(title, 2)
            if (dash.size == 2) {
                byKey(dash[1], dash[0])?.let { return it }
                byKey(dash[0], dash[1])?.let { return it }
            }
            val text = " " + key(track.title) + " " + key(artist) + " "
            return titled.firstOrNull { (t, song) ->
                text.contains(t) && artistNamed(song.artistName, text)
            }?.second
        }

        private fun byKey(title: String, artist: String): SongEntity? {
            val t = key(title)
            if (artist.isNotBlank()) byTitleAndArtist[t + "|" + artistKey(artist)]?.let { return it }
            val same = byTitle[t] ?: return null
            if (same.size == 1) return same[0]
            val text = " " + key(artist) + " "
            return same.singleOrNull { artistNamed(it.artistName, text) }
        }

        private fun artistNamed(songArtist: String, text: String): Boolean {
            val a = artistKey(songArtist)
            return a.length >= 2 && a != "unknown" && text.contains(" $a ")
        }
    }

    // --- reading ------------------------------------------------------------------

    private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
    private val DASH = Regex("\\s+[-–—]\\s+")
    private val BRACKETS = Regex("\\([^)]*\\)|\\[[^]]*]|【[^】]*】")

    private val HISTORY_JSON = Regex(
        "\"title\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*,\\s*\"titleUrl\"\\s*:\\s*\"[^\"]*?watch\\?v(?:=|\\\\u003d)" +
            "([A-Za-z0-9_-]{11})[^\"]*\"(?:\\s*,\\s*\"subtitles\"\\s*:\\s*\\[\\s*\\{\\s*\"name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\")?"
    )
    private val HISTORY_HTML = Regex(
        "<a href=\"https?://(?:music|www)\\.youtube\\.com/watch\\?v=([A-Za-z0-9_-]{11})[^\"]*\">([^<]*)</a>" +
            "(?:\\s*<br>\\s*<a href=\"[^\"]*\">([^<]*)</a>)?"
    )

    /** "Watched X" in English; other languages are left to the looser match. */
    private fun stripWatched(title: String) = title.removePrefix("Watched ")

    internal fun readHistoryJson(text: String, into: MutableMap<String, Track>) {
        for (m in HISTORY_JSON.findAll(text)) {
            val id = m.groupValues[2]
            if (id in into) continue
            into[id] = Track(stripWatched(unescapeJson(m.groupValues[1])), unescapeJson(m.groupValues[3]))
        }
    }

    internal fun readHistoryHtml(text: String, into: MutableMap<String, Track>) {
        for (m in HISTORY_HTML.findAll(text)) {
            val id = m.groupValues[1]
            if (id in into) continue
            into[id] = Track(unescapeHtml(m.groupValues[2]), unescapeHtml(m.groupValues[3]))
        }
    }

    private fun unescapeJson(s: String): String {
        if (!s.contains('\\')) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'u' -> if (i + 5 < s.length) {
                        s.substring(i + 2, i + 6).toIntOrNull(16)?.let { out.append(it.toChar()) }
                        i += 6
                        continue
                    }
                    'n', 't', 'r' -> out.append(' ')
                    else -> out.append(n)
                }
                i += 2
                continue
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    private fun unescapeHtml(s: String) = s
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<")
        .replace("&gt;", ">").replace("&amp;", "&")

    /** `playlists/Shabbat-videos.csv` -> "Shabbat". */
    private fun playlistName(path: String): String {
        val stem = path.replace('\\', '/').substringAfterLast('/').substringBeforeLast('.')
        return stem.removeSuffix("-videos").removeSuffix("-סרטונים").trim().ifBlank { "YouTube Music" }
    }

    private fun splitRow(line: String) = PlayCountImport.splitRow(line, ',')

    private fun firstOf(header: List<String>, names: List<String>): Int {
        for (name in names) {
            val at = header.indexOf(name)
            if (at >= 0) return at
        }
        return -1
    }

    /** What a video adds to a song's name: "(Official Video)", "[HD]", "| Live". */
    private fun cleanTitle(raw: String): String =
        BRACKETS.replace(stripWatched(raw), " ").substringBefore(" | ").trim()

    /** A "Topic" channel is the artist's own name with a suffix. */
    private fun cleanArtist(raw: String): String =
        raw.trim().removeSuffix(" - Topic").removeSuffix("VEVO").trim()

    private fun key(raw: String) = Names.normalizeKey(raw)
    private fun artistKey(raw: String) = Names.normalizeKey(Names.primaryArtist(cleanArtist(raw)))
}
