package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.data.db.SongStatsEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SamplesTest {

    private fun song(id: Long, title: String = "שיר $id", minutes: Double = 3.5) = SongEntity(
        id = id, title = title, titleLower = title.lowercase(), artistName = "אמן", artistKey = "אמן",
        albumName = "", albumId = 0, durationMs = (minutes * 60_000).toLong(), trackNumber = 0, year = 0,
        genre = null, path = "/m/$id.mp3", folder = "/m", dateAddedSec = 0, sizeBytes = 0
    )

    @Test fun leavesOutWhatIsNotASongToTaste() {
        val songs = listOf(
            song(1), song(2, minutes = 7.0), song(3, minutes = 0.5), song(4, title = "מחרוזת חתונה"),
            song(5), song(6)
        )
        val stats = mapOf(5L to SongStatsEntity(songId = 5, liked = -1))
        val chosen = Samples.choose(songs, stats, emptyMap(), inSeason = false,
            isVocal = { s, _ -> s.id == 6L }, score = { 0.0 }, random = Random(1)).map { it.id }
        assertEquals(listOf(1L), chosen)
        // In the Omer the vocal song is back.
        val omer = Samples.choose(songs, stats, emptyMap(), inSeason = true,
            isVocal = { s, _ -> s.id == 6L }, score = { 0.0 }, random = Random(1)).map { it.id }
        assertTrue(6L in omer)
    }

    @Test fun neverPlayedFirstThenByScore() {
        val songs = (1L..6L).map { song(it) }
        val stats = mapOf(1L to SongStatsEntity(songId = 1, playCount = 9), 2L to SongStatsEntity(songId = 2, playCount = 3))
        val chosen = Samples.choose(songs, stats, emptyMap(), false, { _, _ -> false },
            score = { it.id.toDouble() * 100 }, random = Random(2)).map { it.id }
        assertEquals(listOf(6L, 5L, 4L, 3L, 2L, 1L), chosen)
        assertFalse(chosen.take(4).any { it == 1L || it == 2L })
    }

    @Test fun tastedGoToTheEnd() {
        val songs = (1L..4L).map { song(it) }
        assertEquals(listOf(2L, 4L, 1L, 3L), Samples.tastedLast(songs, setOf(1L, 3L)).map { it.id })
    }
}
