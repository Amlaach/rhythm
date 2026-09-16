package com.elchanan.rhythm.engine

/**
 * The modal vocabulary this library actually uses.
 *
 * Major and minor describe Western pop and almost nothing else. Ashkenazi
 * liturgical music is organised around the shtaygerim - Ahavah Rabbah, Magen
 * Avot, Adonai Malach, Mi Sheberach - and Mizrahi music around the maqamat,
 * whose ajnas overlap with them: Hijaz is the same tetrachord that gives Ahavah
 * Rabbah its character, and Nahawand is the minor family. Testing only the two
 * Western profiles throws that distinction away and files a Hasidic niggun and
 * a pop ballad as the same thing.
 *
 * Two honest limits. Rast and Bayati turn on quarter tones, and a twelve-bin
 * chroma cannot represent them - Rast is approximated by its nearest twelve
 * tone neighbour and will sometimes read as major. And a mode is melodic
 * behaviour, not just a set of notes: this identifies the scale a piece draws
 * on, which is a strong hint about style, not a full modal analysis.
 */
enum class MusicalMode(
    val label: String,
    /** Semitones above the tonic that belong to the scale. */
    private val degrees: IntArray,
    /** Degrees that carry the mode's identity and deserve extra weight. */
    private val signature: IntArray,
    /** True for the modes that read as "major" to the rest of the app. */
    val brightFamily: Boolean
) {
    MAJOR("מז'ור", intArrayOf(0, 2, 4, 5, 7, 9, 11), intArrayOf(4, 11), true),
    MINOR("מינור", intArrayOf(0, 2, 3, 5, 7, 8, 10), intArrayOf(3, 8), false),

    /** Freygish / Hijaz. The augmented second between the 2nd and 3rd degrees. */
    AHAVAH_RABBAH("אהבה רבה", intArrayOf(0, 1, 4, 5, 7, 8, 10), intArrayOf(1, 4), false),

    /** Ukrainian Dorian: minor third with a raised fourth. */
    MI_SHEBERACH("מי שברך", intArrayOf(0, 2, 3, 6, 7, 9, 10), intArrayOf(3, 6), false),

    /** Mixolydian: major third, flattened seventh. */
    ADONAI_MALACH("אדוני מלך", intArrayOf(0, 2, 4, 5, 7, 9, 10), intArrayOf(4, 10), true),

    /** Harmonic minor, the Nahawand colouring common in Mizrahi writing. */
    NAHAWAND("נהוונד", intArrayOf(0, 2, 3, 5, 7, 8, 11), intArrayOf(3, 11), false),

    /** Phrygian. Flat second with a minor third, distinct from Ahavah Rabbah. */
    KURD("כורד", intArrayOf(0, 1, 3, 5, 7, 8, 10), intArrayOf(1, 3), false),

    /**
     * Rast. Neutral third and seventh - the notes that sit between the keys of
     * a piano - so it only exists in the quarter tone profiles below.
     */
    RAST("ראסט", intArrayOf(0, 2, 4, 5, 7, 9, 11), intArrayOf(4, 11), true),

    /** Bayati. Neutral second over a minor tetrachord. Quarter tone only. */
    BAYATI("בּיאתי", intArrayOf(0, 1, 3, 5, 7, 8, 10), intArrayOf(1, 3), false);

    /**
     * Krumhansl style weights: the tonic and fifth anchor the fit, the degrees
     * that identify the mode are lifted, and notes outside it are not zeroed -
     * real recordings contain passing tones, and a zero would reject any
     * performance with ornament in it.
     */
    val profile: DoubleArray = DoubleArray(12) { 2.10 }.also { p ->
        for (d in degrees) p[d] = 3.30
        p[0] = 6.35
        if (7 in degrees) p[7] = 5.00
        for (d in signature) p[d] = 4.55
    }

    /**
     * Scale degrees in quarter tones, 24 to the octave.
     *
     * For the twelve tone modes these are just the semitone degrees doubled.
     * Rast and Bayati are the reason the table exists at all: their neutral
     * intervals - 7 and 21 for Rast, 3 for Bayati - fall between the semitones
     * and cannot be written any other way.
     */
    val quarterDegrees: IntArray = when (name) {
        "RAST" -> intArrayOf(0, 4, 7, 10, 14, 18, 21)
        "BAYATI" -> intArrayOf(0, 3, 6, 10, 14, 16, 20)
        else -> degrees.map { it * 2 }.toIntArray()
    }

    private val quarterSignature: IntArray = when (name) {
        "RAST" -> intArrayOf(7, 21)
        "BAYATI" -> intArrayOf(3, 6)
        else -> signature.map { it * 2 }.toIntArray()
    }

    /** The 24 bin counterpart of [profile]. */
    val quarterProfile: DoubleArray = DoubleArray(24) { 2.10 }.also { p ->
        for (d in quarterDegrees) p[d] = 3.30
        p[0] = 6.35
        if (14 in quarterDegrees) p[14] = 5.00
        for (d in quarterSignature) p[d] = 4.55
    }

    companion object {
        /** Ashkenazi liturgical modes, for grouping and for the taste report. */
        val LITURGICAL = setOf(AHAVAH_RABBAH, MI_SHEBERACH, ADONAI_MALACH)

        /** Modes that sound Middle Eastern to a listener. */
        val EASTERN = setOf(AHAVAH_RABBAH, NAHAWAND, KURD, MI_SHEBERACH, RAST, BAYATI)

        /** The two that only a quarter tone profile can find. */
        val NEUTRAL = setOf(RAST, BAYATI)

        fun byOrdinalOrNull(index: Int): MusicalMode? = entries.getOrNull(index)
    }
}

