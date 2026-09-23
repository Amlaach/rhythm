package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.db.AudioFeatureEntity
import com.elchanan.rhythm.data.db.SongEntity

/**
 * Tells talking from music.
 *
 * A library like this holds shiurim, stories and recorded lectures alongside
 * the songs, and they want opposite treatment in almost every way: a shiur
 * should resume where it stopped, should not turn up in a mix, and should not
 * be shuffled. None of that can happen until something knows which is which.
 *
 * Length alone will not do it, which is the mistake worth avoiding. A concert
 * recording, a medley and a long niggun all run past half an hour, and filing
 * them as spoken word would be worse than not having the feature. What
 * separates speech is how it sounds, and there are two ways to hear that:
 *
 * The tagging model, where it ran. AudioSet has Speech, Narration and
 * Conversation as classes, and they are the closest thing to a direct answer
 * available anywhere in this app.
 *
 * And the measured features, for the light build and for anything analysed
 * before the model arrived. Speech has no steady beat, sits in a narrow band,
 * and pauses constantly - the gaps between phrases are what make its loudness
 * jump around far more than a song's.
 *
 * Both are combined with the length, which is real evidence even if it is not
 * enough on its own: an hour of anything is much more likely to be talking
 * than a three minute track is.
 */
object Spoken {

    /** Below this, length says nothing either way. */
    const val SHORT_MINUTES = 8

    /** Past this, length is strong evidence on its own. */
    const val LONG_MINUTES = 25

    /**
     * How sure the detector is that a track is speech, 0 to 1.
     *
     * @param tagScores the six classes of [AudioTags.SPEECH_INDICES], in that
     *   order, or null where the model never ran. Six rather than all 521,
     *   because this is called once per track over a whole library.
     */
    fun score(
        song: SongEntity,
        feature: AudioFeatureEntity?,
        tagScores: FloatArray?
    ): Double {
        val minutes = song.durationMs / 60000.0
        val lengthTerm = when {
            minutes < SHORT_MINUTES -> 0.0
            minutes >= LONG_MINUTES -> 1.0
            else -> (minutes - SHORT_MINUTES) / (LONG_MINUTES - SHORT_MINUTES)
        }

        // The model, when it has something to say.
        if (tagScores != null && tagScores.size >= AudioTags.SPEECH_INDICES.size) {
            val speech = maxOf(
                tagScores[AudioTags.SLOT_SPEECH].toDouble(),
                tagScores[AudioTags.SLOT_CHILD_SPEECH].toDouble(),
                tagScores[AudioTags.SLOT_CONVERSATION].toDouble(),
                tagScores[AudioTags.SLOT_NARRATION].toDouble()
            )
            val music = maxOf(
                tagScores[AudioTags.SLOT_MUSIC].toDouble(),
                tagScores[AudioTags.SLOT_SINGING].toDouble()
            )
            // The difference rather than the speech score alone. Singing is
            // speech-like enough to score on both, and a niggun that fires
            // 0.4 on Speech and 0.9 on Music is not a shiur.
            val heard = ((speech - music) / 0.6).coerceIn(-1.0, 1.0)
            val fromModel = (0.5 + 0.5 * heard).coerceIn(0.0, 1.0)
            // The model decides; the clock only leans. At 0.35 the clock was
            // most of the answer: an eight minute shiur needed the model to
            // be all but certain before it counted as talking, so the short
            // divrei Torah - the commonest kind - went on turning up in the
            // mixes. Now speech clearly ahead of music is enough at any
            // length, and a long track needs only a little less.
            return (MODEL_SHARE * fromModel + (1 - MODEL_SHARE) * lengthTerm).coerceIn(0.0, 1.0)
        }

        val measured = fromFeatures(feature)
        if (measured == null) {
            // Nothing but the clock. Deliberately weak: this alone should
            // never be enough to call something a podcast, only to suspect it.
            return 0.45 * lengthTerm
        }
        // Length counts for less here than it does alongside the model. With
        // no second opinion to contradict it, a high weight on the clock alone
        // was enough to carry a long niggun over the line: a 28 minute one
        // scored 0.65 against a 0.62 threshold on its length, despite having a
        // detectable tempo. The sound has to make the case.
        return (0.75 * measured + 0.25 * lengthTerm).coerceIn(0.0, 1.0)
    }

