package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.db.SongEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Names written in English letters, offered in Hebrew - and only when it is clear what they are. */
class HebrewSpellingTest {

    private fun song(id: Long, title: String, artist: String, album: String = "al $id") = SongEntity(
        id, title, title.lowercase(), artist, Names.normalizeKey(Names.primaryArtist(artist)), album, id,
        200_000L, 1, 2021, null, "/m/$id", "/m", 1L, 1L
    )

    private val library = listOf(
        song(1, "Becho Botchu", "Naftali Kempeh", "Ke'malach"),
        song(2, "Ke'malach", "Naftali Kempeh", "Ke'malach"),
        song(3, "Ki Vonu", "Naftali Kempeh", "Ke'malach"),
        song(4, "Korveini Eilecho", "Naftali Kempeh", "Ke'malach"),
        song(5, "Mangina Shel Beis Medrash", "Naftali Kempeh", "Ke'malach"),
        // the same singer, already in Hebrew elsewhere in the library
        song(6, "אבינו", "נפתלי קמפה"),
        // a song that is in the library in Hebrew too
        song(7, "תוכו רצוף אהבה", "ישי ריבו"),
        song(8, "Tocho Ratzuf Ahava", "Ishay Ribo"),
        // English, and English it stays
        song(9, "Hold On", "Some Band"),
        song(10, "Love Song", "Some Band"),
        song(11, "Adon Olam (Live)", "Some Band")
    )

    private val suggestions = HebrewSpelling.suggest(library)
    private fun title(id: Long) = suggestions.firstOrNull { it.field == HebrewSpelling.Field.TITLE && id in it.songIds }
    private fun artist(latin: String) = suggestions.firstOrNull { it.field == HebrewSpelling.Field.ARTIST && it.latin == latin }

    @Test fun theArtistIsSpelledAsTheLibraryAlreadySpellsIt() {
        val s = artist("Naftali Kempeh")!!
        assertEquals("נפתלי קמפה", s.hebrew)
        assertTrue(s.fromLibrary)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), s.songIds)
    }

    @Test fun songNamesAreSpelledWordByWord() {
        assertEquals("בך בטחו", title(1)!!.hebrew)
        assertEquals("כמלאך", title(2)!!.hebrew)
        assertEquals("כי בנו", title(3)!!.hebrew)
        assertEquals("קרבני אליך", title(4)!!.hebrew)
        assertEquals("מנגינה של בית מדרש", title(5)!!.hebrew)
        assertFalse(title(5)!!.fromLibrary)
    }

    @Test fun aSongAlsoInTheLibraryInHebrewTakesThatSpelling() {
        val s = title(8)!!
        assertEquals("תוכו רצוף אהבה", s.hebrew)
        assertTrue(s.fromLibrary)
    }

    @Test fun englishStaysEnglish() {
        assertNull(title(9))
        assertNull(title(10))
        assertNull(artist("Some Band"))
    }

    @Test fun whatIsInBracketsIsKept() {
        assertEquals("אדון עולם (Live)", title(11)!!.hebrew)
    }

    @Test fun aWordNotRecognisedMeansNoOffer() {
        assertNull(HebrewSpelling.spellPhrase("Shalom Xyzzy"))
        assertEquals("שלום עליכם", HebrewSpelling.spellPhrase("Shalom Aleichem"))
        assertEquals("בשמחה", HebrewSpelling.spellPhrase("B'simcha"))
    }
}
