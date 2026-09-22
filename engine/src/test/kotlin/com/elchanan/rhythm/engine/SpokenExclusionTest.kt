package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.data.db.SongStatsEntity
import org.junit.Assert.*
import org.junit.Test

/**
 * A lecture must not behave like a track.
 *
 * The detector was right and nothing asked it: a shiur was filed on its own
 * shelf and went on appearing in the feed, in mixes, in radios and in
 * shuffles, because the engine was never told which songs were speech.
 */
class SpokenExclusionTest {

    private val lecture = song(1, "שיעור בפרשת השבוע", minutes = 52)
    private val music = (2L..25L).map { song(it, "ניגון $it", minutes = 4) }
    private val all = listOf(lecture) + music

    private fun engine(spoken: Set<Long>) = Recommender(
        songs = all,
        stats = all.associate {
            it.id to SongStatsEntity(it.id, playCount = 6, liked = 1, rating = 5)
        },
        artists = emptyMap(),
        affinity = emptyMap(),
        transitions = emptyMap(),
        features = emptyMap(),
        acoustic = null,
        tuning = EngineTuning(),
        now = System.currentTimeMillis(),
        feedSeed = 1L,
        spoken = spoken
    )

    @Test fun aLectureIsKeptOutOfEverythingGenerated() {
        val withIt = engine(emptySet())
        val without = engine(setOf(lecture.id))

        // It really was getting in before, or this proves nothing.
        assertTrue(
            "the lecture should reach the feed while nothing excludes it",
            withIt.buildFeed().any { section -> section.songs.any { it.id == lecture.id } }
        )

        for (section in without.buildFeed()) {
            assertFalse(
                "a lecture reached the feed section ${section.title}",
                section.songs.any { it.id == lecture.id }
            )
        }
        assertFalse(without.radio(music.first(), size = 40).any { it.id == lecture.id })
        assertFalse(
            without.continuation(listOf(music.first().id), emptySet(), size = 40)
                .any { it.id == lecture.id }
        )
        assertFalse(
            without.dailyMixes().any { mix -> mix.songs.any { it.id == lecture.id } }
        )
    }

    @Test fun itIsStillFindableByName() {
        // Not hidden. Being speech is a reason to keep it out of an evening's
        // listening, not a reason to pretend it is not in the library.
        val without = engine(setOf(lecture.id))
        assertTrue(without.search("שיעור").any { it.id == lecture.id })
    }

    @Test fun anEmptySetLeavesTheEngineExactlyAsItWas() {
        // Libraries with nothing spoken in them must behave identically, and
        // the parameter defaults to empty for callers not yet passing one.
        val a = engine(emptySet()).buildFeed().map { s -> s.title to s.songs.map { it.id } }
        val b = Recommender(
            songs = all,
            stats = all.associate {
                it.id to SongStatsEntity(it.id, playCount = 6, liked = 1, rating = 5)
            },
            artists = emptyMap(),
            affinity = emptyMap(),
            transitions = emptyMap(),
            features = emptyMap(),
            acoustic = null,
            tuning = EngineTuning(),
            now = System.currentTimeMillis(),
            feedSeed = 1L
        ).buildFeed().map { s -> s.title to s.songs.map { it.id } }
        assertEquals(a, b)
    }

    private fun song(id: Long, title: String, minutes: Int) = SongEntity(
        id, title, title.lowercase(), "אמן", "אמן", "אלבום", 1,
        minutes * 60_000L, 1, 2026, null, "/music/$id.mp3", "/music", 0, 1000
    )
}
