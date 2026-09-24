package com.elchanan.rhythm.data

import com.elchanan.rhythm.engine.Names
import com.elchanan.rhythm.engine.Transliteration

/** Suggestions only; callers must ask which spelling to keep. */
object ArtistMerge {
    fun oneLetterApart(left: String, right: String): Boolean {
        val a = Names.normalizeKey(left)
        val b = Names.normalizeKey(right)
        if (a == b || a == "unknown" || b == "unknown" || kotlin.math.abs(a.length - b.length) > 1) return false
        var i = 0
        var j = 0
        var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) { i++; j++; continue }
            if (++edits > 1) return false
            // Suggest letter typos, not punctuation, spacing or numeric variants.
            if (a.length >= b.length && !a[i].isLetter()) return false
            if (b.length >= a.length && !b[j].isLetter()) return false
            if (a.length >= b.length) i++
            if (b.length >= a.length) j++
        }
        if (i < a.length) { if (!a[i].isLetter()) return false; edits++ }
        if (j < b.length) { if (!b[j].isLetter()) return false; edits++ }
        return edits == 1
    }

    // Match the separators recognized by Names.credits, preserving guest credits.
    private val separator = Regex(
        " feat\\. | feat | ft\\. | ft | featuring | & | / | x | vs\\. | vs |;| עם |,",
        RegexOption.IGNORE_CASE
    )

    fun renameCredit(raw: String, sourceKey: String, targetName: String): String {
        fun rename(part: String): String {
            if (Names.normalizeKey(part.trim().trim('-', '–', '.').trim()) != sourceKey) return part
            val leading = part.takeWhile { it.isWhitespace() }
            val trailing = part.takeLastWhile { it.isWhitespace() }
            return leading + targetName + trailing
        }
        return buildString {
            var start = 0
            for (match in separator.findAll(raw)) {
                append(rename(raw.substring(start, match.range.first)))
                append(match.value)
                start = match.range.last + 1
            }
            append(rename(raw.substring(start)))
        }
    }

    /**
     * Which artists' ratings and styles should follow their songs to a new
     * name, from each song's artist key before a change and after it.
     *
     * An old key is moved only when it has no songs left and every one of its
     * songs went to the same new key. A split - some songs to one singer, some
     * to another - moves nothing, because either answer would be a guess.
     */
    fun profileMoves(before: Map<Long, String>, after: Map<Long, String>): Map<String, String> {
        val stillThere = after.values.toHashSet()
        val movedTo = HashMap<String, MutableSet<String>>()
        for ((id, old) in before) {
            val now = after[id] ?: continue
            if (now != old) movedTo.getOrPut(old) { HashSet() }.add(now)
        }
        return movedTo.filter { (old, targets) -> old !in stillThere && targets.size == 1 }
            .mapValues { it.value.first() }
    }

    /**
     * A name as loosely as two spellings of one Hebrew name can differ and
     * still be the same person.
     *
     * The key already ignores case, marks and punctuation. This also ignores
     * the order of the words ("ריבו ישי"), a title in front ("הרב", "ר'",
     * "החזן"), a final letter written as an ordinary one, and the doubled
     * vav and yod of full spelling ("שוואקי" and "שואקי", "וייס" and "ויס").
     * Too loose to merge by itself; exactly right to ask about.
     */
    fun looseKey(name: String): String {
        val words = Names.normalizeKey(name).split(' ')
            .filter { it.length > 1 && it !in TITLES }
            .map { word ->
                val folded = StringBuilder(word.length)
                for (c in word) folded.append(FINALS[c] ?: c)
                folded.toString().replace("וו", "ו").replace("יי", "י")
            }
            .sorted()
        return words.joinToString(" ").ifEmpty { Names.normalizeKey(name) }
    }

    /** Titles that come before a name and are not part of it. */
    private val TITLES = setOf(
        "הרב", "הרבנית", "הגאון", "הגה", "רבי", "הזמר", "הזמרת", "החזן", "חזן", "המנחה",
        "rabbi", "reb", "cantor", "chazzan", "the", "mr", "mrs"
    )

    private val FINALS = mapOf('ך' to 'כ', 'ם' to 'מ', 'ן' to 'נ', 'ף' to 'פ', 'ץ' to 'צ')

    /**
     * Whether two artist names are probably one artist: the same loosely,
     * one letter apart, or one in Hebrew and the other its English spelling.
     * A suggestion to put to the user, never a merge.
     */
    fun likelySame(left: String, right: String): Boolean {
        val a = Names.normalizeKey(left)
        val b = Names.normalizeKey(right)
        if (a == b || a == "unknown" || b == "unknown") return false
        val la = looseKey(left)
        val lb = looseKey(right)
        if (la == lb) return true
        if (oneLetterApart(left, right) || oneLetterApart(la, lb)) return true
        return Transliteration.sameName(left, right) || Transliteration.sameName(right, left)
    }

    /** Every pair of [names] that is [likelySame], each pair once, in the order given. */
    fun <T> suggestions(names: List<T>, nameOf: (T) -> String): List<Pair<T, T>> {
        val out = ArrayList<Pair<T, T>>()
        for (i in names.indices) for (j in i + 1 until names.size) {
            if (likelySame(nameOf(names[i]), nameOf(names[j]))) out.add(names[i] to names[j])
        }
        return out
    }

    /**
     * Where the rating, styles and note of artists with no songs left should
     * go, when the answer is clear.
     *
     * A tag fix or a merge moves an artist's songs to another spelling, and
     * the profile is carried along when it happens (see [profileMoves]) -
     * but only since that was added, and only when the change was made in
     * the app. Profiles stranded before, or by tags written into the files,
     * sat on a name with no songs: the rating looked lost. This finds them
     * a home after the fact, in this order:
     *
     *  - the old key read the way keys are read now: a name spelled with a
     *    geresh, a dash or a direction mark was a key of its own until
     *    [Names.normalizeKey] learned to see through them;
     *  - where the songs filed under it by their own tags are all filed now:
     *    [rawToNow] is each song's artist key from its file and in the app;
     *  - the one artist it is loosely the same as ([looseKey]), when there is
     *    exactly one.
     *
     * Anything less certain stays where it is.
     */
    fun orphanMoves(
        orphans: Collection<String>,
        present: Set<String>,
        rawToNow: Collection<Pair<String, String>>
    ): Map<String, String> {
        val byLoose = present.groupBy { looseKey(it) }
        val out = HashMap<String, String>()
        for (old in orphans) {
            if (old in present) continue
            val renamed = Names.normalizeKey(old)
            if (renamed != old && renamed in present) {
                out[old] = renamed
                continue
            }
            val went = rawToNow.filter { it.first == old || it.first == renamed }.mapTo(HashSet()) { it.second }
            if (went.size == 1 && went.first() in present && went.first() != old) {
                out[old] = went.first()
                continue
            }
            val same = byLoose[looseKey(old)].orEmpty().filter { it != old }
            if (same.size == 1) out[old] = same[0]
        }
        return out
    }
}
