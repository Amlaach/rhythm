package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.db.SongStatsEntity
import org.junit.Assert.*
import org.junit.Test

class MusicModelEvaluationTest {
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
