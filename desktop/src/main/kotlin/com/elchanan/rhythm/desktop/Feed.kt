package com.elchanan.rhythm.desktop

import com.elchanan.rhythm.data.db.ArtistEntity
import com.elchanan.rhythm.data.db.AudioFeatureEntity
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.data.db.SongStatsEntity
import com.elchanan.rhythm.engine.AcousticSpace
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
 * Two of its inputs are still empty: affinity and transitions are learned
 * from listening history, and they fill in on their own as the user listens.
 * The recommender is built to work without them.
 *
 * The acoustic space is built here when there is enough measured to be worth
 * it. Below a handful of analysed songs the space is a z-score against almost
 * nothing, which is worse than no space at all - the distances it reports
 * would be noise wearing the shape of a measurement.
 */
object Feed {

    /** Below this, the acoustic space measures against too little to mean anything. */
    private const val MIN_ANALYSED_FOR_SPACE = 8

    /**
     * The engine itself, kept rather than built per question.
     *
     * Constructing one scores the whole library, which is the right cost to
     * pay once for a feed and the wrong cost to pay per keystroke in a search
     * box. The caller holds it and asks it both.
     */
    fun engine(
        songs: List<SongEntity>,
        stats: Map<Long, SongStatsEntity>,
        artists: List<ArtistEntity>,
        features: Map<Long, AudioFeatureEntity>,
        seed: Long,
        tuning: EngineTuning = EngineTuning()
    ) = Recommender(
        songs = songs,
        stats = stats,
        artists = artists.associateBy { it.artistKey },
        affinity = emptyMap(),
        transitions = emptyMap(),
        features = features,
        acoustic = if (features.size >= MIN_ANALYSED_FOR_SPACE) {
            AcousticSpace(features.values)
        } else {
            null
        },
        tuning = tuning,
        now = System.currentTimeMillis(),
        feedSeed = seed
    )

    /**
     * A station from one song, for when a shelf or a mix is played rather
     * than a single track.
     */
    fun radio(
        seed: SongEntity,
        songs: List<SongEntity>,
        stats: Map<Long, SongStatsEntity>,
        artists: List<ArtistEntity>,
        features: Map<Long, AudioFeatureEntity>,
        feedSeed: Long
    ): List<SongEntity> {
        if (songs.isEmpty()) return emptyList()
        return engine(songs, stats, artists, features, feedSeed).radio(seed)
    }
}
