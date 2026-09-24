package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.db.SongEntity
import java.util.Locale

/**
 * Hebrew spellings for names written in Latin letters: "Naftali Kempeh" is
 * נפתלי קמפה, "Korveini Eilecho" is קרבני אליך.
 *
 * Not translation. The names are Hebrew or Yiddish to begin with and only
 * their spelling came out in English letters, which is what [Transliteration]
 * already sees when it matches the two spellings of one artist. This runs the
 * same idea the other way, and only offers what it finds - a song whose real
 * name is English stays English unless the listener says otherwise.
 *
 * Two sources, the stronger first:
 *  - the library itself. When the same artist or the same song is also there
 *    in Hebrew, that spelling is the one to offer, exactly as it is written.
 *  - word by word, from the Hebrew words the library already uses and a
 *    short dictionary of the words Jewish music is made of. Every word has to
 *    be recognised, or nothing is offered: half a Hebrew name is worse than
 *    the English one.
 */
object HebrewSpelling {

    enum class Field { ARTIST, TITLE }

    /**
     * One offer: [songIds] carry [latin] in [field], and [hebrew] is how it
     * would be spelled. [fromLibrary] when the spelling was found written
     * that way elsewhere in the library rather than put together word by word.
     */
    data class Suggestion(
        val field: Field,
        val latin: String,
        val hebrew: String,
        val songIds: List<Long>,
        val fromLibrary: Boolean
    )

    fun suggest(songs: List<SongEntity>): List<Suggestion> {
        val vocabulary = Vocabulary(songs)
        val out = ArrayList<Suggestion>()

        // Artists: one offer per spelling, covering every song that carries it.
        val hebrewArtists = songs.map { it.artistName.trim() }
            .filter { Transliteration.hasHebrew(it) }
            .groupingBy { it }.eachCount()
        songs.filter { latinOnly(it.artistName) }
            .groupBy { it.artistName.trim() }
            .forEach { (latin, carriers) ->
                val fromLibrary = hebrewArtists.entries
                    .filter { Transliteration.sameName(it.key, latin) }
                    .maxByOrNull { it.value }?.key
                val hebrew = fromLibrary ?: spellCredits(latin, vocabulary)
                if (hebrew != null && hebrew != latin) {
                    out.add(Suggestion(Field.ARTIST, latin, hebrew, carriers.map { it.id }, fromLibrary != null))
                }
            }

        // Titles: one offer per song. The same song in Hebrew elsewhere in the
        // library is looked up by its consonants, which both spellings keep.
        val hebrewTitles = HashMap<String, MutableList<SongEntity>>()
        for (s in songs) {
            val main = mainPart(s.title).first
            if (Transliteration.hasHebrew(main)) {
                hebrewTitles.getOrPut(canonicalHebrew(main)) { ArrayList() }.add(s)
            }
        }
        for (s in songs) {
            if (!latinOnly(s.title)) continue
            val (main, rest) = mainPart(s.title)
            if (main.isBlank()) continue
            val twin = latinKeys(main).asSequence()
                .flatMap { hebrewTitles[it].orEmpty().asSequence() }
                .filter { Transliteration.sameName(mainPart(it.title).first, main) }
                // The same artist's copy first, then anybody's.
                .sortedByDescending { if (it.artistKey == s.artistKey) 1 else 0 }
                .firstOrNull()
            val fromLibrary = twin?.let { mainPart(it.title).first.trim() }
            val hebrew = fromLibrary ?: spellPhrase(main, vocabulary) ?: continue
            val full = (hebrew + rest).trim()
            if (full != s.title.trim()) out.add(Suggestion(Field.TITLE, s.title, full, listOf(s.id), fromLibrary != null))
        }
        return out
    }

    /** Latin letters and no Hebrew: the names this is for. */
    private fun latinOnly(text: String) = Transliteration.hasLatin(text) && !Transliteration.hasHebrew(text)

    /** The name without a trailing bracket - "(Live)", "[Acapella]" - which is kept as it is. */
    private fun mainPart(title: String): Pair<String, String> {
        val i = title.indexOfFirst { it == '(' || it == '[' }
        return if (i <= 0) title.trim() to "" else title.substring(0, i).trim() to " " + title.substring(i).trim()
    }

