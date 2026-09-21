package com.elchanan.rhythm.desktop.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.FloatControl
import javax.sound.sampled.SourceDataLine
import kotlin.math.log10

/** What the screen needs to know, and nothing the player keeps for itself. */
data class PlayerState(
    val file: File? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    /** Set when a file could not be opened, so the screen can say which. */
    val error: String? = null
)

/**
 * Playback on a desktop JVM.
 *
 * This is the part with no Android equivalent to lean on. ExoPlayer decoded,
 * buffered, resampled and pushed to the speaker; here that is javax.sound,
 * which does the pushing and nothing else.
 *
 * Decoding comes from Service Provider Interface implementations on the
 * classpath: FFSampledSP, mp3spi, vorbisspi and jFLAC. They register
 * themselves with AudioSystem, so there is no per format branch anywhere
 * below - every file goes through the same two calls, and adding a format is
 * adding a dependency.
 *
 * FFSampledSP is the one that covers m4a, and with it aac, wma and the rest
 * of what FFmpeg reads; it carries its own FFmpeg build as a native library.
 * The pure Java three stay underneath it, so mp3 - which most of a library is
 * in - still plays if that native fails to load on some machine: a provider
 * that cannot load declines the file and the next one is asked. Either way
 * the installer stays one file with nothing for the user to fetch first.
 *
 * One thread per track. Commands are volatile fields it checks between
 * buffers rather than a queue, because the only commands are pause, seek and
 * stop, and a buffer is a few milliseconds.
 */
class AudioPlayer {

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    /**
     * The tone controls, sitting between the decoder and the speaker.
     *
     * Owned by the player rather than built beside it, because it carries
     * filter state that only means anything in the context of one continuous
     * stream of samples.
     */
    val equalizer = Equalizer()

    /** Called when a track reaches its end on its own, not when stopped. */
    var onEnded: (() -> Unit)? = null

    private var worker: Thread? = null
    private val pauseLock = Object()

    @Volatile private var stopRequested = false
    @Volatile private var paused = false
    @Volatile private var seekRequestMs = -1L
    @Volatile private var volume = 1.0f
    @Volatile private var trackGain = 1.0f

    /**
     * @param durationMs what the tags said, because a decoded stream usually
     *   cannot say. An MP3's frame count is not in its header, so asking the
     *   stream gives NOT_SPECIFIED and the progress bar would have no end.
     */
    fun play(file: File, durationMs: Long) {
        stop()
        stopRequested = false
        paused = false
        seekRequestMs = -1L
        _state.value = PlayerState(file = file, playing = true, durationMs = durationMs)
        worker = Thread({ run(file, durationMs) }, "rhythm-audio").apply {
            isDaemon = true
            start()
        }
    }

    fun pause() {
        paused = true
        _state.value = _state.value.copy(playing = false)
    }

    fun resume() {
        synchronized(pauseLock) {
            paused = false
            pauseLock.notifyAll()
        }
        _state.value = _state.value.copy(playing = true)
    }

    fun togglePause() = if (_state.value.playing) pause() else resume()

    fun seekTo(ms: Long) {
        seekRequestMs = ms.coerceAtLeast(0L)
        // A seek while paused has to wake the thread or nothing happens until
        // the user presses play, which looks like the seek was ignored.
        synchronized(pauseLock) { pauseLock.notifyAll() }
    }

    /** Linear 0..1, as a volume slider means it. */
    fun setVolume(value: Float) {
        volume = value.coerceIn(0f, 1f)
    }

    /**
     * A correction for this particular track's mastering, multiplied into the
     * volume rather than replacing it.
     *
     * Separate from [setVolume] because the two answer different questions -
     * how loud the listener wants it, and how loud this file happens to be -
     * and folding them together would mean the slider jumping at every change
     * of song. One at a time: set before the next track starts.
     */
    fun setTrackGain(value: Float) {
        trackGain = value.coerceIn(0.05f, 1f)
    }

    fun stop() {
        stopRequested = true
        synchronized(pauseLock) {
            paused = false
            pauseLock.notifyAll()
        }
        worker?.join(1_000)
        worker = null
        _state.value = _state.value.copy(playing = false)
    }

