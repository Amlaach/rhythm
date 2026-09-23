package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.data.db.SongStatsEntity
import org.junit.Assert.*
import org.junit.Test

/**
 * The genre a song counts under: the user's when they set one, never a
 * placeholder, and never a word so broad it names nothing.
 */
class GenreWordsTest {

    private fun song(id: Long, genre: String?) = SongEntity(
        id, "שיר $id", "שיר $id", "אמן ${id % 7}", "artist${id % 7}", "al", id, 240_000L,
        1, 2020, genre, "/m/$id", "/m", 1L, 1L
    )

    private fun engine(songs: List<SongEntity>, stats: Map<Long, SongStatsEntity>) = Recommender(
        songs = songs, stats = stats, artists = emptyMap(), affinity = emptyMap(), transitions = emptyMap(),
        features = emptyMap(), acoustic = null, tuning = EngineTuning(), now = 1_790_000_000_000L, feedSeed = 1L
    )

    private fun styleWords(e: Recommender, s: SongEntity) =
        e.explain(s).first { it.label == "התאמת סגנון" }.detail

    @Test fun theGenreTheUserSetIsTheOneThatCounts() {
        val songs = listOf(song(1, "Pop"), song(2, "Other"), song(3, "(12)"))
        val e = engine(songs, mapOf(1L to SongStatsEntity(1L, genre = "חסידי")))
        assertTrue(styleWords(e, songs[0]).contains("חסידי"))
        assertFalse(styleWords(e, songs[0]).contains("pop"))
        assertFalse("a placeholder genre", styleWords(e, songs[1]).contains("other"))
        assertFalse("an ID3 number", styleWords(e, songs[2]).contains("12"))
    }

    @Test fun aFileGenreOnMostOfTheLibraryNamesNoMix() {
        // Nine in ten files say "Jewish"; the rest a real style. All played.
        val songs = (1L..60L).map { song(it, if (it % 10 == 0L) "חזנות" else "Jewish") }
        val stats = songs.associate { it.id to SongStatsEntity(it.id, playCount = 5, completeCount = 5, liked = 1) }
        val mixes = engine(songs, stats).buildFeed().flatMap { it.mixes }
        assertFalse(mixes.any { it.id == "mix:style:jewish" })
    }
}
