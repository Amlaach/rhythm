package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.db.AudioFeatureEntity
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.data.db.SongStatsEntity

enum class LearningStatus {
    NO_LABELS, NO_AUDIO, NO_OVERLAP, TOO_FEW_SONGS, TOO_FEW_ARTISTS,
    NOT_VALIDATABLE, QUALITY_TOO_LOW, NO_CANDIDATES, NO_CONFIDENT_PREDICTIONS, READY
}

data class LearnResult(
    val status: LearningStatus,
    val labelled: Int,
    val artists: Int,
    val withStyles: Int,
    val withEvidence: Int,
    val validation: StyleValidationReport? = null,
    val predictions: List<Pair<Long, String>> = emptyList()
) {
    /** Only genuinely new/changed assignments are returned for storage. */
    val applied: Int get() = predictions.size
}

/**
 * Validation estimates agreement with user labels on unseen artists; it does
 * not certify genre truth or the accuracy of the underlying audio analyser.
 * These gates are conservative product policy, not statistical guarantees.
 */
object StyleLearning {
    const val MIN_MACRO_F1 = 0.70
    const val MIN_PRECISION = 0.80
    const val MIN_BASELINE_GAIN = 0.05

    internal fun passesQuality(report: StyleValidationReport): Boolean =
        report.metrics.macroF1 >= MIN_MACRO_F1 &&
            report.metrics.macroPrecision >= MIN_PRECISION &&
            report.metrics.macroF1 >= report.baseline.macroF1 + MIN_BASELINE_GAIN

    fun learn(
        songs: List<SongEntity>,
        stats: Map<Long, SongStatsEntity>,
        stylesByArtist: Map<String, String>,
        features: Map<Long, AudioFeatureEntity>
    ): LearnResult {
        val withStyles = songs.count { song ->
            val own = stats[song.id]
            (own?.stylesAuto == 0 && Styles.parse(own.styles).isNotEmpty()) ||
                Styles.parse(stylesByArtist[song.artistKey].orEmpty()).isNotEmpty()
        }
        val withEvidence = songs.count { StyleTraining.featuresFor(features[it.id]) != null }
        val rows = StyleTraining.rows(songs, features, stylesByArtist, stats)
        val artists = rows.map { it.artistKey }.distinct().size
        fun result(
            status: LearningStatus,
            validation: StyleValidationReport? = null,
            predictions: List<Pair<Long, String>> = emptyList()
        ) = LearnResult(status, rows.size, artists, withStyles, withEvidence, validation, predictions)

        if (withStyles == 0) return result(LearningStatus.NO_LABELS)
        if (withEvidence == 0) return result(LearningStatus.NO_AUDIO)
        if (rows.isEmpty()) return result(LearningStatus.NO_OVERLAP)
        if (rows.size < StyleLearner.MIN_ROWS_TO_VALIDATE) return result(LearningStatus.TOO_FEW_SONGS)
        if (artists < StyleValidation.MIN_ARTISTS) return result(LearningStatus.TOO_FEW_ARTISTS)

        val validation = StyleValidation.evaluate(rows)
            ?: return result(LearningStatus.NOT_VALIDATABLE)
        if (!passesQuality(validation)) return result(LearningStatus.QUALITY_TOO_LOW, validation)

        val model = StyleLearner.fit(rows.map { it.trainingRow() })
            ?: return result(LearningStatus.NOT_VALIDATABLE, validation)
        var candidates = 0
        val predictions = ArrayList<Pair<Long, String>>()
        for (song in songs) {
            val own = stats[song.id]
            val current = Styles.parse(own?.styles.orEmpty())
            if (current.isNotEmpty() && own?.stylesAuto != 1) continue
            if (Styles.parse(stylesByArtist[song.artistKey].orEmpty()).isNotEmpty()) continue
            val x = StyleTraining.featuresFor(features[song.id]) ?: continue
            candidates++
            val predicted = model.predict(x)
            if (predicted.isEmpty() || predicted.toSet() == current.toSet()) continue
            predictions.add(song.id to Styles.join(predicted))
        }
        return result(
            when {
                predictions.isNotEmpty() -> LearningStatus.READY
                candidates == 0 -> LearningStatus.NO_CANDIDATES
                else -> LearningStatus.NO_CONFIDENT_PREDICTIONS
            },
            validation, predictions
        )
    }

