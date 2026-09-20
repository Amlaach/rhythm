package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.db.AudioFeatureEntity
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.data.db.SongStatsEntity

/**
 * What one run of the style learner produced, and why.
 *
 * The counts are here because "not enough information" is true of every
 * failure this can have and useless in all of them. Four different dead ends
 * used to share one sentence about needing more tagged songs, and for three
 * of them that sentence was simply false.
 */
data class LearnResult(
    val accuracy: Double?,
    val trained: Int,
    val labelled: Int,
    /** Songs whose artist carries style tags: the labels to learn from. */
    val withStyles: Int = 0,
    /** Songs with something measured or heard: the evidence to learn from. */
    val withEvidence: Int = 0,
    /** How many tagged songs carry each style, most common first. */
    val counts: List<Pair<String, Int>> = emptyList(),
    /**
     * What the model would write, as song id to style words.
     *
     * Returned rather than written, because writing is the one part of this
     * that differs between a Room database and a JDBC one. The caller stores
     * them; [applied] is their count.
     */
    val predictions: List<Pair<Long, String>> = emptyList()
) {
    val applied: Int get() = predictions.size
}

/**
 * Learns the user's own style words from their own library, and fills in the
 * songs they never tagged.
 *
 * Measured before it is trusted. The model is fitted on half the tagged songs
 * and scored on the other half, and if it cannot beat a coin toss by a clear
 * margin nothing is written - a library quietly filled with wrong labels is
 * worse than one with no labels, because the wrong ones then feed the
 * recommender as though somebody had confirmed them.
 *
 * This is the orchestration, not the mathematics: [StyleLearner] fits and
 * validates, [StyleTraining] builds the rows, and this decides what to do
 * with the answer. It lives in :engine because both builds offer it, and a
 * model that learned different things on a phone and on a desktop from the
 * same library and the same tags would be a bug nobody could see.
 */
object StyleLearning {

    /** Below this, the held-out score does not say the model generalises. */
    const val MIN_ACCURACY = 0.70

    /** Below this many analysed songs the acoustic space measures against noise. */
    private const val MIN_FOR_SPACE = 8

    fun learn(
        songs: List<SongEntity>,
        stats: Map<Long, SongStatsEntity>,
        stylesByArtist: Map<String, String>,
        features: Map<Long, AudioFeatureEntity>
    ): LearnResult {
        val tagsBySong = features.mapValues { it.value.tags }

        // The measured half of the evidence, built from the same rows the
        // recommender uses - so "loud" and "fast" mean here exactly what they
        // mean everywhere else in the app.
        val space = if (features.size >= MIN_FOR_SPACE) AcousticSpace(features.values) else null

        // Counted separately so the caller can name the half that is missing.
        val withStyles = songs.count {
            Styles.parse(stylesByArtist[it.artistKey].orEmpty()).isNotEmpty()
        }
        val withEvidence = songs.count {
            StyleTraining.featuresFor(it.id, tagsBySong[it.id].orEmpty(), space) != null
        }

        val rows = StyleTraining.rows(songs, tagsBySong, stylesByArtist, space)
        val counts = StyleLearner.styleCounts(rows)
        if (rows.isEmpty()) {
            return LearnResult(null, 0, 0, withStyles, withEvidence)
        }

        // The held-out half also picks how hard to regularise; the final model
        // is then fitted with that same strength, not a different one.
        val validation = StyleLearner.crossValidate(rows)
        val accuracy = validation?.accuracy
        val model = StyleLearner.fit(rows, l2 = validation?.l2 ?: 0.1)
            ?: return LearnResult(accuracy, 0, rows.size, withStyles, withEvidence, counts)

        if (accuracy == null || accuracy < MIN_ACCURACY) {
            return LearnResult(accuracy, rows.size, rows.size, withStyles, withEvidence, counts)
        }

        // Written where the user left a blank, and over the app's own earlier
        // guesses. Their words are the ground truth this was trained on and
        // are never touched; a guess is only as good as the model that made
        // it, and the model is better now than it was the first time this ran.
        val predictions = ArrayList<Pair<Long, String>>()
        for (song in songs) {
            val own = stats[song.id]
            val hasOwn = Styles.parse(own?.styles.orEmpty()).isNotEmpty()
            if (hasOwn && own?.stylesAuto != 1) continue
            if (Styles.parse(stylesByArtist[song.artistKey].orEmpty()).isNotEmpty()) continue
            // The same vector the model was fitted on. Predicting from a
            // different shape than it was trained on is the easiest way to get
            // confident nonsense.
            val x = StyleTraining.featuresFor(song.id, tagsBySong[song.id].orEmpty(), space)
                ?: continue
            val predicted = model.predict(x)
            if (predicted.isEmpty()) continue
            predictions.add(song.id to Styles.join(predicted))
        }
        return LearnResult(
            accuracy, rows.size, rows.size, withStyles, withEvidence, counts, predictions
        )
    }

