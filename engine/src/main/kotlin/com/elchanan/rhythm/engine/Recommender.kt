package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.db.ArtistEntity
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
    val lastMood: String = "",
    /**
     * When [lastMood] was chosen, or 0 when that is not known.
     *
     * The margin alone did not hold the shelf. With a couple of dozen songs
     * behind the verdict, ten percent is two songs, and one evening - or a
     * re-analysis that moved a few songs from one mood to the next - was
     * enough to go from "you like happy" to "you like calm" within the hour.
     * A taste does not change in an hour, so for a few days after the shelf
     * says something it keeps saying it, as long as the mood is still there
     * in what the listener plays.
     */
    val lastMoodAt: Long = 0L,
    /**
     * Weights for the signals that predict an unheard song, learned from this
     * listener's own history by [SignalCalibration] - or null for the
     * defaults. The sliders above still multiply them.
     */
    val learned: SignalWeights? = null,
    /** During the Omer and the Three Weeks, generate from vocal songs only. */
    val onlyVocalInSeason: Boolean = false,
    /**
     * From how many minutes a track counts as a medley even when its title
     * does not say so, or 0 for the title alone. A set of several songs is
     * often just called by the first of them, and its length is what gives it
     * away. Off by default: a long niggun is not a medley, and where the line
     * falls is the listener's to draw.
     */
    val medleyMinutes: Int = 0
) {
    companion object {
        /** The lengths the settings offer for [medleyMinutes]; 0 is off. The same on both apps. */
        val MEDLEY_CHOICES = listOf(0, 8, 10, 12, 15, 20, 30)
    }
}

/**
 * How much each of the signals that can speak for a song before it has been
 * heard counts: the artist's rating, listening to the artist's other songs,
 * the style match, the sound match and the mood match.
 *
 * These are the whole of the app's promise - to know what you will like
 * before you have played it - and they were five numbers chosen by hand, the
 * same for everyone. Someone whose taste follows the artist and someone whose
 * taste follows the sound were ranked by one guess about which matters more.
 */
