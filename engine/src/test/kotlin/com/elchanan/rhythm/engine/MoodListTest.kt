package com.elchanan.rhythm.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A mood chip answered from the engine gives exactly the list the full
 * computation over the database rows gives - the same songs in the same
 * order - so answering it from memory changes nothing but the wait.
 */
class MoodListTest {

    @Test fun fromTheEngineIsTheSameListAsFromTheRows() {
        val lib = EngineFixture.build()
        val e = EngineGoldenTest.engine(lib, EngineFixture.NOW)
        val rows = lib.features.filter { it.energy > 0f }.associateBy { it.songId }
        // What the engine may offer outside the vocal weeks, less the disliked.
        val offered = lib.songs.filter {
            it.id !in lib.spoken && it.id !in lib.vocal && (lib.stats[it.id]?.liked ?: 0) != -1
        }
        var nonEmpty = 0
        for (mood in Mood.entries) {
            val expected = Mood.strongest(offered, rows, mood, MoodMarks.of(lib.stats))
            val actual = e.strongestIn(mood)
            assertEquals("$mood", expected.map { it.id }, actual.map { it.id })
            if (actual.isNotEmpty()) nonEmpty++
        }
        assertTrue("the fixture has songs in most moods", nonEmpty >= 5)
    }
}
