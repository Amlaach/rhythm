package com.elchanan.rhythm.data

import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.engine.Names
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Quotation marks around a whole name are not part of it. */
class UnquoteTest {

    @Test fun marksAroundTheWholeNameGo() {
        assertEquals("האסיר", TagFixer.unquote("\"האסיר\""))
        assertEquals("האסיר", TagFixer.unquote("״האסיר״"))
        assertEquals("לשם שמיים", TagFixer.unquote("“לשם שמיים”"))
        assertEquals("Hello", TagFixer.unquote("'Hello'"))
        // an abbreviation inside stays
        assertEquals("שירי תנ\"ך", TagFixer.unquote("\"שירי תנ\"ך\""))
    }

    @Test fun realQuotationsStay() {
        // quotes that are only part of the name
        assertEquals("\"אבא\" שלי", TagFixer.unquote("\"אבא\" שלי"))
        // two quotations, not one wrapped name
        assertEquals("\"א\" ו\"ב\"", TagFixer.unquote("\"א\" ו\"ב\""))
        // an abbreviation ending a word, and marks with nothing inside
        assertEquals("צה\"ל", TagFixer.unquote("צה\"ל"))
        assertEquals("\"\"", TagFixer.unquote("\"\""))
    }

    @Test fun theTagFixOffersIt() {
        val song = SongEntity(
            1L, "\"האסיר\"", "\"האסיר\"", "בן צור", Names.normalizeKey("בן צור"), "al", 1L,
            180_000L, 1, 2020, null, "/m/1", "/m", 1L, 1L
        )
        val proposal = TagFixer.propose(listOf(song)).single()
        assertEquals("האסיר", proposal.newTitle)
        assertEquals("בן צור", proposal.newArtist)
        assertFalse(proposal.albumChanged)
    }
}
