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
    private val stripRegex = Regex("[\\u0591-\\u05C7\\p{Punct}\\s]+")

    /**
     * Folders that hold recordings rather than music.
     *
     * MediaStore's own `is_music` flag does not settle this: plenty of call
     * recorders and voice memo apps set it, and the files then arrive looking
     * exactly like tracks. Matching on the folder is cruder but it is what
     * actually works, because these apps all write to predictable places.
     *
     * Matched against the folder path, case insensitively, so "Call
     * Recordings" and "callrecorder" both catch.
     */
    private val recordingFolders = listOf(
        "callrecord", "call_record", "call recording", "callrecording",
        "recordings", "recorder", "voicerecorder", "voice recorder",
        "voicememo", "voice memo", "voicenotes", "voice notes", "soundrecorder",
        "whatsapp audio", "whatsapp voice", "whatsapp/media/whatsapp voice notes",
        "telegram audio", "ptt", "cube acr", "acr",
        "הקלטות", "שיחות", "הקלטות שיחה"
    )

    /**
     * True when a file looks like a recording rather than a track.
     *
     * The file name is checked as well as the folder, because recorders that
     * write into a shared folder still name their files distinctively.
     */
    fun looksLikeRecording(path: String, fileName: String): Boolean {
        val folder = path.lowercase(Locale.ROOT).replace('\\', '/')
        if (recordingFolders.any { folder.contains(it) }) return true
        val name = fileName.lowercase(Locale.ROOT)
        // "call_20250114_093012.m4a", "PTT-20240103-WA0002.opus", "REC_0012"
        return name.startsWith("call") ||
            name.startsWith("ptt-") ||
            name.startsWith("rec_") ||
            name.startsWith("voice ") ||
            name.startsWith("audio-") ||
            Regex("^(aud|rec)[-_]?\\d{6,}").containsMatchIn(name)
    }

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
