package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.db.AudioFeatureEntity
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.data.db.SongStatsEntity
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

/**
 * A library tagged the way the app asks for it still gives the learner work.
 *
 * Tagging artists is one decision covering a hundred songs, so it is what the
 * app tells people to do and what they do. It also made every song in the
 * library ineligible, and a run over a thoroughly tagged library ended with
 * "no songs need tags" - which reads as a failure, and was reported as one.
 */
class LearnerHasWorkTest {

    private val random = Random(19)

    private fun song(id: Long, artist: String) = SongEntity(
        id = id, title = "שיר $id", titleLower = "שיר $id",
        artistName = artist, artistKey = artist,
        albumName = "al", albumId = id / 10, durationMs = 240_000, trackNumber = 1,
        year = 2020, genre = null, path = "/m/$id.mp3", folder = "/m/$artist",
        dateAddedSec = 0, sizeBytes = 0
    )

    /** Two separable sounds, so there is something real for the model to find. */
    private fun feature(id: Long, fast: Boolean) = AudioFeatureEntity(
        songId = id, analyzedAt = 1L,
        bpm = if (fast) 130f + random.nextFloat() * 8f else 70f + random.nextFloat() * 8f,
        bpmConfidence = 0.9f, musicalKey = 0, mode = 1,
        energy = if (fast) 0.75f + random.nextFloat() * 0.08f else 0.3f + random.nextFloat() * 0.08f,
        brightness = if (fast) 0.6f + random.nextFloat() * 0.05f else 0.25f + random.nextFloat() * 0.05f,
        flatness = if (fast) 0.2f else 0.08f,
        dynamics = if (fast) 1.4f else 0.7f,
        onsetRate = if (fast) 1.8f else 0.5f,
        chroma = "1,2,3", timbre = "4,5,6", timbreVar = "7,8,9"
    )

    @Test fun aCharacterCanBeLearnedForSongsWhoseGenreIsAlreadySet() {
        // The shape of a library that has been worked on: the genre is settled
        // at artist level, one decision per singer, and the character of
        // individual tracks is typed in on the songs themselves. Every song by
        // a tagged artist used to be ineligible, so the run ended with "no
        // songs need tags" and the character tags were never carried across.
        val songs = ArrayList<SongEntity>()
        val features = HashMap<Long, AudioFeatureEntity>()
        val stats = HashMap<Long, SongStatsEntity>()
        val cast = listOf(
            "א" to "חסידי", "ב" to "חסידי", "ג" to "חסידי", "ד" to "חסידי",
            "ה" to "ישראלי", "ו" to "ישראלי", "ז" to "ישראלי", "ח" to "ישראלי"
        )
        var id = 1L
        for ((artist, _) in cast) {
            repeat(14) { n ->
                val fast = n % 2 == 0
                songs.add(song(id, artist))
                features[id] = feature(id, fast)
                // Ten of the fourteen carry a hand typed character; the other
                // four are what the learner is being asked to fill in.
                if (n < 10) {
                    stats[id] = SongStatsEntity(
                        id, styles = if (fast) "קצבי" else "רגוע", stylesAuto = 0
                    )
                }
                id++
            }
        }

        val result = StyleLearning.learn(songs, stats, cast.toMap(), features)

        assertTrue(
            "a library tagged by artist left the learner nothing to look at, " +
                "status was ${result.status}",
            result.candidates > 0
        )
        assertEquals(
            "a separable character should have been learned and written",
            LearningStatus.READY, result.status
        )
        assertTrue("nothing was written", result.predictions.isNotEmpty())
        // Whatever it writes must answer a question the artist tag did not.
        for ((_, styles) in result.predictions) {
            for (style in Styles.parse(styles)) {
                assertEquals(
                    "the learner offered a genre where a genre was already set",
                    "אופי", Styles.familyOf(style)
                )
            }
        }
    }

    @Test fun anUntaggedLibraryStillGetsGenresOffered() {
        // The restriction must apply only where a genre is already known, or
        // it would silently stop the learner doing its original job.
        val songs = ArrayList<SongEntity>()
        val features = HashMap<Long, AudioFeatureEntity>()
        val stats = HashMap<Long, SongStatsEntity>()
        var id = 1L
        for ((artist, style) in listOf(
            "א" to "חסידי", "ב" to "חסידי", "ג" to "חסידי", "ד" to "חסידי",
            "ה" to "ישראלי", "ו" to "ישראלי", "ז" to "ישראלי", "ח" to "ישראלי"
        )) {
            repeat(10) { n ->
                val s = song(id, artist)
                songs.add(s)
                features[id] = feature(id, fast = style == "ישראלי")
                // Tagged on the song, leaving the artist rows empty, so a
                // handful of songs are left over with nothing on them at all.
                if (n < 8) stats[id] = SongStatsEntity(id, styles = style, stylesAuto = 0)
                id++
            }
        }
        val result = StyleLearning.learn(songs, stats, emptyMap(), features)
        assertTrue("nothing was left for the learner to label", result.candidates > 0)
    }
}