    fun message(r: LearnResult?): String {
        if (r == null) return "הלמידה נכשלה. נסה שוב."
        return when (r.status) {
            LearningStatus.NO_LABELS ->
                "אין תגיות ידניות ללמידה. סמן סגנונות לאמנים או לשירים ונסה שוב."
            LearningStatus.NO_AUDIO ->
                "אין נתוני צליל תקינים ללמידה. הרץ ניתוח אודיו בהגדרות."
            LearningStatus.NO_OVERLAP ->
                "יש ${r.withStyles} שירים מתויגים ו-${r.withEvidence} עם נתוני צליל, אבל חסרות דוגמאות שמשלבות את שניהם ואמן מזוהה."
            LearningStatus.TOO_FEW_SONGS ->
                "יש ${r.labelled} דוגמאות. צריך לפחות ${StyleLearner.MIN_ROWS_TO_VALIDATE} שירים מתויגים עם נתוני צליל."
            LearningStatus.TOO_FEW_ARTISTS ->
                "יש ${r.artists} אמנים בדוגמאות. צריך לפחות ${StyleValidation.MIN_ARTISTS} אמנים כדי לבדוק על אמנים שלא השתתפו באימון."
            LearningStatus.NOT_VALIDATABLE ->
                "אין מספיק דוגמאות מגוונות בכל חלוקה לפי אמנים. הוסף אמנים ושירים בסגנונות שונים; לא שונו תגיות."
            LearningStatus.QUALITY_TOO_LOW ->
                "הבדיקה לא עמדה בתנאי האיכות או לא שיפרה מספיק את ניחוש הסגנון הנפוץ. לא שונו תגיות."
            LearningStatus.NO_CANDIDATES ->
                "הבדיקה עברה. אין שירים עם נתוני צליל שחסרות להם תגיות ידניות או תגיות אמן."
            LearningStatus.NO_CONFIDENT_PREDICTIONS ->
                "הבדיקה עברה, אבל אין תגיות חדשות מספיק בטוחות להוספה. לא שונו תגיות."
            LearningStatus.READY -> "עודכנו תגיות אוטומטיות ב-${r.applied} שירים."
        }
    }

    /** Kept on the settings screen, not only in a transient notification. */
    fun report(r: LearnResult?): String = buildString {
        append(message(r))
        if (r == null) return@buildString
        append("\nדוגמאות ללמידה: ${r.labelled} שירים מ-${r.artists} אמנים.")
        val v = r.validation ?: return@buildString
        append("\nנבדקו ${v.metrics.songs} שירים מ-${v.artists} אמנים ב-${v.folds} חלוקות.")
        append("\nציון משולב לתגיות נכונות וחסרות (F1): ${percent(v.metrics.macroF1)}")
        append("\nדיוק התגיות שהוצעו: ${percent(v.metrics.macroPrecision)}")
        append("\nכיסוי התגיות הידועות: ${percent(v.metrics.macroRecall)}")
        append("\nציון ניחוש הסגנון הנפוץ: ${percent(v.baseline.macroF1)}")
        for ((style, score) in v.metrics.byStyle) {
            append("\n$style: F1 ${percent(score.f1)}, דיוק ${percent(score.precision)}, כיסוי ${percent(score.recall)}")
        }
        append("\nהמדדים הם ממוצע שווה בין סגנונות, מול התיוג שלך; אינם הבטחת דיוק לז׳אנרים.")
        append("\nנדרשים F1 של 70%, דיוק של 80% ושיפור של 5 נקודות אחוז על ניחוש פשוט.")
    }

    private fun percent(value: Double): String = "${(value * 100).toInt()}%"
}
