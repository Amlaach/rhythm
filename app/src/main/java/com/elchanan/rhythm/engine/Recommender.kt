package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.db.ArtistEntity
import com.elchanan.rhythm.data.MediaScanner
import com.elchanan.rhythm.data.db.AudioFeatureEntity
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.data.db.SongStatsEntity
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

// ---------------------------------------------------------------------------
// Public model
// ---------------------------------------------------------------------------

data class Mix(
    val id: String,
    val title: String,
    val subtitle: String,
    val songs: List<SongEntity>
)

enum class SectionKind { QUICK_PICKS, MIX_ROW, SONG_ROW }

/**
 * Whether a title announces itself as a stage recording.
 *
 * Deliberately literal: only wording that actually says "live". Venue names and
 * years - "קיסריה 2025" and the like - are just as often studio releases, and a
 * shelf that quietly mixes the two is worse than one that misses a few.
 */
private val LIVE_MARKERS = Regex(
    """\blive\b|לייב|בהופעה|הופעה חיה|בהופעה חיה|במופע""",
    RegexOption.IGNORE_CASE
)

/**
 * Venues, which only count as evidence alongside a year.
 *
 * "קיסריה" on its own could be a song title; "קיסריה 2025" is a concert. The
 * pairing is what makes it safe to treat as a live recording without the word
 * "live" appearing anywhere.
 */
private val VENUE_MARKERS = Regex(
    """קיסריה|היכל מנורה|מנורה מבטחים|היכל התרבות|זאפה|בארבי|אמפי|סולטן""",
    RegexOption.IGNORE_CASE
)

private val YEAR_MARKER = Regex("""\b20\d{2}\b""")

/**
 * A medley: several songs strung together in one track.
 *
 * These are the reason a generated mix suddenly seems to start in the middle of
 * a song - the track really does, because whatever is playing is the fourth
 * tune of a set. One is a fine thing to choose deliberately and a bad thing to
 * be handed, so mixes leave them out and the library keeps them.
 */
private val MEDLEY_MARKERS = Regex(
    """מחרוזת|מחרוזות|\bmedley\b|\bmashup\b|מדלי""",
    RegexOption.IGNORE_CASE
)

fun isMedley(title: String): Boolean = MEDLEY_MARKERS.containsMatchIn(title)

fun isLiveRecording(title: String): Boolean {
    if (LIVE_MARKERS.containsMatchIn(title)) return true
    return VENUE_MARKERS.containsMatchIn(title) && YEAR_MARKER.containsMatchIn(title)
}

data class FeedSection(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val kind: SectionKind,
    val songs: List<SongEntity> = emptyList(),
    val mixes: List<Mix> = emptyList()
)

data class EngineTuning(
    val discovery: Float = 0.35f,
    val artistWeight: Float = 1.0f,
    val styleWeight: Float = 1.0f,
    val repeatGuard: Float = 1.0f,
    val acousticWeight: Float = 1.0f,
    /** Styles the user has said never belong in the same mix, one rule a line. */
    val separations: String = "",
    /**
     * The mood the feed last told the user they lean towards.
     *
     * Carried in so the shelf can defend its previous answer rather than
     * recomputing an opinion about someone from scratch every refresh.
     */
    val lastMood: String = ""
)

data class TransitionEdge(val weight: Double, val penalty: Double)

/**
 * Bracketed or trailing wording that marks a version rather than a song:
 * "(LIVE)", "קיסריה 2025", "הרמיקס הרשמי" and the like.
 */
private val VERSION_NOISE = Regex(
    """[\(\[][^\)\]]*[\)\]]|\b(live|remix|רמיקס|קאבר|cover|אולפן|היכל|קיסריה|מנורה)\b|\b20\d{2}\b""",
    RegexOption.IGNORE_CASE
)

data class TasteReport(
    val topStyles: List<Pair<String, Double>>,
    val ratedArtists: Int,
    val totalArtists: Int,
    val ratedSongs: Int,
    val songsWithStyle: Int,
    val totalSongs: Int,
    val learnedPairs: Int,
    val learnedTransitions: Int,
    val analyzedSongs: Int,
    val medianBpm: Int
)

/** One line of the "why was this picked" breakdown. */
data class ScoreTerm(val label: String, val value: Double, val detail: String)

// ---------------------------------------------------------------------------
// Engine
// ---------------------------------------------------------------------------

/**
 * A fully offline ranking engine. Five independent signal families are blended
 * into one bounded score:
 *
 *  1. what the user typed   - song rating, artist rating, style tags
 *  2. what the user pressed - like / dislike
 *  3. what the user did     - plays, skips, completion, time of day
 *  4. what goes together    - symmetric co-occurrence and a directed
 *                             "after A comes B" transition matrix
 *  5. how the file sounds   - tempo, key, energy, timbre, measured on device
 */
