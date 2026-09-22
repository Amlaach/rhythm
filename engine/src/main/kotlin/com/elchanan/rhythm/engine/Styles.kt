package com.elchanan.rhythm.engine

/**
 * The style vocabulary the user picks from when rating an artist.
 * Free text is allowed too - these are only shortcuts.
 */
object Styles {

    val SUGGESTED: List<String> = listOf(
        "חסידי", "ליטאי", "מזרחי", "חזנות", "ישראלי", "פופ", "רוק", "בלדה",
        "קצבי", "רגוע", "שמח", "מרגש", "ריקודים", "אקוסטי", "אלקטרוני",
        "ראפ", "היפ הופ", "ג'אז", "קלאסי", "ילדים", "נוסטלגיה", "אנגלית",
        "שירי נשמה", "כלייזמר", "אווירה", "אינסטרומנטלי"
    )

    /** A rough grouping used only to colour the chips. */
    val FAMILIES: Map<String, List<String>> = linkedMapOf(
        "ז'אנר" to listOf(
            "חסידי", "ליטאי", "מזרחי", "חזנות", "ישראלי", "פופ", "רוק",
            "ראפ", "היפ הופ", "ג'אז", "קלאסי", "אלקטרוני", "כלייזמר", "ילדים", "אנגלית"
        ),
        "אופי" to listOf(
            "קצבי", "רגוע", "שמח", "מרגש", "ריקודים", "בלדה",
            "אקוסטי", "נוסטלגיה", "שירי נשמה", "אווירה", "אינסטרומנטלי"
        )
    )

    /**
     * Which family a style word belongs to, or null for anything free typed.
     *
     * The distinction the learner needs in order to have anything to say about
     * a song whose artist is already tagged: "חסידי" and "קצבי" are answers to
     * two different questions, and knowing one is no reason not to learn the
     * other.
     */
    fun familyOf(style: String): String? {
        val wanted = style.trim()
        for ((family, members) in FAMILIES) {
            if (members.any { it.equals(wanted, ignoreCase = true) }) return family
        }
        return null
    }

    /**
     * Whether a set of labels says anything about the question [style] answers.
     *
     * Nobody labels a library completely. They settle the genre once per
     * artist and type a character on the handful of tracks they feel strongly
     * about, and everything else carries a genre and nothing else.
     *
     * Treating that silence as a "no" is what broke the learner. A song
     * labelled only "חסידי" was counted as a negative example of "קצבי" -
     * proof that it is not lively - when all it really says is that nobody was
     * ever asked. Every fast song in the library that happened to be tagged
     * only by genre became a counter-example of fastness, the boundary was
     * fitted through the middle of the positives, and the style then failed
     * the precision gate on the same unanswered songs. Which is what the
     * screen reported, run after run, to a user who had done a great deal of
     * tagging: nothing is good enough to write.
     *
     * So a style is only trained and scored on the songs that answered its
     * question - the ones carrying some label from the same family. A free
     * typed word belongs to no family and there is no telling what it
     * contradicts, so it keeps the old behaviour of being measured against
     * everything.
     */
    fun answers(style: String, labels: Collection<String>): Boolean {
        val family = familyOf(style) ?: return true
        return labels.any { familyOf(it) == family }
    }

    fun parse(raw: String): List<String> =
        raw.split(',', '|', '،')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    fun join(list: List<String>): String = list.distinct().joinToString(", ")

    /**
     * Styles the user has said must never end up in the same mix.
     *
     * The engine's idea of similar is built from listening, and listening does
     * not distinguish between "these go together" and "I happen to own both".
     * Someone with a library of חסידי and ישראלי plays both, so both get
     * learned as part of the same taste, and a mix drifts from one to the
     * other in a way that is technically well-scored and musically wrong.
     * There is no signal in the data that would prevent it, because the
     * objection is not about the audio - it is about what the listener wants
     * to hear in one sitting. So it is stated rather than inferred.
     *
     * Stored one rule per line, styles within a rule separated by commas.
     * Two styles clash when some rule names both of them.
     */
    class Separations private constructor(private val rules: List<Set<String>>) {

        val isEmpty: Boolean get() = rules.isEmpty()

        /**
         * True when these two sets of styles must not be put together.
         *
         * A song with no styles at all never clashes. It is not evidence of
         * agreement, but treating silence as a conflict would empty every mix
         * in a library that has not been tagged.
         */
        fun clash(a: Collection<String>, b: Collection<String>): Boolean {
            if (rules.isEmpty() || a.isEmpty() || b.isEmpty()) return false
            val left = a.map { normalize(it) }
            val right = b.map { normalize(it) }
            for (rule in rules) {
                val inLeft = left.filter { it in rule }
                if (inLeft.isEmpty()) continue
                val inRight = right.filter { it in rule }
                if (inRight.isEmpty()) continue
                // Both sides name something in this rule. They are only allowed
                // together if they name the same thing - two חסידי tracks are
                // fine, חסידי against ישראלי is not.
                if (inRight.any { it !in inLeft }) return true
                if (inLeft.any { it !in inRight }) return true
            }
            return false
        }

        companion object {
            fun parse(raw: String): Separations {
                val rules = raw.split('\n')
                    .map { line ->
                        line.split(',', '|', '،')
                            .map { normalize(it) }
                            .filter { it.isNotEmpty() }
                            .toSet()
                    }
                    // A rule naming one style separates it from nothing.
                    .filter { it.size >= 2 }
                return Separations(rules)
            }

            val NONE = Separations(emptyList())

            private fun normalize(style: String): String =
                style.trim().lowercase()
        }
    }

    /**
     * What a new install starts with.
     *
     * Only the one pair, and only because it is the pair this app was built
     * for. Everything else is left to the user, who is the only one who knows
     * what they do not want mixed.
     */
    const val DEFAULT_SEPARATIONS = "חסידי, ישראלי"
}
