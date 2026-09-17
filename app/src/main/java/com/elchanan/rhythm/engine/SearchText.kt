package com.elchanan.rhythm.engine

import java.util.Locale

/**
 * Text handling for search, built around how Hebrew is actually typed.
 *
 * Plain substring matching fails on this library constantly, and almost never
 * because of a real spelling mistake:
 *
 *  - Final forms. Nobody types "מוהרן" with a final nun when searching, and the
 *    title has one. ך ם ן ף ץ all have to fold into their ordinary letters or
 *    half the library is unreachable by its own name.
 *  - Gershayim and quotes. "ליקוטי מוהר״ן" is stored with a mark in the middle
 *    of the word; typing the word without it should still find it.
 *  - Niqqud, which appears in a handful of titles and in none of the queries.
 *
 * Only after all of that is a genuine typo worth allowing for, and then just
 * one character, on words long enough that one edit cannot turn them into a
 * different word.
 */
object SearchText {

    private val FINALS = mapOf(
        'ך' to 'כ', // ך -> כ
        'ם' to 'מ', // ם -> מ
        'ן' to 'נ', // ן -> נ
        'ף' to 'פ', // ף -> פ
        'ץ' to 'צ'  // ץ -> צ
    )

    /** Niqqud, cantillation, and the marks that sit inside words. */
    private val MARKS = Regex("[֑-ׇ׳״\"'`´’“”׳״]")

    private val SEPARATORS = Regex("[\\p{Punct}\\s]+")

    fun normalize(raw: String): String {
        val stripped = MARKS.replace(raw, "")
        val folded = buildString(stripped.length) {
            for (c in stripped) append(FINALS[c] ?: c)
        }
        return folded.lowercase(Locale.ROOT).trim()
    }

    fun terms(query: String): List<String> =
        SEPARATORS.split(normalize(query)).filter { it.isNotBlank() }

    /**
     * How well one term matches a field, or null when it does not match at all.
     *
     * Word starts score above mid-word hits, so typing "שמח" puts "שמחה" above
     * a song with "בשמחה" buried in the middle of its title.
     */
    fun score(field: String, term: String): Double? {
        if (term.isEmpty()) return null
        if (field.startsWith(term)) return 3.0
        val at = field.indexOf(term)
        if (at == 0) return 3.0
        if (at > 0) {
            val before = field[at - 1]
            return if (before == ' ') 2.4 else 1.6
        }
        // A typo is worth allowing only on a word long enough to survive one.
        if (term.length >= 4 && anyWordWithinOneEdit(field, term)) return 1.0
        return null
    }

    private fun anyWordWithinOneEdit(field: String, term: String): Boolean {
        for (word in field.split(' ')) {
            if (word.length < 3) continue
            if (withinOneEdit(word, term)) return true
        }
        return false
    }

    /** True when one insertion, deletion or substitution turns a into b. */
    fun withinOneEdit(a: String, b: String): Boolean {
        val diff = a.length - b.length
        if (diff > 1 || diff < -1) return false
        var i = 0
        var j = 0
        var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) {
                i++
                j++
                continue
            }
            if (++edits > 1) return false
            when {
                a.length > b.length -> i++
                a.length < b.length -> j++
                else -> {
                    i++
                    j++
                }
            }
        }
        if (i < a.length || j < b.length) edits++
        return edits <= 1
    }
}