    /** An artist field, a credit at a time: "A & B" keeps its "&". */
    private fun spellCredits(raw: String, vocabulary: Vocabulary): String? {
        val parts = CREDIT_SEPARATOR.split(raw)
        val separators = CREDIT_SEPARATOR.findAll(raw).map { it.value }.toList()
        val spelled = parts.map { spellPhrase(it, vocabulary) ?: return null }
        return buildString {
            spelled.forEachIndexed { i, p ->
                append(p)
                if (i < separators.size) append(separators[i])
            }
        }
    }

    private val CREDIT_SEPARATOR = Regex("""\s*(,|&|\+|\bx\b|\bfeat\.?|\bft\.?)\s*""", RegexOption.IGNORE_CASE)

    /** A whole name, or null when any word of it is not recognised. */
    internal fun spellPhrase(text: String, vocabulary: Vocabulary? = null): String? {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        PHRASES[words.joinToString(" ") { normal(it) }]?.let { return it }
        val out = words.map { spellWord(it, vocabulary) ?: return null }
        return out.joinToString(" ")
    }

    private fun normal(word: String) = word.lowercase(Locale.ROOT).filter { it.isLetter() }

    private fun spellWord(raw: String, vocabulary: Vocabulary?): String? {
        // Punctuation around a word stays around it.
        val lead = raw.takeWhile { !it.isLetterOrDigit() }
        val tail = raw.takeLastWhile { !it.isLetterOrDigit() }
        val core = raw.substring(lead.length, raw.length - tail.length)
        if (core.isEmpty()) return raw
        if (core.all { it.isDigit() }) return raw
        val lower = core.lowercase(Locale.ROOT)
        // "Ke'malach", "B'simcha": a prefix letter written apart.
        APOSTROPHE_PREFIX.matchEntire(lower)?.let { m ->
            val letter = PREFIXES[m.groupValues[1]] ?: return@let
            val rest = resolve(m.groupValues[2].filter { it.isLetter() }, vocabulary) ?: return null
            return lead + letter + rest + tail
        }
        val word = lower.filter { it.isLetter() }
        resolve(word, vocabulary)?.let { return lead + it + tail }
        // "Hamikdash", "Vehi": a prefix letter written on.
        for ((prefix, letter) in PREFIXES) {
            if (prefix.length < 2 || !word.startsWith(prefix) || word.length - prefix.length < 3) continue
            val rest = resolve(word.substring(prefix.length), vocabulary) ?: continue
            return lead + letter + rest + tail
        }
        return null
    }

    private val APOSTROPHE_PREFIX = Regex("""([a-z]{1,3})['’`](.+)""")

    private fun resolve(word: String, vocabulary: Vocabulary?): String? =
        WORDS[word] ?: vocabulary?.find(word)

    /**
     * The Hebrew words the library already spells, by their consonants.
     *
     * A match has to be long enough to mean something - three consonants -
     * and clearly the most used of the words that share them: מלך and מלאך
     * have the same consonants, and a guess between the two is not offered.
     */
    class Vocabulary(songs: List<SongEntity>) {
        private val byKey = HashMap<String, HashMap<String, Int>>()

        init {
            for (s in songs) {
                for (field in listOf(s.title, s.artistName, s.albumName)) {
                    for (w in HEBREW_WORD.findAll(field)) {
                        val word = w.value
                        if (word.length < 2) continue
                        for (key in hebrewKeys(word)) {
                            byKey.getOrPut(key) { HashMap() }.merge(word, 1, Int::plus)
                        }
                    }
                }
            }
        }

        fun find(latin: String): String? {
            val found = HashMap<String, Int>()
            for (key in latinKeys(latin)) {
                if (key.length < 3) continue
                byKey[key]?.forEach { (w, n) -> found.merge(w, n, ::maxOf) }
            }
            val ranked = found.entries.sortedByDescending { it.value }
            val best = ranked.firstOrNull() ?: return null
            val next = ranked.getOrNull(1)
            return if (next == null || best.value >= next.value * 3) best.key else null
        }
    }

    private val HEBREW_WORD = Regex("""[\x{05D0}-\x{05EA}]+""")

