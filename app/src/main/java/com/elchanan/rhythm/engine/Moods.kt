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
 * Each axis is measured twice and the two are averaged.
 *
 * Once against fixed musical anchors: 60 BPM is slow and 150 is fast wherever
 * they are found, and nothing about the rest of the collection changes that.
 * Once against the rest of the library, by rank. Neither works alone. Fixed
 * cutoffs on their own assume a collection spanning lullabies to techno, and
 * on a library that is one artist in one style every track lands on the same
 * side of every threshold, so some moods match everything and the rest match
 * nothing. Ranks on their own have the opposite fault, and it is the one that
 * was actually being complained about: a rank always spreads across the whole
 * range, so "the calmest two fifths" is two fifths of the library whether or
 * not a single track in it is calm. On a collection of fast music, every
 * second song was being offered as רגוע.
 *
 * Averaging the two keeps both properties. On a varied library the rank does
 * the work and the filters describe real, comparable parts of it. On a library
 * that leans one way the anchors hold, and a mood that genuinely is not there
 * returns a few tracks instead of a confident two fifths of the wrong ones.
 *
 * Where the tagging model ran, what it heard is folded in as well. AudioSet
 * has classes for happy, sad, tender and exciting music, labelled by people
 * asked what a clip sounded like - the only part of any of this trained on the
 * question actually being asked.
 */
class MoodModel(all: Collection<AudioFeatureEntity>) {

    private val usable = all.filter { it.energy > 0f }

    private val brightScale = scaleOf(usable.map { it.brightness.toDouble() })
    private val dynamicsScale = scaleOf(usable.map { it.dynamics.toDouble() })
    private val energyScale = scaleOf(usable.map { it.energy.toDouble() })

    /**
     * What the tagging model heard, per song, or null where it never ran.
     *
     * Read once here rather than on every comparison: filtering a library
     * calls [matches] once per track, and parsing the stored scores inside
     * that would reparse the same strings for every mood chip pressed.
     */
    private val cues: Map<Long, FloatArray> = buildMap {
        for (f in usable) {
            val picked = AudioTags.pick(f.tags, AudioTags.MOOD_INDICES) ?: continue
            put(f.songId, picked)
        }
    }

    /**
     * The tag scores ranked across the library.
     *
     * The model's raw outputs are small and their useful range differs by
     * class - "Exciting music" fires more readily than "Tender music" - so an
     * absolute threshold on them would mean something different for each. What
     * is comparable is where a track sits against the others.
     */
    private val tagScales: List<DoubleArray> = AudioTags.MOOD_INDICES.indices.map { slot ->
        scaleOf(cues.values.map { it[slot].toDouble() })
    }

    /** Arousal and valence before the library is taken into account. */
    private val rawArousal: Map<Long, Double> =
        usable.associate { it.songId to absoluteArousal(it) }
    private val rawValence: Map<Long, Double> =
        usable.associate { it.songId to absoluteValence(it) }

    private val arousalScale = scaleOf(rawArousal.values.toList())
    private val valenceScale = scaleOf(rawValence.values.toList())

    val ready: Boolean get() = usable.size >= 8

    /**
     * How activated the track is, 0 (still) to 1 (driving), against fixed
     * anchors rather than against the library.
     */
    private fun absoluteArousal(f: AudioFeatureEntity): Double {
        val pulse = between(f.onsetRate.toDouble(), CALM_ONSETS, BUSY_ONSETS)
        val measured = if (f.bpm > 0f) {
            val tempo = between(f.bpm.toDouble(), SLOW_BPM, FAST_BPM)
            0.65 * tempo + 0.35 * pulse
        } else {
            // No tempo estimate, so the onset rate carries it alone rather
            // than a missing value being scored as average.
            pulse
        }
        val heard = cues[f.songId] ?: return measured
        // Exciting and angry push up, tender and lullaby pull down.
        val up = maxOf(rankTag(heard, SLOT_EXCITING), rankTag(heard, SLOT_ANGRY))
        val down = maxOf(rankTag(heard, SLOT_TENDER), rankTag(heard, SLOT_LULLABY))
        val tag = (0.5 + 0.5 * (up - down)).coerceIn(0.0, 1.0)
        return (1 - TAG_WEIGHT) * measured + TAG_WEIGHT * tag
    }

