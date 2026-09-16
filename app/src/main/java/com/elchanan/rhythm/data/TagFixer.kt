package com.elchanan.rhythm.data

import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.data.db.TagOverrideEntity
import java.util.Locale

/**
 * Repairs the tags that download tools leave behind.
 *
 * Files pulled from a video site arrive with the whole description crammed into
 * the title - "Artist - Song (Prod. by Someone)" - and the uploader's channel
 * name sitting in the artist field. Everything downstream reads those two
 * fields, so a library like that collapses into one artist and one album, and
 * the artist screen, the album shelf and the recommender all have nothing to
 * work with.
 *
 * The split below is deliberately conservative. It only claims an artist when
 * the title really does carry one, and it never invents information.
 */
object TagFixer {

    /** Dashes people actually type, including the Hebrew keyboard's maqaf. */
    private val SEPARATORS = listOf(" - ", " – ", " — ", " -", "- ")

    /**
     * Trailing production and version credits. Removed from the song name but
     * never from the artist, since "(LIVE)" and "(Prod. by X)" describe the
     * recording rather than who made it.
     */
    private val TRAILING = Regex(
        """\s*[\(\[]\s*(prod\.?|produced|by|remix|רמיקס|cover|קאבר)\b[^\)\]]*[\)\]]\s*$""",
        RegexOption.IGNORE_CASE
    )

