package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.db.AudioFeatureEntity
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.data.db.SongStatsEntity
import kotlin.math.exp
import kotlin.math.roundToInt

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
            mood: Mood,
            marks: Map<Long, Map<Mood, Boolean>> = emptyMap()
        ): List<SongEntity> {
            val model = MoodModel(features.values, marks)
            return songs.filter { model.matches(mood, features[it.id]) && !Spoken.speechAhead(features[it.id]) }
        }

        /**
         * The same songs, the ones that express the mood most first.
         *
         * Matching is a yes or no, and a list of yeses in whatever order they
         * were stored opens on whichever of them happens to sort first. Asking
         * for קצבי and being handed the quietest track that still cleared the
         * bar is a correct answer to the wrong question - the point of the
         * chip is the mood, so the strongest example of it leads.
         */
        fun strongest(
            songs: List<SongEntity>,
            features: Map<Long, AudioFeatureEntity>,
            mood: Mood,
            marks: Map<Long, Map<Mood, Boolean>> = emptyMap()
        ): List<SongEntity> {
            val model = MoodModel(features.values, marks)
            // Talking is not a mood: see Spoken.speechAhead.
            return songs.filter { model.matches(mood, features[it.id]) && !Spoken.speechAhead(features[it.id]) }
                .sortedByDescending { model.strength(mood, features[it.id]) }
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
class MoodModel(
    all: Collection<AudioFeatureEntity>,
    /** What the user said about songs' moods; see [MoodMarks]. */
    private val marks: Map<Long, Map<Mood, Boolean>> = emptyMap(),
    /**
     * The music prints folded as [AcousticSpace.musicPrints] holds them.
     * The engine hands its own over, because the rows it keeps no longer
     * carry the raw prints; null reads them from [all], the same way.
     */
    private val musicPrints: Map<Long, DoubleArray>? = null
) {

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

    /**
     * What MTG's heads said, per song, where the music model ran: arousal and
     * valence on 0..1, from the regression heads trained on people's ratings
     * and the mood classifiers on top of them.
     *
     * The rules below read a mood off tempo, key and brightness - proxies,
     * and on this music often wrong ones: a fast niggun that everyone hears as
     * calm, a minor key wedding song nobody hears as sad. These heads were
     * trained on the question itself, by people asked how a song felt, so
     * where they exist they carry most of the answer.
     */
    private val heard: Map<Long, Pair<Double?, Double?>> = buildMap {
        for (f in usable) {
            val m = MusicMoods.parse(f.musicMoods)
            if (m.isEmpty()) continue
            put(f.songId, musicArousal(m) to musicValence(m))
        }
    }

    /** Arousal and valence before the library is taken into account. */
    private val rawArousal: Map<Long, Double> =
        usable.associate { it.songId to blend(absoluteArousal(it), heard[it.songId]?.first) }
    private val rawValence: Map<Long, Double> =
        usable.associate { it.songId to blend(absoluteValence(it), heard[it.songId]?.second) }

    private fun blend(rules: Double, model: Double?): Double =
        if (model == null) rules else (1 - MUSIC_WEIGHT) * rules + MUSIC_WEIGHT * model

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
            // The tempo gets its say in proportion to how sure the detector
            // was, and the onset rate takes whatever it gives up.
            //
            // A tempo estimate is an inference and an onset rate is a
            // measurement, and this music is full of material the inference
            // has nothing to work with: a niggun in free time, a cantorial
            // piece, a rubato ballad. The detector still answers - it always
            // answers - and a sparse pulse aliases upward readily, so one beat
            // every two seconds can come back as a confident sounding hundred
            // and twenty. Weighted equally with a real measurement, that put
            // the calmest recordings in a library at the top of קצבי.
            //
            // So the confidence decides how much of the answer the tempo is
            // allowed to be. Certain, and this is what it always was; unsure,
            // and it falls back to counting onsets, which cannot be fooled the
            // same way because it is not guessing at a period.
            val trusted = TEMPO_SHARE * f.bpmConfidence.toDouble().coerceIn(0.0, 1.0)
            trusted * tempo + (1.0 - trusted) * pulse
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
        val raw = rawArousal[f.songId] ?: blend(absoluteArousal(f), heardOf(f)?.first)
        return 0.5 * raw + 0.5 * rank(arousalScale, raw)
    }

    fun valence(f: AudioFeatureEntity): Double {
        val raw = rawValence[f.songId] ?: blend(absoluteValence(f), heardOf(f)?.second)
        return 0.5 * raw + 0.5 * rank(valenceScale, raw)
    }

    /**
     * Whether a track is in a mood.
     *
     * What the user said about this very song first - nothing the audio
     * suggests overrides a person saying "this is not calm". Then, for a mood
     * the user has corrected enough songs in, the reading learned from those
     * corrections, where it proved more accurate than the rules. Otherwise
     * the rules.
     */
    fun matches(mood: Mood, f: AudioFeatureEntity?): Boolean {
        val feature = f ?: return false
        marks[feature.songId]?.get(mood)?.let { return it }
        if (feature.energy <= 0f) return false
        learner(mood)?.let { return it.matches(feature) }
        return ruleMatches(mood, feature)
    }

    private fun ruleMatches(mood: Mood, feature: AudioFeatureEntity): Boolean {
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

    /**
     * How strongly a track expresses a mood, rather than whether it clears it.
     *
     * The same quantities [matches] tests, read as a degree instead of a
     * threshold, so a list can lead with its best example. Only meaningful
     * among songs that already matched: a track far outside a mood scores
     * low here too, but nothing asks it to.
     */
    fun strength(mood: Mood, f: AudioFeatureEntity?): Double {
        val feature = f ?: return 0.0
        if (marks[feature.songId]?.get(mood) == true) return 1.0
        if (feature.energy <= 0f) return 0.0
        // Learned from "no" alone, it decides what leaves the list and not
        // the order of what stays.
        learner(mood)?.takeIf { !it.onlyRemoves }?.let { return it.probability(feature) }
        return ruleStrength(mood, feature)
    }

    private fun ruleStrength(mood: Mood, feature: AudioFeatureEntity): Double {
        val a = arousal(feature)
        val v = valence(feature)
        val bright = rank(brightScale, feature.brightness.toDouble())
        val steady = rank(dynamicsScale, feature.dynamics.toDouble())
        val loud = rank(energyScale, feature.energy.toDouble())
        return when (mood) {
            Mood.CALM -> 1.0 - a
            Mood.ENERGETIC -> a
            // Loudness counts here as it does in the test: a fast piece played
            // quietly is not what anyone means by a workout track.
            Mood.WORKOUT -> 0.6 * a + 0.4 * loud
            Mood.BRIGHT -> v
            Mood.DEEP -> 1.0 - v
            Mood.FOCUS -> 1.0 - steady
            Mood.NIGHT -> 0.5 * (1.0 - a) + 0.5 * (1.0 - bright)
        }
    }

    private fun heardOf(f: AudioFeatureEntity): Pair<Double?, Double?>? = heard[f.songId]
        ?: MusicMoods.parse(f.musicMoods).takeIf { it.isNotEmpty() }?.let { musicArousal(it) to musicValence(it) }

    private fun rankTag(heard: FloatArray, slot: Int): Double =
        rank(tagScales[slot], heard[slot].toDouble())

    // -----------------------------------------------------------------------
    // Learning the listener's ear
    // -----------------------------------------------------------------------

    private val byId: Map<Long, AudioFeatureEntity> = usable.associateBy { it.songId }

    /**
     * Per mood, the marked songs as examples: true for "is", false for "is
     * not" - and a song marked as an opposite mood counts as "is not", so
     * marking something קצבי also teaches רגוע.
     */
    private val examples: Map<Mood, List<Pair<Long, Boolean>>> = Mood.entries.associateWith { mood ->
        marks.mapNotNull { (id, said) ->
            if (id !in byId) return@mapNotNull null
            val direct = said[mood]
            when {
                direct != null -> id to direct
                OPPOSITES[mood].orEmpty().any { said[it] == true } -> id to false
                else -> null
            }
        }
    }

    /**
     * The library's average print, so a print can be centred without holding
     * every print in memory: a layer of ReLUs points everything the same way,
     * and only the departure from the average tells songs apart.
     */
    private val printMean: DoubleArray? by lazy {
        if (marks.isEmpty()) return@lazy null
        val mean = DoubleArray(SoundPrint.DIMS)
        var count = 0
        for (f in usable) {
            val p = SoundPrint.unpack(f.soundPrint) ?: continue
            for (i in 0 until SoundPrint.DIMS) mean[i] = mean[i] + p[i]
            count++
        }
        if (count < 8) return@lazy null
        for (i in 0 until SoundPrint.DIMS) mean[i] = mean[i] / count
        mean
    }

    private fun centredPrint(f: AudioFeatureEntity): FloatArray? {
        val mean = printMean ?: return null
        val p = SoundPrint.unpack(f.soundPrint) ?: return null
        var norm = 0.0
        val out = FloatArray(SoundPrint.DIMS)
        for (i in 0 until SoundPrint.DIMS) {
            val d = p[i] - mean[i]
            out[i] = d.toFloat()
            norm += d * d
        }
        val length = kotlin.math.sqrt(norm)
        if (length < 1e-9) return null
        for (i in 0 until SoundPrint.DIMS) out[i] = (out[i] / length).toFloat()
        return out
    }

    /** The prints of the marked songs only - the rest are read one at a time. */
    private val markedPrints: Map<Long, FloatArray> by lazy {
        buildMap {
            for (id in marks.keys) {
                val f = byId[id] ?: continue
                centredPrint(f)?.let { put(id, it) }
            }
        }
    }

    /**
     * The music model's prints, folded and centred like the engine's. Read
     * only once a mood has marks to learn from.
     */
    private val music: Map<Long, DoubleArray> by lazy {
        musicPrints ?: AcousticSpace.folded(usable) { MusicPrint.unpack(it.musicPrint) }
    }

    /**
     * Whether a mood learns only what to take out of its list: enough "no"
     * marks, not enough "yes".
     *
     * Most corrections are a "no" - a song taken out of a list it should not
     * have been in - and until there were three of each, a mood learned
     * nothing from any of them. Here the list itself stands in for the
     * missing "yes": the songs the rules put in the mood, against the songs
     * the user took out. What is learned can then only take songs out - a
     * "no" is never a reason to add one.
     */
    private fun removing(mood: Mood): Boolean {
        val list = examples[mood].orEmpty()
        return list.count { !it.second } >= MIN_MARKS && list.count { it.second } < MIN_MARKS
    }

    /** The mood's list as the rules have it, the songs marked for it left out. In id order. */
    private fun ruleList(mood: Mood): List<Long> {
        val marked = examples[mood].orEmpty().mapTo(HashSet()) { it.first }
        return usable.filter { it.songId !in marked && ruleMatches(mood, it) }
            .map { it.songId }
            .sorted()
    }

    /** What each mood is taught with: the marks, and the list where only "no" was said. */
    private val teaching: Map<Mood, List<Pair<Long, Boolean>>> by lazy {
        Mood.entries.associateWith { mood ->
            val list = examples[mood].orEmpty()
            if (removing(mood)) list + ruleList(mood).map { it to true } else list
        }
    }

    /**
     * Per mood, the summed prints of the songs taught as it and as not it,
     * and which songs went into each. A print's dot product with a sum, over
     * the count, is its average similarity to every song in the group - so
     * one pass per song, however many songs were marked.
     */
    private class Groups(val yes: DoubleArray, val yesIds: Set<Long>, val no: DoubleArray, val noIds: Set<Long>)

    private fun groupsOf(dims: Int, addInto: (Long, DoubleArray) -> Boolean): Map<Mood, Groups> =
        Mood.entries.mapNotNull { mood ->
            val yes = DoubleArray(dims)
            val no = DoubleArray(dims)
            val yesIds = HashSet<Long>()
            val noIds = HashSet<Long>()
            for ((id, label) in teaching[mood].orEmpty()) {
                if (label) {
                    if (addInto(id, yes)) yesIds.add(id)
                } else {
                    if (addInto(id, no)) noIds.add(id)
                }
            }
            if (yesIds.isEmpty() || noIds.isEmpty()) null else mood to Groups(yes, yesIds, no, noIds)
        }.toMap()

    private fun soundOf(id: Long): FloatArray? = markedPrints[id] ?: byId[id]?.let { centredPrint(it) }

    private val soundGroups: Map<Mood, Groups> by lazy {
        groupsOf(SoundPrint.DIMS) { id, into ->
            val p = soundOf(id) ?: return@groupsOf false
            for (k in 0 until SoundPrint.DIMS) into[k] = into[k] + p[k]
            true
        }
    }

    private val musicGroups: Map<Mood, Groups> by lazy {
        groupsOf(AcousticSpace.PRINT_DIMS) { id, into ->
            val p = music[id] ?: return@groupsOf false
            for (k in 0 until AcousticSpace.PRINT_DIMS) into[k] = into[k] + p[k]
            true
        }
    }

    /**
     * How much more a track sounds like the songs taught as the mood than
     * like the ones taught as not it, the track itself left out of both.
     * [dot] is the track's print against a group's sum. Zero when there is
     * nothing to go on.
     */
    private fun lean(g: Groups?, songId: Long, dot: (DoubleArray) -> Double): Double {
        if (g == null) return 0.0
        var dotYes = dot(g.yes)
        var dotNo = dot(g.no)
        var yesCount = g.yesIds.size
        var noCount = g.noIds.size
        // a song is always exactly like itself: take it out of its own group
        if (songId in g.yesIds) { dotYes -= 1.0; yesCount-- }
        if (songId in g.noIds) { dotNo -= 1.0; noCount-- }
        if (yesCount <= 0 || noCount <= 0) return 0.0
        return dotYes / yesCount - dotNo / noCount
    }

    /** By YAMNet's print: what the track sounds like. */
    private fun soundLean(mood: Mood, songId: Long, print: FloatArray?): Double {
        val p = print ?: return 0.0
        return lean(soundGroups[mood], songId) { sum ->
            var d = 0.0
            for (k in 0 until SoundPrint.DIMS) d += p[k] * sum[k]
            d
        }
    }

    /**
     * By the music model's print: what kind of music it is. Trained on
     * music, where YAMNet was trained on sounds of every kind, so it is the
     * better ear for "these songs are alike".
     */
    private fun musicLean(mood: Mood, songId: Long): Double {
        val p = music[songId] ?: return 0.0
        return lean(musicGroups[mood], songId) { sum ->
            var d = 0.0
            for (k in 0 until AcousticSpace.PRINT_DIMS) d += p[k] * sum[k]
            d
        }
    }

    /**
     * A mood's reading learned from the user's marks. [onlyRemoves]: learned
     * from "no" alone - see [removing] - so it only takes songs out of the
     * rules' list, and [w] is not used.
     */
    private inner class Learner(val mood: Mood, val w: DoubleArray, val onlyRemoves: Boolean) {
        fun probability(f: AudioFeatureEntity): Double = sigmoid(dot(w, inputs(mood, f)))

        fun matches(f: AudioFeatureEntity): Boolean =
            if (onlyRemoves) ruleMatches(mood, f) && removalLean(mood, f) >= 0.0
            else probability(f) >= 0.5
    }

    /**
     * For a mood learned from "no" alone: how much more a track sounds like
     * the rest of the list than like the songs taken out of it - below zero,
     * it goes the way they went. By both prints where there are both, the
     * track itself left out of its own group. Zero, and so kept, when there
     * is nothing to go on.
     */
    private fun removalLean(mood: Mood, f: AudioFeatureEntity): Double {
        val leans = ArrayList<Double>(2)
        if (musicGroups[mood] != null && music[f.songId] != null) leans.add(musicLean(mood, f.songId))
        val sound = soundOf(f.songId) ?: centredPrint(f)
        if (soundGroups[mood] != null && sound != null) leans.add(soundLean(mood, f.songId, sound))
        return if (leans.isEmpty()) 0.0 else leans.average()
    }

    private fun inputs(mood: Mood, f: AudioFeatureEntity): DoubleArray =
        doubleArrayOf(
            1.0,
            ruleStrength(mood, f) - 0.5,
            soundLean(mood, f.songId, soundOf(f.songId) ?: centredPrint(f)),
            musicLean(mood, f.songId)
        )

    /**
     * How well the rules and the learned reading each do on the songs the user
     * marked, every song judged by a reading that was not taught with it.
     * Where a mood learned from "no" alone ([onlyRemoves]), the learned
     * figure is how many of the songs taken out it finds by itself.
     */
    class MoodReport(
        val mood: Mood,
        val yes: Int,
        val no: Int,
        val ruleAccuracy: Double,
        val learnedAccuracy: Double?,
        val usingLearned: Boolean,
        val onlyRemoves: Boolean = false
    )

    private val learned = HashMap<Mood, Pair<Learner?, MoodReport?>>()

    @Synchronized
    private fun learnt(mood: Mood): Pair<Learner?, MoodReport?> = learned.getOrPut(mood) { train(mood) }

    private fun learner(mood: Mood): Learner? = if (marks.isEmpty()) null else learnt(mood).first

    fun report(): List<MoodReport> = Mood.entries.mapNotNull { learnt(it).second }

    private fun train(mood: Mood): Pair<Learner?, MoodReport?> {
        val list = examples[mood].orEmpty()
        val yes = list.count { it.second }
        val no = list.size - yes
        if (yes == 0 && no == 0) return null to null
        val ruleAccuracy = balanced(list.map { (id, label) -> ruleMatches(mood, byId.getValue(id)) to label })
        val removes = removing(mood)
        if (!removes && (yes < MIN_MARKS || no < MIN_MARKS)) {
            return null to MoodReport(mood, yes, no, ruleAccuracy, null, false)
        }
        if (removes) return trainRemoval(mood, yes, no, ruleAccuracy)
        val rows = list.map { (id, label) -> inputs(mood, byId.getValue(id)) to label }
        val held = rows.indices.map { i ->
            val w = fit(rows.filterIndexed { j, _ -> j != i })
            (sigmoid(dot(w, rows[i].first)) >= 0.5) to rows[i].second
        }
        val learnedAccuracy = balanced(held)
        val use = learnedAccuracy >= ruleAccuracy
        val learner = if (use) Learner(mood, fit(rows), false) else null
        return learner to MoodReport(mood, yes, no, ruleAccuracy, learnedAccuracy, use)
    }

    /**
     * Learning from "no" alone. It earns its place when the marks agree with
     * each other: each song taken out, left out of the teaching in turn, is
     * found by the others - at least [REMOVALS_FOUND] of them. A few wrong
     * songs that have nothing in common teach nothing, and are only taken
     * out themselves. And it must leave at least [KEEP_OF_LIST] of the list:
     * a correction is not a reason to empty a mood.
     */
    private fun trainRemoval(mood: Mood, yes: Int, no: Int, ruleAccuracy: Double): Pair<Learner?, MoodReport?> {
        val list = ruleList(mood)
        val taken = examples[mood].orEmpty().filter { !it.second }.map { byId.getValue(it.first) }
        if (list.isEmpty()) return null to MoodReport(mood, yes, no, ruleAccuracy, null, false, true)
        val found = taken.count { removalLean(mood, it) < 0.0 }.toDouble() / taken.size
        val kept = list.count { removalLean(mood, byId.getValue(it)) >= 0.0 }.toDouble() / list.size
        val use = found >= REMOVALS_FOUND && kept >= KEEP_OF_LIST
        val learner = if (use) Learner(mood, DoubleArray(0), true) else null
        return learner to MoodReport(mood, yes, no, ruleAccuracy, found, use, true)
    }

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
        /**
         * The most of the arousal estimate a tempo may account for, before
         * its confidence is taken into account.
         */
        const val TEMPO_SHARE = 0.65

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


        /** How much of each axis the music model's heads decide, where they ran. */
        const val MUSIC_WEIGHT = 0.7

        /** The regression heads' 1..9 rating scale onto 0..1. */
        private fun nine(v: Float): Double = ((v - 1.0) / 8.0).coerceIn(0.0, 1.0)

        private fun ratings(m: Map<String, Float>, axis: String): Double? =
            m.filterKeys { it.endsWith(".$axis") }.values.map(::nine).takeIf { it.isNotEmpty() }?.average()

        /**
         * Arousal from the heads: the rated arousal where there is one, with
         * the aggressive and relaxed classifiers pulling either way.
         */
        fun musicArousal(m: Map<String, Float>): Double? {
            val rated = ratings(m, "arousal")
            val pull = listOfNotNull(
                m["aggressive"]?.toDouble(),
                m["relaxed"]?.let { 1.0 - it },
                m["party"]?.toDouble()
            ).takeIf { it.isNotEmpty() }?.average()
            return when {
                rated != null && pull != null -> 0.6 * rated + 0.4 * pull
                else -> rated ?: pull
            }
        }

        /** Valence likewise: rated valence, with happy and sad pulling. */
        fun musicValence(m: Map<String, Float>): Double? {
            val rated = ratings(m, "valence")
            val pull = listOfNotNull(
                m["happy"]?.toDouble(),
                m["sad"]?.let { 1.0 - it }
            ).takeIf { it.isNotEmpty() }?.average()
            return when {
                rated != null && pull != null -> 0.6 * rated + 0.4 * pull
                else -> rated ?: pull
            }
        }

        /**
         * Marks a mood needs before it is learned rather than ruled: this
         * many each way, or this many "no" to learn what to take out.
         */
        const val MIN_MARKS = 3

        /** How many of the songs taken out a mood learned from "no" alone must find on its own. */
        const val REMOVALS_FOUND = 2.0 / 3.0

        /** How much of its list a mood learned from "no" alone must keep. */
        const val KEEP_OF_LIST = 0.5

        /** Moods that a song cannot be at once, so marking one teaches the other. */
        val OPPOSITES: Map<Mood, Set<Mood>> = mapOf(
            Mood.CALM to setOf(Mood.ENERGETIC, Mood.WORKOUT),
            Mood.NIGHT to setOf(Mood.ENERGETIC, Mood.WORKOUT),
            Mood.ENERGETIC to setOf(Mood.CALM, Mood.NIGHT),
            Mood.WORKOUT to setOf(Mood.CALM, Mood.NIGHT),
            Mood.BRIGHT to setOf(Mood.DEEP),
            Mood.DEEP to setOf(Mood.BRIGHT)
        )

        /** The mean of the two recalls, so a mood marked mostly one way cannot score by always answering that way. */
        fun balanced(pairs: List<Pair<Boolean, Boolean>>): Double {
            val pos = pairs.filter { it.second }
            val neg = pairs.filter { !it.second }
            val parts = listOfNotNull(
                pos.takeIf { it.isNotEmpty() }?.let { l -> l.count { it.first }.toDouble() / l.size },
                neg.takeIf { it.isNotEmpty() }?.let { l -> l.count { !it.first }.toDouble() / l.size }
            )
            return if (parts.isEmpty()) 0.0 else parts.average()
        }

        /**
         * Logistic regression over [bias, rule strength, sound lean, music
         * lean], the classes balanced and the weights held near a start that
         * trusts the rules: with a handful of marks, the rules are the prior
         * and the marks move them, rather than a few examples replacing
         * everything.
         */
        fun fit(rows: List<Pair<DoubleArray, Boolean>>): DoubleArray {
            val start = doubleArrayOf(0.0, 8.0, 0.0, 0.0)
            val w = start.copyOf()
            val pos = rows.count { it.second }.coerceAtLeast(1)
            val neg = (rows.size - rows.count { it.second }).coerceAtLeast(1)
            val n = rows.size.coerceAtLeast(1).toDouble()
            repeat(400) {
                val g = DoubleArray(start.size)
                for ((x, label) in rows) {
                    val err = (sigmoid(dot(w, x)) - if (label) 1.0 else 0.0) *
                        (if (label) n / (2.0 * pos) else n / (2.0 * neg))
                    for (i in start.indices) g[i] += err * x[i]
                }
                for (i in start.indices) {
                    val pull = if (i == 0) 0.0 else 0.05 * (w[i] - start[i])
                    w[i] -= 0.8 * (g[i] / n + pull)
                }
            }
            return w
        }

        fun dot(w: DoubleArray, x: DoubleArray): Double {
            var s = 0.0
            for (i in w.indices) s += w[i] * x[i]
            return s
        }

        fun sigmoid(z: Double) = 1.0 / (1.0 + exp(-z.coerceIn(-30.0, 30.0)))

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

/**
 * What the user said about songs' moods, as stored on the song's stats:
 * "CALM,-ENERGETIC" - a mood by name for "it is", a minus for "it is not".
 */
object MoodMarks {
    fun parse(raw: String): Map<Mood, Boolean> {
        if (raw.isBlank()) return emptyMap()
        val out = LinkedHashMap<Mood, Boolean>()
        for (part in raw.split(',')) {
            val t = part.trim()
            if (t.isEmpty()) continue
            val no = t.startsWith("-")
            val mood = Mood.entries.firstOrNull { it.name == t.removePrefix("-") } ?: continue
            out[mood] = !no
        }
        return out
    }

    fun encode(marks: Map<Mood, Boolean>): String =
        marks.entries.joinToString(",") { (mood, yes) -> if (yes) mood.name else "-${mood.name}" }

    /** One song's marks with one mood set to yes, no, or (null) left to the audio. */
    fun with(raw: String, mood: Mood, value: Boolean?): String {
        val m = LinkedHashMap(parse(raw))
        if (value == null) m.remove(mood) else m[mood] = value
        return encode(m)
    }

    /** The mood part of the report card, in words. */
    fun describe(reports: List<MoodModel.MoodReport>): String {
        if (reports.isEmpty()) {
            return "מצבי רוח: עוד לא תיקנת אף שיר. אפשר מתפריט השיר ← \"מצב רוח\", " +
                "או מתוך רשימת מצב רוח ← \"לא מתאים\". מ-${MoodModel.MIN_MARKS} סימוני \"לא\" " +
                "האפליקציה לומדת להוציא ממצב הרוח שירים שנשמעים כמותם, ועם " +
                "${MoodModel.MIN_MARKS} \"כן\" היא לומדת לזהות אותו לפי האוזן שלך."
        }
        fun pct(v: Double) = "${(v * 100).roundToInt()}%"
        return buildString {
            append("מצבי רוח — עד כמה הזיהוי צודק בשירים שתיקנת:\n")
            for (r in reports) {
                append("• ${r.mood.label}: ${r.yes} כן · ${r.no} לא — ")
                append("הזיהוי האוטומטי ${pct(r.ruleAccuracy)}")
                when {
                    // "No" alone is enough to start: what is missing is only ever "no".
                    r.learnedAccuracy == null -> (MoodModel.MIN_MARKS - r.no).takeIf { it > 0 }?.let {
                        append(" · כדי ללמוד צריך עוד $it \"לא\"")
                    }
                    r.usingLearned && r.onlyRemoves ->
                        append(", אחרי לימוד ${pct(r.learnedAccuracy)} (פעיל — מוציא שירים שנשמעים כמו אלה שסימנת \"לא\")")
                    r.usingLearned -> append(", אחרי לימוד ${pct(r.learnedAccuracy)} (פעיל)")
                    else -> append(", אחרי לימוד ${pct(r.learnedAccuracy)} (לא טוב יותר, לא הופעל)")
                }
                append("\n")
            }
            append("הסימונים שלך תמיד גוברים על הזיהוי.")
        }
    }

    fun of(stats: Map<Long, SongStatsEntity>): Map<Long, Map<Mood, Boolean>> = buildMap {
        for ((id, st) in stats) {
            if (st.moods.isBlank()) continue
            val m = parse(st.moods)
            if (m.isNotEmpty()) put(id, m)
        }
    }
}
