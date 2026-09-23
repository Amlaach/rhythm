package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.db.AudioFeatureEntity
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.data.db.SongStatsEntity
import kotlin.math.roundToInt

/**
 * Read-only check of the music model's contribution on this listener's library.
 *
 * Genre learning is tested on artists held out of training. Both variants use
 * exactly the same songs: only those with a music print. Mood comparisons use
 * the same manually marked songs and the same library on both sides; only the
 * MTG mood readings are removed for the baseline. Neither check writes tags.
 */
object MusicModelEvaluation {
    private const val MIN_EACH_MOOD = 5

    data class MoodComparison(
        val mood: Mood,
        val yes: Int,
        val no: Int,
        val withMusic: Double,
        val withoutMusic: Double
    )

    data class Report(
        val labelledSongs: Int,
        val printedLabelledSongs: Int,
        val style: LearnResult,
        val sound: SoundCheck.Result?,
        val markedSongsWithMusic: Int,
        val moods: List<MoodComparison>
    )

    fun measure(
        songs: List<SongEntity>,
        stats: Map<Long, SongStatsEntity>,
        stylesByArtist: Map<String, String>,
        features: Map<Long, AudioFeatureEntity>
    ): Report {
        val labelled = StyleTraining.rows(songs, features, stylesByArtist, stats).size
        val printed = features.filterValues { MusicPrint.unpack(it.musicPrint) != null }
        val printedLabelled = StyleTraining.rows(songs, printed, stylesByArtist, stats).size

        // learn() computes held-out scores and proposed tags but has no writes.
        // Filtering first makes the with/without scores directly comparable.
        val style = StyleLearning.learn(songs, stats, stylesByArtist, printed)
        val printedSongs = songs.filter { it.id in printed }
        val sound = SoundCheck.measure(printedSongs, printed, stylesByArtist)

        val marked = MoodMarks.of(stats).filterKeys { id ->
            MusicMoods.parse(features[id]?.musicMoods.orEmpty()).isNotEmpty()
        }
        val withMusic = MoodModel(features.values, marked).report()
        val withoutMusicFeatures = features.values.map { it.copy(musicMoods = "") }
        val withoutMusic = MoodModel(withoutMusicFeatures, marked).report().associateBy { it.mood }
        val moods = withMusic.mapNotNull { result ->
            val baseline = withoutMusic[result.mood] ?: return@mapNotNull null
            MoodComparison(
                result.mood, result.yes, result.no,
                (if (result.usingLearned) result.learnedAccuracy else null) ?: result.ruleAccuracy,
                (if (baseline.usingLearned) baseline.learnedAccuracy else null) ?: baseline.ruleAccuracy
            )
        }
        return Report(labelled, printedLabelled, style, sound, marked.size, moods)
    }

    fun describe(r: Report): String = buildString {
        fun pct(value: Double) = "${(value * 100).roundToInt()}%"
        append("בדיקה לקריאה בלבד — לא משנה תגיות או המלצות.")
        append("\n\nסגנונות: למודל עצמו אין פלט של שם ז׳אנר. נמדדת התרומה שלו ללמידת התגיות שלך, על אמנים שלא השתתפו באימון.")
        append("\nשירים מתויגים: ${r.labelledSongs}; עם טביעה מוזיקלית: ${r.printedLabelledSongs}.")
        val without = r.style.withoutMusicF1
        val with = r.style.withMusicF1
        if (without != null && with != null && r.style.validation != null) {
            append("\nעל קבוצת שירים זהה: F1 עם המודל ${pct(with)}, בלעדיו ${pct(without)}.")
            append("\nF1 משלב תגיות נכונות, תגיות שגויות ותגיות שהוחמצו; הוא אינו אחוז השירים שהמודל זיהה.")
            append(if (r.style.usedMusic) " הלמידה בוחרת להשתמש בטביעה." else " הלמידה אינה בוחרת בטביעה כעת.")
        } else {
            append("\nאין עדיין מספיק שירים מתויגים עם טביעת מודל, אמנים וסגנונות מגוונים כדי לתת ציון F1 השוואתי.")
        }
        val sound = r.sound
        if (sound?.music != null) {
            append("\nבדיקת דמיון נפרדת: ${pct(sound.music)} מהשכנים של שיר הם מאותו סגנון, מול ${pct(sound.chance)} בבחירה אקראית (${sound.songs} שירים).")
            append(" זה אינו דיוק תיוג ז׳אנרים.")
        }

        append("\n\nמצבי רוח: נבדקת החלטת האפליקציה עם ובלי תוצאות המודל המוזיקלי, מול תיקונים ידניים שלך.")
        append("\nשירים מתוקנים שיש להם תוצאת מודל: ${r.markedSongsWithMusic}.")
        if (r.moods.isEmpty()) {
            append("\nאין עדיין תיקוני מצב רוח שאפשר לבדוק.")
        } else {
            for (m in r.moods) {
                append("\n• ${m.mood.label}: ${m.yes} כן, ${m.no} לא")
                if (m.yes < MIN_EACH_MOOD || m.no < MIN_EACH_MOOD) {
                    append(" — צריך לפחות $MIN_EACH_MOOD מכל צד לפני הצגת אחוז.")
                } else {
                    append(" — דיוק מאוזן של האפליקציה עם המודל ${pct(m.withMusic)}, בלעדיו ${pct(m.withoutMusic)}.")
                }
            }
        }
        append("\n\nאין אחוז דיוק אחד ל'הכול': דמיון, למידת סגנונות, מצבי רוח והמלצות הן משימות שונות.")
        append(" בדיקת ההמלצות האישיות נמצאת ב״תעודת ציונים לאלגוריתם״.")
    }
}
