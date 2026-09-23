package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.db.ArtistEntity
import com.elchanan.rhythm.data.db.AudioFeatureEntity
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.data.db.SongStatsEntity
import java.util.Locale
import kotlin.random.Random

/**
 * A large, deterministic library that reaches every part of the engine.
 *
 * Artists sound like themselves, so similarity, radio and style learning have
 * something real to find: each has a centre in every feature space and its
 * songs scatter around it. Some songs have no music print, some no print at
 * all, some were never analysed, so every fallback is taken. Listening covers
 * plays over months, likes and dislikes with dates, ratings, skips in bursts
 * and alone, typed and learned styles, mood corrections for every mood, vocal
 * and spoken recordings, pairs and transitions.
 *
 * Nothing here may change once golden digests have been recorded against it,
 * or the digests stop meaning anything. Add a second fixture instead.
 */
object EngineFixture {

    /** Noon UTC on 2026-09-16: outside the Omer and the Three Weeks. */
    const val NOW = 1_789_560_000_000L

    /** Noon UTC on 2026-04-20, in the Omer. */
    const val NOW_IN_SEFIRA = 1_776_686_400_000L

    private const val DAY = 86_400_000L

    class Library(
        val songs: List<SongEntity>,
        val stats: Map<Long, SongStatsEntity>,
        val artists: Map<String, ArtistEntity>,
        val affinity: Map<Long, Map<Long, Double>>,
        val transitions: Map<Long, Map<Long, TransitionEdge>>,
        val features: List<AudioFeatureEntity>,
        val spoken: Set<Long>,
        val vocal: Set<Long>,
        val lastHeard: Map<Long, Long>
    )

