package com.elchanan.rhythm.engine

/** Raw features and user labels, with the artist retained as the validation group. */
data class StyleExample(
    val songId: Long,
    val artistKey: String,
    val features: FloatArray,
    val labels: List<String>
) {
    fun trainingRow(): Pair<FloatArray, List<String>> = features to labels
}

data class StyleScore(val truePositives: Int, val falsePositives: Int, val falseNegatives: Int) {
    val precision: Double get() = ratio(truePositives, truePositives + falsePositives)
    val recall: Double get() = ratio(truePositives, truePositives + falseNegatives)
    val f1: Double get() = ratio(2 * truePositives, 2 * truePositives + falsePositives + falseNegatives)

    private fun ratio(numerator: Int, denominator: Int): Double =
        if (denominator == 0) 0.0 else numerator.toDouble() / denominator
}

/** Macro averages give rare styles the same weight as common styles. */
data class StyleMetrics(val byStyle: Map<String, StyleScore>, val songs: Int) {
    val macroF1: Double get() = byStyle.values.map { it.f1 }.averageOrZero()
    val macroPrecision: Double get() = byStyle.values.map { it.precision }.averageOrZero()
    val macroRecall: Double get() = byStyle.values.map { it.recall }.averageOrZero()

    private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()

    companion object {
        /**
         * Each pair is (user labels, predicted labels); every extra or missing
         * label counts - among the songs that answered the question.
         *
         * A song labelled only with a genre is not evidence that it is not
         * lively; it is a song nobody asked about. Counting it as a wrong
         * answer made every style whose question is only partly answered fail
         * on songs it was never given a chance at. See [Styles.answers].
         *
         * What this does cost is stated plainly rather than hidden: precision
         * here is precision among the songs the user did answer for. The model
         * still writes tags onto songs they did not, and there is no way to
         * measure that from labels that do not exist.
         */
        fun measure(results: List<Pair<Set<String>, Set<String>>>): StyleMetrics {
            val labels = results.flatMap { (truth, prediction) -> truth + prediction }.toSortedSet()
            return StyleMetrics(labels.associateWith { label ->
                val asked = results.filter { (truth, _) -> Styles.answers(label, truth) }
                StyleScore(
                    asked.count { (truth, prediction) -> label in truth && label in prediction },
                    asked.count { (truth, prediction) -> label !in truth && label in prediction },
                    asked.count { (truth, prediction) -> label in truth && label !in prediction }
                )
            }, results.size)
        }
    }
}

data class StyleValidationReport(
    val metrics: StyleMetrics,
    val baseline: StyleMetrics,
    val artists: Int,
    val folds: Int,
    /**
     * The bar each style had to clear during the test, averaged over the
     * folds. Reported so the screen can show what "confident" meant for each
     * style rather than implying one number governed them all.
     */
    val thresholds: Map<String, Double> = emptyMap()
)

/**
 * The confidence bar each style has to clear, measured rather than guessed.
 *
 * One bar for every style is the wrong shape for this problem. Styles in a
 * real library are not equally separable - a library that is mostly חסידי
 * with a few ג'אז records will draw a clean line around the first and a
 * blurry one around the second - and the probability that means "certain"
 * differs with it. A single number has to be set high enough for the hardest
 * style, and then the styles the model genuinely knows never clear it either.
 * So each style gets its own, chosen where its own precision and recall
 * balance best.
 *
 * Chosen on the training examples only, never on the test fold. A threshold
 * picked by looking at the answers is a parameter fitted to the test set, and
 * every number reported afterwards would be flattering and wrong. The tuning
 * here runs its own artist-grouped split *inside* the training data, so the
 * held-out artists are as unseen by the thresholds as they are by the weights.
 */
object StyleThresholds {

    /**
     * Coarse on purpose. A finer grid does not find a better bar, it finds
     * the noise in whichever few songs happened to land near the old one.
     */
    private val GRID: List<Double> = (25..90 step 5).map { it / 100.0 }

