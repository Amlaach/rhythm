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
        /** Only artist styles explicitly entered by the listener, never catalogue seeds. */
        stylesByArtist: Map<String, String>,
        features: Map<Long, AudioFeatureEntity>
    ): Report {
        // The regular learner deliberately trains on the shipped catalogue. A
        // measurement against those same catalogue labels would only measure
        // agreement with our assumptions, not accuracy against user evidence.
        val manuallyLabelled = songs.filter { song ->
            val own = stats[song.id]
            (own?.stylesAuto == 0 && Styles.parse(own.styles).isNotEmpty()) ||
                Styles.parse(stylesByArtist[song.artistKey].orEmpty()).isNotEmpty()
        }
        val labelled = StyleTraining.rows(manuallyLabelled, features, stylesByArtist, stats).size
        val printed = features.filterValues { MusicPrint.unpack(it.musicPrint) != null }
        val printedLabelled = StyleTraining.rows(manuallyLabelled, printed, stylesByArtist, stats).size

        // learn() computes held-out scores and proposed tags but has no writes.
        // Filtering first makes the with/without scores directly comparable.
        val style = StyleLearning.learn(manuallyLabelled, stats, stylesByArtist, printed)
        // SoundCheck measures artist genres, so one-off song tags cannot be
        // treated as artist-wide truth in this separate diagnostic.
        val soundSongs = manuallyLabelled.filter { song ->
            song.id in printed && Styles.parse(stylesByArtist[song.artistKey].orEmpty()).isNotEmpty()
        }
        val sound = SoundCheck.measure(soundSongs, printed, stylesByArtist)

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

    fun describe(r: Report, language: String = "he"): String =
        if (language == "en") describeEnglish(r) else describeHebrew(r)

    private fun describeEnglish(r: Report): String = buildString {
        fun pct(value: Double) = "${(value * 100).roundToInt()}%"
        append("Read-only check: no tags or recommendations are changed.")
        append("\n\nGenres: the music model itself does not output a genre name. This checks whether its audio features help learn the genres you labelled, using artists held out of training.")
        append("\nPersonally labelled songs that were analysed: ${r.labelledSongs}; with a music embedding: ${r.printedLabelledSongs}.")
        append(" Built-in artist labels are excluded as ground truth. Your own labels are useful reference points, but may still be subjective or mistaken.")
        val without = r.style.withoutMusicF1
        val with = r.style.withMusicF1
        if (without != null && with != null && r.style.validation != null) {
            append("\nOn the same held-out songs, F1 with the music model: ${pct(with)}; without it: ${pct(without)}.")
            append(" F1 combines correct, incorrect and missed tags. It is not the percentage of songs whose genre the model identified.")
            append(if (r.style.usedMusic) " The learner currently uses the music embedding." else " The learner does not currently use the music embedding.")
        } else {
            append("\nThere are not yet enough diverse personally labelled, analysed songs and artists for a comparative F1 score.")
        }
        r.sound?.let { sound ->
            if (sound.music != null) {
                append("\nSeparate similarity check: ${pct(sound.music)} of a song's nearest neighbours share its labelled genre, versus ${pct(sound.chance)} at random (${sound.songs} songs).")
                append(" This is not genre classification accuracy.")
            }
        }
        append("\n\nMoods: the app's decisions with and without the music model are compared against your own manual corrections.")
        append("\nManually corrected songs with model output: ${r.markedSongsWithMusic}.")
        if (r.moods.isEmpty()) append("\nThere are no usable manual mood corrections yet.")
        for (m in r.moods) {
            val name = when (m.mood) {
                Mood.CALM -> "Calm"; Mood.ENERGETIC -> "Energetic"
                Mood.BRIGHT -> "Happy"; Mood.DEEP -> "Emotional"
                Mood.FOCUS -> "Focus"; Mood.WORKOUT -> "Workout"; Mood.NIGHT -> "Night"
            }
            append("\n• $name: ${m.yes} yes, ${m.no} no")
            if (m.yes < MIN_EACH_MOOD || m.no < MIN_EACH_MOOD) {
                append(" — at least $MIN_EACH_MOOD of each are needed to show a percentage.")
            } else {
                append(" — balanced accuracy with the model: ${pct(m.withMusic)}; without it: ${pct(m.withoutMusic)}.")
            }
        }
        append("\n\nThere is no single accuracy percentage for everything: similarity, genre learning, moods and recommendations are different tasks. Recommendation checks are in the algorithm report.")
        append(" These numbers describe performance on your own labelled library, not universal model accuracy.")
    }

    private fun describeHebrew(r: Report): String = buildString {
        fun pct(value: Double) = "${(value * 100).roundToInt()}%"
        append("בדיקה לקריאה בלבד — לא משנה תגיות או המלצות.")
        append("\n\nסגנונות: למודל עצמו אין פלט של שם ז׳אנר. נמדדת התרומה שלו ללמידת התגיות שלך, על אמנים שלא השתתפו באימון.")
        append("\nשירים שתייגת בעצמך ושנותחו: ${r.labelledSongs}; עם טביעה מוזיקלית: ${r.printedLabelledSongs}.")
        append(" תגיות האמנים המובנות אינן נחשבות תשובת אמת בבדיקה הזאת.")
        val without = r.style.withoutMusicF1
        val with = r.style.withMusicF1
        if (without != null && with != null && r.style.validation != null) {
            append("\nעל קבוצת שירים זהה: F1 עם המודל ${pct(with)}, בלעדיו ${pct(without)}.")
            append("\nF1 משלב תגיות נכונות, תגיות שגויות ותגיות שהוחמצו; הוא אינו אחוז השירים שהמודל זיהה.")
            append(if (r.style.usedMusic) " הלמידה בוחרת להשתמש בטביעה." else " הלמידה אינה בוחרת בטביעה כעת.")
        } else {
            append("\nאין עדיין מספיק שירים שתייגת בעצמך עם טביעת מודל, אמנים וסגנונות מגוונים כדי לתת ציון F1 השוואתי.")
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