class Recommender(
    private val songs: List<SongEntity>,
    private val stats: Map<Long, SongStatsEntity>,
    private val artists: Map<String, ArtistEntity>,
    private val affinity: Map<Long, Map<Long, Double>>,
    private val transitions: Map<Long, Map<Long, TransitionEdge>>,
    private val features: Map<Long, AudioFeatureEntity>,
    private val acoustic: AcousticSpace?,
    private val tuning: EngineTuning,
    private val now: Long,
    private val feedSeed: Long
) {

    private val hourBucket: Int = bucketOf(now)
    private val maxPlays: Int = stats.values.maxOfOrNull { it.playCount } ?: 0

    /** The styles the user has said must not be mixed, ready to consult. */
    private val separations: Styles.Separations =
        Styles.Separations.parse(tuning.separations)

    /**
     * The style words on a song, from the song's own tags or its artist's.
     *
     * Only the words the user wrote. The measured tokens that [tokensFor] adds
     * - tempo, mode, decade - are for scoring similarity, and a rule about
     * what not to mix is about what the user called things.
     */
    private val declaredStyles: Map<Long, List<String>> = if (separations.isEmpty) {
        emptyMap()
    } else {
        songs.associate { song ->
            val own = Styles.parse(stats[song.id]?.styles.orEmpty())
            val styles = own.ifEmpty { Styles.parse(artists[song.artistKey]?.styles.orEmpty()) }
            song.id to styles
        }
    }

    /** True when these two must not appear in the same generated list. */
    private fun separated(a: Long, b: Long): Boolean {
        if (separations.isEmpty) return false
        return separations.clash(
            declaredStyles[a].orEmpty(),
            declaredStyles[b].orEmpty()
        )
    }

    /**
     * Thins a group down to one side of every separation rule.
     *
     * Used where a list was assembled without a seed to measure against - a
     * cluster, say. The biggest group wins, counted by how many songs carry
     * each style, and anything that clashes with the winner goes. Untagged
     * songs stay: they clash with nothing, and throwing them out would gut
     * the mixes of anyone who has not tagged their library.
     */
    private fun withoutSeparated(group: List<SongEntity>): List<SongEntity> {
        if (separations.isEmpty || group.size < 2) return group
        val counts = HashMap<String, Int>()
        for (song in group) {
            for (style in declaredStyles[song.id].orEmpty()) {
                counts[style] = (counts[style] ?: 0) + 1
            }
        }
        val leader = counts.maxByOrNull { it.value }?.key ?: return group
        val winning = listOf(leader)
        return group.filterNot { separations.clash(winning, declaredStyles[it.id].orEmpty()) }
    }

    /**
     * How restless the listening has been lately, 0..1.
     *
     * A fixed discovery dial cannot answer the one question that matters in the
     * moment: is the feed landing or not. Heavy skipping says the safe picks are
     * not working, and the useful response is to widen the net rather than keep
     * offering more of the same. Held at zero until there is enough history for
     * the ratio to mean anything.
     */
    private val restlessness: Double = run {
        val plays = stats.values.sumOf { it.playCount }
        val skips = stats.values.sumOf { it.skipCount }
        val attempts = plays + skips
        if (attempts < 10) 0.0 else (skips.toDouble() / attempts).coerceIn(0.0, 1.0)
    }

    /** The user's dial, widened when the recent picks are being skipped. */
    private val effectiveDiscovery: Double =
        (tuning.discovery + 0.5 * restlessness).coerceIn(0.0, 1.0)

    /**
     * The acoustic centre of the last three quarters of an hour, or null when too
     * little has just played to say.
     *
     * Taste drifts within a sitting - the same listener wants something different
     * at midnight than at noon - so ranking purely against a long-run profile
     * ignores the most informative evidence there is: what is playing right now.
     */
    private val sessionCentre: DoubleArray? = run {
        val cutoff = now - 45 * 60 * 1000L
        val recent = stats.values
            .filter { it.lastPlayedAt >= cutoff }
            .sortedByDescending { it.lastPlayedAt }
            .take(5)
            .mapNotNull { features[it.songId] }
            .filter { it.energy > 0f }
        if (recent.size < 3) null else doubleArrayOf(
            recent.map { it.bpm.toDouble() }.average(),
            recent.map { it.energy.toDouble() }.average(),
            recent.map { it.brightness.toDouble() }.average()
        )
    }

    /** 1 when a track sits right on the session's centre, negative when far off. */
    private fun sessionFit(songId: Long): Double {
        val centre = sessionCentre ?: return 0.0
        val f = features[songId] ?: return 0.0
        if (f.energy <= 0f) return 0.0
        val bpmGap = if (f.bpm > 0f && centre[0] > 0.0) abs(f.bpm - centre[0]) / 55.0 else 1.0
        val energyGap = abs(f.energy - centre[1]) / 0.25
        val brightGap = abs(f.brightness - centre[2]) / 0.2
        return (1.0 - (bpmGap + energyGap + brightGap) / 3.0).coerceIn(-1.0, 1.0)
    }

    private val tokensBySong: Map<Long, List<String>> = songs.associate { it.id to tokensFor(it) }

    /**
     * How much each token actually distinguishes one song from another.
     *
     * Every token used to weigh the same. A song carries its style words plus
     * a length bucket, a tempo bucket and a mode, so "len:mid" - which sits on
     * half the library and separates nothing - counted exactly as much as
     * "חזנות", which sits on a handful and separates everything. The words the
     * user typed were left holding well under half the vector, and the more
     * the analyser measured the less they held.
     *
     * Inverse document frequency is the standard answer: a token on every song
     * carries no information and is worth almost nothing, a rare one is worth
     * a great deal. Smoothed with the 1 + so that a universal token fades
     * rather than vanishing outright.
     */
    private val tokenWeight: Map<String, Double> = run {
        val df = HashMap<String, Int>()
        for (tokens in tokensBySong.values) {
            for (t in tokens.distinct()) df[t] = (df[t] ?: 0) + 1
        }
        val total = songs.size.coerceAtLeast(1).toDouble()
        df.mapValues { (_, count) -> ln(1.0 + total / count) }
    }

    /**
     * Groups recordings of the same piece.
     *
     * A library built from downloads is full of them - a studio cut, a live take
     * and a remix all sit there as three unrelated tracks, and a shelf that
     * offers all three in a row feels broken. The name is the first test and the
     * harmony the second: chroma is stored rotated to the tonic, so the same
     * melody matches even when the live version was sung in another key.
     */
    private val versionKeyById: Map<Long, String> = songs.associate { song ->
        song.id to MediaScanner.normalizeKey(
            VERSION_NOISE.replace(song.title, " ") + " " + song.artistKey
        )
    }

    /** Keeps the first take of each piece and drops the rest, order preserved. */
    private fun dedupeVersions(list: List<SongEntity>): List<SongEntity> {
        val seen = HashSet<String>()
        return list.filter { seen.add(versionKeyById[it.id] ?: it.id.toString()) }
    }

    private val artistKeyById: Map<Long, String> = songs.associate { it.id to it.artistKey }

    /**
     * Whether each track is the studio cut, a stage take, a cover or a remix.
     *
     * Computed once for the whole snapshot: classifying a cover needs to look
     * across every artist who recorded the piece, so it cannot be answered one
     * song at a time.
     */
    val versionTypes: Map<Long, VersionType> =
        Versions.classify(songs) { id -> stats[id]?.playCount ?: 0 }

    /** The piece each song is a version of, ignoring who performed it. */
    private val pieceKeyById: Map<Long, String> =
        songs.associate { it.id to Versions.pieceKey(it.title) }

    /**
     * True when two tracks are the same song in different clothes.
     *
     * Broader than [sameRecording], which asks whether they are the same
     * recording. This one is what keeps a studio cut and its own live take from
     * landing next to each other in a queue: different recordings, but hearing
     * them back to back is hearing the same song twice.
     */
    fun samePiece(a: Long, b: Long): Boolean {
        if (a == b) return true
        val ka = pieceKeyById[a] ?: return false
        val kb = pieceKeyById[b] ?: return false
        return ka.isNotBlank() && ka == kb
    }

    /**
     * True when two tracks look like the same piece rather than two songs.
     *
     * Looked up rather than searched: this is called from inside the sequencer's
     * quadratic loop, and the two linear scans it used to do made ordering a
     * queue cubic in the size of the library.
     */
    fun sameRecording(a: Long, b: Long): Boolean {
        if (a == b) return true
        val ka = versionKeyById[a]
        val kb = versionKeyById[b]
        if (ka != null && ka == kb) return true
        val harmony = acoustic?.harmonicSimilarity(a, b) ?: return false
        if (harmony < 0.985) return false
        // Harmony alone confuses two songs in the same key; the artist has to
        // match as well before anything is called a duplicate.
        val sa = artistKeyById[a] ?: return false
        val sb = artistKeyById[b] ?: return false
        return sa == sb
    }

    /** how strongly the user's behaviour endorses each song, positive or negative */
    private val behaviour: Map<Long, Double> = songs.associate { it.id to behaviourWeight(it.id) }

    private val taste: Map<String, Double> = buildTasteVector()

    /** the songs the acoustic kNN measures against */
    private val acousticPositives: List<Long>
    private val acousticNegatives: List<Long>
    private val acousticFitById: Map<Long, Double>

    private val baseScores: Map<Long, Double>

    init {
        val space = acoustic
        acousticPositives = behaviour.entries
            .filter { it.value > 0.35 && space?.has(it.key) == true }
            .sortedByDescending { it.value }
            .take(60)
            .map { it.key }
        acousticNegatives = behaviour.entries
            .filter { it.value < -0.35 && space?.has(it.key) == true }
            .sortedBy { it.value }
            .take(30)
            .map { it.key }

        acousticFitById = if (space == null || acousticPositives.isEmpty()) {
            emptyMap()
        } else {
            songs.associate { song ->
                val positive = space.similarityToSet(song.id, acousticPositives, 3)
                val negative = if (acousticNegatives.isEmpty()) 0.0
                else space.similarityToSet(song.id, acousticNegatives, 2)
                song.id to (2.0 * positive - 1.0 - 0.9 * negative).coerceIn(-1.5, 1.0)
            }
        }

        baseScores = songs.associate { it.id to computeBase(it) }
    }

    // -----------------------------------------------------------------------
    // Tokens and taste
    // -----------------------------------------------------------------------

    private fun tokensFor(song: SongEntity): List<String> {
        val out = ArrayList<String>(8)
        // a tag put on the song itself wins over the artist's tags
        val songStyles = Styles.parse(stats[song.id]?.styles.orEmpty())
        if (songStyles.isNotEmpty()) out.addAll(songStyles)
        else artists[song.artistKey]?.styles?.let { out.addAll(Styles.parse(it)) }

        song.genre?.takeIf { it.isNotBlank() }?.let { out.add(it.trim()) }
        if (song.year in 1900..2100) out.add("decade:${song.year / 10 * 10}")
        val minutes = song.durationMs / 60000
        out.add(if (minutes < 3) "len:short" else if (minutes < 6) "len:mid" else "len:long")

        // measured tokens, so even an untagged library has something to match on
        features[song.id]?.let { f ->
            if (f.bpm > 20f) {
                out.add(
                    when {
                        f.bpm < 80f -> "tempo:slow"
                        f.bpm < 110f -> "tempo:mid"
                        f.bpm < 140f -> "tempo:up"
                        else -> "tempo:fast"
                    }
                )
            }
            if (f.mode >= 0) out.add(if (f.mode == 1) "mode:major" else "mode:minor")
        }
        return out.map { it.lowercase(Locale.ROOT) }.distinct()
    }

    /** The song's tokens, weighted by how much each one tells us, then L2 normalised. */
    private fun unitVector(tokens: List<String>): Map<String, Double> {
        if (tokens.isEmpty()) return emptyMap()
        val raw = tokens.associateWith { tokenWeight[it] ?: 1.0 }
        val norm = sqrt(raw.values.sumOf { it * it })
        if (norm < 1e-9) return emptyMap()
        return raw.mapValues { it.value / norm }
    }

    /**
     * A single number per song summarising how much the user's behaviour
     * endorses it. Reused by the taste vector and by the acoustic kNN seeds so
     * the two never disagree about what "liked" means.
     */
    private fun behaviourWeight(songId: Long): Double {
        val st = stats[songId] ?: return 0.0
        var w = 0.0
        if (st.playCount > 0) {
            val recency = if (st.lastPlayedAt == 0L) 0.0 else exp(-daysSince(st.lastPlayedAt) / 45.0)
            w += ln(1.0 + st.playCount) * (0.55 + 0.45 * recency)
        }
        w += when (st.liked) {
            1 -> 2.0
            -1 -> -2.6
            else -> 0.0
        }
        if (st.rating > 0) w += (st.rating - 3) * 0.8
        val attempts = st.playCount + st.skipCount
        if (attempts > 0) w -= 0.7 * (st.skipCount.toDouble() / attempts) * ln(1.0 + st.skipCount)
        return w
    }

    private fun buildTasteVector(): Map<String, Double> {
        val acc = HashMap<String, Double>()

        // (a) explicit artist ratings - useful from the very first minute
        for (artist in artists.values) {
            if (artist.rating == 0) continue
            val styles = Styles.parse(artist.styles)
            if (styles.isEmpty()) continue
            val w = (artist.rating - 3) * 1.1
            // Through the same weighting the songs get, or the two sides of
            // the cosine would be measuring on different scales.
            for ((k, value) in unitVector(styles.map { it.lowercase(Locale.ROOT) })) {
                acc[k] = (acc[k] ?: 0.0) + w * value
            }
        }

        // (b) behaviour, including per song ratings
        for (song in songs) {
            val w = behaviour[song.id] ?: 0.0
            if (abs(w) < 1e-6) continue
            for ((t, value) in unitVector(tokensBySong[song.id].orEmpty())) {
                acc[t] = (acc[t] ?: 0.0) + w * value
            }
        }

        val norm = sqrt(acc.values.sumOf { it * it })
        if (norm < 1e-9) return emptyMap()
        return acc.mapValues { it.value / norm }
    }

    private fun styleFit(songId: Long): Double {
        if (taste.isEmpty()) return 0.0
        val v = unitVector(tokensBySong[songId].orEmpty())
        if (v.isEmpty()) return 0.0
        var dot = 0.0
        for ((k, value) in v) dot += value * (taste[k] ?: 0.0)
        return dot.coerceIn(-1.0, 1.0)
    }

    private fun styleSimilarity(a: Long, b: Long): Double {
        val va = unitVector(tokensBySong[a].orEmpty())
        val vb = unitVector(tokensBySong[b].orEmpty())
        if (va.isEmpty() || vb.isEmpty()) return 0.0
        var dot = 0.0
        val (small, large) = if (va.size <= vb.size) va to vb else vb to va
        for ((k, value) in small) dot += value * (large[k] ?: 0.0)
        return dot
    }

    // -----------------------------------------------------------------------
    // Scoring
    // -----------------------------------------------------------------------

    private fun timeFit(st: SongStatsEntity?): Double {
        if (st == null) return 0.0
        val buckets = intArrayOf(st.b0, st.b1, st.b2, st.b3)
        val total = buckets.sum()
        if (total < 3) return 0.0
        val share = buckets[hourBucket].toDouble() / total
        return ((share - 0.25) / 0.75).coerceIn(-0.4, 1.0)
    }

    private fun ratingTerm(song: SongEntity): Double {
        val songRating = stats[song.id]?.rating ?: 0
        val artistRating = artists[song.artistKey]?.rating ?: 0
        return when {
            // a rating on the song itself is the most specific thing the user
            // ever says, so it outranks the artist level rating
            songRating > 0 -> 1.9 * ((songRating - 3) / 2.0) +
                if (artistRating > 0) 0.45 * ((artistRating - 3) / 2.0) else 0.0
            artistRating > 0 -> 1.35 * ((artistRating - 3) / 2.0)
            else -> -0.06
        }
    }

    private fun computeBase(song: SongEntity): Double {
        val st = stats[song.id]
        var score = 0.0

        score += tuning.artistWeight * ratingTerm(song)
        score += 1.55 * tuning.styleWeight * styleFit(song.id)
        score += 1.15 * tuning.acousticWeight * (acousticFitById[song.id] ?: 0.0)

        when (st?.liked ?: 0) {
            1 -> score += 1.5
            -1 -> score -= 7.0
        }

        val plays = st?.playCount ?: 0
        if (maxPlays > 0) score += 0.55 * (ln(1.0 + plays) / ln(1.0 + maxPlays))

        val skips = st?.skipCount ?: 0
        val attempts = plays + skips
        // Smoothed rather than taken raw. One play and two skips is a ratio of
        // 0.67 on three observations, and charging the full penalty for that
        // condemned songs on evidence far too thin to carry it. The prior it
        // is pulled towards is how often this listener skips anything at all,
        // so a library that is skipped through constantly does not read every
        // song in it as bad.
        if (attempts > 0) {
            val rate = (skips + SKIP_PRIOR * restlessness) / (attempts + SKIP_PRIOR)
            score -= 1.25 * rate
        }

        // How much of the track actually gets heard. A skip count alone is blunt:
        // it cannot tell a song abandoned after four seconds from one left at the
        // last chorus. Both numbers below were already being recorded and neither
        // was ever read.
        if (plays >= 2) {
            val finished = (st?.completeCount ?: 0).toDouble() / plays
            score += 0.9 * (finished - 0.5)
        }
        if (attempts >= 2 && song.durationMs > 0) {
            val expected = song.durationMs.toDouble() * attempts
            val heard = ((st?.listenedMs ?: 0L).toDouble() / expected).coerceIn(0.0, 1.0)
            score += 0.7 * (heard - 0.5)
        }

        score += 0.75 * timeFit(st)
        score += 0.6 * sessionFit(song.id)
        score -= 2.6 * tuning.repeatGuard * exp(-hoursSince(st?.lastPlayedAt ?: 0L) / 9.0)
        score += 0.35 * exp(-daysSince(song.dateAddedSec * 1000L) / 21.0)
        if (plays == 0) score += 1.1 * effectiveDiscovery

        return score
    }

    /** The same numbers the ranker used, formatted for the "why this" sheet. */
    fun explain(song: SongEntity): List<ScoreTerm> {
        val st = stats[song.id]
        val out = ArrayList<ScoreTerm>(10)
        val songRating = st?.rating ?: 0
        val artistRating = artists[song.artistKey]?.rating ?: 0

        out.add(
            ScoreTerm(
                "דירוג",
                tuning.artistWeight * ratingTerm(song),
                when {
                    songRating > 0 -> "השיר דורג $songRating מתוך 5"
                    artistRating > 0 -> "האמן דורג $artistRating מתוך 5"
                    else -> "לא דורג"
                }
            )
        )
        out.add(
            ScoreTerm(
                "התאמת סגנון",
                1.55 * tuning.styleWeight * styleFit(song.id),
                tokensBySong[song.id].orEmpty()
                    .filterNot { it.startsWith("decade:") || it.startsWith("len:") }
                    .joinToString(", ").ifEmpty { "אין תגיות" }
            )
        )
        val acousticFit = acousticFitById[song.id]
        out.add(
            ScoreTerm(
                "התאמת סאונד",
                1.15 * tuning.acousticWeight * (acousticFit ?: 0.0),
                features[song.id]?.let { f ->
                    "${f.bpm.toInt()} BPM · ${AudioAnalyzer.keyLabel(f.musicalKey, f.mode)}"
                } ?: "השיר עוד לא נותח"
            )
        )
        when (st?.liked ?: 0) {
            1 -> out.add(ScoreTerm("לייק", 1.5, "סימנת לייק"))
            -1 -> out.add(ScoreTerm("דיסלייק", -7.0, "סימנת דיסלייק"))
        }
        val plays = st?.playCount ?: 0
        if (maxPlays > 0) {
            out.add(
                ScoreTerm(
                    "היכרות",
                    0.55 * (ln(1.0 + plays) / ln(1.0 + maxPlays)),
                    "$plays השמעות"
                )
            )
        }
        val skips = st?.skipCount ?: 0
        val attempts = plays + skips
        if (attempts > 0) {
            val rate = (skips + SKIP_PRIOR * restlessness) / (attempts + SKIP_PRIOR)
            out.add(
                ScoreTerm("דילוגים", -1.25 * rate, "$skips דילוגים מתוך $attempts")
            )
        }
        // Four terms used to be missing from this list, so the numbers on
        // screen could not add up to the total printed above them - which is
        // the one thing a breakdown has to do.
        if (plays >= 2) {
            val finished = (st?.completeCount ?: 0).toDouble() / plays
            out.add(
                ScoreTerm(
                    "השלמת השיר",
                    0.9 * (finished - 0.5),
                    "${(finished * 100).toInt()}% מההשמעות הושלמו"
                )
            )
        }
        if (attempts >= 2 && song.durationMs > 0) {
            val expected = song.durationMs.toDouble() * attempts
            val heard = ((st?.listenedMs ?: 0L).toDouble() / expected).coerceIn(0.0, 1.0)
            out.add(
                ScoreTerm(
                    "כמה נשמע בפועל",
                    0.7 * (heard - 0.5),
                    "${(heard * 100).toInt()}% מהאורך, בממוצע"
                )
            )
        }
        val session = sessionFit(song.id)
        if (session != 0.0) {
            out.add(
                ScoreTerm("מה שמתנגן עכשיו", 0.6 * session, "לפי הסשן ב-45 הדקות האחרונות")
            )
        }
        val daysOnDevice = daysSince(song.dateAddedSec * 1000L)
        out.add(
            ScoreTerm(
                "נוסף לאחרונה",
                0.35 * exp(-daysOnDevice / 21.0),
                if (daysOnDevice > 9_000) "לא ידוע מתי נוסף"
                else "במכשיר כבר ${daysOnDevice.toInt()} ימים"
            )
        )
        out.add(
            ScoreTerm(
                "התאמה לשעה",
                0.75 * timeFit(st),
                bucketName(hourBucket)
            )
        )
        val hours = hoursSince(st?.lastPlayedAt ?: 0L)
        out.add(
            ScoreTerm(
                "מניעת חזרתיות",
                -2.6 * tuning.repeatGuard * exp(-hours / 9.0),
                if (hours > 100_000) "לא הושמע לאחרונה" else "הושמע לפני ${hours.toInt()} שעות"
            )
        )
        if (plays == 0) {
            out.add(ScoreTerm("גילוי", 1.1 * effectiveDiscovery, "עוד לא הושמע"))
        }
        return out.sortedByDescending { abs(it.value) }
    }

    fun totalScore(song: SongEntity): Double = baseScores[song.id] ?: 0.0

    private fun noise(id: Long, salt: Long, amount: Double): Double =
        (Random(id * 1_000_003L + salt).nextDouble() - 0.5) * amount

    /**
     * How strongly the listening says these belong together, with popularity
     * divided out.
     *
     * The edges are raw counts: every time two songs are heard close together
     * the weight between them goes up. So a song played two hundred times
     * accumulates heavy edges to everything it has ever sat near, and one
     * played three times has almost nothing - and the question "what goes with
     * this" quietly became "what is popular", which the score already answers
     * elsewhere and does not need answering twice.
     *
     * Dividing by the square root of the two play counts is the standard
     * correction, the same normalisation that turns a co-occurrence count into
     * a cosine. What is left is how often these two were heard together
     * relative to how often either was heard at all.
     */
    private fun affinityTo(seedIds: Collection<Long>, candidate: Long): Double {
        if (seedIds.isEmpty()) return 0.0
        val candidatePlays = (stats[candidate]?.playCount ?: 0).toDouble()
        var sum = 0.0
        for (seed in seedIds) {
            val weight = affinity[seed]?.get(candidate) ?: 0.0
            if (weight <= 0.0) continue
            val seedPlays = (stats[seed]?.playCount ?: 0).toDouble()
            // The + 1 keeps a pair whose counts have been reset from dividing
            // by zero, and costs nothing once either song has been played.
            sum += weight / sqrt((seedPlays + 1.0) * (candidatePlays + 1.0))
        }
        // The saturation constant moves with the scale: the terms above are now
        // fractions of one rather than counts, so the old 3.0 would have
        // flattened every pair to near nothing.
        return sum / (sum + AFFINITY_HALF)
    }

    /**
     * Directed: how often [to] followed [from] and was actually listened to,
     * minus how often it was skipped away from. Range roughly -1..1.
     */
    fun transitionScore(from: Long, to: Long): Double {
        val edge = transitions[from]?.get(to) ?: return 0.0
        val total = edge.weight + edge.penalty
        if (total <= 0.0) return 0.0
        return (edge.weight - 1.6 * edge.penalty) / (total + 2.0)
    }

    private fun acousticSimilarity(a: Long, b: Long): Double = acoustic?.similarity(a, b) ?: 0.0

    /**
     * Direction, which plain similarity cannot express.
     *
     * Acoustic distance is symmetric, but listening is not: stepping up in
     * energy carries a set forward, while dropping off a peak stalls it. Mild
     * rises are rewarded, steep falls penalised, and level moves left alone.
     */
    private fun liftFit(from: Long, to: Long): Double {
        val a = features[from] ?: return 0.0
        val b = features[to] ?: return 0.0
        if (a.energy <= 0f || b.energy <= 0f) return 0.0
        val step = ((b.energy - a.energy) / a.energy).coerceIn(-1.0f, 1.0f).toDouble()
        return when {
            step > 0.35 -> 0.1      // a jump is jarring in its own way
            step > 0.0 -> 1.0       // a lift forward
            step > -0.2 -> 0.5      // holding level is fine
            else -> -0.6            // falling off a peak
        }
    }

    private fun tempoDistance(a: Long, b: Long): Double = acoustic?.tempoDistance(a, b) ?: 0.0

    /**
     * Whether two tracks draw on the same modal world.
     *
     * This is the closest thing the app has to a sense of genre without a
     * trained model: Ahavah Rabbah and Hijaz carry the sound people hear as
     * Jewish or Middle Eastern, and a plain major scale does not. Only counted
     * when both estimates were clear enough to trust.
     */
    private fun modeFit(a: Long, b: Long): Double {
        val fa = features[a] ?: return 0.0
        val fb = features[b] ?: return 0.0
        if (fa.scaleConfidence < 0.2f || fb.scaleConfidence < 0.2f) return 0.0
        val ma = MusicalMode.byOrdinalOrNull(fa.scaleMode) ?: return 0.0
        val mb = MusicalMode.byOrdinalOrNull(fb.scaleMode) ?: return 0.0
        return when {
            ma == mb -> 1.0
            ma in MusicalMode.EASTERN && mb in MusicalMode.EASTERN -> 0.55
            ma in MusicalMode.LITURGICAL && mb in MusicalMode.LITURGICAL -> 0.5
            ma.brightFamily == mb.brightFamily -> 0.2
            else -> -0.25
        }
    }

    // -----------------------------------------------------------------------
    // Selection with diversity
    // -----------------------------------------------------------------------

    private fun pick(
        candidates: List<SongEntity>,
        count: Int,
        salt: Long,
        maxPerArtist: Int = 2,
        maxPerAlbum: Int = 2,
        extra: ((SongEntity) -> Double)? = null
    ): List<SongEntity> {
        if (candidates.isEmpty() || count <= 0) return emptyList()
        val jitter = 0.22 + 0.85 * effectiveDiscovery
        val ranked = candidates.map { song ->
            var s = baseScores[song.id] ?: 0.0
            if (extra != null) s += extra(song)
            s += noise(song.id, salt + feedSeed, jitter)
            song to s
        }.sortedByDescending { it.second }

        val artistCount = HashMap<String, Int>()
        val albumCount = HashMap<Long, Int>()
        // One take of a piece per shelf. Offering the studio cut, the live take
        // and the remix as three separate recommendations is the single most
        // obvious way a library of downloads looks broken.
        val versionsUsed = HashSet<String>()
        val out = ArrayList<SongEntity>(count)
        for ((song, _) in ranked) {
            if (out.size >= count) break
            val a = artistCount.getOrDefault(song.artistKey, 0)
            val b = albumCount.getOrDefault(song.albumId, 0)
            if (a >= maxPerArtist || b >= maxPerAlbum) continue
            val version = versionKeyById[song.id]
            if (version != null && !versionsUsed.add(version)) continue
            artistCount[song.artistKey] = a + 1
            albumCount[song.albumId] = b + 1
            out.add(song)
        }
        if (out.size < count) {
            val taken = out.mapTo(HashSet()) { it.id }
            for ((song, _) in ranked) {
                if (out.size >= count) break
                if (song.id in taken) continue
                out.add(song)
            }
        }
        return out
    }

    /**
     * Orders an already chosen set so consecutive tracks flow into each other:
     * learned transitions first, then acoustic and tempo continuity.
     * Greedy nearest neighbour starting from the given head.
     */
    fun sequence(head: SongEntity, rest: List<SongEntity>): List<SongEntity> {
        if (rest.size < 2) return listOf(head) + rest
        val remaining = rest.toMutableList()
        val out = ArrayList<SongEntity>(rest.size + 1)
        out.add(head)
        var current = head
        while (remaining.isNotEmpty()) {
            var bestIndex = 0
            var bestScore = Double.NEGATIVE_INFINITY
            for (i in remaining.indices) {
                val c = remaining[i]
                val s = 2.4 * transitionScore(current.id, c.id) +
                    0.9 * acousticSimilarity(current.id, c.id) +
                    0.5 * styleSimilarity(current.id, c.id) -
                    0.7 * tempoDistance(current.id, c.id) +
                    0.55 * liftFit(current.id, c.id) +
                    0.8 * modeFit(current.id, c.id) -
                    (if (sameRecording(current.id, c.id)) 3.0 else 0.0) -
                    // The studio cut followed by its own live take is the same
                    // song twice in a row. Different recordings, so the penalty
                    // above does not catch it, and heard back to back it is the
                    // most obviously wrong thing a queue can do.
                    (if (samePiece(current.id, c.id)) 2.2 else 0.0) +
                    noise(c.id, current.id, 0.35)
                if (s > bestScore) {
                    bestScore = s
                    bestIndex = i
                }
            }
            current = remaining.removeAt(bestIndex)
            out.add(current)
        }
        return out
    }

    // -----------------------------------------------------------------------
    // Radio and queue continuation
    // -----------------------------------------------------------------------

    fun radio(seed: SongEntity, size: Int = 40): List<SongEntity> {
        val seedIds = listOf(seed.id)
        // Medleys are excluded from anything generated. Landing on one without
        // having chosen it is indistinguishable from a song starting halfway
        // through, which is the single most jarring thing an automatic queue
        // can do. Seeding a radio *from* a medley is still allowed - that was a
        // deliberate choice.
        val pool = songs.filter {
            it.id != seed.id && (stats[it.id]?.liked ?: 0) != -1 && !isMedley(it.title) &&
                !separated(seed.id, it.id)
        }
        val chosen = pick(
            candidates = pool,
            count = size,
            salt = seed.id,
            maxPerArtist = 4,
            maxPerAlbum = 3
        ) { candidate ->
            var bonus = 2.3 * affinityTo(seedIds, candidate.id)
            bonus += 1.35 * styleSimilarity(seed.id, candidate.id)
            bonus += 1.5 * acousticSimilarity(seed.id, candidate.id)
            bonus += 1.2 * transitionScore(seed.id, candidate.id)
            bonus -= 0.45 * tempoDistance(seed.id, candidate.id)
            if (candidate.artistKey == seed.artistKey) bonus += 0.55
            if (candidate.albumId == seed.albumId) bonus += 0.2
            bonus
        }
        return sequence(seed, chosen)
    }

    /** Used when the queue is running out and autoplay is on. */
    fun continuation(recent: List<Long>, exclude: Set<Long>, size: Int = 20): List<SongEntity> {
        val seedIds = recent.take(5)
        val last = seedIds.firstOrNull()
        val pool = songs.filter {
            it.id !in exclude && (stats[it.id]?.liked ?: 0) != -1 && !isMedley(it.title) &&
                // Against the track just played, not the whole of `recent`: a
                // continuation follows what is happening now, and a sitting
                // that moved from one style to another should be allowed to
                // carry on where it got to.
                (last == null || !separated(last, it.id))
        }
        val chosen = pick(pool, size, salt = (last ?: 7L), maxPerArtist = 3) { candidate ->
            var bonus = 1.6 * affinityTo(seedIds, candidate.id)
            if (last != null) {
                bonus += 2.6 * transitionScore(last, candidate.id)
                bonus += 1.2 * acousticSimilarity(last, candidate.id)
                bonus += 0.9 * styleSimilarity(last, candidate.id)
                bonus -= 0.4 * tempoDistance(last, candidate.id)
            }
            bonus
        }
        val head = chosen.firstOrNull() ?: return emptyList()
        return sequence(head, chosen.drop(1))
    }

    // -----------------------------------------------------------------------
    // Home feed
    // -----------------------------------------------------------------------

    fun buildFeed(): List<FeedSection> {
        if (songs.isEmpty()) return emptyList()
        val sections = ArrayList<FeedSection>(8)
        // Medleys stay out of every generated shelf and mix, for the same reason
        // they stay out of radio: one arriving unasked sounds like a song that
        // began halfway through. They are still there to be played on purpose
        // from the library, from search, and from a folder.
        val notDisliked = songs.filter {
            (stats[it.id]?.liked ?: 0) != -1 && !isMedley(it.title)
        }

        // Speed dial: the handful of tracks actually returned to, first on the
        // page. Held back until there is real listening behind it - a "most
        // played" shelf built from one play each is just a shuffle.
        val mostPlayed = notDisliked
            .filter { (stats[it.id]?.playCount ?: 0) >= 3 }
            .sortedByDescending { stats[it.id]?.playCount ?: 0 }
            .take(12)
        if (mostPlayed.size >= 4) {
            sections.add(
                FeedSection(
                    id = "speeddial",
                    title = "חיוג מהיר",
                    subtitle = "מה שאתה חוזר אליו הכי הרבה",
                    kind = SectionKind.QUICK_PICKS,
                    songs = mostPlayed
                )
            )
        }

        sections.add(
            FeedSection(
                id = "quick",
                title = "בחירה מהירה",
                subtitle = "מבוסס על מה שאתה שומע, על הדירוגים ועל הסאונד עצמו",
                kind = SectionKind.QUICK_PICKS,
                songs = pick(notDisliked, 20, salt = 11L)
            )
        )

        val mixes = ArrayList<Mix>(12)

        mixes.add(
            Mix(
                id = "mix:you",
                title = "המיקס שלך",
                subtitle = "נבנה מחדש בכל רענון",
                songs = pick(notDisliked, 60, salt = 23L, maxPerArtist = 4, maxPerAlbum = 3)
                    .let { if (it.isEmpty()) it else sequence(it.first(), it.drop(1)) }
            )
        )

        val unheard = notDisliked.filter { (stats[it.id]?.playCount ?: 0) == 0 }
        if (unheard.size >= 6) {
            mixes.add(
                Mix(
                    id = "mix:discover",
                    title = "מיקס גילוי",
                    subtitle = "${unheard.size} שירים שעוד לא שמעת",
                    songs = pick(unheard, 45, salt = 31L, maxPerArtist = 3, maxPerAlbum = 2)
                )
            )
        }

        val liked = songs.filter { (stats[it.id]?.liked ?: 0) == 1 }
        if (liked.size >= 4) {
            val likedIds = liked.map { it.id }
            val likedSet = likedIds.toSet()
            val pool = notDisliked.filter { it.id !in likedSet }
            val expanded = pick(pool, 30, salt = 41L, maxPerArtist = 3) { c ->
                1.9 * affinityTo(likedIds.take(12), c.id)
            }
            mixes.add(
                Mix(
                    id = "mix:liked",
                    title = "על בסיס האהובים",
                    subtitle = "מהשירים שסימנת בלייק והסביבה שלהם",
                    songs = (liked.shuffled(Random(feedSeed)) + expanded).take(50)
                )
            )
        }

        // rated songs get their own shelf now that ratings exist per song
        val topRated = songs.filter { (stats[it.id]?.rating ?: 0) >= 4 }
        if (topRated.size >= 5) {
            mixes.add(
                Mix(
                    id = "mix:rated",
                    title = "החמישיות שלך",
                    subtitle = "${topRated.size} שירים שדירגת 4 ומעלה",
                    songs = pick(topRated, 50, salt = 47L, maxPerArtist = 5, maxPerAlbum = 4)
                )
            )
        }

        val timeTitle = when (hourBucket) {
            1 -> "מיקס בוקר"
            2 -> "מיקס צהריים"
            3 -> "מיקס ערב"
            else -> "מיקס לילה"
        }
        mixes.add(
            Mix(
                id = "mix:time:$hourBucket",
                title = timeTitle,
                subtitle = "לפי מה שאתה בדרך כלל שומע בשעה הזאת",
                songs = pick(notDisliked, 40, salt = 53L + hourBucket) { c ->
                    1.5 * timeFit(stats[c.id])
                }
            )
        )

        // tempo based mixes, only meaningful once the analyser has run
        if (features.size >= 12) {
            val fast = notDisliked.filter { s ->
                val f = features[s.id] ?: return@filter false
                f.bpm >= 112f && f.onsetRate >= 1.2f
            }
            if (fast.size >= 8) {
                mixes.add(
                    Mix(
                        id = "mix:tempo:fast",
                        title = "מיקס קצבי",
                        subtitle = "מעל 112 פעימות בדקה",
                        songs = pick(fast, 40, salt = 67L, maxPerArtist = 3)
                    )
                )
            }
            val calm = notDisliked.filter { s ->
                val f = features[s.id] ?: return@filter false
                f.bpm in 1f..95f || (f.onsetRate < 0.8f && f.dynamics < 0.9f)
            }
            if (calm.size >= 8) {
                mixes.add(
                    Mix(
                        id = "mix:tempo:calm",
                        title = "מיקס רגוע",
                        subtitle = "איטי, פחות הקשה",
                        songs = pick(calm, 40, salt = 71L, maxPerArtist = 3)
                    )
                )
            }
            // acoustic neighbourhood of the single most endorsed song
            val anchor = behaviour.entries
                .filter { acoustic?.has(it.key) == true }
                .maxByOrNull { it.value }
                ?.key
                ?.let { id -> songs.firstOrNull { it.id == id } }
            if (anchor != null && acousticPositives.isNotEmpty()) {
                val neighbours = pick(
                    notDisliked.filter { it.id != anchor.id },
                    35,
                    salt = 79L,
                    maxPerArtist = 3
                ) { c -> 2.4 * acousticSimilarity(anchor.id, c.id) }
                mixes.add(
                    Mix(
                        id = "mix:sound:${anchor.id}",
                        title = "אותו סאונד",
                        subtitle = "שירים שנשמעים כמו \"${anchor.title}\"",
                        songs = listOf(anchor) + neighbours
                    )
                )
            }
        }

        val topStyles = taste.entries
            .filter { it.value > 0 && !it.key.startsWith("decade:") && !it.key.startsWith("len:") &&
                !it.key.startsWith("tempo:") && !it.key.startsWith("mode:") }
            .sortedByDescending { it.value }
            .take(4)
        for ((style, _) in topStyles) {
            val pool = notDisliked.filter { style in tokensBySong[it.id].orEmpty() }
            if (pool.size < 6) continue
            mixes.add(
                Mix(
                    id = "mix:style:$style",
                    title = "מיקס $style",
                    subtitle = "${pool.size} שירים בסגנון הזה",
                    songs = pick(pool, 40, salt = style.hashCode().toLong(), maxPerArtist = 3)
                )
            )
        }

        val songsByArtist = notDisliked.groupBy { it.artistKey }
        val topArtists = artists.values
            .filter { it.rating >= 4 && (songsByArtist[it.artistKey]?.size ?: 0) >= 4 }
            .sortedByDescending { it.rating * 100 + (songsByArtist[it.artistKey]?.size ?: 0) }
            .take(4)
        for (artist in topArtists) {
            val own = songsByArtist[artist.artistKey].orEmpty()
            val neighbours = pick(
                notDisliked.filter { it.artistKey != artist.artistKey },
                12,
                salt = artist.artistKey.hashCode().toLong()
            ) { c ->
                1.6 * affinityTo(own.map { it.id }.take(10), c.id) +
                    0.9 * acousticSimilarity(own.first().id, c.id)
            }
            mixes.add(
                Mix(
                    id = "mix:artist:${artist.artistKey}",
                    title = "רדיו ${artist.displayName}",
                    subtitle = "${artist.displayName} ודומים לו",
                    songs = (pick(own, 26, salt = 61L, maxPerArtist = 99, maxPerAlbum = 99) + neighbours)
                        .distinctBy { it.id }
                )
            )
        }

        sections.add(
            FeedSection(
                id = "mixes",
                title = "מיקסים בשבילך",
                kind = SectionKind.MIX_ROW,
                mixes = mixes.filter { it.songs.size >= 4 }
            )
        )

        val daily = dailyMixes()
        if (daily.isNotEmpty()) {
            sections.add(
                FeedSection(
                    id = "daily",
                    title = "המיקסים היומיים שלך",
                    subtitle = "אשכולות סאונד שנמצאו בספרייה עצמה",
                    kind = SectionKind.MIX_ROW,
                    mixes = daily
                )
            )
        }

        val heard = notDisliked.filter { (stats[it.id]?.playCount ?: 0) >= 2 }
        if (heard.size >= 6) {
            sections.add(
                FeedSection(
                    id = "again",
                    title = "תשמע שוב",
                    kind = SectionKind.SONG_ROW,
                    songs = heard.sortedByDescending { s ->
                        val st = stats[s.id]!!
                        ln(1.0 + st.playCount) * (0.4 + 0.6 * exp(-daysSince(st.lastPlayedAt) / 30.0))
                    }.take(20)
                )
            )
        }

        val lastLiked = songs
            .filter { (stats[it.id]?.liked ?: 0) == 1 }
            .maxByOrNull { stats[it.id]?.likedAt ?: 0L }
        if (lastLiked != null) {
            val related = radio(lastLiked, 18).drop(1)
            if (related.size >= 6) {
                sections.add(
                    FeedSection(
                        id = "because:${lastLiked.id}",
                        title = "כי אהבת את \"${lastLiked.title}\"",
                        kind = SectionKind.SONG_ROW,
                        songs = related
                    )
                )
            }
        }

        if (unheard.size >= 6) {
            sections.add(
                FeedSection(
                    id = "fresh",
                    title = "עדיין לא שמעת",
                    subtitle = "מדורג לפי הדירוגים, הסגנונות והסאונד",
                    kind = SectionKind.SONG_ROW,
                    songs = pick(unheard, 20, salt = 71L, maxPerArtist = 2)
                )
            )
        }

        // The one artist the listening actually points at, with their strongest
        // tracks. Deliberately a single artist: a home page carrying three of
        // these stops being a recommendation and becomes a directory.
        val engagement = HashMap<String, Double>()
        for (s in songs) {
            val st = stats[s.id] ?: continue
            var w = ln(1.0 + st.playCount)
            if (st.liked == 1) w += 1.5
            if (st.liked == -1) w -= 1.5
            if (st.rating >= 4) w += 1.0
            engagement.merge(s.artistKey, w) { x, y -> x + y }
        }
        val favouriteKey = engagement.entries
            .filter { it.value >= 3.0 }
            .maxByOrNull { it.value }
            ?.key
        if (favouriteKey != null) {
            val name = artists[favouriteKey]?.displayName?.takeIf { it.isNotBlank() }
                ?: songs.firstOrNull { it.artistKey == favouriteKey }?.artistName
            val best = dedupeVersions(
                notDisliked
                    .filter { it.artistKey == favouriteKey }
                    .sortedByDescending { totalScore(it) }
            ).take(20)
            if (name != null && best.size >= 5) {
                sections.add(
                    FeedSection(
                        id = "artist:$favouriteKey",
                        title = "כי אתה שומע הרבה $name",
                        subtitle = "הטובים ביותר שלו, לפי מה שנלמד",
                        kind = SectionKind.SONG_ROW,
                        songs = best
                    )
                )
            }
        }

        // A stage recording of a song you already own is a genuinely different
        // listen, so the live takes get a shelf of their own rather than being
        // scattered through the others.
        val live = dedupeVersions(
            notDisliked.filter { isLiveRecording(it.title) }
                .sortedByDescending { totalScore(it) }
        )
        if (live.size >= 3) {
            sections.add(
                FeedSection(
                    id = "live",
                    title = "הופעות חיות",
                    subtitle = "הקלטות במה מתוך הספרייה",
                    kind = SectionKind.SONG_ROW,
                    songs = live.take(24)
                )
            )
        }

        // Live takes of tracks already liked in the studio. The connection the
        // listener most wants and the hardest one to stumble on by browsing.
        val likedStudio = songs.filter {
            (stats[it.id]?.liked ?: 0) == 1 && !isLiveRecording(it.title)
        }
        if (likedStudio.isNotEmpty()) {
            val liveOfLiked = notDisliked.filter { candidate ->
                isLiveRecording(candidate.title) &&
                    likedStudio.any { it.id != candidate.id && sameRecording(it.id, candidate.id) }
            }
            if (liveOfLiked.size >= 3) {
                sections.add(
                    FeedSection(
                        id = "liveofliked",
                        title = "על הבמה — שירים שאהבת",
                        subtitle = "גרסאות חיות לשירים שסימנת",
                        kind = SectionKind.SONG_ROW,
                        songs = liveOfLiked.sortedByDescending { totalScore(it) }.take(20)
                    )
                )
            }
        }

        // Someone else's take on a song already in the library. Live recordings
        // are deliberately not here: they are a different kind of alternative
        // and they have two shelves of their own above, and mixing them in
        // left this one mostly full of concert tracks. Deduping by version key
        // would defeat the point, since the alternatives are the offer.
        val alternates = Versions.alternates(notDisliked, versionTypes)
        if (alternates.size >= 3) {
            sections.add(
                FeedSection(
                    id = "covers",
                    title = "גרסאות כיסוי",
                    subtitle = "ביצועים של אמנים אחרים לשירים שכבר יש לך",
                    kind = SectionKind.SONG_ROW,
                    songs = alternates.sortedByDescending { totalScore(it) }.take(24)
                )
            )
        }

        // What the user has actually claimed - liked or rated - as opposed to what
        // merely sits on the device.
        val yours = notDisliked.filter {
            val st = stats[it.id]
            (st?.liked ?: 0) == 1 || (st?.rating ?: 0) > 0
        }
        if (yours.size >= 6) {
            sections.add(
                FeedSection(
                    id = "yours",
                    title = "מהספרייה שלך",
                    subtitle = "מה שסימנת ודירגת",
                    kind = SectionKind.SONG_ROW,
                    songs = yours.sortedByDescending { totalScore(it) }.take(24)
                )
            )
        }

        // The mood the user's own picks cluster into, then more of it. This leans
        // on measured audio rather than the style tags, which stay empty until
        // somebody types them in by hand.
        val engaged = songs.filter {
            val st = stats[it.id]
            (st?.liked ?: 0) == 1 || (st?.rating ?: 0) >= 4 || (st?.playCount ?: 0) >= 3
        }
        val moodModel = MoodModel(features.values)
        if (engaged.size >= 5 && moodModel.ready) {
            val favourite = favouriteMood(engaged, moodModel)
            if (favourite != null) {
                val more = notDisliked
                    .filter { moodModel.matches(favourite, features[it.id]) && it !in engaged }
                    .sortedByDescending { totalScore(it) }
                    .take(20)
                if (more.size >= 6) {
                    sections.add(
                        FeedSection(
                            id = "affinity:${favourite.name}",
                            title = "נראה שאתה אוהב ${favourite.label}",
                            subtitle = favourite.subtitle,
                            kind = SectionKind.SONG_ROW,
                            songs = more
                        )
                    )
                }
            }
        }

        // Long-form audio - shiurim, sets, full concerts - is a different kind of
        // listen from a three minute single, so it gets its own shelf when any
        // turns up. Nothing in a library of singles will match, and the section
        // simply stays hidden until something does.
        val longForm = notDisliked.filter { it.durationMs >= 15 * 60 * 1000L }
        if (longForm.size >= 2) {
            sections.add(
                FeedSection(
                    id = "longform",
                    title = "סרטוני מוזיקה ארוכים",
                    subtitle = "מעל רבע שעה",
                    kind = SectionKind.SONG_ROW,
                    songs = longForm.sortedByDescending { it.durationMs }.take(20)
                )
            )
        }

        val recentFiles = songs.sortedByDescending { it.dateAddedSec }.take(20)
        if (recentFiles.size >= 6) {
            sections.add(
                FeedSection(
                    id = "added",
                    title = "נוספו לאחרונה למכשיר",
                    kind = SectionKind.SONG_ROW,
                    songs = recentFiles
                )
            )
        }

        return sections.filter {
            (it.kind == SectionKind.MIX_ROW && it.mixes.isNotEmpty()) || it.songs.isNotEmpty()
        }
    }

    /**
     * The mood the listening leans towards, or null when it does not lean.
     *
     * This shelf announces a conclusion about a person - "it looks like you
     * like calm" - and it was changing its mind constantly. Three reasons, all
     * of them fixable:
     *
     * The moods are not exclusive. One track can be calm and dark and steady
     * at once, so it is counted for לילה, רגוע and ריכוז together and the
     * totals sit on top of each other. Taking the largest of a set of numbers
     * that are nearly equal is taking noise.
     *
     * There was no margin. A library where calm scored five and rhythmic four
     * produced a confident headline, and the next play reversed it.
     *
     * And there was no memory. Nothing knew what it had said last time, so
     * there was nothing to be consistent with.
     *
     * So: a share rather than a count, a clear margin over the runner up
     * before anything is claimed at all, and the previous answer defended
     * unless the new one beats it by that same margin. What it costs is
     * reacting a little late to a real change in taste, which is the right
     * way round for a sentence that purports to describe someone.
     */
    fun favouriteMood(engaged: List<SongEntity>, model: MoodModel): Mood? {
        if (engaged.size < 5) return null
        val counts = Mood.entries
            .map { mood -> mood to engaged.count { model.matches(mood, features[it.id]) } }
            .filter { it.second >= 3 }
            .sortedByDescending { it.second }
        if (counts.isEmpty()) return null

        val leader = counts[0]
        val runnerUp = counts.getOrNull(1)?.second ?: 0
        val total = engaged.size.toDouble()
        val margin = (leader.second - runnerUp) / total

        val previous = Mood.entries.firstOrNull { it.name == tuning.lastMood }
        if (previous != null && previous != leader.first) {
            // The incumbent keeps the shelf unless the challenger is clearly
            // ahead. Without this the two swap on a single play.
            val held = counts.firstOrNull { it.first == previous }
            if (held != null && (leader.second - held.second) / total < MOOD_MARGIN) {
                pickedMood = previous.name
                return previous
            }
        }
        if (margin < MOOD_MARGIN && previous != leader.first) {
            // Nothing is clearly in front and there is no incumbent to keep.
            // Saying nothing is better than saying whichever was first in the
            // enum, which is what the old code did.
            pickedMood = null
            return null
        }
        pickedMood = leader.first.name
        return leader.first
    }

    /**
     * The mood [buildFeed] settled on, for the caller to remember.
     *
     * A snapshot is thrown away after every feed, so the value has to leave
     * through something; this is read once, straight after buildFeed.
     */
    var pickedMood: String? = null
        private set

    // -----------------------------------------------------------------------
    // Daily mixes: k-means over the acoustic space
    // -----------------------------------------------------------------------

    /**
     * Splits the analysed part of the library into a handful of sound-alike
     * clusters and turns each into its own mix. This is the offline stand-in
     * for Spotify's daily mixes: instead of clustering listeners, it clusters
     * the audio itself, which is the only material available here.
     *
     * Returns an empty list until enough songs have been analysed for the
     * clusters to mean anything.
     */
    fun dailyMixes(maxMixes: Int = 6): List<Mix> {
        val space = acoustic ?: return emptyList()
        val entries = songs.filter { space.has(it.id) }
        if (entries.size < 40) return emptyList()

        val dims = AcousticSpace.DIMS
        val points = entries.map { space.vectors[it.id]!! }
        val k = minOf(maxMixes, maxOf(2, entries.size / 60))
        // By the day, not by the refresh button. These are called the daily
        // mixes and they were changing only when the feed was reshuffled.
        val random = Random(now / 86_400_000L * 31 + 7)

        // k-means++ seeding: first centre at random, the rest biased towards
        // whatever is furthest from what has been chosen already
        val centres = ArrayList<DoubleArray>(k)
        centres.add(points[random.nextInt(points.size)].copyOf())
        while (centres.size < k) {
            val distances = points.map { p ->
                centres.minOf { c -> squaredDistance(p, c, dims) }
            }
            val total = distances.sum()
            if (total <= 1e-9) break
            var target = random.nextDouble() * total
            var index = 0
            while (index < distances.size - 1 && target > distances[index]) {
                target -= distances[index]
                index++
            }
            centres.add(points[index].copyOf())
        }
        if (centres.size < 2) return emptyList()

        val assignment = IntArray(points.size)
        repeat(14) {
            var moved = false
            for (i in points.indices) {
                var best = 0
                var bestDistance = Double.MAX_VALUE
                for (c in centres.indices) {
                    val d = squaredDistance(points[i], centres[c], dims)
                    if (d < bestDistance) {
                        bestDistance = d
                        best = c
                    }
                }
                if (assignment[i] != best) {
                    assignment[i] = best
                    moved = true
                }
            }
            for (c in centres.indices) {
                val sums = DoubleArray(dims)
                var count = 0
                for (i in points.indices) {
                    if (assignment[i] != c) continue
                    val p = points[i]
                    for (d in 0 until dims) sums[d] += p[d]
                    count++
                }
                if (count > 0) for (d in 0 until dims) centres[c][d] = sums[d] / count
            }
            if (!moved) return@repeat
        }

        val out = ArrayList<Mix>(centres.size)
        for (c in centres.indices) {
            val clustered = entries.filterIndexed { i, _ -> assignment[i] == c }
            // Clusters are acoustic, and two styles the user keeps apart can
            // sound alike enough to land in the same one. Whichever of them
            // has more of the cluster keeps it, and the rest are dropped -
            // they will have their own cluster elsewhere.
            val members = withoutSeparated(clustered)
            if (members.size < 10) continue
            val label = clusterLabel(members, c)
            out.add(
                Mix(
                    id = "mix:daily:$c",
                    title = label,
                    subtitle = "${members.size} שירים שנשמעים דומה",
                    songs = pick(
                        members.filter { (stats[it.id]?.liked ?: 0) != -1 },
                        45,
                        salt = 900L + c,
                        maxPerArtist = 4,
                        maxPerAlbum = 3
                    )
                )
            )
        }
        return out.sortedByDescending { it.songs.size }
    }

    /** Names a cluster after its most common style tag, then its top artist. */
    private fun clusterLabel(members: List<SongEntity>, index: Int): String {
        val styleCounts = HashMap<String, Int>()
        for (song in members) {
            for (token in tokensBySong[song.id].orEmpty()) {
                if (token.startsWith("decade:") || token.startsWith("len:") ||
                    token.startsWith("tempo:") || token.startsWith("mode:")
                ) continue
                styleCounts[token] = (styleCounts[token] ?: 0) + 1
            }
        }
        val topStyle = styleCounts.entries.maxByOrNull { it.value }
        if (topStyle != null && topStyle.value >= members.size / 3) {
            return "מיקס ${topStyle.key}"
        }
        val topArtist = members.groupingBy { it.artistName }.eachCount()
            .entries.maxByOrNull { it.value }
        val medianBpm = members.mapNotNull { features[it.id]?.bpm?.takeIf { b -> b > 20f } }
            .sorted()
            .let { if (it.isEmpty()) 0 else it[it.size / 2].toInt() }
        return when {
            topArtist != null && topArtist.value >= members.size / 3 ->
                "מיקס ${topArtist.key}"
            medianBpm > 0 -> "מיקס ${medianBpm} BPM"
            else -> "מיקס ${index + 1}"
        }
    }

    private fun squaredDistance(a: DoubleArray, b: DoubleArray, dims: Int): Double {
        var acc = 0.0
        for (d in 0 until dims) {
            val delta = a[d] - b[d]
            acc += delta * delta
        }
        return acc
    }

    fun tasteReport(): TasteReport {
        val bpms = features.values.map { it.bpm }.filter { it > 20f }.sorted()
        return TasteReport(
            topStyles = taste.entries
                .filter {
                    it.value > 0 && !it.key.startsWith("decade:") && !it.key.startsWith("len:")
                }
                .sortedByDescending { it.value }
                .take(8)
                .map { it.key to it.value },
            ratedArtists = artists.values.count { it.rating > 0 },
            totalArtists = max(artists.size, songs.map { it.artistKey }.distinct().size),
            ratedSongs = stats.values.count { it.rating > 0 },
            songsWithStyle = songs.count { s ->
                Styles.parse(stats[s.id]?.styles.orEmpty()).isNotEmpty() ||
                    Styles.parse(artists[s.artistKey]?.styles.orEmpty()).isNotEmpty()
            },
            totalSongs = songs.size,
            learnedPairs = affinity.values.sumOf { it.size },
            learnedTransitions = transitions.values.sumOf { it.size },
            analyzedSongs = features.size,
            medianBpm = if (bpms.isEmpty()) 0 else bpms[bpms.size / 2].toInt()
        )
    }

    /**
     * Searchable text per song, folded once rather than on every keystroke.
     *
     * Search runs on every character typed, over the whole library; normalising
     * three fields per song inside that loop is work done thousands of times to
     * get the same answer.
     */
    private val searchFields: Map<Long, Triple<String, String, String>> =
        songs.associate { song ->
            song.id to Triple(
                SearchText.normalize(song.title),
                SearchText.normalize(song.artistName),
                SearchText.normalize(song.albumName)
            )
        }

    /**
     * @param personal how much the learned taste is allowed to reorder results,
     *   0 for none. Text relevance always dominates: someone typing a title
     *   wants that title, not the app's opinion of it.
     */
    fun search(query: String, limit: Int = 60, personal: Double = 0.25): List<SongEntity> {
        val terms = SearchText.terms(query)
        if (terms.isEmpty()) return emptyList()
        return songs.mapNotNull { song ->
            val fields = searchFields[song.id] ?: return@mapNotNull null
            val (title, artist, album) = fields
            var textScore = 0.0
            for (t in terms) {
                // Best field wins for each term, so matching the artist does not
                // disqualify a song whose title matches the next word.
                val best = listOfNotNull(
                    SearchText.score(title, t),
                    SearchText.score(artist, t)?.times(0.8),
                    SearchText.score(album, t)?.times(0.4)
                ).maxOrNull() ?: return@mapNotNull null
                textScore += best
            }
            song to textScore + personal * (baseScores[song.id] ?: 0.0)
        }.sortedByDescending { it.second }.take(limit).map { it.first }
    }

    // -----------------------------------------------------------------------

    private fun hoursSince(t: Long): Double =
        if (t <= 0L) 1_000_000.0 else max(0.0, (now - t) / 3_600_000.0)

    private fun daysSince(t: Long): Double =
        if (t <= 0L) 10_000.0 else max(0.0, (now - t) / 86_400_000.0)

    private fun bucketName(bucket: Int): String = when (bucket) {
        1 -> "בוקר"
        2 -> "צהריים"
        3 -> "ערב"
        else -> "לילה"
    }

    companion object {

        /**
         * Where the affinity term reaches half its ceiling.
         *
         * Chosen for the normalised scale: a pair that is heard together most
         * of the times either is heard scores near 1 before the sum, and this
         * puts the midpoint where an ordinary strong pair lands.
         */
        private const val AFFINITY_HALF = 0.6

        /**
         * Strength of the prior on the skip rate, in observations.
         *
         * Four means a song needs four attempts of its own before its measured
         * rate outweighs the listener's baseline, which is about where three
         * skips stop being an accident.
         */
        private const val SKIP_PRIOR = 4.0

        /**
         * How far in front a mood has to be before the shelf names it, as a
         * share of the songs the listener has actually engaged with.
         *
         * A tenth: on fifty engaged songs that is five songs of daylight,
         * which a single play cannot manufacture.
         */
        private const val MOOD_MARGIN = 0.10

        /** 0 night, 1 morning, 2 afternoon, 3 evening */
        fun bucketOf(timeMs: Long): Int {
            val c = Calendar.getInstance()
            c.timeInMillis = timeMs
            return when (c.get(Calendar.HOUR_OF_DAY)) {
                in 5..11 -> 1
                in 12..17 -> 2
                in 18..22 -> 3
                else -> 0
            }
        }

        fun clamp01(v: Double): Double = min(1.0, max(0.0, v))
    }
}
