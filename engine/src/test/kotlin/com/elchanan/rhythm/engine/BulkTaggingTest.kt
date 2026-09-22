package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.data.db.SongStatsEntity
import org.junit.Assert.*
import org.junit.Test

class BulkTaggingTest {

    private fun song(id: Long, folder: String) = SongEntity(
        id = id, title = "t$id", titleLower = "t$id", artistName = "a", artistKey = "a",
        albumName = "al", albumId = 1, durationMs = 1000, trackNumber = 1, year = 0,
        genre = null, path = "$folder/t$id.mp3", folder = folder,
        dateAddedSec = 0, sizeBytes = 0
    )

    @Test fun anUntaggedSongTakesTheFolderTag() {
        assertEquals("חסידי", BulkTagging.tagsFor(null, listOf("חסידי"), replace = false))
    }

    @Test fun aGuessIsAlwaysReplaced() {
        val guessed = SongStatsEntity(1, styles = "מזרחי", stylesAuto = 1)
        assertEquals("חסידי", BulkTagging.tagsFor(guessed, listOf("חסידי"), replace = false))
    }

    @Test fun whatTheUserTypedIsAddedToRatherThanLost() {
        val typed = SongStatsEntity(1, styles = "חסידי", stylesAuto = 0)
        assertEquals(
            "חסידי, קצבי",
            BulkTagging.tagsFor(typed, listOf("קצבי"), replace = false)
        )
    }

    @Test fun replaceMeansReplace() {
        val typed = SongStatsEntity(1, styles = "חסידי, קצבי", stylesAuto = 0)
        assertEquals("ליטאי", BulkTagging.tagsFor(typed, listOf("ליטאי"), replace = true))
    }

    @Test fun nothingToChangeWritesNothing() {
        val typed = SongStatsEntity(1, styles = "חסידי", stylesAuto = 0)
        assertNull(BulkTagging.tagsFor(typed, listOf("חסידי"), replace = false))
        assertNull(BulkTagging.tagsFor(typed, emptyList(), replace = true))
    }

    @Test fun aFolderMeansItsSubfoldersToo() {
        val songs = listOf(
            song(1, "/Music/Shiur"),
            song(2, "/Music/Shiur/2024"),
            song(3, "/Music/Shiurim"),
            song(4, "/Music")
        )
        assertEquals(
            listOf(1L, 2L),
            BulkTagging.songsUnder(songs, "/Music/Shiur").map { it.id }
        )
    }
}
