package com.elchanan.rhythm.engine

/**
 * The kinds of shelf the home feed can build, as stable keys.
 *
 * The feed's own section ids carry the thing they were built from - "because:
 * 5512", "artist:bentzur", "affinity:chassidic" - so they are different on
 * every library and change as taste changes. That is right for the feed and
 * useless for a setting: a switch has to still mean the same shelf tomorrow.
 * These names are what the switches are stored against, and [of] maps a live
 * section back onto one.
 */
enum class ShelfKind(val key: String, val label: String, val about: String) {
    SPEED_DIAL("speeddial", "חיוג מהיר", "השירים שאתה חוזר אליהם הכי הרבה"),
    QUICK_PICKS("quick", "בחירה מהירה", "מבוסס על ההאזנה, הדירוגים והסאונד"),
    MIXES("mixes", "המיקסים שלך", "אשכולות שנמצאו בספרייה עצמה"),
    DAILY("daily", "מיקסים יומיים", "מתחלפים כל יום"),
    AGAIN("again", "שוב", "דברים שחזרת אליהם"),
    BECAUSE("because", "כי אהבת", "סביב שיר שסימנת בלייק"),
    FRESH("fresh", "עוד לא שמעת", "שירים שטרם ניגנת"),
    ARTIST("artist", "האמן המועדף", "מדף לאמן שאתה הכי מאזין לו"),
    LIVE("live", "הופעות חיות", "הקלטות מהופעה"),
    LIVE_OF_LIKED("liveofliked", "לייב של מה שאהבת", "גרסאות חיות לשירים שאהבת"),
    COVERS("covers", "גרסאות כיסוי", "אמן אחר מבצע שיר שיש לך"),
    YOURS("yours", "בשבילך", "התאמה לפי הטעם שנלמד"),
    AFFINITY("affinity", "לפי סגנון", "סגנון שחוזר אצלך"),
    LONGFORM("longform", "ארוכים", "שירים מעל שמונה דקות"),
    ADDED("added", "נוספו לאחרונה", "מה שהגיע למכשיר לא מזמן"),
    ALBUMS("albums", "אלבומים בשבילך", "אלבומים מתוך הספרייה");

    companion object {
        /** Everything on, which is what a new install should look like. */
        val ALL_KEYS: Set<String> = entries.map { it.key }.toSet()

        /**
         * The shelf a live section belongs to.
         *
         * Ids are either the key itself or the key followed by a colon and
         * whatever the shelf was built around.
         */
        fun of(sectionId: String): ShelfKind? {
            val head = sectionId.substringBefore(':')
            return entries.firstOrNull { it.key == head }
        }
    }
}
