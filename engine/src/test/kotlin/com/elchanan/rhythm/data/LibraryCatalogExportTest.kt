package com.elchanan.rhythm.data

import com.elchanan.rhythm.data.db.SongEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryCatalogExportTest {

    @Test fun exportsOnlyArtistAndSongNames() {
        val catalog = LibraryCatalogExport.create(
            listOf(song(1, "ניגון", "אמן", "/private/music/file.mp3"))
        )
        assertEquals("## אמן\n### secret album\n- ניגון\n", catalog.text)
        assertFalse(catalog.text.contains("/private"))
        assertFalse(catalog.text.contains("mp3"))
        assertEquals(1, catalog.artists)
        assertEquals(1, catalog.albums)
        assertEquals(1, catalog.songs)
    }

    @Test fun everyLibraryRowRemainsEvenWhenNamesRepeat() {
        val input = listOf(
            song(1, "שיר ב", "זמר", "/one"),
            song(2, "שיר א", "זמר", "/two"),
            song(3, "שיר א", " זמר ", "/copy")
        )
        val catalog = LibraryCatalogExport.create(input)
        assertEquals(
            "## זמר\n### secret album\n- שיר א\n- שיר א\n- שיר ב\n",
            catalog.text
        )
        assertEquals(3, catalog.songs)
    }

    @Test fun lineBreaksCannotCreateFakeArtistsOrSongs() {
        val catalog = LibraryCatalogExport.create(
            listOf(song(1, "שם\nהשיר", "שם\r\nהאמן", "/one"))
        )
        assertEquals("## שם האמן\n### secret album\n- שם השיר\n", catalog.text)
    }

    @Test fun namelessRowsAreMarkedAndNeverSkipped() {
        val catalog = LibraryCatalogExport.create(
            listOf(song(1, "", "אמן", "/one"), song(2, "שיר", "", "/two"))
        )
        assertEquals(2, catalog.songs)
        assertTrue(catalog.text.contains("[ללא שם אמן]"))
        assertTrue(catalog.text.contains("- one"))
        assertEquals(2, catalog.unnamed)
    }

    private fun song(id: Long, title: String, artist: String, path: String) = SongEntity(
        id = id, title = title, titleLower = title.lowercase(), artistName = artist,
        artistKey = artist.lowercase(), albumName = "secret album", albumId = 9,
        durationMs = 123_000, trackNumber = 4, year = 2026, genre = "secret genre",
        path = path, folder = "/private", dateAddedSec = 999, sizeBytes = 777
    )
}
