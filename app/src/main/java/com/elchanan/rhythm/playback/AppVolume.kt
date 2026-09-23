package com.elchanan.rhythm.playback

/**
 * The player's own volume, apart from the phone's.
 *
 * The phone's volume is shared with everything else on it - a call, a video,
 * the next app - and turning one of them down turns them all down. This one
 * belongs to the player alone: the service multiplies it into whatever else
 * sets the output level (the loudness levelling, the fade between tracks), so
 * each of those keeps doing its job at the listener's level.
 *
 * The slider position is kept, not the gain: the gain is its square, because
 * loudness is heard on a curve and a straight line puts all the useful range
 * in the first third of the slider.
 */
object AppVolume {

    /** The slider, 0..1. */
    @Volatile
    var position: Float = 1f
        private set

    /** What the output is multiplied by. */
    val gain: Float get() = position * position

    /** Set by the service while it is running, to apply a change at once. */
    @Volatile
    var onChange: (() -> Unit)? = null

    fun set(value: Float) {
        position = value.coerceIn(0f, 1f)
        onChange?.invoke()
    }
}