    /**
     * How positive it sounds, 0 (dark) to 1 (bright).
     *
     * Major or minor carries most of it - the strongest cue available from the
     * signal alone - with spectral brightness as the tiebreaker, and what the
     * model heard on top of both.
     */
    private fun absoluteValence(f: AudioFeatureEntity): Double {
        val modeTerm = when (f.mode) {
            1 -> 1.0
            0 -> 0.0
            else -> 0.5
        }
        val bright = rank(brightScale, f.brightness.toDouble())
        val measured = (0.6 * modeTerm + 0.4 * bright).coerceIn(0.0, 1.0)
        val heard = cues[f.songId] ?: return measured
        val tag = (0.5 + 0.5 * (rankTag(heard, SLOT_HAPPY) - rankTag(heard, SLOT_SAD)))
            .coerceIn(0.0, 1.0)
        return (1 - TAG_WEIGHT) * measured + TAG_WEIGHT * tag
    }

    /** Half what the anchors say, half where the library puts it. */
    fun arousal(f: AudioFeatureEntity): Double {
        val raw = rawArousal[f.songId] ?: absoluteArousal(f)
        return 0.5 * raw + 0.5 * rank(arousalScale, raw)
    }

    fun valence(f: AudioFeatureEntity): Double {
        val raw = rawValence[f.songId] ?: absoluteValence(f)
        return 0.5 * raw + 0.5 * rank(valenceScale, raw)
    }

    fun matches(mood: Mood, f: AudioFeatureEntity?): Boolean {
        val feature = f ?: return false
        if (feature.energy <= 0f) return false
        val a = arousal(feature)
        val v = valence(feature)
        val bright = rank(brightScale, feature.brightness.toDouble())
        val steady = rank(dynamicsScale, feature.dynamics.toDouble())
        val loud = rank(energyScale, feature.energy.toDouble())
        return when (mood) {
            Mood.CALM -> a <= 0.40
            Mood.ENERGETIC -> a >= 0.60
            // Loudness as well as pace: a fast piece played quietly is not
            // what anyone means by a workout track.
            Mood.WORKOUT -> a >= 0.75 && loud >= 0.55
            Mood.BRIGHT -> v >= 0.60 && a >= 0.40
            Mood.DEEP -> v <= 0.40 && a in 0.20..0.80
            Mood.FOCUS -> steady <= 0.40 && a in 0.25..0.75
            Mood.NIGHT -> a <= 0.45 && bright <= 0.35
        }
    }

    private fun rankTag(heard: FloatArray, slot: Int): Double =
        rank(tagScales[slot], heard[slot].toDouble())

    private companion object {

        /**
         * The anchors, in the units the analyser reports.
         *
         * The tempo pair is the part that can be stated plainly: 60 BPM is a
         * ballad and 150 is dance music, in any collection. The onset pair -
         * prominent onsets per second - is an estimate of what this analyser
         * produces for sparse and for busy material, and is weighted lower
         * because of it. Both are only half the answer anyway; the library's
         * own spread is the other half.
         */
        const val SLOW_BPM = 62.0
        const val FAST_BPM = 150.0
        const val CALM_ONSETS = 1.2
        const val BUSY_ONSETS = 5.5

        /**
         * How much of each axis the tagging model gets to move.
         *
         * Modest on purpose. AudioSet is built from YouTube, where the music
         * this library is full of is thinly represented, so the mood classes
         * are informed rather than authoritative here.
         */
        const val TAG_WEIGHT = 0.3

        // Positions within AudioTags.MOOD_INDICES.
        const val SLOT_LULLABY = 0
        const val SLOT_HAPPY = 1
        const val SLOT_SAD = 2
        const val SLOT_TENDER = 3
        const val SLOT_EXCITING = 4
        const val SLOT_ANGRY = 5

        /** Where [value] sits between [low] and [high], clamped to 0..1. */
        fun between(value: Double, low: Double, high: Double): Double =
            ((value - low) / (high - low)).coerceIn(0.0, 1.0)

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
