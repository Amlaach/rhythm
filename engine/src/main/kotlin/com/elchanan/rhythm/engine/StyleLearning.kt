package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.db.AudioFeatureEntity
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.data.db.SongStatsEntity

enum class LearningStatus {
    NO_LABELS, NO_AUDIO, NO_OVERLAP, TOO_FEW_SONGS, TOO_FEW_ARTISTS,
    NOT_VALIDATABLE, QUALITY_TOO_LOW, NO_CANDIDATES, NO_CONFIDENT_PREDICTIONS, READY
}

/**
 * What happened to one style, start to finish.
 *
 * Per style rather than per run, because the run's verdict is no longer one
 * verdict. A library can know exactly what חסידי sounds like and have no idea
 * about ג'אז, and the honest answer is to say so for each and write only the
 * ones that earned it.
 */
data class StyleOutcome(
    val style: String,
    /** Labelled songs carrying this style, which is what it was fitted on. */
    val examples: Int,
    /**
     * How many different artists carry it.
     *
     * The number that decides whether a style could ever have worked. The test
     * splits by artist, so a style living in one artist is absent from
     * training exactly when it is present in the test - it scores zero however
     * many songs it has, and no amount of tagging more songs by that same
     * artist changes it.
     */
    val artists: Int,
    /**
     * The most examples any fold could train on while still testing it.
     *
     * Below [StyleLearner.DEFAULT_MIN_PER_STYLE] the style was never fitted
     * in a fold that could score it, so its zero is the absence of a test
     * rather than the result of one.
     */
    val trainable: Int,
    /** How it did on artists it never trained on, or null if never fitted. */
    val score: StyleScore?,
    /** What guessing the commonest style would have scored on this one. */
    val baseline: StyleScore?,
    /** The confidence bar this style had to clear, or null for the default. */
    val threshold: Double?,
    val accepted: Boolean,
    /** Why it was accepted or refused, in the words the screen shows. */
    val reason: String,
    /** Songs this style was actually written to. */
    val applied: Int = 0,
    /**
     * What the model leans on when it names this style, strongest first.
     *
     * Shown so a style can be read as well as scored. Four of the inputs are
     * moods, and a style whose top influences are רגוע and קצב was not learned
     * as a kind of music at all - it was learned as a tempo, and it will break
     * on the first fast song of that kind. The scores cannot show that; this
     * can.
     */
    val influences: List<Pair<String, Double>> = emptyList()
)

data class LearnResult(
    val status: LearningStatus,
    val labelled: Int,
    val artists: Int,
    val withStyles: Int,
    val withEvidence: Int,
    val validation: StyleValidationReport? = null,
    val predictions: List<Pair<Long, String>> = emptyList(),
    /** Every style the run considered, accepted or not. */
    val styles: List<StyleOutcome> = emptyList(),
    /** Songs that were eligible to receive a tag. */
    val candidates: Int = 0
) {
    /** Only genuinely new/changed assignments are returned for storage. */
    val applied: Int get() = predictions.size

    val accepted: List<StyleOutcome> get() = styles.filter { it.accepted }
    val rejected: List<StyleOutcome> get() = styles.filter { !it.accepted }
}

/**
 * Validation estimates agreement with user labels on unseen artists; it does
 * not certify genre truth or the accuracy of the underlying audio analyser.
 * These gates are conservative product policy, not statistical guarantees.
 */
object StyleLearning {
    /**
     * The bars, applied to each style on its own rather than to an average.
     *
     * Named without "macro" since the change: they are what one style has to
     * reach, and the macro average they used to govern is now a summary line
     * that decides nothing.
     */
    const val MIN_F1 = 0.70
    const val MIN_PRECISION = 0.80
    const val MIN_BASELINE_GAIN = 0.05

    /**
     * Artists a style needs before it can be tested at all.
     *
     * Two is the arithmetic minimum and three is the number that actually
     * works: the folds are balanced by song count and know nothing about
     * styles, so two artists can land in the same fold and leave the style
     * missing from a training set again.
     */
    const val MIN_ARTISTS_PER_STYLE = 2

    /**
     * Whether the run as a whole cleared the bar, averaged over every style.
     *
     * Kept as the summary line, and no longer the gate. Averaging is the
     * wrong way to decide what to write: it lets a style the model knows
     * nothing about veto one it knows perfectly, and lets several mediocre
     * styles carry one bad one through. [trustedStyles] decides; this only
     * describes.
     */
    internal fun passesQuality(report: StyleValidationReport): Boolean =
        report.metrics.macroF1 >= MIN_F1 &&
            report.metrics.macroPrecision >= MIN_PRECISION &&
            report.metrics.macroF1 >= report.baseline.macroF1 + MIN_BASELINE_GAIN

