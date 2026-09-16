package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.db.AudioFeatureEntity
import com.elchanan.rhythm.data.db.SongEntity

/**
 * Quick filters over the measured audio features.
 */
enum class Mood(val label: String, val subtitle: String) {
    CALM("רגוע", "איטי, פחות הקשה"),
    ENERGETIC("קצבי", "מהיר ועם דופק"),
    BRIGHT("שמח", "מז'ור ובהיר"),
    DEEP("מרגש", "מינור, דינמיקה רחבה"),
    FOCUS("ריכוז", "אחיד, בלי קפיצות"),
    WORKOUT("אימון", "הכי מהיר והכי חזק"),
    NIGHT("לילה", "שקט וכהה");

    companion object {
        fun filter(
            songs: List<SongEntity>,
            features: Map<Long, AudioFeatureEntity>,
            mood: Mood
        ): List<SongEntity> {
            val model = MoodModel(features.values)
            return songs.filter { model.matches(mood, features[it.id]) }
        }
    }
}

/**
 * Places each track on the two axes music-emotion research settles on - arousal
 * (how activated it is) and valence (how positive it sounds) - and reads the
 * moods off as regions of that plane.
 *
 * The important part is that both axes are measured *against the rest of the
 * library* rather than against fixed numbers. Absolute cutoffs - "energetic
 * means over 110 BPM" - assume a collection spanning lullabies to techno. Give
 * them a library that is one artist in one style, as most personal collections
 * are, and every track lands on the same side of every threshold: some moods
 * match everything and the rest match nothing, which is exactly the state the
 * old thresholds produced here. Ranking within the library instead guarantees
 * each mood describes a real, distinguishable part of the collection.
 */
class MoodModel(all: Collection<AudioFeatureEntity>) {

    private val usable = all.filter { it.energy > 0f }

    private val bpmScale = scaleOf(usable.mapNotNull { if (it.bpm > 0f) it.bpm.toDouble() else null })
    private val onsetScale = scaleOf(usable.map { it.onsetRate.toDouble() })
    private val energyScale = scaleOf(usable.map { it.energy.toDouble() })
    private val brightScale = scaleOf(usable.map { it.brightness.toDouble() })
    private val dynamicsScale = scaleOf(usable.map { it.dynamics.toDouble() })

    val ready: Boolean get() = usable.size >= 8

    /** How activated the track is, 0 (still) to 1 (driving). */
    fun arousal(f: AudioFeatureEntity): Double {
        val tempo = if (f.bpm > 0f) rank(bpmScale, f.bpm.toDouble()) else 0.5
        val pulse = rank(onsetScale, f.onsetRate.toDouble())
        val loud = rank(energyScale, f.energy.toDouble())
        return (0.4 * tempo + 0.35 * pulse + 0.25 * loud).coerceIn(0.0, 1.0)
    }

    /**
     * How positive it sounds, 0 (dark) to 1 (bright).
     *
     * Major or minor carries most of it - the single strongest cue available
     * without a trained model - with spectral brightness as the tiebreaker.
     */
    fun valence(f: AudioFeatureEntity): Double {
        val modeTerm = when (f.mode) {
            1 -> 1.0
            0 -> 0.0
            else -> 0.5
        }
        val bright = rank(brightScale, f.brightness.toDouble())
        return (0.6 * modeTerm + 0.4 * bright).coerceIn(0.0, 1.0)
    }

    fun matches(mood: Mood, f: AudioFeatureEntity?): Boolean {
        val feature = f ?: return false
        if (feature.energy <= 0f) return false
        val a = arousal(feature)
        val v = valence(feature)
        val bright = rank(brightScale, feature.brightness.toDouble())
        val steady = rank(dynamicsScale, feature.dynamics.toDouble())
        return when (mood) {
            Mood.CALM -> a <= 0.40
            Mood.ENERGETIC -> a >= 0.60
            Mood.WORKOUT -> a >= 0.80
            Mood.BRIGHT -> v >= 0.60 && a >= 0.40
            Mood.DEEP -> v <= 0.40 && a in 0.20..0.80
            Mood.FOCUS -> steady <= 0.40 && a in 0.25..0.75
            Mood.NIGHT -> a <= 0.45 && bright <= 0.35
        }
    }

    private companion object {
        /** Sorted copy used for rank lookups; empty when nothing was measured. */
        fun scaleOf(values: List<Double>): DoubleArray = values.sorted().toDoubleArray()

        /** Fraction of the library at or below [value], 0.5 when unknown. */
        fun rank(scale: DoubleArray, value: Double): Double {
            if (scale.isEmpty()) return 0.5
            var low = 0
            var high = scale.size
            while (low < high) {
                val mid = (low + high) / 2
                if (scale[mid] <= value) low = mid + 1 else high = mid
            }
            return low.toDouble() / scale.size
        }
    }
}