    /**
     * The consonants both spellings keep, with the pairs a transliteration
     * writes either way made one: ח and כ are k, צ is s, ו-as-v is ב.
     */
    private fun canonical(skeleton: String): String = buildString {
        for (c in skeleton) {
            val d = when (c) {
                'X' -> 'K'
                'C' -> 'S'
                'V' -> 'B'
                'H' -> null
                else -> c
            }
            if (d != null && (isEmpty() || last() != d)) append(d)
        }
    }

    private fun canonicalHebrew(text: String) = canonical(Transliteration.hebrewSkeleton(text))

    /** A Hebrew word's keys: as written, and with ת read as s, as Ashkenazi Hebrew says it (בית, "beis"). */
    private fun hebrewKeys(word: String): Set<String> {
        val plain = canonicalHebrew(word)
        return if ('ת' in word) setOf(plain, canonicalHebrew(word.replace('ת', 'ס'))) else setOf(plain)
    }

    /** A Latin word's keys: a v may be ב or a ו the Hebrew side does not keep. */
    private fun latinKeys(text: String): Set<String> {
        val skeleton = Transliteration.latinSkeleton(text)
        return setOf(canonical(skeleton), canonical(skeleton.replace("V", "")))
    }

    /** Letters written on the front of a word. */
    private val PREFIXES = linkedMapOf(
        "she" to "ש", "ha" to "ה", "ve" to "ו", "be" to "ב", "le" to "ל", "ke" to "כ", "mi" to "מ", "me" to "מ",
        "de" to "ד", "sh" to "ש", "h" to "ה", "v" to "ו", "u" to "ו", "b" to "ב", "l" to "ל", "k" to "כ", "m" to "מ", "d" to "ד"
    )

    /** Whole names that are better known than their words. */
    private val PHRASES: Map<String, String> = table(
        "lecha dodi|lcha dodi|lecho dodi" to "לכה דודי",
        "adon olam|adon oilam|adon olom" to "אדון עולם",
        "shalom aleichem|sholom aleichem|shalom alechem|sholem aleichem" to "שלום עליכם",
        "avinu malkeinu|ovinu malkeinu|avinu malkenu" to "אבינו מלכנו",
        "am yisrael chai|am yisroel chai" to "עם ישראל חי",
        "ani maamin" to "אני מאמין",
        "hinei ma tov|hine ma tov|hineh ma tov" to "הנה מה טוב",
        "oseh shalom|ose shalom|oise sholom" to "עושה שלום",
        "eishes chayil|eshet chayil|eishes chayal" to "אשת חיל",
        "yedid nefesh" to "ידיד נפש",
        "kol haolam kulo" to "כל העולם כולו",
        "vehi sheamda|vhi sheamda" to "והיא שעמדה",
        "mode ani|modeh ani" to "מודה אני",
        "yibaneh hamikdash|yiboneh hamikdosh" to "יבנה המקדש",
        "tzama lecha nafshi|tzamah lecha nafshi" to "צמאה לך נפשי"
    )

