package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.db.ArtistEntity
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.data.db.SongStatsEntity
import org.junit.Assert.*
import org.junit.Test

/**
 * A learned tag adds to what the artist said; a typed one replaces it.
 *
 * The reported symptom was that learning never succeeds. One cause was
 * structural rather than statistical: tag your artists - which is what the
 * app tells you to do, since one decision covers a hundred songs - and every
 * song in the library became ineligible, so a run ended with "no songs need
 * tags". Doing the work correctly was what stopped it working.
 */
class LearnedTagsAddTest {

    private fun song(id: Long, artist: String) = SongEntity(
        id = id, title = "שיר $id", titleLower = "שיר $id",
        artistName = artist, artistKey = Names.normalizeKey(artist),
        albumName = "al", albumId = 1, durationMs = 240_000, trackNumber = 1,
        year = 2020, genre = null, path = "/m/$id.mp3", folder = "/m",
        dateAddedSec = 0, sizeBytes = 0
    )

    private val track = song(1, "אהרלה")
    private val artistRow = mapOf(
        Names.normalizeKey("אהרלה") to ArtistEntity(
            artistKey = Names.normalizeKey("אהרלה"), displayName = "אהרלה", styles = "חסידי"
        )
    )

    private fun stylesSeenBy(stats: SongStatsEntity?): List<String> {
        val e = Recommender(
            songs = listOf(track),
            stats = stats?.let { mapOf(track.id to it) }.orEmpty(),
            artists = artistRow,
            affinity = emptyMap(), transitions = emptyMap(), features = emptyMap(),
            acoustic = null,
            // Any rule at all, so declaredStyles is populated and readable.
            tuning = EngineTuning(separations = "חסידי, ישראלי"),
            now = 0L, feedSeed = 1L
        )
        return e.stylesInForce(track)
    }

    @Test fun withNothingOnTheSongTheArtistsTagApplies() {
        assertEquals(listOf("חסידי"), stylesSeenBy(null))
    }

    @Test fun aLearnedTagIsAddedToTheArtistsRatherThanReplacingIt() {
        val guessed = SongStatsEntity(track.id, styles = "קצבי", stylesAuto = 1)
        assertEquals(listOf("חסידי", "קצבי"), stylesSeenBy(guessed))
    }

    @Test fun aTypedTagStillReplacesTheArtists() {
        // Saying this one song is different is the whole point of typing it.
        val typed = SongStatsEntity(track.id, styles = "ליטאי", stylesAuto = 0)
        assertEquals(listOf("ליטאי"), stylesSeenBy(typed))
    }

    @Test fun familiesSeparateTheTwoKindsOfQuestion() {
        assertEquals("ז'אנר", Styles.familyOf("חסידי"))
        assertEquals("אופי", Styles.familyOf("קצבי"))
        assertNull("free text has no family to reason about", Styles.familyOf("ניגוני קרלין"))
    }
}