    /** Channel boilerplate that is never part of an artist's name. */
    private val CHANNEL_NOISE = Regex(
        """\s*(הערוץ הרשמי|ערוץ רשמי|official( channel| audio| video)?|topic)\s*$""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Invisible direction marks. Mixed Hebrew and Latin text picks these up from
     * everywhere, and they survive into the tag where they break string
     * comparison in ways nothing on screen explains.
     */
    private val INVISIBLE =
        Regex("""[\x{200E}\x{200F}\x{202A}-\x{202E}\x{2066}-\x{2069}\x{FEFF}]""")

    /**
     * Full width stand ins. A file name cannot contain | or ", so download tools
     * substitute these look alikes; they are meaningless inside a tag.
     *
     * Written as code points rather than as the characters themselves: several
     * are indistinguishable from the ASCII they replace in most editors, which
     * is exactly the confusion this map exists to undo.
     */
    private val WIDE = mapOf(
        Char(0xFF5C) to '|', Char(0xFF02) to '"', Char(0xFF07) to '\'',
        Char(0xFF1A) to ':', Char(0xFF0F) to '/', Char(0xFF1F) to '?',
        Char(0xFF0A) to '*'
    )

    private val HEBREW = Regex("""[\x{0590}-\x{05FF}]""")

    /**
     * Markers worth keeping even though they are written in Latin script.
     *
     * "(LIVE)" is the only thing separating a concert recording from the studio
     * version of the same song, both in the title the user reads and in the live
     * shelf on the home screen. Stripping it would merge two different
     * recordings into one name and leave that shelf empty.
     *
     * A guest credit is kept for the same reason: "(feat. X)" says who is on the
     * recording, which is part of what the song is, unlike "(Official Video)".
     */
    private val WORTH_KEEPING = Regex(
        """\blive\b|\bunplugged\b|\bfeat\b|\bft\b|\b(19|20)\d\d\b""",
        RegexOption.IGNORE_CASE
    )

    /** Any bracketed aside, wherever it sits in the title. */
    private val BRACKETED = Regex("""\s*[\(\[]([^\)\]]*)[\)\]]""")

    /** A trailing segment hanging off a pipe. */
    private val TAIL_SEGMENT = Regex("""\s*[|\x{FF5C}]\s*([^|\x{FF5C}]*)$""")

    data class Proposal(
        val songId: Long,
        val oldTitle: String,
        val oldArtist: String,
        val newTitle: String,
        val newArtist: String
    ) {
        val changed: Boolean
            get() = newTitle != oldTitle || newArtist != oldArtist
    }

    /** Strips channel boilerplate: "בן צור הערוץ הרשמי" -> "בן צור". */
    fun cleanArtist(raw: String): String {
        val trimmed = CHANNEL_NOISE.replace(normalize(raw), "").trim()
        return trimmed.ifEmpty { raw.trim() }
    }

    /**
     * Undoes what the file system did to the text: invisible direction marks
     * out, full width substitutes back to the characters they stand for, runs of
     * whitespace collapsed.
     *
     * This is not a judgement call about the name, so it runs regardless of the
     * user's preferences - it only restores what was already meant.
     */
    fun normalize(raw: String): String {
        val stripped = INVISIBLE.replace(raw, "")
        val mapped = buildString(stripped.length) {
            for (c in stripped) append(WIDE[c] ?: c)
        }
        return mapped.replace(Regex("\\s+"), " ").trim()
    }

    private fun hasHebrew(text: String) = HEBREW.containsMatchIn(text)

    /**
     * Drops the Latin script leftovers from a title that is really in Hebrew.
     *
     * A bracketed aside or a trailing segment goes only when it contains no
     * Hebrew at all and is not worth keeping. That keeps real bilingual names
     * ("סולי (Soul)" loses the gloss, but "(LIVE מנורה)" and "(הרמיקס הרשמי)"
     * survive because they carry Hebrew), and it never touches the artist, where
     * a Latin name like "Vini Vici" is the actual name.
     */
    fun stripForeign(title: String): String {
        var out = title
        var guard = 0
        while (guard++ < 6) {
            val before = out
            out = BRACKETED.replace(out) { m ->
                val inner = m.groupValues[1]
                if (hasHebrew(inner) || WORTH_KEEPING.containsMatchIn(inner)) m.value else ""
            }
            out = TAIL_SEGMENT.replace(out) { m ->
                val inner = m.groupValues[1]
                if (hasHebrew(inner) || WORTH_KEEPING.containsMatchIn(inner)) m.value else ""
            }
            out = out.trim().trimEnd('-', '–', '—', '|', ',').trim()
            if (out == before) break
        }
        // A title that was Latin all along is left exactly as it was, rather
        // than dissolved into nothing.
        return if (out.isBlank()) title.trim() else out
    }

    /**
     * Splits "Artist - Title (Prod. by X)" into its parts.
     *
     * Returns null for the artist half when the title carries no separator, so
     * the caller keeps whatever the file already said rather than guessing.
     */
    fun split(title: String): Pair<String?, String> {
        val cleaned = TRAILING.replace(normalize(title), "").trim()
        val separator = SEPARATORS.firstOrNull { cleaned.contains(it) }
            ?: return null to cleaned.ifEmpty { title.trim() }
        val index = cleaned.indexOf(separator)
        val left = cleaned.take(index).trim()
        val right = cleaned.substring(index + separator.length).trim()
        // A split is only believable when both halves survive it.
        if (left.isEmpty() || right.isEmpty()) return null to cleaned
        return left to right
    }

    /**
     * @param dropForeign also drop Latin script leftovers from the song name -
     *   producer credits in brackets, an English gloss of the Hebrew title, the
     *   remains of a video page's heading.
     */
    fun propose(songs: List<SongEntity>, dropForeign: Boolean = false): List<Proposal> =
        songs.map { song ->
            val (fromTitle, rawName) = split(song.title)
            val songName = if (dropForeign) stripForeign(rawName) else rawName
            val artist = (fromTitle ?: cleanArtist(song.artistName)).trim()
            Proposal(
                songId = song.id,
                oldTitle = song.title,
                oldArtist = song.artistName,
                newTitle = songName.ifEmpty { song.title },
                newArtist = artist.ifEmpty { song.artistName }
            )
        }

    fun toOverrides(proposals: List<Proposal>): List<TagOverrideEntity> =
        proposals.filter { it.changed }.map {
            TagOverrideEntity(
                songId = it.songId,
                title = it.newTitle,
                artistName = it.newArtist,
                albumName = ""
            )
        }
}

/** Rebuilds the derived fields so grouping and search follow the correction. */
fun applyOverride(song: SongEntity, override: TagOverrideEntity?): SongEntity {
    if (override == null) return song
    val title = override.title.ifBlank { song.title }
    val artist = override.artistName.ifBlank { song.artistName }
    val album = override.albumName.ifBlank { song.albumName }
    if (title == song.title && artist == song.artistName && album == song.albumName) return song
    return song.copy(
        title = title,
        titleLower = title.lowercase(Locale.ROOT),
        artistName = artist,
        artistKey = MediaScanner.normalizeKey(MediaScanner.primaryArtist(artist)),
        albumName = album
    )
}