    /**
     * The words Jewish music is made of, in the ways they are spelled in
     * English letters - Sephardi and Ashkenazi alike.
     */
    private val WORDS: Map<String, String> = table(
        "ki" to "כי", "shel" to "של", "al" to "על", "im" to "אם", "ein|eyn" to "אין", "od" to "עוד",
        "hu" to "הוא", "hi" to "היא", "ata|atah|ato" to "אתה", "ani" to "אני", "mi" to "מי", "ma|mah" to "מה",
        "gam" to "גם", "ze|zeh" to "זה", "kan" to "כאן", "am" to "עם", "yom" to "יום",
        "beis|beit|bet|bais|bayis|bayit" to "בית", "medrash|midrash|medresh|midrosh" to "מדרש",
        "mikdash|mikdosh" to "מקדש", "shalom|sholom|sholem" to "שלום",
        "aleichem|alechem|aleichim" to "עליכם", "adon" to "אדון", "olam|oilam|olom|olum" to "עולם",
        "dodi" to "דודי", "avinu|ovinu" to "אבינו", "malkeinu|malkenu" to "מלכנו",
        "yisrael|yisroel|israel|yisrol" to "ישראל", "chai" to "חי", "maamin" to "מאמין", "hashem" to "השם",
        "eretz|erets" to "ארץ", "yerushalayim|yerushalaim|yerusholayim|yerusholaim" to "ירושלים",
        "torah|toirah|toire|tora" to "תורה", "simcha|simchah|simcho" to "שמחה", "mazel|mazal" to "מזל",
        "tov" to "טוב", "neshama|neshamah|neshomo|neshome" to "נשמה", "lev" to "לב", "libi" to "ליבי",
        "ahava|ahavah|ahavo" to "אהבה", "ahavas|ahavat" to "אהבת", "emuna|emunah|emuno|emunoh" to "אמונה",
        "tefila|tefilah|tefillah|tefilla" to "תפילה", "niggun|nigun" to "ניגון", "niggunim|nigunim" to "ניגונים",
        "mashiach|moshiach|meshiach" to "משיח", "geula|geulah|geuloh|geulo" to "גאולה", "kulam" to "כולם",
        "shabbos|shabbat|shabes|shabbes" to "שבת", "rosh" to "ראש", "hashana|hashanah" to "השנה",
        "ima|imma" to "אמא", "abba|aba" to "אבא", "tatte|tatty|tate" to "טאטע", "ribono|ribbono|riboino" to "ריבונו",
        "shir" to "שיר", "shira|shirah" to "שירה", "hamaalos|hamaalot" to "המעלות", "ana" to "אנא",
        "bekoach|bkoach" to "בכח", "ashreinu" to "אשרינו", "modeh|mode" to "מודה", "yehi" to "יהי",
        "ratzon|rotzon" to "רצון", "rachem|racheim" to "רחם", "hashiveinu|hashivenu" to "השיבנו",
        "bilvavi" to "בלבבי", "mishkan" to "משכן", "evne|evneh" to "אבנה", "milvado" to "מלבדו",
        "shema|shma" to "שמע", "echad" to "אחד", "baruch|boruch" to "ברוך", "haba|habaa|habo" to "הבא",
        "malach|malech" to "מלאך", "melech|meilech|melekh" to "מלך", "becho|becha|bcha" to "בך",
        "botchu|batchu" to "בטחו", "eilecho|elecha|eilecha|elecho" to "אליך",
        "korveini|korveni|karveini|karveni" to "קרבני", "vonu|banu" to "בנו", "mangina|mangine" to "מנגינה",
        "tzadik|tzaddik" to "צדיק", "tzion|tziyon|zion" to "ציון", "nafshi" to "נפשי", "zemer" to "זמר",
        "hinei|hineh|hine" to "הנה", "chaim|chayim" to "חיים", "ochila|ochilah" to "אוחילה",
        "kadosh|kodosh|kadoish" to "קדוש", "tehilim|tehillim" to "תהילים", "zechor|zchor" to "זכור",
        "lemaan|lmaan" to "למען", "shuva|shuvah" to "שובה", "ir" to "עיר", "nachamu" to "נחמו",
        "yeled" to "ילד", "ben" to "בן", "bas|bat" to "בת",
        // names
        "avraham|avrohom|avrom|avrum" to "אברהם", "yitzchak|yitzchok" to "יצחק", "yaakov|yakov" to "יעקב",
        "moshe" to "משה", "david|dovid" to "דוד", "shlomo" to "שלמה", "mordechai|mordche|mordechay" to "מרדכי",
        "yossi|yosi" to "יוסי", "motty|motti|moti" to "מוטי", "benny|beni" to "בני", "naftali" to "נפתלי",
        "yonatan|yonasan" to "יונתן", "yishai|ishay" to "ישי", "chanan|hanan" to "חנן", "ari" to "ארי",
        "yehuda|yehudah" to "יהודה", "shmuel|shmiel" to "שמואל", "eli" to "אלי", "avi" to "אבי",
        "levi" to "לוי", "michael|michoel" to "מיכאל", "aharon|ahron" to "אהרן"
    )

    private fun table(vararg rows: Pair<String, String>): Map<String, String> = buildMap {
        for ((spellings, hebrew) in rows) for (s in spellings.split('|')) put(s, hebrew)
    }
}
