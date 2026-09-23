package com.elchanan.rhythm.engine

import kotlin.math.sqrt

/**
 * Where the silence at the end of a track begins, if it has one worth skipping.
 *
 * Plenty of files end in several seconds of nothing - a rip that ran on, a
 * hidden track twenty seconds later, a fade that finished long before the
 * file did. In a radio that goes on by itself, those seconds are dead air
 * between two songs. Given the last stretch of a track, this finds the last
 * moment anything was heard; the player moves on from there.
 *
 * Measured in short windows, so a single click in the silence does not count
 * as sound, and only a silence long enough to be noticed is reported: a
 * second of breath at the end of a song is part of the song.
 */
object TrailingSilence {

    /** Quieter than this is silence: -50 dBFS, below anything a fade still carries. */
    const val THRESHOLD = 0.00316f

    /** Shorter than this, the ending is left alone. */
    const val MIN_SILENCE_MS = 2_000L

    /** Kept after the last sound, so a note's own tail is not clipped. */
    const val GRACE_MS = 400L

    private const val WINDOW_MS = 50

    /**
     * @param samples mono samples of the end of the track
     * @param sampleRate their rate
     * @param startMs where in the track [samples] begins
     * @param durationMs the track's length
     * @return where to move on, in the track's time, or null when the track
     *   has no silence at its end worth skipping.
     */
    fun moveOnAt(samples: FloatArray, sampleRate: Int, startMs: Long, durationMs: Long): Long? {
        if (sampleRate <= 0 || samples.isEmpty() || durationMs <= 0) return null
        val window = (sampleRate * WINDOW_MS / 1000).coerceAtLeast(1)
        val windows = samples.size / window
        if (windows == 0) return null
        fun loud(w: Int): Boolean {
            var sum = 0.0
            val from = w * window
            for (i in from until from + window) sum += samples[i] * samples[i].toDouble()
            return sqrt(sum / window) > THRESHOLD
        }
        // Sound is two windows in a row: a click is one, and it is not music.
        var lastLoud = -1
        var after = false
        for (w in windows - 1 downTo 0) {
            val now = loud(w)
            if (now && after) {
                lastLoud = w + 1
                break
            }
            after = now
        }
        // Nothing heard in the whole stretch: not enough known to cut anything.
        if (lastLoud < 0) return null
        val soundEndsMs = startMs + (lastLoud + 1L) * WINDOW_MS
        val moveOn = soundEndsMs + GRACE_MS
        return if (durationMs - moveOn >= MIN_SILENCE_MS) moveOn else null
    }
}
