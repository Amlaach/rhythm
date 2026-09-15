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
    val acousticWeight: Float = 1.0f
)

data class TransitionEdge(val weight: Double, val penalty: Double)

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

    private val tokensBySong: Map<Long, List<String>> = songs.associate { it.id to tokensFor(it) }

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

    private fun unitVector(tokens: List<String>): Map<String, Double> {
        if (tokens.isEmpty()) return emptyMap()
        val v = 1.0 / sqrt(tokens.size.toDouble())
        return tokens.associateWith { v }
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
            val per = w / sqrt(styles.size.toDouble())
            for (s in styles) {
                val k = s.lowercase(Locale.ROOT)
                acc[k] = (acc[k] ?: 0.0) + per
            }
        }

        // (b) behaviour, including per song ratings
        for (song in songs) {
            val w = behaviour[song.id] ?: 0.0
            if (abs(w) < 1e-6) continue
            val tokens = tokensBySong[song.id].orEmpty()
            if (tokens.isEmpty()) continue
            val per = w / sqrt(tokens.size.toDouble())
            for (t in tokens) acc[t] = (acc[t] ?: 0.0) + per
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
        if (attempts >= 3) score -= 1.25 * (skips.toDouble() / attempts)

        score += 0.75 * timeFit(st)
        score -= 2.6 * tuning.repeatGuard * exp(-hoursSince(st?.lastPlayedAt ?: 0L) / 9.0)
        score += 0.35 * exp(-daysSince(song.dateAddedSec * 1000L) / 21.0)
        if (plays == 0) score += 1.1 * tuning.discovery

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
        if (plays + skips >= 3) {
            out.add(
                ScoreTerm(
                    "דילוגים",
                    -1.25 * (skips.toDouble() / (plays + skips)),
                    "$skips דילוגים מתוך ${plays + skips}"
                )
            )
        }
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
            out.add(ScoreTerm("גילוי", 1.1 * tuning.discovery, "עוד לא הושמע"))
        }
        return out.sortedByDescending { abs(it.value) }
    }

    fun totalScore(song: SongEntity): Double = baseScores[song.id] ?: 0.0

    private fun noise(id: Long, salt: Long, amount: Double): Double =
        (Random(id * 1_000_003L + salt).nextDouble() - 0.5) * amount

    private fun affinityTo(seedIds: Collection<Long>, candidate: Long): Double {
        if (seedIds.isEmpty()) return 0.0
        var sum = 0.0
        for (seed in seedIds) sum += affinity[seed]?.get(candidate) ?: 0.0
        return sum / (sum + 3.0)
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

    private fun tempoDistance(a: Long, b: Long): Double = acoustic?.tempoDistance(a, b) ?: 0.0

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
        val jitter = 0.22 + 0.85 * tuning.discovery
        val ranked = candidates.map { song ->
            var s = baseScores[song.id] ?: 0.0
            if (extra != null) s += extra(song)
            s += noise(song.id, salt + feedSeed, jitter)
            song to s
        }.sortedByDescending { it.second }

        val artistCount = HashMap<String, Int>()
        val albumCount = HashMap<Long, Int>()
        val out = ArrayList<SongEntity>(count)
        for ((song, _) in ranked) {
            if (out.size >= count) break
            val a = artistCount.getOrDefault(song.artistKey, 0)
            val b = albumCount.getOrDefault(song.albumId, 0)
            if (a >= maxPerArtist || b >= maxPerAlbum) continue
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
        val pool = songs.filter { it.id != seed.id && (stats[it.id]?.liked ?: 0) != -1 }
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
        val pool = songs.filter { it.id !in exclude && (stats[it.id]?.liked ?: 0) != -1 }
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
        val notDisliked = songs.filter { (stats[it.id]?.liked ?: 0) != -1 }

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
        val random = Random(feedSeed * 31 + 7)

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
            val members = entries.filterIndexed { i, _ -> assignment[i] == c }
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

    fun search(query: String, limit: Int = 60): List<SongEntity> {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return emptyList()
        val terms = q.split(' ').filter { it.isNotBlank() }
        return songs.mapNotNull { song ->
            val title = song.titleLower
            val artist = song.artistName.lowercase(Locale.ROOT)
            val album = song.albumName.lowercase(Locale.ROOT)
            var textScore = 0.0
            for (t in terms) {
                when {
                    title.startsWith(t) -> textScore += 3.0
                    title.contains(t) -> textScore += 2.0
                    artist.startsWith(t) -> textScore += 2.4
                    artist.contains(t) -> textScore += 1.6
                    album.contains(t) -> textScore += 0.9
                    else -> return@mapNotNull null
                }
            }
            song to textScore + 0.25 * (baseScores[song.id] ?: 0.0)
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
