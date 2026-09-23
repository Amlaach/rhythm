package com.elchanan.rhythm.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class TrailingSilenceTest {

    private val rate = 16_000

    /** [sound] seconds of a tone, then [silence] seconds of near-nothing with a click in it. */
    private fun tail(sound: Double, silence: Double): FloatArray {
        val n = ((sound + silence) * rate).toInt()
        return FloatArray(n) { i ->
            val t = i.toDouble() / rate
            when {
                t < sound -> (0.3 * sin(2 * PI * 440 * t)).toFloat()
                i == (sound * rate).toInt() + rate -> 0.5f // one click, a second into the silence
                else -> 0.0005f
            }
        }
    }

    @Test fun movesOnWhereTheSoundEnds() {
        // The last 25 s of a 200 s track: 15 s of music, then 10 s of silence.
        val at = TrailingSilence.moveOnAt(tail(15.0, 10.0), rate, startMs = 175_000, durationMs = 200_000)
        assertEquals(190_000L + TrailingSilence.GRACE_MS, at)
    }

    @Test fun aShortPauseAtTheEndIsPartOfTheSong() {
        assertNull(TrailingSilence.moveOnAt(tail(23.5, 1.5), rate, startMs = 175_000, durationMs = 200_000))
    }

    @Test fun musicToTheLastSecondIsLeftAlone() {
        assertNull(TrailingSilence.moveOnAt(tail(25.0, 0.0), rate, startMs = 175_000, durationMs = 200_000))
    }

    @Test fun silenceThroughoutSaysNothing() {
        assertNull(TrailingSilence.moveOnAt(FloatArray(rate * 25) { 0f }, rate, startMs = 0, durationMs = 25_000))
    }
}
