package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.db.ArtistEntity
import com.elchanan.rhythm.data.db.AudioFeatureEntity
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.data.db.SongStatsEntity
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

/**
 * Two families of bug, both about the thing a list is built around.
 *
 * The seed: a radio, a continuation, "sounds like X" and "because you liked
 * X" all excluded the seed by id and nothing else. The nearest thing to any
 * track by every measure is its own second copy or live take, so each of
 * them opened, reliably, on the seed again.
 *
 * The weight: an artist rating was added to every one of that artist's songs,
 * so it counted once per file. A singer with a hundred downloads, rated and
 * never played, outvoted a hundred and twenty real plays of somebody else.
 */
class SeedAndWeightTest {

    private val now = 1_750_000_000_000L
    private val day = 86_400_000L

    private fun song(id: Long, title: String, artist: String) = SongEntity(
        id = id, title = title, titleLower = title.lowercase(),
        artistName = artist, artistKey = Names.normalizeKey(artist),
        albumName = "al${id / 4}", albumId = id / 4, durationMs = 240_000,
        trackNumber = 1, year = 2020, genre = null, path = "/m/$artist/$id.mp3",
        folder = "/m/$artist", dateAddedSec = 1_700_000_000L, sizeBytes = 1
    )

    private fun sound(id: Long, level: Float, random: Random) = AudioFeatureEntity(
        songId = id, analyzedAt = 1L,
        bpm = 80f + level * 60f + random.nextFloat() * 4f, bpmConfidence = 0.9f,
        musicalKey = 0, mode = 1,
        energy = 0.3f + level * 0.5f + random.nextFloat() * 0.03f,
        brightness = 0.2f + level * 0.4f + random.nextFloat() * 0.03f,
        flatness = 0.1f, dynamics = 0.8f + level * 0.4f, onsetRate = 0.6f + level,
        chroma = "1,0,0,0,0,0,0,0,0,0,0,0", timbre = "0,0,0,0,0,0,0,0,0,0,0,0",
        timbreVar = "0,0,0,0,0,0,0,0,0,0,0,0"
    )

    // --- the seed ----------------------------------------------------------

    private class Library(
        val songs: List<SongEntity>,
        val features: Map<Long, AudioFeatureEntity>,
        val seed: SongEntity,
        val copy: SongEntity,
        val live: SongEntity,
        val medley: SongEntity
    )

    private fun library(): Library {
        val random = Random(5)
        val songs = ArrayList<SongEntity>()
        val features = HashMap<Long, AudioFeatureEntity>()
        var id = 1L
        for ((index, artist) in listOf("אהרלה", "מוטי", "ישי", "חנן", "פריד", "שוואקי").withIndex()) {
            for (n in 1..10) {
                songs.add(song(id, "$artist שיר $n", artist))
                features[id] = sound(id, (index * 10 + n) / 60f, random)
                id++
            }
        }
        val seed = songs.first { it.title == "אהרלה שיר 3" }
        // The same track twice more, as a library of downloads holds it: once
        // re-tagged, once live. Both sound the same as the seed.
        val copy = song(id++, "אהרלה שיר 3", "אהרלה feat. מוטי")
        val live = song(id++, "אהרלה שיר 3 (live)", "אהרלה")
        val medley = song(id++, "מחרוזת אהרלה", "אהרלה")
        for (extra in listOf(copy, live)) {
            songs.add(extra)
            features[extra.id] = features.getValue(seed.id).copy(songId = extra.id)
        }
        songs.add(medley)
        features[medley.id] = sound(medley.id, 0.1f, random)
        return Library(songs, features, seed, copy, live, medley)
    }

    private fun engineFor(lib: Library, stats: Map<Long, SongStatsEntity>) = Recommender(
        songs = lib.songs, stats = stats, artists = emptyMap(),
        affinity = emptyMap(), transitions = emptyMap(), features = lib.features,
        acoustic = AcousticSpace(lib.features.values), tuning = EngineTuning(),
        now = now, feedSeed = 3L
    )

    private fun Library.againIn(list: List<SongEntity>): List<String> =
        list.filter { it.id == copy.id || it.id == live.id }.map { it.title + " / " + it.artistName }

    @Test fun aRadioDoesNotOpenOnTheSeedAgain() {
        val lib = library()
        val e = engineFor(lib, emptyMap())
        assertEquals(emptyList<String>(), lib.againIn(e.radio(lib.seed, 40)))
    }

