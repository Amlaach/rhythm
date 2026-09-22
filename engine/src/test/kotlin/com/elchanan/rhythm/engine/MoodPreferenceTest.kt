package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.db.AudioFeatureEntity
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.data.db.SongStatsEntity
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

/**
 * The feed learns which moods the listener prefers, and offers more of them
 * everywhere - not just on the one shelf that used to carry the idea.
 *
 * The trap is the base rate. A library that is mostly energetic, played on
 * shuffle, is heard mostly energetic; that is availability, not taste, and
 * the engine must not mistake one for the other.
 */
class MoodPreferenceTest {

    private val now = 1_750_000_000_000L
    private val day = 86_400_000L

    private fun song(id: Long, artist: String) = SongEntity(
        id, "שיר $id", "שיר $id", artist, Names.normalizeKey(artist), "al$id", id,
        240_000, 1, 2020, null, "/m/$id", "/m/$artist", 1_600_000_000L, 1
    )

    /** level 0 reads as calm and dark, level 1 as energetic, bright, workout. */
    private fun sound(id: Long, level: Float, r: Random) = AudioFeatureEntity(
        songId = id, analyzedAt = 1L, bpm = 70f + level * 80f + r.nextFloat() * 4f,
        bpmConfidence = 0.9f, musicalKey = 0, mode = 1,
        energy = 0.25f + level * 0.55f + r.nextFloat() * 0.02f,
        brightness = 0.15f + level * 0.45f + r.nextFloat() * 0.02f, flatness = 0.1f,
        dynamics = 0.5f + level * 0.6f, onsetRate = 0.4f + level * 1.6f,
        chroma = "1,0,0,0,0,0,0,0,0,0,0,0", timbre = "0,0,0,0,0,0,0,0,0,0,0,0",
        timbreVar = "0,0,0,0,0,0,0,0,0,0,0,0"
    )

    private class Lib(
        val calm: List<SongEntity>,
        val loud: List<SongEntity>,
        val features: Map<Long, AudioFeatureEntity>
    ) {
        val all get() = calm + loud
    }

    /** Different artists throughout, so artist listening cannot do the work. */
    private fun library(calmCount: Int, loudCount: Int): Lib {
        val r = Random(7)
        val calm = (1L..calmCount).map { song(it, "רגוע$it") }
        val loud = (1000L until 1000L + loudCount).map { song(it, "קצבי$it") }
        val features = calm.associate { it.id to sound(it.id, 0.05f, r) } +
            loud.associate { it.id to sound(it.id, 0.95f, r) }
        return Lib(calm, loud, features)
    }

    private fun engine(lib: Lib, stats: Map<Long, SongStatsEntity>) = Recommender(
        lib.all, stats, emptyMap(), emptyMap(), emptyMap(), lib.features,
        AcousticSpace(lib.features.values), EngineTuning(), now, 1L
    )

    private fun moodTerm(e: Recommender, s: SongEntity) =
        e.explain(s).firstOrNull { it.label == "התאמת מצב רוח" }?.value ?: 0.0

    private fun played(id: Long) =
        SongStatsEntity(id, playCount = 4, completeCount = 4, listenedMs = 4 * 240_000L, lastPlayedAt = now - 5 * day)

    private fun skipped(id: Long) =
        SongStatsEntity(id, playCount = 1, skipCount = 4, listenedMs = 240_000L + 4 * 5_000L, lastPlayedAt = now - 5 * day)

    @Test fun aPreferredMoodLiftsItsUnheardSongs() {
        val lib = library(20, 20)
        // Calm songs are listened to the end; loud ones are turned off.
        val stats = lib.calm.take(12).associate { it.id to played(it.id) } +
            lib.loud.take(12).associate { it.id to skipped(it.id) }
        val e = engine(lib, stats)
        val calmFresh = lib.calm.last()
        val loudFresh = lib.loud.last()
        assertTrue("an unheard calm song should gain", moodTerm(e, calmFresh) > 0.2)
        assertTrue("an unheard loud song should lose", moodTerm(e, loudFresh) < -0.2)
    }

    @Test fun aLibraryThatIsMostlyOneMoodIsNotATasteForIt() {
        // Four fifths energetic, every song heard the same way: shuffle, no
        // skips, no likes. Hearing more of it is availability, not taste.
        val lib = library(10, 40)
        val stats = lib.all.associate { it.id to played(it.id) }
        val e = engine(lib, stats)
        for (s in lib.all) {
            assertEquals("${s.title} got a mood term on evidence of availability", 0.0, moodTerm(e, s), 0.05)
        }
    }

    @Test fun aRareMoodCanStillBeTheFavourite() {
        // One song in ten is calm, and it is the one that gets played through.
        val lib = library(8, 72)
        val stats = lib.calm.take(6).associate { it.id to played(it.id) } +
            lib.loud.take(30).associate { it.id to skipped(it.id) }
        val e = engine(lib, stats)
        assertTrue(moodTerm(e, lib.calm.last()) > 0.2)
    }

    @Test fun twoSongsAreNotYetATaste() {
        val lib = library(20, 20)
        val stats = lib.calm.take(2).associate { it.id to played(it.id) } +
            lib.loud.take(2).associate { it.id to skipped(it.id) }
        val few = moodTerm(engine(lib, stats), lib.calm.last())
        val stats2 = lib.calm.take(12).associate { it.id to played(it.id) } +
            lib.loud.take(12).associate { it.id to skipped(it.id) }
        val many = moodTerm(engine(lib, stats2), lib.calm.last())
        assertTrue("a mood read off two songs should count for less ($few vs $many)", few < many)
    }

    @Test fun itIsBoundedAndTheSheetStillAddsUp() {
        val lib = library(20, 20)
        val stats = lib.calm.associate { it.id to played(it.id).copy(liked = 1, rating = 5) } +
            lib.loud.associate { it.id to skipped(it.id).copy(liked = -1) }
        val e = engine(lib, stats)
        for (s in lib.all) {
            assertTrue(kotlin.math.abs(moodTerm(e, s)) <= 0.9 + 1e-9)
            assertEquals(e.totalScore(s), e.explain(s).sumOf { it.value }, 1e-9)
        }
    }

    @Test fun withoutAnalysisThereIsNoMood() {
        val songs = (1L..20L).map { song(it, "א$it") }
        val stats = songs.take(10).associate { it.id to played(it.id) }
        val e = Recommender(songs, stats, emptyMap(), emptyMap(), emptyMap(), emptyMap(), null, EngineTuning(), now, 1L)
        assertTrue(songs.all { moodTerm(e, it) == 0.0 })
    }

    @Test fun theMoodShelfIsNotDrivenBySongsYouKeepSkipping() {
        // Loud songs played three times each but skipped thirty; calm songs
        // played through. Three plays used to be enough to count as a
        // favourite, so the skipped songs pulled towards their own moods, the
        // two sides cancelled, and the shelf left the page altogether.
        val lib = library(20, 20)
        val stats = lib.calm.take(8).associate { it.id to played(it.id) } +
            lib.loud.take(14).associate {
                it.id to SongStatsEntity(it.id, playCount = 3, skipCount = 30, listenedMs = 3 * 240_000L, lastPlayedAt = now - 5 * day)
            }
        val shelf = engine(lib, stats).buildFeed().firstOrNull { it.id.startsWith("affinity:") }
        assertEquals("affinity:${Mood.CALM.name}", shelf?.id)
    }
}
