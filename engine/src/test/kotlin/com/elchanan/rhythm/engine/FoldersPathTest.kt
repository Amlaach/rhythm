package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.db.SongEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/** The player opens a song's folder by the song's own folder string. */
class FoldersPathTest {

    private fun song(id: Long, folder: String) = SongEntity(
        id = id, title = "s$id", titleLower = "s$id", artistName = "A", artistKey = "a",
        albumName = "", albumId = id, durationMs = 1, trackNumber = 0, year = 0, genre = null,
        path = "$folder/s$id.mp3", folder = folder, dateAddedSec = 0, sizeBytes = 0
    )

    @Test
    fun aSongsFolderIsFoundInTheTree() {
        val songs = listOf(
            song(1, "/storage/emulated/0/Music/Rock/Live"),
            song(2, "/storage/emulated/0/Music/Rock/Studio/"),
            song(3, "/storage/emulated/0/Music/Pop")
        )
        val root = Folders.build(songs)
        for (s in songs) {
            val node = Folders.find(root, Folders.pathOf(s.folder))
            assertEquals(listOf(s.id), node?.songs?.map { it.id })
        }
    }
}
