package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.db.AudioFeatureEntity
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.data.db.SongStatsEntity
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

/**
 * What is kept out of every shelf is kept out of what the shelves learn from.
 *
 * A shiur played every day never reached the feed, and still opened the
 * "same sound" mix, seeded what "sounds like what you love" meant and set the
 * scale every other song's familiarity was measured on.
 */
class TasteLeakTest {

    private val now = 1_790_000_000_000L
    private val day = 86_400_000L

    private fun song(id: Long, title: String) = SongEntity(
        id, title, title.lowercase(), "אמן ${id % 9}", "artist${id % 9}", "al", id / 5, 240_000L,
        1, 2020, null, "/m/$id", "/m", 1L, 1L
    )

    private fun feature(id: Long, r: Random) = AudioFeatureEntity(
        songId = id, analyzedAt = 1L, bpm = 90f + r.nextFloat() * 60f, bpmConfidence = 0.8f,
        musicalKey = r.nextInt(12), mode = r.nextInt(2), energy = 0.2f + r.nextFloat() * 0.5f,
        brightness = r.nextFloat(), flatness = r.nextFloat() * 0.3f, dynamics = 0.5f + r.nextFloat(),
        onsetRate = 1f + r.nextFloat() * 4f,
        chroma = List(12) { r.nextFloat() }.joinToString(","),
        timbre = List(12) { r.nextFloat() * 4 - 2 }.joinToString(","),
        timbreVar = List(12) { r.nextFloat() }.joinToString(",")
    )

    private val lecture = song(1, "שיעור")
    private val music = (2L..60L).map { song(it, "ניגון $it") }
    private val all = listOf(lecture) + music
    private val r = Random(3)
    private val features = all.associate { it.id to feature(it.id, r) }

    private fun engine(stats: Map<Long, SongStatsEntity>, spoken: Set<Long> = setOf(lecture.id)) = Recommender(
        songs = all, stats = stats, artists = emptyMap(), affinity = emptyMap(), transitions = emptyMap(),
        features = features, acoustic = AcousticSpace(features.values), tuning = EngineTuning(),
        now = now, feedSeed = 1L, spoken = spoken
    )

    @Test fun aShiurPlayedDailyDoesNotOpenTheSameSoundMix() {
        val stats = HashMap<Long, SongStatsEntity>()
        stats[lecture.id] = SongStatsEntity(lecture.id, playCount = 300, completeCount = 300, lastPlayedAt = now - day)
        for (id in 2L..12L) stats[id] = SongStatsEntity(id, playCount = 6, completeCount = 6, lastPlayedAt = now - 5 * day)
        val mixes = engine(stats).buildFeed().flatMap { it.mixes }
        val sound = mixes.firstOrNull { it.id.startsWith("mix:sound:") }
        assertNotNull("the same-sound mix should be there", sound)
        assertFalse("a shiur opened the same-sound mix", sound!!.songs.any { it.id == lecture.id })
        assertNotEquals("mix:sound:${lecture.id}", sound.id)
    }

    @Test fun aShiurDoesNotSetTheFamiliarityScale() {
        val stats = HashMap<Long, SongStatsEntity>()
        for (id in 2L..12L) stats[id] = SongStatsEntity(id, playCount = 20, completeCount = 20, lastPlayedAt = now - 5 * day)
        val quiet = engine(stats)
        stats[lecture.id] = SongStatsEntity(lecture.id, playCount = 2000, completeCount = 2000, lastPlayedAt = now - day)
        val loud = engine(stats)
        // A song's familiarity is measured against the most played song. The
        // lecture must not be that song.
        for (id in 2L..12L) {
            val s = music.first { it.id == id }
            assertEquals(quiet.totalScore(s), loud.totalScore(s), 1e-9)
        }
    }

    /** The discovery term of a song nobody has touched, read off its breakdown. */
    private fun discovery(e: Recommender): Double =
        e.explain(music.last()).first { it.label == "גילוי" }.value

    @Test fun oldSkippingNoLongerWidensTheFeedForGood() {
        val stats = HashMap<Long, SongStatsEntity>()
        // A year ago: skipped almost everything. This month: heard it all through.
        for (id in 2L..40L) {
            stats[id] = SongStatsEntity(
                id, playCount = 2, skipCount = 20, completeCount = 2,
                lastPlayedAt = now - 3 * day, lastSkipAt = now - 400 * day
            )
        }
        val settled = discovery(engine(stats))
        assertEquals("the dial alone, since nothing is being skipped now", 1.1 * EngineTuning().discovery, settled, 1e-6)

        // And skipping now does widen it.
        for (id in 2L..40L) {
            stats[id] = stats.getValue(id).copy(lastSkipAt = now - 3 * day)
        }
        assertTrue(discovery(engine(stats)) > settled + 0.1)
    }
}
