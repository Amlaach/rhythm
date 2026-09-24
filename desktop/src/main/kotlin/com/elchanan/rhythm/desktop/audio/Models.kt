package com.elchanan.rhythm.desktop.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.elchanan.rhythm.engine.MusicMel
import com.elchanan.rhythm.engine.MusicPrint
import com.elchanan.rhythm.engine.SoundPrint
import java.io.File
import java.nio.FloatBuffer

/**
 * The phone's two models, on the desktop.
 *
 * The phone runs YAMNet and Discogs-EffNet through TensorFlow Lite, which has
 * no desktop Java build. These are the same networks converted to ONNX from
 * the very files the phone ships (tools/models/to_onnx.py, which checks every
 * one against TensorFlow Lite before it is accepted) and run with ONNX
 * Runtime. The work around them - which samples go in, how frames are
 * averaged, how a head's two classes become one number - is the phone's
 * [com.elchanan.rhythm.engine.AudioTagger] and
 * [com.elchanan.rhythm.engine.MusicTagger] line for line, so a song analysed
 * here and on a phone makes the same row, to rounding.
 *
 * Loaded once, on first use, and kept: building a session costs more than
 * running one. A failure is remembered too, so a machine where the runtime
 * will not load finds out once rather than once per song - and then goes on
 * analysing without labels, as the phone does when its model is missing.
 *
 * YAMNet: Apache 2.0. Discogs-EffNet and heads: MTG-UPF, CC BY-NC-SA 4.0.
 */
object Models {

    /** YAMNet's frame, 0.975 s at 16 kHz, and its half-frame hop. */
    private const val YAMNET_FRAME = 15600
    private const val CLASS_COUNT = 521

    class SoundHeard(val scores: FloatArray, val print: FloatArray?)
    class MusicHeard(val print: FloatArray, val moods: Map<String, Float>)

    private class Head(val name: String, val classes: List<String>, val session: OrtSession)

    private class Loaded(val env: OrtEnvironment, val yamnet: OrtSession, val effnet: OrtSession?, val heads: List<Head>)

    @Volatile
    private var loaded: Loaded? = null

    @Volatile
    private var attempted = false

    /** Why the models did not load, for the settings screen. Empty when they did. */
    @Volatile
    var failure: String = ""
        private set

    /** Whether this build carries the models at all. A build's resources do not change. */
    private val soundShipped: Boolean by lazy { shipped("yamnet.onnx") }
    private val musicShipped: Boolean by lazy { shipped("effnet.onnx") }

    /** Whether the music model is here and loaded. Loads it on first call: not for the UI thread. */
    fun musicAvailable(): Boolean = load()?.effnet != null

    /** Whether YAMNet is here and loaded. Loads it on first call: not for the UI thread. */
    fun soundAvailable(): Boolean = load() != null

    /**
     * The same two answers without loading anything, for counting on screen:
     * shipped, and not already known to have failed. Before the first pass
     * this is a promise; the pass itself asks [soundAvailable] and
     * [musicAvailable] first, so it never queues work a broken runtime would
     * only leave undone again.
     */
    fun soundExpected(): Boolean = soundShipped && (!attempted || loaded != null)
    fun musicExpected(): Boolean = musicShipped && (!attempted || loaded?.effnet != null)

    private fun shipped(name: String): Boolean = Models::class.java.getResource("/models/$name") != null

    private fun load(): Loaded? {
        if (attempted) return loaded
        synchronized(this) {
            if (!attempted) {
                loaded = runCatching { open() }.onFailure {
                    failure = (it.message ?: it.javaClass.simpleName)
                    System.err.println("Rhythm: models did not load - $failure")
                }.getOrNull()
                attempted = true
            }
        }
        return loaded
    }

    private fun open(): Loaded {
        preloadRuntimeLibraries()
        val env = OrtEnvironment.getEnvironment()
        fun session(name: String): OrtSession? {
            val bytes = resource(name) ?: return null
            val options = OrtSession.SessionOptions().apply {
                // One thread per run. The analysis pass already works several
                // songs at once, one per core, and a session that also spread
                // itself over every core would only make them take turns.
                setIntraOpNumThreads(1)
                setInterOpNumThreads(1)
                setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            }
            return env.createSession(bytes, options)
        }
        val yamnet = session("yamnet.onnx") ?: error("yamnet.onnx is missing from this build")
        val effnet = runCatching { session("effnet.onnx") }.getOrNull()
        val heads = ArrayList<Head>()
        if (effnet != null) {
            val meta = resource("heads.json")?.toString(Charsets.UTF_8).orEmpty()
            for ((name, classes) in parseHeads(meta)) {
                val s = runCatching { session("$name.onnx") }.getOrNull() ?: continue
                heads.add(Head(name, classes, s))
            }
        }
        return Loaded(env, yamnet, effnet, heads)
    }

