package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.db.ArtistEntity
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.data.db.SongStatsEntity
import org.junit.Assert.*
import org.junit.Test

/**
 * The rules a generated shelf promised and did not keep.
 *
 * Every assertion here comes from one user's report of a library they had
 * spent real time tagging: the same song twice, one singer filling a row, two
 * styles mixed after they had been told not to, and an artist rating that
 * moved nothing. All four were true, and all four had the same shape - the
 * rule existed, and the code that filled a shelf was allowed to walk past it.
 */
class FeedRulesTest {

    private fun song(
        id: Long,
        title: String,
        artist: String,
        album: Long = id / 5,
        minutes: Int = 4
    ) = SongEntity(
        id = id, title = title, titleLower = title.lowercase(),
        artistName = artist, artistKey = Names.normalizeKey(artist),
        albumName = "album$album", albumId = album,
        durationMs = minutes * 60_000L, trackNumber = 1, year = 2020, genre = null,
        path = "/m/$artist/$title.mp3", folder = "/m/$artist",
        dateAddedSec = 1_700_000_000L, sizeBytes = 1000
    )

    /** Two styles the user separated, two singers each, plus one untagged singer. */
    private val cast = listOf(
        "אהרלה" to "חסידי", "מוטי" to "חסידי",
        "ישי" to "ישראלי", "חנן" to "ישראלי",
        "פלוני" to ""
    )

    private val songs: List<SongEntity> = buildList {
        var id = 1L
        for ((artist, _) in cast) {
            for (n in 1..12) add(song(id++, "$artist שיר $n", artist))
            // the same piece a second time, as a library of downloads holds it
            add(song(id++, "$artist שיר 1 (live)", artist))
        }
    }

    private val artistRows = cast.associate { (name, style) ->
        Names.normalizeKey(name) to ArtistEntity(
            artistKey = Names.normalizeKey(name), displayName = name,
            rating = if (style == "חסידי") 5 else 0, styles = style
        )
    }

    private fun engine(separations: String = "חסידי, ישראלי") = Recommender(
        songs = songs,
        stats = songs.filter { it.artistKey == Names.normalizeKey("ישי") }.take(3)
            .associate { it.id to SongStatsEntity(it.id, rating = 5, playCount = 4) },
        artists = artistRows,
        affinity = emptyMap(), transitions = emptyMap(), features = emptyMap(),
        acoustic = null,
        tuning = EngineTuning(separations = separations),
        now = System.currentTimeMillis(), feedSeed = 7L
    )

    /** Every list the feed offers, shelves and mixes alike. */
    private fun offeredLists(e: Recommender): List<Pair<String, List<SongEntity>>> =
        e.buildFeed().flatMap { section ->
            buildList {
                if (section.songs.isNotEmpty()) add(section.title to section.songs)
                for (mix in section.mixes) add(mix.title to mix.songs)
            }
        }

    @Test fun noGeneratedListOffersTheSamePieceTwice() {
        // The shelves that report rather than recommend are allowed to: they
        // show what is on the device, and hiding half of it would be worse.
        val reporting = setOf("נוספו לאחרונה למכשיר", "מהספרייה שלך", "חיוג מהיר")
        for ((title, list) in offeredLists(engine())) {
            if (title in reporting) continue
            val pieces = list.map { Versions.pieceKey(it.title) }
            assertEquals(
                "\"$title\" offers the same piece more than once",
                pieces.size, pieces.distinct().size
            )
        }
    }

