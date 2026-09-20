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

        /** Examples a style needs before it is worth fitting at all. */
        const val DEFAULT_MIN_PER_STYLE = 8

        /**
         * Labelled songs [crossValidate] needs before it will answer.
         *
         * It splits the set in half and needs each half to stand on its
         * own, so the whole is four times what one style needs. Named
         * rather than buried, because the screen has to be able to say
         * how far off the user is.
         */
        const val MIN_ROWS_TO_VALIDATE = DEFAULT_MIN_PER_STYLE * 4

        /** How hard to hold the weights down, and how well that did. */
        data class Validation(val accuracy: Double, val l2: Double)

        /**
         * Regularisation strengths to try, weakest first.
         *
         * Spread wide because the right answer moves with the number of
         * examples, which here ranges from a few dozen to a few thousand.
         */
        private val L2_CANDIDATES = doubleArrayOf(0.02, 0.1, 0.5, 2.0)

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
         * It also chooses how hard to regularise. That was a fixed 0.02, which
         * cannot be right for both a library with forty labelled songs and one
         * with four hundred - the first needs to be held down hard or it
         * memorises, the second is only blunted by it. The held-out half is
         * already here and is exactly the thing qualified to decide, so it
         * tries a few strengths and keeps the one that generalises best.
         *
         * @return the best accuracy on the held-out half and the strength that
         *   achieved it, or null if there was too little to split.
         */
        fun crossValidate(
            labelled: List<Pair<FloatArray, List<String>>>,
            minPerStyle: Int = DEFAULT_MIN_PER_STYLE
        ): Validation? {
            if (labelled.size < minPerStyle * 4) return null
            // Deterministic split, so the same library gives the same answer.
            val shuffled = labelled.sortedBy { abs(it.first.sum().hashCode()) }
            val cut = shuffled.size / 2
            val train = shuffled.take(cut)
            val test = shuffled.drop(cut)

            var best: Validation? = null
            for (l2 in L2_CANDIDATES) {
                val model = fit(train, minPerStyle = minPerStyle / 2, l2 = l2) ?: continue
                var correct = 0
                var counted = 0
                for ((scores, truth) in test) {
                    if (truth.isEmpty()) continue
                    counted++
                    val predicted = model.predict(scores, minimum = 0.5, limit = 2)
                    if (predicted.any { it in truth }) correct++
                }
                if (counted == 0) continue
                val accuracy = correct.toDouble() / counted
                if (best == null || accuracy > best.accuracy) {
                    best = Validation(accuracy, l2)
                }
            }
            return best
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

    /**
     * What the classifier sees for one song: what the model heard, and what
     * the analyser measured.
     *
     * The tag scores alone were the whole input, and they are the half of the
     * evidence that knows least about this repertoire. AudioSet was labelled
     * from YouTube, where this music is thin on the ground, so its 521 classes
     * describe a niggun only indirectly - some choir, some chant, a balance of
     * instruments.
     *
     * The measured half knows things AudioSet has no word for. The mode is the
     * clearest of them: [MusicalMode] separates Ahavah Rabbah and Mi Sheberach
     * from plain major, and that distinction is most of what "חסידי" and
     * "מזרחי" sound like. Tempo, brightness and the shape of the track over
     * its length are in there too. None of it costs anything - every number is
     * already measured and stored for each song.
     *
     * @return null when the model never ran on this song, which is the one
     *   case there is nothing to learn from.
     */
    fun featuresFor(songId: Long, tags: String, space: AcousticSpace?): FloatArray? {
        val measured = space?.vectors?.get(songId)
        // Either half is enough on its own. Requiring the tags meant the lite
        // build could never learn anything at all - it ships without the model,
        // so every song there has an empty tag string - even though the thirty
        // six measured numbers were sitting there being ignored.
        if (tags.isBlank() && measured == null) return null
        // Twenty five group strengths, not all 521 classes. See
        // AudioTags.groupStrengths: with a few dozen labelled songs, 521
        // inputs is far more freedom than the evidence can pay for.
        val heard = AudioTags.groupStrengths(tags)
        val out = FloatArray(heard.size + AcousticSpace.DIMS)
        System.arraycopy(heard, 0, out, 0, heard.size)
        // Left at zero when a song is not in the acoustic space. Those vectors
        // are z-scores against the user's own library, so zero is the mean -
        // which is the right thing to say about a value that is not known.
        if (measured == null) return out
        for (i in 0 until AcousticSpace.DIMS) {
            out[heard.size + i] = measured[i].toFloat()
        }
        return out
    }

    fun rows(
        songs: List<SongEntity>,
        tagsBySong: Map<Long, String>,
        stylesByArtistKey: Map<String, String>,
        space: AcousticSpace? = null
    ): List<Pair<FloatArray, List<String>>> {
        val out = ArrayList<Pair<FloatArray, List<String>>>()
        for (song in songs) {
            val styles = Styles.parse(stylesByArtistKey[song.artistKey].orEmpty())
            if (styles.isEmpty()) continue
            val x = featuresFor(song.id, tagsBySong[song.id].orEmpty(), space) ?: continue
            out.add(x to styles)
        }
        return out
    }
}
