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
        for (c in 0 until classCount) total[c] /= frames
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

/**
 * The AudioSet classes this app cares about, and what it calls them.
 *
 * AudioSet has 521 labels covering everything from birdsong to reversing
 * lorries. These are the ones that mean something for a music library, grouped
 * so that a handful of related classes can back one conclusion - "there is
 * singing here" is better evidence from Singing, Choir, Chant and Vocal music
 * together than from any one of them.
 *
 * Indices are positions in YAMNet's own class map, which is fixed for the
 * model file shipped in assets.
 */
object AudioTags {

    /** A named group of AudioSet classes, and the app-facing tag it supports. */
    data class Group(val label: String, val indices: IntArray, val threshold: Float)

    val VOICE = Group("ווקאל", intArrayOf(24, 25, 27, 29, 249), 0.06f)
    val CHOIR = Group("מקהלה", intArrayOf(25, 27), 0.05f)
    val RAP = Group("ראפ", intArrayOf(31, 213), 0.05f)
    val INSTRUMENTAL = Group("אינסטרומנטלי", intArrayOf(133), 0.10f)

    val MIDDLE_EASTERN = Group("מזרחי", intArrayOf(229), 0.04f)
    val WEDDING = Group("שמחות", intArrayOf(270), 0.04f)
    val FOLK = Group("מסורתי", intArrayOf(228, 259), 0.04f)
    val POP = Group("פופ", intArrayOf(211), 0.05f)
    val ROCK = Group("רוק", intArrayOf(214, 219), 0.05f)
    val HIPHOP = Group("היפ הופ", intArrayOf(212), 0.05f)
    val JAZZ = Group("ג'אז", intArrayOf(230), 0.05f)
    val CLASSICAL = Group("קלאסי", intArrayOf(232, 233), 0.05f)
    val ELECTRONIC = Group("אלקטרוני", intArrayOf(234, 239, 240, 241), 0.05f)
    val DANCE = Group("ריקודים", intArrayOf(269), 0.05f)
    val CHILDREN = Group("ילדים", intArrayOf(247), 0.05f)

    val HAPPY = Group("שמח", intArrayOf(271), 0.03f)
    val SAD = Group("עצוב", intArrayOf(272), 0.03f)
    val TENDER = Group("רגוע", intArrayOf(273), 0.03f)
    val EXCITING = Group("מרגש", intArrayOf(274), 0.03f)

    val ACCORDION = Group("אקורדיון", intArrayOf(204), 0.04f)
    val CLARINET = Group("קלרינט", intArrayOf(193), 0.04f)
    val VIOLIN = Group("כינור", intArrayOf(186), 0.04f)
    val PIANO = Group("פסנתר", intArrayOf(148, 149), 0.05f)
    val GUITAR = Group("גיטרה", intArrayOf(135, 136, 138), 0.05f)
    val DRUMS = Group("כלי הקשה", intArrayOf(156, 157, 159), 0.06f)

    val ALL: List<Group> = listOf(
        VOICE, CHOIR, RAP, INSTRUMENTAL,
        MIDDLE_EASTERN, WEDDING, FOLK, POP, ROCK, HIPHOP, JAZZ, CLASSICAL,
        ELECTRONIC, DANCE, CHILDREN,
        HAPPY, SAD, TENDER, EXCITING,
        ACCORDION, CLARINET, VIOLIN, PIANO, GUITAR, DRUMS
    )

    /** The strongest score among a group's classes. */
    fun strength(scores: FloatArray, group: Group): Float {
        var best = 0f
        for (i in group.indices) {
            if (i < scores.size && scores[i] > best) best = scores[i]
        }
        return best
    }

    /**
     * The tags a track earns, strongest first.
     *
     * Thresholds are per group rather than one global number because the
     * classes are not equally common in the training data: "Music" fires on
     * almost anything musical and needs a high bar, while "Middle Eastern
     * music" is rare enough that a low score already means something.
     */
    fun tagsFor(scores: FloatArray, limit: Int = 6): List<String> =
        ALL.mapNotNull { group ->
            val s = strength(scores, group)
            if (s >= group.threshold) group.label to s else null
        }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }

    /**
     * The scores worth storing: index and value for the classes that fired.
     *
     * Storing all 521 per song would be about two kilobytes of text each, and
     * almost all of it zeros - for music, the distribution is sharply peaked.
     * The top slice keeps everything a linear classifier can use and costs a
     * fraction of the space.
     */
    fun compress(scores: FloatArray, keep: Int = 60): String =
        scores.indices
            .sortedByDescending { scores[it] }
            .take(keep)
            .filter { scores[it] > 0.001f }
            .joinToString(",") { "$it:${"%.4f".format(scores[it])}" }

    /** Rebuilds a sparse score vector written by [compress]. */
    fun decompress(stored: String, size: Int = 521): FloatArray {
        val out = FloatArray(size)
        if (stored.isBlank()) return out
        for (pair in stored.split(',')) {
            val parts = pair.split(':')
            val index = parts.getOrNull(0)?.toIntOrNull() ?: continue
            val value = parts.getOrNull(1)?.toFloatOrNull() ?: continue
            if (index in 0 until size) out[index] = value
        }
        return out
    }
}
