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

    private fun foldFinal(c: Char): Char = when (c) {
        'ך' -> 'כ'
        'ם' -> 'מ'
        'ן' -> 'נ'
        'ף' -> 'פ'
        'ץ' -> 'צ'
        else -> c
    }

    /** Niqqud, cantillation (\u0591..\u05C7), and the marks that sit inside words. */
    private fun isMark(c: Char): Boolean =
        (c in '\u0591'..'\u05C7') ||
        c == '׳' || c == '״' ||
        c == '"' || c == '\'' || c == '`' || c == '´' ||
        c == '’' || c == '“' || c == '”'

    private val SEPARATORS = Regex("[\\p{Punct}\\s]+")

    fun normalize(raw: String): String {
        val sb = java.lang.StringBuilder(raw.length)
        for (i in 0 until raw.length) {
            val c = raw[i]
            if (!isMark(c)) {
                sb.append(foldFinal(c))
            }
        }
        return sb.toString().lowercase(Locale.ROOT).trim()
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
        var start = 0
        val len = field.length
        val termLen = term.length
        while (start < len) {
            val space = field.indexOf(' ', start)
            val end = if (space == -1) len else space
            val wordLen = end - start
            if (wordLen >= 3 && (wordLen - termLen in -1..1)) {
                if (withinOneEdit(field, start, end, term)) return true
            }
            start = end + 1
        }
        return false
    }

    private fun withinOneEdit(field: String, start: Int, end: Int, b: String): Boolean {
        val aLen = end - start
        val bLen = b.length
        val diff = aLen - bLen
        if (diff > 1 || diff < -1) return false
        var i = 0
        var j = 0
        var edits = 0
        while (i < aLen && j < bLen) {
            if (field[start + i] == b[j]) {
                i++
                j++
                continue
            }
            if (++edits > 1) return false
            when {
                aLen > bLen -> i++
                aLen < bLen -> j++
                else -> {
                    i++
                    j++
                }
            }
        }
        if (i < aLen || j < bLen) edits++
        return edits <= 1
    }

    /** True when one insertion, deletion or substitution turns a into b. */
    fun withinOneEdit(a: String, b: String): Boolean {
        return withinOneEdit(a, 0, a.length, b)
    }
}
