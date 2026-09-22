package com.elchanan.rhythm.engine

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * The report card of the recommendation engine, and the one place its weights
 * are learned instead of guessed.
 *
 * The question asked is the one the app exists to answer: before you have
 * heard a song, can it tell whether you will love it? Every song the listening
 * has clearly answered for - loved, or rejected - is scored blind, with its own
 * plays, likes and skips taken out, and the score is compared with the answer.
 *
 * Then the same comparison is used to learn how much each signal should count
 * for this listener. Someone who loves whole artists and someone who loves a
 * sound get different weights. The learned weights are kept only when they
 * predict better, measured on artists they were not learned from - a weighting
 * that merely memorised which artists this person likes would not survive that.
 */
object SignalCalibration {

    /** One answered song: whose it is, its blind signals, and the answer. */
    class Row(val group: String, val signals: DoubleArray, val positive: Boolean)

    class Report(
        val positives: Int,
        val negatives: Int,
        /** 0.5..1: how often a loved song is scored above a rejected one, with the weights compared against */
        val currentAuc: Double?,
        /** the same with learned weights, measured on held-out artists; null when there was too little to learn from */
        val learnedAuc: Double?,
        /** each signal alone */
        val perSignal: List<Double?>,
        /** of the songs placed in the top fifth, how many were loved */
        val currentTopHit: Double?,
        val learnedTopHit: Double?,
        /** how many of the answered songs were loved, the chance level for [currentTopHit] */
        val baseRate: Double,
        /** the weights learned from everything, or null */
        val weights: SignalWeights?,
        /** whether [weights] beat the compared weights by enough to be worth using */
        val accepted: Boolean
    )

    const val MIN_EACH = 15
    const val MIN_GAIN = 0.02
    private const val FOLDS = 5
    private const val STEPS = 600
    private const val RATE = 0.5
    private const val RIDGE = 0.02

    /** Each learned weight stays within this range of the default, so no signal is switched off entirely. */
    private const val FLOOR = 0.25
    private const val CEILING = 3.0

    fun run(rows: List<Row>, current: SignalWeights = SignalWeights.DEFAULT): Report {
        val positives = rows.count { it.positive }
        val negatives = rows.size - positives
        val labels = rows.map { it.positive }
        val baseRate = if (rows.isEmpty()) 0.0 else positives.toDouble() / rows.size
        val cur = current.toArray()
        val currentScores = rows.map { dot(cur, it.signals) }
        val currentAuc = auc(currentScores, labels)
        val perSignal = (0 until SIGNALS).map { i -> auc(rows.map { it.signals[i] }, labels) }
        val currentTop = topHit(currentScores, labels)

        if (positives < MIN_EACH || negatives < MIN_EACH) {
            return Report(
                positives, negatives, currentAuc, null, perSignal, currentTop, null, baseRate,
                null, false
            )
        }

        // Held-out scores: each artist is scored by weights learned without it.
        val groups = rows.map { it.group }.distinct().sorted()
        val foldOf = HashMap<String, Int>()
        val bySize = groups.sortedByDescending { g -> rows.count { it.group == g } }
        bySize.forEachIndexed { i, g -> foldOf[g] = i % FOLDS }
        val held = DoubleArray(rows.size)
        var usable = true
        for (fold in 0 until FOLDS) {
            val train = rows.filter { foldOf[it.group] != fold }
            val test = rows.indices.filter { foldOf[rows[it].group] == fold }
            if (test.isEmpty()) continue
            val w = fit(train)
            if (w == null) {
                usable = false
                break
            }
            for (i in test) held[i] = dot(w, rows[i].signals)
        }
        val learnedAuc = if (usable) auc(held.toList(), labels) else null
        val learnedTop = if (usable) topHit(held.toList(), labels) else null

        val all = fit(rows)
        val weights = all?.let { SignalWeights.of(it) }
        val accepted = weights != null && learnedAuc != null && currentAuc != null &&
            learnedAuc >= currentAuc + MIN_GAIN
        return Report(
            positives, negatives, currentAuc, learnedAuc, perSignal, currentTop, learnedTop, baseRate,
            weights, accepted
        )
    }

