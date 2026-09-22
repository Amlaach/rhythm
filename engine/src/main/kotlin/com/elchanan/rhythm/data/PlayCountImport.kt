package com.elchanan.rhythm.data

import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.engine.Names
import java.util.Locale

/**
 * Brings listening history in from another player, as a CSV.
 *
 * Someone arriving with years of history elsewhere starts here with a library
 * the engine knows nothing about, and every model in it - the taste vector,
 * the acoustic neighbourhood, what to play next - needs listening before it
 * says anything useful. Months of evidence already exist; there was simply no
 * way to hand it over.
 *
 * Two shapes turn up in practice and both are accepted, because which one an
 * export produces is not something a user chooses or should have to explain:
 *
 *  - one row per song with a count column, which is what a library export
 *    from iTunes, MusicBee or Poweramp looks like
 *  - one row per play, which is what a scrobble log looks like. No count
 *    column, so the rows for a song are counted instead.
 *
 * In :engine because it is text in and numbers out, with nothing platform
 * specific anywhere in it, and because the phone and Windows must agree about
 * what a given file means down to the last row.
 */
object PlayCountImport {

    /** One song's worth of imported history. */
    data class Entry(
        val title: String,
        val artist: String,
        val album: String,
        val plays: Int,
        /** Epoch millis of the most recent play, or 0 when the file did not say. */
        val lastPlayedAt: Long
    )

    data class Parsed(
        val entries: List<Entry>,
        /** Which column names were recognised, for showing back to the user. */
        val columns: Map<String, String>,
        /** Rows that could not be read as a song at all. */
        val skipped: Int
    )

    /** What matching the parsed file against the library produced. */
    data class Match(
        val songId: Long,
        val plays: Int,
        val lastPlayedAt: Long
    )

    data class Result(
        val matched: List<Match>,
        /** Titles in the file with nothing in the library to attach them to. */
        val unmatched: List<Entry>,
        val parsed: Parsed
    ) {
        val totalPlays: Int get() = matched.sumOf { it.plays }
    }

    // The header names actually written by the exporters people use. Matched
    // case-insensitively and by substring, so "Play Count", "playcount" and
    // "Plays" all land on the same column.
    private val TITLE_NAMES = listOf("title", "track name", "track", "name", "song", "שיר", "כותרת")
    private val ARTIST_NAMES = listOf("artist", "albumartist", "album artist", "performer", "אמן", "זמר")
    private val ALBUM_NAMES = listOf("album", "אלבום")
    private val COUNT_NAMES = listOf("play count", "playcount", "plays", "count", "השמעות", "מספר השמעות")
    private val DATE_NAMES = listOf("last played", "lastplayed", "date", "timestamp", "uts", "נוגן לאחרונה")

