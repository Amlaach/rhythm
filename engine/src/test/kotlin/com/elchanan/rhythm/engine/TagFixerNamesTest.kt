package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.TagFixer
import com.elchanan.rhythm.data.db.SongEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Names written twice, and names stuck in the wrong field, from real libraries. */
class TagFixerNamesTest {

    private var nextId = 1L
    private fun song(title: String, artist: String) = SongEntity(
        id = nextId++, title = title, titleLower = title.lowercase(), artistName = artist,
        artistKey = Names.normalizeKey(artist), albumName = "", albumId = 0, durationMs = 200_000,
        trackNumber = 0, year = 0, genre = null, path = "/$title.mp3", folder = "/", dateAddedSec = 0, sizeBytes = 0
    )

    private fun fix(vararg songs: SongEntity): List<TagFixer.Proposal> = TagFixer.propose(songs.toList())

    @Test fun theSameNameInBothScripts() {
        assertTrue(Transliteration.sameName("דרשו גלובל", "Dirshu Global"))
        assertTrue(Transliteration.sameName("ישי ריבו", "Ishay Ribo"))
        assertTrue(Transliteration.sameName("חנן בן ארי", "Hanan Ben Ari"))
        assertTrue(Transliteration.sameName("ארץ ישראל", "Erets Israel"))
        assertTrue(Transliteration.sameName("השיבנו", "Hashivenu"))
        assertTrue(Transliteration.sameName("מרדכי בן דוד", "Mordechai Ben David"))
        assertFalse(Transliteration.sameName("ישי ריבו", "Erets Israel"))
        assertFalse(Transliteration.sameName("שלום עליכם", "Mazal Tov"))
        assertFalse("too short to tell", Transliteration.sameName("ים", "Yam"))
    }

    @Test fun anArtistWrittenTwiceKeepsTheHebrew() {
        val p = fix(song("שיר", "דרשו גלובל | Dirshu Global")).single()
        assertEquals("דרשו גלובל", p.newArtist)
        assertTrue(p.certain)
        assertEquals("דרשו גלובל", fix(song("שיר", "Dirshu Global | דרשו גלובל")).single().newArtist)
    }

    @Test fun theSongsNameInEnglishComesOutOfTheArtistField() {
        val p = fix(song("ארץ ישראל", "ישי ריבו | Erets Israel")).single()
        assertEquals("ישי ריבו", p.newArtist)
        assertEquals("ארץ ישראל", p.newTitle)
        assertTrue(p.certain)
    }

    @Test fun anEnglishArtistStuckToAHebrewSongName() {
        val p = fix(song("Hanan Ben Ari השיבנו", "<unknown>")).single()
        assertEquals("השיבנו", p.newTitle)
        assertEquals("חנן בן ארי", p.newArtist)
        assertTrue(p.certain)
    }

    @Test fun theSameStuckInTheArtistField() {
        assertEquals("חנן בן ארי", fix(song("השיבנו", "Hanan Ben Ari השיבנו")).single().newArtist)
    }

    @Test fun aSongNameWrittenTwiceIsNotArtistAndTitle() {
        val a = fix(song("השיבנו - Hashivenu", "חנן בן ארי")).single()
        assertEquals("השיבנו", a.newTitle)
        assertEquals("חנן בן ארי", a.newArtist)
        assertEquals("השיבנו", fix(song("השיבנו | Hashivenu", "חנן בן ארי")).single().newTitle)
        assertEquals("השיבנו", fix(song("השיבנו (Hashivenu)", "חנן בן ארי")).single().newTitle)
    }

    @Test fun anEnglishSongNameTheSameArtistHasInHebrewIsOfferedButNotSure() {
        val proposals = fix(song("השיבנו", "חנן בן ארי"), song("Hashivenu", "חנן בן ארי"))
        val english = proposals[1]
        assertEquals("השיבנו", english.newTitle)
        assertFalse(english.certain)
        // Not applied unless the listener asks.
        assertTrue(TagFixer.toOverrides(proposals).none { it.songId == english.songId })
        assertTrue(TagFixer.toOverrides(proposals, includeUncertain = true).any { it.songId == english.songId })
    }

    @Test fun anArtistNobodyKnowsIsSplitButNotSure() {
        val p = fix(song("שיר חדש", "אמן לא מוכר | Some Channel")).single()
        assertEquals("אמן לא מוכר", p.newArtist)
        assertFalse(p.certain)
    }

    @Test fun whatIsNotANameWrittenTwiceIsLeftAlone() {
        val untouched = listOf(
            song("Hallelujah (Live)", "Vini Vici"),
            song("שיר (LIVE מנורה)", "עומר אדם"),
            song("ניגון", "ישי ריבו | מוטי שטיינמץ"),
            song("Tonight", "Some Band")
        )
        for (p in TagFixer.propose(untouched)) {
            assertEquals(p.oldTitle, p.newTitle)
            assertEquals(p.oldArtist, p.newArtist)
        }
    }
}