    private fun resource(name: String): ByteArray? =
        Models::class.java.getResourceAsStream("/models/$name")?.use { it.readBytes() }

    /**
     * The name and classes of each head, from the phone's heads.json.
     *
     * Read with a pattern rather than a JSON library, which this build does
     * not otherwise need: the file is written by the converter, one head per
     * object, name then classes, and nothing else in it looks like that.
     */
    internal fun parseHeads(json: String): List<Pair<String, List<String>>> =
        HEAD.findAll(json).map { m ->
            m.groupValues[1] to QUOTED.findAll(m.groupValues[2]).map { it.groupValues[1] }.toList()
        }.filter { it.second.isNotEmpty() }.toList()

    private val HEAD = Regex(""""name"\s*:\s*"([^"]+)"\s*,\s*"classes"\s*:\s*\[([^\]]*)\]""")
    private val QUOTED = Regex(""""([^"]*)"""")

    /**
     * ONNX Runtime's Windows build needs the Visual C++ runtime, which not
     * every Windows machine has installed. The Java runtime bundled with the
     * app carries its own copy in its bin folder; loading it from there
     * first means the process already has it when ONNX Runtime asks, so the
     * models work on a machine that has never installed anything.
     */
    private fun preloadRuntimeLibraries() {
        if (!System.getProperty("os.name").orEmpty().lowercase().contains("win")) return
        val bin = File(System.getProperty("java.home").orEmpty(), "bin")
        for (dll in listOf("vcruntime140.dll", "vcruntime140_1.dll", "msvcp140.dll")) {
            val f = File(bin, dll)
            if (f.isFile) runCatching { System.load(f.absolutePath) }
        }
    }

    /**
     * YAMNet's scores for a mono 16 kHz waveform, averaged over its length,
     * and the 1024 wide print averaged the same way. As the phone's
     * AudioTagger.listen: frames of 0.975 s, half a frame apart, the mean
     * rather than the maximum so one stray moment cannot label a track.
     */
    fun listenSound(waveform16k: FloatArray): SoundHeard? {
        val m = load() ?: return null
        if (waveform16k.size < YAMNET_FRAME) return null
        val total = FloatArray(CLASS_COUNT)
        val printTotal = FloatArray(SoundPrint.DIMS)
        var hasPrint = true
        var frames = 0
        val name = m.yamnet.inputNames.first()
        val frame = FloatArray(YAMNET_FRAME)
        var offset = 0
        while (offset + YAMNET_FRAME <= waveform16k.size) {
            System.arraycopy(waveform16k, offset, frame, 0, YAMNET_FRAME)
            val ok = runCatching {
                OnnxTensor.createTensor(m.env, FloatBuffer.wrap(frame), longArrayOf(YAMNET_FRAME.toLong())).use { input ->
                    m.yamnet.run(mapOf(name to input)).use { out ->
                        val scores = floats(out.get(0).value)
                        for (c in 0 until minOf(CLASS_COUNT, scores.size)) total[c] += scores[c]
                        val print = if (out.size() > 1) floats(out.get(1).value) else FloatArray(0)
                        if (print.size == SoundPrint.DIMS) {
                            for (i in print.indices) printTotal[i] += print[i]
                        } else {
                            hasPrint = false
                        }
                    }
                }
            }.isSuccess
            if (!ok) return null
            frames++
            offset += YAMNET_FRAME / 2
        }
        if (frames == 0) return null
        val divisor = frames.toFloat()
        for (c in total.indices) total[c] = total[c] / divisor
        for (i in printTotal.indices) printTotal[i] = printTotal[i] / divisor
        return SoundHeard(total, if (hasPrint) printTotal else null)
    }

    /**
     * EffNet and the mood heads, from the probes each on its own at 16 kHz.
     * As the phone's MusicTagger.listen: one 128 frame patch from the middle
     * of each probe, the embeddings averaged into the print, each head read
     * per patch and averaged the way Essentia applies them.
     */
    fun listenMusic(probes: List<FloatArray>): MusicHeard? {
        val m = load() ?: return null
        val effnet = m.effnet ?: return null
        val mel = MusicMel()
        val embeddings = ArrayList<FloatArray>()
        for (probe in probes) {
            val frames = mel.frames(probe)
            if (frames.size < MusicMel.PATCH) continue
            val start = (frames.size - MusicMel.PATCH) / 2
            val patch = FloatArray(MusicMel.PATCH * MusicMel.BANDS)
            for (f in 0 until MusicMel.PATCH) System.arraycopy(frames[start + f], 0, patch, f * MusicMel.BANDS, MusicMel.BANDS)
            embed(m, effnet, patch)?.let { embeddings.add(it) }
        }
        if (embeddings.isEmpty()) return null

        val print = FloatArray(MusicPrint.DIMS)
        for (e in embeddings) for (i in print.indices) print[i] += e[i]
        for (i in print.indices) print[i] = print[i] / embeddings.size

        val moods = LinkedHashMap<String, Float>()
        for (head in m.heads) {
            val sums = FloatArray(head.classes.size)
            var n = 0
            for (e in embeddings) {
                val out = runHead(m, head, e) ?: continue
                for (i in sums.indices) sums[i] += out.getOrElse(i) { 0f }
                n++
            }
            if (n == 0) continue
            for (i in sums.indices) sums[i] = sums[i] / n
            readHead(head, sums, moods)
        }
        return MusicHeard(print, moods)
    }

    /** One patch through EffNet, [128 x 96] row by row. For [ModelCheck]. */
    internal fun embedPatch(patch: FloatArray): FloatArray? {
        val m = load() ?: return null
        return embed(m, m.effnet ?: return null, patch)
    }

    /** Each head's raw outputs for one embedding, by head name. For [ModelCheck]. */
    internal fun headOutputs(embedding: FloatArray): Map<String, FloatArray> {
        val m = load() ?: return emptyMap()
        return m.heads.mapNotNull { h -> runHead(m, h, embedding)?.let { h.name to it } }.toMap()
    }

    private fun embed(m: Loaded, effnet: OrtSession, patch: FloatArray): FloatArray? = runCatching {
        val shape = longArrayOf(1, MusicMel.PATCH.toLong(), MusicMel.BANDS.toLong())
        OnnxTensor.createTensor(m.env, FloatBuffer.wrap(patch), shape).use { input ->
            effnet.run(mapOf(effnet.inputNames.first() to input)).use { out ->
                floats(out.get(0).value).takeIf { it.size == MusicPrint.DIMS }
            }
        }
    }.getOrNull()

    private fun runHead(m: Loaded, head: Head, embedding: FloatArray): FloatArray? = runCatching {
        OnnxTensor.createTensor(m.env, FloatBuffer.wrap(embedding), longArrayOf(1, MusicPrint.DIMS.toLong())).use { input ->
            head.session.run(mapOf(head.session.inputNames.first() to input)).use { out ->
                floats(out.get(0).value)
            }
        }
    }.getOrNull()

    /**
     * A two class head ("happy", "non_happy") becomes one probability under
     * the class that is not a negation; anything else keeps every output,
     * named head.class. The phone's rule, word for word.
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

    /** Whatever shape an output came in, its values in order. */
    private fun floats(value: Any?): FloatArray {
        if (value is FloatArray) return value
        if (value is Array<*> && value.isNotEmpty()) {
            if (value.size == 1 && value[0] is FloatArray) return value[0] as FloatArray
            var allFloats = true
            var total = 0
            for (item in value) {
                if (item is FloatArray) total += item.size else { allFloats = false; break }
            }
            if (allFloats) {
                val out = FloatArray(total)
                var offset = 0
                for (item in value) {
                    val arr = item as FloatArray
                    System.arraycopy(arr, 0, out, offset, arr.size)
                    offset += arr.size
                }
                return out
            }
        }
        val out = ArrayList<Float>()
        fun walk(v: Any?) {
            when (v) {
                is FloatArray -> for (x in v) out.add(x)
                is Array<*> -> for (x in v) walk(x)
                is Float -> out.add(v)
            }
        }
        walk(value)
        return out.toFloatArray()
    }
}