    /** Fewer than the outer pass uses: this runs inside every outer fold. */
    private const val INNER_FOLDS = 3

    /**
     * A bar per style, or no entry for a style there was no honest way to
     * calibrate - [StyleLearner.DEFAULT_THRESHOLD] then applies.
     *
     * @param minPrecision the precision a bar must reach to be preferred. A
     *   wrong tag is written into someone's library and is worse than a
     *   missing one, so recall is only maximised among the bars that are
     *   already precise enough.
     */
    fun tune(training: List<StyleExample>, minPrecision: Double): Map<String, Double> {
        val folds = folds(training, INNER_FOLDS)
        if (folds.isEmpty()) return emptyMap()

        // Probability and truth for every held-out example, per style.
        val observed = HashMap<String, MutableList<Pair<Double, Boolean>>>()
        for (testIndex in folds.indices) {
            val inner = folds.filterIndexed { index, _ -> index != testIndex }
                .flatten().map { it.trainingRow() }
            // A fold that cannot be fitted is skipped rather than fatal. This
            // picks a bar; it reports nothing, so falling back to the default
            // for a style costs accuracy and not honesty.
            val model = StyleLearner.fit(inner) ?: continue
            for (example in folds[testIndex]) {
                val truth = example.labels.toSet()
                for ((style, p) in model.probabilities(example.features)) {
                    // Same rule as the scoring: a song that never answered
                    // this question cannot calibrate the bar for it either.
                    if (!Styles.answers(style, truth)) continue
                    observed.getOrPut(style) { mutableListOf() }.add(p to (style in truth))
                }
            }
        }

        return observed.mapNotNull { (style, points) ->
            bestThreshold(points, minPrecision)?.let { style to it }
        }.toMap()
    }

    /**
     * The bar with the best F1 among those precise enough, or the strictest
     * bar on the grid when none of them is.
     *
     * Returning the strictest rather than nothing is deliberate: a style whose
     * every bar is imprecise should be close to silent, and the outer
     * validation will see it fire rarely and reject it on its own evidence.
     */
    private fun bestThreshold(
        points: List<Pair<Double, Boolean>>,
        minPrecision: Double
    ): Double? {
        if (points.none { it.second }) return null
        var best: Double? = null
        var bestF1 = -1.0
        for (threshold in GRID) {
            var truePositives = 0
            var falsePositives = 0
            var falseNegatives = 0
            for ((p, truth) in points) {
                val said = p >= threshold
                when {
                    said && truth -> truePositives++
                    said -> falsePositives++
                    truth -> falseNegatives++
                }
            }
            if (truePositives == 0) continue
            val precision = truePositives.toDouble() / (truePositives + falsePositives)
            if (precision < minPrecision) continue
            val f1 = 2.0 * truePositives /
                (2 * truePositives + falsePositives + falseNegatives)
            // Ties go to the stricter bar: the same score with fewer guesses
            // is the same score with less to be wrong about.
            if (f1 > bestF1 + 1e-9 || (f1 > bestF1 - 1e-9 && best != null && threshold > best!!)) {
                bestF1 = f1
                best = threshold
            }
        }
        return best ?: GRID.last()
    }

    private fun folds(examples: List<StyleExample>, max: Int) =
        StyleValidation.folds(examples, max)
}

/**
 * Fixed-parameter, out-of-artist validation. No parameter is selected on the
 * reported test results. Standardisation is fitted inside StyleLearner.fit on
 * raw training features only; held-out artists never contribute to it.
 */
object StyleValidation {
    const val MIN_ARTISTS = 4
    private const val MAX_FOLDS = 5

