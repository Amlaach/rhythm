package com.elchanan.rhythm.engine

import org.junit.Assert.*
import org.junit.Test

class EdgeDecayTest {

    private val day = 86_400_000L
    private val now = 1_790_000_000_000L

    @Test fun aYearHalvesAnEdge() {
        assertEquals(5.0, EdgeDecay.at(10.0, now - 365 * day, now), 1e-9)
        assertEquals(10.0, EdgeDecay.at(10.0, now, now), 1e-9)
        // No time stored: as it was.
        assertEquals(10.0, EdgeDecay.at(10.0, 0L, now), 1e-9)
    }

    @Test fun bumpingIsAnExactFadingSum() {
        // Heard together once two years ago and once a year ago, stored
        // step by step, is what the two would be worth now counted apart.
        val first = now - 730 * day
        val second = now - 365 * day
        val stored = EdgeDecay.bump(1.0, first, second, 1.0)
        val apart = EdgeDecay.at(1.0, first, now) + EdgeDecay.at(1.0, second, now)
        assertEquals(apart, EdgeDecay.at(stored, second, now), 1e-9)
    }

    @Test fun recentListeningOutweighsOldListening() {
        val old = EdgeDecay.at(10.0, now - 2 * 365 * day, now)
        val recent = EdgeDecay.at(4.0, now - 7 * day, now)
        assertTrue(recent > old)
    }
}
