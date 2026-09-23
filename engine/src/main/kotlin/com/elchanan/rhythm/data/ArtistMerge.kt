package com.elchanan.rhythm.data

import com.elchanan.rhythm.engine.Names

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
}
