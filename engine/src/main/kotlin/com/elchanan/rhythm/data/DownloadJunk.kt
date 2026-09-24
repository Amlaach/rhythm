package com.elchanan.rhythm.data

import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.engine.Names

/**
 * What download sites and channels stamp on every file, taken back out.
 *
 * A song downloaded from a site comes back named after the site as well as
 * the song: "תוכו רצוף אהבה - חדשות המוזיקה להורדה", an artist field of
 * "ישי ריבו | www.site.co.il", an album that is the site's name for every
 * file it ever served - which makes one "album" of hundreds of songs by
 * everybody. None of it is the song.
 *
 * Two kinds of evidence. The first is words that are never a song's name
 * in any library: a web address, "להורדה", "MP3", "Official Video". The
 * second is learned from the library itself, because every site signs its
 * files differently and no list can know them all: a phrase that sits
 * beside the song's name in many songs, by several different artists, is
 * the signature of wherever they came from.
 *
 * Learned carefully, because the same shape fits real things. A song's own
 * name is sung by many artists - "אדון עולם", "לכה דודי" - so a phrase that
 * is ever the whole of a song's name is never junk. An artist's name is
 * never junk. And the words other parts of the app read a title for -
 * מחרוזת, live, ניגון, שיעור - are left where they are.
 */
class DownloadJunk private constructor(private val learned: Set<String>) {

    private val learnedPatterns: List<Regex> = learned.map { wholeWords(it) }

    /** [text] without anything a download added; [text] itself when nothing is left. */
    fun clean(text: String): String = strip(text).ifBlank { text.trim() }

    /** Whether [text] is nothing but what a download added: a site's name as an album. */
    fun isAllJunk(text: String): Boolean = text.isNotBlank() && strip(text).isBlank()

    private fun strip(text: String): String {
        val original = text.trim()
        var out = TagFixer.normalize(original)
        for (pattern in KNOWN) out = pattern.replace(out, " ")
        // A learned phrase as a whole part of the text: between separators or brackets.
        out = BRACKET.replace(out) { m -> if (isJunk(m.groupValues[1])) " " else m.value }
        val parts = SEPARATOR.split(out)
        if (parts.any { isJunk(it) }) {
            out = parts.filterNot { isJunk(it) }.filter { it.isNotBlank() }.joinToString(" - ")
        }
        // And written on without a separator: "... חדשות המוזיקה להורדה".
        for (pattern in learnedPatterns) out = pattern.replace(out, " ")
        return TIDY.replace(out.replace(Regex("\\s+"), " "), "").trim()
    }

    private fun isJunk(part: String): Boolean {
        val key = Names.normalizeKey(part)
        if (key == "unknown") return part.isNotBlank() && part.none { it.isLetterOrDigit() }
        return key in learned || KNOWN.any { it.matches(part.trim()) }
    }

    /** The learned phrases, for the screen that lists them. */
    val phrases: Set<String> get() = learned

