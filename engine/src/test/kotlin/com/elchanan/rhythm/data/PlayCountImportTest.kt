package com.elchanan.rhythm.data

import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.engine.Names
import org.junit.Assert.*
import org.junit.Test

class PlayCountImportTest {

    private fun song(id: Long, title: String, artist: String) = SongEntity(
        id = id, title = title, titleLower = title.lowercase(),
        artistName = artist, artistKey = Names.normalizeKey(artist),
        albumName = "", albumId = 0, durationMs = 200_000, trackNumber = 0,
        year = 0, genre = null, path = "/m/$id.mp3", folder = "/m",
        dateAddedSec = 0, sizeBytes = 0
    )

    private val library = listOf(
        song(1, "לך אלי", "ישי ריבו"),
        song(2, "כתר", "מוטי שטיינמץ"),
        song(3, "ניגון נשמה", "אהרלה סאמט")
    )

    @Test fun readsAnAggregateExport() {
        val csv = """
            Name,Artist,Album,Plays
            לך אלי,ישי ריבו,אלבום,42
            כתר,מוטי שטיינמץ,אלבום,7
        """.trimIndent()
        val r = PlayCountImport.read(csv, library)
        assertEquals(2, r.matched.size)
        assertEquals(42, r.matched.first { it.songId == 1L }.plays)
        assertEquals(49, r.totalPlays)
    }

    @Test fun countsTheRowsWhenThereIsNoCountColumn() {
        // A scrobble log: one line per play, no total anywhere in the file.
        val csv = """
            artist,track
            ישי ריבו,לך אלי
            ישי ריבו,לך אלי
            ישי ריבו,לך אלי
            מוטי שטיינמץ,כתר
        """.trimIndent()
        val r = PlayCountImport.read(csv, library)
        assertEquals(3, r.matched.first { it.songId == 1L }.plays)
        assertEquals(1, r.matched.first { it.songId == 2L }.plays)
    }

    @Test fun aCommaInsideAQuotedTitleIsNotAColumnBreak() {
        val csv = "Title,Artist,Plays\n\"לך אלי, חלק ב\",ישי ריבו,5"
        val parsed = PlayCountImport.parse(csv)
        assertEquals("לך אלי, חלק ב", parsed.entries.single().title)
        assertEquals(5, parsed.entries.single().plays)
    }

    @Test fun semicolonsAndTabsAreRecognisedToo() {
        for (d in listOf(';', '\t')) {
            val csv = "Title${d}Artist${d}Plays\nכתר${d}מוטי שטיינמץ${d}9"
            assertEquals(9, PlayCountImport.read(csv, library).matched.single().plays)
        }
    }

    @Test fun aTitleAloneMatchesWhenItIsUnambiguous() {
        val csv = "Title,Plays\nניגון נשמה,12"
        val r = PlayCountImport.read(csv, library)
        assertEquals(3L, r.matched.single().songId)
    }

    @Test fun anAmbiguousTitleIsReportedRatherThanGuessedAt() {
        val twoOfAKind = library + song(4, "ניגון נשמה", "זמר אחר")
        val r = PlayCountImport.read("Title,Plays\nניגון נשמה,12", twoOfAKind)
        assertTrue(r.matched.isEmpty())
        assertEquals(1, r.unmatched.size)
    }

    @Test fun whatIsNotInTheLibraryIsReportedBack() {
        val csv = "Title,Artist,Plays\nשיר שאין,מישהו,3\nכתר,מוטי שטיינמץ,4"
        val r = PlayCountImport.read(csv, library)
        assertEquals(1, r.matched.size)
        assertEquals("שיר שאין", r.unmatched.single().title)
    }

    @Test fun timestampsAreReadInSecondsOrMilliseconds() {
        assertEquals(1_700_000_000_000L, PlayCountImport.timeOf("1700000000"))
        assertEquals(1_700_000_000_000L, PlayCountImport.timeOf("1700000000000"))
        assertEquals(0L, PlayCountImport.timeOf("אתמול"))
    }

    @Test fun countsAreReadHoweverTheyWereWritten() {
        assertEquals(1234, PlayCountImport.countOf("1,234"))
        assertEquals(12, PlayCountImport.countOf("12.0"))
        assertNull(PlayCountImport.countOf(""))
        assertNull(PlayCountImport.countOf("-"))
    }

    @Test fun aFileWithNoTitleColumnIsRefusedRatherThanMisread() {
        val r = PlayCountImport.parse("foo,bar\n1,2")
        assertTrue(r.entries.isEmpty())
        assertEquals(1, r.skipped)
    }

    @Test fun albumArtistIsNotMistakenForTheArtistColumn() {
        val csv = "Title,Album Artist,Artist,Plays\nכתר,קומפילציה,מוטי שטיינמץ,6"
        assertEquals("מוטי שטיינמץ", PlayCountImport.parse(csv).entries.single().artist)
    }
}