    /**
     * Splits one CSV line, honouring quotes and doubled quotes inside them.
     *
     * Hand written rather than pulled in, because a title with a comma in it
     * is not an edge case in this library - it is most of a Hebrew track list
     * - and a naive split turns every one of those rows into garbage that then
     * matches nothing.
     */
    internal fun splitRow(line: String, delimiter: Char): List<String> {
        val out = ArrayList<String>(6)
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    field.append('"')
                    i++
                }
                c == '"' -> quoted = !quoted
                c == delimiter && !quoted -> {
                    out.add(field.toString().trim())
                    field.setLength(0)
                }
                else -> field.append(c)
            }
            i++
        }
        out.add(field.toString().trim())
        return out
    }

    /**
     * Which character separates the fields.
     *
     * Guessed from the header rather than assumed, because a European export
     * uses semicolons and a spreadsheet saved as "CSV" is as often tabs. The
     * winner is whichever splits the header into the most fields.
     */
    internal fun delimiterOf(header: String): Char =
        listOf(',', ';', '\t', '|').maxByOrNull { splitRow(header, it).size } ?: ','

    private fun indexOf(headers: List<String>, names: List<String>): Int {
        val lower = headers.map { it.lowercase(Locale.ROOT).trim() }
        // Exact first, so a file with both "artist" and "album artist" picks
        // the one that was actually asked for rather than whichever came first.
        for (name in names) {
            val exact = lower.indexOf(name)
            if (exact >= 0) return exact
        }
        for (name in names) {
            val partial = lower.indexOfFirst { it.contains(name) }
            if (partial >= 0) return partial
        }
        return -1
    }

    /**
     * Reads a play count out of whatever the column holds.
     *
     * Exports write "12", "12.0" and "1,234" for the same number.
     */
    internal fun countOf(raw: String): Int? {
        val cleaned = raw.trim().replace(",", "").substringBefore('.')
        if (cleaned.isEmpty()) return null
        return cleaned.toIntOrNull()?.takeIf { it >= 0 }
    }

    /**
     * Reads a timestamp, in the two forms exports write it.
     *
     * Epoch seconds and epoch milliseconds are told apart by size: anything
     * below the year 2001 in milliseconds is being quoted in seconds. Text
     * dates are ignored rather than guessed at - there are too many formats
     * and a wrong date is worse here than no date, since recency is a term in
     * the ranking.
     */
    internal fun timeOf(raw: String): Long {
        val n = raw.trim().toLongOrNull() ?: return 0L
        if (n <= 0L) return 0L
        return if (n < 100_000_000_000L) n * 1000L else n
    }

    fun parse(content: String): Parsed {
        val lines = content.removePrefix("﻿")
            .split('\n')
            .map { it.trimEnd('\r') }
            .filter { it.isNotBlank() }
        if (lines.isEmpty()) return Parsed(emptyList(), emptyMap(), 0)

        val delimiter = delimiterOf(lines.first())
        val headers = splitRow(lines.first(), delimiter)
        val titleAt = indexOf(headers, TITLE_NAMES)
        val artistAt = indexOf(headers, ARTIST_NAMES)
        if (titleAt < 0) return Parsed(emptyList(), emptyMap(), lines.size - 1)
        val albumAt = indexOf(headers, ALBUM_NAMES)
        val countAt = indexOf(headers, COUNT_NAMES)
        val dateAt = indexOf(headers, DATE_NAMES)

        val columns = buildMap {
            put("שיר", headers.getOrNull(titleAt).orEmpty())
            if (artistAt >= 0) put("אמן", headers[artistAt])
            if (albumAt >= 0) put("אלבום", headers[albumAt])
            if (countAt >= 0) put("השמעות", headers[countAt])
            if (dateAt >= 0) put("תאריך", headers[dateAt])
        }

        // Rows for one song are folded together. With a count column that
        // means taking the largest, since two rows for one song are two
        // spellings of one history rather than two histories. Without one,
        // every row is a play and they are summed - which is what makes a
        // scrobble log work without the user having to know it is one.
        val byKey = LinkedHashMap<String, Entry>()
        var skipped = 0
        for (line in lines.drop(1)) {
            val cells = splitRow(line, delimiter)
            val title = cells.getOrNull(titleAt).orEmpty()
            if (title.isBlank()) {
                skipped++
                continue
            }
            val artist = if (artistAt >= 0) cells.getOrNull(artistAt).orEmpty() else ""
            val album = if (albumAt >= 0) cells.getOrNull(albumAt).orEmpty() else ""
            val counted = if (countAt >= 0) countOf(cells.getOrNull(countAt).orEmpty()) else null
            if (countAt >= 0 && counted == null) {
                skipped++
                continue
            }
            val at = if (dateAt >= 0) timeOf(cells.getOrNull(dateAt).orEmpty()) else 0L
            val key = keyOf(title, artist)
            val existing = byKey[key]
            byKey[key] = if (existing == null) {
                Entry(title, artist, album, counted ?: 1, at)
            } else {
                existing.copy(
                    plays = if (counted != null) maxOf(existing.plays, counted)
                    else existing.plays + 1,
                    lastPlayedAt = maxOf(existing.lastPlayedAt, at)
                )
            }
        }
        return Parsed(byKey.values.toList(), columns, skipped)
    }

    private fun keyOf(title: String, artist: String): String =
        Names.normalizeKey(title) + "|" + Names.normalizeKey(Names.primaryArtist(artist))

    /**
     * Attaches parsed history to songs that are actually on the device.
     *
     * Title and artist first. Then title alone, but only when exactly one song
     * in the library carries that title - an export whose artist column is
     * blank or spelled differently is common, and refusing all of it would
     * throw away most of the file, while guessing between two songs of the
     * same name would silently credit the wrong one.
     */
    fun match(parsed: Parsed, songs: List<SongEntity>): Result {
        val byTitleAndArtist = HashMap<String, Long>()
        val byTitle = HashMap<String, MutableList<Long>>()
        for (song in songs) {
            byTitleAndArtist.putIfAbsent(keyOf(song.title, song.artistName), song.id)
            byTitle.getOrPut(Names.normalizeKey(song.title)) { ArrayList() }.add(song.id)
        }

        val matched = ArrayList<Match>()
        val unmatched = ArrayList<Entry>()
        val claimed = HashSet<Long>()
        for (entry in parsed.entries) {
            val exact = byTitleAndArtist[keyOf(entry.title, entry.artist)]
            val only = byTitle[Names.normalizeKey(entry.title)]?.singleOrNull()
            val id = exact ?: only
            if (id == null || !claimed.add(id)) {
                unmatched.add(entry)
                continue
            }
            matched.add(Match(id, entry.plays, entry.lastPlayedAt))
        }
        return Result(matched, unmatched, parsed)
    }

    fun read(content: String, songs: List<SongEntity>): Result = match(parse(content), songs)
}
