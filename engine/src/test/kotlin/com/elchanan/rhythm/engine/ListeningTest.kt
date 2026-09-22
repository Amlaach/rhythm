package com.elchanan.rhythm.engine

import org.junit.Assert.*
import org.junit.Test

class ListeningTest {

    private val threeMinutes = 180_000L

    @Test fun touchingASongIsNeitherAPlayNorASkip() {
        // The reported complaint: a song lit for a second counted as played.
        // It is not a play, and it is not a rejection either - it is someone
        // looking for something else, and the honest record is no record.
        for (heard in listOf(0L, 500L, 2_999L)) {
            assertFalse(Listening.countsAsPlay(heard, threeMinutes, endedOnItsOwn = false))
            assertFalse(Listening.countsAsSkip(heard, threeMinutes, endedOnItsOwn = false))
        }
    }

    @Test fun theBarIsAdjustableAndClamped() {
        val ten = Listening.minimumMsOf(10)
        assertFalse(Listening.countsAsPlay(9_000L, threeMinutes, false, ten))
        assertFalse(Listening.countsAsSkip(9_000L, threeMinutes, false, ten))
        assertTrue(Listening.countsAsSkip(11_000L, threeMinutes, false, ten))

        // Nothing a screen can produce may turn the bar into something absurd.
        assertEquals(0L, Listening.minimumMsOf(-5))
        assertEquals(Listening.MAX_MINIMUM_SEC * 1000L, Listening.minimumMsOf(9999))
        // Zero means off: anything heard at all is measured.
        assertTrue(Listening.countsAsSkip(1L, threeMinutes, false, Listening.minimumMsOf(0)))
    }

    @Test fun halfATrackOrNinetySecondsIsAPlay() {
        assertTrue(Listening.countsAsPlay(90_000L, threeMinutes, false))
        assertFalse(Listening.countsAsPlay(80_000L, threeMinutes, false))
        // An hour of speech needs ninety seconds, not half an hour.
        assertTrue(Listening.countsAsPlay(90_000L, 3_600_000L, false))
    }

    @Test fun aShortTrackPlayedToItsEndIsAPlayOnBothPlatforms() {
        // The clause the phone was missing. A forty second interlude in a file
        // with no duration tag reaches neither threshold and was still heard
        // in full: the desktop counted it, the phone did not, and the two
        // builds then ranked the same library differently.
        assertTrue(Listening.countsAsPlay(40_000L, 0L, endedOnItsOwn = true))
        assertFalse(Listening.countsAsPlay(40_000L, 0L, endedOnItsOwn = false))
        // And a track that ended on its own is never also a skip.
        assertFalse(Listening.countsAsSkip(40_000L, 0L, endedOnItsOwn = true))
    }

    @Test fun leavingATrackEarlyIsASkipAndTheTwoAreNeverBoth() {
        assertTrue(Listening.countsAsSkip(20_000L, threeMinutes, false))
        for (heard in listOf(4_000L, 20_000L, 95_000L, threeMinutes)) {
            val play = Listening.countsAsPlay(heard, threeMinutes, false)
            val skip = Listening.countsAsSkip(heard, threeMinutes, false)
            assertFalse("$heard counted as both", play && skip)
        }
    }
}
