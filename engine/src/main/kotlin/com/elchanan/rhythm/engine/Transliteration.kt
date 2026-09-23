package com.elchanan.rhythm.engine

import java.util.Locale

/**
 * Whether a Hebrew name and a Latin one are the same name.
 *
 * Downloaded files carry names twice - "דרשו גלובל | Dirshu Global" - and a
 * tag repair that is to drop the second copy has to know it is a copy and not
 * something else, such as the song's own name in English sitting in the
 * artist field. There is no dictionary to ask. What both spellings keep is
 * the consonants: Hebrew writes few vowels and transliteration has no fixed
 * ones, so both are reduced to a skeleton of consonant classes and compared.
 *
 *   דרשו גלובל -> D R S G L B L      Dirshu Global -> D R S G L B L
 *   ארץ ישראל  -> R C S R L          Erets Israel  -> R C S R L
 *
 * The letters that are sometimes a vowel and sometimes not - א ו י ע, and
 * the Latin vowels with y and w - are left out on both sides. ה and a written
 * h are kept but may be missing on either side, as in משה and Moshe, and so
 * may a Latin v, which is as often ו as ב - David, דוד. A few
 * pairs are written either way in practice and count as one: ב is b or v,
 * כ is k or ch, ח is h, ch or kh, צ is ts, tz or z, פ is p or f.
 */
object Transliteration {

    private val HEBREW = Regex("""[\x{0590}-\x{05FF}]""")
    private val LATIN = Regex("[A-Za-z]")
    private val MARKS = Regex("""[\x{0591}-\x{05C7}]""")

    fun hasHebrew(text: String) = HEBREW.containsMatchIn(text)
    fun hasLatin(text: String) = LATIN.containsMatchIn(text)

    /** The consonant skeleton of a Hebrew name. */
    fun hebrewSkeleton(text: String): String = buildString {
        for (c in MARKS.replace(text, "")) {
            when (c) {
                'ב' -> append('B')
                'ג' -> append('G')
                'ד' -> append('D')
                'ז' -> append('Z')
                'ח' -> append('X')
                'ה' -> append('H')
                'ט', 'ת' -> append('T')
                'כ', 'ך', 'ק' -> append('K')
                'ל' -> append('L')
                'מ', 'ם' -> append('M')
                'נ', 'ן' -> append('N')
                'ס', 'ש' -> append('S')
                'פ', 'ף' -> append('P')
                'צ', 'ץ' -> append('C')
                'ר' -> append('R')
                // א ו י ע and everything else: a vowel, a mark, a space.
                else -> Unit
            }
        }
    }.collapseRuns()

    /** The consonant skeleton of a Latin transliteration. */
    fun latinSkeleton(text: String): String {
        val s = text.lowercase(Locale.ROOT)
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val pair = if (i + 1 < s.length) s.substring(i, i + 2) else ""
            when {
                pair == "sh" -> { out.append('S'); i += 2; continue }
                pair == "ch" || pair == "kh" -> { out.append('X'); i += 2; continue }
                pair == "ts" || pair == "tz" -> { out.append('C'); i += 2; continue }
                pair == "ph" -> { out.append('P'); i += 2; continue }
                pair == "th" -> { out.append('T'); i += 2; continue }
            }
            when (c) {
                'b' -> out.append('B')
                // Either ב or a ו written as a consonant, which the Hebrew
                // side does not keep: see gap.
                'v' -> out.append('V')
                'g', 'j' -> out.append('G')
                'd' -> out.append('D')
                'z' -> out.append('Z')
                'h' -> out.append('H')
                't' -> out.append('T')
                'k', 'q', 'c' -> out.append('K')
                'x' -> out.append("KS")
                'l' -> out.append('L')
                'm' -> out.append('M')
                'n' -> out.append('N')
                's' -> out.append('S')
                'p', 'f' -> out.append('P')
                'r' -> out.append('R')
                else -> Unit
            }
            i++
        }
        return out.toString().collapseRuns()
    }

    /**
     * Whether [hebrew] and [latin] spell the same name.
     *
     * Short names are compared exactly - two or three consonants in common is
     * how unrelated words look alike. Longer ones may differ by one consonant
     * in every five, which is about what an extra ה or a doubled letter costs.
     */
    fun sameName(hebrew: String, latin: String): Boolean {
        if (!hasHebrew(hebrew) || !hasLatin(latin) || hasHebrew(latin)) return false
        val a = hebrewSkeleton(hebrew)
        val b = latinSkeleton(latin)
        if (a.count { it != 'H' } < MIN_SKELETON || b.count { it != 'H' } < MIN_SKELETON) return false
        val allowed = if (maxOf(a.length, b.length) <= 4) 0 else maxOf(a.length, b.length) / 5
        return distance(a, b) <= allowed
    }

    private const val MIN_SKELETON = 3

    /** Consonants two spellings may use for the same letter. */
    private fun alike(x: Char, y: Char): Boolean = x == y ||
        (x == 'X' && (y == 'K' || y == 'H')) || (y == 'X' && (x == 'K' || x == 'H')) ||
        (x == 'C' && (y == 'Z' || y == 'S')) || (y == 'C' && (x == 'Z' || x == 'S')) ||
        (x == 'T' && y == 'C') || (x == 'C' && y == 'T') ||
        (x == 'B' && y == 'V') || (x == 'V' && y == 'B')

    private fun distance(a: String, b: String): Int {
        // Leaving out an H costs nothing: a silent ה is written or not. Nor a
        // Latin v, which is often a ו - "David" for דוד.
        fun gap(c: Char) = if (c == 'H' || c == 'V') 0 else 1
        val prev = IntArray(b.length + 1)
        for (j in 1..b.length) prev[j] = prev[j - 1] + gap(b[j - 1])
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = prev[0] + gap(a[i - 1])
            for (j in 1..b.length) {
                val cost = if (alike(a[i - 1], b[j - 1])) 0 else 1
                cur[j] = minOf(prev[j] + gap(a[i - 1]), cur[j - 1] + gap(b[j - 1]), prev[j - 1] + cost)
            }
            System.arraycopy(cur, 0, prev, 0, cur.size)
        }
        return prev[b.length]
    }

    /** A doubled consonant is one consonant: "Ribbo" and "ריבו" alike. */
    private fun String.collapseRuns(): String = buildString {
        for (c in this@collapseRuns) if (isEmpty() || last() != c) append(c)
    }
}
