package com.elchanan.rhythm.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import com.elchanan.rhythm.engine.EqFilters
import com.elchanan.rhythm.engine.EqSettings
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * The thirty one band equaliser, sitting in the player's audio path.
 *
 * ExoPlayer hands every buffer of decoded PCM to the processors in its chain
 * before the audio sink writes them out, which is the only place in the app
 * where the actual samples exist. Filtering here rather than through
 * `android.media.audiofx` means the result is the same on every device
 * instead of whatever that device's manufacturer implemented, and it means
 * there are thirty one bands rather than five.
 *
 * The one thing to be careful about is the thread. Everything in here runs on
 * the audio thread, where blocking is not late, it is a dropout. So the
 * settings arrive as a single immutable reference that the settings screen
 * swaps in, and this side picks it up at a buffer boundary; nothing is locked
 * and nothing is allocated while audio is flowing.
 */
class GraphicEqProcessor : BaseAudioProcessor() {

    private val filters = EqFilters()

    /**
     * Written by whoever is holding the sliders, read by the audio thread.
     * Volatile is the whole of the synchronisation: the reference swap is
     * atomic, [EqSettings] is immutable, and a reader that catches the old one
     * is simply a buffer late.
     */
    @Volatile
    private var settings: EqSettings = EqSettings.FLAT

    /** The settings the filters were last built for, audio thread only. */
    private var applied: EqSettings? = null

    private var floatInput = false

    /** One frame, reused. Allocating here would allocate per buffer. */
    private var frame = FloatArray(0)

    fun setSettings(value: EqSettings) {
        settings = value
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val encoding = inputAudioFormat.encoding
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        floatInput = encoding == C.ENCODING_PCM_FLOAT
        frame = FloatArray(inputAudioFormat.channelCount)
        filters.configure(inputAudioFormat.sampleRate, inputAudioFormat.channelCount, settings)
        applied = settings
        // Same format out as in: this changes the samples, not their shape.
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val position = inputBuffer.position()
        val limit = inputBuffer.limit()
        val size = limit - position
        if (size == 0) return

        // Coefficients are rebuilt here rather than when a slider moves, so
        // that a burst of touch events costs one rebuild per buffer instead of
        // one per event, and so the rebuild never happens halfway through a
        // frame.
        val wanted = settings
        if (wanted !== applied) {
            filters.update(wanted)
            applied = wanted
        }

        val output = replaceOutputBuffer(size)
        val channels = frame.size

        if (channels == 0 || filters.isBypassing) {
            output.put(inputBuffer)
        } else if (floatInput) {
            var index = position
            while (index + 4 * channels <= limit) {
                for (c in 0 until channels) frame[c] = inputBuffer.getFloat(index + 4 * c)
                filters.processFrame(frame)
                for (c in 0 until channels) output.putFloat(frame[c])
                index += 4 * channels
            }
            inputBuffer.position(index)
            // A trailing part-frame would desynchronise the channels if it were
            // filtered, so it is copied straight through.
            while (inputBuffer.hasRemaining()) output.put(inputBuffer.get())
        } else {
            var index = position
            while (index + 2 * channels <= limit) {
                for (c in 0 until channels) {
                    frame[c] = inputBuffer.getShort(index + 2 * c) / 32768f
                }
                filters.processFrame(frame)
                for (c in 0 until channels) {
                    val scaled = frame[c] * 32768f
                    output.putShort(
                        when {
                            scaled >= 32767f -> 32767
                            scaled <= -32768f -> -32768
                            else -> scaled.roundToInt().toShort()
                        }
                    )
                }
                index += 2 * channels
            }
            inputBuffer.position(index)
            while (inputBuffer.hasRemaining()) output.put(inputBuffer.get())
        }

        output.flip()
    }

    /**
     * A seek or a track change means the samples about to arrive have nothing
     * to do with the ones before them, so the filters' memory of the past is
     * wrong and has to go. Leaving it would put a short thump at the start of
     * every track - the filters ringing out the end of the previous one.
     */
    override fun onFlush() {
        filters.reset()
    }

    override fun onReset() {
        filters.reset()
        applied = null
        frame = FloatArray(0)
    }
}
