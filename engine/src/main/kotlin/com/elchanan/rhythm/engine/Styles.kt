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
    class Separations private constructor(
        private val rules: List<Set<String>>,
        /**
         * Styles that mix only with themselves: a line naming one style.
         *
         * The pairwise rules could only keep two named styles apart. They had
         * nothing to say about everything else, so "אנגלית" kept a mix clear of
         * "חסידי" if the user had thought to write that pair, and sat happily
         * next to every untagged song and every style nobody had paired it
         * with. What was actually wanted was "English with English, and that
         * is all" - which is a property of one style, not of a pair.
         *
         * Untagged songs count as "not this style" here, unlike in the pair
         * rules. Silence is no conflict between two named styles, but a style
         * that is to mix only with itself has to keep out the unknown too, or
         * it keeps out almost nothing in a library that is mostly untagged.
         */
        private val isolated: Set<String> = emptySet()
    ) {

        val isEmpty: Boolean get() = rules.isEmpty() && isolated.isEmpty()

        /**
         * True when these two sets of styles must not be put together.
         *
         * A song with no styles at all never clashes. It is not evidence of
         * agreement, but treating silence as a conflict would empty every mix
         * in a library that has not been tagged.
         */
        fun clash(a: Collection<String>, b: Collection<String>): Boolean {
            if (isolated.isNotEmpty()) {
                val left = a.mapTo(HashSet()) { normalize(it) }
                val right = b.mapTo(HashSet()) { normalize(it) }
                for (style in isolated) {
                    if ((style in left) != (style in right)) return true
                }
            }
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

        /**
         * Thins a list down to one side of every rule: the side the listening
         * leans to, weighed by [weight]. The list's own order is kept.
         *
         * For a list with no seed to take a side from - a mood chip's list,
         * strongest first. Taking the strongest song's side made the whole
         * list hang on one reading of the model: whichever style happened to
         * top "שמח" decided that a listener of English songs got none. The
         * side the listener actually plays in is the one they came for.
         *
         * Ties go to the side with more songs, then, for a style kept to
         * itself, to the side without it, as [keepTogether] does. Untagged
         * songs clash with no pair rule and stay.
         */
        fun <T> favoured(group: List<T>, stylesOf: (T) -> Collection<String>, weight: (T) -> Double): List<T> {
            if (isEmpty || group.size < 2) return group
            val named = group.map { g -> stylesOf(g).mapTo(HashSet()) { normalize(it) } }
            val weights = group.map(weight)
            fun heavier(a: List<Int>, b: List<Int>): Boolean {
                val wa = a.sumOf { weights[it] }
                val wb = b.sumOf { weights[it] }
                return if (wa != wb) wa > wb else a.size > b.size
            }
            var kept: List<Int> = group.indices.toList()
            for (style in isolated.sorted()) {
                val (with, without) = kept.partition { style in named[it] }
                if (with.isEmpty() || without.isEmpty()) continue
                kept = if (heavier(with, without)) with else without
            }
            if (rules.isNotEmpty()) {
                val ruled = rules.flatMapTo(HashSet()) { it }
                val weightOf = HashMap<String, Double>()
                val countOf = HashMap<String, Int>()
                for (i in kept) for (style in named[i]) {
                    if (style !in ruled) continue
                    weightOf[style] = (weightOf[style] ?: 0.0) + weights[i]
                    countOf[style] = (countOf[style] ?: 0) + 1
                }
                // Heaviest first, each taken unless a rule already holds
                // another style it names. A song stays only if every ruled
                // style it carries was taken - which leaves no two songs
                // naming different things in one rule.
                val order = weightOf.keys.sortedWith(
                    compareByDescending<String> { weightOf[it] }
                        .thenByDescending { countOf[it] }
                        .thenBy { it }
                )
                val chosen = HashSet<String>()
                for (style in order) {
                    if (rules.none { rule -> style in rule && rule.any { it in chosen } }) chosen.add(style)
                }
                kept = kept.filter { i -> named[i].all { it !in ruled || it in chosen } }
            }
            return kept.map { group[it] }
        }

        companion object {
            fun parse(raw: String): Separations {
                val lines = raw.split('\n')
                    .map { line ->
                        line.split(',', '|', '،')
                            .map { normalize(it.trim().removePrefix(ONLY).trim()) }
                            .filter { it.isNotEmpty() }
                            .toSet()
                    }
                // A line naming two or more styles keeps them apart; a line
                // naming one keeps that style to itself.
                val rules = lines.filter { it.size >= 2 }
                val isolated = lines.filter { it.size == 1 }.flatMapTo(HashSet()) { it }
                return Separations(rules, isolated)
            }

            /** The word a single-style line may start with: "רק אנגלית". */
            private const val ONLY = "רק "

            /**
             * Thins a group down to what can sit together, keeping the larger
             * side of every divide and the group's own order.
             */
            fun <T> keepTogether(
                group: List<T>,
                separations: Separations,
                stylesOf: (T) -> Collection<String>
            ): List<T> {
                if (separations.isEmpty || group.size < 2) return group
                var kept = group
                // A style kept to itself splits the group in two: songs that
                // carry it and songs that do not. The bigger half stays.
                for (style in separations.isolated) {
                    val (with, without) = kept.partition { s ->
                        stylesOf(s).any { normalize(it) == style }
                    }
                    if (with.isEmpty() || without.isEmpty()) continue
                    kept = if (with.size > without.size) with else without
                }
                return kept
            }

            val NONE = Separations(emptyList(), emptySet())

            private fun normalize(style: String): String =
                style.trim().lowercase()
        }
    }

    /**
     * What a new install starts with: nothing kept apart.
     *
     * It was "חסידי, ישראלי", the pair the app was first built around, and
     * the owner asked for it gone - what not to mix is the listener's to say,
     * and Windows never had it. A rule someone wrote stays theirs; only an
     * install that never set one follows this.
     */
    const val DEFAULT_SEPARATIONS = ""

    /** The rule the default used to be, for the tests that exercise separations with it. */
    const val HASIDIC_ISRAELI = "חסידי, ישראלי"
}
