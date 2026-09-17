package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.MediaScanner
import com.elchanan.rhythm.data.db.SongEntity

/**
 * What a particular recording of a piece is.
 *
 * The engine already knew that two tracks were the same piece; it did not know
 * which of them was the studio cut and which was the concert. That distinction
 * is what stops a shelf putting the same song on twice in two costumes, and it
 * is the whole basis of a shelf of alternative takes.
 */
enum class VersionType(val label: String) {
    ORIGINAL("מקור"),
    LIVE("הופעה חיה"),
    COVER("קאבר"),
    REMIX("רמיקס")
}

/**
 * Sorts a library into pieces, and each piece into its versions.
 *
 * "Piece" here means the song as written, across every performer - which is
 * exactly what the engine's own version key does not give, because that one
 * includes the artist so that two singers never collapse into one entry. A
 * cover can only be recognised by looking across artists, so this keeps a
 * second, artist-free key alongside it.
 */
object Versions {

    private val LIVE_WORDS = Regex(
        """\blive\b|לייב|בהופעה|הופעה חיה|במופע""",
        RegexOption.IGNORE_CASE
    )
    private val REMIX_WORDS = Regex("""\bremix\b|רמיקס""", RegexOption.IGNORE_CASE)
    private val COVER_WORDS = Regex("""\bcover\b|קאבר|ביצוע""", RegexOption.IGNORE_CASE)

    /** Bracketed asides and version wording, which name the take and not the song. */
    private val NOISE = Regex(
        """[\(\[][^\)\]]*[\)\]]|\b(live|remix|רמיקס|קאבר|cover|אולפן|היכל|קיסריה|מנורה|unplugged)\b|\b20\d{2}\b""",
        RegexOption.IGNORE_CASE
    )

    /** The song as written, with nothing in it that identifies a performance. */
    fun pieceKey(title: String): String =
        MediaScanner.normalizeKey(NOISE.replace(title, " "))

    /**
     * Classifies every song, using the shape of the library as evidence.
     *
     * Wording settles most of it. What wording cannot settle is a plain cover -
     * a second singer performing the same song with nothing in the title to say
     * so - and there the only signal is that two different artists have the
     * same piece. The one with more of the library's listening behind it is
     * taken as the original and the other as the cover, which is a guess, but a
     * guess that is right far more often than it is wrong in a collection built
     * around a few favourite artists.
     */
    fun classify(
        songs: List<SongEntity>,
        playCount: (Long) -> Int = { 0 }
    ): Map<Long, VersionType> {
        val byPiece = songs.groupBy { pieceKey(it.title) }
        val out = HashMap<Long, VersionType>(songs.size)

        for ((_, group) in byPiece) {
            // Which artist "owns" this piece: the one with the most plays, and
            // failing that the one contributing the most recordings of it.
            val owner = group
                .groupBy { it.artistKey }
                .maxByOrNull { (_, list) ->
                    list.sumOf { playCount(it.id).toLong() } * 100 + list.size
                }
                ?.key

            for (song in group) {
                out[song.id] = when {
                    LIVE_WORDS.containsMatchIn(song.title) -> VersionType.LIVE
                    REMIX_WORDS.containsMatchIn(song.title) -> VersionType.REMIX
                    COVER_WORDS.containsMatchIn(song.title) -> VersionType.COVER
                    // Same piece, different artist, and not the one who owns it.
                    group.size > 1 && owner != null && song.artistKey != owner -> VersionType.COVER
                    else -> VersionType.ORIGINAL
                }
            }
        }
        return out
    }

    /**
     * Pieces that exist in more than one version, newest interpretation first.
     *
     * Only pieces where the versions actually differ in kind are returned - two
     * copies of the same studio cut are a duplicate, not an alternative take,
     * and belong in the duplicate finder rather than on a shelf.
     */
    fun alternates(
        songs: List<SongEntity>,
        types: Map<Long, VersionType>
    ): List<SongEntity> {
        val byPiece = songs.groupBy { pieceKey(it.title) }
        val out = ArrayList<SongEntity>()
        for ((key, group) in byPiece) {
            if (key.isBlank() || group.size < 2) continue
            val kinds = group.mapNotNull { types[it.id] }.toSet()
            if (kinds.size < 2) continue
            // The alternatives, not the original - the point of the shelf is the
            // version you are less likely to have reached for.
            out += group.filter { types[it.id] != VersionType.ORIGINAL }
        }
        return out
    }

    /**
     * Exact duplicates: the same recording sitting in the library twice.
     *
     * Same piece, same artist, same kind of version and near enough the same
     * length. Duration is what separates a genuine second copy from two
     * different takes that happen to share a name, so it is required rather
     * than treated as a hint.
     */
    fun duplicateGroups(
        songs: List<SongEntity>,
        types: Map<Long, VersionType>
    ): List<List<SongEntity>> =
        songs
            .groupBy { song ->
                val seconds = song.durationMs / 1000
                listOf(
                    pieceKey(song.title),
                    song.artistKey,
                    types[song.id]?.name.orEmpty(),
                    // Bucketed to two seconds, so re-encodes of the same file
                    // still land together.
                    (seconds / 2).toString()
                ).joinToString("|")
            }
            .values
            .filter { it.size > 1 }
            .map { group -> group.sortedBy { it.id } }

    /** One song per duplicate group, keeping everything that is not duplicated. */
    fun withoutDuplicates(
        songs: List<SongEntity>,
        types: Map<Long, VersionType>
    ): List<SongEntity> {
        val drop = duplicateGroups(songs, types)
            .flatMap { it.drop(1) }
            .mapTo(HashSet()) { it.id }
        if (drop.isEmpty()) return songs
        return songs.filter { it.id !in drop }
    }
}
