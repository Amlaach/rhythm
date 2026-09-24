package com.elchanan.rhythm.engine

import java.util.Locale

/**
 * Reading names out of the text a file carries.
 *
 * These were part of MediaScanner, next to the MediaStore query, because that
 * is where the strings first arrive. They do not belong to it: normalising a
 * key and deciding whether "יעקב שוואקי feat. מוטי שטיינמץ" names one artist
 * or two are judgements about Hebrew music metadata, and they hold wherever
 * the filename came from - a phone's media index, a folder on a disk, a tag
 * read out of an mp3.
 *
 * They are in the engine now for that reason: the engine has to agree with
 * itself about what counts as the same artist, and a platform's file scanner
 * is the wrong place to keep that agreement. MediaScanner still calls in here
 * for its own rows.
 */
object Names {

    /** Hebrew niqqud / cantillation ranges plus common punctuation we ignore in keys. */

    /** What the Android scanner writes when a file carries no artist. */
    const val UNKNOWN_ARTIST = "אמן לא ידוע"

    /** The names that mean "nobody filled this in". */
    private val emptyArtistNames = setOf(
        UNKNOWN_ARTIST, "<unknown>", "unknown", "unknown artist", "various artists"
    )

    /**
     * Whether a file actually carries an artist.
     *
     * The two scanners disagree about what an absent artist looks like - one
     * writes a placeholder and the other leaves it blank - and the callers of
     * [looksLikeRecording] must not each decide for themselves, because
     * getting it backwards means either keeping every voice note or throwing
     * away every song.
     */
    fun hasRealArtist(artistName: String): Boolean {
        val trimmed = artistName.trim()
        return trimmed.isNotEmpty() && trimmed.lowercase(Locale.ROOT) !in
            emptyArtistNames.map { it.lowercase(Locale.ROOT) }
    }

    /**
     * Folders that only ever hold recordings.
     *
     * MediaStore's own `is_music` flag does not settle this: plenty of call
     * recorders and voice memo apps set it, and the files then arrive looking
     * exactly like tracks. Matching on the folder is cruder but it is what
     * actually works, because these apps all write to predictable places.
     *
     * Matched as a substring of the folder path, case insensitively, so
     * "Call Recordings" and "callrecorder" both catch. Everything here is
     * long enough that it cannot appear inside an ordinary word.
     */
    private val recorderFolders = listOf(
        "callrecord", "call_record", "call recording", "callrecording",
        "voicerecorder", "voice recorder", "voicememo", "voice memo",
        "voicenotes", "voice notes", "soundrecorder", "cube acr",
        "whatsapp voice", "whatsapp/media/whatsapp voice notes",
        "הקלטות שיחה"
    )

    /**
     * Folders where music and recordings both land.
     *
     * A great deal of music arrives through a messaging app, and it lands in
     * the same folder as the voice notes. Treating the folder alone as proof
     * threw all of it away, silently - which is most of what "the app does
     * not find my songs" turned out to be. These only count alongside other
     * evidence: see [looksLikeRecording].
     */
    private val sharedFolders = listOf(
        "whatsapp audio", "telegram audio", "recordings", "recorder",
        "הקלטות", "שיחות"
    )

    /**
     * Short folder names, matched as a whole path segment.
     *
     * "acr" and "ptt" are three letters, and as a substring of a whole path
     * they match inside ordinary words - every song in a folder called
     * "Sacred" was being thrown away as a call recording. A segment is a
     * folder someone named, which is what was meant.
     */
    private val recorderSegments = setOf("acr", "ptt")

    /**
     * Below this, an untagged file in a messaging folder is taken for a voice
     * note. Above it, it is taken for music.
     *
     * Two minutes, and deliberately generous towards keeping things. A voice
     * note that ends up in the library is visible and can be dealt with; a
     * song that never arrives is invisible, and the only symptom is a library
     * that feels incomplete for reasons nobody can see.
     */
    private const val VOICE_NOTE_MS = 120_000L