    /** Whole artists assigned to balanced folds, independently of sound or labels. */
    internal fun folds(
        examples: List<StyleExample>,
        maxFolds: Int = MAX_FOLDS
    ): List<List<StyleExample>> {
        val artists = examples.groupBy { it.artistKey }
        if (artists.size < MIN_ARTISTS) return emptyList()
        val folds = List(minOf(maxFolds, artists.size)) { mutableListOf<StyleExample>() }
        for (artist in artists.entries.sortedWith(
            compareByDescending<Map.Entry<String, List<StyleExample>>> { it.value.size }
                .thenBy { it.key }
        )) {
            val destination = folds.indices.minWithOrNull(
                compareBy<Int> { folds[it].size }.thenBy { it }
            ) ?: return emptyList()
            folds[destination].addAll(artist.value.sortedBy { it.songId })
        }
        return folds
    }

    /**
     * Per style, the most training examples any fold can offer it while also
     * holding some of it back to be tested on.
     *
     * The number that says whether a style had a chance. Eligibility is
     * checked on the whole set and then the model is fitted on four fifths of
     * it, so a style can clear the headline requirement and still fall under
     * it inside every fold - nine songs across two artists passes "at least
     * eight" and leaves five once an artist is held out, which is not enough
     * to fit. The style is then never learned in exactly the folds where it
     * could have been scored, and reports zero correct for a test it was
     * never actually given.
     *
     * A style is only ever scorable above zero when this number reaches
     * [StyleLearner.DEFAULT_MIN_PER_STYLE].
     */
    internal fun trainableCounts(examples: List<StyleExample>): Map<String, Int> {
        val folds = folds(examples)
        if (folds.isEmpty()) return emptyMap()

        fun tally(rows: List<StyleExample>): Map<String, Int> {
            val out = HashMap<String, Int>()
            for (row in rows) for (label in row.labels.distinct()) {
                out[label] = (out[label] ?: 0) + 1
            }
            return out
        }

        val totals = tally(examples)
        val best = HashMap<String, Int>()
        for (fold in folds) {
            for ((style, held) in tally(fold)) {
                // Only folds that actually test the style count: training for
                // a fold with none of it can never produce a correct answer.
                val training = (totals[style] ?: 0) - held
                best[style] = maxOf(best[style] ?: 0, training)
            }
        }
        return best
    }

    fun evaluate(
        examples: List<StyleExample>,
        minPrecision: Double = StyleLearning.MIN_PRECISION
    ): StyleValidationReport? {
        if (examples.size < StyleLearner.MIN_ROWS_TO_VALIDATE) return null
        val folds = folds(examples)
        if (folds.isEmpty()) return null
        val results = ArrayList<Pair<Set<String>, Set<String>>>()
        val baselineResults = ArrayList<Pair<Set<String>, Set<String>>>()
        // Summed per style over the folds, to report the bar each style was
        // actually held to rather than one number that governed none of them.
        val barTotals = HashMap<String, Double>()
        val barCounts = HashMap<String, Int>()
        for (testIndex in folds.indices) {
            val trainingExamples = folds.filterIndexed { index, _ -> index != testIndex }.flatten()
            val training = trainingExamples.map { it.trainingRow() }
            // Do not silently discard difficult folds and report only easy artists.
            val model = StyleLearner.fit(training) ?: return null
            val majority = StyleLearner.styleCounts(training).firstOrNull()?.first ?: return null
            // The bars come from the training artists of this fold alone. The
            // held-out artists are unseen by the thresholds exactly as they
            // are by the weights, so the scores below stay honest.
            val thresholds = StyleThresholds.tune(trainingExamples, minPrecision)
            for ((style, bar) in thresholds) {
                barTotals[style] = (barTotals[style] ?: 0.0) + bar
                barCounts[style] = (barCounts[style] ?: 0) + 1
            }
            for (example in folds[testIndex]) {
                val truth = example.labels.toSet()
                results.add(truth to model.predict(example.features, thresholds).toSet())
                baselineResults.add(truth to setOf(majority))
            }
        }
        return StyleValidationReport(
            StyleMetrics.measure(results), StyleMetrics.measure(baselineResults),
            examples.map { it.artistKey }.distinct().size, folds.size,
            barTotals.mapValues { (style, total) -> total / (barCounts[style] ?: 1) }
        )
    }
}
