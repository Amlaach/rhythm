package com.elchanan.rhythm.engine

import java.util.Locale

/**
 * Styles for artists the app ships knowing about.
 *
 * Learning needs labelled examples, and the person who has to supply them is
 * the one person least able to: knowing that one singer is חסידי and another
 * is פופ ישראלי is an editor's job, not a listener's. Someone opening the app
 * for the first time has a library, no tags, and no way to start.
 *
 * So a small catalogue ships with the app. An artist in it and in the library
 * gets a style without anyone typing one; an artist in it and not in the
 * library costs nothing and waits. Nothing here is written to the database -
 * it is consulted when the library is read, so a correction always wins and a
 * later version's catalogue arrives without a migration.
 *
 * Deliberately small. Every entry is an artist whose style is not in doubt;
 * anything uncertain was left out rather than guessed at, because a wrong
 * seed is not a missing tag - it is a false training example, and everything
 * learned from it is wrong in the same direction.
 *
 * Seeds are also chosen for being *typical* rather than interesting. A style
 * is defined here by what the model hears, so an artist who moves between
 * sounds teaches a blurred boundary even when the label is perfectly correct.
 */
object ArtistStyles {

    /**
     * One artist, under every spelling worth expecting.
     *
     * Aliases are not a nicety. A Hebrew name reaches a file in several
     * spellings - with and without the Yiddish ע, with an ASCII apostrophe or
     * a Hebrew geresh, transliterated into Latin - and [Names.normalizeKey]
     * keeps them apart, correctly, because it cannot know they are one person.
     * A catalogue without them matches almost nothing.
     */
    data class Seed(val name: String, val style: String, val aliases: List<String> = emptyList())

    const val HASIDIC = "חסידי"
    const val ISRAELI_POP = "פופ ישראלי"
    const val CANTORIAL = "חזנות"
    const val MIZRAHI = "מזרחי"

    /**
     * Its own bucket rather than a corner of the pop one.
     *
     * A rapped verse and a sung one are not the same sound - the tagging model
     * has separate classes for them and hears the difference plainly - so
     * filing rap under פופ ישראלי would teach a blurred boundary for the sake
     * of one fewer word.
     */
    const val HIP_HOP = "היפ הופ"

