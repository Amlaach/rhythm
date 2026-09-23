package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.db.AudioFeatureEntity
import com.elchanan.rhythm.data.db.SongEntity
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

/**
 * The daily mixes are kinds of music, not tempo bands.
 *
 * They were clustered on tempo, loudness, timbre and key - the weakest
 * thing measured - and came out as "מיקס 131 BPM". Four kinds here share
 * nothing in those numbers and differ only in what the music model heard.
 */
class DailyMixKindTest {

    private fun song(id: Long) = SongEntity(
        id, "שיר $id", "שיר $id", "אמן ${id % 31}", "artist${id % 31}", "al", id, 240_000L,
        1, 2020, null, "/m/$id", "/m", 1L, 1L
    )

    private fun kindOf(id: Long) = ((id - 1) / 60).toInt()

    private fun musicPrint(kind: Int, r: Random): String {
        val v = FloatArray(MusicPrint.DIMS) { r.nextFloat() * 0.3f }
        for (i in kind * 300 until kind * 300 + 300) v[i] = v[i] + 3f + r.nextFloat()
        return MusicPrint.pack(v)
    }

    private fun feature(id: Long, r: Random) = AudioFeatureEntity(
        songId = id, analyzedAt = 1L, bpm = 70f + r.nextFloat() * 90f, bpmConfidence = 0.8f,
        musicalKey = r.nextInt(12), mode = r.nextInt(2), energy = 0.1f + r.nextFloat() * 0.6f,
        brightness = r.nextFloat(), flatness = r.nextFloat() * 0.3f, dynamics = 0.5f + r.nextFloat(),
        onsetRate = 1f + r.nextFloat() * 4f,
        chroma = List(12) { r.nextFloat() }.joinToString(","),
        timbre = List(12) { r.nextFloat() * 4 - 2 }.joinToString(","),
        timbreVar = List(12) { r.nextFloat() }.joinToString(","),
        musicPrint = musicPrint(kindOf(id), r)
    )

    @Test fun eachDailyMixIsOneKindOfMusic() {
        val r = Random(21)
        val songs = (1L..240L).map { song(it) }
        val features = songs.associate { it.id to feature(it.id, r) }
        val engine = Recommender(
            songs = songs, stats = emptyMap(), artists = emptyMap(), affinity = emptyMap(),
            transitions = emptyMap(), features = Recommender.leanFeatures(features.values.toList()),
            acoustic = AcousticSpace(features.values), tuning = EngineTuning(),
            now = 1_790_000_000_000L, feedSeed = 1L
        )
        val mixes = engine.dailyMixes()
        assertTrue("only ${mixes.size} mixes", mixes.size >= 3)
        for (mix in mixes) {
            val kinds = mix.songs.groupingBy { kindOf(it.id) }.eachCount()
            val purity = kinds.values.max().toDouble() / mix.songs.size
            assertTrue("${mix.title} mixes kinds: $kinds", purity >= 0.9)
        }
    }
}
