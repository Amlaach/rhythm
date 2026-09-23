package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.data.db.SongStatsEntity
import org.junit.Assert.*
import org.junit.Test

/**
 * "Based on your likes, and what is around them" has to have both halves.
 *
 * All the liked songs went in and the list was cut at fifty afterwards, so
 * past fifty likes the surroundings were cut off, and a singer with fifteen
 * liked songs could fill most of it.
 */
class LikedMixTest {

    private fun song(id: Long, artist: Int) = SongEntity(
        id, "שיר $id", "שיר $id", "אמן $artist", "artist$artist", "al", id, 240_000L,
        1, 2020, null, "/m/$id", "/m", 1L, 1L
    )

    @Test fun manyLikesLeaveRoomForTheirSurroundings() {
        // 80 liked songs, 20 of them by one singer; 80 more nobody marked.
        val liked = (1L..80L).map { song(it, if (it <= 20) 0 else (it % 12).toInt() + 1) }
        val rest = (81L..160L).map { song(it, (it % 12).toInt() + 20) }
        val all = liked + rest
        val stats = liked.associate { it.id to SongStatsEntity(it.id, playCount = 2, liked = 1, likedAt = it.id) }
        val engine = Recommender(
            songs = all, stats = stats, artists = emptyMap(), affinity = emptyMap(), transitions = emptyMap(),
            features = emptyMap(), acoustic = null, tuning = EngineTuning(), now = 1_790_000_000_000L, feedSeed = 5L
        )
        val mix = engine.buildFeed().flatMap { it.mixes }.first { it.id == "mix:liked" }
        val likedIds = liked.map { it.id }.toSet()
        val fromLikes = mix.songs.filter { it.id in likedIds }
        assertTrue("only ${mix.songs.size} songs", mix.songs.size >= 40)
        assertTrue("no surroundings at all", mix.songs.any { it.id !in likedIds })
        assertTrue("${fromLikes.size} liked songs", fromLikes.size <= 30)
        assertTrue(
            "one singer took ${fromLikes.count { it.artistKey == "artist0" }} of the liked places",
            fromLikes.count { it.artistKey == "artist0" } <= 3
        )
    }
}