    val CATALOGUE: List<Seed> = listOf(
        // --- חסידי --------------------------------------------------------
        // Yiddish or Hebrew over a choir and a traditional arrangement. The
        // clearest bucket of the four to the ear, and the one the model found
        // מקהלה for on its own.
        Seed("מרדכי בן דוד", HASIDIC, listOf("MBD", "Mordechai Ben David")),
        Seed("אברהם פריד", HASIDIC, listOf("Avraham Fried", "אברהם פריעד", "Avrohom Fried")),
        Seed("מוטי שטיינמץ", HASIDIC, listOf("Motty Steinmetz", "מוטי שטיינמעץ", "מוטי שטינמץ")),
        Seed(
            "אהרל'ה סאמעט", HASIDIC,
            listOf("אהרלה סאמט", "אהרן סאמעט", "Aharale Samet", "Aharaleh Samet")
        ),
        Seed("שמילי אונגר", HASIDIC, listOf("Shmueli Ungar", "שמואלי אונגר", "שמילי אונגאר")),
        Seed("בערי וועבער", HASIDIC, listOf("Beri Weber", "ברי ובר", "בערי וועבר")),
        Seed("דדי גרוכר", HASIDIC, listOf("Dedi Graucher", "דדי")),
        Seed("לוי פאלקוביץ", HASIDIC, listOf("Levy Falkowitz", "לוי פאלקאוויטש", "לוי פלקוביץ")),
        Seed("יואלי דיקמן", HASIDIC, listOf("Yoely Dickman", "יואלי דיקמאן")),
        Seed("ישראל ויליגר", HASIDIC, listOf("Yisroel Williger", "ישראל וויליגער")),
        Seed("מנדי ורדיגר", HASIDIC, listOf("Mendy Werdyger", "מנדי ווערדיגער")),
        Seed("שלומי גרטנר", HASIDIC, listOf("Shloime Gertner", "שלמה גרטנר", "שלוימי גערטנער")),
        Seed("בני פרידמן", HASIDIC, listOf("Benny Friedman", "בעני פרידמן")),
        Seed("יעקב שוואקי", HASIDIC, listOf("Yaakov Shwekey", "שוואקי", "Shwekey", "יעקב שוועקי")),
        Seed("אברהם מרדכי ברגר", HASIDIC, listOf("Avremi Berger", "אברימי ברגר")),
        Seed("ליפא שמעלצר", HASIDIC, listOf("Lipa Schmeltzer", "ליפא שמלצר", "Lipa")),
        Seed("שלמה קרליבך", HASIDIC, listOf("Shlomo Carlebach", "רבי שלמה קרליבך", "קרליבך")),
        Seed("זאנוויל ויינברג", HASIDIC, listOf("Zanvil Weinberger", "זנוויל ויינברגר", "זאנוויל ווינבערגער")),
        Seed("נפתלי קמפה", HASIDIC, listOf("Naftali Kempeh", "נפתלי קעמפע", "נפתלי קמפא")),
        Seed("אוהד מושקוביץ", HASIDIC, listOf("Ohad Moskowitz", "אוהד", "Ohad")),
        Seed("שלמה שמחה", HASIDIC, listOf("Shloime Simcha", "שלוימי שמחה")),
        Seed("ברוך לוין", HASIDIC, listOf("Baruch Levine", "ברוך לעווין")),
        Seed("מרדכי שפירא", HASIDIC, listOf("Mordechai Shapiro", "מרדכי שפירו")),
        Seed("שמחה ליינר", HASIDIC, listOf("Simcha Leiner", "שמחה לינר")),
        Seed("יידל ורדיגר", HASIDIC, listOf("Yeedle", "יידל", "Yeedle Werdyger")),
        Seed("מקהלת ידידים", HASIDIC, listOf("Yedidim Choir", "ידידים", "מקהלת ידידים הראשית")),
        Seed("מקהלת שירה", HASIDIC, listOf("Shira Choir", "שירה כוייר")),
        Seed("מקהלת מלכות", HASIDIC, listOf("Malchus Choir", "מלכות")),

        // --- פופ ישראלי ---------------------------------------------------
        // Hebrew, a solo voice, guitar and studio production. Religious and
        // secular together on purpose: the split between them is in the words,
        // and the words are the one thing the model never hears.
        Seed("ישי ריבו", ISRAELI_POP, listOf("Ishay Ribo", "Yishai Ribo")),
        Seed("חנן בן ארי", ISRAELI_POP, listOf("Hanan Ben Ari")),
        Seed("יונתן רזאל", ISRAELI_POP, listOf("Yonatan Razel", "יונתן רזל")),
        Seed("אהרן רזאל", ISRAELI_POP, listOf("Aaron Razel", "אהרון רזאל", "אהרן רזל")),
        Seed("אביתר בנאי", ISRAELI_POP, listOf("Evyatar Banai", "אביתר באנאי", "Eviatar Banai")),
        Seed("עמיר דדון", ISRAELI_POP, listOf("Amir Dadon")),
        Seed("שולי רנד", ISRAELI_POP, listOf("Shuli Rand")),
        Seed("עקיבא", ISRAELI_POP, listOf("Akiva", "עקיבא תורג'מן")),
        Seed("נתן גושן", ISRAELI_POP, listOf("Nathan Goshen")),
        Seed("עידן עמדי", ISRAELI_POP, listOf("Idan Amedi")),
        Seed("מוש בן ארי", ISRAELI_POP, listOf("Mosh Ben Ari")),
        Seed("הראל סקעת", ISRAELI_POP, listOf("Harel Skaat")),
        Seed("שלמה ארצי", ISRAELI_POP, listOf("Shlomo Artzi")),
        Seed("שלום חנוך", ISRAELI_POP, listOf("Shalom Hanoch")),
        Seed("אריק איינשטיין", ISRAELI_POP, listOf("Arik Einstein", "אריק אינשטיין")),
        Seed("מאיר בנאי", ISRAELI_POP, listOf("Meir Banai", "מאיר באנאי")),
        Seed("אהוד בנאי", ISRAELI_POP, listOf("Ehud Banai", "אהוד באנאי")),
        Seed("יהודה פוליקר", ISRAELI_POP, listOf("Yehuda Poliker")),
        Seed("ברי סחרוף", ISRAELI_POP, listOf("Berry Sakharof", "ברי סחרוב")),
        Seed("מאיר אריאל", ISRAELI_POP, listOf("Meir Ariel")),
        Seed("רמי קלינשטיין", ISRAELI_POP, listOf("Rami Kleinstein")),
        Seed("ריטה", ISRAELI_POP, listOf("Rita")),
        Seed("אביב גפן", ISRAELI_POP, listOf("Aviv Geffen")),
        Seed("מוקי", ISRAELI_POP, listOf("Muki", "דניאל נידרמאיר")),
        Seed("שלמה גרוניך", ISRAELI_POP, listOf("Shlomo Gronich")),
        Seed("קורין אלאל", ISRAELI_POP, listOf("Corinne Allal")),
        Seed("יאיר לוי", ISRAELI_POP, listOf("Yair Levi")),
        Seed("זושא", ISRAELI_POP, listOf("Zusha")),
        Seed("שוטי הנבואה", ISRAELI_POP, listOf("Shotei Hanevua")),
        // Secular and mainstream. The bucket is a sound, not an outlook, and
        // these sit in the same one as everything above: Hebrew, a solo voice,
        // guitar or keys, and a studio behind it.
        Seed("נועה קירל", ISRAELI_POP, listOf("Noa Kirel")),
        Seed("שירי מימון", ISRAELI_POP, listOf("Shiri Maimon")),
        Seed("נטע ברזילי", ISRAELI_POP, listOf("Netta Barzilai", "נטע")),
        Seed("סטטיק ובן אל", ISRAELI_POP, listOf("Static & Ben El", "סטטיק ובן אל תבורי")),
        Seed("עברי לידר", ISRAELI_POP, listOf("Ivri Lider")),
        Seed("שלומי שבן", ISRAELI_POP, listOf("Shlomi Shaban")),
        Seed("מרינה מקסימיליאן", ISRAELI_POP, listOf("Marina Maximilian", "מרינה מקסימיליאן בלומין")),
        Seed("אסף אמדורסקי", ISRAELI_POP, listOf("Assaf Amdursky")),
        Seed("דודו טסה", ISRAELI_POP, listOf("Dudu Tassa")),
        Seed("דיוויד ברוזה", ISRAELI_POP, listOf("David Broza", "דויד ברוזה")),
        Seed("ארקדי דוכין", ISRAELI_POP, listOf("Arkadi Duchin")),
        Seed("רמי פורטיס", ISRAELI_POP, listOf("Rami Fortis")),
        Seed("יהורם גאון", ISRAELI_POP, listOf("Yehoram Gaon")),
        Seed("חוה אלברשטיין", ISRAELI_POP, listOf("Chava Alberstein", "חווה אלברשטיין")),
        Seed("מתי כספי", ISRAELI_POP, listOf("Matti Caspi")),
        Seed("יוני רכטר", ISRAELI_POP, listOf("Yoni Rechter")),
        Seed("דני סנדרסון", ISRAELI_POP, listOf("Danny Sanderson")),
        Seed("גידי גוב", ISRAELI_POP, listOf("Gidi Gov")),
        Seed("שלמה יידוב", ISRAELI_POP, listOf("Shlomo Ydov")),
        Seed("כוורת", ISRAELI_POP, listOf("Kaveret", "פוגי")),
        Seed("משינה", ISRAELI_POP, listOf("Mashina")),
        Seed("אתניקס", ISRAELI_POP, listOf("Ethnix")),
        Seed("טיפקס", ISRAELI_POP, listOf("Teapacks", "טיפקס")),

        // --- מזרחי ---------------------------------------------------------
        // The ornamented vocal line and its instrumentation, which is what
        // separates this from the bucket above rather than anything lyrical.
        Seed("חיים ישראל", MIZRAHI, listOf("Haim Israel")),
        Seed("אייל גולן", MIZRAHI, listOf("Eyal Golan")),
        Seed("משה פרץ", MIZRAHI, listOf("Moshe Peretz")),
        Seed("שלומי שבת", MIZRAHI, listOf("Shlomi Shabat")),
        Seed("עומר אדם", MIZRAHI, listOf("Omer Adam")),
        Seed("שרית חדד", MIZRAHI, listOf("Sarit Hadad")),
        Seed("זהר ארגוב", MIZRAHI, listOf("Zohar Argov", "זוהר ארגוב")),
        Seed("איתי לוי", MIZRAHI, listOf("Itay Levy")),
        Seed("קובי פרץ", MIZRAHI, listOf("Kobi Peretz")),
        Seed("ליאור נרקיס", MIZRAHI, listOf("Lior Narkis")),
        Seed("דודו אהרון", MIZRAHI, listOf("Dudu Aharon")),
        Seed("שלומי סרנגה", MIZRAHI, listOf("Shlomi Saranga")),
        Seed("בניה ברבי", MIZRAHI, listOf("Benaia Barabi", "בניה ברעבי")),
        Seed("אמיר בניון", MIZRAHI, listOf("Amir Benayoun")),
        Seed("עופר לוי", MIZRAHI, listOf("Ofer Levi")),
        Seed("חיים משה", MIZRAHI, listOf("Haim Moshe")),
        Seed("אביהו מדינה", MIZRAHI, listOf("Avihu Medina")),
        Seed("מרגלית צנעני", MIZRAHI, listOf("Margalit Tzanani", "מרגול")),
        Seed("אבנר גדסי", MIZRAHI, listOf("Avner Gadassi")),
        Seed("נסרין קדרי", MIZRAHI, listOf("Nasrin Kadri")),
        Seed("מאור אדרי", MIZRAHI, listOf("Maor Edri")),
        Seed("עדן בן זקן", MIZRAHI, listOf("Eden Ben Zaken")),
        Seed("ליאור אלמליח", MIZRAHI, listOf("Lior Elmaliach")),
        Seed("ששי קשת", MIZRAHI, listOf("Sasi Keshet")),
        Seed("פאר טסי", MIZRAHI, listOf("Peer Tasi")),
        Seed("עדן חסון", MIZRAHI, listOf("Eden Hason")),
        Seed("אושר כהן", MIZRAHI, listOf("Osher Cohen")),
        Seed("מאיה בוסקילה", MIZRAHI, listOf("Maya Buskila")),
        Seed("דיקלה", MIZRAHI, listOf("Dikla")),

        // --- היפ הופ -------------------------------------------------------
        // Rapped rather than sung, over programmed drums. The one bucket here
        // the tagging model already has its own classes for.
        Seed("הדג נחש", HIP_HOP, listOf("Hadag Nahash", "הדג נחש")),
        Seed("סאבלימינל", HIP_HOP, listOf("Subliminal", "סובלימינל")),
        Seed("טונה", HIP_HOP, listOf("Tuna")),
        Seed("רביד פלוטניק", HIP_HOP, listOf("Ravid Plotnik", "נצ'י נצ'", "Nechi Nech")),
        Seed("שאנן סטריט", HIP_HOP, listOf("Shaanan Streett")),
        Seed("קפה שחור חזק", HIP_HOP, listOf("Cafe Shahor Hazak")),
        Seed("ג'ימבו ג'יי", HIP_HOP, listOf("Jimbo J", "גימבו גיי")),
        Seed("לוקץ'", HIP_HOP, listOf("Lukach", "לוקץ")),
        Seed("פלד", HIP_HOP, listOf("Peled")),

        // --- חזנות ---------------------------------------------------------
        // Acoustically the most distinct of the four, and the one where old
        // recordings help rather than hurt: the room and the range are the
        // signature.
        Seed("יצחק מאיר הלפגוט", CANTORIAL, listOf("Yitzchak Meir Helfgot", "חזן הלפגוט", "Helfgot")),
        Seed("יוסלה רוזנבלט", CANTORIAL, listOf("Yossele Rosenblatt", "יוסעלע ראזענבלאט")),
        Seed("יעקב למר", CANTORIAL, listOf("Yaakov Lemmer", "יעקב לעמער")),
        Seed("משה קוסביצקי", CANTORIAL, listOf("Moshe Koussevitzky", "משה קוסעוויצקי")),
        Seed("דוד ורדיגר", CANTORIAL, listOf("David Werdyger", "דוד ווערדיגער")),
        Seed("בנציון מילר", CANTORIAL, listOf("Benzion Miller", "בן ציון מילר")),
        Seed("נפתלי הרשטיק", CANTORIAL, listOf("Naftali Herstik", "נפתלי הערשטיק")),
        Seed("מוישה אוישר", CANTORIAL, listOf("Moishe Oysher", "משה אוישר")),
        Seed("זבולון קוורטין", CANTORIAL, listOf("Zavel Kwartin", "זבולון קווארטין")),
        Seed("ליב גלאנץ", CANTORIAL, listOf("Leib Glantz", "לייב גלאנץ")),
        Seed("משה שטרן", CANTORIAL, listOf("Moshe Stern", "משה שטערן"))
    )

