package com.elchanan.rhythm.engine

/**
 * Which letter a title files under, for a scrollable A to Z index.
 *
 * Hebrew first, because that is what this library is mostly made of, then a
 * single bucket for anything written in latin letters and a catch-all for
 * numbers and symbols. Twenty three entries fit down the side of a screen;
 * twenty three plus twenty six does not, and splitting the latin titles into
 * their own letters would spend most of that space on buckets holding one
 * album each.
 *
 * Shared by both builds so the two indexes agree. A song that sits under ס on
 * the phone has to sit under ס on Windows - not because anything breaks
 * otherwise, but because it is the same library and someone who has learned
 * where a title lives should not have to learn it twice.
 */
object AlphabetIndexing {

    /** The buckets, in the order they are drawn down the side of a list. */
    val letters: List<String> =
        ("אבגדהוזחטיכלמנסעפצקרשת".map { it.toString() }) + listOf("A", "#")

    /**
     * The bucket a title belongs to.
     *
     * Final forms fold into their ordinary letters: nobody looks for a title
     * beginning with ם, and five extra buckets holding almost nothing would
     * push the useful ones off the strip.
     */
    fun initialOf(title: String): String {
        val c = title.trim().firstOrNull() ?: return "#"
        return when {
            c in 'א'..'ת' -> when (c) {
                'ך' -> "כ"
                'ם' -> "מ"
                'ן' -> "נ"
                'ף' -> "פ"
                'ץ' -> "צ"
                else -> c.toString()
            }
            c.isLetter() -> "A"
            else -> "#"
        }
    }

    /**
     * The buckets actually present in a list, in index order.
     *
     * Drawing the empty ones would make the strip a fixed ruler rather than a
     * map of what is there, and tapping one of them would scroll nowhere.
     */
    fun present(titles: List<String>): List<String> {
        val set = titles.mapTo(HashSet()) { initialOf(it) }
        return letters.filter { it in set }
    }
}