    private fun run(file: File, durationMs: Long) {
        var startMs = 0L
        var finished = false

        while (!stopRequested) {
            var line: SourceDataLine? = null
            var pcm: AudioInputStream? = null
            var reopening = false
            try {
                val opened = open(file, startMs)
                if (opened == null) {
                    _state.value = _state.value.copy(
                        playing = false,
                        error = "לא הצלחתי לנגן את ${file.name}"
                    )
                    return
                }
                line = opened.first
                pcm = opened.second
                val offset = startMs
                val buffer = ByteArray(16 * 1024)
                line.start()

                while (!stopRequested) {
                    val wanted = seekRequestMs
                    if (wanted >= 0) {
                        seekRequestMs = -1L
                        startMs = wanted
                        reopening = true
                        break
                    }
                    if (paused) {
                        line.stop()
                        synchronized(pauseLock) {
                            while (paused && !stopRequested && seekRequestMs < 0) {
                                pauseLock.wait()
                            }
                        }
                        if (stopRequested) break
                        line.start()
                        continue
                    }

                    val read = pcm.read(buffer, 0, buffer.size)
                    if (read <= 0) {
                        finished = true
                        break
                    }
                    equalizer.process(buffer, read)
                    applyVolume(line)
                    line.write(buffer, 0, read)
                    // The line's own count, not bytes handed to it: write()
                    // returns as soon as the buffer accepts the data, which is
                    // ahead of what anyone has heard. microsecondPosition is
                    // what actually came out of the speaker.
                    _state.value = _state.value.copy(
                        positionMs = offset + line.microsecondPosition / 1000L
                    )
                }

                if (finished) line.drain()
            } catch (_: InterruptedException) {
                return
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    playing = false,
                    error = e.message ?: "שגיאה בניגון"
                )
                return
            } finally {
                runCatching { line?.stop() }
                runCatching { line?.close() }
                runCatching { pcm?.close() }
            }

            if (!reopening) break
        }

        if (finished && !stopRequested) {
            _state.value = _state.value.copy(playing = false, positionMs = durationMs)
            onEnded?.invoke()
        }
    }

    /**
     * A file as 16 bit PCM, positioned [fromMs] in, with a line to play it on.
     *
     * The seek is a skip over the decoded stream rather than a real one. For
     * a constant bitrate file that is exact; for a VBR MP3 it lands close but
     * not on the millisecond, because working out where a given moment lives
     * in the file means either an index the file does not carry or decoding
     * everything before it. Close is what every player does here.
     */
    private fun open(file: File, fromMs: Long): Pair<SourceDataLine, AudioInputStream>? {
        val encoded = runCatching { AudioSystem.getAudioInputStream(file) }.getOrNull() ?: return null
        val base = encoded.format
        val rate = if (base.sampleRate > 0f) base.sampleRate else 44_100f
        val channels = if (base.channels > 0) base.channels else 2
        val target = AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            rate,
            16,
            channels,
            channels * 2,
            rate,
            false
        )

        val pcm = runCatching { AudioSystem.getAudioInputStream(target, encoded) }.getOrNull()
        if (pcm == null) {
            runCatching { encoded.close() }
            return null
        }

        if (fromMs > 0) {
            var remaining = (fromMs / 1000.0 * target.frameRate).toLong() * target.frameSize
            while (remaining > 0) {
                val skipped = pcm.skip(remaining)
                if (skipped <= 0) break
                remaining -= skipped
            }
        }

        val info = DataLine.Info(SourceDataLine::class.java, target)
        val line = runCatching { AudioSystem.getLine(info) as SourceDataLine }.getOrNull()
        if (line == null) {
            runCatching { pcm.close() }
            return null
        }
        runCatching { line.open(target, 64 * 1024) }.getOrElse {
            runCatching { pcm.close() }
            return null
        }
        // Per track, not once: the sample rate and the channel count are
        // properties of the file and both change the filter coefficients.
        equalizer.prepare(rate.toInt(), channels)
        return line to pcm
    }

    private fun applyVolume(line: SourceDataLine) {
        if (!line.isControlSupported(FloatControl.Type.MASTER_GAIN)) return
        val control = line.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl
        // The control is in decibels and a slider is not, so silence is the
        // control's own floor rather than log10(0).
        val level = volume * trackGain
        val db = if (level <= 0.0001f) {
            control.minimum
        } else {
            (20.0 * log10(level.toDouble())).toFloat().coerceIn(control.minimum, control.maximum)
        }
        if (control.value != db) control.value = db
    }
}
