package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.data.db.SongStatsEntity
import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class VocalSeasonTest {

    private fun day(y: Int, m: Int, d: Int) = JewishSeasons.epochDay(y, m, d)

    @Test fun pesachFallsWhereTheCalendarSaysItDoes() {
        // 15 Nisan, first day of Pesach
        val known = mapOf(
            2020 to (4 to 9), 2021 to (3 to 28), 2022 to (4 to 16), 2023 to (4 to 6),
            2024 to (4 to 23), 2025 to (4 to 13), 2026 to (4 to 2), 2027 to (4 to 22),
            2028 to (4 to 11), 2029 to (3 to 31), 2030 to (4 to 18)
        )
        for ((year, md) in known) {
            assertEquals("Pesach $year", day(year, md.first, md.second), JewishSeasons.pesach(year))
        }
    }

    @Test fun theOmerAndTheThreeWeeks() {
        // 2025: Pesach 13 April. Omer 14 April .. 1 June (Shavuot 2 June), Lag BaOmer 16 May.
        assertNull(JewishSeasons.on(day(2025, 4, 13), 2025))
        assertEquals(JewishSeasons.Season.SEFIRA, JewishSeasons.on(day(2025, 4, 14), 2025))
        assertNull("Lag BaOmer", JewishSeasons.on(day(2025, 5, 16), 2025))
        assertEquals(JewishSeasons.Season.SEFIRA, JewishSeasons.on(day(2025, 6, 1), 2025))
        assertNull("Shavuot", JewishSeasons.on(day(2025, 6, 2), 2025))
        // 17 Tammuz 5785 = 13 July 2025, 9 Av = 3 August 2025
        assertNull(JewishSeasons.on(day(2025, 7, 12), 2025))
        assertEquals(JewishSeasons.Season.THREE_WEEKS, JewishSeasons.on(day(2025, 7, 13), 2025))
        assertEquals(JewishSeasons.Season.THREE_WEEKS, JewishSeasons.on(day(2025, 8, 3), 2025))
        assertNull(JewishSeasons.on(day(2025, 8, 4), 2025))
        // 2022: 9 Av fell on Shabbat 6 August, the fast was Sunday 7 August
        assertEquals(6, JewishSeasons.dayOfWeek(day(2022, 8, 6)))
        assertEquals(JewishSeasons.Season.THREE_WEEKS, JewishSeasons.on(day(2022, 8, 7), 2022))
        assertNull(JewishSeasons.on(day(2022, 8, 8), 2022))
        // 2026: 9 Av is Thursday 23 July, nothing added after it
        assertEquals(JewishSeasons.Season.THREE_WEEKS, JewishSeasons.on(day(2026, 7, 23), 2026))
        assertNull(JewishSeasons.on(day(2026, 7, 24), 2026))
    }

    private fun song(id: Long, title: String, artist: String = "זמר") = SongEntity(
        id, title, title, artist, Names.normalizeKey(artist), "al$id", id,
        240_000L, 1, 2020, null, "/m/$id", "/m/$artist", 1_600_000_000L, 1
    )

    @Test fun vocalIsReadOffTheNameAndTheUserHasTheLastWord() {
        assertTrue(Vocal.isVocal(song(1, "ניגון - ווקאלי"), null, null))
        assertTrue(Vocal.isVocal(song(2, "Shalom Aleichem (Acapella)"), null, null))
        assertFalse(Vocal.isVocal(song(3, "ניגון"), null, null))
        assertTrue(Vocal.isVocal(song(3, "ניגון"), null, null, artistStyles = "חסידי, ווקאלי"))
        assertFalse(Vocal.isVocal(song(1, "ניגון - ווקאלי"), SongStatsEntity(1, vocal = 0), null))
        assertTrue(Vocal.isVocal(song(3, "ניגון"), SongStatsEntity(3, vocal = 1), null))
        val heard = Analysis.blankFor(4).copy(energy = 0.5f, tags = "250:0.6,24:0.9")
        assertTrue(Vocal.heard(heard))
        assertFalse(Vocal.heard(heard.copy(tags = "250:0.05")))
    }

    private fun engine(now: Long, vocal: Set<Long>, only: Boolean): Recommender {
        val songs = (1L..40L).map { song(it, "שיר $it", "זמר${it % 5}") }
        return Recommender(
            songs, emptyMap<Long, SongStatsEntity>(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), null,
            EngineTuning(onlyVocalInSeason = only), now, 1L, emptySet(), emptyMap(), vocal
        )
    }

    private fun at(y: Int, m: Int, d: Int): Long =
        (JewishSeasons.epochDay(y, m, d) * 86_400_000L) + 12 * 3_600_000L - TimeZone.getDefault().getOffset(0L)

    @Test fun vocalSongsStayOutOfTheFeedExceptInTheirSeason() {
        val vocal = (1L..20L).toSet()
        val ordinary = engine(at(2025, 9, 1), vocal, only = false)
        assertNull(ordinary.season)
        val offered = ordinary.buildFeed().flatMap { it.mixes }.flatMap { it.songs }.map { it.id }.toSet()
        assertTrue(offered.isNotEmpty())
        assertTrue("vocal songs offered out of season: ${offered.filter { it in vocal }}", offered.none { it in vocal })

        val omer = engine(at(2025, 5, 1), vocal, only = true)
        assertEquals(JewishSeasons.Season.SEFIRA, omer.season)
        val inSeason = omer.buildFeed().flatMap { it.mixes }.flatMap { it.songs }.map { it.id }.toSet()
        assertTrue(inSeason.isNotEmpty())
        assertTrue("only vocal in the Omer: ${inSeason.filterNot { it in vocal }}", inSeason.all { it in vocal })
    }
}
