package com.elchanan.rhythm.desktop

import com.elchanan.rhythm.data.db.ArtistEntity
import com.elchanan.rhythm.data.db.AudioFeatureEntity
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.data.db.SongStatsEntity
import com.elchanan.rhythm.engine.AudioTags
import com.elchanan.rhythm.engine.Spoken
import com.elchanan.rhythm.engine.AcousticSpace
import com.elchanan.rhythm.engine.EngineTuning
import com.elchanan.rhythm.engine.FeedSection
import com.elchanan.rhythm.engine.Recommender
import com.elchanan.rhythm.engine.TransitionEdge
import com.elchanan.rhythm.engine.Vocal

/**
 * The home screen's shelves, from the same engine the phone runs.
 *
 * Nothing here decides anything. Every judgement - what to surface, in what
 * order, under which heading - is [Recommender]'s, compiled once in :engine,
 * and this only hands it the library and passes the answer on.
 *
 * Two of its inputs come from listening rather than from the library:
 * affinity, which is what was heard near what, and transitions, which is
 * what followed what. Both start empty on a fresh install and fill in as
 * the user listens; the recommender works without them and is a good deal
 * better with them, since they carry the heaviest term in a radio and the
 * whole basis of the sequencer.
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
        tuning: EngineTuning = EngineTuning(),
        affinity: Map<Long, Map<Long, Double>> = emptyMap(),
        transitions: Map<Long, Map<Long, TransitionEdge>> = emptyMap(),
        lastHeard: Map<Long, Long> = emptyMap()
    ): Recommender {
        // Rows with no energy are placeholders for files that would not
        // decode; they must not enter the statistics of the acoustic space.
        // The phone leaves them out for the same reason.
        val rows = features.values.filter { it.energy > 0f }
        val hasMoodMarks = stats.values.any { it.moods.isNotEmpty() }
        val lean = Recommender.leanFeatures(rows, keepSoundPrint = hasMoodMarks)
        val engineArtists = com.elchanan.rhythm.engine.ArtistStyles.withCatalogue(
            artists.associateBy { it.artistKey }, songs
        )
        return Recommender(
        songs = songs,
        stats = stats,
        artists = engineArtists,
        affinity = affinity,
        transitions = transitions,
        // The space is made from the full rows; the engine keeps them without
        // what only the space reads. See Recommender.leanFeatures.
        features = lean,
        acoustic = if (rows.size >= MIN_ANALYSED_FOR_SPACE) {
            AcousticSpace(rows)
        } else {
            null
        },
        tuning = tuning,
        now = System.currentTimeMillis(),
        feedSeed = seed,
        // Talking is kept out of everything the engine generates. The detector
        // already knew which tracks these were; nothing was asking it, so a
        // shiur sat correctly on its own shelf and went on turning up in the
        // feed, in mixes and in shuffles like any other track.
        spoken = songs.filterTo(HashSet()) { song ->
            val feature = lean[song.id]
            Spoken.isSpoken(
                song,
                feature,
                feature?.tags?.let { AudioTags.pick(it, AudioTags.SPEECH_INDICES) },
                stats[song.id]?.spoken ?: -1
            )
        }.mapTo(HashSet()) { it.id },
        lastHeard = lastHeard,
        // Vocal-only songs, held back outside the Omer and the Three Weeks,
        // decided as the phone decides it.
        vocal = songs.filter { song ->
            Vocal.isVocal(song, stats[song.id], lean[song.id], engineArtists[song.artistKey]?.styles.orEmpty())
        }.mapTo(HashSet()) { it.id }
    )
    }

}