    /**
     * Why one style may or may not be written, in the words the screen shows.
     *
     * Every style is judged on its own evidence. The point of the whole
     * change: a library can separate חסידי perfectly and ג'אז not at all, and
     * one average over the two describes neither. Under a single average the
     * good style is silenced by the bad one - which is precisely the failure
     * this replaces.
     *
     * The comparison against the majority guess is per style too. For the
     * commonest style that guess is a real opponent - always answering
     * "חסידי" in a mostly חסידי library scores well without knowing anything -
     * so beating it means something. For a rare style the guess scores zero
     * and the real bars are precision and F1.
     */
    internal fun verdict(
        style: String,
        score: StyleScore?,
        baseline: StyleScore?
    ): Pair<Boolean, String> {
        if (score == null) return false to "לא נלמד"
        val baselineF1 = baseline?.f1 ?: 0.0
        return when {
            score.truePositives + score.falsePositives == 0 ->
                false to "לא הוצע אף פעם על אמנים שלא באימון"
            score.precision < MIN_PRECISION ->
                false to "דיוק ${percent(score.precision)} — נדרש ${percent(MIN_PRECISION)}"
            score.f1 < MIN_F1 ->
                false to "F1 ${percent(score.f1)} — נדרש ${percent(MIN_F1)}"
            score.f1 < baselineF1 + MIN_BASELINE_GAIN ->
                false to "לא משפר מספיק על ניחוש הסגנון הנפוץ (${percent(baselineF1)})"
            else -> true to "עומד בתנאים"
        }
    }

