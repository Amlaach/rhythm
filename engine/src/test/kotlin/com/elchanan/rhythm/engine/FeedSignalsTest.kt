package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.db.AudioFeatureEntity
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.data.db.SongStatsEntity
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

/**
 * The home feed reading its own evidence the wrong way round.
 *
 * Most of these share one cause: a skip was being read as a kind of play.
 * `lastPlayedAt` is moved by a skip, `playCount` is not, and between them a
 * song turned off six times was "not heard yet", a song skipped this morning
 * was "heard lately", and a run of skips set the sound of the session.
 */
class FeedSignalsTest {

    private val now = 1_750_000_000_000L
    private val day = 86_400_000L
    private val minute = 60_000L

    private fun song(id: Long, title: String, artist: String, minutes: Int = 4) = SongEntity(
        id, title, title.lowercase(), artist, Names.normalizeKey(artist), "al${id / 4}", id / 4,
        minutes * 60_000L, 1, 2020, null, "/m/$id", "/m/$artist", 1_600_000_000L, 1
    )

    private fun engine(
        songs: List<SongEntity>,
        stats: Map<Long, SongStatsEntity>,
        features: Map<Long, AudioFeatureEntity> = emptyMap(),
        spoken: Set<Long> = emptySet(),
        lastHeard: Map<Long, Long> = emptyMap()
    ) = Recommender(
        songs, stats, emptyMap(), emptyMap(), emptyMap(), features,
        if (features.size >= 8) AcousticSpace(features.values) else null,
        EngineTuning(), now, 1L, spoken, lastHeard
    )

    private fun sound(id: Long, level: Float, random: Random) = AudioFeatureEntity(
        songId = id, analyzedAt = 1L, bpm = 80f + level * 60f + random.nextFloat() * 3f,
        bpmConfidence = 0.9f, musicalKey = 0, mode = 1,
        energy = 0.3f + level * 0.5f + random.nextFloat() * 0.02f,
        brightness = 0.2f + level * 0.4f + random.nextFloat() * 0.02f, flatness = 0.1f,
        dynamics = 0.8f + level * 0.4f, onsetRate = 0.6f + level,
        chroma = "1,0,0,0,0,0,0,0,0,0,0,0", timbre = "0,0,0,0,0,0,0,0,0,0,0,0",
        timbreVar = "0,0,0,0,0,0,0,0,0,0,0,0"
    )

    private val library = (1L..40L).map { song(it, "שיר $it", "אמן${it % 5}") }

    @Test fun aSongSkippedSixTimesIsNotUndiscovered() {
        val stats = HashMap<Long, SongStatsEntity>()
        for (id in 1L..6L) {
            stats[id] = SongStatsEntity(id, skipCount = 6, listenedMs = 30_000, lastPlayedAt = now - 5 * day)
        }
        val e = engine(library, stats)
        val discover = e.buildFeed().flatMap { it.mixes }.firstOrNull { it.id == "mix:discover" }
        assertNotNull("the fixture should still produce a discovery mix", discover)
        assertFalse(
            "a song skipped six times was offered as one not heard yet",
            discover!!.songs.any { it.id <= 6L }
        )
        assertFalse(
            "a song skipped six times collected the discovery bonus",
            e.explain(library[0]).any { it.label == "גילוי" }
        )
        // And a song genuinely never touched still gets it.
        assertTrue(e.explain(library[30]).any { it.label == "גילוי" })
    }

    @Test fun skippingASongDoesNotMakeItTheOneEverythingIsBuiltAround() {
        val random = Random(2)
        val calm = (1L..20L).map { song(it, "רגוע $it", "רגוע") }
        val loud = (21L..40L).map { song(it, "קצבי $it", "קצבי") }
        val features = (calm.map { it.id to sound(it.id, 0.05f, random) } +
            loud.map { it.id to sound(it.id, 0.95f, random) }).toMap()
        val stats = HashMap<Long, SongStatsEntity>()
        calm.take(8).forEach {
            stats[it.id] = SongStatsEntity(it.id, playCount = 6, completeCount = 6, lastPlayedAt = now - 20 * day)
        }
        // Loved once, long ago - then turned off three times in the last two days.
        val old = loud[10]
        stats[old.id] = SongStatsEntity(
            old.id, playCount = 10, completeCount = 10, skipCount = 3, lastPlayedAt = now - 2 * day
        )
        // The history knows the last real play was two hundred days ago.
        val heard = stats.keys.associateWith { stats.getValue(it).lastPlayedAt } +
            (old.id to now - 200 * day)
        val anchor = engine(calm + loud, stats, features, lastHeard = heard).buildFeed()
            .flatMap { it.mixes }.first { it.id.startsWith("mix:sound") }.songs.first()
        assertNotEquals("\"sounds like\" was anchored on a song just skipped", old.id, anchor.id)
    }

