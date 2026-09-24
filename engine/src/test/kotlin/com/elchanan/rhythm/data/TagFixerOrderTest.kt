package com.elchanan.rhythm.data

import com.elchanan.rhythm.data.db.SongEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Reported from a real library: the artist's English name after a Hebrew song name, and "Download" as an album. */
class TagFixerOrderTest {

    private fun song(id: Long, title: String, artist: String, album: String = "Download", folder: String = "/storage/emulated/0/Download") =
        SongEntity(id, title, title.lowercase(), artist, artist, album, id, 200_000, 0, 0, null, "$folder/$id.mp3", folder, 0, 0)

    private fun proposal(songs: List<SongEntity>, id: Long) = TagFixer.propose(songs).first { it.songId == id }

    @Test fun theArtistsEnglishNameComesOffEitherSide() {
        val songs = listOf(
            song(1, "Hanan Ben Ari השיבנו", "חנן בן ארי"),
            song(2, "השיבנו Hanan Ben Ari", "חנן בן ארי"),
            song(3, "לב אמיץ Hanan Ben Ari", "חנן בן ארי")
        )
        assertEquals("השיבנו", proposal(songs, 1).newTitle)
        assertEquals("השיבנו", proposal(songs, 2).newTitle)
        assertEquals("לב אמיץ", proposal(songs, 3).newTitle)
        assertEquals("חנן בן ארי", proposal(songs, 3).newArtist)
    }

    @Test fun anEnglishWordThatIsNoArtistStays() {
        val songs = listOf(song(1, "שיר אהבה Remix", "בן צור"), song(2, "הווה", "בן צור"))
        assertEquals("שיר אהבה Remix", proposal(songs, 1).newTitle)
    }

    @Test fun anAlbumThatIsOnlyTheFolderIsLeftAlone() {
        val songs = listOf(song(1, "הווה", "בן צור"), song(2, "השם", "בן צור"), song(3, "לולא תורתך", "ישי ריבו"))
        for (p in TagFixer.propose(songs)) {
            assertFalse("${p.oldTitle}: album ${p.oldAlbum} -> ${p.newAlbum}", p.albumChanged)
            assertFalse(p.changed)
        }
    }
}
