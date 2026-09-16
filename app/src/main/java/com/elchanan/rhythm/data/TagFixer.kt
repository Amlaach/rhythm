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
        val trimmed = CHANNEL_NOISE.replace(raw.trim(), "").trim()
        return trimmed.ifEmpty { raw.trim() }
    }

    /**
     * Splits "Artist - Title (Prod. by X)" into its parts.
     *
     * Returns null for the artist half when the title carries no separator, so
     * the caller keeps whatever the file already said rather than guessing.
     */
    fun split(title: String): Pair<String?, String> {
        val cleaned = TRAILING.replace(title.trim(), "").trim()
        val separator = SEPARATORS.firstOrNull { cleaned.contains(it) }
            ?: return null to cleaned.ifEmpty { title.trim() }
        val index = cleaned.indexOf(separator)
        val left = cleaned.take(index).trim()
        val right = cleaned.substring(index + separator.length).trim()
        // A split is only believable when both halves survive it.
        if (left.isEmpty() || right.isEmpty()) return null to cleaned
        return left to right
    }

    fun propose(songs: List<SongEntity>): List<Proposal> = songs.map { song ->
        val (fromTitle, songName) = split(song.title)
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
