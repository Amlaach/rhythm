package com.elchanan.rhythm.engine

import android.content.Context
import android.content.res.AssetFileDescriptor
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Names what a recording contains, using YAMNet.
 *
 * The rest of the analyser measures: tempo, key, energy, timbre. None of that
 * says whether there is a choir, an accordion, or a voice at all. This does,
 * and it is the difference between a library the app can sort and a library it
 * understands.
 *
 * YAMNet is a MobileNet trained on AudioSet - 3.7M weights, 69M multiplies per
 * frame, which is small enough that a phone gets through a song in about a
 * second. It returns a score for each of 521 sound classes, and among them are
 * the ones this repertoire actually turns on: Middle Eastern music, Wedding
 * music, Choir, Chant, Accordion, Clarinet, along with Happy, Sad, Tender and
 * Exciting music.
 *
 * Both the model and its weights are Apache 2.0. The app also bundles
 * separate MTG-UPF music models under CC BY-NC-SA 4.0; their attribution
 * and conversion notice are in assets/THIRD_PARTY_NOTICES.txt.
 */
class AudioTagger private constructor(private val interpreter: Interpreter) {

    /** Samples the model expects per call, read from the model itself. */
    private val frameSamples: Int = runCatching {
        interpreter.getInputTensor(0).shape().last()
    }.getOrDefault(DEFAULT_FRAME)

    /** Number of classes, likewise read rather than assumed. */
    private val classCount: Int = runCatching {
        interpreter.getOutputTensor(0).shape().last()
    }.getOrDefault(CLASS_COUNT)

    /**
     * The second output, when the model has one: the 1024 channel layer the
     * classifier sits on top of, which is what a song sounds like in the
     * model's own terms.
     *
     * The published classification model does not expose it. The copy
     * shipped here is that same file with this one internal tensor also
     * listed as an output - same weights, same scores to the bit, which was
     * checked on the desktop before it was committed. Any model without it
     * simply produces no print, and everything else goes on as before.
     */
    private val print: PrintOutput? = runCatching {
        if (interpreter.outputTensorCount < 2) return@runCatching null
        val tensor = interpreter.getOutputTensor(1)
        if (tensor.numElements() != SoundPrint.DIMS) return@runCatching null
        when (tensor.dataType()) {
            DataType.INT8 -> {
                val q = tensor.quantizationParams()
                PrintOutput(quantised = true, scale = q.scale, zeroPoint = q.zeroPoint)
            }
            DataType.FLOAT32 -> PrintOutput(quantised = false, scale = 1f, zeroPoint = 0)
            else -> null
        }
    }.getOrNull()

    private class PrintOutput(val quantised: Boolean, val scale: Float, val zeroPoint: Int) {
        val bytes: Int get() = if (quantised) SoundPrint.DIMS else SoundPrint.DIMS * 4
    }

    /** What the model heard in a recording. */
    class Heard(val scores: FloatArray, val print: FloatArray?)

    /**
     * Scores for a mono 16 kHz waveform, averaged over its length.
     *
     * Averaged rather than taken at a single point because a song is not one
     * sound: an intro can be a solo voice and the chorus a full band, and the
     * question being asked is what the track contains, not what its fourth
     * second contains. Mean over frames answers that; a maximum would let one
     * stray moment of applause label the whole recording.
     */
    fun listen(waveform16k: FloatArray): Heard? {
        if (waveform16k.size < frameSamples) return null
        val total = FloatArray(classCount)
        var frames = 0

        val input = ByteBuffer
            .allocateDirect(frameSamples * 4)
            .order(ByteOrder.nativeOrder())
        val output = Array(1) { FloatArray(classCount) }
        val printOut = print
        val printBuffer = printOut?.let {
            ByteBuffer.allocateDirect(it.bytes).order(ByteOrder.nativeOrder())
        }
        val printTotal = if (printOut != null) FloatArray(SoundPrint.DIMS) else null

        var offset = 0
        while (offset + frameSamples <= waveform16k.size) {
            input.rewind()
            for (i in 0 until frameSamples) {
                input.putFloat(waveform16k[offset + i])
            }
            input.rewind()
            val outputs = HashMap<Int, Any>(2)
            outputs[0] = output
            if (printBuffer != null) {
                printBuffer.rewind()
                outputs[1] = printBuffer
            }
            val ok = runCatching {
                interpreter.runForMultipleInputsOutputs(arrayOf<Any>(input), outputs)
            }.isSuccess
            if (!ok) return null
            val row = output[0]
            for (c in 0 until classCount) total[c] += row[c]
            if (printOut != null && printBuffer != null && printTotal != null) {
                printBuffer.rewind()
                for (i in 0 until SoundPrint.DIMS) {
                    printTotal[i] += if (printOut.quantised) {
                        printOut.scale * (printBuffer.get().toInt() - printOut.zeroPoint)
                    } else {
                        printBuffer.float
                    }
                }
            }
            frames++
            // Half-frame hop, matching how the model was trained to be applied.
            offset += frameSamples / 2
        }
        if (frames == 0) return null
        val divisor = frames.toFloat()
        for (c in 0 until classCount) total[c] = total[c] / divisor
        printTotal?.let { p -> for (i in p.indices) p[i] = p[i] / divisor }
        return Heard(total, printTotal)
    }

    fun close() = runCatching { interpreter.close() }.let { }

    companion object {
        private const val ASSET = "yamnet.tflite"

        /** 0.975 s at 16 kHz, the published frame, used only as a fallback. */
        private const val DEFAULT_FRAME = 15600
        private const val CLASS_COUNT = 521

        /** The sample rate the model was trained at. Not negotiable. */
        const val SAMPLE_RATE = 16000

        /**
         * [threads]: two normally - the analyser already runs several songs
         * in sequence on a background thread, and taking every core would
         * make the interface stutter while a library is scanned. More while
         * the phone charges; see [AudioAnalyzer.threadsFor].
         */
        fun create(context: Context, threads: Int = 2): AudioTagger? = runCatching {
            val options = Interpreter.Options().apply { setNumThreads(threads) }
            AudioTagger(Interpreter(loadModel(context), options))
        }.getOrNull()

        /**
         * Maps the asset straight out of the apk rather than copying it.
         *
         * This is why the build is told not to compress .tflite - a compressed
         * asset has no contiguous region to map and the interpreter cannot open
         * it at all.
         */
        private fun loadModel(context: Context): ByteBuffer {
            val fd: AssetFileDescriptor = context.assets.openFd(ASSET)
            FileInputStream(fd.fileDescriptor).use { stream ->
                return stream.channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    fd.startOffset,
                    fd.declaredLength
                )
            }
        }
    }
}