/** Tonic pitch class, the mode that fitted best, and how clear the fit was. */
data class ModeEstimate(val key: Int, val mode: MusicalMode, val confidence: Double)

object ModeDetector {

    /**
     * Correlates the chroma against every mode in every one of the twelve
     * rotations and keeps the best fit.
     *
     * Confidence is the margin over the runner up rather than the raw
     * correlation: a piece that fits Ahavah Rabbah at 0.82 and minor at 0.81 has
     * not really been identified, and the caller needs to know that.
     */
    fun detect(chroma: DoubleArray, chroma24: DoubleArray? = null): ModeEstimate? {
        // Prefer the quarter tone profile when one was measured: it can express
        // everything the twelve bin version can, plus the neutral intervals.
        val fine = chroma24?.takeIf { it.size >= 24 && it.sum() > 1e-9 }
        if (fine != null) return detectIn(fine, 24) { it.quarterProfile }
        if (chroma.size < 12 || chroma.sum() < 1e-9) return null
        return detectIn(chroma, 12) { it.profile }
    }

    private inline fun detectIn(
        chroma: DoubleArray,
        steps: Int,
        profileOf: (MusicalMode) -> DoubleArray
    ): ModeEstimate? {
        var bestScore = -2.0
        var runnerUp = -2.0
        var bestStep = 0
        var bestMode = MusicalMode.MAJOR

        for (rotation in 0 until steps) {
            val rotated = DoubleArray(steps) { chroma[(it + rotation) % steps] }
            for (mode in MusicalMode.entries) {
                // A neutral mode read off a twelve bin profile is meaningless,
                // and a semitone rotation of a quarter tone chroma is a tonic
                // that lies between two keys - neither is a real answer.
                if (steps == 12 && mode in MusicalMode.NEUTRAL) continue
                if (steps == 24 && rotation % 2 != 0 && mode !in MusicalMode.NEUTRAL) continue
                val score = correlate(rotated, profileOf(mode))
                if (score > bestScore) {
                    runnerUp = bestScore
                    bestScore = score
                    bestStep = rotation
                    bestMode = mode
                } else if (score > runnerUp) {
                    runnerUp = score
                }
            }
        }

        if (bestScore <= -1.5) return null
        val margin = (bestScore - runnerUp).coerceAtLeast(0.0)
        // The key is always reported in semitones, whatever resolution found it.
        val key = if (steps == 24) (bestStep / 2) % 12 else bestStep
        return ModeEstimate(key, bestMode, (margin * 6.0).coerceIn(0.0, 1.0))
    }

    private fun correlate(a: DoubleArray, b: DoubleArray): Double {
        val n = a.size
        var sumA = 0.0
        var sumB = 0.0
        for (i in 0 until n) {
            sumA += a[i]
            sumB += b[i]
        }
        val meanA = sumA / n
        val meanB = sumB / n
        var num = 0.0
        var denA = 0.0
        var denB = 0.0
        for (i in 0 until n) {
            val da = a[i] - meanA
            val db = b[i] - meanB
            num += da * db
            denA += da * da
            denB += db * db
        }
        if (denA < 1e-12 || denB < 1e-12) return -2.0
        return num / Math.sqrt(denA * denB)
    }
}