    /**
     * Speech as the signal measurements see it, or null when nothing was
     * measured.
     *
     * Three things, each of which a song usually fails:
     *
     * No pulse. Speech has rhythm but not a beat, so the tempo estimate comes
     * back with nothing behind it and the onsets are sparse and irregular.
     *
     * Narrow and dark. A voice alone occupies a fraction of the spectrum that
     * any arrangement fills, so the brightness sits low.
     *
     * Gappy. A song holds a level; a speaker stops between sentences, and
     * those silences push the variation in loudness well past what music
     * reaches.
     */
    private fun fromFeatures(f: AudioFeatureEntity?): Double? {
        if (f == null || f.energy <= 0f) return null
        val noPulse = if (f.bpm <= 0f) 1.0 else (1.0 - f.bpmConfidence).coerceIn(0.0, 1.0)
        val sparse = (1.0 - (f.onsetRate / 4.0)).coerceIn(0.0, 1.0)
        val dark = (1.0 - (f.brightness / 0.12)).coerceIn(0.0, 1.0)
        val gappy = ((f.dynamics - 0.5) / 0.8).coerceIn(0.0, 1.0)
        return (0.3 * noPulse + 0.3 * sparse + 0.2 * dark + 0.2 * gappy).coerceIn(0.0, 1.0)
    }

    /**
     * Whether to treat a track as spoken word.
     *
     * The threshold is high on purpose. A song wrongly filed as a shiur
     * disappears from the mixes and the shuffles it belongs in, and the user
     * has no reason to go looking for it there; a shiur wrongly left as music
     * is merely in the wrong shelf, where it is still findable. The cheaper
     * mistake is the one to make.
     */
    fun isSpoken(
        song: SongEntity,
        feature: AudioFeatureEntity?,
        tagScores: FloatArray?,
        stored: Int = -1
    ): Boolean {
        // The user's own answer always wins. They have listened to it.
        if (stored >= 0) return stored == 1
        // Short tracks only on the model's word. It was refused to them
        // outright, whatever the model heard, so everything under eight
        // minutes - most shiurim of the short kind - could never be told
        // from a song. The measured features alone are still not trusted that
        // far: without the model, short stays music.
        val heardByModel = tagScores != null && tagScores.size >= AudioTags.SPEECH_INDICES.size
        if (!heardByModel && song.durationMs < SHORT_MINUTES * 60_000L) return false
        return score(song, feature, tagScores) >= THRESHOLD
    }

    /**
     * Whether the model heard more talking than music in a track, whatever
     * [isSpoken] concluded.
     *
     * Not a verdict on what the track is - a shiur with a niggun in the
     * middle, a talk over a backing track, can land either side of the
     * threshold above, and that choice stays with [isSpoken] and the
     * listener. This answers a narrower question: may this track stand in a
     * list of music chosen for how it feels? A mood is a musical thing, and a
     * speaker who talks fast and loud reads as "energetic" to every measure
     * the moods use. There, speech ahead of music is enough to stay out.
     * False where the model never ran: nothing heard, nothing held back.
     */
    fun speechAhead(feature: AudioFeatureEntity?): Boolean {
        val tags = feature?.tags?.takeIf { it.isNotBlank() } ?: return false
        val scores = AudioTags.pick(tags, AudioTags.SPEECH_INDICES) ?: return false
        if (scores.size < AudioTags.SPEECH_INDICES.size) return false
        val speech = maxOf(
            scores[AudioTags.SLOT_SPEECH],
            scores[AudioTags.SLOT_CHILD_SPEECH],
            scores[AudioTags.SLOT_CONVERSATION],
            scores[AudioTags.SLOT_NARRATION]
        )
        val music = maxOf(scores[AudioTags.SLOT_MUSIC], scores[AudioTags.SLOT_SINGING])
        return speech - music > SPEECH_LEAD
    }

    /** How far ahead speech must be for [speechAhead]: clearly, not a close call. */
    const val SPEECH_LEAD = 0.1f

    const val THRESHOLD = 0.62

    /** How much of the verdict is the model's where it ran; the rest is the length. */
    private const val MODEL_SHARE = 0.85
}
