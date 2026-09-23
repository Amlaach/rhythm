package com.elchanan.rhythm.data

import com.elchanan.rhythm.data.db.SongEntity

/** One album on an artist's page, or their loose songs ([albumId] null). */
data class ArtistShelf(val albumId: Long?, val name: String, val year: Int, val songs: List<SongEntity>)

/**
 * An artist's songs as their albums, for both apps' artist page.
 *
 * The newest album first, each in its own track order. A song that is the
 * artist's only one on its album - a single, a compilation, a file with no
 * album tag - joins the others like it in one shelf at the end, rather than
 * each getting a heading of its own and scattering the page again.
 */
object ArtistShelves {

    fun of(songs: List<SongEntity>, looseName: String): List<ArtistShelf> {
        val (albums, loose) = songs.distinctBy { it.id }.groupBy { it.albumId }.values.partition { tracks ->
            val name = tracks.first().albumName
            tracks.size > 1 && name.isNotBlank() && !name.startsWith("<")
        }
        val shelves = albums.map { tracks ->
            val sorted = tracks.sortedWith(compareBy({ it.trackNumber }, { it.titleLower }))
            ArtistShelf(sorted.first().albumId, sorted.first().albumName, tracks.maxOf { it.year }, sorted)
        }.sortedWith(compareByDescending<ArtistShelf> { it.year }.thenBy { it.name.lowercase() })
        val singles = loose.flatten().sortedBy { it.titleLower }
        return if (singles.isEmpty()) shelves else shelves + ArtistShelf(null, looseName, 0, singles)
    }
}
