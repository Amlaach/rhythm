package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.db.SongEntity
import kotlin.math.abs
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
            minPerStyle: Int = 8,
            epochs: Int = 220,
            learningRate: Double = 0.35,
            l2: Double = 0.02
        ): StyleLearner? {
            if (labelled.size < minPerStyle * 2) return null

            val counts = HashMap<String, Int>()
            for ((_, tags) in labelled) {
                for (tag in tags.distinct()) counts[tag] = (counts[tag] ?: 0) + 1
            }
            // A style also needs counter-examples: if everything in the library
            // is tagged "חסידי" then "חסידי" separates nothing, and a model
            // that always answers yes looks perfect while knowing nothing.
            val styles = counts
                .filter { it.value >= minPerStyle && it.value <= labelled.size - minPerStyle }
                .keys
                .sorted()
            if (styles.isEmpty()) return null

            val dimension = labelled.first().first.size
            val mean = DoubleArray(dimension)
            val scale = DoubleArray(dimension) { 1.0 }

            for ((scores, _) in labelled) {
                for (i in 0 until dimension) mean[i] += scores[i].toDouble()
            }
            for (i in 0 until dimension) mean[i] /= labelled.size
            for ((scores, _) in labelled) {
                for (i in 0 until dimension) {
                    val d = scores[i] - mean[i]
                    scale[i] += d * d
                }
            }
            for (i in 0 until dimension) {
                scale[i] = sqrt(scale[i] / labelled.size).coerceAtLeast(EPS)
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

        /**
         * Splits the labelled set, fits on one half and reports how well it did
         * on the other.
         *
         * Without this there is no way to tell a model that learned something
         * from one that memorised the examples, and the difference is invisible
         * from the inside - both fit the training data perfectly well. The
         * number this returns is what decides whether the predictions are used
         * at all.
         *
         * @return accuracy on the held-out half, or null if there was too
         *   little to split.
         */
        fun crossValidate(
            labelled: List<Pair<FloatArray, List<String>>>,
            minPerStyle: Int = 8
        ): Double? {
            if (labelled.size < minPerStyle * 4) return null
            // Deterministic split, so the same library gives the same answer.
            val shuffled = labelled.sortedBy { abs(it.first.sum().hashCode()) }
            val cut = shuffled.size / 2
            val train = shuffled.take(cut)
            val test = shuffled.drop(cut)
            val model = fit(train, minPerStyle = minPerStyle / 2) ?: return null

            var correct = 0
            var counted = 0
            for ((scores, truth) in test) {
                if (truth.isEmpty()) continue
                counted++
                val predicted = model.predict(scores, minimum = 0.5, limit = 2)
                if (predicted.any { it in truth }) correct++
            }
            if (counted == 0) return null
            return correct.toDouble() / counted
        }
    }
}

/**
 * Turns a library into training rows.
 *
 * The labels come from the artist a song belongs to, not the song, because
 * that is where the user actually does the tagging - one artist rated covers
 * every track they have by them, which is how a few minutes of tagging becomes
 * a few hundred labelled examples.
 */
object StyleTraining {

    fun rows(
        songs: List<SongEntity>,
        tagsBySong: Map<Long, String>,
        stylesByArtistKey: Map<String, String>
    ): List<Pair<FloatArray, List<String>>> {
        val out = ArrayList<Pair<FloatArray, List<String>>>()
        for (song in songs) {
            val stored = tagsBySong[song.id] ?: continue
            if (stored.isBlank()) continue
            val styles = Styles.parse(stylesByArtistKey[song.artistKey].orEmpty())
            if (styles.isEmpty()) continue
            out.add(AudioTags.decompress(stored) to styles)
        }
        return out
    }
}
