package com.elchanan.rhythm.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class AudioResampleTest {
    @Test fun fortyFourPointOneKBecomesExactlySixteenK() {
        val source = FloatArray(44_100) { 0.25f }
        val output = Analysis.resampleMono(source, 44_100, 16_000)
        assertEquals(16_000, output.size)
        assertTrue(output.all { abs(it - 0.25f) < 0.00001f })
    }

    @Test fun aToneRetainsItsFrequencyAndLevel() {
        val source = FloatArray(48_000) { i ->
            sin(2.0 * Math.PI * 440.0 * i / 48_000).toFloat()
        }
        val output = Analysis.resampleMono(source, 48_000, 16_000)
        assertEquals(16_000, output.size)
        val risingCrossings = (1 until output.size).count {
            output[it - 1] <= 0f && output[it] > 0f
        }
        assertTrue(risingCrossings in 438..441)
        val rms = sqrt(output.map { it.toDouble() * it }.average())
        assertTrue(rms in 0.68..0.72)
    }
}
