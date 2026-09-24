package com.elchanan.rhythm.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A list with no seed keeps the side of each rule the listening leans to,
 * in the list's own order.
 */
class FavouredSideTest {

    private data class S(val name: String, val styles: List<String>, val plays: Int)

    private fun keep(rule: String, vararg songs: S) =
        Styles.Separations.parse(rule).favoured(songs.toList(), { it.styles }, { it.plays.toDouble() }).map { it.name }

    @Test fun theSideListenedToMostStaysEvenWhenSmaller() {
        val kept = keep(
            "אנגלית",
            S("a", emptyList(), 0), S("e1", listOf("אנגלית"), 9), S("b", listOf("חסידי"), 1),
            S("e2", listOf("אנגלית"), 4), S("c", emptyList(), 2)
        )
        assertEquals(listOf("e1", "e2"), kept)
    }

    @Test fun withNoListeningTheBiggerSideStays() {
        val kept = keep(
            "אנגלית",
            S("a", emptyList(), 0), S("e1", listOf("אנגלית"), 0), S("b", listOf("חסידי"), 0)
        )
        assertEquals(listOf("a", "b"), kept)
    }

    @Test fun aPairRuleKeepsTheUntaggedAndTheListenedSide() {
        val kept = keep(
            "חסידי, ישראלי",
            S("i1", listOf("ישראלי"), 0), S("h1", listOf("חסידי"), 3), S("u", emptyList(), 0),
            S("i2", listOf("ישראלי"), 1), S("h2", listOf("חסידי", "קצבי"), 0), S("both", listOf("חסידי", "ישראלי"), 5)
        )
        // חסידי weighs 3 + 0 + 5 against ישראלי's 0 + 1 + 5; a song naming both clashes with either side.
        assertEquals(listOf("h1", "u", "h2"), kept)
    }

    @Test fun rulesThatShareAStyleAreSettledTogether() {
        val kept = keep(
            "א, ב\nב, ג",
            S("a", listOf("א"), 5), S("b", listOf("ב"), 4), S("c", listOf("ג"), 1)
        )
        // א wins its rule, so ב goes, and nothing then stands against ג.
        assertEquals(listOf("a", "c"), kept)
    }

    @Test fun nothingToKeepApartChangesNothing() {
        val songs = arrayOf(S("a", listOf("אנגלית"), 1), S("b", listOf("חסידי"), 0))
        assertEquals(listOf("a", "b"), keep("", *songs))
    }
}
