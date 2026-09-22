package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.db.AudioFeatureEntity
import com.elchanan.rhythm.data.db.SongEntity

/**
 * How well a measure of "sounds alike" actually groups a library by style.
 *
 * Every change to how songs are compared is a guess until something says
 * whether it helped. This is that something, and it runs on the listener's
 * own library, where the answer matters: for each song whose style is known,
 * take the songs it sounds most like - by other artists only, since a singer
 * sounds like himself and that proves nothing - and count how many share its
 * style. The same songs, the same labels, two measures side by side.
 *
 * Style comes from the user's own artist tags where they exist, and from the
 * catalogue built into the app where they do not. Only kinds of music count -
 * a word like "קצבי" is a character, and every style of music has fast songs.
 */
object SoundCheck {

    data class Result(
        /** Songs that had a style, a measurement and a print. */
        val songs: Int,
        val artists: Int,
        val styles: Int,
        /** Neighbours compared per song. */
        val neighbours: Int,
        /**
         * Labelled songs left out because their style belongs to one artist
         * only here. Neighbours come from other artists, so such a song has no
         * right answer to find; counting it scores zero for every measure and
         * drowns the comparison - which is how a first run read 2%, 2%, 1%.
         */
        val lonely: Int,
        /** What picking neighbours at random would have scored. */
        val chance: Double,
        /** The sound features the engine uses today. */
        val current: Double,
        /** YAMNet's sound print. */
        val print: Double
    )

    const val MIN_SONGS = 30
    private const val NEIGHBOURS = 5

    fun measure(
        songs: List<SongEntity>,
        features: Map<Long, AudioFeatureEntity>,
        stylesByArtist: Map<String, String> = emptyMap()
    ): Result? {
        val usable = features.values.filter { it.energy > 0f }
        if (usable.size < 8) return null
        val space = AcousticSpace(usable)

        val labelled = songs.mapNotNull { song ->
            val f = features[song.id] ?: return@mapNotNull null
            if (f.energy <= 0f || !space.has(song.id)) return@mapNotNull null
            val print = SoundPrint.unpack(f.soundPrint) ?: return@mapNotNull null
            val style = styleOf(song, stylesByArtist) ?: return@mapNotNull null
            Triple(song, style, print)
        }
        val artistsPerStyle = labelled.groupBy({ it.second }, { it.first.artistKey })
            .mapValues { (_, keys) -> keys.distinct().size }
        val all = labelled
        val scorable = all.filter { (artistsPerStyle[it.second] ?: 0) >= 2 }
        val lonely = all.size - scorable.size
        if (scorable.size < MIN_SONGS) return null
        val styles = scorable.map { it.second }.distinct()
        if (styles.size < 2) return null

        // Centred against every labelled song, and neighbours drawn from all of
        // them - a lonely style's songs are fair wrong answers - but only the
        // scorable ones are asked the question.
        val prints = SoundPrint.centred(all.associate { it.first.id to it.third })

        var chance = 0.0
        var current = 0.0
        var print = 0.0
        var counted = 0
        for ((song, style, _) in scorable) {
            val others = all.filter { it.first.artistKey != song.artistKey }
            if (others.size < NEIGHBOURS) continue
            val k = NEIGHBOURS
            chance += others.count { it.second == style }.toDouble() / others.size
            current += others
                .sortedByDescending { space.similarity(song.id, it.first.id) }
                .take(k).count { it.second == style }.toDouble() / k
            val mine = prints.getValue(song.id)
            print += others
                .sortedByDescending { SoundPrint.similarity(mine, prints.getValue(it.first.id)) }
                .take(k).count { it.second == style }.toDouble() / k
            counted++
        }
        if (counted == 0) return null
        return Result(
            songs = counted,
            artists = scorable.map { it.first.artistKey }.distinct().size,
            styles = styles.size,
            neighbours = NEIGHBOURS,
            lonely = lonely,
            chance = chance / counted,
            current = current / counted,
            print = print / counted
        )
    }

    /** A kind of music for this song's artist, or null. */
    private fun styleOf(song: SongEntity, stylesByArtist: Map<String, String>): String? {
        val typed = Styles.parse(stylesByArtist[song.artistKey].orEmpty())
            .firstOrNull { Styles.familyOf(it) == GENRE }
        return typed ?: ArtistStyles.styleFor(song.artistName)
    }

    private val GENRE: String = Styles.FAMILIES.keys.first()

    fun describe(r: Result?): String {
        if (r == null) {
            return "אין עדיין מספיק לבדוק: צריך לפחות $MIN_SONGS שירים שנותחו מחדש, " +
                "בשני סגנונות לפחות, כשלכל סגנון יש לפחות שני אמנים — " +
                "כי משווים כל שיר לשירים של אמנים אחרים. " +
                "אם הניתוח עוד רץ, כדאי לחכות שיסתיים."
        }
        fun pct(x: Double) = "${(x * 100).toInt()}%"
        return buildString {
            append("נבדקו ${r.songs} שירים של ${r.artists} אמנים ב-${r.styles} סגנונות. ")
            append("לכל שיר נלקחו ${r.neighbours} השירים שהכי נשמעים כמוהו, של אמנים אחרים, ")
            append("ונבדק כמה מהם באותו סגנון.\n")
            append("• מדידת הסאונד הנוכחית: ${pct(r.current)}\n")
            append("• טביעת הצליל החדשה: ${pct(r.print)}\n")
            append("• בחירה אקראית, להשוואה: ${pct(r.chance)}")
            if (r.lonely > 0) {
                append("\n\n${r.lonely} שירים לא נבדקו: הסגנון שלהם שייך כאן לאמן אחד בלבד, ")
                append("ואין להם שכן נכון לחפש אצל אמנים אחרים.")
            }
        }
    }
}
