package com.elchanan.rhythm.desktop

import com.elchanan.rhythm.data.db.ArtistEntity
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.data.db.SongStatsEntity
import com.elchanan.rhythm.engine.EngineTuning
import com.elchanan.rhythm.engine.FeedSection
import com.elchanan.rhythm.engine.Recommender

/**
 * The home screen's shelves, from the same engine the phone runs.
 *
 * Nothing here decides anything. Every judgement - what to surface, in what
 * order, under which heading - is [Recommender]'s, compiled once in :engine,
 * and this only hands it the library and passes the answer on.
 *
 * Four of its inputs are empty, and knowingly so:
 *
 *  - affinity and transitions are learned from listening history this build
 *    has only just started keeping. They fill in on their own.
 *  - features and the acoustic space come from audio analysis, which needs a
 *    PCM decoder feeding the DSP and is the next piece of work.
 *
 * The recommender is built to work without them: they raise the ceiling, and
 * ratings, likes, play counts, artists and styles carry the feed until then.
 * Handing it empty maps is how it is meant to start, not a shortcut.
 */
object Feed {

    fun build(
        songs: List<SongEntity>,
        stats: Map<Long, SongStatsEntity>,
        artists: List<ArtistEntity>,
        seed: Long
    ): List<FeedSection> {
        if (songs.isEmpty()) return emptyList()
        val recommender = Recommender(
            songs = songs,
            stats = stats,
            artists = artists.associateBy { it.artistKey },
            affinity = emptyMap(),
            transitions = emptyMap(),
            features = emptyMap(),
            acoustic = null,
            tuning = EngineTuning(),
            now = System.currentTimeMillis(),
            feedSeed = seed
        )
        return recommender.buildFeed()
    }

    /**
     * A station from one song, for when a mix or a shelf is played rather
     * than a single track.
     */
    fun radio(
        seed: SongEntity,
        songs: List<SongEntity>,
        stats: Map<Long, SongStatsEntity>,
        artists: List<ArtistEntity>,
        feedSeed: Long
    ): List<SongEntity> {
        if (songs.isEmpty()) return emptyList()
        return Recommender(
            songs = songs,
            stats = stats,
            artists = artists.associateBy { it.artistKey },
            affinity = emptyMap(),
            transitions = emptyMap(),
            features = emptyMap(),
            acoustic = null,
            tuning = EngineTuning(),
            now = System.currentTimeMillis(),
            feedSeed = feedSeed
        ).radio(seed)
    }
}