    @Test fun autoplayDoesNotQueueACopyOfWhatJustPlayed() {
        val lib = library()
        val e = engineFor(lib, emptyMap())
        assertEquals(
            emptyList<String>(),
            lib.againIn(e.continuation(listOf(lib.seed.id), setOf(lib.seed.id), 30))
        )
        // Nor of something already waiting further down the queue.
        val other = lib.songs.first { it.title == "ישי שיר 4" }
        assertEquals(
            emptyList<String>(),
            lib.againIn(e.continuation(listOf(other.id), setOf(other.id, lib.seed.id), 30))
        )
    }

    @Test fun theShelvesBuiltAroundOneSongDoNotOfferItAgain() {
        val lib = library()
        // Liked and played, so it anchors both "sounds like" and "because you liked".
        val stats = mapOf(
            lib.seed.id to SongStatsEntity(
                lib.seed.id, playCount = 8, liked = 1, likedAt = now - 1000,
                lastPlayedAt = now - 3 * day
            )
        )
        val feed = engineFor(lib, stats).buildFeed()
        val sameSound = feed.flatMap { it.mixes }.first { it.id.startsWith("mix:sound") }
        assertEquals(lib.seed.id, sameSound.songs.first().id)
        assertEquals(emptyList<String>(), lib.againIn(sameSound.songs))
        val because = feed.first { it.id.startsWith("because:") }
        assertEquals(emptyList<String>(), lib.againIn(because.songs))
    }

    @Test fun dailyMixesLeaveOutMedleys() {
        val lib = library()
        val daily = engineFor(lib, emptyMap()).dailyMixes()
        assertTrue("the fixture should be big enough to cluster", daily.isNotEmpty())
        assertFalse(daily.any { mix -> mix.songs.any { it.id == lib.medley.id } })
    }

    @Test fun theLikedMixKeepsTheRules() {
        val lib = library()
        val chasidi = listOf("אהרלה", "מוטי", "פריד")
        val artists = listOf("אהרלה", "מוטי", "ישי", "חנן", "פריד", "שוואקי").associate { name ->
            Names.normalizeKey(name) to ArtistEntity(
                Names.normalizeKey(name), name,
                styles = if (name in chasidi) "חסידי" else "ישראלי"
            )
        }
        // Liked on both sides of the rule, and both copies of one song liked.
        val liked = lib.songs.filter { it.title.endsWith("שיר 2") || it.title.endsWith("שיר 5") } +
            listOf(lib.seed, lib.copy)
        val stats = liked.associate { it.id to SongStatsEntity(it.id, liked = 1, playCount = 2) }
        val e = Recommender(
            lib.songs, stats, artists, emptyMap(), emptyMap(), lib.features,
            AcousticSpace(lib.features.values), EngineTuning(separations = "חסידי, ישראלי"),
            now, 3L
        )
        val mix = e.buildFeed().flatMap { it.mixes }.first { it.id == "mix:liked" }
        // Untagged songs clash with nothing, by design, so only the tagged count.
        val styles = mix.songs.map { artists[it.artistKey]?.styles.orEmpty() }
            .filter { it.isNotEmpty() }.distinct()
        assertEquals("the liked mix mixed two separated styles: $styles", 1, styles.size)
        val pieces = mix.songs.map { Versions.pieceKey(it.title) }
        assertEquals("the liked mix offered one song twice", pieces.size, pieces.distinct().size)
    }

    // --- the weight --------------------------------------------------------

    /** Played one singer a great deal; rated another five stars and never played him. */
    private fun lopsided(): Recommender {
        val random = Random(9)
        val songs = ArrayList<SongEntity>()
        val features = HashMap<Long, AudioFeatureEntity>()
        val stats = HashMap<Long, SongStatsEntity>()
        var id = 1L
        repeat(30) { n ->
            songs.add(song(id, "ישי $n", "ישי"))
            features[id] = sound(id, 0.8f, random)
            stats[id] = SongStatsEntity(
                id, playCount = 4, completeCount = 4, lastPlayedAt = now - 3 * day
            )
            id++
        }
        repeat(100) { n ->
            songs.add(song(id, "פריד $n", "פריד"))
            features[id] = sound(id, 0.1f, random)
            id++
        }
        val artists = mapOf(
            "ישי" to ArtistEntity("ישי", "ישי", styles = "ישראלי"),
            "פריד" to ArtistEntity("פריד", "פריד", rating = 5, styles = "חסידי")
        )
        return Recommender(
            songs, stats, artists, emptyMap(), emptyMap(), features,
            AcousticSpace(features.values), EngineTuning(), now, 3L
        )
    }

