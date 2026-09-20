package com.elchanan.rhythm.engine

import android.content.Context
import android.content.res.AssetFileDescriptor
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
 * Both the model and its weights are Apache 2.0, which is why this is YAMNet
 * rather than one of the better music-specific models from MTG - those are
 * CC BY-NC-SA, and ShareAlike on a model that ships inside an app is a
 * condition worth avoiding entirely.
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
     * Scores for a mono 16 kHz waveform, averaged over its length.
     *
     * Averaged rather than taken at a single point because a song is not one
     * sound: an intro can be a solo voice and the chorus a full band, and the
     * question being asked is what the track contains, not what its fourth
     * second contains. Mean over frames answers that; a maximum would let one
     * stray moment of applause label the whole recording.
     */
    fun scores(waveform16k: FloatArray): FloatArray? {
        if (waveform16k.size < frameSamples) return null
        val total = FloatArray(classCount)
        var frames = 0

        val input = ByteBuffer
            .allocateDirect(frameSamples * 4)
            .order(ByteOrder.nativeOrder())
        val output = Array(1) { FloatArray(classCount) }

        var offset = 0
        while (offset + frameSamples <= waveform16k.size) {
            input.rewind()
            for (i in 0 until frameSamples) {
                input.putFloat(waveform16k[offset + i])
            }
            input.rewind()
            val ok = runCatching { interpreter.run(input, output) }.isSuccess
            if (!ok) return null
            val row = output[0]
            for (c in 0 until classCount) total[c] += row[c]
            frames++
            // Half-frame hop, matching how the model was trained to be applied.
            offset += frameSamples / 2
        }
        if (frames == 0) return null
        val divisor = frames.toFloat()
        for (c in 0 until classCount) total[c] = total[c] / divisor
        return total
    }

    fun close() = runCatching { interpreter.close() }.let { }

    companion object {
        private const val ASSET = "yamnet.tflite"

        /** 0.975 s at 16 kHz, the published frame, used only as a fallback. */
        private const val DEFAULT_FRAME = 15600
        private const val CLASS_COUNT = 521

        /** The sample rate the model was trained at. Not negotiable. */
        const val SAMPLE_RATE = 16000

        fun create(context: Context): AudioTagger? = runCatching {
            val options = Interpreter.Options().apply {
                // Two threads: the analyser already runs several songs in
                // sequence on a background thread, and taking every core would
                // make the interface stutter while a library is scanned.
                setNumThreads(2)
            }
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