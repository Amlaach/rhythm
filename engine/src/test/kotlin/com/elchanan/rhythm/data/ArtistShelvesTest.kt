package com.elchanan.rhythm.data

import com.elchanan.rhythm.data.db.SongEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class ArtistShelvesTest {

    private fun song(id: Long, title: String, album: String, albumId: Long, track: Int = 0, year: Int = 0) =
        SongEntity(
            id = id, title = title, titleLower = title.lowercase(), artistName = "A", artistKey = "a",
            albumName = album, albumId = albumId, durationMs = 200_000, trackNumber = track, year = year,
            genre = null, path = "/m/$id.mp3", folder = "/m", dateAddedSec = 0, sizeBytes = 0
        )

    @Test
    fun newestAlbumFirstInTrackOrderAndLooseSongsLast() {
        val shelves = ArtistShelves.of(
            listOf(
                song(1, "b", "Old", 10, track = 2, year = 2012),
                song(2, "a", "Old", 10, track = 1, year = 2012),
                song(3, "z", "New", 20, track = 1, year = 2020),
                song(4, "y", "New", 20, track = 2, year = 2020),
                song(5, "single", "Single", 30, year = 2023),
                song(6, "untagged", "", 40),
                song(7, "compilation pick", "Hits", 50)
            ),
            looseName = "loose"
        )
        assertEquals(listOf("New", "Old", "loose"), shelves.map { it.name })
        assertEquals(listOf(3L, 4L), shelves[0].songs.map { it.id })
        assertEquals(listOf(2L, 1L), shelves[1].songs.map { it.id })
        assertEquals(listOf(7L, 5L, 6L), shelves[2].songs.map { it.id })
        assertEquals(null, shelves[2].albumId)
    }

    @Test
    fun noLooseShelfWhenEverySongHasItsAlbum() {
        val shelves = ArtistShelves.of(
            listOf(song(1, "a", "X", 1), song(2, "b", "X", 1)),
            looseName = "loose"
        )
        assertEquals(1, shelves.size)
        assertEquals(1L, shelves[0].albumId)
    }
}
