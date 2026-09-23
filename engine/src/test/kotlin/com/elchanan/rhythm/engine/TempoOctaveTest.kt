package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.db.AudioFeatureEntity
import org.junit.Assert.*
import org.junit.Test

/**
 * A tempo read at twice or half its value is the detector's commonest
 * mistake, and it was scored as the furthest two songs can be apart.
 */
class TempoOctaveTest {

    private fun row(id: Long, bpm: Float, confidence: Float) = AudioFeatureEntity(
        songId = id, analyzedAt = 1L, bpm = bpm, bpmConfidence = confidence, musicalKey = 0, mode = 1,
        energy = 0.3f, brightness = 0.4f, flatness = 0.1f, dynamics = 1f, onsetRate = 2f,
        chroma = "1,0,0,0,0,0,0,0,0,0,0,0", timbre = "0,0,0,0,0,0,0,0,0,0,0,0",
        timbreVar = "0,0,0,0,0,0,0,0,0,0,0,0"
    )

    private fun distance(a: Float, b: Float, confidence: Float): Double {
        val space = AcousticSpace(listOf(row(1, a, confidence), row(2, b, confidence)))
        return space.tempoDistance(1, 2)
    }

    @Test fun anUnsureOctaveIsNotFarApart() {
        assertEquals(0.0, distance(70f, 140f, 0.1f), 0.11)
        assertEquals(0.0, distance(140f, 70f, 0.0f), 1e-9)
    }

    @Test fun aSureOctaveStillIs() {
        assertEquals(1.0, distance(70f, 140f, 1f), 1e-9)
    }

    @Test fun nearTempiAreMeasuredAsBefore() {
        // Under half an octave the two measures agree, however sure.
        val before = kotlin.math.abs(kotlin.math.ln(100.0 / 120.0)) / kotlin.math.ln(2.0)
        assertEquals(before, distance(100f, 120f, 0.1f), 1e-6)
        assertEquals(before, distance(100f, 120f, 0.9f), 1e-6)
    }
}