data class SignalWeights(
    val artistRating: Double,
    val artistListening: Double,
    val style: Double,
    val acoustic: Double,
    val mood: Double
) {
    fun toArray() = doubleArrayOf(artistRating, artistListening, style, acoustic, mood)

    fun encode(): String = toArray().joinToString(",") { "%.4f".format(java.util.Locale.ROOT, it) }

    companion object {
        /** The hand-picked defaults, the ones every listener got before. */
        val DEFAULT = SignalWeights(1.35, 1.0, 1.55, 1.15, 0.9)

        val LABELS = listOf("דירוג האמן", "האזנה לאמן", "התאמת סגנון", "התאמת סאונד", "התאמת מצב רוח")

        fun of(v: DoubleArray) = SignalWeights(v[0], v[1], v[2], v[3], v[4])

        fun decode(raw: String): SignalWeights? {
            val parts = raw.split(',').mapNotNull { it.trim().toDoubleOrNull() }
            if (parts.size != 5 || parts.any { !it.isFinite() || it < 0.0 }) return null
            return of(parts.toDoubleArray())
        }
    }
}

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
    private val feedSeed: Long,
    /**
     * Songs that are talking rather than music.
     *
     * The detector found these and nothing here knew it. A shiur was filed
     * correctly on its own shelf and went on behaving like a track everywhere
     * else: in the feed, in a mix, in a radio, in a shuffle. The shelf was the
     * only place the answer was used.
     *
     * Excluded from everything this class generates, and from the taste vector
     * as well - an hour of listening to a lecture is not a statement about
     * what music someone likes. Still reachable by search and still in the
     * library, because being spoken is a reason not to mix something into an
     * evening's listening, not a reason to hide it.
     */
    private val spoken: Set<Long> = emptySet(),
    /**
     * When each song was last actually heard, from the play history.
     *
     * `lastPlayedAt` in the stats is when a song was last touched, and a skip
     * touches it. Read as "heard lately" that turned every skip into an
     * endorsement: skip a song you loved months ago and it became your most
     * recent favourite, anchoring "sounds like" on the very track you had just
     * turned off, and five skips in a row set the session's sound to the one
     * you were running away from. The history table records plays and never
     * skips, so it is the honest source. See [heardAt] for what happens to a
     * song older than the history reaches.
     */
    private val lastHeard: Map<Long, Long> = emptyMap(),
    /**
     * Vocal-only songs, see [Vocal]. Held back from everything generated
     * outside the Omer and the Three Weeks; during them, with
     * [EngineTuning.onlyVocalInSeason], the only thing generated.
     */
    private val vocal: Set<Long> = emptySet()
) {

    /** Whether today is in the Omer or the Three Weeks. */
    val season: JewishSeasons.Season? = JewishSeasons.at(now)

    /**
     * The songs anything generated may draw on.
     *
     * One list, computed once, so a new shelf or mix cannot forget to exclude
     * speech by forgetting to filter - which is exactly how this went wrong
     * the first time.
     */
    private val playable: List<SongEntity> = run {
        val music = if (spoken.isEmpty()) songs else songs.filterNot { it.id in spoken }
        when {
            vocal.isEmpty() -> music
            season == null -> music.filterNot { it.id in vocal }
            // Only when there is enough of it to make a feed from; otherwise
            // a library with three vocal tracks would play those three on repeat.
            tuning.onlyVocalInSeason && music.count { it.id in vocal } >= MIN_VOCAL_TO_REPLACE ->
                music.filter { it.id in vocal }
            else -> music
        }
    }

    /**
     * [playable] as a set, for the questions asked of it per song.
     *
     * Everything that reads the listening to learn a taste reads it through
     * this: the taste vector did, and the sound model's seeds, the session's
     * centre, the familiarity scale and the restlessness did not - so a shiur
     * played daily, kept out of every shelf, still set what "sounds like what
     * you love" meant, what was "playing now", and how well known every song
     * looked beside it.
     */
    private val playableIds: Set<Long> = playable.mapTo(HashSet()) { it.id }

    /**
     * When a song was last heard - played, not skipped - or 0 when unknown.
     *
     * The history is capped, so a song can be missing from it. When it has
     * never been skipped its last touch was a play and `lastPlayedAt` is
     * exact; when it has, that touch may have been the skip, and unknown is
     * the honest answer - it only costs a song the recency boost that a play
     * old enough to have fallen out of the history would not earn anyway.
     */
    private fun heardAt(songId: Long): Long {
        lastHeard[songId]?.let { return it }
        val st = stats[songId] ?: return 0L
        return if (st.skipCount == 0) st.lastPlayedAt else 0L
    }

    /**
     * Never played and never skipped: the only honest meaning of "not heard yet".
     *
     * Plays alone were counted, so a song skipped six times read as undiscovered
     * - it collected the discovery bonus and was offered in the discovery mix
     * as "a song you have not heard yet", to someone who had heard it enough
     * to turn it off six times.
     */
    private fun untouched(songId: Long): Boolean {
        val st = stats[songId] ?: return true
        return st.playCount == 0 && st.skipCount == 0
    }

    /**
     * Whether the song was heard again after it was last skipped.
     *
     * The rule asked for was: an immediate skip counts against a song, unless
     * you listened to it afterwards. The skip count alone cannot say that - it
     * only adds up - so a song skipped five times a year ago and played five
     * times since still carried a fifty per cent skip rate, and a later play
     * diluted a skip rather than answering it.
     *
     * The order is recoverable without storing anything new. `lastPlayedAt`
     * is moved by every touch, a skip included; [heardAt] only by a play. A
     * play writes both with the same timestamp, so when the last touch is no
     * later than the last play, the last thing that happened was a play.
     */
    private fun heardSinceLastSkip(songId: Long): Boolean {
        val st = stats[songId] ?: return false
        if (st.skipCount == 0 || st.playCount == 0) return false
        val heard = heardAt(songId)
        if (heard <= 0L) return false
        return st.lastPlayedAt <= heard + SAME_EVENT_MS
    }

    /**
     * Skips as the ranking counts them: in full, or mostly forgiven when the
     * song was played after them.
     *
     * Mostly rather than entirely. One skip followed by a play is a mood and
     * is almost erased; thirty skips followed by one play are still thirty
     * skips, and pretending otherwise would bring back a song the listener has
     * turned off over and over on the strength of one time they let it run.
     */
    private fun countedSkips(songId: Long): Double {
        val st = stats[songId] ?: return 0.0
        // A skip in a burst - flicking through for something - says little
        // about the song it landed on.
        val burst = st.burstSkips.coerceIn(0, st.skipCount)
        val skips = (st.skipCount - burst) + BURST_SKIP * burst
        return if (heardSinceLastSkip(songId)) skips * SKIP_FORGIVEN else skips
    }

    /**
     * 0.5..1: skips fade as they age. Taste moves, and a song turned off
     * every time a year ago is not the same verdict as one turned off this
     * week. Measured from the latest skip, so skipping it again renews the
     * whole count; never below half, because a song skipped thirty times was
     * not an accident. 1 when the date is unknown - skips from before it was
     * kept count as they always did.
     */
    private fun skipAge(st: SongStatsEntity): Double {
        if (st.lastSkipAt <= 0L) return 1.0
        return 0.5 + 0.5 * exp(-daysSince(st.lastSkipAt) / SKIP_FADE_DAYS)
    }

    private val hourBucket: Int = bucketOf(now)
    private val weekendNow: Boolean = isWeekend(now)
    private val maxPlays: Int = playable.maxOfOrNull { stats[it.id]?.playCount ?: 0 } ?: 0

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
        songs.associate { song -> song.id to stylesOf(song) }
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
    private fun withoutSeparated(input: List<SongEntity>): List<SongEntity> {
        if (separations.isEmpty || input.size < 2) return input
        // Styles kept to themselves first: the larger side of each stays.
        val group = Styles.Separations.keepTogether(input, separations) { declaredStyles[it.id].orEmpty() }
        if (group.size < 2) return group
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
        var plays = 0
        var skips = 0
        for (song in playable) {
            val st = stats[song.id] ?: continue
            plays += st.playCount
            skips += st.skipCount
        }
        val attempts = plays + skips
        if (attempts < 10) 0.0 else (skips.toDouble() / attempts).coerceIn(0.0, 1.0)
    }

    /**
     * The same question asked of the last weeks only: of the songs touched
     * lately, how many were last turned off rather than heard.
     *
     * [restlessness] is every skip since the app was installed, so a month
     * of skipping a year ago widened the feed for good, and a listener whose
     * feed stopped landing this week was drowned out by years of it
     * landing. Still what the skip prior leans on - how often this listener
     * skips anything at all is a lifetime question - but the discovery dial
     * answers to now. Thirty days, then ninety if too little was touched,
     * then the lifetime figure.
     */
    private val recentRestlessness: Double = run {
        for (days in RESTLESS_WINDOWS_DAYS) {
            val since = now - days * 86_400_000L
            var touched = 0
            var skipped = 0
            for (song in playable) {
                val st = stats[song.id] ?: continue
                if (st.lastPlayedAt < since) continue
                touched++
                // The last touch was a skip when the skip is that touch and no
                // play came after it.
                if (st.lastSkipAt > 0L && st.lastSkipAt + SAME_EVENT_MS >= st.lastPlayedAt &&
                    heardAt(song.id) < st.lastSkipAt
                ) skipped++
            }
            if (touched >= RESTLESS_MIN_SONGS) return@run skipped.toDouble() / touched
        }
        restlessness
    }

    /** The user's dial, widened when the recent picks are being skipped. */
    private val effectiveDiscovery: Double =
        (tuning.discovery + 0.5 * recentRestlessness).coerceIn(0.0, 1.0)

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
        // What was heard in the last three quarters of an hour, not what was
        // touched. Skips count as touches, so five skips in a row used to set
        // the session's centre to the sound being skipped, and the feed leaned
        // towards exactly what was being rejected.
        val recent = playable
            .map { it.id to heardAt(it.id) }
            .filter { it.second >= cutoff }
            .sortedByDescending { it.second }
            .take(5)
            .mapNotNull { features[it.first] }
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
        song.id to Names.normalizeKey(
            VERSION_NOISE.replace(song.title, " ") + " " + song.artistKey
        )
    }

    /**
     * Keeps the first take of each piece and drops the rest, order preserved.
     *
     * Keyed on the piece rather than the recording. The recording key carries
     * the artist, so two files of one song credited slightly differently - the
     * ordinary state of a library built from downloads - counted as two songs
     * and both went on the shelf.
     */
    private fun dedupeVersions(list: List<SongEntity>): List<SongEntity> {
        val seen = HashSet<String>()
        return list.filter { song ->
            val key = pieceKeyById[song.id]?.takeIf { it.isNotBlank() }
                ?: versionKeyById[song.id]
                ?: song.id.toString()
            seen.add(key)
        }
    }

    /**
     * What every offered shelf goes through: one take of each piece, and one
     * side of every separation rule.
     *
     * The shelves built by [pick] get both from inside it. These are the ones
     * assembled by sorting a filtered list instead, and each of them had to
     * remember the rules on its own - which is how a shelf of concert
     * recordings ended up being the one place in the feed that still mixed two
     * styles the user had told it never to mix.
     *
     * Order is preserved and the first tagged song decides the side, so the
     * shelf keeps whatever ranking its caller chose.
     *
     * Not used on the shelves that report rather than recommend - most played,
     * what you rated, what was added to the device. There the app is not
     * choosing to put two styles together, it is showing what is there, and
     * quietly hiding half of it would be its own kind of wrong.
     */
    private fun offered(list: List<SongEntity>): List<SongEntity> = oneSide(dedupeVersions(list))

    /**
     * Drops whatever clashes with the side of the rule this list has landed on.
     *
     * Separate from [offered] because one shelf needs this half without the
     * other: the covers shelf exists to show two takes of a piece, so deduping
     * it by piece would empty it.
     */
    private fun oneSide(list: List<SongEntity>): List<SongEntity> {
        if (separations.isEmpty) return list
        val kept = ArrayList<SongEntity>(list.size)
        for (song in list) {
            val mine = declaredStyles[song.id].orEmpty()
            if (kept.any { separations.clash(declaredStyles[it.id].orEmpty(), mine) }) continue
            kept.add(song)
        }
        return kept
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

    /**
     * What a song's listening says about everything around it - its artist,
     * its style, its mood - rather than about the song itself.
     *
     * Asymmetric on purpose. A like is a statement about the kind of music; a
     * skip or a thumbs down is mostly a statement about this one track - the
     * wrong moment, a weak recording, the one song by a loved singer that does
     * not land. Carried over at full strength, three skips of one song were
     * enough to sink its whole artist and every song that shares its style.
     * The song itself keeps its full penalty; its neighbours get [NEGATIVE_SPILL]
     * of it.
     */
    private val spill: Map<Long, Double> = behaviour.mapValues { (_, w) -> if (w < 0.0) w * NEGATIVE_SPILL else w }

    /** The signal weights in force: learned for this listener, or the defaults. */
    private val weights: SignalWeights = tuning.learned ?: SignalWeights.DEFAULT

    /**
     * A medley, by its title or - when the listener set a length - by being
     * at least that long. Kept out of everything generated, as [isMedley] is;
     * the shelves that only list what is there - recently added, most played
     * - show a medley by length exactly as they show one by title.
     */
    private fun medley(song: SongEntity): Boolean =
        isMedley(song.title) ||
            (tuning.medleyMinutes > 0 && song.durationMs >= tuning.medleyMinutes * 60_000L)

    /** The taste vector before normalising, kept so one song can be taken out of it. */
    private val tasteRaw: Map<String, Double> = buildTasteVector()

    private val taste: Map<String, Double> = run {
        val norm = sqrt(tasteRaw.values.sumOf { it * it })
        if (norm < 1e-9) emptyMap() else tasteRaw.mapValues { it.value / norm }
    }

    /**
     * What the listening says about each artist: the sum of what it says
     * about each of their songs, skips taken off.
     *
     * The second rule asked for: hear an artist and more of them should come
     * up. Nothing did that. An artist reached a song's score only through a
     * rating typed by hand; listening moved the song listened to, and reached
     * the rest of that artist's work only if they happened to sound alike or
     * had been given a style tag. Fifty plays of an untagged singer with a
     * varied catalogue did almost nothing for his other songs.
     *
     * Read per song through [artistListeningTerm], which leaves the song itself
     * out - its own plays already count in its own score - and saturates, so
     * the hundredth play of an artist adds far less than the tenth. That is
     * what keeps this from becoming "one artist and nothing else", which the
     * per-artist caps on every shelf then enforce outright.
     */
    private val artistListening: Map<String, Double> = run {
        val out = HashMap<String, Double>()
        for (song in playable) {
            val w = spill[song.id] ?: continue
            if (w == 0.0) continue
            out.merge(song.artistKey, w) { a, b -> a + b }
        }
        out
    }

    /**
     * -1..1: how much the listening to this song's artist - their other songs,
     * not this one - speaks for it. Negative for an artist who is mostly
     * skipped.
     */
    private fun artistListeningTerm(song: SongEntity): Double {
        val total = artistListening[song.artistKey] ?: return 0.0
        val others = total - (spill[song.id] ?: 0.0)
        if (abs(others) < 1e-9) return 0.0
        return kotlin.math.tanh(others / ARTIST_LISTEN_SCALE)
    }

    /** The mood reading of the analysed library, built once per snapshot. */
    private val moodModel: MoodModel = MoodModel(features.values, MoodMarks.of(stats), acoustic?.musicPrints)

    /** Which moods each analysed song expresses, worked out once. */
    private val moodsOf: Map<Long, List<Mood>> =
        if (!moodModel.ready) emptyMap() else playable.mapNotNull { song ->
            val f = features[song.id] ?: return@mapNotNull null
            val moods = Mood.entries.filter { moodModel.matches(it, f) }
            if (moods.isEmpty()) null else song.id to moods
        }.toMap()

    /**
     * Per mood, the total endorsement of the songs in it that the listening has
     * touched, and how many of them there are.
     *
     * An average rather than a sum, and against the listener's own average,
     * which is the whole difficulty here. A sum would say "you like
     * ENERGETIC" to anyone whose library is mostly energetic and who presses
     * shuffle - they hear more of it because there is more of it, not because
     * they prefer it. What says something is whether songs in a mood fare
     * better or worse than this listener's songs do in general: played to the
     * end where others are skipped, liked where others are not.
     */
    private val moodTotals: Map<Mood, Pair<Double, Int>> = run {
        val out = HashMap<Mood, Pair<Double, Int>>()
        for ((id, moods) in moodsOf) {
            val w = spill[id] ?: continue
            if (w == 0.0) continue
            for (mood in moods) {
                val (sum, n) = out[mood] ?: (0.0 to 0)
                out[mood] = (sum + w) to (n + 1)
            }
        }
        out
    }

    /** The average endorsement across every analysed song the listening touched. */
    private val touchedAverage: Double = run {
        val touched = moodsOf.keys.mapNotNull { id -> spill[id]?.takeIf { it != 0.0 } }
        if (touched.isEmpty()) 0.0 else touched.average()
    }

    /**
     * -1..1: how the listening treats this mood, with the song itself left out.
     *
     * Shrunk towards nothing while there are few songs to go on - two songs
     * played to the end are not yet a taste for a mood - and saturating, so
     * that a mood can lift a song but not bury everything else.
     */
    private fun moodPreference(mood: Mood, songId: Long): Double {
        val (sum, n) = moodTotals[mood] ?: return 0.0
        val own = spill[songId]?.takeIf { it != 0.0 && songId in moodsOf }
        val othersSum = if (own != null) sum - own else sum
        val othersN = if (own != null) n - 1 else n
        if (othersN <= 0) return 0.0
        val lift = othersSum / othersN - touchedAverage
        val confidence = othersN / (othersN + MOOD_PRIOR_SONGS)
        return kotlin.math.tanh(lift * confidence / MOOD_LIFT_SCALE)
    }

    /**
     * -1..1: how well this song's moods sit with the moods the listening
     * prefers. The average over the moods it expresses, so a track that is
     * both calm and dark answers to both.
     *
     * The mood used to reach the feed through one shelf only - "it looks like
     * you like X" - and only its single leading mood. Everywhere else it was
     * present at most by accident, through the sound model, which measures
     * resemblance to particular tracks and not a taste for a kind of feeling.
     */
    private fun moodTerm(song: SongEntity): Double {
        val moods = moodsOf[song.id] ?: return 0.0
        return moods.map { moodPreference(it, song.id) }.average()
    }

    /** the songs the acoustic kNN measures against */
    private val acousticPositives: List<Long>
    private val acousticNegatives: List<Long>
    private val acousticFitById: Map<Long, Double>

    private val baseScores: Map<Long, Double>

    init {
        val space = acoustic
        val listenedFor = behaviour.entries
            .filter { it.value > 0.35 && it.key in playableIds && space?.has(it.key) == true }
            .sortedByDescending { it.value }
            .take(60)
            .map { it.key }
        val listenedAgainst = behaviour.entries
            .filter { it.value < -0.35 && it.key in playableIds && space?.has(it.key) == true }
            .sortedBy { it.value }
            .take(30)
            .map { it.key }
        // Listening is the evidence about sound; an artist rating only fills
        // in where there is too little of it, and then a few tracks per artist
        // so that no single rating can become the whole neighbourhood.
        acousticPositives = listenedFor + artistSeeds(
            loved = true, room = ARTIST_SEED_FLOOR - listenedFor.size, taken = listenedFor.toSet()
        )
        acousticNegatives = listenedAgainst + artistSeeds(
            loved = false, room = ARTIST_SEED_FLOOR / 2 - listenedAgainst.size,
            taken = listenedAgainst.toSet()
        )

        acousticFitById = if (space == null || acousticPositives.isEmpty()) {
            emptyMap()
        } else {
            songs.associate { song -> song.id to acousticFit(space, song.id, acousticPositives, acousticNegatives) }
        }

        baseScores = songs.associate { it.id to computeBase(it) }
    }

    private fun acousticFit(space: AcousticSpace, songId: Long, positives: List<Long>, negatives: List<Long>): Double {
        if (positives.isEmpty()) return 0.0
        val positive = space.similarityToSet(songId, positives, 3)
        val negative = if (negatives.isEmpty()) 0.0 else space.similarityToSet(songId, negatives, 2)
        // The songs pushed away count for less than the songs drawn in, for
        // the reason [spill] gives: a rejected track says less about its
        // sound than a loved one does.
        return (2.0 * positive - 1.0 - NEGATIVE_SPILL * negative).coerceIn(-1.5, 1.0)
    }

    /**
     * Songs by rated artists, to seed the sound model when listening is thin.
     *
     * Someone who rates artists and has barely listened yet would otherwise
     * have no acoustic neighbourhood at all. At most [SEEDS_PER_ARTIST] per
     * artist, taken round the artists in rating order, so a rating counts once
     * per artist however many files that artist has.
     */
    private fun artistSeeds(loved: Boolean, room: Int, taken: Set<Long>): List<Long> {
        val space = acoustic ?: return emptyList()
        if (room <= 0) return emptyList()
        val rated = artists.values
            .filter { if (loved) it.rating >= 4 else it.rating in 1..2 }
            .sortedWith(
                if (loved) compareByDescending<ArtistEntity> { it.rating }.thenBy { it.artistKey }
                else compareBy<ArtistEntity> { it.rating }.thenBy { it.artistKey }
            )
        if (rated.isEmpty()) return emptyList()
        val byArtist = playable
            .filter { space.has(it.id) && it.id !in taken }
            .groupBy { it.artistKey }
            .mapValues { (_, list) -> list.sortedBy { it.id }.take(SEEDS_PER_ARTIST) }
        val out = ArrayList<Long>(room)
        for (round in 0 until SEEDS_PER_ARTIST) {
            for (artist in rated) {
                if (out.size >= room) return out
                byArtist[artist.artistKey]?.getOrNull(round)?.let { out.add(it.id) }
            }
        }
        return out
    }

    // -----------------------------------------------------------------------
    // Tokens and taste
    // -----------------------------------------------------------------------

    private fun tokensFor(song: SongEntity): List<String> {
        val out = ArrayList<String>(8)
        out.addAll(stylesOf(song))

        genreOf(song)?.let { out.add(it) }
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

    /**
     * The genre a song counts under: the one the user set, else the file's -
     * and neither when it is a placeholder.
     *
     * The one the user set was stored, described as replacing the file's,
     * and never read: only the file's genre reached the recommendations. And
     * a file's genre is whoever tagged it's opinion - "Other", "Unknown",
     * an ID3 number, the site it came from - which went into the taste like
     * a style the user had typed, and could name a mix: "מיקס other".
     */
    private fun genreOf(song: SongEntity): String? {
        val own = stats[song.id]?.genre?.trim().orEmpty()
        val genre = own.ifEmpty { song.genre?.trim().orEmpty() }
        if (genre.isEmpty()) return null
        val key = genre.lowercase(Locale.ROOT)
        if (key in PLACEHOLDER_GENRES || NUMBERED_GENRE.matches(key)) return null
        return genre
    }

    /**
     * Genre words that came only from files, never from a style, and sit on
     * more than half the library: "Jewish" on a Jewish library says nothing
     * about a kind of music within it, and is not a name for a mix.
     */
    private val broadGenres: Set<String> by lazy {
        val styleWords = songs.flatMapTo(HashSet()) { s -> stylesOf(s).map { it.lowercase(Locale.ROOT) } }
        songs.mapNotNull { genreOf(it)?.lowercase(Locale.ROOT) }
            .groupingBy { it }.eachCount()
            .filter { (word, count) -> word !in styleWords && count * 2 > songs.size }
            .keys
    }

    /**
     * The style words in force for a song: its own, or its artist's, or both.
     *
     * A tag the user typed on a song replaces the artist's, because saying
     * this one is different is the whole point of typing it. A tag the learner
     * wrote adds to them instead: it is only ever allowed to name something
     * the artist tag left unanswered - a character where the artist gave a
     * genre - so replacing would throw away what the user actually said in
     * order to keep a guess.
     */
    fun stylesInForce(song: SongEntity): List<String> = stylesOf(song)

    private fun stylesOf(song: SongEntity): List<String> {
        val own = stats[song.id]
        val songStyles = Styles.parse(own?.styles.orEmpty())
        val artistStyles = Styles.parse(artists[song.artistKey]?.styles.orEmpty())
        return when {
            songStyles.isEmpty() -> artistStyles
            own?.stylesAuto == 1 -> (artistStyles + songStyles).distinct()
            else -> songStyles
        }
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
    /**
     * What the user's own behaviour says about one song: plays, likes, the
     * song's own rating, skips.
     *
     * Deliberately nothing about the artist. An artist rating was once added
     * here, per song, and that multiplied one statement by however many files
     * the artist happened to have: rate a singer with a hundred downloads five
     * stars and you had said something a hundred times, loudly enough to
     * outvote a hundred and twenty real plays of somebody else. The taste
     * vector swung to the rated artist's style, the sound model moved to his
     * sound, and "sounds like this" anchored on a track never once played.
     * Artist ratings reach those models once per artist instead - see
     * [buildTasteVector] and [artistSeeds].
     */
    private fun behaviourWeight(songId: Long): Double {
        var w = 0.0
        val st = stats[songId] ?: return w
        if (st.playCount > 0) {
            // Recency of the last play, not the last touch - see [heardAt].
            val heard = heardAt(songId)
            val recency = if (heard == 0L) 0.0 else exp(-daysSince(heard) / 45.0)
            w += ln(1.0 + st.playCount) * (0.55 + 0.45 * recency) * spread(st)
        }
        w += when (st.liked) {
            1 -> 2.0
            -1 -> -2.6
            else -> 0.0
        }
        if (st.rating > 0) w += (st.rating - 3) * 0.8
        val skips = countedSkips(songId)
        val attempts = st.playCount + skips
        if (attempts > 0.0) w -= 0.7 * (skips / attempts) * ln(1.0 + skips) * skipAge(st)
        return w
    }

    /**
     * 0.7..1.15: whether the plays were spread over many days or packed into
     * a few. Settled taste is coming back to a song on day after day; a burst
     * of repeats in one evening is a mood, and should not swing the whole
     * profile the way months of listening do. 1 where the days are unknown -
     * plays imported from another player, or older than the counter.
     */
    private fun spread(st: SongStatsEntity): Double {
        if (st.playDays <= 0 || st.playCount <= 1) return 1.0
        val days = st.playDays.coerceAtMost(st.playCount)
        val ratio = ln(1.0 + days) / ln(1.0 + st.playCount)
        return 0.7 + 0.45 * ratio
    }

    /** How well known a song is, relative to the most played one, weighted by [spread]. */
    private fun familiarity(st: SongStatsEntity?): Double {
        if (maxPlays <= 0) return 0.0
        val plays = st?.playCount ?: 0
        val base = 0.55 * (ln(1.0 + plays) / ln(1.0 + maxPlays))
        return if (st == null) base else base * spread(st)
    }

    private fun buildTasteVector(): Map<String, Double> {
        val acc = HashMap<String, Double>()

        // (a) explicit artist ratings - useful from the very first minute.
        // Once per artist, whatever the number of files: a rating is one
        // statement, and letting it scale with a download count is how a
        // singer nobody had played came to outweigh one played a hundred
        // times.
        val songsOfArtist = playable.groupBy { it.artistKey }
        for (artist in artists.values) {
            if (artist.rating == 0) continue
            val w = (artist.rating - 3) * 1.1
            if (w == 0.0) continue
            val styles = Styles.parse(artist.styles)
            // Through the same weighting the songs get, or the two sides of
            // the cosine would be measuring on different scales.
            val direction: Map<String, Double> = if (styles.isNotEmpty()) {
                unitVector(styles.map { it.lowercase(Locale.ROOT) })
            } else {
                // No words from the user, so the artist's own measured tokens
                // stand in: the average of their songs, which is one vector
                // of length at most one however many songs went into it.
                val theirs = songsOfArtist[artist.artistKey].orEmpty()
                if (theirs.isEmpty()) continue
                val sum = HashMap<String, Double>()
                for (song in theirs) {
                    for ((k, v) in unitVector(tokensBySong[song.id].orEmpty())) {
                        sum[k] = (sum[k] ?: 0.0) + v
                    }
                }
                sum.mapValues { it.value / theirs.size }
            }
            for ((k, value) in direction) {
                acc[k] = (acc[k] ?: 0.0) + w * value
            }
        }

        // (b) behaviour, including per song ratings - dislikes carried over
        // at their reduced weight, see [spill]
        for (song in playable) {
            val w = spill[song.id] ?: 0.0
            if (abs(w) < 1e-6) continue
            for ((t, value) in unitVector(tokensBySong[song.id].orEmpty())) {
                acc[t] = (acc[t] ?: 0.0) + w * value
            }
        }

        return acc
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

    /**
     * How much this song belongs to the kind of day it is now.
     *
     * The same shape as [timeFit] and deliberately weaker. Two buckets means
     * a strong claim can be made from few plays, which is exactly when a
     * strong claim is least warranted, so the ceiling is lower and the floor
     * shallower. Silent until a song has been heard enough times for the
     * split to mean anything.
     */
    private fun dayFit(st: SongStatsEntity?): Double {
        if (st == null) return 0.0
        val total = st.dWeekend + st.dWeekday
        if (total < 4) return 0.0
        val here = if (weekendNow) st.dWeekend else st.dWeekday
        val share = here.toDouble() / total
        return ((share - 0.5) / 0.5).coerceIn(-0.3, 1.0)
    }

    private fun ratingTerm(song: SongEntity): Double {
        val songRating = stats[song.id]?.rating ?: 0
        val artistRating = artists[song.artistKey]?.rating ?: 0
        return when {
            // a rating on the song itself is the most specific thing the user
            // ever says, so it outranks the artist level rating
            songRating > 0 -> 1.9 * ((songRating - 3) / 2.0) +
                if (artistRating > 0) {
                    0.45 * (weights.artistRating / SignalWeights.DEFAULT.artistRating) *
                        ((artistRating - 3) / 2.0)
                } else 0.0
            artistRating > 0 -> weights.artistRating * ((artistRating - 3) / 2.0)
            else -> -0.06
        }
    }

    /**
     * The style match with this song's own listening taken out of the taste
     * vector. Without that, a song played a hundred times matches the taste it
     * built - which says nothing about whether the taste predicts it.
     */
    private fun styleFitWithout(songId: Long): Double {
        val v = unitVector(tokensBySong[songId].orEmpty())
        if (v.isEmpty()) return 0.0
        val own = spill[songId] ?: 0.0
        val adjusted = HashMap(tasteRaw)
        if (abs(own) > 1e-9 && songId in playableIds) {
            for ((k, value) in v) adjusted[k] = (adjusted[k] ?: 0.0) - own * value
        }
        val norm = sqrt(adjusted.values.sumOf { it * it })
        if (norm < 1e-9) return 0.0
        var dot = 0.0
        for ((k, value) in v) dot += value * ((adjusted[k] ?: 0.0) / norm)
        return dot.coerceIn(-1.0, 1.0)
    }

    /**
     * Tracks the model heard as more talking than music, kept out of the
     * mood lists even when they were not filed as spoken word - a lecture
     * is not "energetic" however fast the speaker. See [Spoken.speechAhead].
     */
    private val speechAhead: Set<Long> by lazy {
        playable.filterTo(HashSet()) { Spoken.speechAhead(features[it.id]) }.mapTo(HashSet()) { it.id }
    }

    /**
     * What the engine can say about a song without its own history: the five
     * signals of [SignalWeights], each read with the song itself left out.
     *
     * The honest test of the whole app. A song's own plays, likes and skips
     * trivially predict its own label; these five are what speak for a song
     * nobody has played yet.
     */
    fun blindSignals(song: SongEntity): DoubleArray {
        val artistRating = artists[song.artistKey]?.rating ?: 0
        return doubleArrayOf(
            if (artistRating > 0) (artistRating - 3) / 2.0 else 0.0,
            artistListeningTerm(song),
            styleFitWithout(song.id),
            // similarityToSet already leaves the song out of its own neighbourhood
            acousticFitById[song.id] ?: 0.0,
            moodTerm(song)
        )
    }

    /**
     * Songs the listening has clearly answered for: true for loved, false for
     * rejected, absent when it has not said enough.
     */
    fun verdict(songId: Long): Boolean? {
        val st = stats[songId] ?: return null
        if (st.liked == 1 || st.rating >= 4) return true
        if (st.liked == -1 || st.rating in 1..2) return false
        val attempts = st.playCount + st.skipCount
        if (attempts < 2) return null
        val skipRate = st.skipCount.toDouble() / attempts
        val finished = if (st.playCount > 0) st.completeCount.toDouble() / st.playCount else 0.0
        // Plays imported from another player carry no "finished" count, so a
        // song played thirty times there read as never finished and was never
        // counted as loved. Three plays that were not skipped say enough alone.
        return when {
            skipRate >= 0.6 -> false
            st.playCount >= 3 && skipRate <= 0.34 -> true
            st.playCount >= 2 && skipRate <= 0.34 && finished >= 0.6 -> true
            else -> null
        }
    }

    /** Every answered song with its blind signals, for [SignalCalibration]. */
    fun calibrationRows(): List<SignalCalibration.Row> = playable.mapNotNull { song ->
        val label = verdict(song.id) ?: return@mapNotNull null
        SignalCalibration.Row(song.artistKey, blindSignals(song), label)
    }

    /** The skip penalty, or null when the song has never been tried. */
    private fun skipTerm(song: SongEntity): Double? {
        val st = stats[song.id] ?: return null
        val skips = countedSkips(song.id)
        val attempts = st.playCount + skips
        if (attempts <= 0.0) return null
        // Smoothed rather than taken raw. One play and two skips is a ratio of
        // 0.67 on three observations, and charging the full penalty for that
        // condemned songs on evidence far too thin to carry it. The prior it
        // is pulled towards is how often this listener skips anything at all,
        // so a library that is skipped through constantly does not read every
        // song in it as bad.
        val rate = (skips + SKIP_PRIOR * restlessness) / (attempts + SKIP_PRIOR)
        // The size of the penalty fades, not the rate: a song only ever
        // skipped has a rate of one however old the skips are.
        return -1.25 * rate * skipAge(st)
    }

    /**
     * How much of the track actually gets heard, or null when there is too
     * little to say.
     *
     * A skip count alone is blunt: it cannot tell a song abandoned after four
     * seconds from one left at the last chorus. This can, and it now speaks
     * from the very first skip. It used to wait for two attempts, so a single
     * skip at four seconds and a single skip at seventy per cent cost exactly
     * the same - and only the first of those is a listener saying no. When
     * every attempt so far is a skip the average here is exact: it is simply
     * how far into the song the skips got.
     */
    private fun heardFraction(song: SongEntity): Double? {
        val st = stats[song.id] ?: return null
        if (song.durationMs <= 0) return null
        val attempts = st.playCount + st.skipCount
        val onlySkips = st.playCount == 0 && st.skipCount >= 1
        if (attempts < 2 && !onlySkips) return null
        val expected = song.durationMs.toDouble() * attempts
        return ((st.listenedMs).toDouble() / expected).coerceIn(0.0, 1.0)
    }

    private fun computeBase(song: SongEntity): Double {
        val st = stats[song.id]
        var score = 0.0

        score += tuning.artistWeight * ratingTerm(song)
        score += weights.artistListening * tuning.artistWeight * artistListeningTerm(song)
        score += weights.style * tuning.styleWeight * styleFit(song.id)
        score += weights.acoustic * tuning.acousticWeight * (acousticFitById[song.id] ?: 0.0)
        score += weights.mood * tuning.acousticWeight * moodTerm(song)

        when (st?.liked ?: 0) {
            1 -> score += 1.5
            -1 -> score -= 7.0
        }

        val plays = st?.playCount ?: 0
        if (maxPlays > 0) score += familiarity(st)

        skipTerm(song)?.let { score += it }

        if (plays >= 2) {
            val finished = (st?.completeCount ?: 0).toDouble() / plays
            score += 0.9 * (finished - 0.5)
        }
        heardFraction(song)?.let { score += 0.7 * (it - 0.5) }

        score += 0.75 * timeFit(st)
        score += 0.45 * dayFit(st)
        score += 0.6 * sessionFit(song.id)
        score -= 2.6 * tuning.repeatGuard * exp(-hoursSince(st?.lastPlayedAt ?: 0L) / 9.0)
        score += 0.35 * exp(-daysSince(song.dateAddedSec * 1000L) / 21.0)
        if (untouched(song.id)) score += 1.1 * effectiveDiscovery

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
        val listening = artistListeningTerm(song)
        if (listening != 0.0) {
            out.add(
                ScoreTerm(
                    "האזנה לאמן",
                    weights.artistListening * tuning.artistWeight * listening,
                    if (listening > 0) "אתה שומע שירים אחרים של ${song.artistName}"
                    else "אתה מדלג על שירים אחרים של ${song.artistName}"
                )
            )
        }
        out.add(
            ScoreTerm(
                "התאמת סגנון",
                weights.style * tuning.styleWeight * styleFit(song.id),
                tokensBySong[song.id].orEmpty()
                    .filterNot { it.startsWith("decade:") || it.startsWith("len:") }
                    .joinToString(", ").ifEmpty { "אין תגיות" }
            )
        )
        val acousticFit = acousticFitById[song.id]
        out.add(
            ScoreTerm(
                "התאמת סאונד",
                weights.acoustic * tuning.acousticWeight * (acousticFit ?: 0.0),
                features[song.id]?.let { f ->
                    "${f.bpm.toInt()} BPM · ${Features.keyLabel(f.musicalKey, f.mode)}"
                } ?: "השיר עוד לא נותח"
            )
        )
        val mood = moodTerm(song)
        if (mood != 0.0) {
            val labels = moodsOf[song.id].orEmpty().joinToString(", ") { it.label }
            out.add(
                ScoreTerm(
                    "התאמת מצב רוח",
                    weights.mood * tuning.acousticWeight * mood,
                    if (mood > 0) "$labels — מצבי רוח שאתה שומע יותר"
                    else "$labels — מצבי רוח שאתה מדלג עליהם יותר"
                )
            )
        }
        when (st?.liked ?: 0) {
            1 -> out.add(ScoreTerm("לייק", 1.5, "סימנת לייק"))
            -1 -> out.add(ScoreTerm("דיסלייק", -7.0, "סימנת דיסלייק"))
        }
        val plays = st?.playCount ?: 0
        if (maxPlays > 0) {
            out.add(
                ScoreTerm(
                    "היכרות",
                    familiarity(st),
                    if ((st?.playDays ?: 0) > 0) "$plays השמעות ב-${st?.playDays} ימים שונים" else "$plays השמעות"
                )
            )
        }
        val skips = st?.skipCount ?: 0
        val attempts = plays + skips
        skipTerm(song)?.let { value ->
            out.add(
                ScoreTerm(
                    "דילוגים", value,
                    if (heardSinceLastSkip(song.id)) {
                        "$skips דילוגים מתוך $attempts · שמעת אותו אחרי הדילוג, אז הם נספרים בחלקם"
                    } else {
                        "$skips דילוגים מתוך $attempts"
                    }
                )
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
        heardFraction(song)?.let { heard ->
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
        val day = dayFit(st)
        if (day != 0.0) {
            out.add(
                ScoreTerm(
                    "התאמה ליום",
                    0.45 * day,
                    if (weekendNow) "שישי-שבת" else "אמצע השבוע"
                )
            )
        }
        val hours = hoursSince(st?.lastPlayedAt ?: 0L)
        out.add(
            ScoreTerm(
                "מניעת חזרתיות",
                -2.6 * tuning.repeatGuard * exp(-hours / 9.0),
                if (hours > 100_000) "לא הושמע לאחרונה" else "הושמע לפני ${hours.toInt()} שעות"
            )
        )
        if (untouched(song.id)) {
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
        /**
         * For a shelf that promises "like this one": first keep only this many
         * candidates, the closest by [extra] alone, and only then rank those
         * by everything else.
         *
         * Added straight to the listener's score, the closeness was a nudge
         * on a ranking the score decided - its whole spread was a third of the
         * score's - so "sounds like X" and a song's radio came out as the
         * listener's favourites with a lean towards X. Measured on a library
         * with known genres, a radio's songs by other artists were from the
         * seed's genre little more often than chance. Choosing the
         * neighbourhood first and letting taste choose within it keeps both
         * promises: related, and still what this listener likes.
         */
        nearest: Int = 0,
        extra: ((SongEntity) -> Double)? = null
    ): List<SongEntity> {
        if (candidates.isEmpty() || count <= 0) return emptyList()
        val jitter = 0.22 + 0.85 * effectiveDiscovery
        val pool = if (nearest <= 0 || extra == null || candidates.size <= nearest) candidates else
            candidates.map { it to extra(it) }.sortedByDescending { it.second }.take(nearest).map { it.first }
        val ranked = pool.map { song ->
            var s = baseScores[song.id] ?: 0.0
            if (extra != null) s += extra(song)
            s += noise(song.id, salt + feedSeed, jitter)
            song to s
        }.sortedByDescending { it.second }

        val artistCount = HashMap<String, Int>()
        val albumCount = HashMap<Long, Int>()
        // One take of a piece per shelf. Offering the studio cut, the live take
        // and the remix as three separate recommendations is the single most
        // obvious way a library of downloads looks broken. Keyed on the piece
        // rather than the recording, so somebody else's cover of a song already
        // on the shelf does not count as a second offer either.
        val piecesUsed = HashSet<String>()
        val taken = HashSet<Long>()
        val out = ArrayList<SongEntity>(count)

        // Two passes over the same ranking. The second widens the per artist
        // and per album caps, because a shelf short of its target is better
        // off with a third track by someone than left half empty.
        //
        // Nothing else widens, and that is the whole point of the rewrite.
        // What stood here filled the shortfall by walking the ranking again
        // with every rule switched off - so the moment a pool was smaller than
        // the shelf asked for, the shelf silently became "the top scoring
        // songs, caps and duplicates and separations be damned". That is not a
        // degraded shelf, it is a different shelf, and it is what put thirteen
        // tracks by one singer, both copies of the same song and two styles the
        // user had said never to mix into a single row.
        for (round in 0..1) {
            val artistCap = if (round == 0) maxPerArtist else maxPerArtist * 3
            val albumCap = if (round == 0) maxPerAlbum else maxPerAlbum * 3
            for ((song, _) in ranked) {
                if (out.size >= count) return out
                if (song.id in taken) continue
                if (artistCount.getOrDefault(song.artistKey, 0) >= artistCap) continue
                if (albumCount.getOrDefault(song.albumId, 0) >= albumCap) continue
                val piece = pieceKeyById[song.id]?.takeIf { it.isNotBlank() }
                    ?: versionKeyById[song.id]
                if (piece != null && piece in piecesUsed) continue
                // Against every song already on the shelf, not against a side
                // the first tagged one pinned. Against a side, an untagged song
                // that got in before the first tagged one was never checked -
                // harmless for a rule between two styles, and exactly wrong for
                // a style that is to mix only with itself.
                if (!separations.isEmpty) {
                    val mine = declaredStyles[song.id].orEmpty()
                    if (out.any { separations.clash(declaredStyles[it.id].orEmpty(), mine) }) continue
                }
                if (piece != null) piecesUsed.add(piece)
                artistCount[song.artistKey] = artistCount.getOrDefault(song.artistKey, 0) + 1
                albumCount[song.albumId] = albumCount.getOrDefault(song.albumId, 0) + 1
                taken.add(song.id)
                out.add(song)
            }
        }
        return out
    }

    /** The first [cap] songs of each artist, order kept. */
    private fun capPerArtist(list: List<SongEntity>, cap: Int): List<SongEntity> {
        val count = HashMap<String, Int>()
        return list.filter { song ->
            val n = count.getOrDefault(song.artistKey, 0)
            if (n >= cap) false else { count[song.artistKey] = n + 1; true }
        }
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
        // Not the seed in another costume either. The most acoustically similar
        // song to anything is its own second copy or its live take, so a radio
        // with only the seed's id excluded opened, reliably, on the same song
        // again - which is exactly the duplicate that was reported.
        val pool = playable.filter {
            it.id != seed.id && (stats[it.id]?.liked ?: 0) != -1 && !medley(it) &&
                !separated(seed.id, it.id) && !samePiece(seed.id, it.id)
        }
        val chosen = pick(
            candidates = pool,
            count = size,
            salt = seed.id,
            maxPerArtist = 4,
            maxPerAlbum = 3,
            nearest = size * NEAREST_FACTOR
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
        // What was just heard, as pieces rather than ids: another copy of the
        // track that just ended is the nearest thing to it by every measure
        // below, and queued next it is the same song twice in a row.
        // The queue itself counts too: a copy of something already waiting in
        // it is the same song twice, only further apart.
        val heardPieces = (seedIds + exclude)
            .mapNotNullTo(HashSet()) { id -> pieceKeyById[id]?.takeIf { it.isNotBlank() } }
        val pool = playable.filter {
            it.id !in exclude && (stats[it.id]?.liked ?: 0) != -1 && !medley(it) &&
                pieceKeyById[it.id] !in heardPieces &&
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
        val notDisliked = playable.filter {
            (stats[it.id]?.liked ?: 0) != -1 && !medley(it)
        }

        // Speed dial: the handful of tracks actually returned to, first on the
        // page. Held back until there is real listening behind it - a "most
        // played" shelf built from one play each is just a shuffle.
        //
        // "What you return to" is a matter of days, not of plays: a song put
        // on repeat for one evening was ranked here beside one heard on
        // thirty different days, on the same thirty-nine plays. And a song
        // skipped more than it is heard is not something anyone returns to,
        // so it has to be vouched for by the listening as well.
        val mostPlayed = dedupeVersions(
            notDisliked
                .filter { (stats[it.id]?.playCount ?: 0) >= 3 && (behaviour[it.id] ?: 0.0) > 0.35 }
                .sortedWith(
                    compareByDescending<SongEntity> { stats[it.id]?.playDays ?: 0 }
                        .thenByDescending { stats[it.id]?.playCount ?: 0 }
                )
        ).take(12)
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

        val unheard = notDisliked.filter { untouched(it.id) }
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

        val liked = playable.filter { (stats[it.id]?.liked ?: 0) == 1 && !medley(it) }
        if (liked.size >= 4) {
            // The liked half went straight in, untouched by any rule: two
            // liked takes of one song both appeared, and liked songs from two
            // styles the user keeps apart sat in the same mix. And the "and
            // their surroundings" half was picked on its own, so it could land
            // on the opposite side of a separation from the liked half it was
            // meant to surround.
            // A share of them, a few per singer. All of them went in, and the
            // list was cut at fifty afterwards - so past fifty likes the
            // surroundings the title promises were cut off entirely, and one
            // singer's fifteen liked songs could be most of the mix.
            val kept = offered(liked.shuffled(Random(feedSeed)))
                .let { capPerArtist(it, LIKED_PER_ARTIST) }
                .take(LIKED_IN_MIX)
            val keptIds = kept.map { it.id }
            val keptSet = keptIds.toSet()
            val side = kept.flatMap { declaredStyles[it.id].orEmpty() }.distinct()
            // The surroundings are what was not liked: the likes left out
            // above would otherwise outscore everything and take their place.
            val likedSet = liked.mapTo(HashSet()) { it.id }
            val pool = notDisliked.filter { c ->
                c.id !in keptSet && c.id !in likedSet &&
                    kept.none { samePiece(it.id, c.id) } &&
                    !separations.clash(side, declaredStyles[c.id].orEmpty())
            }
            val expanded = pick(pool, LIKED_MIX_SIZE - kept.size, salt = 41L, maxPerArtist = 3) { c ->
                1.9 * affinityTo(keptIds.take(12), c.id)
            }
            // Woven together rather than the liked half and then the rest.
            val together = kept + expanded
            mixes.add(
                Mix(
                    id = "mix:liked",
                    title = "על בסיס האהובים",
                    subtitle = "מהשירים שסימנת בלייק והסביבה שלהם",
                    songs = if (together.isEmpty()) together else sequence(together.first(), together.drop(1))
                )
            )
        }

        // Songs by an artist the user rated highly and has not rated one by one.
        //
        // The gap the whole complaint sits in. The feed had a shelf for songs
        // rated four and up and a shelf for songs never touched at all, and
        // nothing in between - so someone who works by rating artists rather
        // than tracks saw their ratings reflected nowhere, and concluded the
        // rating did nothing. It very nearly didn't.
        val lovedArtists = artists.values
            .filter { it.rating >= 4 }
            .mapTo(HashSet()) { it.artistKey }
        if (lovedArtists.isNotEmpty()) {
            val theirs = notDisliked.filter {
                it.artistKey in lovedArtists && (stats[it.id]?.rating ?: 0) == 0
            }
            if (theirs.size >= 5) {
                mixes.add(
                    Mix(
                        id = "mix:lovedartists",
                        title = "מהאמנים שדירגת",
                        subtitle = "${lovedArtists.size} אמנים שנתת להם 4 ומעלה",
                        songs = pick(theirs, 50, salt = 83L, maxPerArtist = 6, maxPerAlbum = 3)
                    )
                )
            }
        }

        // rated songs get their own shelf now that ratings exist per song
        // From what may be offered at all: a medley and a disliked song are
        // left out of every other mix, and a four star rating given before a
        // thumbs down is not a reason to bring it back.
        val topRated = notDisliked.filter { (stats[it.id]?.rating ?: 0) >= 4 }
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
            // By the mood model where there is one: קצבי and רגוע as the
            // mood chips and lists read them, with the user's own marks.
            //
            // They were a tempo threshold - "above 112" - on the raw
            // estimate, and the tempo detector answers even when it has
            // nothing to go on: a sparse pulse aliases upward readily, and a
            // niggun in free time comes back as a confident 120. The mood
            // model weighs the tempo by how sure the detector was and falls
            // back to counting onsets; the threshold did not, and it could
            // not be taught - a song marked "not קצבי" stayed in the mix.
            val byMood = moodModel.ready
            fun inMood(mood: Mood, s: SongEntity) =
                moodModel.matches(mood, features[s.id]) && s.id !in speechAhead
            fun lean(mood: Mood): ((SongEntity) -> Double)? =
                if (!byMood) null else { c -> MOOD_MIX_LEAN * moodModel.strength(mood, features[c.id]) }
            val fast = notDisliked.filter { s ->
                if (byMood) inMood(Mood.ENERGETIC, s) else {
                    val f = features[s.id] ?: return@filter false
                    f.bpm >= 112f && f.onsetRate >= 1.2f
                }
            }
            if (fast.size >= 8) {
                mixes.add(
                    Mix(
                        id = "mix:tempo:fast",
                        title = "מיקס קצבי",
                        subtitle = if (byMood) Mood.ENERGETIC.subtitle else "מעל 112 פעימות בדקה",
                        songs = pick(fast, 40, salt = 67L, maxPerArtist = 3, extra = lean(Mood.ENERGETIC))
                    )
                )
            }
            val calm = notDisliked.filter { s ->
                if (byMood) inMood(Mood.CALM, s) else {
                    val f = features[s.id] ?: return@filter false
                    f.bpm in 1f..95f || (f.onsetRate < 0.8f && f.dynamics < 0.9f)
                }
            }
            if (calm.size >= 8) {
                mixes.add(
                    Mix(
                        id = "mix:tempo:calm",
                        title = "מיקס רגוע",
                        subtitle = Mood.CALM.subtitle,
                        songs = pick(calm, 40, salt = 71L, maxPerArtist = 3, extra = lean(Mood.CALM))
                    )
                )
            }
            // acoustic neighbourhood of the single most endorsed song - of the
            // songs that may be offered: it opens the mix, and it was taken
            // from the whole library, so a shiur played every day, a vocal
            // song outside its weeks or a medley could open a mix of music
            val anchor = notDisliked
                .filter { acoustic?.has(it.id) == true }
                .maxByOrNull { behaviour[it.id] ?: 0.0 }
                ?.takeIf { (behaviour[it.id] ?: 0.0) > 0.0 }
            if (anchor != null && acousticPositives.isNotEmpty()) {
                val neighbours = pick(
                    // Not the anchor's other copies: they are what it sounds
                    // most like, and "sounds like X" opening with X again is
                    // the one answer that is certainly not what was meant.
                    notDisliked.filter {
                        it.id != anchor.id && !samePiece(anchor.id, it.id) &&
                            !separated(anchor.id, it.id)
                    },
                    35,
                    salt = 79L,
                    maxPerArtist = 3,
                    nearest = 35 * NEAREST_FACTOR
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
                !it.key.startsWith("tempo:") && !it.key.startsWith("mode:") && it.key !in broadGenres }
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
            val ownIds = own.map { it.id }
            // "Similar to him" has to mean similar within what the user is
            // willing to hear in one sitting. The neighbours were drawn from
            // the whole library and only checked against each other, so a
            // חסידי singer's radio filled its second half with ישראלי - the
            // one pairing the separation rule exists to prevent.
            val ownStyles = Styles.parse(artist.styles).ifEmpty {
                own.firstOrNull()?.let { declaredStyles[it.id] }.orEmpty()
            }
            val neighbours = pick(
                notDisliked.filter {
                    it.artistKey != artist.artistKey &&
                        !separations.clash(ownStyles, declaredStyles[it.id].orEmpty())
                },
                12,
                salt = artist.artistKey.hashCode().toLong(),
                nearest = 12 * NEAREST_FACTOR
            ) { c ->
                // To the artist as a whole. It was measured against whichever
                // of their songs happened to come first in the list.
                1.6 * affinityTo(own.map { it.id }.take(10), c.id) +
                    0.9 * (acoustic?.similarityToSet(c.id, ownIds, ARTIST_SIMILARITY_K) ?: 0.0)
            }
            mixes.add(
                Mix(
                    id = "mix:artist:${artist.artistKey}",
                    title = "רדיו ${artist.displayName}",
                    subtitle = "${artist.displayName} ודומים לו",
                    // In the order a radio plays, not theirs and then the rest.
                    songs = (pick(own, 26, salt = 61L, maxPerArtist = 99, maxPerAlbum = 99) + neighbours)
                        .distinctBy { it.id }
                        .let { if (it.isEmpty()) it else sequence(it.first(), it.drop(1)) }
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

        // Songs the listening vouches for that have not been on for a while.
        // It was ranked by play count times the recency of the last touch, so
        // it opened on whatever was heard ten minutes ago - not "again" in any
        // sense - followed by a song skipped thirty times, whose plays and
        // fresh skip both counted in its favour.
        val heard = notDisliked.filter {
            val st = stats[it.id]
            (st?.playCount ?: 0) >= 2 &&
                hoursSince(st?.lastPlayedAt ?: 0L) >= AGAIN_AFTER_HOURS &&
                (behaviour[it.id] ?: 0.0) > 0.35
        }
        if (heard.size >= 6) {
            sections.add(
                FeedSection(
                    id = "again",
                    title = "תשמע שוב",
                    kind = SectionKind.SONG_ROW,
                    songs = offered(
                        heard.sortedByDescending { behaviour[it.id] ?: 0.0 }
                    ).take(20)
                )
            )
        }

        // From what may be offered: the latest like could be a shiur, or a
        // vocal song outside its weeks, and a shelf named after it would then
        // stand on a song the feed itself keeps out of sight.
        val lastLiked = playable
            .filter { (stats[it.id]?.liked ?: 0) == 1 && !medley(it) }
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
        //
        // Measured by the same endorsement the rest of the engine uses, which
        // subtracts skips. Plays alone were summed, so under shuffle - where
        // plays follow file count - the singer with the most files won even
        // when he was skipped twice for every play, over one played eighty
        // times to the end. And over music only: shiurim played daily used to
        // win the shelf, find fewer than five songs to fill it, and take it
        // off the page altogether.
        val engagement = HashMap<String, Double>()
        for (s in playable) {
            val w = behaviour[s.id] ?: continue
            if (w == 0.0) continue
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
        val live = offered(
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
        val likedStudio = playable.filter {
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
                        songs = offered(
                            liveOfLiked.sortedByDescending { totalScore(it) }
                        ).take(20)
                    )
                )
            }
        }

        // Someone else's take on a song already in the library. Live recordings
        // are deliberately not here: they are a different kind of alternative
        // and they have two shelves of their own above, and mixing them in
        // left this one mostly full of concert tracks. Deduping by version key
        // would defeat the point, since the alternatives are the offer.
        // Covers only. Remixes came in too, under a subtitle promising another
        // singer's performance, and a remix is mostly the same singer.
        val alternates = Versions.alternates(notDisliked, versionTypes, setOf(VersionType.COVER))
        if (alternates.size >= 3) {
            sections.add(
                FeedSection(
                    id = "covers",
                    title = "גרסאות כיסוי",
                    subtitle = "ביצועים של אמנים אחרים לשירים שכבר יש לך",
                    kind = SectionKind.SONG_ROW,
                    songs = oneSide(alternates.sortedByDescending { totalScore(it) }).take(24)
                )
            )
        }

        // What the user has actually claimed - liked or rated - as opposed to what
        // merely sits on the device.
        val yours = notDisliked.filter {
            val st = stats[it.id]
            // Three and up. A one or two star rating is a statement against
            // the song, and a shelf of what you have claimed was opening on
            // songs you had marked as bad.
            (st?.liked ?: 0) == 1 || (st?.rating ?: 0) >= 3
        }
        if (yours.size >= 6) {
            sections.add(
                FeedSection(
                    id = "yours",
                    title = "מהספרייה שלך",
                    subtitle = "מה שסימנת ודירגת",
                    kind = SectionKind.SONG_ROW,
                    songs = dedupeVersions(yours.sortedByDescending { totalScore(it) }).take(24)
                )
            )
        }

        // The mood the user's own picks cluster into, then more of it. This leans
        // on measured audio rather than the style tags, which stay empty until
        // somebody types them in by hand.
        // What the listening vouches for, skips taken off. Three plays used to
        // be enough on their own, so a song skipped thirty times still counted
        // as a favourite and pulled the shelf towards its mood.
        val engaged = playable.filter { (behaviour[it.id] ?: 0.0) > 0.35 }
        if (engaged.size >= 5 && moodModel.ready) {
            val favourite = favouriteMood(engaged, moodModel)
            if (favourite != null) {
                val more = offered(
                    notDisliked
                        .filter {
                            moodModel.matches(favourite, features[it.id]) && it !in engaged &&
                                it.id !in speechAhead
                        }
                        .sortedByDescending { totalScore(it) }
                ).take(20)
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
                    songs = dedupeVersions(
                        longForm.sortedByDescending { it.durationMs }
                    ).take(20)
                )
            )
        }

        val recentFiles = dedupeVersions(
            playable.sortedByDescending { it.dateAddedSec }
        ).take(20)
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
            val settling = tuning.lastMoodAt > 0L && now - tuning.lastMoodAt < MOOD_HOLD_MS
            if (held != null && (settling || (leader.second - held.second) / total < MOOD_MARGIN)) {
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
     * What a mood chip opens: the songs expressing [mood], strongest first.
     *
     * Answered from this snapshot, which already holds the analysis and a
     * mood model built over it. The chip used to read every song's analysis
     * from the database and build a second model on each tap - seconds on a
     * large library, for the same answer. The same computation as
     * [Mood.strongest] over the same rows and marks; drawn from what the feed
     * may offer, so a shiur is never a mood and the vocal-only weeks apply.
     */
    fun strongestIn(mood: Mood): List<SongEntity> =
        playable
            .filter { (stats[it.id]?.liked ?: 0) != -1 }
            .filter { moodModel.matches(mood, features[it.id]) && it.id !in speechAhead }
            .sortedByDescending { moodModel.strength(mood, features[it.id]) }

    /** Whether anything in this snapshot has been analysed at all. */
    val hasAnalysis: Boolean get() = features.isNotEmpty()

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
        // Medleys stay out, as they do of every other generated mix: landing on
        // one unasked sounds like a song that started halfway through.
        val analysed = playable.filter { space.has(it.id) && !medley(it) }
        // What the clusters are made of: the music model's print where nearly
        // every song has one, else YAMNet's, else the measured features.
        //
        // It was always the features - tempo, loudness, timbre, key - and
        // those are the weakest thing the app measures: asked which songs
        // share a style, a song's nearest neighbours by the features did so
        // less often than a random pick (12% against 25%), where the prints
        // did best. So the daily mixes were mostly tempo bands - "מיקס 131
        // BPM" - rather than kinds of music. One measure for all of them, so
        // no cluster is an artefact of which songs had which.
        fun covered(prints: Map<Long, DoubleArray>): List<SongEntity>? {
            val with = analysed.filter { it.id in prints }
            return with.takeIf { it.size >= 40 && it.size * 10 >= analysed.size * DAILY_PRINT_COVER }
        }
        val (entries, points) = covered(space.musicPrints)?.let { it to it.map { s -> space.musicPrints.getValue(s.id) } }
            ?: covered(space.soundPrints)?.let { it to it.map { s -> space.soundPrints.getValue(s.id) } }
            ?: (analysed to analysed.map { space.vectors.getValue(it.id) })
        if (entries.size < 40) return emptyList()

        val dims = points.first().size
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
        for (iteration in 0 until 14) {
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
            // A real stop. This was `return@repeat`, which only ends the
            // current round of a repeat, so every call ran all fourteen.
            if (!moved) break
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
                    token.startsWith("tempo:") || token.startsWith("mode:") || token in broadGenres
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

    /**
     * How well the engine would have guessed what actually came next.
     *
     * Every change to the ranking until now has been an argument. This turns
     * them into a number: walk the recent history, and for each song that was
     * followed by another, ask where the engine ranks that other song out of
     * the whole library. If a change to the scoring is an improvement, the
     * true next song moves up.
     *
     * Two honest limits, and they matter.
     *
     * It is optimistic. The statistics it ranks with already include the plays
     * being predicted, so the engine has seen the answer. That makes the
     * absolute numbers flattering and says nothing about them. What it does
     * not do is favour one version of the scoring over another, which is what
     * this is for - the comparison between two runs is sound even though
     * neither is an unbiased estimate of anything.
     *
     * And it measures agreement with what was listened to under the old
     * recommendations, not what the listener would have enjoyed most. A
     * change that scores worse here is not necessarily worse; it is
     * differently. It is a guard against regressions, not a verdict.
     *
     * The random baseline is returned alongside for exactly that reason: a
     * recall of 0.30 means nothing until it is set against the 0.025 that
     * guessing would have produced.
     */
    data class SequenceReport(
        val pairs: Int,
        val librarySize: Int,
        val recallAt10: Double,
        val recallAt50: Double,
        val meanReciprocalRank: Double,
        val medianRank: Int
    ) {
        /** What pure chance would score on a library this size. */
        val randomRecallAt10: Double get() = 10.0 / librarySize.coerceAtLeast(1)
        val randomRecallAt50: Double get() = 50.0 / librarySize.coerceAtLeast(1)
    }

    /**
     * @param recent song ids in the order they were played, oldest first.
     * @param maxPairs a ceiling on the work: each pair is scored against the
     *   whole library, so this is the difference between a second and a minute.
     */
    fun evaluateSequence(recent: List<Long>, maxPairs: Int = 60): SequenceReport? {
        val pool = playable.filter { (stats[it.id]?.liked ?: 0) != -1 }
        if (pool.size < 20) return null

        val pairs = ArrayList<Pair<Long, Long>>()
        for (i in 0 until recent.size - 1) {
            val from = recent[i]
            val to = recent[i + 1]
            if (from == to || from <= 0L || to <= 0L) continue
            if (tokensBySong[from] == null || tokensBySong[to] == null) continue
            pairs.add(from to to)
        }
        if (pairs.isEmpty()) return null
        val sample = pairs.takeLast(maxPairs)

        var hits10 = 0
        var hits50 = 0
        var reciprocal = 0.0
        val ranks = ArrayList<Int>(sample.size)

        for ((from, to) in sample) {
            val target = continuationScore(from, to)
            // The rank is how many candidates the engine put in front of the
            // song that actually came next. Counting beats sorting: the whole
            // ordering is not needed, only one position in it.
            var ahead = 1
            for (candidate in pool) {
                if (candidate.id == from || candidate.id == to) continue
                if (continuationScore(from, candidate.id) > target) ahead++
            }
            ranks.add(ahead)
            if (ahead <= 10) hits10++
            if (ahead <= 50) hits50++
            reciprocal += 1.0 / ahead
        }

        ranks.sort()
        return SequenceReport(
            pairs = sample.size,
            librarySize = pool.size,
            recallAt10 = hits10.toDouble() / sample.size,
            recallAt50 = hits50.toDouble() / sample.size,
            meanReciprocalRank = reciprocal / sample.size,
            medianRank = ranks[ranks.size / 2]
        )
    }

    /**
     * The same arithmetic [continuation] ranks by, for one candidate.
     *
     * Shared deliberately: an evaluation that scores with a different formula
     * than the one being shipped measures nothing about the one being shipped.
     */
    private fun continuationScore(from: Long, candidate: Long): Double {
        var s = baseScores[candidate] ?: 0.0
        s += 1.6 * affinityTo(listOf(from), candidate)
        s += 2.6 * transitionScore(from, candidate)
        s += 1.2 * acousticSimilarity(from, candidate)
        s += 0.9 * styleSimilarity(from, candidate)
        s -= 0.4 * tempoDistance(from, candidate)
        return s
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
            // Bounded, so taste can only reorder songs the text already ranks
            // alike. The raw score was added straight in, and it runs from
            // about -10 to +6 - wide enough to jump a whole tier of text match.
            // So the exact title someone typed came second to a loved song
            // that merely contained the word, whenever the exact one had been
            // played a minute ago and the repeat guard was holding it down:
            // a feed concern, leaking into the one place it has no business.
            // tanh keeps the order among equals and caps the reach below half
            // the smallest gap between text tiers.
            val taste = kotlin.math.tanh((baseScores[song.id] ?: 0.0) / 4.0)
            song to textScore + personal * taste
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
         * The feature rows as the engine should hold them: without what it
         * never reads once it has been built.
         *
         * The music print, chroma, timbre and the quarter tone chroma are read
         * exactly once, by [AcousticSpace] when it is made - which must be
         * from the full rows - and never again. Held in every row they were
         * about a quarter of the memory a library takes, for the life of every
         * snapshot. What is still read later stays: the tags, the sound print
         * and the model's moods for [MoodModel], the shape, and every number.
         *
         * Same order as [rows], because the mood model reads them in order.
         * EngineGoldenTest builds the engine both ways and requires identical
         * answers, so a later change that starts reading one of these fields
         * after construction fails there rather than quietly getting blanks.
         */
        fun leanFeatures(rows: List<AudioFeatureEntity>): Map<Long, AudioFeatureEntity> =
            rows.associate {
                it.songId to it.copy(musicPrint = "", chroma = "", timbre = "", timbreVar = "", chroma24 = "")
            }

        /**
         * Where the affinity term reaches half its ceiling.
         *
         * Chosen for the normalised scale: a pair that is heard together most
         * of the times either is heard scores near 1 before the sum, and this
         * puts the midpoint where an ordinary strong pair lands.
         */
        private const val AFFINITY_HALF = 0.6

        /**
         * What a skip still weighs once the song has been heard after it: a
         * quarter. See [countedSkips] for why not nothing.
         */
        private const val SKIP_FORGIVEN = 0.25

        /**
         * A play writes the history and the stats with one timestamp; this is
         * slack for any clock that rounds, far short of the few seconds a skip
         * needs to be recorded at all.
         */
        private const val SAME_EVENT_MS = 1_000L

        /**
         * Where artist listening saturates. Roughly: a handful of songs heard a
         * few times each is most of the way there, and the hundredth play adds
         * almost nothing more.
         */
        private const val ARTIST_LISTEN_SCALE = 6.0

        /** Vocal songs needed before "only vocal" in the season replaces everything else. */
        const val MIN_VOCAL_TO_REPLACE = 15

        /** How many days it takes an old skip to lose most of what can fade. */
        const val SKIP_FADE_DAYS = 180.0

        /** What one skip in a burst of skipping counts for, against a considered one. */
        const val BURST_SKIP = 0.3

        /** How much of a song's dislike or skips reaches its artist, style, mood and sound. */
        const val NEGATIVE_SPILL = 0.4

        /** A mood needs about this many touched songs before it is half believed. */
        private const val MOOD_PRIOR_SONGS = 5.0

        /** How far above the listener's average a mood must sit to count fully. */
        private const val MOOD_LIFT_SCALE = 0.8

        /** Genres that are only a placeholder, lower case. */
        private val PLACEHOLDER_GENRES = setOf(
            "other", "others", "unknown", "<unknown>", "misc", "miscellaneous", "none", "genre",
            "general", "default", "various", "various artists", "music", "audio", "mp3", "unclassifiable",
            "אחר", "אחרים", "כללי", "שונות", "לא ידוע", "מוזיקה", "ללא", "ללא ז'אנר"
        )

        /** An ID3v1 genre number left unresolved: "12", "(12)". */
        private val NUMBERED_GENRE = Regex("\\(?\\d{1,3}\\)?")

        /** How far a clear example of a mood leads its mix, against the listener's score. */
        private const val MOOD_MIX_LEAN = 0.8

        /** In tenths: how much of the analysed library a print must cover to be what the daily mixes cluster on. */
        private const val DAILY_PRINT_COVER = 8

        /** How long the "based on your likes" mix is, how much of it the likes are, and how many per singer. */
        private const val LIKED_MIX_SIZE = 50
        private const val LIKED_IN_MIX = 30
        private const val LIKED_PER_ARTIST = 3

        /** The windows [recentRestlessness] tries, shortest first. */
        private val RESTLESS_WINDOWS_DAYS = longArrayOf(30L, 90L)

        /** Songs touched in a window before its skip share is believed. */
        private const val RESTLESS_MIN_SONGS = 10

        /** How long a song must have been off before "listen again" offers it. */
        private const val AGAIN_AFTER_HOURS = 48.0

        /**
         * Below this many songs the listening says something about, rated
         * artists top the sound model up. Above it they are left out: plays
         * are better evidence about sound than an opinion of a singer.
         */
        private const val ARTIST_SEED_FLOOR = 12

        /** How many of one rated artist's songs may seed the sound model. */
        private const val SEEDS_PER_ARTIST = 3

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

        /** A "like this" shelf chooses among this many times its length of the closest candidates; see pick. */
        private const val NEAREST_FACTOR = 5

        /** An artist's radio measures a candidate against their closest few songs. */
        private const val ARTIST_SIMILARITY_K = 3

        /** How long a newly chosen mood keeps the shelf whatever the margin; see [EngineTuning.lastMoodAt]. */
        const val MOOD_HOLD_MS = 3L * 24 * 60 * 60 * 1000

        /**
         * Friday or Saturday.
         *
         * The week this library is listened to across is not flat. What is
         * played coming into Shabbat, through it, and on a Tuesday afternoon
         * are three different things, and hour-of-day buckets cannot see any
         * of it - Friday evening and Monday evening land in the same bucket.
         *
         * Drawn on the device's own calendar, so it follows whatever week the
         * phone is set to rather than assuming one.
         */
        fun isWeekend(timeMs: Long): Boolean {
            val c = Calendar.getInstance()
            c.timeInMillis = timeMs
            val day = c.get(Calendar.DAY_OF_WEEK)
            return day == Calendar.FRIDAY || day == Calendar.SATURDAY
        }

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
