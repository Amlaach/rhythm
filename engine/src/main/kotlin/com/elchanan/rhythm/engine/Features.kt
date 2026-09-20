package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.db.AudioFeatureEntity

/**
 * Reading a stored analysis row back out, and naming what it found.
 *
 * These sat in AudioAnalyzer next to the code that produced the row. That is
 * where they were written, but it is not where they belong: analysing a file
 * needs a decoder and therefore a platform, while reading the result back is
 * arithmetic on a string and a pair of integers. Everything in the engine
 * that consults a stored feature row - the acoustic space, the explanations
 * the recommender writes - needed these and would have dragged the whole
 * decoder along behind them.
 *
 * Split out so the engine can read an analysis anywhere, whatever produced
 * it. AudioAnalyzer still writes the rows; this reads them.
 */
object Features {

    val KEY_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    /**
     * A vector back out of the comma separated form it is stored in.
     *
     * Always [expected] long: a row written by an older version with fewer
     * numbers in it reads as zeros in the missing places rather than throwing,
     * because these rows outlive the code that wrote them.
     */
    fun parseVector(csv: String, expected: Int): DoubleArray {
        val parts = csv.split(',')
        return DoubleArray(expected) { parts.getOrNull(it)?.toDoubleOrNull() ?: 0.0 }
    }

    fun keyLabel(key: Int, mode: Int): String = when {
        key < 0 -> "לא זוהה"
        mode == 1 -> "${KEY_NAMES[key]} מז'ור"
        mode == 0 -> "${KEY_NAMES[key]} מינור"
        else -> KEY_NAMES[key]
    }

    /**
     * Prefers the modal name over "major"/"minor" when the estimate was clear.
     * "D אהבה רבה" tells a listener here far more than "D מינור" does.
     */
    fun modeLabel(f: AudioFeatureEntity): String {
        if (f.musicalKey < 0) return "לא זוהה"
        val mode = MusicalMode.byOrdinalOrNull(f.scaleMode)
        if (mode == null || f.scaleConfidence < 0.2f) return keyLabel(f.musicalKey, f.mode)
        return "${KEY_NAMES[f.musicalKey]} ${mode.label}"
    }
}
