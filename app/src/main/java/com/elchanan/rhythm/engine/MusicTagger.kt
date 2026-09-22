package com.elchanan.rhythm.engine

import android.content.Context
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Discogs-EffNet and MTG's mood heads: what a recording is, musically.
 *
 * YAMNet ([AudioTagger]) was trained on every kind of sound and knows music
 * as one corner of that. This was trained on two million records labelled
 * with 400 styles, and the heads on top of it on songs people rated for how
 * happy, sad, relaxed, aggressive and danceable they are - the question the
 * mood chips ask, answered by a model trained on exactly that question.
 *
 * Essentia models by MTG-UPF, licensed CC BY-NC-SA 4.0: free, non-commercial
 * use with attribution, which is how this app is distributed. The converted
 * files in assets/music are under the same licence.
 *
 * Input is [MusicMel]'s spectrogram, checked against Essentia's front end;
 * output is a [MusicPrint] and a set of head readings for [MusicMoods].
 */
class MusicTagger private constructor(
    private val effnet: Interpreter,
    private val heads: List<Head>
) {

    private class Head(val name: String, val classes: List<String>, val interpreter: Interpreter)

    /** Which of the model's outputs is the 1280 wide embedding. */
    private val embeddingIndex: Int = (0 until effnet.outputTensorCount).firstOrNull {
        effnet.getOutputTensor(it).numElements() == MusicPrint.DIMS
    } ?: 0

    class Heard(val print: FloatArray, val moods: Map<String, Float>)

    /**
     * Reads a song from its probes, each mono at 16 kHz.
     *
     * One patch per probe, from its middle: eight two second glimpses spread
     * over the track. Every patch is another pass through the network, so
     * this is where the cost is decided; eight is about what a phone gets
     * through in a second or two, and the embedding is an average anyway.
     */
    fun listen(probes: List<FloatArray>): Heard? {
        val mel = MusicMel()
        val embeddings = ArrayList<FloatArray>()
        for (probe in probes) {
            val frames = mel.frames(probe)
            if (frames.size < MusicMel.PATCH) continue
            val start = (frames.size - MusicMel.PATCH) / 2
            val patch = Array(MusicMel.PATCH) { frames[start + it] }
            embed(patch)?.let { embeddings.add(it) }
        }
        if (embeddings.isEmpty()) return null

        val print = FloatArray(MusicPrint.DIMS)
        for (e in embeddings) for (i in print.indices) print[i] += e[i]
        for (i in print.indices) print[i] = print[i] / embeddings.size

        // Heads are read per patch and averaged, as Essentia applies them.
        val moods = LinkedHashMap<String, Float>()
        for (head in heads) {
            val sums = FloatArray(head.classes.size)
            var n = 0
            for (e in embeddings) {
                val out = runHead(head, e) ?: continue
                for (i in sums.indices) sums[i] += out.getOrElse(i) { 0f }
                n++
            }
            if (n == 0) continue
            for (i in sums.indices) sums[i] = sums[i] / n
            readHead(head, sums, moods)
        }
        return Heard(print, moods)
    }

    private fun embed(patch: Array<FloatArray>): FloatArray? = runCatching {
        val input = ByteBuffer.allocateDirect(MusicMel.PATCH * MusicMel.BANDS * 4).order(ByteOrder.nativeOrder())
        for (frame in patch) for (v in frame) input.putFloat(v)
        input.rewind()
        val outputs = HashMap<Int, Any>()
        for (i in 0 until effnet.outputTensorCount) {
            val t = effnet.getOutputTensor(i)
            outputs[i] = ByteBuffer.allocateDirect(t.numBytes()).order(ByteOrder.nativeOrder())
        }
        effnet.runForMultipleInputsOutputs(arrayOf<Any>(input), outputs)
        val buffer = outputs.getValue(embeddingIndex) as ByteBuffer
        buffer.rewind()
        FloatArray(MusicPrint.DIMS) { buffer.float }
    }.getOrNull()

    private fun runHead(head: Head, embedding: FloatArray): FloatArray? = runCatching {
        val input = arrayOf(embedding)
        val size = head.interpreter.getOutputTensor(0).numElements()
        val output = arrayOf(FloatArray(size))
        head.interpreter.run(input, output)
        output[0]
    }.getOrNull()

    /**
     * A two class head ("happy", "non_happy") becomes one probability under
     * the class that is not a negation; anything else keeps every output,
     * named head.class - arousal and valence from the regression heads.
     */
    private fun readHead(head: Head, values: FloatArray, into: MutableMap<String, Float>) {
        val classes = head.classes
        fun negated(c: String) = c.startsWith("non") || c.startsWith("not")
        if (classes.size == 2 && classes.any(::negated)) {
            val positive = classes.indexOfFirst { !negated(it) }.coerceAtLeast(0)
            into[classes[positive]] = values[positive]
        } else {
            classes.forEachIndexed { i, c -> into["${head.name}.$c"] = values.getOrElse(i) { 0f } }
        }
    }

    fun close() {
        runCatching { effnet.close() }
        heads.forEach { runCatching { it.interpreter.close() } }
    }

    companion object {
        private const val FOLDER = "music"

        fun create(context: Context): MusicTagger? = runCatching {
            val options = Interpreter.Options().apply { setNumThreads(2) }
            val effnet = Interpreter(map(context, "$FOLDER/effnet.tflite"), options)
            val meta = JSONObject(context.assets.open("$FOLDER/heads.json").bufferedReader().use { it.readText() })
            val list = meta.optJSONArray("heads")
            val heads = ArrayList<Head>()
            if (list != null) {
                for (i in 0 until list.length()) {
                    val h = list.getJSONObject(i)
                    val name = h.getString("name")
                    val classesJson = h.optJSONArray("classes") ?: continue
                    val classes = List(classesJson.length()) { classesJson.getString(it) }
                    val interpreter = runCatching {
                        Interpreter(map(context, "$FOLDER/$name.tflite"), Interpreter.Options())
                    }.getOrNull() ?: continue
                    heads.add(Head(name, classes, interpreter))
                }
            }
            MusicTagger(effnet, heads)
        }.getOrNull()

        /** Mapped straight out of the apk; the build stores .tflite uncompressed. */
        private fun map(context: Context, asset: String): ByteBuffer {
            val fd = context.assets.openFd(asset)
            FileInputStream(fd.fileDescriptor).use { stream ->
                return stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }
    }
}
