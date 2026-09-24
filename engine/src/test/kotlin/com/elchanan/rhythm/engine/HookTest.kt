package com.elchanan.rhythm.engine

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/** A taste starts on the chorus - the part the song keeps coming back to. */
class HookTest {

    private val rate = 22050

    private fun note(midi: Int) = 440.0 * Math.pow(2.0, (midi - 69) / 12.0)

    /** [seconds] of chords, each held [each] seconds, at [loud]. */
    private fun part(chords: List<List<Int>>, each: Double, seconds: Double, loud: Double, random: Random): FloatArray {
        val n = (seconds * rate).toInt()
        return FloatArray(n) { i ->
            val t = i.toDouble() / rate
            val chord = chords[((t / each).toInt()) % chords.size]
            var v = 0.0
            for (m in chord) v += sin(2 * PI * note(m) * t)
            (loud * v / chord.size + 0.02 * (random.nextDouble() - 0.5)).toFloat()
        }
    }

    private fun framesOf(parts: List<FloatArray>): Hook.FrameSet {
        val frames = Hook.Frames(rate)
        for (p in parts) {
            var i = 0
            while (i < p.size) {
                val n = minOf(4096, p.size - i)
                frames.add(p.copyOfRange(i, i + n))
                i += n
            }
        }
        return frames.build()
    }

    @Test fun theTasteStartsOnTheChorus() {
        val r = Random(1)
        val verse = listOf(listOf(60, 64, 67), listOf(65, 69, 72), listOf(57, 60, 64), listOf(62, 65, 69))
        val chorus = listOf(listOf(67, 71, 74), listOf(62, 66, 69), listOf(64, 67, 71), listOf(60, 64, 67))
        val bridge = listOf(listOf(58, 62, 65), listOf(63, 67, 70))
        val intro = part(listOf(listOf(57, 64)), 4.0, 12.0, 0.2, r)
        val v = { part(verse, 2.0, 24.0, 0.35, r) }
        val c = { part(chorus, 2.5, 20.0, 0.6, r) }
        // intro 0-12, verse 12-36, chorus 36-56, verse 56-80, chorus 80-100,
        // bridge 100-116, chorus 116-136, outro
        val song = listOf(intro, v(), c(), v(), c(), part(bridge, 2.0, 16.0, 0.4, r), c(), part(verse, 2.0, 10.0, 0.2, r))
        val at = Hook.find(framesOf(song))
        assertNotNull(at)
        val chorusStarts = listOf(36.0, 80.0, 116.0)
        assertTrue("taste at $at, choruses at $chorusStarts", chorusStarts.any { abs(it - at!!) <= 3.0 })
    }

    @Test fun withNothingRepeatingTheLoudestMiddleStandsIn() {
        val r = Random(2)
        // Every section different, the fourth one loud.
        val parts = (0 until 8).map { k ->
            val root = 48 + k * 3
            part(listOf(listOf(root, root + 4, root + 7), listOf(root + 2, root + 5, root + 9)), 1.7, 18.0,
                if (k == 4) 0.7 else 0.2, r)
        }
        val at = Hook.find(framesOf(parts))!!
        assertTrue("taste at $at, loud part 72-90", at in 66.0..90.0)
    }

    @Test fun tooShortToTell() {
        val r = Random(3)
        assertNull(Hook.find(framesOf(listOf(part(listOf(listOf(60, 64, 67)), 1.0, 40.0, 0.4, r)))))
    }
}
