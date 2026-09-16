package com.elchanan.rhythm.playback

import android.media.audiofx.Equalizer
import com.elchanan.rhythm.data.Prefs

/**
 * The system equaliser, attached to whatever audio session the player is using.
 *
 * Deliberately thin. Android already ships a working multi-band equaliser and
 * the hardware often implements it below the mixer, so reimplementing the DSP
 * here would be slower and sound worse. The only job left is holding the user's
 * settings and re-attaching after the session changes, which happens whenever
 * playback restarts.
 *
 * Everything is wrapped: audio effects are the part of the platform most likely
 * to be missing or broken on a particular device, and a music player that
 * refuses to play because an equaliser failed to initialise is a bad trade.
 */
/**
 * How the settings screen reaches the live effect.
 *
 * The equaliser belongs to the playback service, which owns the audio session,
 * but the sliders live in the UI. Rather than open a second binder connection
 * for a handful of integers, the service publishes its controller here. Null
 * whenever nothing is playing, which the UI reports rather than hides - an
 * equaliser with no audio session genuinely has nothing to adjust.
 */
object EqBridge {
    @Volatile
    var controller: EqController? = null
}

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
            eq.enabled = prefs.eqEnabled
            if (!prefs.eqEnabled) return
            val preset = prefs.eqPreset
            if (preset >= 0 && preset < presetNames.size) {
                eq.usePreset(preset.toShort())
                return
            }
            val levels = prefs.eqBands
            for (band in 0 until eq.numberOfBands) {
                val wanted = levels.getOrNull(band) ?: 0
                eq.setBandLevel(
                    band.toShort(),
                    wanted.coerceIn(minLevel.toInt(), maxLevel.toInt()).toShort()
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
