package com.elchanan.rhythm.data

import com.elchanan.rhythm.engine.Names
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistMergeTest {
    @Test fun suggestsExactlyOneLetterEdit() {
        assertTrue(ArtistMerge.oneLetterApart("אברהם פריד", "אברם פריד"))
        assertTrue(ArtistMerge.oneLetterApart("אברם פריד", "אברהם פריד"))
        assertTrue(ArtistMerge.oneLetterApart("יוסי כהן", "יוסי כהנ"))
        assertTrue(ArtistMerge.oneLetterApart("אברהם פרי", "אברהם פריד"))
        assertFalse(ArtistMerge.oneLetterApart("אברהם פריד", "אברהם פריד"))
        assertFalse(ArtistMerge.oneLetterApart("אברהם פריד", "מרדכי פריד"))
        assertFalse(ArtistMerge.oneLetterApart("Artist 1", "Artist 2"))
        assertFalse(ArtistMerge.oneLetterApart("unknown", "unknow"))
        assertFalse(ArtistMerge.oneLetterApart("יוסי כהן", "יוסיכהן"))
    }

    @Test fun preservesOtherCreditsAndSeparators() {
        val key = Names.normalizeKey("אברם פריד")
        assertEquals("אברהם פריד feat. יעקב שוואקי",
            ArtistMerge.renameCredit("אברם פריד feat. יעקב שוואקי", key, "אברהם פריד"))
        assertEquals("יעקב שוואקי עם אברהם פריד",
            ArtistMerge.renameCredit("יעקב שוואקי עם אברם פריד", key, "אברהם פריד"))
        assertEquals("אברם פרידמן", ArtistMerge.renameCredit("אברם פרידמן", key, "אברהם פריד"))
        assertEquals("אברהם פריד, יעקב שוואקי",
            ArtistMerge.renameCredit("אברם פריד, יעקב שוואקי", key, "אברהם פריד"))
    }
}
