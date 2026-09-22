package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.db.AudioFeatureEntity
import com.elchanan.rhythm.data.db.SongStatsEntity
import com.elchanan.rhythm.data.db.SongEntity
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Learns what each of the user's own style words sounds like, from their own
 * library, on their own device.
 *
 * This is the part the pre-trained model cannot do. YAMNet was trained on
 * YouTube, where Hasidic and Mizrahi music are thin on the ground, so asking it
 * directly for "חסידי" is asking a question it was never taught. What it does
 * reliably is describe: it will give a niggun a consistent signature - some
 * choir, some chant, a particular balance of instruments - even while filing it
 * under a label that means nothing here.
 *
 * A consistent signature is all a classifier needs. The user has already
 * supplied the missing half by tagging artists with styles, so every song by a
 * tagged artist is a labelled example. Fit one small model per style and the
 * app can label the rest of the library in the user's own vocabulary rather
 * than AudioSet's.
 *
 * One logistic regression per style, trained one-against-the-rest. Linear on
 * purpose: with a few dozen labelled artists, anything with more capacity
 * memorises the examples and learns nothing that transfers.
 */
class StyleLearner private constructor(
    private val styles: List<String>,
    private val weights: Array<DoubleArray>,
    private val bias: DoubleArray,
    private val mean: DoubleArray,
    private val scale: DoubleArray
) {

    /**
     * How strongly each learned style fits, highest first.
     *
     * Only styles the model is actually confident about are returned. A
     * classifier trained on a handful of examples will happily produce 0.51 for
     * everything, and a label that weak is worse than none: it would be written
     * into the library as though it were known.
     */
    fun predict(scores: FloatArray, minimum: Double = 0.65, limit: Int = 3): List<String> {
        if (styles.isEmpty()) return emptyList()
        val x = standardise(scores)
        return styles.indices
            .map { s -> styles[s] to probability(x, s) }
            .filter { it.second >= minimum }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    private fun probability(x: DoubleArray, style: Int): Double {
        var z = bias[style]
        val w = weights[style]
        for (i in x.indices) z += w[i] * x[i]
        return 1.0 / (1.0 + exp(-z))
    }

    private fun standardise(scores: FloatArray): DoubleArray {
        val out = DoubleArray(mean.size)
        for (i in mean.indices) {
            val v = if (i < scores.size) scores[i].toDouble() else 0.0
            out[i] = (v - mean[i]) / scale[i]
        }
        return out
    }

    companion object {

        /**
         * Class scores are tiny and wildly different in range - "Music" sits
         * near 0.9 on everything, "Accordion" near 0.01 when it is there at
         * all. Gradient descent on raw values would spend all its effort on the
         * loud dimensions, so every input is centred and scaled first.
         */
        private const val EPS = 1e-6

        /** Examples a style needs before it is worth fitting at all. */
        const val DEFAULT_MIN_PER_STYLE = 8

        /** Minimum examples before attempting validation on held-out artists. */
        const val MIN_ROWS_TO_VALIDATE = DEFAULT_MIN_PER_STYLE * 4

        /**
         * How many labelled songs carry each style, most common first.
         *
         * Public because these counts are the entire diagnosis when learning
         * refuses to run. Too few rows and a library that is all one style
         * both end as "no model", and they need opposite things from the
         * user - more songs in the first case, more variety in the second.
         * Without the counts the screen cannot tell them apart and has to
         * guess, which is how it came to tell someone with fifty six tagged
         * songs that thirty two were needed.
         */
        fun styleCounts(labelled: List<Pair<FloatArray, List<String>>>): List<Pair<String, Int>> {
            val counts = HashMap<String, Int>()
            for ((_, tags) in labelled) {
                for (tag in tags.distinct()) counts[tag] = (counts[tag] ?: 0) + 1
            }
            return counts.map { it.key to it.value }
                .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
        }

        /**
         * The styles there is any point fitting.
         *
         * A style needs examples, and it equally needs counter-examples: if
         * everything in the library is tagged "חסידי" then "חסידי" separates
         * nothing, and a model that always answers yes looks perfect while
         * having learned nothing at all.
         */
        fun eligibleStyles(
            labelled: List<Pair<FloatArray, List<String>>>,
            minPerStyle: Int = DEFAULT_MIN_PER_STYLE
        ): List<String> = styleCounts(labelled)
            .filter { it.second >= minPerStyle && it.second <= labelled.size - minPerStyle }
            .map { it.first }
            .sorted()

        /**
         * Fits a model, or returns null when there is not enough to learn from.
         *
         * The thresholds are deliberately conservative. A style with three
         * examples produces a classifier that is confidently wrong, and this
         * runs unsupervised over someone's whole library - the failure mode to
         * avoid is filling it with labels nobody checked.
         *
         * @param labelled songs whose style words are known, with their scores.
         * @param minPerStyle how many examples a style needs to be worth fitting.
         */
        fun fit(
            labelled: List<Pair<FloatArray, List<String>>>,
            minPerStyle: Int = DEFAULT_MIN_PER_STYLE,
            epochs: Int = 220,
            learningRate: Double = 0.35,
            l2: Double = 0.1
        ): StyleLearner? {
            if (labelled.size < minPerStyle * 2) return null
            if (labelled.any { (x, _) -> x.isEmpty() || x.size != labelled.first().first.size || x.any { !it.isFinite() } }) return null

            val styles = eligibleStyles(labelled, minPerStyle)
            if (styles.isEmpty()) return null

            val dimension = labelled.first().first.size
            val mean = DoubleArray(dimension)
            val scale = DoubleArray(dimension)

            for ((scores, _) in labelled) {
                for (i in 0 until dimension) mean[i] += scores[i].toDouble()
            }
            val rowCount = labelled.size.toDouble()
            for (i in 0 until dimension) mean[i] = mean[i] / rowCount
            for ((scores, _) in labelled) {
                for (i in 0 until dimension) {
                    val d = scores[i] - mean[i]
                    scale[i] += d * d
                }
            }
            for (i in 0 until dimension) {
                scale[i] = sqrt(scale[i] / rowCount).coerceAtLeast(EPS)
            }

            val x = Array(labelled.size) { row ->
                val scores = labelled[row].first
                DoubleArray(dimension) { i -> (scores[i] - mean[i]) / scale[i] }
            }

            val weights = Array(styles.size) { DoubleArray(dimension) }
            val bias = DoubleArray(styles.size)

            for (s in styles.indices) {
                val style = styles[s]
                val y = BooleanArray(labelled.size) { style in labelled[it].second }
                // Positives are usually the minority, and without this the
                // cheapest way to cut the loss is to answer "no" to everything.
                val positives = y.count { it }
                val posWeight = (labelled.size - positives).toDouble() / positives.coerceAtLeast(1)

                val w = weights[s]
                var b = bias[s]
                for (epoch in 0 until epochs) {
                    val gradW = DoubleArray(dimension)
                    var gradB = 0.0
                    for (row in x.indices) {
                        var z = b
                        val xi = x[row]
                        for (i in 0 until dimension) z += w[i] * xi[i]
                        val p = 1.0 / (1.0 + exp(-z))
                        val target = if (y[row]) 1.0 else 0.0
                        val weightOfRow = if (y[row]) posWeight else 1.0
                        val error = (p - target) * weightOfRow
                        for (i in 0 until dimension) gradW[i] += error * xi[i]
                        gradB += error
                    }
                    val step = learningRate / labelled.size
                    for (i in 0 until dimension) {
                        w[i] -= step * (gradW[i] + l2 * w[i])
                    }
                    b -= step * gradB
                }
                bias[s] = b
            }

            return StyleLearner(styles, weights, bias, mean, scale)
        }

    }
}

/**
 * Raw acoustic measurements and frozen YAMNet scores. User artist labels are
 * supervision, not objective genre truth. Manual song labels override them.
 * Automatically inferred labels are never fed back into training.
 */
object StyleTraining {
    fun featuresFor(feature: AudioFeatureEntity?): FloatArray? {
        val f = feature ?: return null
        val measured = if (f.energy > 0f && f.energy.isFinite()) AcousticSpace.rawVector(f) else null
        if (f.tags.isBlank() && measured == null) return null
        val heard = AudioTags.groupStrengths(f.tags)
        val out = FloatArray(heard.size + AcousticSpace.DIMS)
        System.arraycopy(heard, 0, out, 0, heard.size)
        if (measured != null) {
            for (i in measured.indices) out[heard.size + i] = measured[i].toFloat()
        }
        return out.takeIf { vector -> vector.all { it.isFinite() } }
    }

    fun rows(
        songs: List<SongEntity>,
        features: Map<Long, AudioFeatureEntity>,
        stylesByArtistKey: Map<String, String>,
        stats: Map<Long, SongStatsEntity> = emptyMap()
    ): List<StyleExample> = songs.mapNotNull { song ->
        val own = stats[song.id]
        val manual = if (own?.stylesAuto == 0) Styles.parse(own.styles) else emptyList()
        val labels = manual.ifEmpty { Styles.parse(stylesByArtistKey[song.artistKey].orEmpty()) }
        if (labels.isEmpty() || song.artistKey.isBlank()) return@mapNotNull null
        val x = featuresFor(features[song.id]) ?: return@mapNotNull null
        StyleExample(song.id, song.artistKey, x, labels.distinct())
    }
}
