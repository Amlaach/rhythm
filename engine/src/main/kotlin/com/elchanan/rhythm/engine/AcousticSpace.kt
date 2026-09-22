package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.db.AudioFeatureEntity
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Turns the raw analyser output into a comparable vector space.
 *
 * Raw features are on wildly different scales - BPM lives around 100, RMS
 * around 0.05, MFCCs around ±3 - so every dimension is standardised against
 * the user's own library. "Loud" therefore means loud *relative to what this
 * person owns*, which is exactly the comparison that matters here.
 */
class AcousticSpace(
    features: Collection<AudioFeatureEntity>,
    /**
     * Whether YAMNet's sound print takes part where both songs have one. Off
     * only for the sound check, which has to measure the hand-made features
     * on their own to compare them with anything.
     */
    usePrints: Boolean = true
) {

    companion object {
        private const val CORE = 6          // logBpm, energy, brightness, flatness, dynamics, onsetRate
        private const val TIMBRE = 12       // MFCC means
        private const val HARMONY = 12      // key invariant chroma
        private const val SHAPE = 6         // how the track moves over its length
        const val DIMS = CORE + TIMBRE + HARMONY + SHAPE

        /**
         * Group weights, chosen so no group can drown the others out.
         *
         * Harmony was previously turned almost all the way down, which threw
         * away the one feature that says two recordings are the same piece:
         * MFCC describes the production, chroma describes the notes, and the
         * two carry complementary information. It now counts for about as much
         * as timbre.
         */
        private val WEIGHTS = DoubleArray(DIMS) { i ->
            when {
                i < CORE -> 1.0
                i < CORE + TIMBRE -> 0.45
                i < CORE + TIMBRE + HARMONY -> 0.50
                else -> 0.55
            }
        }
        private val WEIGHT_SUM = WEIGHTS.sum()

        /**
         * How much of a similarity the sound print is, where both songs have
         * one: all of it. Measured on a real library - how often a song's
         * nearest neighbours by other artists share its style - the print
         * scored 30%, the hand-made features 12%, picking at random 25%, and
         * a 60/40 blend of the two 22%. The features were not merely weaker,
         * they pulled the blend below chance: their similarities spread wider,
         * so even at 40% they decided the order. They remain for songs that
         * have no print yet.
         */
        const val PRINT_SHARE = 1.0

        /** The print folded to this many dimensions, to keep a library of them small in memory. */
        const val PRINT_DIMS = 128

        /**
         * Feature hashing: each of the print's dimensions lands in one bucket
         * with a fixed random sign. Linear, one pass, and inner products are
         * kept on average - so a cosine between folded prints is a fair
         * estimate of the cosine between the full ones, at an eighth the size.
         */
        private val BUCKET = IntArray(SoundPrint.DIMS)
        private val SIGN = FloatArray(SoundPrint.DIMS)

        init {
            val r = java.util.Random(0x5EED)
            for (i in 0 until SoundPrint.DIMS) {
                BUCKET[i] = r.nextInt(PRINT_DIMS)
                SIGN[i] = if (r.nextBoolean()) 1f else -1f
            }
        }

        private fun fold(p: FloatArray): DoubleArray {
            val out = DoubleArray(PRINT_DIMS)
            for (i in 0 until SoundPrint.DIMS) out[BUCKET[i]] += (SIGN[i] * p[i]).toDouble()
            return out
        }

        internal fun rawVector(f: AudioFeatureEntity): DoubleArray {
            val out = DoubleArray(DIMS)
            out[0] = if (f.bpm > 20f) ln(f.bpm.toDouble()) else ln(100.0)
            out[1] = ln(f.energy.toDouble() + 1e-6)
            out[2] = f.brightness.toDouble()
            out[3] = f.flatness.toDouble()
            out[4] = f.dynamics.toDouble()
            out[5] = f.onsetRate.toDouble()

            val timbre = Features.parseVector(f.timbre, TIMBRE)
            for (i in 0 until TIMBRE) out[CORE + i] = timbre[i]

            val chroma = Features.parseVector(f.chroma, HARMONY)
            for (i in 0 until HARMONY) out[CORE + TIMBRE + i] = chroma[i]

            val shape = Features.parseVector(f.shape, SHAPE)
            for (i in 0 until SHAPE) out[CORE + TIMBRE + HARMONY + i] = shape[i]
            return out
        }
    }

    private val raw = HashMap<Long, DoubleArray>(features.size)
    private val means = DoubleArray(DIMS)
    private val deviations = DoubleArray(DIMS) { 1.0 }

    /** standardised vectors, clamped so one broken file cannot skew a distance */
    val vectors: Map<Long, DoubleArray>

    val bpmById: Map<Long, Float> = features.associate { it.songId to it.bpm }

    val size: Int get() = vectors.size

    init {
        for (f in features) raw[f.songId] = rawVector(f)

        if (raw.isNotEmpty()) {
            for (d in 0 until DIMS) {
                var sum = 0.0
                for (v in raw.values) sum += v[d]
                means[d] = sum / raw.size
            }
            for (d in 0 until DIMS) {
                var acc = 0.0
                for (v in raw.values) {
                    val delta = v[d] - means[d]
                    acc += delta * delta
                }
                val sd = sqrt(acc / max(1, raw.size - 1))
                deviations[d] = if (sd < 1e-6) 1.0 else sd
            }
        }

        vectors = raw.mapValues { (_, v) ->
            DoubleArray(DIMS) { d -> ((v[d] - means[d]) / deviations[d]).coerceIn(-4.0, 4.0) }
        }
    }

    /** Key invariant chroma only: the notes, ignoring how they were recorded. */
    private val chromaById: Map<Long, DoubleArray> =
        features.associate { it.songId to Features.parseVector(it.chroma, HARMONY) }

    /**
     * Cosine similarity of the two chroma profiles, 0..1.
     *
     * Chroma is stored already rotated to the tonic, so a live take in a
     * different key still lines up with the studio cut. This is the standard
     * starting point for spotting that two recordings are the same piece.
     */
    fun harmonicSimilarity(a: Long, b: Long): Double {
        val va = chromaById[a] ?: return 0.0
        val vb = chromaById[b] ?: return 0.0
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in va.indices) {
            dot += va[i] * vb[i]
            na += va[i] * va[i]
            nb += vb[i] * vb[i]
        }
        if (na < 1e-12 || nb < 1e-12) return 0.0
        return (dot / (sqrt(na) * sqrt(nb))).coerceIn(0.0, 1.0)
    }

    /**
     * Weighted squared distance mapped through a gaussian, so the result is a
     * bounded 0..1 similarity rather than an unbounded distance.
     */
    fun similarity(a: Long, b: Long): Double {
        val pa = prints[a] ?: return featureSimilarity(a, b)
        val pb = prints[b] ?: return featureSimilarity(a, b)
        var dot = 0.0
        for (i in 0 until PRINT_DIMS) dot += pa[i] * pb[i]
        // The same curve as the features: for unit vectors the squared distance
        // is 2(1 - cos), so a pair of unrelated songs lands where it does there.
        val print = exp(-(1.0 - dot.coerceIn(-1.0, 1.0)) / 0.8)
        if (PRINT_SHARE >= 1.0) return print
        return (1.0 - PRINT_SHARE) * featureSimilarity(a, b) + PRINT_SHARE * print
    }

    /**
     * Folded, centred, unit length prints, or none at all when too few songs
     * have one to know what the library's average sounds like.
     */
    private val prints: Map<Long, DoubleArray> = if (!usePrints) emptyMap() else {
        val folded = HashMap<Long, DoubleArray>()
        for (f in features) {
            val p = SoundPrint.unpack(f.soundPrint) ?: continue
            folded[f.songId] = fold(p)
        }
        if (folded.size < 8) emptyMap() else {
            // Folding is linear, so the folded mean is the fold of the mean.
            val mean = DoubleArray(PRINT_DIMS)
            for (v in folded.values) for (i in 0 until PRINT_DIMS) mean[i] += v[i]
            for (i in 0 until PRINT_DIMS) mean[i] = mean[i] / folded.size
            folded.mapValues { (_, v) ->
                var norm = 0.0
                for (i in 0 until PRINT_DIMS) {
                    v[i] -= mean[i]
                    norm += v[i] * v[i]
                }
                val length = sqrt(norm)
                if (length > 1e-9) for (i in 0 until PRINT_DIMS) v[i] /= length
                v
            }
        }
    }

    /** The hand-made features alone: tempo, loudness, timbre, harmony, shape. */
    fun featureSimilarity(a: Long, b: Long): Double {
        val va = vectors[a] ?: return 0.0
        val vb = vectors[b] ?: return 0.0
        var acc = 0.0
        for (d in 0 until DIMS) {
            val delta = va[d] - vb[d]
            acc += WEIGHTS[d] * delta * delta
        }
        return exp(-(acc / WEIGHT_SUM) / 1.6)
    }

    /**
     * Mean similarity to the k closest members of a seed set - a small kNN.
     *
     * A single centroid would be wrong here: someone who likes both slow
     * cantorial pieces and fast dance tracks has a centroid that sits in an
     * empty region matching neither. Nearest neighbours keep multi modal taste
     * intact.
     */
    fun similarityToSet(songId: Long, seeds: Collection<Long>, k: Int): Double {
        if (vectors[songId] == null || seeds.isEmpty()) return 0.0
        val best = DoubleArray(k)
        for (seed in seeds) {
            if (seed == songId) continue
            val s = similarity(songId, seed)
            // insert into the running top-k
            var i = k - 1
            if (s <= best[i]) continue
            while (i > 0 && best[i - 1] < s) {
                best[i] = best[i - 1]
                i--
            }
            best[i] = s
        }
        var sum = 0.0
        var count = 0
        for (v in best) {
            if (v > 0.0) {
                sum += v
                count++
            }
        }
        return if (count == 0) 0.0 else sum / count
    }

    /** 0 when the tempos match, 1 when they are a factor of two apart. */
    fun tempoDistance(a: Long, b: Long): Double {
        val ba = bpmById[a] ?: return 0.0
        val bb = bpmById[b] ?: return 0.0
        if (ba < 20f || bb < 20f) return 0.0
        val ratio = ln(ba.toDouble() / bb.toDouble())
        return (kotlin.math.abs(ratio) / ln(2.0)).coerceIn(0.0, 1.0)
    }

    fun has(songId: Long): Boolean = vectors.containsKey(songId)

    /** Human readable mode, or null when the estimate was not clear enough. */
    fun modeLabel(f: AudioFeatureEntity): String? {
        if (f.scaleConfidence < 0.2f) return null
        return MusicalMode.byOrdinalOrNull(f.scaleMode)?.label
    }
}