    /** The styles that earned the right to be written into the library. */
    internal fun trustedStyles(report: StyleValidationReport): Set<String> =
        report.metrics.byStyle
            .filter { (style, score) -> verdict(style, score, report.baseline.byStyle[style]).first }
            .keys

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
            predictions: List<Pair<Long, String>> = emptyList(),
            styles: List<StyleOutcome> = emptyList(),
            candidates: Int = 0
        ) = LearnResult(
            status, rows.size, artists, withStyles, withEvidence,
            validation, predictions, styles, candidates
        )

        if (withStyles == 0) return result(LearningStatus.NO_LABELS)
        if (withEvidence == 0) return result(LearningStatus.NO_AUDIO)
        if (rows.isEmpty()) return result(LearningStatus.NO_OVERLAP)
        if (rows.size < StyleLearner.MIN_ROWS_TO_VALIDATE) return result(LearningStatus.TOO_FEW_SONGS)
        if (artists < StyleValidation.MIN_ARTISTS) return result(LearningStatus.TOO_FEW_ARTISTS)

        val validation = StyleValidation.evaluate(rows)
            ?: return result(LearningStatus.NOT_VALIDATABLE)

        // How many labelled songs carry each style, which is the other half of
        // every per style line: a score of 100% off nine examples and off nine
        // hundred are not the same claim.
        val counts = StyleLearner.styleCounts(rows.map { it.trainingRow() }).toMap()
        val fitted = StyleLearner.eligibleStyles(rows.map { it.trainingRow() }).toSet()
        // Distinct artists per style. Counted here because it is the one
        // diagnosis the scores themselves cannot give: a style with a hundred
        // songs by one singer and a style with a hundred songs by ten singers
        // look identical in every column except this one, and only the second
        // can be learned.
        val trainable = StyleValidation.trainableCounts(rows)
        val artistsPerStyle = rows
            .flatMap { row -> row.labels.distinct().map { it to row.artistKey } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, keys) -> keys.distinct().size }

        fun outcomes(
            applied: Map<String, Int> = emptyMap(),
            fittedModel: StyleLearner? = null
        ): List<StyleOutcome> {
            val seen = validation.metrics.byStyle.keys + counts.keys
            return seen.sortedWith(
                compareByDescending<String> { counts[it] ?: 0 }.thenBy { it }
            ).map { style ->
                val score = validation.metrics.byStyle[style]
                val baseline = validation.baseline.byStyle[style]
                val artists = artistsPerStyle[style] ?: 0
                val reachable = trainable[style] ?: 0
                val (ok, why) = when {
                    // Before anything about scores: a style on one artist
                    // cannot pass, whatever it scored, and the fix is a
                    // different one - another singer with the same sound, not
                    // more songs by the one already there.
                    artists in 1 until MIN_ARTISTS_PER_STYLE ->
                        false to "רק אצל אמן אחד — הבדיקה מחלקת לפי אמנים, " +
                            "אז סגנון כזה נכשל תמיד. תייג בו עוד אמן"
                    style !in fitted && counts.containsKey(style) ->
                        false to "רק ${counts[style]} דוגמאות — צריך ${StyleLearner.DEFAULT_MIN_PER_STYLE} וגם דוגמאות נגד"
                    // Passed the headline count and still never got fitted.
                    // Said plainly, because the score underneath it is not a
                    // judgement of the style - there was no test to fail.
                    counts.containsKey(style) && reachable < StyleLearner.DEFAULT_MIN_PER_STYLE ->
                        false to "${counts[style]} דוגמאות ב-$artists אמנים — " +
                            "כשמסירים אמן לבדיקה נשארות $reachable בלבד לאימון, " +
                            "פחות מ-${StyleLearner.DEFAULT_MIN_PER_STYLE}. הסגנון לא נלמד כלל"
                    else -> verdict(style, score, baseline)
                }
                StyleOutcome(
                    style = style,
                    examples = counts[style] ?: 0,
                    artists = artists,
                    trainable = reachable,
                    score = score,
                    baseline = baseline,
                    threshold = validation.thresholds[style],
                    accepted = ok,
                    reason = why,
                    applied = applied[style] ?: 0,
                    influences = fittedModel?.influences(style).orEmpty()
                )
            }
        }

        val trusted = trustedStyles(validation)
        // Not "did the average pass" - "is there any style that passed". One
        // style the model genuinely knows is worth writing even when the rest
        // of the library defeats it.
        if (trusted.isEmpty()) {
            return result(LearningStatus.QUALITY_TOO_LOW, validation, styles = outcomes())
        }

        val model = StyleLearner.fit(rows.map { it.trainingRow() })
            ?: return result(LearningStatus.NOT_VALIDATABLE, validation, styles = outcomes())

        // The production bars, calibrated the same way the tested ones were,
        // on everything now that nothing is being held out to score.
        val thresholds = StyleThresholds.tune(rows, MIN_PRECISION)

        var candidates = 0
        val predictions = ArrayList<Pair<Long, String>>()
        val appliedByStyle = HashMap<String, Int>()
        for (song in songs) {
            val own = stats[song.id]
            val current = Styles.parse(own?.styles.orEmpty())
            if (current.isNotEmpty() && own?.stylesAuto != 1) continue
            if (Styles.parse(stylesByArtist[song.artistKey].orEmpty()).isNotEmpty()) continue
            val x = StyleTraining.featuresFor(features[song.id]) ?: continue
            candidates++
            val predicted = model.predict(x, thresholds, trusted)
            if (predicted.isEmpty() || predicted.toSet() == current.toSet()) continue
            for (style in predicted) appliedByStyle[style] = (appliedByStyle[style] ?: 0) + 1
            predictions.add(song.id to Styles.join(predicted))
        }
        return result(
            when {
                predictions.isNotEmpty() -> LearningStatus.READY
                candidates == 0 -> LearningStatus.NO_CANDIDATES
                else -> LearningStatus.NO_CONFIDENT_PREDICTIONS
            },
            validation, predictions, outcomes(appliedByStyle, model), candidates
        )
    }

    /**
     * What learning needs before it can write a tag at all.
     *
     * Stated on the settings screens, so the requirement is visible before a
     * run rather than only in the message after one that found too little. The
     * two ways of falling short of it need opposite things from the user - more
     * songs in one case, more variety in the other - and both otherwise arrive
     * in the same shape, after the fact.
     */
    fun requirement(): String =
        "נדרשים לפחות ${StyleLearner.MIN_ROWS_TO_VALIDATE} שירים מתויגים עם נתוני צליל, " +
            "מ-${StyleValidation.MIN_ARTISTS} אמנים לפחות ובשני סגנונות לפחות."

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

    /**
     * The full account of a run, kept on the settings screen rather than
     * flashing past in a notification.
     *
     * Long on purpose. This is the one place the app says what it did to
     * someone's library and why, and a summary that hides which style failed
     * leaves them with no way to act - the fix for a style short of examples
     * is to tag more songs in it, and the fix for one that scores badly is
     * usually that it is not a sound, it is a category. Those need opposite
     * things, and only the per style lines can tell them apart.
     */
    fun report(r: LearnResult?): String = buildString {
        append(message(r))
        if (r == null) return@buildString

        append("\n\nדוגמאות ללמידה: ${r.labelled} שירים מ-${r.artists} אמנים.")
        append("\nבספרייה: ${r.withStyles} שירים מתויגים, ${r.withEvidence} עם נתוני צליל.")

        val v = r.validation ?: return@buildString
        append("\n\nהבדיקה — ${v.metrics.songs} שירים מ-${v.artists} אמנים ב-${v.folds} חלוקות,")
        append(" כל פעם על אמנים שלא השתתפו באימון.")
        append("\nממוצע בין הסגנונות: F1 ${percent(v.metrics.macroF1)}, ")
        append("דיוק ${percent(v.metrics.macroPrecision)}, כיסוי ${percent(v.metrics.macroRecall)}.")
        append("\nניחוש הסגנון הנפוץ, לשם השוואה: F1 ${percent(v.baseline.macroF1)}.")

        if (r.styles.isNotEmpty()) {
            append("\n\nלפי סגנון — כל אחד נבחן בנפרד:")
            for (o in r.styles) {
                append("\n\n${if (o.accepted) "✓" else "✗"} ${o.style} — ${o.reason}")
                append("\n    דוגמאות מתויגות: ${o.examples} · אמנים: ${o.artists}")
                if (o.examples > 0 && o.trainable < StyleLearner.DEFAULT_MIN_PER_STYLE) {
                    append("\n    זמינות לאימון כשאמן מוחזק לבדיקה: ${o.trainable}")
                }
                val score = o.score
                if (score != null) {
                    append("\n    F1 ${percent(score.f1)} · דיוק ${percent(score.precision)}")
                    append(" · כיסוי ${percent(score.recall)}")
                    append("\n    בבדיקה: ${score.truePositives} נכון, ")
                    append("${score.falsePositives} שגוי, ${score.falseNegatives} פוספס")
                    o.baseline?.let { append("\n    ניחוש פשוט על סגנון זה: F1 ${percent(it.f1)}") }
                }
                o.threshold?.let { append("\n    סף הביטחון שנמדד לסגנון: ${percent(it)}") }
                if (o.influences.isNotEmpty()) {
                    append("\n    המודל מקשיב בעיקר ל: ")
                    append(o.influences.joinToString(", ") { (name, weight) ->
                        "$name${if (weight >= 0) "+" else "−"}"
                    })
                }
                if (o.accepted) {
                    append("\n    נכתב ל-${o.applied} שירים")
                }
            }
        }

        if (r.candidates > 0 || r.applied > 0) {
            append("\n\nמה נכתב: ${r.applied} שירים מתוך ${r.candidates} מועמדים")
            append(" (שירים עם נתוני צליל שאין להם תגית ידנית או תגית אמן).")
        }

        val starved = r.styles.filter {
            it.examples > 0 && it.trainable < StyleLearner.DEFAULT_MIN_PER_STYLE &&
                it.artists >= MIN_ARTISTS_PER_STYLE
        }
        if (starved.isNotEmpty()) {
            append("\n\nשים לב: ${starved.joinToString(", ") { it.style }} לא נלמדו כלל. ")
            append("הדרישה של ${StyleLearner.DEFAULT_MIN_PER_STYLE} דוגמאות נבדקת על כל הספרייה, ")
            append("אבל האימון רץ על חלק ממנה בכל פעם — ואחרי שמוציאים אמן לבדיקה ")
            append("לא נשארו מספיק. הציון שלהם אינו שיפוט של הסגנון; לא היה מבחן להיכשל בו. ")
            append("צריך עוד שירים בסגנון, או עוד אמן שנושא אותו.")
        }

        val thin = r.styles.filter { it.artists in 1 until MIN_ARTISTS_PER_STYLE }
        if (thin.isNotEmpty()) {
            append("\n\nשים לב: ${thin.joinToString(", ") { it.style }} ")
            append(if (thin.size == 1) "קיים" else "קיימים")
            append(" רק אצל אמן אחד. הבדיקה מחלקת לפי אמנים כדי למדוד למידה ולא שינון,")
            append(" ולכן סגנון שיש לו אמן אחד בלבד נכשל תמיד — גם אם יש לו מאה שירים.")
            append(" מה שיעזור הוא אמן נוסף שנשמע דומה, לא עוד שירים של אותו אמן.")
        }

        append("\n\nכל סגנון נבחן לחוד ויש לו סף משלו, ")
        append("כך שסגנון שעומד בתנאים מתויג גם אם סגנון אחר נכשל.")
        append("\nנדרש מכל סגנון: F1 ${percent(MIN_F1)}, דיוק ${percent(MIN_PRECISION)}, ")
        append("ושיפור של ${(MIN_BASELINE_GAIN * 100).toInt()} נקודות על ניחוש פשוט.")
        append("\nהמדדים הם מול התיוג שלך, לא הבטחת דיוק לז׳אנרים.")
        if (r.styles.any { it.influences.isNotEmpty() }) {
            append("\n\n\"המודל מקשיב בעיקר ל\" מראה על מה ההחלטה נשענת: + מושך לסגנון, ")
            append("− דוחה ממנו. אם מה שמופיע שם הוא מצב רוח או קצב (שמח, עצוב, רגוע, ")
            append("מרגש, קצב) ולא סוג מוזיקה או כלי נגינה — הסגנון נלמד כמצב רוח ולא ")
            append("כז׳אנר, והוא ייכשל על שיר חריג. הדרך לתקן היא לתייג דוגמאות מגוונות ")
            append("יותר בתוך אותו סגנון, מהיר ואיטי כאחד.")
        }
    }

    private fun percent(value: Double): String = "${(value * 100).toInt()}%"
}
