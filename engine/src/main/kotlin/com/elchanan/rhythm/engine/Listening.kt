package com.elchanan.rhythm.engine

/**
 * What counts as having listened to something.
 *
 * One copy, because the recommender divides skips by attempts: two builds
 * that disagree about what an attempt is will rank the same library
 * differently, and the same person uses both. The rule used to be written out
 * twice - once in the phone's playback service and once beside the desktop's
 * queue - and the two had already drifted apart on the case where a file's
 * length is unknown.
 */
object Listening {

    /**
     * How long a track must be heard before it is measured at all.
     *
     * Touching a song and moving on is not a play, and it is not a skip
     * either: it is someone looking for something else. Counting it as a
     * play inflates what the recommender thinks is liked; counting it as a
     * skip buries a song nobody rejected.
     *
     * Adjustable, because the right number is a matter of how someone uses
     * the app rather than a fact about music. Anyone who browses by ear
     * wants it higher than anyone who does not.
     */
    const val DEFAULT_MINIMUM_SEC = 3

    /** The range the setting offers: off, up to half a minute. */
    const val MIN_MINIMUM_SEC = 0
    const val MAX_MINIMUM_SEC = 30

    /** Half the track is a play, whatever its length. */
    private const val PLAY_FRACTION = 0.5

    /** Or this much of it, so an hour of speech does not need half an hour. */
    private const val PLAY_MS = 90_000L

    /** What a file with no length in its tags needs instead of a fraction. */
    private const val PLAY_MS_UNKNOWN_LENGTH = 60_000L

    /**
     * Whether a track was listened to, as opposed to passed over.
     *
     * @param endedOnItsOwn the track reached its end rather than being left.
     *   A play whatever its length, which is the case the thresholds cannot
     *   see: a forty second interlude never reaches either bar and was still
     *   heard in full. The phone was missing this clause, so an interlude in
     *   a file with no duration tag counted on one platform and not the other.
     * @param minimumMs how long counts as touching it. Below this nothing is
     *   recorded at all - see [DEFAULT_MINIMUM_SEC].
     */
    fun countsAsPlay(
        heardMs: Long,
        durationMs: Long,
        endedOnItsOwn: Boolean,
        minimumMs: Long = DEFAULT_MINIMUM_SEC * 1000L
    ): Boolean {
        if (heardMs < minimumMs) return false
        if (endedOnItsOwn) return true
        if (durationMs <= 0L) return heardMs >= PLAY_MS_UNKNOWN_LENGTH
        return heardMs >= durationMs * PLAY_FRACTION || heardMs >= PLAY_MS
    }

    /**
     * Whether leaving a track early was a rejection.
     *
     * Only what was heard for long enough to have been judged. Anything
     * shorter is the same "not this one" as above and says nothing about the
     * song, so it is left out of the ratio entirely rather than counted
     * against it.
     */
    fun countsAsSkip(
        heardMs: Long,
        durationMs: Long,
        endedOnItsOwn: Boolean,
        minimumMs: Long = DEFAULT_MINIMUM_SEC * 1000L
    ): Boolean =
        heardMs >= minimumMs && !endedOnItsOwn &&
            !countsAsPlay(heardMs, durationMs, endedOnItsOwn, minimumMs)

    /** Clamps whatever a settings screen produced into a usable bar. */
    fun minimumMsOf(seconds: Int): Long =
        seconds.coerceIn(MIN_MINIMUM_SEC, MAX_MINIMUM_SEC) * 1000L
}
