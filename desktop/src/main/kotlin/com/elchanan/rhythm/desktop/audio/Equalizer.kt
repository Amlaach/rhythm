package com.elchanan.rhythm.desktop.audio

import com.elchanan.rhythm.engine.EqBands
import com.elchanan.rhythm.engine.EqFilters
import com.elchanan.rhythm.engine.EqSettings

/**
 * The thirty one band equaliser, in the desktop's playback loop.
 *
 * The filtering itself is [EqFilters] over in :engine - the same biquads,
 * the same solver, the same coefficients the phone puts in its ExoPlayer
 * audio processor. This is only the part that differs: on Android the
 * samples arrive as a ByteBuffer from a decoder, and here they arrive as a
 * byte array on the way to a SourceDataLine.
 *
 * There were six bands here before, with their own cookbook arithmetic
 * beside the phone's. Six is enough to make music duller or brighter and
 * not enough to do anything specific, and two implementations of the same
 * idea is two things to keep in step. Now there is one of each.
 */
class Equalizer {

    private val filters = EqFilters()

    /**
     * What the sliders currently say.
     *
     * Volatile because the audio thread reads it and whoever is holding a
     * slider writes it. [EqSettings] is immutable, so the worst a reader can
     * see is the previous set of gains for one more buffer.
     */
    @Volatile
    var settings: EqSettings = EqSettings.FLAT
        private set

    /** The settings the coefficients were last built for. Audio thread only. */
    private var applied: EqSettings? = null

    private var channels = 0

    /** One frame, reused - allocating here would allocate per buffer. */
    private var frame = FloatArray(0)

    var enabled: Boolean
        get() = settings.enabled
        set(value) {
            settings = settings.copy(enabled = value)
        }

    fun gain(band: Int): Int = settings.bands.getOrElse(band) { 0 }

    fun setGain(band: Int, millibels: Int) {
        settings = settings.withBand(band, millibels)
    }

    fun setPreamp(millibels: Int) {
        settings = settings.copy(
            preampMb = millibels.coerceIn(EqBands.PREAMP_MIN_MB, EqBands.PREAMP_MAX_MB)
        )
    }

    fun setBands(bands: List<Int>) {
        settings = EqSettings.of(settings.enabled, bands, settings.preampMb)
    }

    fun reset() {
        settings = settings.copy(bands = List(EqBands.COUNT) { 0 }, preampMb = 0)
    }

    /** Puts back what was stored, in one step, at startup. */
    fun restore(enabled: Boolean, bands: List<Int>, preampMb: Int) {
        settings = EqSettings.of(enabled, bands, preampMb)
    }

    /**
     * Sizes the filter bank for a stream that is about to play.
     *
     * Per track rather than once: the sample rate and the channel count are
     * properties of the file, and both change every coefficient.
     */
    fun prepare(sampleRate: Int, channelCount: Int) {
        channels = channelCount.coerceAtLeast(1)
        frame = FloatArray(channels)
        filters.configure(sampleRate, channels, settings)
        applied = settings
    }

    /**
     * Filters one buffer of 16 bit little endian PCM, in place.
     *
     * In place because the alternative is allocating a second buffer sixty
     * times a second for the length of every song.
     */
    fun process(buffer: ByteArray, length: Int) {
        if (channels <= 0) return
        // Rebuilt here rather than when a slider moves, so a burst of drag
        // events costs one rebuild per buffer instead of one per event, and
        // so a rebuild never lands halfway through a frame.
        val wanted = settings
        if (wanted !== applied) {
            filters.update(wanted)
            applied = wanted
        }
        if (filters.isBypassing) return

        val frameBytes = channels * 2
        val frames = length / frameBytes
        var at = 0
        for (f in 0 until frames) {
            for (c in 0 until channels) {
                val lo = buffer[at].toInt() and 0xFF
                val hi = buffer[at + 1].toInt()
                frame[c] = ((hi shl 8) or lo) / 32768f
                at += 2
            }
            filters.processFrame(frame)
            at -= frameBytes
            for (c in 0 until channels) {
                // The soft clip inside processFrame keeps this inside full
                // scale; the coerce is the belt to its braces, because a
                // wrapped sixteen bit integer is not quiet distortion - it
                // is a crack loud enough to hear across the room.
                val scaled = (frame[c] * 32768f).coerceIn(-32768f, 32767f).toInt()
                buffer[at] = (scaled and 0xFF).toByte()
                buffer[at + 1] = ((scaled shr 8) and 0xFF).toByte()
                at += 2
            }
        }
    }
}
