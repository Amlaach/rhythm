package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.db.AudioFeatureEntity
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

/**
 * A pair measured by YAMNet's print is not closer than a pair measured by
 * the music model's only because YAMNet's numbers run higher.
 *
 * Half this library has music prints and half is still waiting for them, the
 * state of every library while an update's analysis runs.
 */
class SimilarityScaleTest {

    private fun print(dims: Int, r: Random, noise: Float): FloatArray {
        val base = FloatArray(dims) { 1f }
        return FloatArray(dims) { base[it] + r.nextFloat() * noise }
    }

    /** A print leaning towards one region, as in MoodTeachingTest. */
    private fun leaning(dims: Int, region: Int, r: Random): FloatArray {
        val v = FloatArray(dims) { r.nextFloat() * 0.3f }
        for (i in region * 100 until region * 100 + 100) v[i] = v[i] + 3f + r.nextFloat()
        return v
    }

    private fun row(id: Long, r: Random, withMusic: Boolean) = AudioFeatureEntity(
        songId = id, analyzedAt = 1L, bpm = 100f, bpmConfidence = 0.5f, musicalKey = 0, mode = 1,
        energy = 0.3f, brightness = 0.4f, flatness = 0.1f, dynamics = 1f, onsetRate = 2f,
        chroma = "1,0,0,0,0,0,0,0,0,0,0,0", timbre = "0,0,0,0,0,0,0,0,0,0,0,0",
        timbreVar = "0,0,0,0,0,0,0,0,0,0,0,0",
        // To YAMNet the songs still waiting for a music print all sound
        // alike, and unlike the others; the music model's prints are spread.
        soundPrint = SoundPrint.pack(leaning(SoundPrint.DIMS, if (withMusic) 1 else 6, r)),
        musicPrint = if (withMusic) MusicPrint.pack(print(MusicPrint.DIMS, r, 3f)) else ""
    )

    @Test fun eachMeasureSitsOnTheSameScale() {
        val r = Random(2)
        val rows = (1L..80L).map { row(it, r, withMusic = it <= 40) }
        val space = AcousticSpace(rows)
        fun meanOver(ids: LongRange): Double {
            val list = ids.toList()
            var sum = 0.0
            var n = 0
            for (i in list.indices) for (j in i + 1 until list.size) {
                sum += space.similarity(list[i], list[j]); n++
            }
            return sum / n
        }
        val byMusic = meanOver(1L..40L)
        val bySound = meanOver(41L..80L)
        assertEquals("music-measured pairs $byMusic, sound-measured $bySound", byMusic, bySound, 0.05)
    }

    @Test fun aLibraryMeasuredOneWayIsUntouched() {
        // Every song has a music print: nothing is rescaled.
        val r = Random(3)
        val rows = (1L..30L).map { row(it, r, withMusic = true) }
        val space = AcousticSpace(rows)
        val a = space.musicPrints.getValue(1L)
        val b = space.musicPrints.getValue(2L)
        var dot = 0.0
        for (i in a.indices) dot += a[i] * b[i]
        assertEquals(kotlin.math.exp(-(1.0 - dot.coerceIn(-1.0, 1.0)) / 0.8), space.similarity(1L, 2L), 1e-12)
    }
}
