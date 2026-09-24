package com.elchanan.rhythm.data

import com.elchanan.rhythm.engine.Names
import org.junit.Assert.*
import org.junit.Test

/**
 * One Hebrew name, however the file happened to spell it.
 */
class HebrewNamesTest {

    @Test fun whatHebrewTagsCarryIsNotPartOfTheName() {
        val plain = Names.normalizeKey("ישי ריבו")
        assertEquals(plain, Names.normalizeKey("ישי‏ ריבו"))            // a direction mark
        assertEquals(plain, Names.normalizeKey("ישי ריבו"))            // a non-breaking space
        assertEquals(plain, Names.normalizeKey("ישי – ריבו"))               // an en dash
        assertEquals(plain, Names.normalizeKey("‫ישי ריבו‬"))     // an embedding
        assertEquals(Names.normalizeKey("צ'ארלי"), Names.normalizeKey("צ׳ארלי"))   // geresh
        assertEquals(Names.normalizeKey("חב\"ד"), Names.normalizeKey("חב״ד"))     // gershayim
        // niqqud used to split a word into letters
        assertEquals(Names.normalizeKey("דוד"), Names.normalizeKey("דָּוִד"))
        // accented Latin, precomposed or not
        assertEquals(Names.normalizeKey("Beyoncé"), Names.normalizeKey("Beyoncé"))
    }

    @Test fun plainNamesKeepTheirKeys() {
        // What every library already has must not move.
        assertEquals("ישי ריבו", Names.normalizeKey("  ישי  ריבו "))
        assertEquals("avraham fried", Names.normalizeKey("Avraham-Fried!"))
        assertEquals("unknown", Names.normalizeKey("!!"))
        assertEquals("זמר 19 ומקהלה", Names.normalizeKey("זמר 19 ומקהלה"))
    }

    @Test fun spellingsOfOneArtistAreSuggested() {
        assertTrue(ArtistMerge.likelySame("ישי ריבו", "ריבו ישי"))           // word order
        assertTrue(ArtistMerge.likelySame("הרב ישי ריבו", "ישי ריבו"))       // a title
        assertTrue(ArtistMerge.likelySame("יעקב שוואקי", "יעקב שואקי"))      // full spelling
        assertTrue(ArtistMerge.likelySame("אברהם פריד", "Avraham Fried"))    // English
        assertFalse(ArtistMerge.likelySame("אברהם פריד", "מרדכי בן דוד"))
        assertFalse(ArtistMerge.likelySame("ישי ריבו", "ישי ריבו"))          // already one
    }

    @Test fun strandedRatingsFindTheirArtist() {
        val present = setOf("ישי ריבו", "אברהם פריד", "מוטי שטיינמץ")
        val moves = ArtistMerge.orphanMoves(
            orphans = listOf(
                "ישי‏ ריבו",        // a key from before keys ignored direction marks
                "avraham fried",          // its songs were retagged in Hebrew
                "הרב מוטי שטיינמץ",       // loosely the same as exactly one
                "מישהו אחר"               // nowhere to go
            ),
            present = present,
            rawToNow = listOf("avraham fried" to "אברהם פריד", "avraham fried" to "אברהם פריד")
        )
        assertEquals("ישי ריבו", moves["ישי‏ ריבו"])
        assertEquals("אברהם פריד", moves["avraham fried"])
        assertEquals("מוטי שטיינמץ", moves["הרב מוטי שטיינמץ"])
        assertNull(moves["מישהו אחר"])
    }

    @Test fun aSplitGoesNowhere() {
        val moves = ArtistMerge.orphanMoves(
            orphans = listOf("x y"),
            present = setOf("a", "b"),
            rawToNow = listOf("x y" to "a", "x y" to "b")
        )
        assertTrue(moves.isEmpty())
    }
}
