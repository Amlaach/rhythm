package com.elchanan.rhythm.desktop.audio

import com.elchanan.rhythm.engine.MusicMel
import com.elchanan.rhythm.engine.MusicPrint
import com.elchanan.rhythm.engine.SoundPrint
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Runs the models on a known signal and compares what comes out with what
 * the phone's models gave for it.
 *
 * The references (models/check.json) are the phone's own YAMNet through
 * TensorFlow Lite, and Essentia's own EffNet and heads, on the signal
 * [MusicMel.testSignal] builds - written by tools/models/to_onnx.py. So a
 * pass here says more than "the runtime loaded": it says these files, run by
 * this runtime on this machine, hear what the phone hears.
 *
 * Run by the desktop tests on Linux, and by CI inside the finished Windows
 * application image (`Rhythm.exe --check-models=<file>`), which is the only
 * place a missing DLL or a packaging mistake would show up before a user
 * found it.
 */
object ModelCheck {

    class Result(val ok: Boolean, val lines: List<String>)

    fun run(): Result {
        val lines = ArrayList<String>()
        var ok = true
        fun check(what: String, pass: Boolean, detail: String) {
            lines += (if (pass) "ok   " else "FAIL ") + what + ": " + detail
            if (!pass) ok = false
        }
        val json = ModelCheck::class.java.getResourceAsStream("/models/check.json")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
        if (json == null) return Result(false, listOf("FAIL models/check.json is missing from this build"))

        check("runtime", Models.soundAvailable(), Models.failure.ifEmpty { "loaded" })
        if (!Models.soundAvailable()) return Result(false, lines)
        check("music model", Models.musicAvailable(), Models.failure.ifEmpty { "loaded" })

        val signal = MusicMel.testSignal()

        // YAMNet, averaged over the signal as the analyser averages a song.
        val sound = Models.listenSound(signal)
        if (sound == null) {
            check("yamnet", false, "no output")
        } else {
            val want = array(json, "yamnetScores")
            val worst = want.indices.maxOf { abs(want[it] - sound.scores[it]) }
            // int8 on both sides with different rounding: a pure tone sits on
            // rounding edges, so a few hundredths at worst. A wiring mistake -
            // the wrong output, a missing bias - is tenths.
            check("yamnet scores", worst < 0.03, "largest difference from the phone %.4f".format(worst))
            val top = { v: FloatArray -> v.indices.sortedByDescending { v[it] }.take(5).toSet() }
            check("yamnet top classes", top(want) == top(sound.scores), "${top(sound.scores)} vs phone ${top(want)}")
            val print = sound.print
            if (print == null || print.size != SoundPrint.DIMS) {
                check("sound print", false, "missing")
            } else {
                val c = cos(array(json, "yamnetPrint"), print)
                check("sound print", c > 0.999, "cosine with the phone %.6f".format(c))
            }
        }

        // EffNet on the first patch, against Essentia itself.
        if (Models.musicAvailable()) {
            val frames = MusicMel().frames(signal)
            val patch = FloatArray(MusicMel.PATCH * MusicMel.BANDS)
            for (f in 0 until MusicMel.PATCH) System.arraycopy(frames[f], 0, patch, f * MusicMel.BANDS, MusicMel.BANDS)
            val embedding = Models.embedPatch(patch)
            if (embedding == null || embedding.size != MusicPrint.DIMS) {
                check("music print", false, "no output")
            } else {
                val c = cos(array(json, "effnetEmbedding0"), embedding)
                check("music print", c > 0.999, "cosine with Essentia %.6f".format(c))
                val heads = Models.headOutputs(embedding)
                val heads0 = json.substring(json.indexOf("\"heads0\""))
                var names = 0
                for ((name, out) in heads) {
                    val want = array(heads0, name)
                    val worst = want.indices.maxOf { abs(want[it] - out.getOrElse(it) { Float.NaN }) }
                    check("head $name", worst < 0.02, "largest difference from Essentia %.4f".format(worst))
                    names++
                }
                check("heads", names >= 8, "$names loaded")
            }
        }
        return Result(ok, lines)
    }

    /** For CI: runs the check, writes what it found to [out], exit code 0 when it passed. */
    fun runTo(out: File): Int {
        val r = runCatching { run() }.getOrElse { Result(false, listOf("FAIL " + it)) }
        runCatching { out.writeText(r.lines.joinToString("\n") + "\n" + (if (r.ok) "PASSED" else "FAILED") + "\n") }
        return if (r.ok) 0 else 1
    }

    /** The numbers of `"key": [ ... ]`; the file is written by the converter and has nothing else like it. */
    private fun array(json: String, key: String): FloatArray {
        val at = json.indexOf("\"$key\"")
        require(at >= 0) { "no $key in check.json" }
        val open = json.indexOf('[', at)
        val close = json.indexOf(']', open)
        return json.substring(open + 1, close).split(',').map { it.trim().toFloat() }.toFloatArray()
    }

    private fun cos(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i].toDouble(); na += a[i] * a[i].toDouble(); nb += b[i] * b[i].toDouble() }
        return dot / (sqrt(na) * sqrt(nb) + 1e-12)
    }
}
