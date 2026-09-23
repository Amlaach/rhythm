package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.db.AudioFeatureEntity
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.data.db.SongStatsEntity
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

/**
 * "מיקס קצבי" is the קצבי the mood lists mean, marks and all.
 *
 * It was "the tempo estimate is over 112", and the estimate answers even
 * when it has nothing to go on: a slow niggun in free time comes back as a
 * confident-looking 120. The mood model knows how sure the detector was.
 */
class TempoMixMoodTest {

    private fun song(id: Long) = SongEntity(
        id, "שיר $id", "שיר $id", "אמן ${id % 13}", "artist${id % 13}", "al", id, 240_000L,
        1, 2020, null, "/m/$id", "/m", 1L, 1L
    )

    private fun feature(id: Long, bpm: Float, confidence: Float, onsets: Float, energy: Float, r: Random) =
        AudioFeatureEntity(
            songId = id, analyzedAt = 1L, bpm = bpm, bpmConfidence = confidence,
            musicalKey = 0, mode = 1, energy = energy, brightness = 0.3f + r.nextFloat() * 0.3f,
            flatness = 0.1f, dynamics = 1f, onsetRate = onsets,
            chroma = "1,0,0,0,0,0,0,0,0,0,0,0", timbre = "0,0,0,0,0,0,0,0,0,0,0,0",
            timbreVar = "0,0,0,0,0,0,0,0,0,0,0,0"
        )

    // 1..15: really driving. 16..30: slow, and read at twice their tempo by an unsure detector.
    // 31..60: in between.
    private val r = Random(8)
    private val songs = (1L..60L).map { song(it) }
    private val features = songs.associate { s ->
        s.id to when {
            s.id <= 15 -> feature(s.id, 140f + r.nextFloat() * 10f, 0.9f, 5f + r.nextFloat(), 0.7f, r)
            s.id <= 30 -> feature(s.id, 118f + r.nextFloat() * 6f, 0.1f, 1.3f + r.nextFloat() * 0.1f, 0.12f, r)
            else -> feature(s.id, 95f + r.nextFloat() * 10f, 0.6f, 2.5f + r.nextFloat(), 0.35f, r)
        }
    }

    private fun fastMix(stats: Map<Long, SongStatsEntity> = emptyMap()) = Recommender(
        songs = songs, stats = stats, artists = emptyMap(), affinity = emptyMap(), transitions = emptyMap(),
        features = features, acoustic = AcousticSpace(features.values), tuning = EngineTuning(),
        now = 1_790_000_000_000L, feedSeed = 1L
    ).buildFeed().flatMap { it.mixes }.firstOrNull { it.id == "mix:tempo:fast" }

    @Test fun aSlowSongReadAtDoubleTempoIsNotInTheFastMix() {
        val mix = fastMix()
        assertNotNull(mix)
        assertTrue("the driving songs should be there", mix!!.songs.count { it.id <= 15 } >= 10)
        assertEquals("slow songs read at 120 by an unsure detector", 0, mix.songs.count { it.id in 16L..30L })
    }

    @Test fun aSongMarkedNotEnergeticLeavesTheFastMix() {
        val marks = mapOf(3L to SongStatsEntity(3L, moods = MoodMarks.encode(mapOf(Mood.ENERGETIC to false))))
        val mix = fastMix(marks)
        assertNotNull(mix)
        assertFalse(mix!!.songs.any { it.id == 3L })
    }
}
