package com.elchanan.rhythm.engine

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * The mel front end Discogs-EffNet reads. The exact numbers are checked against
 * Essentia's own output when the converter's fixture is present; these are the
 * properties that hold regardless.
 */
class MusicMelTest {

    @Test fun framesAndPatchesAreCountedAsEssentiaCountsThem() {
        val mel = MusicMel()
        val frames = mel.frames(MusicMel.testSignal())
        // Essentia, same signal: 1874 frames starting from zero, 29 patches
        assertEquals(1874, frames.size)
        assertEquals(29, mel.patches(frames).size)
        assertEquals(1876, MusicMel(startFromZero = false).frames(MusicMel.testSignal()).size)
    }

    @Test fun aToneLightsTheBandItBelongsTo() {
        val tone = FloatArray(16000) { (0.5 * sin(2 * PI * 1000.0 * it / 16000)).toFloat() }
        val frame = MusicMel().frames(tone)[10]
        val loudest = frame.indices.maxBy { frame[it] }
        // 1 kHz is where Slaney's scale turns from linear to logarithmic: 15 of 40.03 mel
        val expected = (MusicMel.slaney(1000.0) / MusicMel.slaney(8000.0) * 97).toInt() - 1
        assertTrue("loudest band $loudest, expected near $expected", kotlin.math.abs(loudest - expected) <= 1)
        assertEquals(0f, MusicMel().frames(FloatArray(4000))[0].max(), 0f)
    }

    @Test fun theSlaneyScaleRoundTrips() {
        for (hz in listOf(0.0, 440.0, 999.0, 1000.0, 3000.0, 8000.0)) {
            assertEquals(hz, MusicMel.unslaney(MusicMel.slaney(hz)), 1e-6)
        }
    }
}
