package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.db.ArtistEntity
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.data.db.SongStatsEntity
import org.junit.Assert.*
import org.junit.Test

/**
 * A style that mixes only with itself: "English with English, and that is all".
 *
 * Reported as the app still mixing English with things never set up to be
 * mixed with it. A rule could keep two named styles apart and said nothing
 * about the rest - every untagged song and every style nobody had thought to
 * pair it with.
 */
class IsolatedStyleTest {

    private val rule = Styles.Separations.parse("אנגלית")

    @Test fun oneStyleOnALineKeepsItToItself() {
        assertTrue(rule.clash(listOf("אנגלית"), emptyList()))
        assertTrue(rule.clash(emptyList(), listOf("אנגלית")))
        assertTrue(rule.clash(listOf("אנגלית"), listOf("חסידי")))
        assertFalse(rule.clash(listOf("אנגלית"), listOf("אנגלית", "קצבי")))
        assertFalse("untagged with untagged is not this rule's business", rule.clash(emptyList(), emptyList()))
        assertFalse("and nothing else is affected", rule.clash(listOf("חסידי"), emptyList()))
    }

    @Test fun onlyReadsTheSame() {
        val only = Styles.Separations.parse("רק אנגלית")
        assertTrue(only.clash(listOf("אנגלית"), emptyList()))
    }

    @Test fun pairRulesStillWorkAlongside() {
        val both = Styles.Separations.parse("אנגלית\nחסידי, ישראלי")
        assertTrue(both.clash(listOf("חסידי"), listOf("ישראלי")))
        assertFalse(both.clash(listOf("חסידי"), emptyList()))
        assertTrue(both.clash(listOf("אנגלית"), listOf("ישראלי")))
    }

    private fun song(id: Long, artist: String) = SongEntity(
        id, "שיר $id", "שיר $id", artist, Names.normalizeKey(artist), "al${id / 3}", id / 3,
        240_000, 1, 2020, null, "/m/$id", "/m/$artist", 1_600_000_000L, 1
    )

    private val english = listOf("Adele", "Coldplay", "Sia")
    private val others = listOf("אהרלה", "מוטי", "ישי", "פלוני", "אלמוני")
    private val songs = buildList {
        var id = 1L
        for (a in english + others) repeat(10) { add(song(id++, a)) }
    }
    private val artists = (english.map { it to "אנגלית" } + listOf("אהרלה" to "חסידי", "מוטי" to "חסידי"))
        .associate { (name, style) ->
            Names.normalizeKey(name) to ArtistEntity(Names.normalizeKey(name), name, styles = style)
        }
    private fun isEnglish(s: SongEntity) = artists[s.artistKey]?.styles == "אנגלית"

    // Some listening on both sides, so the shelves have something to rank.
    private val listening = songs.filter { it.id % 3 == 0L }.associate { it.id to SongStatsEntity(it.id, playCount = 3, liked = 1) }

    private fun engine(separations: String, stats: Map<Long, SongStatsEntity> = listening) = Recommender(
        songs,
        stats,
        artists, emptyMap(), emptyMap(), emptyMap(), null,
        EngineTuning(separations = separations), 1_750_000_000_000L, 5L
    )

    private fun generated(e: Recommender): List<Pair<String, List<SongEntity>>> {
        val reporting = setOf("נוספו לאחרונה למכשיר", "מהספרייה שלך", "חיוג מהיר")
        return e.buildFeed().flatMap { section ->
            buildList {
                if (section.songs.isNotEmpty()) add(section.title to section.songs)
                for (m in section.mixes) add(m.title to m.songs)
            }
        }.filter { it.first !in reporting } +
            listOf("רדיו" to e.radio(songs.first { isEnglish(it) }, 30)) +
            listOf("רדיו לא מתויג" to e.radio(songs.first { it.artistName == "פלוני" }, 30))
    }

    @Test fun noGeneratedListMixesEnglishWithAnythingElse() {
        for ((title, list) in generated(engine("אנגלית"))) {
            val mixed = list.any { isEnglish(it) } && list.any { !isEnglish(it) }
            assertFalse("\"$title\" mixes English with other songs", mixed)
        }
    }

    /**
     * "שמח" typed on one English song says how it feels, not that it stopped
     * being English. It used to replace the artist's tag outright, and that
     * one song then went into every mix the rule kept English out of.
     */
    @Test fun aSongTaggedByHandKeepsItsArtistsGenreForTheRule() {
        val tagged = songs.filter { isEnglish(it) }.take(4)
        val stats = listening + tagged.associate {
            it.id to (listening[it.id] ?: SongStatsEntity(it.id)).copy(styles = "שמח", playCount = 6, liked = 1)
        }
        val e = engine("אנגלית", stats)
        for ((title, list) in generated(e)) {
            val mixed = list.any { isEnglish(it) } && list.any { !isEnglish(it) }
            assertFalse("\"$title\" mixes English with other songs", mixed)
        }
        // A genre typed on the song still overrides the artist's.
        val retagged = tagged.first()
        val own = engine("אנגלית", stats + (retagged.id to stats.getValue(retagged.id).copy(styles = "חסידי")))
        assertTrue(own.radio(retagged, 30).drop(1).none { isEnglish(it) })
    }

    @Test fun withoutTheRuleTheyDoMix() {
        // Otherwise the guard above could be passing on shelves that happen
        // to come out single-style.
        assertTrue(generated(engine("")).any { (_, list) ->
            list.any { isEnglish(it) } && list.any { !isEnglish(it) }
        })
    }
}
