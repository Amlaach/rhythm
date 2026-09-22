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

    /**
     * How far a track moves between its own probes, per song.
     *
     * The analyser measures eight points across a track and records how much
     * they disagreed; nothing here ever read it. So a piece that holds a
     * whisper for three minutes and then brings in a full choir was scored on
     * its averages, and its averages say "still" - which is how a track that
     * explodes came to be filed as רגוע.
     *
     * Spread and contrast rather than rise. Rise compares the end against the
     * beginning, so a track that erupts in the middle and settles again comes
     * out at nearly zero - and that track is exactly the case this is for.
     * Spread and contrast do not care where the loud part sits.
     *
     * Missing for anything analysed before the shape vector existed, and for
     * anything whose probes all failed. Those keep their old score rather than
     * being nudged on a vector of zeros.
     */
    private val swing: Map<Long, Double> = buildMap {
        for (f in usable) {
            val shape = Features.parseVector(f.shape, Analysis.SHAPE_DIMS)
            if (shape.all { it == 0.0 }) continue
            val moved = 0.5 * shape[Analysis.SHAPE_ENERGY_SPREAD] + 0.5 * shape[Analysis.SHAPE_CONTRAST]
            if (moved > 0.0) put(f.songId, moved)
        }
    }

    /**
     * Ranked across the library, like every other scale here.
     *
     * Spread and contrast are ratios against a track's own mean and have no
     * natural ceiling, so an absolute threshold on them would mean one thing
     * for a compressed studio record and another for a live recording. Where a
     * track sits against the rest of the library is the comparable question.
     */
    private val swingScale = scaleOf(swing.values.toList())

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
        val heard = cues[f.songId]
        val blended = if (heard == null) {
            measured
        } else {
            // Exciting and angry push up, tender and lullaby pull down.
            val up = maxOf(rankTag(heard, SLOT_EXCITING), rankTag(heard, SLOT_ANGRY))
            val down = maxOf(rankTag(heard, SLOT_TENDER), rankTag(heard, SLOT_LULLABY))
            val tag = (0.5 + 0.5 * (up - down)).coerceIn(0.0, 1.0)
            (1 - TAG_WEIGHT) * measured + TAG_WEIGHT * tag
        }
        return withSwing(f, blended)
    }

    /**
     * Raises a track that moves, and leaves a steady one alone.
     *
     * Upward only, and deliberately. A track that swings between a whisper and
     * a choir is not still, whatever its mean says, so the averages understate
     * it; but a track that holds one level is exactly what its averages claim,
     * and there is nothing to correct. A symmetric adjustment would invent a
     * second effect to justify the first.
     *
     * Proportional to the room left, so it can lift a track off the floor
     * without ever pushing one past the ceiling, and bounded by [SWING_WEIGHT]
     * so it stays a correction to the measurement rather than a replacement
     * for it.
     *
     * Arousal only. A rising brightness might mean a brighter mood or only a
     * louder chorus, and guessing which would be inventing a signal rather
     * than using one.
     */
    private fun withSwing(f: AudioFeatureEntity, arousal: Double): Double {
        val moved = swingRank(f) ?: return arousal
        return (arousal + SWING_WEIGHT * moved * (1.0 - arousal)).coerceIn(0.0, 1.0)
    }

    /**
     * Where this track sits against the library for how much it moves, or
     * null when it was analysed before the shape vector and has nothing to say.
     */
    private fun swingRank(f: AudioFeatureEntity): Double? {
        if (swingScale.isEmpty()) return null
        return rank(swingScale, swing[f.songId] ?: return null)
    }

    /**
     * Whether a track holds its level, which is what the quiet moods promise.
     *
     * Asking for רגוע means asking for something that stays that way. A track
     * whose mean is low because it whispers for three minutes before a choir
     * arrives satisfies "quiet on average" and breaks the promise entirely,
     * and the lift [withSwing] applies is a correction to a measurement - too
     * small, by design, to throw such a track out of a filter on its own.
     *
     * So the quiet moods ask this as well. Stated where the promise is made
     * rather than by inflating arousal, which is shared with every other mood
     * and should stay an honest measure of how activated a track is.
     */
    private fun holdsItsLevel(f: AudioFeatureEntity): Boolean =
        (swingRank(f) ?: 0.0) <= STEADY_ENOUGH

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
            // Quiet the whole way through, not quiet on average: a track that
            // erupts once is not what either of these words promises.
            Mood.CALM -> a <= 0.40 && holdsItsLevel(feature)
            Mood.ENERGETIC -> a >= 0.60
            // Loudness as well as pace: a fast piece played quietly is not
            // what anyone means by a workout track.
            Mood.WORKOUT -> a >= 0.75 && loud >= 0.55
            Mood.BRIGHT -> v >= 0.60 && a >= 0.40
            Mood.DEEP -> v <= 0.40 && a in 0.20..0.80
            Mood.FOCUS -> steady <= 0.40 && a in 0.25..0.75
            Mood.NIGHT -> a <= 0.45 && bright <= 0.35 && holdsItsLevel(feature)
        }
    }

    private fun rankTag(heard: FloatArray, slot: Int): Double =
        rank(tagScales[slot], heard[slot].toDouble())

    internal companion object {

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

        /**
         * How much a track's own movement may raise its arousal.
         *
         * Smaller than [TAG_WEIGHT] because it corrects a measurement rather
         * than adding evidence: the averages are right about what the track is
         * made of and wrong only about how still it is.
         */
        const val SWING_WEIGHT = 0.22

        /**
         * How much movement a quiet mood will still accept, as a place in the
         * library rather than an absolute: spread and contrast are ratios with
         * no natural ceiling, and what counts as restless in a library of
         * niggunim is not what counts as restless in one of rock records.
         */
        const val STEADY_ENOUGH = 0.70


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
