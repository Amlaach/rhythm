package com.elchanan.rhythm.ui

/**
 * Where a player control appears.
 *
 * Three states rather than a switch, because "show it" and "show it as its own
 * button" are different questions. Someone who wants the lyrics reachable but
 * does not want a fourth icon across the top has no way to say so with an
 * on/off toggle, and the header is exactly where that runs out of room.
 */
enum class ActionPlacement(val label: String) {
    BUTTON("כפתור"),
    MENU("בתפריט"),
    HIDDEN("מוסתר")
}

/**
 * Everything the player can offer, and where it sits unless the user moves it.
 *
 * The defaults reproduce the player as it already is, so nobody's screen
 * changes underneath them the first time they update - the new controls arrive
 * switched off, waiting to be asked for.
 */
enum class PlayerAction(
    val key: String,
    val label: String,
    val about: String,
    val default: ActionPlacement
) {
    QUEUE("queue", "תור ההשמעה", "מה מתנגן אחר כך", ActionPlacement.BUTTON),
    SLEEP("sleep", "טיימר שינה", "עצירה אוטומטית אחרי זמן", ActionPlacement.BUTTON),
    LIKE("like", "לייק ודיסלייק", "מלמד את האלגוריתם", ActionPlacement.BUTTON),
    MIX("mix", "צור מיקס מהשיר", "רשימה סביב השיר הזה", ActionPlacement.BUTTON),
    RADIO("radio", "התחל רדיו", "השמעה אינסופית מהשיר", ActionPlacement.BUTTON),
    LYRICS("lyrics", "מילות השיר", "נגללות עם הזמן כשיש קובץ LRC", ActionPlacement.MENU),
    RATING("rating", "דירוג בכוכבים", "שורת הכוכבים מתחת לשם", ActionPlacement.BUTTON),
    ADD_TO_PLAYLIST("playlist", "הוספה לרשימה", "", ActionPlacement.MENU),
    DETAILS("details", "פרטי השיר", "קובץ, קצב, סולם ומיקום בדיסק", ActionPlacement.MENU),
    WHY("why", "למה זה הומלץ", "פירוק הניקוד שהמנוע נתן", ActionPlacement.MENU),
    SPEED("speed", "מהירות הפעלה", "האטה והאצה בלי שינוי גובה", ActionPlacement.HIDDEN),
    SEEK("seek", "הרצה קדימה ואחורה", "קפיצה של עשר שניות", ActionPlacement.HIDDEN),
    EQUALIZER("equalizer", "אקולייזר", "31 התדרים, ישירות מהנגן", ActionPlacement.BUTTON),
    BOOKMARK("bookmark", "סימניות", "סימון מקום בהקלטה וחזרה אליו", ActionPlacement.MENU),
    SHARE("share", "שיתוף", "שליחת קובץ השיר לאפליקציה אחרת", ActionPlacement.MENU),
    DELETE("delete", "מחיקת הקובץ", "מוחק מהמכשיר, לא רק מהספרייה", ActionPlacement.HIDDEN);

    companion object {
        fun placementOf(stored: Map<String, String>, action: PlayerAction): ActionPlacement =
            stored[action.key]
                ?.let { name -> ActionPlacement.entries.firstOrNull { it.name == name } }
                ?: action.default
    }
}
