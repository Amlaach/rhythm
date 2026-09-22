package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.ArtistMerge
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
        Names.normalizeKey(NOISE.replace(title, " "))

    /**
     * Whether two artist fields name the same performer.
     *
     * The question the covers shelf lives or dies on. A library built from
     * downloads holds the same track twice under "ישי ריבו" and under
     * "ישי ריבו feat. מוטי שטיינמץ", or with one letter different, or with a
     * stray space - and the classifier read "different artist key" as proof
     * of a different singer. So the second copy of a song was filed as
     * somebody's cover of it and put on a shelf beside the original: the
     * duplicate the user was trying to be rid of, wearing a label saying it
     * is not one.
     *
     * Deliberately generous. Calling two spellings one performer costs the
     * covers shelf an entry; calling one performer two costs the user a
     * duplicate on their home screen, which is the thing they complained
     * about. An unknown artist is evidence of nobody, so it is never taken
     * as evidence of somebody else.
     */
    fun samePerformer(left: String, right: String): Boolean {
        val a = Names.normalizeKey(Names.primaryArtist(left))
        val b = Names.normalizeKey(Names.primaryArtist(right))
        if (a == b) return true
        if (a == "unknown" || b == "unknown") return true
        // A whole-word prefix: "ישי" and "ישי ריבו" are one singer written
        // two ways. The trailing space is what keeps it from also swallowing
        // "אבי" into "אביתר".
        if (a.startsWith("$b ") || b.startsWith("$a ")) return true
        return ArtistMerge.oneLetterApart(a, b)
    }

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
                ?.value
                ?.firstOrNull()
                ?.artistName

            for (song in group) {
                out[song.id] = when {
                    LIVE_WORDS.containsMatchIn(song.title) -> VersionType.LIVE
                    REMIX_WORDS.containsMatchIn(song.title) -> VersionType.REMIX
                    COVER_WORDS.containsMatchIn(song.title) -> VersionType.COVER
                    // Same piece, and a genuinely different singer from the one
                    // who owns it. Comparing the keys was not enough: a second
                    // download of the same track under a slightly different
                    // artist tag has a different key and the same singer, and
                    // reading that as a cover is what put duplicates on the
                    // covers shelf.
                    group.size > 1 && owner != null && !samePerformer(song.artistName, owner) ->
                        VersionType.COVER
                    else -> VersionType.ORIGINAL
                }
            }
        }
        return out
    }

    /**
     * Pieces that exist in more than one version.
     *
     * Only pieces where the versions actually differ in kind are returned - two
     * copies of the same studio cut are a duplicate, not an alternative take,
     * and belong in the duplicate finder rather than on a shelf.
     *
     * @param wanted which kinds of take to return. Live recordings are left out
     *   by default: a concert take of a song is a different listen from someone
     *   else's cover of it, and they already have shelves of their own. Putting
     *   the two together made the covers shelf mostly live tracks, which is not
     *   what a shelf called covers is for.
     */
    fun alternates(
        songs: List<SongEntity>,
        types: Map<Long, VersionType>,
        wanted: Set<VersionType> = setOf(VersionType.COVER, VersionType.REMIX)
    ): List<SongEntity> {
        val byPiece = songs.groupBy { pieceKey(it.title) }
        val out = ArrayList<SongEntity>()
        for ((key, group) in byPiece) {
            if (key.isBlank() || group.size < 2) continue
            val kinds = group.mapNotNull { types[it.id] }.toSet()
            if (kinds.size < 2) continue
            out += group.filter { types[it.id] in wanted }
        }
        return out
    }

    /** How far two copies of one recording may drift in length, in milliseconds. */
    private const val SAME_LENGTH_MS = 2_500L

    /**
     * Exact duplicates: the same recording sitting in the library twice.
     *
     * Same piece, same singer, same kind of version and near enough the same
     * length. Duration is what separates a genuine second copy from two
     * different takes that happen to share a name, so it is required rather
     * than treated as a hint.
     *
     * Two things here used to be decided by string equality and are now
     * decided by tolerance, because both failed on exactly the libraries this
     * is for. The artist is matched through [samePerformer], so the same
     * singer tagged two ways is still one singer. The length is compared
     * pairwise rather than bucketed: two copies at 4:01 and 4:02 fall either
     * side of a fixed bucket edge about as often as they fall inside one,
     * which meant the duplicate finder missed half of what it was for.
     */
    fun duplicateGroups(
        songs: List<SongEntity>,
        types: Map<Long, VersionType>
    ): List<List<SongEntity>> {
        val out = ArrayList<List<SongEntity>>()
        val byPieceAndKind = songs.groupBy { song ->
            pieceKey(song.title) + "|" + types[song.id]?.name.orEmpty()
        }
        for ((key, group) in byPieceAndKind) {
            if (group.size < 2 || key.startsWith("|")) continue
            val clusters = ArrayList<MutableList<SongEntity>>()
            for (song in group.sortedBy { it.id }) {
                val home = clusters.firstOrNull { cluster ->
                    val head = cluster.first()
                    samePerformer(head.artistName, song.artistName) &&
                        kotlin.math.abs(head.durationMs - song.durationMs) <= SAME_LENGTH_MS
                }
                if (home != null) home.add(song) else clusters.add(mutableListOf(song))
            }
            for (cluster in clusters) if (cluster.size > 1) out.add(cluster)
        }
        return out
    }

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
