package com.elchanan.rhythm.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

/** Turned right up, "don't repeat" holds a song down for days, not hours. */
class RepeatGuardTest {

    private fun old(guard: Float, hours: Double) = 2.6 * guard * exp(-hours / 9.0)

    @Test fun upToTheMiddleNothingChanges() {
        for (guard in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            for (hours in listOf(-3.0, 0.0, 1.0, 5.0, 24.0, 72.0, 500.0, 200_000.0)) {
                // Bit for bit: the recommendations at the default are pinned.
                assertEquals(old(guard, hours), repeatPenalty(guard, hours), 0.0)
            }
        }
    }

    @Test fun theFarEndHoldsASongDownForAWeek() {
        // Yesterday, three days ago, six days ago: still the full push.
        for (hours in listOf(24.0, 72.0, 144.0)) {
            assertEquals(2.6 * 2, repeatPenalty(2f, hours), 1e-9)
        }
        // Where it used to be nearly gone by the next day.
        assertTrue(old(2f, 24.0) < 0.4)
        // After the week it fades as before.
        assertTrue(repeatPenalty(2f, 7 * 24 + 48.0) < 0.05)
    }

    @Test fun inBetweenItHoldsInProportion() {
        // Three quarters of the way: half the week.
        assertEquals(2.6 * 1.5, repeatPenalty(1.5f, 80.0), 1e-9)
        assertTrue(repeatPenalty(1.5f, 3.5 * 24 + 36) < 0.1)
        // Never less than it was, anywhere on the slider.
        for (guard in listOf(1.1f, 1.5f, 2f)) for (hours in listOf(0.0, 10.0, 30.0, 100.0, 300.0)) {
            assertTrue(repeatPenalty(guard, hours) >= old(guard, hours))
        }
    }
}
