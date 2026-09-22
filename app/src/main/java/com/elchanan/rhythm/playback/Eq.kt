package com.elchanan.rhythm.playback

import android.media.audiofx.Equalizer
import com.elchanan.rhythm.data.Prefs
import com.elchanan.rhythm.engine.EqBands
import com.elchanan.rhythm.engine.EqSettings

/**
 * How the settings screen reaches the live equalisers.
 *
 * They belong to the playback service, which owns the audio path, but the
 * sliders live in the UI. Rather than open a second binder connection for a
 * handful of integers, the service publishes its controllers here.
 */
object EqBridge {

    /**
     * The device's own effect. Null whenever nothing is playing, which the UI
     * reports rather than hides - an effect with no audio session genuinely
     * has nothing to adjust.
     */
    @Volatile
    var controller: EqController? = null

    /**
     * The app's own thirty one band equaliser.
     *
     * Unlike the system one this exists from the moment the service starts,
     * because it is part of the player's audio path rather than an effect
     * bolted onto a session id. Its sliders work with nothing playing, and
     * take effect the instant something does.
     */
    @Volatile
    var graphic: GraphicEqController? = null
}

/**
 * Holds the thirty one band settings and keeps the live processor in step.
 *
 * Thin by design. The arithmetic is in [EqFilters], the buffer handling is in
 * [GraphicEqProcessor], and what is left here is remembering what the user
 * chose - the part that has to survive the service being killed.
 */
class GraphicEqController(
    private val prefs: Prefs,
    private val processor: GraphicEqProcessor
) {

    /** The current settings, as the audio path sees them. */
    @Volatile
    var settings: EqSettings = EqSettings.of(
        enabled = prefs.graphicEqEnabled,
        bands = prefs.graphicEqBands,
        preampMb = prefs.graphicEqPreamp
    )
        private set

    init {
        processor.setSettings(settings)
    }

    private fun push(next: EqSettings) {
        settings = next
        processor.setSettings(next)
    }

    /**
     * Moves one slider.
     *
     * The processor is told at once so the sound follows the finger, but
     * nothing is written to disk: a drag produces a hundred of these and none
     * of them are worth a commit. [commit] is for the end of the gesture.
     */
    fun setBand(index: Int, millibels: Int) {
        push(settings.withBand(index, millibels))
    }

    fun setPreamp(millibels: Int) {
        push(
            settings.copy(
                preampMb = millibels.coerceIn(EqBands.PREAMP_MIN_MB, EqBands.PREAMP_MAX_MB)
            )
        )
    }

    fun setEnabled(enabled: Boolean) {
        push(settings.copy(enabled = enabled))
        prefs.graphicEqEnabled = enabled
    }

    fun setBands(bands: List<Int>) {
        push(EqSettings.of(settings.enabled, bands, settings.preampMb))
        commit()
    }

    fun reset() {
        push(settings.copy(bands = List(EqBands.COUNT) { 0 }, preampMb = 0))
        commit()
    }

    /** Writes the current gains out, for the end of a drag. */
    fun commit() {
        prefs.graphicEqBands = settings.bands
        prefs.graphicEqPreamp = settings.preampMb
    }
}

/**
 * The device's own equaliser, attached to whatever audio session the player is
 * using.
 *
 * Kept alongside the app's own one rather than replaced by it. On some phones
 * this is implemented in hardware below the mixer, where it costs nothing and
 * is tied into whatever else the manufacturer ships; what it is not is
 * detailed, since almost every device reports five bands. Only one of the two
 * runs at a time.
 *
 * Everything is wrapped: audio effects are the part of the platform most
 * likely to be missing or broken on a particular device, and a music player
 * that refuses to play because an equaliser failed to initialise is a bad
 * trade.
 */
class EqController(private val prefs: Prefs) {

    private var equalizer: Equalizer? = null
    private var sessionId: Int = 0

    /** Band centre frequencies in Hz, empty until an audio session exists. */
    var bandFrequencies: List<Int> = emptyList()
        private set

    /** Level range in millibels, as reported by the device. */
    var minLevel: Short = -1500
        private set
    var maxLevel: Short = 1500
        private set

    val bandCount: Int get() = bandFrequencies.size

    /** Named presets the device itself provides, "ידני" standing for none. */
    var presetNames: List<String> = emptyList()
        private set

    fun attach(audioSessionId: Int) {
        if (audioSessionId == 0) return
        if (sessionId == audioSessionId && equalizer != null) {
            apply()
            return
        }
        release()
        sessionId = audioSessionId
        runCatching {
            val eq = Equalizer(0, audioSessionId)
            val bands = eq.numberOfBands.toInt()
            bandFrequencies = (0 until bands).map { eq.getCenterFreq(it.toShort()) / 1000 }
            val range = eq.bandLevelRange
            minLevel = range[0]
            maxLevel = range[1]
            presetNames = (0 until eq.numberOfPresets.toInt()).map { eq.getPresetName(it.toShort()) }
            equalizer = eq
        }
        apply()
    }

    /** Pushes the stored settings onto the effect. */
    fun apply() {
        val eq = equalizer ?: return
        runCatching {
            // Switched off outright while the app's own equaliser is the one
            // in charge, so the two never filter the same signal in series.
            val wanted = prefs.eqEnabled && !prefs.eqUseGraphic
            eq.enabled = wanted
            if (!wanted) return
            val preset = prefs.eqPreset
            if (preset >= 0 && preset < presetNames.size) {
                eq.usePreset(preset.toShort())
                return
            }
            val levels = prefs.eqBands
            for (band in 0 until eq.numberOfBands) {
                val level = levels.getOrNull(band) ?: 0
                eq.setBandLevel(
                    band.toShort(),
                    level.coerceIn(minLevel.toInt(), maxLevel.toInt()).toShort()
                )
            }
        }
    }

    /** Current level of each band in millibels, from the stored settings. */
    fun levels(): List<Int> = (0 until bandCount).map { prefs.eqBands.getOrNull(it) ?: 0 }

    fun setBand(index: Int, millibels: Int) {
        val current = prefs.eqBands.toMutableList()
        while (current.size < bandCount) current.add(0)
        if (index !in current.indices) return
        current[index] = millibels
        prefs.eqBands = current
        // Moving a slider means the user has left the preset behind.
        prefs.eqPreset = -1
        apply()
    }

    fun usePreset(index: Int) {
        prefs.eqPreset = index
        if (index >= 0) {
            runCatching {
                val eq = equalizer ?: return@runCatching
                eq.usePreset(index.toShort())
                prefs.eqBands = (0 until eq.numberOfBands).map {
                    eq.getBandLevel(it.toShort()).toInt()
                }
            }
        }
        apply()
    }

    fun setEnabled(enabled: Boolean) {
        prefs.eqEnabled = enabled
        apply()
    }

    fun release() {
        runCatching { equalizer?.release() }
        equalizer = null
        sessionId = 0
    }
}
