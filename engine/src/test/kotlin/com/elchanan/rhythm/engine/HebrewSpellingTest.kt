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
    private fun artist(latin: String) = suggestions.firstOrNull { it.field == HebrewSpelling.Field.ARTIST && it.original == latin }

    @Test fun theArtistIsSpelledAsTheLibraryAlreadySpellsIt() {
        val s = artist("Naftali Kempeh")!!
        assertEquals("נפתלי קמפה", s.proposed)
        assertTrue(s.fromLibrary)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), s.songIds)
    }

    @Test fun songNamesAreSpelledWordByWord() {
        assertEquals("בך בטחו", title(1)!!.proposed)
        assertEquals("כמלאך", title(2)!!.proposed)
        assertEquals("כי בנו", title(3)!!.proposed)
        assertEquals("קרבני אליך", title(4)!!.proposed)
        assertEquals("מנגינה של בית מדרש", title(5)!!.proposed)
        assertFalse(title(5)!!.fromLibrary)
    }

    @Test fun aSongAlsoInTheLibraryInHebrewTakesThatSpelling() {
        val s = title(8)!!
        assertEquals("תוכו רצוף אהבה", s.proposed)
        assertTrue(s.fromLibrary)
    }

    @Test fun englishStaysEnglish() {
        assertNull(title(9))
        assertNull(title(10))
        assertNull(artist("Some Band"))
    }

    @Test fun whatIsInBracketsIsKept() {
        assertEquals("אדון עולם (Live)", title(11)!!.proposed)
    }

    @Test fun aWordNotRecognisedMeansNoOffer() {
        assertNull(HebrewSpelling.spellPhrase("Shalom Xyzzy"))
        assertEquals("שלום עליכם", HebrewSpelling.spellPhrase("Shalom Aleichem"))
        assertEquals("בשמחה", HebrewSpelling.spellPhrase("B'simcha"))
    }

    // The other way, for those who read the app in English.

    private val latinSuggestions = HebrewSpelling.suggestLatin(library + listOf(
        song(20, "מנגינה של בית מדרש", "נפתלי קמפה"),
        song(21, "אדון עולם", "מישהו"),
        song(22, "שיר עם מילה זרגולית", "מישהו")
    ))

    @Test fun hebrewArtistsTakeTheLibrarysLatinSpelling() {
        val s = latinSuggestions.first { it.field == HebrewSpelling.Field.ARTIST && it.original == "נפתלי קמפה" }
        assertEquals("Naftali Kempeh", s.proposed)
        assertTrue(s.fromLibrary)
    }

    @Test fun hebrewTitlesAreSpelledInLatinWordByWord() {
        val titles = latinSuggestions.filter { it.field == HebrewSpelling.Field.TITLE }.associateBy { it.songIds.single() }
        assertEquals("Mangina Shel Beis Medrash", titles.getValue(20).proposed)
        assertEquals("Adon Olam", titles.getValue(21).proposed)
        // a word it does not know: nothing offered
        assertNull(titles[22L])
        // a Hebrew title whose Latin copy is in the library takes that spelling
        assertEquals("Tocho Ratzuf Ahava", titles.getValue(7).proposed)
        assertEquals("B'simcha", HebrewSpelling.romanizePhrase("בשמחה"))
    }
}