    @Test fun oneSingerCannotFillAShelf() {
        // Lopsided on purpose. The per-artist cap held on the first pass and
        // was then abandoned by the pass that topped a short shelf up, so the
        // caps only ever bound when the library was wide enough not to need
        // them - which is to say, when they did not matter.
        var id = 1L
        val hoarded = (1..60).map { song(id++, "פריד שיר $it", "אברהם פריד") }
        val rest = listOf("שוואקי", "לעמער", "גרין", "וויליגער").flatMap { name ->
            (1..3).map { song(id++, "$name שיר $it", name) }
        }
        val e = Recommender(
            songs = hoarded + rest,
            stats = emptyMap(),
            artists = emptyMap(),
            affinity = emptyMap(), transitions = emptyMap(), features = emptyMap(),
            acoustic = null, tuning = EngineTuning(),
            now = System.currentTimeMillis(), feedSeed = 3L
        )
        for ((title, list) in offeredLists(e)) {
            if (list.size < 8) continue
            // An artist radio is one singer on purpose; everything else is not.
            if (title.startsWith("רדיו ") || title.startsWith("כי אתה שומע הרבה ")) continue
            if (title in setOf("נוספו לאחרונה למכשיר", "מהספרייה שלך", "חיוג מהיר")) continue
            val biggest = list.groupingBy { it.artistKey }.eachCount().values.max()
            assertTrue(
                "\"$title\" is ${biggest * 100 / list.size}% one singer",
                biggest * 4 <= list.size * 3
            )
        }
    }

    @Test fun aSeparationRuleHoldsInEveryGeneratedList() {
        val rule = Styles.Separations.parse("חסידי, ישראלי")
        val styleOf = { s: SongEntity -> Styles.parse(artistRows[s.artistKey]?.styles.orEmpty()) }
        val reporting = setOf("נוספו לאחרונה למכשיר", "מהספרייה שלך", "חיוג מהיר")
        for ((title, list) in offeredLists(engine())) {
            if (title in reporting) continue
            for (a in list) for (b in list) {
                assertFalse(
                    "\"$title\" puts ${styleOf(a)} next to ${styleOf(b)}",
                    rule.clash(styleOf(a), styleOf(b))
                )
            }
        }
    }

    @Test fun withoutARuleTheFeedStillMixesFreely() {
        // The guard above must be the rule doing the work, not the shelves
        // having quietly become single-style on their own.
        val mixed = offeredLists(engine(separations = "")).any { (_, list) ->
            list.map { artistRows[it.artistKey]?.styles.orEmpty() }
                .filter { it.isNotEmpty() }.distinct().size > 1
        }
        assertTrue("with no rule set, some shelf should mix styles", mixed)
    }

    @Test fun ratingAnArtistPromotesTheirSongs() {
        val e = engine()
        val loved = songs.filter { it.artistKey == Names.normalizeKey("אהרלה") }
        val unknown = songs.filter { it.artistKey == Names.normalizeKey("פלוני") }
        assertTrue(
            "a 5-star artist's songs should outrank an unrated artist's",
            loved.map { e.totalScore(it) }.average() >
                unknown.map { e.totalScore(it) }.average() + 0.5
        )
        // And it has to be visible, not just true inside the ranking.
        assertTrue(
            "the feed should have a shelf built from artist ratings",
            offeredLists(e).any { it.first == "מהאמנים שדירגת" }
        )
    }

    @Test fun aSecondCopyOfASongIsNotSomebodyElsesCover() {
        // The same track downloaded twice, tagged slightly differently. The
        // artist key differs, the singer does not.
        val library = listOf(
            song(1, "לך אלי", "ישי ריבו"),
            song(2, "לך אלי", "ישי ריבו feat. מוטי שטיינמץ"),
            song(3, "לך אלי", "אברהם פריד")
        )
        val types = Versions.classify(library)
        assertNotEquals(
            "a re-tagged copy of the same singer's track was called a cover",
            VersionType.COVER, types[2L]
        )
        assertEquals(
            "a genuinely different singer's take is still a cover",
            VersionType.COVER, types[3L]
        )
        assertTrue(
            "the two copies should be found as duplicates of each other",
            Versions.duplicateGroups(library, types)
                .any { group -> group.map { it.id }.containsAll(listOf(1L, 2L)) }
        )
    }

    @Test fun twoCopiesASecondApartAreStillTheSameFile() {
        // Bucketing by duration put 4:01 and 4:02 in different buckets about as
        // often as it put them in the same one.
        val library = listOf(
            song(1, "ניגון", "אהרלה").copy(durationMs = 241_000),
            song(2, "ניגון", "אהרלה").copy(durationMs = 242_000)
        )
        assertEquals(
            1,
            Versions.duplicateGroups(library, Versions.classify(library)).size
        )
    }
}