    /**
     * A key forgiving enough to match a catalogue entry to a real file.
     *
     * Separate from [Names.normalizeKey] on purpose, and never a replacement
     * for it: that key is what the whole library is grouped by, and loosening
     * it would re-file every artist anyone owns. This one is used for a single
     * lookup and nothing else, so it can afford to be blunt.
     *
     * Whitespace goes entirely rather than being collapsed, which is what puts
     * "בן דוד", "בן־דוד" and "בןדוד" together; the Hebrew geresh and
     * gershayim go too, since normalizeKey removes the ASCII apostrophe but
     * not its Hebrew twin, and the same singer is written both ways.
     */
    fun matchKey(raw: String): String =
        STRIP.replace(raw.lowercase(Locale.ROOT), "")

    private val STRIP = Regex("[\\u0591-\\u05C7\\u05F3\\u05F4\\p{Punct}\\s]+")

    private val byKey: Map<String, String> = HashMap<String, String>().apply {
        for (seed in CATALOGUE) {
            for (name in listOf(seed.name) + seed.aliases) {
                val key = matchKey(name)
                if (key.isNotEmpty()) put(key, seed.style)
            }
        }
    }

    /**
     * The style this artist is known for, or null for anyone not in the
     * catalogue - which is almost everyone, and is the normal case.
     */
    fun styleFor(artistName: String): String? {
        if (artistName.isBlank()) return null
        // The performer, not the collaboration: a track credited to two
        // singers is filed under the first everywhere else in the app.
        return byKey[matchKey(Names.primaryArtist(artistName))]
            ?: byKey[matchKey(artistName)]
    }

    /** Every style the catalogue can supply, for a screen that lists them. */
    val STYLES: List<String> = CATALOGUE.map { it.style }.distinct().sorted()
}
