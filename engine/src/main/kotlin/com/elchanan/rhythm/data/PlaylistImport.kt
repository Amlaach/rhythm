package com.elchanan.rhythm.data

import com.elchanan.rhythm.data.db.SongEntity
import java.util.Locale

/**
 * Reads playlist files written by other players.
 *
 * Musicolet is the one that matters here - it is what most of this app's users
 * already have - and its export was the reference. A real exported file looks
 * like this:
 *
 *     #EXTM3U
 *     #EXTINF:176,בן צור & ג'הבי - "האסיר"
 *     Music/בן צור & ג'הבי  - ＂האסיר＂  (Prod.By Tamir Zur).mp3
 *
 * Three things in that are worth knowing, and two of them are easy to get
 * wrong. It is UTF-8 with no byte order mark. And the path is relative to the
 * storage root, not to the folder the playlist file is sitting in - that file
 * was saved in Download, so resolving against its own folder looks for
 * Download/Music/... and finds nothing at all.
 *
 * Which is why matching does not trust paths to line up. It compares file
 * names, and only uses the rest of the path to choose between files that share
 * one.
 */
// In :engine because it is text in and text out: an m3u written on a phone
// has to be the same m3u written on a desktop, or the format stops being the
// thing that moves a library between them.
object PlaylistImport {

    /** A name for the list and the raw entries as the file spelled them. */
    data class Parsed(val name: String, val entries: List<String>)

    data class Result(val name: String, val songs: List<SongEntity>, val missing: Int)

    fun parse(content: String, fileName: String): Parsed {
        val stem = fileName.substringAfterLast('/').substringBeforeLast('.')
        val text = content.removePrefix("﻿")
        val entries = if (looksLikePls(text)) parsePls(text) else parseM3u(text)
        return Parsed(stem.ifBlank { "רשימה מיובאת" }, entries)
    }

    private fun looksLikePls(text: String): Boolean =
        text.lineSequence().take(5).any { it.trim().equals("[playlist]", ignoreCase = true) }

    /**
     * Everything that is not a comment is a path. `#EXTINF` carries a title,
     * but the title is only a label - the line after it is what identifies the
     * file, so the comments are skipped entirely.
     */
    private fun parseM3u(text: String): List<String> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .toList()

    /** `File1=...` lines, in the order their numbers give. */
    private fun parsePls(text: String): List<String> {
        val numbered = ArrayList<Pair<Int, String>>()
        for (raw in text.lines()) {
            val line = raw.trim()
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.take(eq).trim().lowercase(Locale.ROOT)
            if (!key.startsWith("file")) continue
            val index = key.removePrefix("file").toIntOrNull() ?: continue
            val value = line.substring(eq + 1).trim()
            if (value.isNotEmpty()) numbered.add(index to value)
        }
        return numbered.sortedBy { it.first }.map { it.second }
    }

    /**
     * Turns the entries into songs from this library.
     *
     * Order is the playlist's, not the library's - a playlist is a sequence and
     * losing that would make the import worthless. Entries that match nothing
     * are counted rather than dropped silently, because "imported 12 of 30" is
     * the difference between a working import and one the user has to guess at.
     */
    fun match(entries: List<String>, library: List<SongEntity>): Pair<List<SongEntity>, Int> {
        if (entries.isEmpty() || library.isEmpty()) return emptyList<SongEntity>() to entries.size

        val byName = HashMap<String, MutableList<SongEntity>>()
        for (song in library) {
            byName.getOrPut(fileNameOf(song.path)) { ArrayList() }.add(song)
        }

        val picked = ArrayList<SongEntity>(entries.size)
        var missing = 0
        val used = HashSet<Long>()

        for (raw in entries) {
            val entry = clean(raw)
            val candidates = byName[fileNameOf(entry)]
            if (candidates.isNullOrEmpty()) {
                missing++
                continue
            }
            val chosen = when {
                candidates.size == 1 -> candidates[0]
                // Several files share this name, so fall back to whichever has
                // the longest tail of its path in common with the entry.
                else -> candidates.maxByOrNull { commonTail(it.path, entry) } ?: candidates[0]
            }
            // A playlist may legitimately list the same song twice; only skip a
            // repeat when the file itself repeats, not when two entries differ.
            if (used.add(chosen.id) || candidates.size == 1) picked.add(chosen)
        }
        return picked to missing
    }

    /** Percent decoding, backslashes, and a leading file:// all show up in the wild. */
    private fun clean(entry: String): String {
        var out = entry.trim().replace('\\', '/')
        if (out.startsWith("file://")) out = out.removePrefix("file://")
        if (out.contains('%')) {
            out = runCatching { java.net.URLDecoder.decode(out, "UTF-8") }.getOrDefault(out)
        }
        return out
    }

    private fun fileNameOf(path: String): String =
        path.trimEnd('/').substringAfterLast('/').lowercase(Locale.ROOT)

    /** How many trailing path characters two paths agree on. */
    private fun commonTail(a: String, b: String): Int {
        val x = a.lowercase(Locale.ROOT)
        val y = b.lowercase(Locale.ROOT)
        var i = 0
        while (i < x.length && i < y.length && x[x.length - 1 - i] == y[y.length - 1 - i]) i++
        return i
    }
}
