package com.elchanan.rhythm.data

import com.elchanan.rhythm.data.db.SongEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class FileTitlesTest {

    private fun song(title: String, path: String) = SongEntity(
        id = 1, title = title, titleLower = title.lowercase(), artistName = "a", artistKey = "a",
        albumName = "", albumId = 0, durationMs = 1, trackNumber = 0, year = 0, genre = null,
        path = path, folder = "", dateAddedSec = 0, sizeBytes = 0
    )

    @Test fun theFileNameWithoutFolderOrExtension() {
        assertEquals("ישי ריבו - סיבות", FileTitles.of("/storage/emulated/0/Music/ישי ריבו - סיבות.mp3"))
        assertEquals("song.v2", FileTitles.of("C:\\Music\\song.v2.flac"))
        assertEquals("no extension", FileTitles.of("/x/no extension"))
        assertNull(FileTitles.of("/x/.mp3"))
        assertNull(FileTitles.of(""))
    }

    @Test fun onlyTheTitleChanges() {
        val s = song("Track 01", "/m/השם.mp3")
        val named = FileTitles.apply(s)
        assertEquals("השם", named.title)
        assertEquals("השם", named.titleLower)
        assertEquals(s.copy(title = "השם", titleLower = "השם"), named)
        // Already the same, or nothing to use: the very same song back.
        val same = song("השם", "/m/השם.mp3")
        assertSame(same, FileTitles.apply(same))
        val bare = song("x", "")
        assertSame(bare, FileTitles.apply(bare))
    }
}
