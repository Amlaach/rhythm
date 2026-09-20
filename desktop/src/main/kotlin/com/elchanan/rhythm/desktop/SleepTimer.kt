package com.elchanan.rhythm.desktop

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Stop playing, later.
 *
 * One thread for the whole application, made daemon so an armed timer never
 * holds the process open after the window is closed - a music player that
 * will not quit because someone set a timer an hour ago is a bug people
 * report as "it keeps running in the background".
 *
 * The "after this track" case is not a timer at all: nothing here knows how
 * long is left, and a countdown computed from the remaining duration would be
 * wrong the moment someone seeks. It is a flag the player checks when a track
 * ends, which is the only moment it means anything.
 */
object SleepTimer {

    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "sleep-timer").apply { isDaemon = true }
    }

    private var pending: ScheduledFuture<*>? = null
    private var firesAt: Long = 0L

    @Volatile
    var stopAfterTrack: Boolean = false
        private set

    /** What the dialog shows, or null when nothing is armed. */
    @Synchronized
    fun remainingMs(): Long? {
        if (pending == null) return null
        val left = firesAt - System.currentTimeMillis()
        return if (left > 0) left else null
    }

    @Synchronized
    fun startMinutes(minutes: Int, onFire: () -> Unit) {
        cancel()
        firesAt = System.currentTimeMillis() + minutes * 60_000L
        pending = scheduler.schedule(
            {
                synchronized(this) {
                    pending = null
                    firesAt = 0L
                }
                onFire()
            },
            minutes.toLong(),
            TimeUnit.MINUTES
        )
    }

    @Synchronized
    fun stopAfterCurrentTrack() {
        cancel()
        stopAfterTrack = true
    }

    /**
     * Consumes the after-track flag.
     *
     * Asked once, at the end of a track, and false afterwards - the timer
     * fires once and disarms itself, the same as a countdown does.
     */
    @Synchronized
    fun consumeAfterTrack(): Boolean {
        if (!stopAfterTrack) return false
        stopAfterTrack = false
        return true
    }

    @Synchronized
    fun cancel() {
        pending?.cancel(false)
        pending = null
        firesAt = 0L
        stopAfterTrack = false
    }
}
