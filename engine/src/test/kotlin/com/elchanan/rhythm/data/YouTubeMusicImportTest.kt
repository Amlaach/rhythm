package com.elchanan.rhythm.data

import com.elchanan.rhythm.data.db.SongEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class YouTubeMusicImportTest {

    private fun song(id: Long, title: String, artist: String) = SongEntity(
        id = id, title = title, titleLower = title.lowercase(), artistName = artist, artistKey = artist.lowercase(),
        albumName = "", albumId = id, durationMs = 200_000, trackNumber = 0, year = 0, genre = null,
        path = "/m/$id.mp3", folder = "/m", dateAddedSec = 0, sizeBytes = 0
    )

    private val library = listOf(
        song(1, "האסיר", "בן צור"),
        song(2, "לב של זהב", "ישי ריבו"),
        song(3, "אהבה", "אמן א"),
        song(4, "אהבה", "אמן ב"),
        song(5, "Hallelujah", "Leonard Cohen")
    )

    @Test
    fun takeoutPlaylistNamedFromTheLibraryListAndTheHistory() {
        val c = YouTubeMusicImport.Collector()
        c.add(
            "Takeout/YouTube and YouTube Music/playlists/שבת-videos.csv",
            "Video ID,Playlist Video Creation Timestamp\n" +
                "aaaaaaaaaaa,2024-01-01T00:00:00+00:00\n" +
                "bbbbbbbbbbb,2024-01-01T00:00:00+00:00\n" +
                "ccccccccccc,2024-01-01T00:00:00+00:00\n" +
                "ddddddddddd,2024-01-01T00:00:00+00:00\n"
        )
        c.add(
            "Takeout/YouTube and YouTube Music/music (library and uploads)/music library songs.csv",
            "Video ID,Song Title,Album Title,Artist Name 1\n" +
                "aaaaaaaaaaa,האסיר,,בן צור\n" +
                "bbbbbbbbbbb,\"אהבה\",,אמן ב\n"
        )
        c.add(
            "Takeout/YouTube and YouTube Music/history/watch-history.json",
            """[{"header":"YouTube Music","title":"Watched ישי ריבו - לב של זהב (Official Video)",""" +
                """"titleUrl":"https://music.youtube.com/watch?v=ccccccccccc","subtitles":[{"name":"ישי ריבו - Topic","url":"x"}]}]"""
        )
        c.add(
            "Takeout/YouTube and YouTube Music/comments/comments.csv",
            "Comment ID,Channel ID,Comment Create Timestamp,Video ID,Comment Text\n" +
                "x,UC1,2024,aaaaaaaaaaa,nice\n"
        )
        val out = YouTubeMusicImport.match(c, library)
        assertEquals(1, out.size)
        assertEquals("שבת", out[0].name)
        assertEquals(listOf(1L, 4L, 2L), out[0].songs.map { it.id })
        assertEquals(1, out[0].missing)
    }

    @Test
    fun oldTakeoutFormatAndHtmlHistory() {
        val c = YouTubeMusicImport.Collector()
        c.add(
            "Mix.csv",
            "Playlist Id,Channel Id,Time Created,Time Updated,Title,Description,Visibility\n" +
                "PL1,UC1,2020,2021,Mix,,Private\n\n" +
                "Video Id,Time Added\n" +
                "eeeeeeeeeee,2021\n"
        )
        c.add(
            "watch-history.html",
            """<a href="https://music.youtube.com/watch?v=eeeeeeeeeee">Hallelujah</a><br><a href="https://www.youtube.com/channel/UC">Leonard Cohen - Topic</a>"""
        )
        val out = YouTubeMusicImport.match(c, library)
        assertEquals(listOf(5L), out.single().songs.map { it.id })
        assertEquals("Mix", out.single().name)
    }

    @Test
    fun convertersCsvWithSeveralPlaylists() {
        val c = YouTubeMusicImport.Collector()
        c.add(
            "export.csv",
            "Track name,Artist name,Album,Playlist name\n" +
                "האסיר,בן צור,,א\n" +
                "אהבה,,,א\n" +
                "Hallelujah,Leonard Cohen,,ב\n"
        )
        val out = YouTubeMusicImport.match(c, library)
        assertEquals(listOf("א", "ב"), out.map { it.name })
        // "אהבה" with no artist is two songs: not guessed.
        assertEquals(listOf(1L), out[0].songs.map { it.id })
        assertEquals(1, out[0].missing)
        assertEquals(listOf(5L), out[1].songs.map { it.id })
    }
}
