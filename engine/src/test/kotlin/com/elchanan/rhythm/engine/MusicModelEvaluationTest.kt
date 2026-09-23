package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.data.db.SongStatsEntity
import org.junit.Assert.*
import org.junit.Test

class MusicModelEvaluationTest {
    @Test fun catalogueTagsAreNotCountedAsVerifiedAnswers() {
        val song = SongEntity(
            7, "test", "test", "Avromi Roth", "avromi roth", "album",
            1, 180000, 1, 2026, null, "/music/7.mp3", "/music", 0, 1000
        )
        val features = mapOf(7L to Analysis.blankFor(7).copy(energy = 0.5f))
        val seeded = MusicModelEvaluation.measure(listOf(song), emptyMap(), emptyMap(), features)
        assertEquals(ArtistStyles.HASIDIC, ArtistStyles.styleFor(song.artistName))
        assertEquals(0, seeded.labelledSongs)

        val artistTagged = MusicModelEvaluation.measure(
            listOf(song), emptyMap(), mapOf(song.artistKey to ArtistStyles.HASIDIC), features
        )
        assertEquals(1, artistTagged.labelledSongs)

        val songTagged = MusicModelEvaluation.measure(
            listOf(song), mapOf(7L to SongStatsEntity(7, styles = "Jazz")), emptyMap(), features
        )
        assertEquals(1, songTagged.labelledSongs)
    }

    @Test fun sparseLibraryDoesNotInventAnAccuracyNumberOrChangeMarks() {
        val stats = mapOf(1L to SongStatsEntity(songId = 1, moods = "CALM"))
        val before = stats.toMap()
        val report = MusicModelEvaluation.measure(emptyList(), stats, emptyMap(), emptyMap())
        assertEquals(0, report.labelledSongs)
        assertEquals(0, report.printedLabelledSongs)
        assertTrue(report.moods.isEmpty())
        assertEquals(before, stats)
        val text = MusicModelEvaluation.describe(report)
        assertTrue(text.contains("אין עדיין מספיק"))
        assertTrue(text.contains("אין עדיין תיקוני"))
        assertFalse(text.contains("100%"))
    }
}
