package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.data.db.SongStatsEntity
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

/**
 * The report card, and weights learned from the listener rather than guessed.
 */
class SignalCalibrationTest {

    private val now = 1_750_000_000_000L

    private fun song(id: Long, artist: String) = SongEntity(
        id, "שיר $id", "שיר $id", artist, Names.normalizeKey(artist), "al$id", id,
        240_000L, 1, 2020, null, "/m/$id", "/m/$artist", 1_600_000_000L, 1
    )

    /** Rows whose answer follows one signal, with the others noise. */
    private fun rows(follows: Int, count: Int = 200, seed: Int = 3): List<SignalCalibration.Row> {
        val random = Random(seed)
        return (0 until count).map { i ->
            val x = DoubleArray(5) { random.nextDouble(-1.0, 1.0) }
            val positive = x[follows] + random.nextDouble(-0.3, 0.3) > 0
            SignalCalibration.Row("artist${i % 23}", x, positive)
        }
    }

    @Test fun aucIsACoinTossForNoiseAndOneForAPerfectSignal() {
        assertEquals(1.0, SignalCalibration.auc(listOf(0.1, 0.2, 0.8, 0.9), listOf(false, false, true, true))!!, 1e-9)
        assertEquals(0.0, SignalCalibration.auc(listOf(0.9, 0.8, 0.1), listOf(false, false, true))!!, 1e-9)
        // ties count half: a signal that is silent about everything says nothing
        assertEquals(0.5, SignalCalibration.auc(listOf(0.0, 0.0, 0.0, 0.0), listOf(false, true, false, true))!!, 1e-9)
        assertNull(SignalCalibration.auc(listOf(1.0, 2.0), listOf(true, true)))
    }

    @Test fun aListenerWhoseTasteFollowsTheSoundGetsTheSoundWeightedUp() {
        val report = SignalCalibration.run(rows(follows = 3))
        val learned = report.weights!!.toArray()
        val defaults = SignalWeights.DEFAULT.toArray()
        val ratio = DoubleArray(5) { learned[it] / defaults[it] }
        assertTrue("sound should gain the most: ${ratio.toList()}", ratio.indices.maxBy { ratio[it] } == 3)
        assertTrue(ratio[3] > 1.5)
        assertTrue(
            "held-out learned ${report.learnedAuc} vs current ${report.currentAuc}",
            report.learnedAuc!! > report.currentAuc!! + 0.05
        )
        assertTrue(report.accepted)
        // the total stays the size of the defaults, so the rest of the score keeps its proportion
        assertEquals(defaults.sum(), learned.sum(), 1e-6)
        // and no signal is switched off entirely
        for (i in 0 until 5) assertTrue(learned[i] >= defaults[i] * 0.2)
    }

    @Test fun aListenerWhoseTasteFollowsTheArtistGetsADifferentWeighting() {
        val byArtist = SignalCalibration.run(rows(follows = 1)).weights!!.toArray()
        val bySound = SignalCalibration.run(rows(follows = 3)).weights!!.toArray()
        assertTrue(byArtist[1] > bySound[1])
        assertTrue(bySound[3] > byArtist[3])
    }

    @Test fun tooLittleListeningLearnsNothing() {
        val report = SignalCalibration.run(rows(follows = 3, count = 20))
        assertNull(report.weights)
        assertFalse(report.accepted)
        assertTrue(SignalCalibration.describe(report).contains("לפחות"))
    }

    @Test fun weightsThatDoNotBeatTheDefaultsAreNotAccepted() {
        // the answers follow the defaults' own mix exactly: nothing to gain
        val random = Random(9)
        val d = SignalWeights.DEFAULT.toArray()
        val rows = (0 until 300).map { i ->
            val x = DoubleArray(5) { random.nextDouble(-1.0, 1.0) }
            val s = (0 until 5).sumOf { d[it] * x[it] }
            SignalCalibration.Row("a${i % 30}", x, s > 0)
        }
        val report = SignalCalibration.run(rows)
        assertFalse("learned ${report.learnedAuc} current ${report.currentAuc}", report.accepted)
    }

    @Test fun theBlindStyleMatchDoesNotCountASongsOwnListening() {
        val songs = (1L..30L).map { song(it, "אמן${it % 6}") }
        val styles = mapOf(1L to "ייחודי", 2L to "ייחודי")
        val stats = HashMap<Long, SongStatsEntity>()
        for (s in songs) stats[s.id] = SongStatsEntity(s.id, styles = styles[s.id] ?: "")
        stats[1L] = SongStatsEntity(1L, playCount = 20, completeCount = 20, listenedMs = 20 * 240_000L, lastPlayedAt = now - 86_400_000L * 3, styles = "ייחודי")
        val e = Recommender(
            songs, stats, emptyMap(), emptyMap(), emptyMap(), emptyMap(), null,
            EngineTuning(), now, 1L, emptySet(), emptyMap()
        )
        assertEquals(
            "the loved song's style match must not come from its own plays",
            0.0, e.blindSignals(songs[0])[2], 1e-9
        )
        assertTrue("its sibling in the same style is predicted by it", e.blindSignals(songs[1])[2] > 0.3)
        assertEquals(true, e.verdict(1L))
        assertNull(e.verdict(5L))
    }

    @Test fun learnedWeightsMoveTheScoreAndTheExplanationStillAddsUp() {
        val songs = (1L..30L).map { song(it, "אמן${it % 6}") }
        val stats = HashMap<Long, SongStatsEntity>()
        for (id in 1L..10L) {
            stats[id] = SongStatsEntity(id, playCount = 5, completeCount = 5, listenedMs = 5 * 240_000L, lastPlayedAt = now - 86_400_000L * 4)
        }
        fun engine(learned: SignalWeights?) = Recommender(
            songs, stats, emptyMap(), emptyMap(), emptyMap(), emptyMap(), null,
            EngineTuning(learned = learned), now, 1L, emptySet(), emptyMap()
        )
        val plain = engine(null)
        val artistHeavy = engine(SignalWeights(1.35, 3.0, 1.55, 1.15, 0.9))
        val target = songs[16] // by an artist with listened songs, never heard itself
        assertNotEquals(plain.totalScore(target), artistHeavy.totalScore(target), 1e-6)
        assertTrue(artistHeavy.totalScore(target) > plain.totalScore(target))
        for (s in songs) {
            assertEquals(artistHeavy.totalScore(s), artistHeavy.explain(s).sumOf { it.value }, 1e-6)
        }
        assertEquals(SignalWeights(1.0, 2.0, 3.0, 4.0, 5.0), SignalWeights.decode(SignalWeights(1.0, 2.0, 3.0, 4.0, 5.0).encode()))
        assertNull(SignalWeights.decode("1,2,x"))
    }
}