    fun build(songCount: Int = 2400, artistCount: Int = 120, now: Long = NOW): Library {
        val rnd = Random(20260916)
        val styleWords = Styles.SUGGESTED

        class Voice(
            val bpm: Float, val energy: Float, val bright: Float, val key: Int,
            val sound: FloatArray, val music: FloatArray, val tags: FloatArray,
            val happy: Float, val calm: Float
        )

        val voices = List(artistCount) {
            Voice(
                bpm = 70f + rnd.nextFloat() * 80f,
                energy = 0.15f + rnd.nextFloat() * 0.6f,
                bright = 0.2f + rnd.nextFloat() * 0.5f,
                key = rnd.nextInt(12),
                sound = FloatArray(SoundPrint.DIMS) { rnd.nextFloat() * 2f },
                music = FloatArray(MusicPrint.DIMS) { rnd.nextFloat() * 2f - 1f },
                tags = FloatArray(521) { if (rnd.nextFloat() < 0.15f) rnd.nextFloat() * 0.6f else rnd.nextFloat() * 0.05f },
                happy = rnd.nextFloat(),
                calm = rnd.nextFloat()
            )
        }
        val artistNames = List(artistCount) { i ->
            when (i % 4) {
                0 -> "אמן $i"
                1 -> "Artist $i"
                2 -> "להקת ${i}ים"
                else -> "זמר ${i} ומקהלה"
            }
        }

        val songs = ArrayList<SongEntity>(songCount)
        for (id in 1L..songCount.toLong()) {
            val a = ((id * 7919) % artistCount).toInt()
            val album = id / 11
            val title = when {
                id % 97 == 0L -> "ווקאלי - ניגון $id"
                id % 131 == 0L -> "שיעור בפרשת השבוע $id"
                id % 53 == 0L -> "שיר $id (Live)"
                id % 61 == 0L -> "שיר ${id - 1} - רמיקס"
                else -> "שיר $id"
            }
            songs.add(
                SongEntity(
                    id = id, title = title, titleLower = title.lowercase(Locale.ROOT),
                    artistName = artistNames[a], artistKey = Names.normalizeKey(artistNames[a]),
                    albumName = "אלבום $album", albumId = album,
                    durationMs = if (id % 131 == 0L) 45 * 60_000L else (150_000L + (id * 3571) % 240_000L),
                    trackNumber = (id % 12).toInt() + 1, year = 1995 + (id % 30).toInt(),
                    genre = if (id % 5 == 0L) styleWords[a % styleWords.size] else null,
                    path = "/storage/emulated/0/Music/${artistNames[a]}/אלבום $album/$title.mp3",
                    folder = "/storage/emulated/0/Music/${artistNames[a]}",
                    dateAddedSec = 1_600_000_000L + id * 3_600L, sizeBytes = 5_000_000L + id
                )
            )
        }

        val artists = HashMap<String, ArtistEntity>()
        for (a in 0 until artistCount) {
            if (a % 3 == 2) continue
            val key = Names.normalizeKey(artistNames[a])
            val styles = listOf(styleWords[a % 6], styleWords[6 + a % 8]).let {
                if (a % 5 == 0) it.take(1) else it
            }
            artists[key] = ArtistEntity(
                artistKey = key, displayName = artistNames[a],
                rating = if (a % 4 == 0) 0 else 1 + a % 5,
                styles = Styles.join(styles), updatedAt = now - a * DAY
            )
        }

        fun jitter(scale: Float) = (rnd.nextFloat() - 0.5f) * scale
        fun csv(values: DoubleArray) = values.joinToString(",") { String.format(Locale.US, "%.5f", it) }

        val features = ArrayList<AudioFeatureEntity>()
        for (s in songs) {
            if (s.id % 29 == 0L) continue // never analysed
            val a = ((s.id * 7919) % artistCount).toInt()
            val v = voices[a]
            if (s.id % 211 == 0L) {
                features.add(Analysis.blankFor(s.id))
                continue
            }
            val sound = if (s.id % 17 == 0L) null else FloatArray(SoundPrint.DIMS) { (v.sound[it] + jitter(0.8f)).coerceIn(0f, 6f) }
            val music = if (s.id % 5 == 0L || sound == null) null else FloatArray(MusicPrint.DIMS) { v.music[it] + jitter(0.9f) }
            val tags = FloatArray(521) { (v.tags[it] + jitter(0.1f)).coerceIn(0f, 1f) }
            val moods = if (music == null) "" else MusicMoods.encode(
                mapOf(
                    "happy" to (v.happy + jitter(0.3f)).coerceIn(0f, 1f),
                    "sad" to (1f - v.happy + jitter(0.3f)).coerceIn(0f, 1f),
                    "relaxed" to (v.calm + jitter(0.3f)).coerceIn(0f, 1f),
                    "aggressive" to (1f - v.calm + jitter(0.3f)).coerceIn(0f, 1f),
                    "danceable" to rnd.nextFloat(),
                    "party" to rnd.nextFloat(),
                    "arousal.arousal" to 3f + (1f - v.calm) * 4f + jitter(1f),
                    "valence.valence" to 3f + v.happy * 4f + jitter(1f)
                )
            )
            features.add(
                AudioFeatureEntity(
                    songId = s.id, analyzedAt = now - 30 * DAY,
                    bpm = if (s.id % 43 == 0L) 0f else (v.bpm + jitter(12f)),
                    bpmConfidence = 0.4f + rnd.nextFloat() * 0.5f,
                    musicalKey = (v.key + if (rnd.nextFloat() < 0.2f) 5 else 0) % 12,
                    mode = if (v.happy > 0.5f) 1 else 0,
                    energy = (v.energy + jitter(0.1f)).coerceAtLeast(0.01f),
                    brightness = (v.bright + jitter(0.1f)).coerceIn(0f, 1f),
                    flatness = 0.05f + rnd.nextFloat() * 0.2f,
                    dynamics = 0.5f + rnd.nextFloat(),
                    onsetRate = 0.4f + (v.bpm - 70f) / 60f + jitter(0.3f),
                    chroma = csv(DoubleArray(12) { if (it == 0) 1.0 else rnd.nextDouble() * 0.6 }),
                    timbre = csv(DoubleArray(12) { (a % 7 - 3) + rnd.nextDouble() - 0.5 }),
                    timbreVar = csv(DoubleArray(12) { rnd.nextDouble() }),
                    shape = csv(DoubleArray(6) { rnd.nextDouble() }),
                    scaleMode = if (rnd.nextFloat() < 0.7f) (a % 6) else -1,
                    scaleConfidence = rnd.nextFloat() * 0.6f,
                    chroma24 = csv(DoubleArray(24) { rnd.nextDouble() }),
                    tags = AudioTags.compress(tags),
                    soundPrint = sound?.let { SoundPrint.pack(it) } ?: SoundPrint.TRIED,
                    musicPrint = music?.let { MusicPrint.pack(it) } ?: MusicPrint.TRIED,
                    musicMoods = moods
                )
            )
        }

        val stats = HashMap<Long, SongStatsEntity>()
        val lastHeard = HashMap<Long, Long>()
        for (s in songs) {
            val a = ((s.id * 7919) % artistCount).toInt()
            val fan = a % 5 < 2
            if (rnd.nextFloat() > (if (fan) 0.75f else 0.3f)) continue
            val plays = if (fan) rnd.nextInt(0, 40) else rnd.nextInt(0, 6)
            val skips = if (fan) rnd.nextInt(0, 3) else rnd.nextInt(0, 9)
            val last = now - rnd.nextLong(DAY / 4, 200 * DAY)
            val liked = when {
                fan && rnd.nextFloat() < 0.25f -> 1
                !fan && rnd.nextFloat() < 0.12f -> -1
                else -> 0
            }
            val moodMarks = if (s.id % 23 == 0L) {
                val mood = Mood.entries[(s.id / 23 % Mood.entries.size).toInt()]
                MoodMarks.encode(mapOf(mood to (s.id % 2 == 0L)))
            } else ""
            stats[s.id] = SongStatsEntity(
                songId = s.id, playCount = plays, skipCount = skips,
                completeCount = (plays * 0.8).toInt(), listenedMs = plays * 180_000L,
                lastPlayedAt = last, liked = liked,
                likedAt = if (liked != 0) last - rnd.nextLong(0, 60 * DAY) else 0L,
                rating = if (s.id % 19 == 0L) 1 + (s.id % 5).toInt() else 0,
                styles = if (s.id % 37 == 0L) Styles.join(listOf(styleWords[(s.id % styleWords.size).toInt()])) else "",
                stylesAuto = if (s.id % 74 == 0L) 1 else 0,
                b0 = rnd.nextInt(0, 5), b1 = rnd.nextInt(0, 5), b2 = rnd.nextInt(0, 5), b3 = rnd.nextInt(0, 5),
                dWeekend = rnd.nextInt(0, 4), dWeekday = rnd.nextInt(0, 8),
                spoken = if (s.id % 263 == 0L) 1 else -1,
                moods = moodMarks,
                vocal = if (s.id % 389 == 0L) 1 else if (s.id % 97 == 0L && s.id % 2 == 0L) 0 else -1,
                playDays = if (plays == 0) 0 else 1 + rnd.nextInt(0, plays),
                lastPlayDay = if (plays == 0) -1L else last / DAY,
                burstSkips = if (skips > 2) rnd.nextInt(0, skips) else 0,
                lastSkipAt = if (skips > 0) now - rnd.nextLong(DAY, 300 * DAY) else 0L
            )
            if (plays > 0 && rnd.nextFloat() < 0.6f) lastHeard[s.id] = last
        }

        val affinity = HashMap<Long, HashMap<Long, Double>>()
        val transitions = HashMap<Long, HashMap<Long, TransitionEdge>>()
        val played = stats.values.filter { it.playCount > 0 }.map { it.songId }.sorted()
        for (i in played.indices) {
            val a = played[i]
            repeat(3) {
                val b = played[(i + 1 + rnd.nextInt(0, 25)) % played.size]
                if (b == a) return@repeat
                val w = rnd.nextDouble() * 3.0
                affinity.getOrPut(a) { HashMap() }[b] = w
                affinity.getOrPut(b) { HashMap() }[a] = w
                transitions.getOrPut(a) { HashMap() }[b] =
                    TransitionEdge(weight = rnd.nextDouble() * 2.0, penalty = if (rnd.nextFloat() < 0.2f) rnd.nextDouble() else 0.0)
            }
        }

        val spoken = songs.filter { it.id % 131 == 0L || it.id % 263 == 0L }.mapTo(HashSet()) { it.id }
        val featureById = features.associateBy { it.songId }
        val vocal = songs.filter { s ->
            Vocal.isVocal(s, stats[s.id], featureById[s.id], artists[s.artistKey]?.styles.orEmpty())
        }.mapTo(HashSet()) { it.id }

        return Library(songs, stats, artists, affinity, transitions, features, spoken, vocal, lastHeard)
    }

    val TUNING = EngineTuning(
        discovery = 0.4f, artistWeight = 1.1f, styleWeight = 0.9f, repeatGuard = 1.0f,
        acousticWeight = 1.2f,
        // What a fresh install has, so the separation paths are pinned too.
        separations = Styles.DEFAULT_SEPARATIONS,
        lastMood = "CALM",
        learned = SignalWeights(1.2, 0.8, 1.1, 1.4, 0.7),
        onlyVocalInSeason = true
    )
}
