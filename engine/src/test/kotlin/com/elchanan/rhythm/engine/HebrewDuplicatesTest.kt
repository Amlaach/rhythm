package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.db.SongEntity
import org.junit.Assert.*
import org.junit.Test

/**
 * One Hebrew song downloaded from two places is one song.
 */
class HebrewDuplicatesTest {

    private fun song(id: Long, title: String, artist: String, seconds: Long = 240) = SongEntity(
        id, title, title.lowercase(), artist, Names.normalizeKey(Names.primaryArtist(artist)), "al", id,
        seconds * 1000L, 1, 2020, null, "/m/$id", "/m", 1L, 1L
    )

    private fun groups(songs: List<SongEntity>) =
        Versions.duplicateGroups(songs, Versions.classify(songs)).map { g -> g.map { it.id }.toSet() }.toSet()

    @Test fun whatADownloadAddsToATitleIsNotTheSong() {
        val songs = listOf(
            song(1, "תוכו רצוף אהבה", "ישי ריבו"),
            song(2, "ישי ריבו - תוכו רצוף אהבה", "ישי ריבו"),
            song(3, "תוכו רצוף אהבה (קליפ רשמי)", "ישי ריבו"),
            song(4, "01 - תוכו רצוף אהבה", "ישי ריבו"),
            song(5, "תוכו רצוף אהבה | Official Video", "ישי‏ ריבו")
        )
        assertEquals(setOf(setOf(1L, 2L, 3L, 4L, 5L)), groups(songs))
    }

    @Test fun oneSingerSpelledTwoWaysIsOnePerformer() {
        assertTrue(Versions.samePerformer("יעקב שוואקי", "יעקב שואקי"))
        assertTrue(Versions.samePerformer("אברהם פריד", "Avraham Fried"))
        assertTrue(Versions.samePerformer("הרב ישי ריבו", "ישי ריבו"))
        assertFalse(Versions.samePerformer("אברהם פריד", "מרדכי בן דוד"))
        val songs = listOf(song(1, "אבינו", "Avraham Fried"), song(2, "אבינו", "אברהם פריד"))
        assertEquals("a copy, not a cover", setOf(setOf(1L, 2L)), groups(songs))
    }

    @Test fun realDifferencesStayApart() {
        val songs = listOf(
            song(1, "תוכו רצוף אהבה", "ישי ריבו"),
            song(2, "תוכו רצוף אהבה", "ישי ריבו", seconds = 330),   // another recording, another length
            song(3, "לשם שמיים", "ישי ריבו"),
            song(4, "12 שבטים", "זמר"),                                // a number that is the title
            song(5, "שבטים", "זמר")
        )
        assertTrue(groups(songs).isEmpty())
    }
}
