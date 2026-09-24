package com.elchanan.rhythm.data

import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.engine.Names
import org.junit.Assert.*
import org.junit.Test

/**
 * What download sites stamp on every file is not the song.
 */
class DownloadJunkTest {

    private fun song(id: Long, title: String, artist: String, album: String = "al $id") = SongEntity(
        id, title, title.lowercase(), artist, Names.normalizeKey(Names.primaryArtist(artist)), album, id,
        240_000L, 1, 2020, null, "/m/$id", "/m", 1L, 1L
    )

    private val artists = listOf("ישי ריבו", "חנן בן ארי", "מוטי שטיינמץ", "אברהם פריד", "בני פרידמן")

    /** A library where one site signed a dozen files, beside songs that are fine. */
    private val library: List<SongEntity> = buildList {
        val names = listOf("תוכו רצוף אהבה", "לשם שמיים", "השיבנו", "אבינו", "ננצח", "הכל לטובה",
            "אדון עולם", "לכה דודי", "שלום עליכם", "בלעדייך", "ניגון געגועים", "מחרוזת חתונה")
        names.forEachIndexed { i, name ->
            add(song(i + 1L, "$name - חדשות המוזיקה", artists[i % artists.size], "חדשות המוזיקה"))
        }
        // The same common titles, sung by others and named plainly.
        add(song(101, "אדון עולם", "יונתן רזאל"))
        add(song(102, "לכה דודי", "ישי ריבו"))
        add(song(103, "אדון עולם", "שולי רנד"))
        add(song(104, "שלום עליכם", "אברהם פריד"))
        // A real compilation: many artists, one album.
        for (i in 0 until 6) add(song(200L + i, "שיר $i", artists[i % artists.size], "שירי שבת"))
    }

    private val junk = DownloadJunk.learn(library)

    @Test fun aSitesSignatureIsLearnedAndRemoved() {
        assertTrue(junk.phrases.contains(Names.normalizeKey("חדשות המוזיקה")))
        assertEquals("תוכו רצוף אהבה", junk.clean("תוכו רצוף אהבה - חדשות המוזיקה"))
        // written on without a separator
        assertEquals("השיבנו", junk.clean("השיבנו חדשות המוזיקה"))
        assertTrue(junk.isAllJunk("חדשות המוזיקה"))
    }

    @Test fun knownJunkGoesEvenOnce() {
        val empty = DownloadJunk.learn(emptyList())
        assertEquals("ננצח", empty.clean("ננצח | www.music-site.co.il"))
        assertEquals("ננצח", empty.clean("ננצח - להורדה"))
        assertEquals("ננצח", empty.clean("ננצח (Official Video) HD"))
        assertEquals("ננצח", empty.clean("ננצח - קליפ רשמי"))
        assertEquals("ננצח", empty.clean("ננצח להאזנה והורדה MP3"))
    }

    @Test fun realNamesStay() {
        // a song name many artists sing is a song, not a signature
        assertEquals("אדון עולם", junk.clean("אדון עולם"))
        assertFalse(junk.phrases.contains(Names.normalizeKey("אדון עולם")))
        // a word that merely contains a junk word
        assertEquals("בלעדייך", DownloadJunk.learn(emptyList()).clean("בלעדייך"))
        // what the app reads titles for
        assertEquals("מחרוזת חתונה", junk.clean("מחרוזת חתונה"))
        // an artist's name, and a real compilation album
        assertEquals("ישי ריבו", junk.clean("ישי ריבו"))
        assertEquals("שירי שבת", junk.clean("שירי שבת"))
        assertFalse(junk.isAllJunk("שירי שבת"))
    }

    @Test fun theTagFixProposesTheCleanNamesAndAnAlbumOfTheSongsOwn() {
        val proposals = TagFixer.propose(library).associateBy { it.songId }
        val first = proposals.getValue(1L)
        assertEquals("תוכו רצוף אהבה", first.newTitle)
        assertEquals("ישי ריבו", first.newArtist)
        assertTrue(first.albumChanged)
        assertEquals("the site's name was all the album was", "תוכו רצוף אהבה", first.newAlbum)
        assertEquals("תוכו רצוף אהבה", TagFixer.toOverrides(listOf(first)).single().albumName)
        // a real compilation is left alone
        assertFalse(proposals.getValue(200L).albumChanged)
        assertFalse(proposals.getValue(101L).changed)
    }
}
