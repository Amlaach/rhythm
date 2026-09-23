package com.elchanan.rhythm.engine

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.util.TimeZone

/**
 * "You seem to like happy", then within the hour "you seem to like calm".
 *
 * Once the shelf has said something it keeps saying it for [Recommender.MOOD_HOLD_MS],
 * as long as that mood is still in what the listener plays; after that the
 * ordinary margin decides again. Without a time - every install from before
 * this was kept - nothing changes at all.
 */
class MoodHoldTest {

    companion object {
        private var savedZone: TimeZone? = null
        @BeforeClass @JvmStatic fun fixZone() {
            savedZone = TimeZone.getDefault()
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        }
        @AfterClass @JvmStatic fun restoreZone() { savedZone?.let { TimeZone.setDefault(it) } }

        private const val DAY = 86_400_000L
        private val lib by lazy { EngineFixture.build() }
        private val rows by lazy { lib.features.filter { it.energy > 0f } }
        private val space by lazy { AcousticSpace(rows) }

        private fun picked(lastMood: String, lastMoodAt: Long): String? {
            val now = EngineFixture.NOW
            val e = Recommender(
                lib.songs, lib.stats, ArtistStyles.withCatalogue(lib.artists, lib.songs),
                lib.affinity, lib.transitions, Recommender.leanFeatures(rows), space,
                EngineFixture.TUNING.copy(lastMood = lastMood, lastMoodAt = lastMoodAt),
                now, 7L, lib.spoken, lib.lastHeard, lib.vocal
            )
            e.buildFeed()
            return e.pickedMood
        }
    }

    @Test fun aMoodJustChosenKeepsTheShelfAndAnOldOneCanLoseIt() {
        val now = EngineFixture.NOW
        var heldByTime = 0
        for (mood in Mood.entries) {
            val byMargin = picked(mood.name, 0L)
            val settling = picked(mood.name, now - DAY)
            val settled = picked(mood.name, now - Recommender.MOOD_HOLD_MS - DAY)

            // Past the hold, exactly the old rule.
            assertEquals("$mood after the hold", byMargin, settled)
            // Whatever the margin kept, the hold keeps too.
            if (byMargin == mood.name) assertEquals("$mood in the hold", mood.name, settling)
            // In the hold the shelf either keeps its mood, or that mood is no
            // longer in the listening at all and the old rule applies.
            assertTrue("$mood in the hold gave $settling", settling == mood.name || settling == byMargin)
            if (byMargin != mood.name && settling == mood.name) heldByTime++
        }
        assertTrue("the hold never made a difference in the fixture", heldByTime > 0)
    }
}
