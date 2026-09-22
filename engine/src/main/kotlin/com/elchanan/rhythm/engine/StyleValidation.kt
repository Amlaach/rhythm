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
        /** Each pair is (user labels, predicted labels); every extra/missing label counts. */
        fun measure(results: List<Pair<Set<String>, Set<String>>>): StyleMetrics {
            val labels = results.flatMap { (truth, prediction) -> truth + prediction }.toSortedSet()
            return StyleMetrics(labels.associateWith { label ->
                StyleScore(
                    results.count { (truth, prediction) -> label in truth && label in prediction },
                    results.count { (truth, prediction) -> label !in truth && label in prediction },
                    results.count { (truth, prediction) -> label in truth && label !in prediction }
                )
            }, results.size)
        }
    }
}

data class StyleValidationReport(
    val metrics: StyleMetrics,
    val baseline: StyleMetrics,
    val artists: Int,
    val folds: Int
)

/**
 * Fixed-parameter, out-of-artist validation. No parameter is selected on the
 * reported test results. Standardisation is fitted inside StyleLearner.fit on
 * raw training features only; held-out artists never contribute to it.
 */
object StyleValidation {
    const val MIN_ARTISTS = 4
    private const val MAX_FOLDS = 5

    /** Whole artists assigned to balanced folds, independently of sound or labels. */
    internal fun folds(examples: List<StyleExample>): List<List<StyleExample>> {
        val artists = examples.groupBy { it.artistKey }
        if (artists.size < MIN_ARTISTS) return emptyList()
        val folds = List(minOf(MAX_FOLDS, artists.size)) { mutableListOf<StyleExample>() }
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

    fun evaluate(examples: List<StyleExample>): StyleValidationReport? {
        if (examples.size < StyleLearner.MIN_ROWS_TO_VALIDATE) return null
        val folds = folds(examples)
        if (folds.isEmpty()) return null
        val results = ArrayList<Pair<Set<String>, Set<String>>>()
        val baselineResults = ArrayList<Pair<Set<String>, Set<String>>>()
        for (testIndex in folds.indices) {
            val training = folds.filterIndexed { index, _ -> index != testIndex }
                .flatten().map { it.trainingRow() }
            // Do not silently discard difficult folds and report only easy artists.
            val model = StyleLearner.fit(training) ?: return null
            val majority = StyleLearner.styleCounts(training).firstOrNull()?.first ?: return null
            for (example in folds[testIndex]) {
                val truth = example.labels.toSet()
                results.add(truth to model.predict(example.features).toSet())
                baselineResults.add(truth to setOf(majority))
            }
        }
        return StyleValidationReport(
            StyleMetrics.measure(results), StyleMetrics.measure(baselineResults),
            examples.map { it.artistKey }.distinct().size, folds.size
        )
    }
}
