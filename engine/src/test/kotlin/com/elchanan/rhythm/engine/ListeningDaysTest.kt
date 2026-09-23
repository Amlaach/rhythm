package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.data.db.SongStatsEntity
import org.junit.Assert.*
import org.junit.Test

/** Settled taste over momentary behaviour. */
class ListeningDaysTest {

    private val now = 1_750_000_000_000L
    private val day = 86_400_000L

    private fun song(id: Long) = SongEntity(
        id, "שיר $id", "שיר $id", "זמר$id", "זמר$id", "al$id", id,
        240_000L, 1, 2020, null, "/m/$id", "/m", 1_600_000_000L, 1
    )

    private fun engine(stats: Map<Long, SongStatsEntity>) = Recommender(
        (1L..6L).map { song(it) }, stats, emptyMap(), emptyMap(), emptyMap(), emptyMap(), null,
        EngineTuning(), now, 1L, emptySet(), emptyMap()
    )

    @Test fun playsSpreadOverManyDaysCountForMoreThanOneEveningOfRepeats() {
        val e = engine(mapOf(
            1L to SongStatsEntity(1L, playCount = 10, completeCount = 10, playDays = 10, lastPlayedAt = now - 2 * day),
            2L to SongStatsEntity(2L, playCount = 10, completeCount = 10, playDays = 1, lastPlayedAt = now - 2 * day),
            3L to SongStatsEntity(3L, playCount = 10, completeCount = 10, playDays = 0, lastPlayedAt = now - 2 * day)
        ))
        val settled = e.totalScore(song(1))
        val evening = e.totalScore(song(2))
        val unknown = e.totalScore(song(3))
        assertTrue("settled $settled evening $evening", settled > evening)
        assertTrue("unknown days are neither: $unknown", unknown < settled && unknown > evening)
    }

    @Test fun flickingThroughSongsIsNotTurningEachOneOff() {
        val e = engine(mapOf(
            1L to SongStatsEntity(1L, skipCount = 4, burstSkips = 4, lastPlayedAt = now - day),
            2L to SongStatsEntity(2L, skipCount = 4, burstSkips = 0, lastPlayedAt = now - day)
        ))
        assertTrue(e.totalScore(song(1)) > e.totalScore(song(2)))
    }

    @Test fun skipsFadeAsTheyAgeButNeverVanish() {
        val e = engine(mapOf(
            1L to SongStatsEntity(1L, skipCount = 5, lastSkipAt = now - 400 * day, lastPlayedAt = now - 400 * day),
            2L to SongStatsEntity(2L, skipCount = 5, lastSkipAt = now - 2 * day, lastPlayedAt = now - 400 * day),
            3L to SongStatsEntity(3L, lastPlayedAt = now - 400 * day)
        ))
        val old = e.totalScore(song(1))
        val fresh = e.totalScore(song(2))
        val never = e.totalScore(song(3))
        assertTrue("old $old fresh $fresh", old > fresh)
        assertTrue("an old skip still counts: $old vs $never", old < never)
    }
}
