package com.elchanan.rhythm.engine

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The Kotlin front end against Essentia's own, on the same signal.
 *
 * The fixture is written by tools/models/convert.py on CI, next to the
 * converted model: Essentia's TensorflowInputMusiCNN frames for the signal
 * [MusicMel.testSignal] builds. If the two disagree the model is being shown
 * a picture it was never trained on, and every reading it gives is noise.
 */
class MusicMelReferenceTest {

    private val fixture: String? =
        javaClass.classLoader.getResource("effnet_reference.json")?.readText()

    /** The numbers of `"key": [ ... ]`, without a JSON library in the engine. */
    private fun array(json: String, key: String): FloatArray {
        val at = json.indexOf("\"$key\"")
        require(at >= 0) { "no $key" }
        val open = json.indexOf('[', at)
        val close = json.indexOf(']', open)
        return json.substring(open + 1, close).split(',').map { it.trim().toFloat() }.toFloatArray()
    }

    private fun flag(json: String, key: String): Boolean =
        Regex("\"$key\"\\s*:\\s*(true|false)").find(json)!!.groupValues[1] == "true"

    private fun number(json: String, key: String): Int =
        Regex("\"$key\"\\s*:\\s*(\\d+)").find(json)!!.groupValues[1].toInt()

    @Test fun framesMatchEssentia() {
        val json = fixture
        assumeTrue("no reference fixture yet", json != null)
        json!!
        val mel = MusicMel(startFromZero = flag(json, "startFromZero"))
        val frames = mel.frames(MusicMel.testSignal())
        assertEquals(number(json, "frames"), frames.size)
        val melAt = json.substring(json.indexOf("\"melAt\""))
        var worst = 0.0
        for (i in listOf(0, 1, 2, 50, 300, frames.size - 1)) {
            val expected = array(melAt, i.toString())
            val mine = frames[i]
            assertEquals(MusicMel.BANDS, expected.size)
            for (b in 0 until MusicMel.BANDS) {
                worst = maxOf(worst, kotlin.math.abs((mine[b] - expected[b]).toDouble()))
            }
        }
        // log10 bands run 0..~4; a hundredth is far inside what the model can feel
        assertTrue("largest difference from Essentia: $worst", worst < 0.01)
    }
}
