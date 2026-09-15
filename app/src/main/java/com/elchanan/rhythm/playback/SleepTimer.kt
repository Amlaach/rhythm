package com.elchanan.rhythm.playback

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process wide sleep timer. Lives outside the view model on purpose: the timer
 * has to keep running after the UI is gone, and the playback service is the
 * component that observes it.
 */
object SleepTimer {

    private val _deadlineElapsed = MutableStateFlow<Long?>(null)
    val deadlineElapsed: StateFlow<Long?> = _deadlineElapsed.asStateFlow()

    /** Also set when the timer should stop at the end of the current track. */
    private val _stopAfterTrack = MutableStateFlow(false)
    val stopAfterTrack: StateFlow<Boolean> = _stopAfterTrack.asStateFlow()

    fun startMinutes(minutes: Int) {
        _stopAfterTrack.value = false
        _deadlineElapsed.value = SystemClock.elapsedRealtime() + minutes * 60_000L
    }

    fun stopAfterCurrentTrack() {
        _deadlineElapsed.value = null
        _stopAfterTrack.value = true
    }

    fun cancel() {
        _deadlineElapsed.value = null
        _stopAfterTrack.value = false
    }

    fun consumeStopAfterTrack(): Boolean {
        val v = _stopAfterTrack.value
        if (v) _stopAfterTrack.value = false
        return v
    }

    /** Remaining milliseconds, or null when no timer is armed. */
    fun remainingMs(): Long? {
        val d = _deadlineElapsed.value ?: return null
        return (d - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
    }
}
