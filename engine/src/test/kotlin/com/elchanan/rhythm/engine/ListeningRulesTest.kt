package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.data.db.SongStatsEntity
import org.junit.Assert.*
import org.junit.Test

/**
 * Two rules, asked for in so many words:
 *
 *  - hear an artist, and more of that artist should come up;
 *  - skip a song straight away and it should drop a little - unless you
 *    listened to it afterwards.
 *
 * Each rule is tested next to what it must not turn into.
 */
class ListeningRulesTest {

    private val now = 1_750_000_000_000L
    private val day = 86_400_000L

    private fun song(id: Long, artist: String) = SongEntity(
        id, "שיר $id", "שיר $id", artist, Names.normalizeKey(artist), "al$id", id,
        240_000, 1, 2020, null, "/m/$id", "/m/$artist", 1_600_000_000L, 1
    )

    private fun engine(
        songs: List<SongEntity>,
        stats: Map<Long, SongStatsEntity>,
        lastHeard: Map<Long, Long> = emptyMap()
    ) = Recommender(
        songs, stats, emptyMap(), emptyMap(), emptyMap(), emptyMap(), null,
        EngineTuning(), now, 1L, emptySet(), lastHeard
    )

    private fun term(e: Recommender, s: SongEntity, label: String) =
        e.explain(s).firstOrNull { it.label == label }?.value ?: 0.0

    // --- hearing an artist ------------------------------------------------

    private val heard = (1L..10L).map { song(it, "ישי") }
    private val stranger = (11L..20L).map { song(it, "פלוני") }

    @Test fun hearingAnArtistRaisesTheirOtherSongs() {
        // Five of ישי's songs heard three times each; the other five never.
        val stats = heard.take(5).associate {
            it.id to SongStatsEntity(it.id, playCount = 3, completeCount = 3, lastPlayedAt = now - 2 * day)
        }
        val e = engine(heard + stranger, stats)
        val untouchedIshay = heard.last()
        val untouchedStranger = stranger.last()
        assertTrue(
            "an unheard song by an artist you listen to should outrank one by an artist you do not",
            e.totalScore(untouchedIshay) > e.totalScore(untouchedStranger) + 0.3
        )
        assertTrue(term(e, untouchedIshay, "האזנה לאמן") > 0.0)
        assertEquals(0.0, term(e, untouchedStranger, "האזנה לאמן"), 0.0)
    }

    @Test fun anArtistYouKeepSkippingSinksInstead() {
        val stats = heard.take(5).associate {
            it.id to SongStatsEntity(it.id, skipCount = 3, listenedMs = 3 * 5_000, lastPlayedAt = now - 2 * day)
        }
        val e = engine(heard + stranger, stats)
        assertTrue(term(e, heard.last(), "האזנה לאמן") < 0.0)
        assertTrue(e.totalScore(heard.last()) < e.totalScore(stranger.last()))
    }

    @Test fun aSongsOwnPlaysDoNotCountTwice() {
        // The only song by this artist: its plays are already in its own score,
        // and there are no other songs of theirs for the listening to speak of.
        val only = song(99, "יחיד")
        val stats = mapOf(99L to SongStatsEntity(99, playCount = 40, completeCount = 40, lastPlayedAt = now - 3 * day))
        val e = engine(listOf(only) + stranger, stats)
        assertEquals(0.0, term(e, only, "האזנה לאמן"), 0.0)
    }

    @Test fun itSaturatesAndCannotFillAShelf() {
        // An artist played to death - two hundred songs, ten plays each - and
        // a dozen others with nothing played at all.
        val flood = (1L..200L).map { song(it, "שוואקי") }
        val others = (1000L..1059L).map { song(it, "אמן${it % 12}") }
        val stats = flood.take(150).associate {
            it.id to SongStatsEntity(it.id, playCount = 10, completeCount = 10, lastPlayedAt = now - 5 * day)
        }
        val e = engine(flood + others, stats)
        assertTrue(
            "the listening term must stay bounded",
            term(e, flood.last(), "האזנה לאמן") <= 1.0 + 1e-9
        )
        val quick = e.buildFeed().first { it.id == "quick" }.songs
        val share = quick.count { it.artistName == "שוואקי" }.toDouble() / quick.size
        assertTrue("one artist took ${(share * 100).toInt()}% of the quick picks", share <= 0.5)
    }

    // --- skipping straight away ------------------------------------------

    @Test fun aSkipAtFourSecondsCostsMoreThanOneNearTheEnd() {
        val early = song(1, "א")
        val late = song(2, "א")
        val stats = mapOf(
            1L to SongStatsEntity(1, skipCount = 1, listenedMs = 4_000, lastPlayedAt = now - 3 * day),
            2L to SongStatsEntity(2, skipCount = 1, listenedMs = 170_000, lastPlayedAt = now - 3 * day)
        )
        val e = engine(listOf(early, late) + stranger, stats)
        assertTrue(
            "a skip after four seconds and one after seventy per cent cost the same",
            e.totalScore(early) < e.totalScore(late) - 0.2
        )
    }

    @Test fun listeningAfterASkipForgivesIt() {
        val s = song(1, "א")
        val base = SongStatsEntity(1, playCount = 3, completeCount = 3, skipCount = 1, listenedMs = 3 * 240_000 + 4_000)
        // Same counts, opposite order. Skipped last: the last touch is later
        // than the last play in the history.
        val skippedLast = base.copy(lastPlayedAt = now - 2 * day)
        val heardLast = base.copy(lastPlayedAt = now - 2 * day)
        val lastPlay = mapOf(1L to now - 5 * day)       // play, then the skip
        val playAfter = mapOf(1L to now - 2 * day)      // skip, then the play
        val afterSkip = engine(listOf(s) + stranger, mapOf(1L to skippedLast), lastPlay)
        val afterPlay = engine(listOf(s) + stranger, mapOf(1L to heardLast), playAfter)
        assertTrue(
            "a skip followed by a play should weigh far less than one that came last",
            term(afterPlay, s, "דילוגים") > term(afterSkip, s, "דילוגים") + 0.1
        )
    }

    @Test fun thirtySkipsAreNotErasedByOnePlay() {
        val s = song(1, "א")
        val stats = SongStatsEntity(
            1, playCount = 1, completeCount = 1, skipCount = 30,
            listenedMs = 240_000 + 30 * 5_000, lastPlayedAt = now - day
        )
        val e = engine(listOf(s) + stranger, mapOf(1L to stats), mapOf(1L to now - day))
        assertTrue(
            "thirty skips vanished on the strength of one play",
            term(e, s, "דילוגים") < -0.6
        )
    }

    @Test fun withoutHistoryNothingIsForgivenByGuesswork() {
        // A song that has been skipped and is missing from the history: there
        // is no telling which came last, so nothing is forgiven.
        val s = song(1, "א")
        val stats = SongStatsEntity(1, playCount = 3, completeCount = 3, skipCount = 1, lastPlayedAt = now - day)
        val withHistory = engine(listOf(s), mapOf(1L to stats), mapOf(1L to now - day))
        val without = engine(listOf(s), mapOf(1L to stats))
        assertTrue(term(without, s, "דילוגים") < term(withHistory, s, "דילוגים"))
    }
}