    @Test fun oneRatingDoesNotOutvoteAHundredPlays() {
        val e = lopsided()
        val taste = e.tasteReport().topStyles.toMap()
        assertTrue(
            "taste leaned to the rated artist over the listened one: $taste",
            (taste["ישראלי"] ?: 0.0) > (taste["חסידי"] ?: 0.0)
        )
        val anchor = e.buildFeed().flatMap { it.mixes }
            .first { it.id.startsWith("mix:sound") }.songs.first()
        assertEquals(
            "\"sounds like\" was anchored on a song nobody had played",
            "ישי", anchor.artistName
        )
    }

    @Test fun theRatingStillPromotesTheArtist() {
        // The fix must not undo the reason the rating was wired in at all.
        val e = lopsided()
        val quick = e.buildFeed().first { it.id == "quick" }.songs
        assertTrue(
            "a five star artist vanished from the quick picks",
            quick.any { it.artistName == "פריד" }
        )
    }

    @Test fun ratedArtistsSeedTheSoundWhenThereIsNoListeningYet() {
        val random = Random(4)
        val songs = ArrayList<SongEntity>()
        val features = HashMap<Long, AudioFeatureEntity>()
        var id = 1L
        for ((name, level) in listOf("פריד" to 0.1f, "ישי" to 0.9f)) {
            repeat(20) { n ->
                songs.add(song(id, "$name $n", name))
                features[id] = sound(id, level, random)
                id++
            }
        }
        val artists = mapOf("פריד" to ArtistEntity("פריד", "פריד", rating = 5))
        val e = Recommender(
            songs, emptyMap(), artists, emptyMap(), emptyMap(), features,
            AcousticSpace(features.values), EngineTuning(), now, 3L
        )
        fun soundOf(name: String) = songs.filter { it.artistName == name }
            .map { s -> e.explain(s).first { it.label == "התאמת סאונד" }.value }.average()
        assertTrue(
            "with no plays at all, a rated artist should still shape the sound model",
            soundOf("פריד") > soundOf("ישי")
        )
    }
}

class SearchStaysAboutTheTextTest {
    private fun song(id: Long, title: String) = SongEntity(
        id, title, title.lowercase(), "a$id", "a$id", "al", id, 240_000, 1, 2020, null,
        "/$id", "/", 1_700_000_000L, 1
    )

    @Test fun theExactTitleWinsWhateverTheFeedThinksOfIt() {
        val now = 1_750_000_000_000L
        val exact = song(1, "אור")
        val loved = song(2, "שיר על האורות")
        val disliked = song(3, "אורות")
        val stats = mapOf(
            // Played a minute ago, so the repeat guard is holding it down.
            1L to SongStatsEntity(1, playCount = 1, lastPlayedAt = now - 60_000),
            2L to SongStatsEntity(
                2, playCount = 20, liked = 1, rating = 5, completeCount = 20,
                lastPlayedAt = now - 30 * 86_400_000L
            ),
            3L to SongStatsEntity(3, liked = -1)
        )
        val e = Recommender(
            listOf(exact, loved, disliked), stats, emptyMap(), emptyMap(), emptyMap(),
            emptyMap(), null, EngineTuning(), now, 1L
        )
        val results = e.search("אור").map { it.title }
        assertEquals("the title that was typed should come first", "אור", results.first())
        // A disliked prefix match still outranks a loved mid-word one: taste
        // reorders equals, it does not cross a tier of text match.
        assertTrue(results.indexOf("אורות") < results.indexOf("שיר על האורות"))
    }

    @Test fun tasteStillBreaksTiesBetweenEqualMatches() {
        val now = 1_750_000_000_000L
        val a = song(1, "ניגון א")
        val b = song(2, "ניגון ב")
        val stats = mapOf(2L to SongStatsEntity(2, liked = 1, rating = 5, playCount = 10))
        val e = Recommender(
            listOf(a, b), stats, emptyMap(), emptyMap(), emptyMap(), emptyMap(), null,
            EngineTuning(), now, 1L
        )
        assertEquals("ניגון ב", e.search("ניגון").first().title)
        assertEquals("ניגון א", e.search("ניגון", personal = 0.0).first().title)
    }
}