    /**
     * Non-negative logistic regression, rescaled to the size of the defaults
     * and kept within [FLOOR]..[CEILING] of each of them.
     *
     * Non-negative because every one of these signals is built to point the
     * right way; a negative weight would mean "the more it sounds like what you
     * love, the less you will like it", which is overfitting and not taste. The
     * rescale keeps the five in proportion with the rest of the score - the
     * song's own history, time of day, discovery - which were tuned against them.
     */
    internal fun fit(rows: List<Row>): DoubleArray? {
        if (rows.none { it.positive } || rows.all { it.positive }) return null
        val n = rows.size.toDouble()
        val pos = rows.count { it.positive }
        // balance the classes so a library of mostly loved songs does not teach "everything is loved"
        val wPos = n / (2.0 * pos)
        val wNeg = n / (2.0 * (rows.size - pos))
        val w = DoubleArray(SIGNALS) { 0.5 }
        var bias = 0.0
        repeat(STEPS) {
            val grad = DoubleArray(SIGNALS)
            var gBias = 0.0
            for (row in rows) {
                val p = sigmoid(bias + dot(w, row.signals))
                val y = if (row.positive) 1.0 else 0.0
                val err = (p - y) * if (row.positive) wPos else wNeg
                for (i in 0 until SIGNALS) grad[i] += err * row.signals[i]
                gBias += err
            }
            for (i in 0 until SIGNALS) {
                w[i] = (w[i] - RATE * (grad[i] / n + RIDGE * w[i])).coerceAtLeast(0.0)
            }
            bias -= RATE * gBias / n
        }
        val defaults = SignalWeights.DEFAULT.toArray()
        val total = w.sum()
        if (total < 1e-6) return null
        val scale = defaults.sum() / total
        val out = DoubleArray(SIGNALS) { i ->
            (w[i] * scale).coerceIn(defaults[i] * FLOOR, defaults[i] * CEILING)
        }
        // the clamp can move the total; bring it back once
        val again = defaults.sum() / out.sum()
        return DoubleArray(SIGNALS) { out[it] * again }
    }

    /**
     * The probability that a loved song is scored above a rejected one. 0.5 is
     * a coin toss, 1 is perfect. Ties count half, so a signal that says nothing
     * about most songs is not rewarded for its silence.
     */
    fun auc(scores: List<Double>, labels: List<Boolean>): Double? {
        val pos = labels.count { it }
        val neg = labels.size - pos
        if (pos == 0 || neg == 0) return null
        val order = scores.indices.sortedBy { scores[it] }
        var rankSum = 0.0
        var i = 0
        while (i < order.size) {
            var j = i
            while (j + 1 < order.size && abs(scores[order[j + 1]] - scores[order[i]]) < 1e-12) j++
            val rank = (i + j) / 2.0 + 1.0
            for (k in i..j) if (labels[order[k]]) rankSum += rank
            i = j + 1
        }
        return (rankSum - pos * (pos + 1) / 2.0) / (pos.toDouble() * neg)
    }

    /** Of the songs scored in the top fifth, the share that were loved. */
    private fun topHit(scores: List<Double>, labels: List<Boolean>): Double? {
        if (scores.size < 10) return null
        val top = scores.indices.sortedByDescending { scores[it] }.take(scores.size / 5)
        return top.count { labels[it] }.toDouble() / top.size
    }

    /** The report in words, for the settings screen. */
    fun describe(report: Report): String = buildString {
        val answered = report.positives + report.negatives
        append("נבדקו $answered שירים שההאזנה שלך כבר ענתה עליהם: ")
        append("${report.positives} אהובים ו-${report.negatives} שנדחו.\n")
        if (report.currentAuc == null) {
            append("עוד אין מספיק שירים משני הסוגים כדי לבדוק.")
            return@buildString
        }
        append("\nעיוור — בלי ההיסטוריה של השיר עצמו:\n")
        append("• ציון החיזוי: ${grade(report.currentAuc)} (50 = ניחוש, 100 = מושלם)\n")
        report.currentTopHit?.let {
            append("• מהשירים שהאלגוריתם שם בראש, ${pct(it)} אהבת — ")
            append("לעומת ${pct(report.baseRate)} בממוצע\n")
        }
        append("\nכל אות לבד:\n")
        report.perSignal.forEachIndexed { i, value ->
            append("• ${SignalWeights.LABELS[i]}: ${value?.let { grade(it) } ?: "—"}\n")
        }
        if (report.learnedAuc == null) {
            append("\nכדי ללמוד משקלים אישיים צריך לפחות $MIN_EACH שירים אהובים ו-$MIN_EACH שנדחו.")
            return@buildString
        }
        append("\nמשקלים שנלמדו מההאזנה שלך (נבדקו על אמנים שלא נלמדו מהם):\n")
        append("• ציון החיזוי: ${grade(report.learnedAuc)}\n")
        report.learnedTopHit?.let { append("• מהשירים בראש: ${pct(it)} אהבת\n") }
        report.weights?.let { w ->
            val d = SignalWeights.DEFAULT.toArray()
            val l = w.toArray()
            append("• ")
            append(
                SignalWeights.LABELS.indices.joinToString(" · ") { i ->
                    "${SignalWeights.LABELS[i]} ×${"%.1f".format(java.util.Locale.ROOT, l[i] / d[i])}"
                }
            )
            append("\n")
        }
        append(
            if (report.accepted) "\nהמשקלים האישיים חוזים טוב יותר — כדאי להפעיל אותם."
            else "\nהמשקלים האישיים לא חוזים מספיק טוב יותר, אז כדאי להשאיר את הרגילים."
        )
    }

    private fun grade(auc: Double) = (auc * 100).roundToInt().toString()
    private fun pct(v: Double) = "${(v * 100).roundToInt()}%"

    private const val SIGNALS = 5

    private fun dot(w: DoubleArray, x: DoubleArray): Double {
        var s = 0.0
        for (i in 0 until SIGNALS) s += w[i] * x[i]
        return s
    }

    private fun sigmoid(z: Double) = 1.0 / (1.0 + exp(-z.coerceIn(-30.0, 30.0)))
}
