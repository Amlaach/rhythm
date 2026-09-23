package com.elchanan.rhythm.engine

import kotlin.math.pow

/**
 * Old listening fades out of "heard together" and "came next".
 *
 * Both only ever added up. A pair heard together two years ago counted
 * exactly as much as one heard together yesterday, so a radio and the queue
 * went on following what someone used to play, long after their listening
 * had moved on - the one part of the engine taste drift never reached.
 *
 * An exact exponentially fading sum, which needs nothing more than the one
 * time each edge already stores: the weight is what it was at [updatedAt],
 * brought forward to now when it is read, and brought forward before
 * anything is added to it. A year's half-life, so this is taste changing,
 * not the app forgetting last month.
 *
 * In :engine so the phone and Windows fade the same way.
 */
object EdgeDecay {

    const val HALF_LIFE_DAYS = 365.0

    private const val DAY_MS = 86_400_000.0

    /** What [weight], as it stood at [updatedAt], is worth at [now]. */
    fun at(weight: Double, updatedAt: Long, now: Long): Double {
        if (updatedAt <= 0L || now <= updatedAt) return weight
        return weight * 0.5.pow((now - updatedAt) / (HALF_LIFE_DAYS * DAY_MS))
    }

    /** [weight] brought forward to [now], and [add] added. Stored with [now] as its time. */
    fun bump(weight: Double, updatedAt: Long, now: Long, add: Double): Double = at(weight, updatedAt, now) + add
}