    /**
     * True when a file looks like a recording rather than a track.
     *
     * Read as a chain of evidence rather than a single test, because the two
     * cheapest signals - the folder and the file name - are also the two that
     * throw away real music.
     *
     * An artist tag settles it on its own: recorders do not write one, and a
     * person who tagged a file meant it as a track. Past that, a folder that
     * only ever holds recordings is enough by itself, and so is a file named
     * the way recorders name them. A folder that holds both only counts when
     * the file is also untagged and shorter than a voice note is long.
     *
     * @param durationMs 0 when unknown, which is treated as "not short" -
     *   MediaStore fills the length in after it notices the file, and a song
     *   must not be thrown away for being newly indexed.
     */
    fun looksLikeRecording(
        path: String,
        fileName: String,
        durationMs: Long = 0L,
        hasArtistTag: Boolean = false
    ): Boolean {
        if (hasArtistTag) return false

        val folder = path.lowercase(Locale.ROOT).replace('\\', '/')
        if (recorderFolders.any { folder.contains(it) }) return true
        if (folder.split('/').any { it in recorderSegments }) return true

        val name = fileName.lowercase(Locale.ROOT)
        // "call_20250114_093012.m4a", "PTT-20240103-WA0002.opus", "REC_0012"
        val namedLikeRecording = name.startsWith("call") ||
            name.startsWith("ptt-") ||
            name.startsWith("rec_") ||
            name.startsWith("voice ") ||
            Regex("^(aud|rec)[-_]?\\d{6,}").containsMatchIn(name)
        if (namedLikeRecording) return true

        // Untagged, short, and sitting where voice notes are kept. Any one of
        // those on its own is ordinary; all three together is a voice note.
        if (sharedFolders.any { folder.contains(it) }) {
            return durationMs in 1 until VOICE_NOTE_MS
        }
        return false
    }

    private val collabSeparators = listOf(
        " feat. ", " feat ", " ft. ", " ft ", " featuring ",
        " & ", " / ", " x ", " vs. ", " vs ", ";", " עם "
    )

    /**
     * The form two spellings of one name are compared in: lower case, the
     * marks gone, and anything that is not a letter or a digit a single gap.
     *
     * It used `\p{Punct}` and `\s`, which in Java are ASCII only - and Hebrew
     * tags are full of what they miss: the geresh and gershayim (׳ ״) where
     * a download has ' and ", the en and em dashes, the invisible direction
     * marks (RLM, LRM) that Hebrew text picks up on the way through a
     * browser, a non-breaking space. Each made "the same" name a different
     * key, so one singer came out as two artists and one song as two songs.
     * And a niqqud mark was turned into a gap, so a pointed name split into
     * letters and never met the same name unpointed.
     *
     * Decomposed first, so a precomposed letter and the same letter with its
     * mark are one, in Hebrew and in accented Latin alike.
     */
    fun normalizeKey(raw: String): String {
        val decomposed = java.text.Normalizer.normalize(raw.lowercase(Locale.ROOT), java.text.Normalizer.Form.NFKD)
        val out = StringBuilder(decomposed.length)
        var gap = false
        for (c in decomposed) {
            when (Character.getType(c)) {
                Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(),
                Character.ENCLOSING_MARK.toInt() -> continue
            }
            if (c.isLetterOrDigit()) {
                if (gap && out.isNotEmpty()) out.append(' ')
                gap = false
                out.append(c)
            } else {
                gap = true
            }
        }
        return out.toString().ifEmpty { "unknown" }
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

    /**
     * Everyone named in an artist field, not only the first.
     *
     * "מוטי שטיינמץ feat. אברהם פריד" is a song by both of them, and filing
     * it under whoever is written first leaves it missing from the other's
     * page - which is exactly where someone would go looking for it. A
     * combined "מוטי שטיינמץ feat. אברהם פריד" artist would be worse again: a
     * third name in the list with one song in it.
     *
     * Only the separators that are actually written as separators, from
     * [collabSeparators]. The Hebrew "ו" prefix is not among them: it attaches
     * to the following word, so splitting on it would cut real names in half.
     *
     * The first name stays the primary one, because that is what everything
     * else is keyed on. This is only for showing a song in more than one
     * place.
     *
     * Kept conservative on purpose. A band whose name genuinely contains "&"
     * should not be split in two, so single letters and very short fragments
     * are dropped rather than trusted.
     */
    fun credits(raw: String): List<String> {
        val value = raw.trim()
        if (value.isEmpty()) return emptyList()
        var parts = listOf(value)
        for (sep in collabSeparators) {
            parts = parts.flatMap { splitIgnoringCase(it, sep) }
        }
        parts = parts.flatMap { it.split(',') }
        return parts
            .map { it.trim().trim('-', '–', '.').trim() }
            .filter { it.length >= 3 }
            .distinctBy { normalizeKey(it) }
    }

    private fun splitIgnoringCase(text: String, separator: String): List<String> {
        val lower = text.lowercase(Locale.ROOT)
        val out = ArrayList<String>(2)
        var from = 0
        while (true) {
            val at = lower.indexOf(separator, from)
            if (at < 0) break
            out.add(text.substring(from, at))
            from = at + separator.length
        }
        if (out.isEmpty()) return listOf(text)
        out.add(text.substring(from))
        return out
    }
}