    companion object {

        /** Word edges that know Hebrew letters: Java's \b does not. */
        private const val A = """(?<![\p{L}\p{N}])"""
        private const val Z = """(?![\p{L}\p{N}])"""

        /** Never a song's name in any library. */
        private val KNOWN = listOf(
            // web addresses and handles
            Regex("""(https?:[/][/])?(www\.)?[\w\-]+(\.[\w\-]+)*\.(co\.il|org\.il|net\.il|com|net|org|info|biz|me|tv|fm|io|xyz|site|online|top)$Z\S*""", RegexOption.IGNORE_CASE),
            Regex("""(?<![\w@])[@][A-Za-z0-9_.]{3,}"""),
            // downloading
            Regex("""$A(שיר\s+חדש\s+)?(להאזנה\s+ו?ל?הורדה|להורדה\s+חינם|הורדה\s+חינם|להורדה|להאזנה)(\s+ב?mp3)?$Z""", RegexOption.IGNORE_CASE),
            Regex("""$A(free\s+download|download|mp3|320\s*kbps|kbps)$Z""", RegexOption.IGNORE_CASE),
            // what a video calls itself
            Regex("""$A(official\s+(music\s+|lyric\s+)?(video|audio|clip)|lyric\s+video|lyrics|video\s+clip|hd|hq|4k|1080p|720p)$Z""", RegexOption.IGNORE_CASE),
            Regex("""$A(הקליפ\s+הרשמי|קליפ\s+רשמי|הסינגל\s+החדש|סינגל\s+חדש|בלעדי|exclusive)$Z""", RegexOption.IGNORE_CASE)
        )

        private val SEPARATOR = Regex("""\s+[-–—|~•]\s+|\s*[|｜•]\s*""")
        private val BRACKET = Regex("""[(\[{]([^)\]}]*)[)\]}]""")
        private val TIDY = Regex("""^[\s\-–—|,:~•]+|[\s\-–—|,:~•]+$|[(\[{]\s*[)\]}]""")

        /** Words other parts of the app read a title for; a phrase holding one is kept. */
        private val MEANINGFUL = setOf(
            "מחרוזת", "מחרוזות", "medley", "live", "לייב", "הופעה", "ניגון", "ניגונים", "remix", "רמיקס",
            "cover", "קאבר", "ווקאלי", "ווקאלית", "אקפלה", "acapella", "שיעור", "הרצאה", "פרק", "חלק",
            "גרסה", "גרסת", "version", "acoustic", "אקוסטי", "אקוסטית", "unplugged", "feat", "ft", "עם", "מארח"
        )

        /** How often, and across how many artists, a phrase must recur to be learned. */
        private const val MIN_SONGS = 4
        private const val MIN_ARTISTS = 3

        internal fun wholeWords(phrase: String): Regex =
            Regex("(?<![\\p{L}\\p{N}])" + phrase.split(' ').joinToString("[\\s\\p{P}]+") { Regex.escape(it) } + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)

        /** Learns this library's signatures from its own titles and artists. */
        fun learn(songs: List<SongEntity>): DownloadJunk {
            val artistKeys = HashSet<String>()
            for (s in songs) {
                artistKeys.add(s.artistKey)
                for (c in Names.credits(s.artistName)) artistKeys.add(Names.normalizeKey(c))
            }
            // Every phrase that is the whole of some song's name, once its
            // brackets and artist are set aside: those are songs, not signatures.
            val wholeTitles = HashSet<String>()
            val seenIn = HashMap<String, MutableSet<Long>>()
            val byArtists = HashMap<String, MutableSet<String>>()
            for (s in songs) {
                val title = BRACKET.replace(TagFixer.normalize(s.title), " ")
                val titleParts = SEPARATOR.split(title).map { it.trim() }.filter { it.isNotEmpty() }
                val named = titleParts.filterNot { Names.normalizeKey(it) in artistKeys }
                if (named.size == 1) wholeTitles.add(Names.normalizeKey(named[0]))
                // Titles and artists only. An album shared by many artists is
                // as often a real compilation as a site's name, so albums are
                // cleaned of what is learned here but teach nothing.
                for (field in listOf(s.title, s.artistName)) {
                    val text = TagFixer.normalize(field)
                    val parts = SEPARATOR.split(BRACKET.replace(text) { " | " + it.groupValues[1] + " | " }) +
                        BRACKET.findAll(text).map { it.groupValues[1] }
                    for (part in parts.toSet()) {
                        val key = Names.normalizeKey(part)
                        if (key == "unknown" || key.split(' ').size < 2) continue
                        seenIn.getOrPut(key) { HashSet() }.add(s.id)
                        byArtists.getOrPut(key) { HashSet() }.add(s.artistKey)
                    }
                }
            }
            val learned = seenIn.keys.filterTo(HashSet()) { key ->
                (seenIn[key]?.size ?: 0) >= MIN_SONGS &&
                    (byArtists[key]?.size ?: 0) >= MIN_ARTISTS &&
                    key !in wholeTitles &&
                    key !in artistKeys &&
                    key.split(' ').none { it in MEANINGFUL }
            }
            return DownloadJunk(learned)
        }
    }
}