    @Test fun aRunOfSkipsDoesNotSetTheSoundOfTheSession() {
        val random = Random(2)
        val calm = (1L..20L).map { song(it, "רגוע $it", "רגוע") }
        val loud = (21L..40L).map { song(it, "קצבי $it", "קצבי") }
        val features = (calm.map { it.id to sound(it.id, 0.05f, random) } +
            loud.map { it.id to sound(it.id, 0.95f, random) }).toMap()
        val stats = HashMap<Long, SongStatsEntity>()
        calm.take(8).forEach {
            stats[it.id] = SongStatsEntity(it.id, playCount = 6, completeCount = 6, lastPlayedAt = now - 5 * day)
        }
        // Five loud songs skipped one after another in the last ten minutes.
        loud.take(5).forEachIndexed { i, s ->
            stats[s.id] = SongStatsEntity(s.id, skipCount = 1, lastPlayedAt = now - (4 + i) * minute)
        }
        val e = engine(calm + loud, stats, features)
        val loudTerm = e.explain(loud[8]).firstOrNull { it.label == "מה שמתנגן עכשיו" }?.value ?: 0.0
        assertTrue("skipping loud songs pulled the feed towards loud songs ($loudTerm)", loudTerm <= 0.0)
    }

    @Test fun theFavouriteArtistIsTheOneListenedTo() {
        // Shuffle through a hundred files by one singer, skipping two for each
        // one let play; play another's ten songs eight times each to the end.
        val skipped = (1L..100L).map { song(1000 + it, "A $it", "A") }
        val loved = (1L..10L).map { song(2000 + it, "B $it", "B") }
        val stats = HashMap<Long, SongStatsEntity>()
        skipped.forEach { stats[it.id] = SongStatsEntity(it.id, playCount = 1, skipCount = 2, lastPlayedAt = now - 3 * day) }
        loved.forEach { stats[it.id] = SongStatsEntity(it.id, playCount = 8, completeCount = 8, lastPlayedAt = now - 3 * day) }
        val shelf = engine(skipped + loved, stats).buildFeed().first { it.id.startsWith("artist:") }
        assertEquals("כי אתה שומע הרבה B", shelf.title)
    }

    @Test fun shiurimDoNotTakeTheFavouriteArtistShelfOffThePage() {
        val shiurim = (1L..6L).map { song(3000 + it, "שיעור $it", "הרב", minutes = 50) }
        val loved = (1L..10L).map { song(2000 + it, "B $it", "B") }
        val stats = HashMap<Long, SongStatsEntity>()
        shiurim.forEach { stats[it.id] = SongStatsEntity(it.id, playCount = 40, lastPlayedAt = now - day) }
        loved.forEach { stats[it.id] = SongStatsEntity(it.id, playCount = 8, completeCount = 8, lastPlayedAt = now - 3 * day) }
        val feed = engine(shiurim + loved + library, stats, spoken = shiurim.map { it.id }.toSet()).buildFeed()
        assertEquals("כי אתה שומע הרבה B", feed.firstOrNull { it.id.startsWith("artist:") }?.title)
    }

    @Test fun oneStarSongsAreNotOfferedAsYours() {
        val stats = HashMap<Long, SongStatsEntity>()
        for (id in 1L..8L) stats[id] = SongStatsEntity(id, rating = 1)
        for (id in 9L..16L) stats[id] = SongStatsEntity(id, rating = 4)
        val yours = engine(library, stats).buildFeed().first { it.id == "yours" }.songs
        assertFalse("a one star song was offered as one of yours", yours.any { it.id <= 8L })
        assertTrue(yours.any { it.id in 9L..16L })
    }

    @Test fun listenAgainIsNotWhatJustPlayedNorWhatIsAlwaysSkipped() {
        val stats = HashMap<Long, SongStatsEntity>()
        for (id in 1L..10L) {
            stats[id] = SongStatsEntity(id, playCount = 5, completeCount = 5, lastPlayedAt = now - 40 * day)
        }
        stats[11] = SongStatsEntity(11, playCount = 5, completeCount = 5, lastPlayedAt = now - 10 * minute)
        stats[12] = SongStatsEntity(12, playCount = 3, skipCount = 30, lastPlayedAt = now - day)
        val again = engine(library, stats).buildFeed().first { it.id == "again" }.songs
        assertFalse("\"listen again\" offered a song heard ten minutes ago", again.any { it.id == 11L })
        assertFalse("\"listen again\" offered a song skipped thirty times", again.any { it.id == 12L })
    }

    @Test fun theWhyThisSheetAddsUpToTheScore() {
        // Checked rather than assumed: the sheet exists to explain the number,
        // and a sheet that drifts from it explains nothing.
        val random = Random(3)
        val stats = library.associate {
            it.id to SongStatsEntity(
                it.id, playCount = random.nextInt(6), skipCount = random.nextInt(4),
                completeCount = random.nextInt(3), listenedMs = random.nextLong(0, 900_000),
                lastPlayedAt = now - random.nextLong(1, 60) * 3_600_000L,
                liked = random.nextInt(-1, 2), rating = random.nextInt(0, 6),
                b0 = random.nextInt(3), b1 = random.nextInt(3), b2 = random.nextInt(3),
                b3 = random.nextInt(3), dWeekend = random.nextInt(4), dWeekday = random.nextInt(4)
            )
        }
        val e = engine(library, stats)
        for (s in library) {
            assertEquals(e.totalScore(s), e.explain(s).sumOf { it.value }, 1e-9)
        }
    }
}
