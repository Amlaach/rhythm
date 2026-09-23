package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.db.SongEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A set of several songs is often titled after the first of them, and its
 * length is what gives it away. With a length set, anything at least that
 * long is kept out of what the engine generates, as a titled medley is;
 * without one, nothing changes (EngineGoldenTest holds that part).
 */
class MedleyLengthTest {

    private fun engine(lib: EngineFixture.Library, songs: List<SongEntity>, minutes: Int): Recommender {
        val rows = lib.features.filter { it.energy > 0f }
        return Recommender(
            songs = songs,
            stats = lib.stats,
            artists = ArtistStyles.withCatalogue(lib.artists, songs),
            affinity = lib.affinity,
            transitions = lib.transitions,
            features = Recommender.leanFeatures(rows),
            acoustic = AcousticSpace(rows),
            tuning = EngineFixture.TUNING.copy(medleyMinutes = minutes),
            now = EngineFixture.NOW,
            feedSeed = 7L,
            spoken = lib.spoken,
            lastHeard = lib.lastHeard,
            vocal = lib.vocal
        )
    }

    private fun generated(e: Recommender, seeds: List<SongEntity>): Set<Long> {
        val out = HashSet<Long>()
        for (section in e.buildFeed()) {
            section.songs.forEach { out += it.id }
            section.mixes.forEach { m -> m.songs.forEach { out += it.id } }
        }
        for (seed in seeds) e.radio(seed, 60).forEach { out += it.id }
        return out
    }

    @Test fun aLongTrackIsAMedleyOnlyWhenALengthIsSet() {
        val lib = EngineFixture.build()
        val seeds = lib.songs.take(40)
        // The songs the engine offers most readily, stretched to twenty minutes.
        val offered = generated(engine(lib, lib.songs, 0), seeds)
        val long = lib.songs.filter { it.id in offered && it !in seeds }.take(30).mapTo(HashSet()) { it.id }
        assertTrue("the fixture offers enough songs to stretch", long.size >= 20)
        val songs = lib.songs.map { if (it.id in long) it.copy(durationMs = 20 * 60_000L) else it }

        val off = generated(engine(lib, songs, 0), seeds)
        assertTrue("without a length a long track is offered as before", off.any { it in long })

        val on = generated(engine(lib, songs, 15), seeds)
        assertFalse("at 15 minutes none of the 20 minute tracks is generated", on.any { it in long })

        val above = generated(engine(lib, songs, 25), seeds)
        assertTrue("at 25 minutes the 20 minute tracks are not medleys", above.any { it in long })
    }
}
