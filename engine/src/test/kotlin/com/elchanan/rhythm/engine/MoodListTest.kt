package com.elchanan.rhythm.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A mood chip answered from the engine gives exactly the list the full
 * computation over the database rows gives - the same songs in the same
 * order - so answering it from memory changes nothing but the wait.
 *
 * Where styles are kept apart, it gives that list thinned to one side, and
 * nothing else changes.
 */
class MoodListTest {

    private val lib = EngineFixture.build()
    private val rows = lib.features.filter { it.energy > 0f }.associateBy { it.songId }

    // What the engine may offer outside the vocal weeks, less the disliked.
    private val offered = lib.songs.filter {
        it.id !in lib.spoken && it.id !in lib.vocal && (lib.stats[it.id]?.liked ?: 0) != -1
    }

    private fun expected(mood: Mood) = Mood.strongest(offered, rows, mood, MoodMarks.of(lib.stats))

    @Test fun fromTheEngineIsTheSameListAsFromTheRows() {
        val e = EngineGoldenTest.engine(lib, EngineFixture.NOW, EngineFixture.TUNING.copy(separations = ""))
        var nonEmpty = 0
        for (mood in Mood.entries) {
            val actual = e.strongestIn(mood)
            assertEquals("$mood", expected(mood).map { it.id }, actual.map { it.id })
            if (actual.isNotEmpty()) nonEmpty++
        }
        assertTrue("the fixture has songs in most moods", nonEmpty >= 5)
    }

    @Test fun keptApartStylesStayApartAndTheOrderStays() = check(EngineFixture.TUNING.separations)

    /** The report: "רק אנגלית", and English still turned up among the rest in "שמח". */
    @Test fun englishKeptToItselfStaysOutOfTheOthers() = check("רק אנגלית")

    private fun check(rule: String) {
        val separations = Styles.Separations.parse(rule)
        val e = EngineGoldenTest.engine(lib, EngineFixture.NOW, EngineFixture.TUNING.copy(separations = rule))
        var thinned = 0
        for (mood in Mood.entries) {
            val full = expected(mood).map { it.id }
            val actual = e.strongestIn(mood).map { it.id }
            // A subsequence of the full list: nothing added, nothing reordered.
            var at = 0
            for (id in actual) {
                while (at < full.size && full[at] != id) at++
                assertTrue("$mood: $id is out of order or not in the list", at < full.size)
                at++
            }
            if (actual.size < full.size) thinned++
            val styles = actual.map { stylesOf(it) }
            for (i in styles.indices) for (j in i + 1 until styles.size) {
                assertFalse("$mood: ${actual[i]} and ${actual[j]} are kept apart", separations.clash(styles[i], styles[j]))
            }
        }
        assertTrue("$rule has something to do in the fixture", thinned > 0)
    }

    private val artists = ArtistStyles.withCatalogue(lib.artists, lib.songs)
    private val byId = lib.songs.associateBy { it.id }

    private fun stylesOf(id: Long): List<String> {
        val own = Styles.parse(lib.stats[id]?.styles.orEmpty())
        val artist = Styles.parse(artists[byId.getValue(id).artistKey]?.styles.orEmpty())
        return when {
            own.isEmpty() -> artist
            lib.stats[id]?.stylesAuto == 1 -> (artist + own).distinct()
            else -> own
        }
    }
}
