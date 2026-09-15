package com.elchanan.rhythm.engine

/**
 * The style vocabulary the user picks from when rating an artist.
 * Free text is allowed too - these are only shortcuts.
 */
object Styles {

    val SUGGESTED: List<String> = listOf(
        "חסידי", "ליטאי", "מזרחי", "חזנות", "פופ", "רוק", "בלדה",
        "קצבי", "רגוע", "שמח", "מרגש", "ריקודים", "אקוסטי", "אלקטרוני",
        "היפ הופ", "ג'אז", "קלאסי", "ילדים", "נוסטלגיה", "אנגלית",
        "שירי נשמה", "כלייזמר", "אווירה", "אינסטרומנטלי"
    )

    /** A rough grouping used only to colour the chips. */
    val FAMILIES: Map<String, List<String>> = linkedMapOf(
        "ז'אנר" to listOf(
            "חסידי", "ליטאי", "מזרחי", "חזנות", "פופ", "רוק",
            "היפ הופ", "ג'אז", "קלאסי", "אלקטרוני", "כלייזמר", "ילדים", "אנגלית"
        ),
        "אופי" to listOf(
            "קצבי", "רגוע", "שמח", "מרגש", "ריקודים", "בלדה",
            "אקוסטי", "נוסטלגיה", "שירי נשמה", "אווירה", "אינסטרומנטלי"
        )
    )

    fun parse(raw: String): List<String> =
        raw.split(',', '|', '،')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    fun join(list: List<String>): String = list.distinct().joinToString(", ")
}
