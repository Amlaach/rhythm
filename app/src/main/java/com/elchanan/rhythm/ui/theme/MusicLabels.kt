package com.elchanan.rhythm.ui.theme

import com.elchanan.rhythm.engine.Features

/**
 * "90 BPM · A# מז'ור", laid out so it reads that way in Hebrew.
 *
 * Written plainly, the line mixes a number, Latin letters, a sharp sign and a
 * Hebrew word, and the bidirectional algorithm pulls it apart: the "#" is a
 * neutral between a Latin note and a Hebrew word, so it takes the Hebrew
 * direction and jumps away from its note, and the line came out as
 * "מז'ור #BPM · A 90". Each Latin piece is isolated here, so it keeps its own
 * order, and the line starts with a direction mark for the interface language,
 * so the pieces are placed right to left in Hebrew and left to right in
 * English.
 */
fun tempoAndKey(bpm: Int, key: Int, mode: Int): String =
    directionMark() + LRI + "$bpm BPM" + PDI + " · " + isolateNote(localized(Features.keyLabel(key, mode)).orEmpty())

/** A key on its own - "A# מז'ור", "D אהבה רבה" - with its note kept together. */
fun keyText(label: String): String = directionMark() + isolateNote(localized(label).orEmpty())

private fun isolateNote(label: String): String {
    val note = NOTES.firstOrNull { label.startsWith(it) && (label.length == it.length || label[it.length] == ' ') }
        ?: return label
    return LRI + note + PDI + label.substring(note.length)
}

private fun directionMark(): String = if (UiLanguage.english) LRM else RLM

/** Longest first, so "A#" is found before "A". */
private val NOTES = Features.KEY_NAMES.sortedByDescending { it.length }

/** Left-to-right isolate: what is inside keeps its own order and does not move what is around it. */
private const val LRI = "⁦"
private const val PDI = "⁩"
private const val LRM = "‎"
private const val RLM = "‏"
