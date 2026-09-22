package com.elchanan.rhythm.data

import com.elchanan.rhythm.data.db.SongEntity
import java.util.Locale

/** A deliberately small, shareable view of a library: artist and title only. */
object LibraryCatalogExport {
    const val MIME = "text/plain"
    const val FILE_NAME = "rhythm-library-catalog.txt"

    data class Catalog(
        val text: String,
        val artists: Int,
        val albums: Int,
        val songs: Int,
        val unnamed: Int
    )

    fun create(songs: List<SongEntity>): Catalog {
        data class AlbumSongs(val name: String, val titles: MutableList<String>)
        data class ArtistAlbums(val name: String, val albums: LinkedHashMap<String, AlbumSongs>)

        val grouped = LinkedHashMap<String, ArtistAlbums>()
        var unnamed = 0
        for (song in songs) {
            val artist = clean(song.artistName).ifEmpty {
                unnamed++
                "[ללא שם אמן]"
            }
            val album = clean(song.albumName).ifEmpty {
                unnamed++
                "[ללא שם אלבום]"
            }
            val title = clean(song.title).ifEmpty {
                unnamed++
                clean(fileName(song.path).substringBeforeLast('.', fileName(song.path)))
                    .ifEmpty { "[שיר ללא שם]" }
            }
            // Preserve every distinct credit as it appears in the library.
            // Aggressive artist normalisation is useful for recommendations,
            // but here it could fold two printed names into one and make the
            // exported inventory look as though one never existed.
            val artistKey = artist.lowercase(Locale.ROOT)
            val artistGroup = grouped.getOrPut(artistKey) { ArtistAlbums(artist, LinkedHashMap()) }
            val albumKey = album.lowercase(Locale.ROOT)
            val albumGroup = artistGroup.albums.getOrPut(albumKey) {
                AlbumSongs(album, ArrayList())
            }
            // Every library row is represented. Two files with the same title
            // remain two lines: the exporter is an inventory, not a duplicate
            // remover, and must not decide that one of them does not exist.
            albumGroup.titles += title
        }

        val artists = grouped.values.sortedBy { it.name.lowercase(Locale.ROOT) }
        val text = buildString {
            for ((artistIndex, artist) in artists.withIndex()) {
                if (artistIndex > 0) append('\n')
                append("## ").append(artist.name).append('\n')
                for (album in artist.albums.values.sortedBy { it.name.lowercase(Locale.ROOT) }) {
                    append("### ").append(album.name).append('\n')
                    for (title in album.titles.sortedBy { it.lowercase(Locale.ROOT) }) {
                        append("- ").append(title).append('\n')
                    }
                }
            }
        }
        return Catalog(
            text = text,
            artists = artists.size,
            albums = artists.sumOf { it.albums.size },
            songs = artists.sumOf { artist -> artist.albums.values.sumOf { it.titles.size } },
            unnamed = unnamed
        )
    }

    /** Metadata may contain line breaks; one item must remain one line. */
    private fun clean(value: String): String = value
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(500)

    private fun fileName(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')
}
