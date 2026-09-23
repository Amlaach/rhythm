package com.elchanan.rhythm.data

import com.elchanan.rhythm.engine.ArtistStyles
import com.elchanan.rhythm.engine.Names
import com.elchanan.rhythm.engine.Transliteration
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
 *
 * It lives in :engine because it reads two strings and writes two strings and
 * touches nothing else - the same reasoning that moved the name parsing, the
 * feature vectors, the DSP and the lyrics parsing here. Both builds offer this
 * repair, and a library corrected on the phone and the same library corrected
 * on the desktop have to come out the same.
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
        val newArtist: String,
        /**
         * False where the repair is a reasonable reading rather than a sure
         * one - an English song name matched to a Hebrew one elsewhere in
         * the library, or a name split off an artist nobody knows. Shown as
         * "לא בטוח" and left out unless the listener asks for them.
         */
        val certain: Boolean = true
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

    /** Separators a name and its other-script copy are written with. */
    private val PAIR = Regex("""\s*(?:\||/| - | – | — )\s*""")

    /** "Name (Other)" - the copy in brackets. */
    private val BRACKET_PAIR = Regex("""^(.*?)\s*[\(\[]([^\)\]]+)[\)\]]\s*$""")

    /** Latin words then Hebrew, or Hebrew then Latin, with nothing between. */
    private val LATIN_THEN_HEBREW = Regex("""^([A-Za-z][A-Za-z0-9 .'&]*?)\s+([\x{0590}-\x{05FF}].*)$""")
    private val HEBREW_THEN_LATIN = Regex("""^([\x{0590}-\x{05FF}][^A-Za-z]*?)\s+([A-Za-z][A-Za-z0-9 .'&]*)$""")

    private fun hebrewOnly(t: String) = Transliteration.hasHebrew(t) && !Transliteration.hasLatin(t)
    private fun latinOnly(t: String) = Transliteration.hasLatin(t) && !Transliteration.hasHebrew(t)

    /** The two halves of "X | Y", "X / Y", "X - Y" or "X (Y)", or null. */
    private fun halves(text: String): Pair<String, String>? {
        val parts = text.split(PAIR).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size == 2) return parts[0] to parts[1]
        BRACKET_PAIR.matchEntire(text)?.let { m ->
            val a = m.groupValues[1].trim()
            val b = m.groupValues[2].trim()
            if (a.isNotEmpty() && b.isNotEmpty()) return a to b
        }
        return null
    }

    /** The Hebrew half and the Latin half, whichever order they came in. */
    private fun bilingual(a: String, b: String): Pair<String, String>? = when {
        hebrewOnly(a) && latinOnly(b) -> a to b
        latinOnly(a) && hebrewOnly(b) -> b to a
        else -> null
    }

    /**
     * The Hebrew name alone, where [text] is a name written twice - once in
     * Hebrew and once in Latin letters, with or without anything between
     * them: "דרשו גלובל | Dirshu Global", "השיבנו (Hashivenu)",
     * "השיבנו Hashivenu". Null when it is not one name twice.
     */
    fun dropDuplicateName(text: String): String? {
        val pair = halves(text)?.let { bilingual(it.first, it.second) }
            ?: LATIN_THEN_HEBREW.matchEntire(text)?.let { bilingual(it.groupValues[1].trim(), it.groupValues[2].trim()) }
            ?: HEBREW_THEN_LATIN.matchEntire(text)?.let { bilingual(it.groupValues[1].trim(), it.groupValues[2].trim()) }
            ?: return null
        val (hebrew, latin) = pair
        return if (Transliteration.sameName(hebrew, latin)) hebrew else null
    }

    /**
     * What the library and the built-in catalogue already know about names:
     * which artists exist, how the catalogue's are written in English, and
     * which Hebrew song names each artist has.
     */
    class Knowledge(songs: List<SongEntity>) {
        /** Every name and English alias in the catalogue, to its Hebrew name. */
        private val catalogue: Map<String, String> = HashMap<String, String>().apply {
            for (seed in ArtistStyles.CATALOGUE) {
                for (name in listOf(seed.name) + seed.aliases) put(ArtistStyles.matchKey(name), seed.name)
            }
        }

        /** Hebrew artist names that stand alone as the whole artist field of some song. */
        private val hebrewArtists: List<String> = songs.asSequence()
            .map { normalize(it.artistName) }
            .filter { hebrewOnly(it) && !PAIR.containsMatchIn(it) }
            .distinct().toList()

        private val known: Set<String> =
            (hebrewArtists.map { ArtistStyles.matchKey(it) } + catalogue.keys).toSet()

        /** Hebrew song names by artist key. */
        private val hebrewTitles: Map<String, List<String>> = songs
            .filter { hebrewOnly(it.title) }
            .groupBy({ Names.normalizeKey(Names.primaryArtist(it.artistName)) }, { normalize(it.title) })

        /** Whether this is an artist the library or the catalogue knows by this exact name. */
        fun isKnown(name: String): Boolean = ArtistStyles.matchKey(name) in known

        /** The Hebrew name of an artist written in Latin letters, if anyone knows it. */
        fun hebrewFor(latin: String): String? {
            catalogue[ArtistStyles.matchKey(latin)]?.let { if (hebrewOnly(it)) return it }
            return hebrewArtists.firstOrNull { Transliteration.sameName(it, latin) }
        }

        /** A Hebrew song by the same artist whose name this Latin title spells. */
        fun hebrewTitleFor(artist: String, latinTitle: String): String? =
            hebrewTitles[Names.normalizeKey(Names.primaryArtist(artist))]
                ?.firstOrNull { Transliteration.sameName(it, latinTitle) }
    }

    private class Repaired(val title: String, val artist: String, val certain: Boolean)

    /**
     * The names written twice, the song name stuck in the artist field, an
     * artist's English name glued to a Hebrew song name, and an English song
     * name that another file of the same artist has in Hebrew.
     */
    private fun repairNames(title: String, artist: String, originalArtist: String, k: Knowledge): Repaired {
        var t = title
        var a = artist
        var certain = true

        // A: a song name written twice.
        dropDuplicateName(t)?.let { t = it }

        // "Hanan Ben Ari השיבנו": an artist the library or catalogue knows, in
        // English, stuck to a Hebrew song name with nothing between them.
        LATIN_THEN_HEBREW.matchEntire(t)?.let { m ->
            val latin = m.groupValues[1].trim()
            val rest = m.groupValues[2].trim()
            val hebrew = k.hebrewFor(latin)
            if (hebrew != null && !Transliteration.hasLatin(rest)) {
                val artistSaysOtherwise = hebrewOnly(originalArtist) && Names.hasRealArtist(originalArtist) &&
                    ArtistStyles.matchKey(originalArtist) != ArtistStyles.matchKey(hebrew)
                t = rest
                a = hebrew
                if (artistSaysOtherwise) certain = false
            }
        }

        // The artist field.
        val names = halves(a)?.let { bilingual(it.first, it.second) }
        when {
            // A: the artist's name written twice.
            dropDuplicateName(a) != null -> a = dropDuplicateName(a)!!
            names != null -> {
                val (hebrew, latin) = names
                when {
                    // C: the English half is this song's name - "ישי ריבו | Erets Israel".
                    hebrewOnly(t) && Transliteration.sameName(t, latin) -> a = hebrew
                    // B: the Hebrew half is an artist the library or the catalogue knows.
                    k.isKnown(hebrew) -> a = hebrew
                    // The English half is: "Ishay Ribo | ארץ ישראל".
                    k.hebrewFor(latin) != null -> a = k.hebrewFor(latin)!!
                    // Probably the artist, but nothing confirms it.
                    else -> { a = hebrew; certain = false }
                }
            }
            else -> {
                // "Hanan Ben Ari השיבנו" in the artist field.
                LATIN_THEN_HEBREW.matchEntire(a)?.let { m ->
                    k.hebrewFor(m.groupValues[1].trim())?.let { a = it }
                }
            }
        }

        // D: a song name in English only, which the same artist has in Hebrew.
        if (latinOnly(t)) {
            k.hebrewTitleFor(a, t)?.let { t = it; certain = false }
        }
        return Repaired(t, a, certain)
    }

    /**
     * @param dropForeign also drop Latin script leftovers from the song name -
     *   producer credits in brackets, an English gloss of the Hebrew title, the
     *   remains of a video page's heading.
     */
    fun propose(songs: List<SongEntity>, dropForeign: Boolean = false): List<Proposal> {
        val knowledge = Knowledge(songs)
        return songs.map { song ->
            // A song name written twice is one name, not "Artist - Title":
            // "השיבנו - Hashivenu" must not become a song by השיבנו.
            val twice = dropDuplicateName(normalize(song.title))
            val (fromTitle, rawName) = if (twice != null) null to twice else split(song.title)
            val songName = if (dropForeign) stripForeign(rawName) else rawName
            val artist = (fromTitle ?: cleanArtist(song.artistName)).trim()
            val repaired = repairNames(songName.ifEmpty { song.title }, artist.ifEmpty { song.artistName }, song.artistName, knowledge)
            Proposal(
                songId = song.id,
                oldTitle = song.title,
                oldArtist = song.artistName,
                newTitle = repaired.title.ifEmpty { song.title },
                newArtist = repaired.artist.ifEmpty { song.artistName },
                certain = repaired.certain
            )
        }
    }

    /**
     * @param includeUncertain whether the proposals marked [Proposal.certain]
     *   false are applied too. Off unless the listener asked.
     */
    fun toOverrides(proposals: List<Proposal>, includeUncertain: Boolean = false): List<TagOverrideEntity> =
        proposals.filter { it.changed && (it.certain || includeUncertain) }.map {
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
        artistKey = Names.normalizeKey(Names.primaryArtist(artist)),
        albumName = album
    )
}
