package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.data.db.SongStatsEntity
import org.junit.Assert.*
import org.junit.Test

/**
 * A skip or a thumbs down says most about the song it was given to; its
 * artist and style should feel it far less than a like.
 */
class NegativeSpillTest {

    private val now = 1_750_000_000_000L

    private fun song(id: Long, artist: String) = SongEntity(
        id, "שיר $id", "שיר $id", artist, Names.normalizeKey(artist), "al$id", id,
        240_000L, 1, 2020, null, "/m/$id", "/m/$artist", 1_600_000_000L, 1
    )

    @Test fun aDislikeHurtsItsArtistLessThanALikeHelpsIt() {
        val songs = (1L..3L).map { song(it, "אהוב") } + (11L..13L).map { song(it, "דחוי") } +
            (21L..23L).map { song(it, "אחר") }
        val stats = mapOf(
            1L to SongStatsEntity(1L, liked = 1, playCount = 3, completeCount = 3, lastPlayedAt = now - 86_400_000L * 5),
            11L to SongStatsEntity(11L, liked = -1, skipCount = 3, lastPlayedAt = now - 86_400_000L * 5)
        )
        val e = Recommender(
            songs, stats, emptyMap(), emptyMap(), emptyMap(), emptyMap(), null,
            EngineTuning(), now, 1L, emptySet(), emptyMap()
        )
        val lifted = e.totalScore(songs[1]) - e.totalScore(songs[7])
        val sunk = e.totalScore(songs[7]) - e.totalScore(songs[4])
        assertTrue("lift $lifted sink $sunk", lifted > 0 && sunk > 0)
        assertTrue("the like should reach its artist more than the dislike reaches its: $lifted vs $sunk", lifted > sunk * 1.5)
        // and the disliked song itself still carries its full penalty
        assertTrue(e.totalScore(songs[3]) < e.totalScore(songs[4]) - 1.0)
    }
}
