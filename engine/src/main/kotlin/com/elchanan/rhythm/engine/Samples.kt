package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.db.AudioFeatureEntity
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.data.db.SongStatsEntity
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Which songs the tastes offer, and in what order - one rule for the phone
 * and for Windows.
 *
 * Left out: anything shorter than a song or longer than six minutes (a
 * medley, a concert, a lecture), medleys by title, songs disliked, spoken
 * word, and vocal-only songs outside the weeks they are for. Songs never
 * played come first, since discovering the library is what tastes are for;
 * within each half the engine's own score leads, shaken a little so two
 * visits do not open on the same song.
 */
object Samples {

    const val MIN_MS = 45_000L
    const val MAX_MS = 6 * 60_000L
    const val LIMIT = 300

    fun choose(
        songs: List<SongEntity>,
        stats: Map<Long, SongStatsEntity>,
        features: Map<Long, AudioFeatureEntity>,
        inSeason: Boolean,
        isVocal: (SongEntity, AudioFeatureEntity?) -> Boolean,
        score: (SongEntity) -> Double,
        random: Random = Random(System.nanoTime())
    ): List<SongEntity> {
        val pool = songs.filter { song ->
            val feature = features[song.id]
            song.durationMs in MIN_MS..MAX_MS &&
                !isMedley(song.title) &&
                (stats[song.id]?.liked ?: 0) != -1 &&
                (inSeason || !isVocal(song, feature)) &&
                !Spoken.isSpoken(
                    song, feature,
                    feature?.tags?.let { AudioTags.pick(it, AudioTags.SPEECH_INDICES) },
                    stats[song.id]?.spoken ?: -1
                )
        }
        val scores = pool.associate { it.id to score(it) }
        val spread = scores.values.let { v ->
            val mean = v.average().takeIf { !it.isNaN() } ?: 0.0
            sqrt(v.sumOf { (it - mean) * (it - mean) } / maxOf(1, v.size))
        }
        val jittered = pool.associate { it.id to (scores.getValue(it.id) + random.nextDouble() * 0.5 * spread) }
        val (unheard, heard) = pool.partition { (stats[it.id]?.playCount ?: 0) == 0 }
        return (unheard.sortedByDescending { jittered.getValue(it.id) } +
            heard.sortedByDescending { jittered.getValue(it.id) }).take(LIMIT)
    }

    /** The songs already tasted this time round, moved to the end. */
    fun tastedLast(list: List<SongEntity>, tasted: Set<Long>): List<SongEntity> {
        if (tasted.isEmpty()) return list
        val (fresh, done) = list.partition { it.id !in tasted }
        return fresh + done
    }
}
