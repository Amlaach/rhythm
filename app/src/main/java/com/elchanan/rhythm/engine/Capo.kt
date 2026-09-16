package com.elchanan.rhythm.engine

/**
 * Where to put the capo, and what to play once it is there.
 *
 * Everything here is arithmetic on the key, not analysis of the audio. That
 * distinction is the whole point: the chords a guitarist is shown get played,
 * so a guess that is sometimes wrong would be worse than nothing. A capo
 * position derived from the key is either right or the key was wrong, and the
 * key can be corrected by hand in one tap.
 *
 * It is also the one place the detector's known weakness does not matter. The
 * mistake it actually makes is confusing a key with its relative - C major for
 * A minor - and those two share a key signature, the same open shapes and the
 * same capo advice. The error cancels.
 */
object Capo {

    /** Chord symbols, the way a chord chart writes them. */
    val NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    /** Keys a guitar plays in open position, no barre needed. */
    private val OPEN_MAJOR = intArrayOf(0, 2, 4, 7, 9)   // C D E G A
    private val OPEN_MINOR = intArrayOf(2, 4, 9)         // Dm Em Am

    /**
     * @param fret 0 means no capo.
     * @param playKey the key whose shapes are fingered.
     * @param open whether those shapes are open chords.
     */
    data class Option(val fret: Int, val playKey: Int, val open: Boolean)

    fun keyName(pitchClass: Int, bright: Boolean): String =
        NAMES[((pitchClass % 12) + 12) % 12] + if (bright) "" else "m"

    /**
     * Every capo position up to [maxFret], easiest first.
     *
     * Past the seventh fret the neck runs out and the tone goes thin, so the
     * list stops there rather than offering positions nobody uses.
     */
    fun options(soundingKey: Int, bright: Boolean, maxFret: Int = 7): List<Option> {
        if (soundingKey !in 0..11) return emptyList()
        val open = if (bright) OPEN_MAJOR else OPEN_MINOR
        return (0..maxFret).map { fret ->
            val playKey = ((soundingKey - fret) % 12 + 12) % 12
            Option(fret, playKey, playKey in open)
        }
    }

    /** The open-chord positions only, which is what most people want to see. */
    fun bestOptions(soundingKey: Int, bright: Boolean): List<Option> =
        options(soundingKey, bright).filter { it.open }

    /**
     * The triads built on each degree of the scale.
     *
     * These are the chords that belong to the key, not the chords the song
     * plays - nothing here listened to the recording. For a guitarist working
     * a song out by ear that is still the useful half: it narrows seven
     * possibilities out of twelve.
     *
     * Returns an empty list for the quarter tone modes, where a stack of thirds
     * lands between the keys of a piano and a triad name would be a fiction.
     */
    fun scaleChords(tonic: Int, mode: MusicalMode): List<String> {
        if (tonic !in 0..11) return emptyList()
        if (mode in MusicalMode.NEUTRAL) return emptyList()
        val scale = mode.degrees
        if (scale.size < 7) return emptyList()
        return scale.indices.map { i ->
            val root = scale[i]
            val third = scale[(i + 2) % 7] + if (i + 2 >= 7) 12 else 0
            val fifth = scale[(i + 4) % 7] + if (i + 4 >= 7) 12 else 0
            val a = third - root
            val b = fifth - third
            val name = NAMES[(tonic + root) % 12]
            when {
                a == 4 && b == 3 -> name
                a == 3 && b == 4 -> name + "m"
                a == 3 && b == 3 -> name + "dim"
                a == 4 && b == 4 -> name + "aug"
                // Ahavah Rabbah and Mi Sheberach both contain an augmented
                // second, which throws a stack of thirds into intervals that
                // have no triad name. Saying so beats inventing one.
                else -> name + "?"
            }
        }
    }
}