    /** What to tell the user, in terms they can act on. */
    fun message(r: LearnResult?): String = when {
        r == null -> "הלמידה נכשלה"
        r.labelled == 0 && r.withStyles == 0 ->
            "אף אמן לא תויג בסגנון. הלמידה לומדת מהתגיות שלך — סמן סגנונות " +
                "לכמה אמנים בטאב \"אמנים\" ונסה שוב."
        r.labelled == 0 && r.withEvidence == 0 ->
            "אף שיר עוד לא נותח. הרץ ניתוח אודיו בהגדרות וחזור לכאן."
        r.labelled == 0 ->
            "${r.withStyles} שירים מתויגים ו-${r.withEvidence} מנותחים, " +
                "אבל אלה לא אותם שירים."
        r.accuracy == null || r.trained == 0 -> whyNotLearned(r)
        r.applied == 0 -> "דיוק נמדד: ${percent(r.accuracy)} — נמוך מדי, לא שיניתי כלום"
        else -> "דיוק נמדד: ${percent(r.accuracy)} · תויגו ${r.applied} שירים"
    }

    /**
     * Why learning produced nothing usable.
     *
     * A library can be past every count and still unlearnable because almost
     * every song carries the same style word: with nothing outside it there is
     * no contrast to learn from, and the classifier is dropped before it is
     * ever fitted. Telling someone with fifty six tagged songs that thirty two
     * are needed is advice they followed long ago, and it points them at the
     * one thing that would not have helped.
     */
    private fun whyNotLearned(r: LearnResult): String {
        val floor = StyleLearner.DEFAULT_MIN_PER_STYLE
        if (r.labelled < StyleLearner.MIN_ROWS_TO_VALIDATE) {
            return "יש ${r.labelled} שירים מתויגים. צריך לפחות " +
                "${StyleLearner.MIN_ROWS_TO_VALIDATE} כדי לבדוק אם הלמידה נכונה."
        }
        val top = r.counts.firstOrNull()
            ?: return "יש ${r.labelled} שירים מתויגים, אבל אין בהם אף סגנון."
        if (r.trained > 0) {
            // Enough to fit on everything, not enough to fit on half and be
            // scored on the other half - and nothing is written without that
            // score, so this still ends with no labels.
            return "נלמדו סגנונות, אבל לא היה אפשר לבדוק את הדיוק על חצי מהשירים. " +
                "עוד אמנים מתויגים יאפשרו את הבדיקה."
        }
        if (top.second > r.labelled - floor) {
            return "${top.second} מתוך ${r.labelled} השירים המתויגים מסומנים \"${top.first}\". " +
                "כדי ללמוד מה מייחד סגנון צריך גם שירים שאינם בו — תייג אמנים " +
                "בסגנונות אחרים, ולא עוד אמנים באותו סגנון."
        }
        return "אף סגנון לא הגיע ל-$floor שירים. הנפוץ ביותר, \"${top.first}\", " +
            "מופיע ב-${top.second}."
    }

    private fun percent(value: Double?): String =
        if (value == null) "—" else "${(value * 100).toInt()}%"
}
