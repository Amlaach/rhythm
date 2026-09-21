package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.db.AudioFeatureEntity

/**
 * Evening out the volume between tracks.
 *
 * A library assembled from thirty years of different masterings has a fifteen
 * decibel spread across it, and the whole of the experience of that spread is
 * reaching for the volume at every change of song. This takes the loud ones
 * down towards the middle of the library rather than pushing the quiet ones
 * up: attenuation cannot clip, and a gain above one can.
 *
 * The reference is the sixty fifth percentile rather than the mean, which
 * would be dragged down by every quiet recording in the collection and would
 * then leave most of the library needing to be turned up. Two thirds of the
 * way up is roughly "an ordinary loud track", and aiming at that leaves the
 * bulk of the library untouched with only the outliers pulled in.
 *
 * Shared by both builds so a song that plays at a given level on the phone
 * plays at that level on Windows too.
 */
object Loudness {

    /** Below this many measured songs the percentile means nothing. */
    private const val MIN_MEASURED = 8

    /** Where in the sorted energies the reference level sits. */
    private const val REFERENCE_PERCENTILE = 0.65

    /**
     * The floor. Twelve decibels of attenuation is already a lot, and letting
     * it go further would turn one freakishly loud file into a reason to make
     * the rest of the library inaudible.
     */
    private const val MIN_GAIN = 0.45f

    /**
     * A linear volume multiplier per song, or an empty map when too little of
     * the library has been measured for the answer to mean anything.
     *
     * Rows with zero energy are files that failed to decode; they are
     * placeholders, not silence, and letting them into the statistics would
     * drag the reference down.
     */
    fun gains(features: Collection<AudioFeatureEntity>): Map<Long, Float> {
        val measured = features.filter { it.energy > 0f }
        if (measured.size < MIN_MEASURED) return emptyMap()
        val sorted = measured.map { it.energy }.sorted()
        val index = ((sorted.size - 1) * REFERENCE_PERCENTILE).toInt()
            .coerceIn(0, sorted.size - 1)
        val reference = sorted[index]
        return measured.associate { f ->
            f.songId to (reference / f.energy).coerceIn(MIN_GAIN, 1f)
        }
    }
}
