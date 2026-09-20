package com.elchanan.rhythm.desktop

import com.elchanan.rhythm.data.db.ArtistEntity
import com.elchanan.rhythm.data.db.PlaylistEntity
import com.elchanan.rhythm.data.db.PlaylistItemEntity
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.data.db.SongStatsEntity
import com.elchanan.rhythm.engine.Names
import java.util.Locale

/**
 * The library as the screens want it, rather than as the database holds it.
 *
 * Everything here is derived: albums are a grouping of songs, an artist page
 * is a grouping of songs, a playlist is a join. None of it is stored, because
 * anything stored can disagree with the songs table, and on a rescan it would.
 *
 * The grouping rules are copied from the phone's view model on purpose, down
 * to the sort orders - the two builds have to answer "how many albums do I
 * have" with the same number, and that answer is entirely a question of how
 * the grouping is done.
 */
data class AlbumInfo(
    val albumId: Long,
    val name: String,
    val artistName: String,
    val songs: List<SongEntity>
)

data class ArtistInfo(
    val key: String,
    val displayName: String,
    val rating: Int,
    val styles: String,
    val note: String,
    val songs: List<SongEntity>
)

data class PlaylistInfo(
    val playlist: PlaylistEntity,
    val songs: List<SongEntity>
)

/**
 * A folder, with what is directly inside it.
 *
 * Flat rather than a tree: the desktop window shows one level at a time and
 * walks into the next, which needs a parent path and the children under it,
 * not a structure held in memory.
 */
data class FolderInfo(
    val path: String,
    val name: String,
    val songs: List<SongEntity>
)

data class LibraryModel(
    val songs: List<SongEntity> = emptyList(),
    val artists: List<ArtistInfo> = emptyList(),
    val albums: List<AlbumInfo> = emptyList(),
    val folders: List<FolderInfo> = emptyList(),
    val playlists: List<PlaylistInfo> = emptyList()
) {
    companion object {

        fun build(
            songs: List<SongEntity>,
            profiles: List<ArtistEntity>,
            playlists: List<PlaylistEntity>,
            items: List<PlaylistItemEntity>
        ): LibraryModel {
            val byId = songs.associateBy { it.id }
            val profileMap = profiles.associateBy { it.artistKey }

            // Every artist named on a song, not only the first one. A duet
            // belongs on both singers' pages; the first name stays the one the
            // song is keyed on everywhere else, this only decides where it is
            // listed. Names are gathered as we go, so a guest who never
            // appears alone still gets a page.
            val byArtist = LinkedHashMap<String, MutableList<SongEntity>>()
            val nameForKey = HashMap<String, String>()
            for (song in songs) {
                byArtist.getOrPut(song.artistKey) { ArrayList() }.add(song)
                nameForKey.putIfAbsent(song.artistKey, Names.primaryArtist(song.artistName))
                for (credit in Names.credits(song.artistName)) {
                    val key = Names.normalizeKey(credit)
                    if (key == song.artistKey) continue
                    byArtist.getOrPut(key) { ArrayList() }.add(song)
                    nameForKey.putIfAbsent(key, credit)
                }
            }
            val artists = byArtist.map { (key, list) ->
                val profile = profileMap[key]
                ArtistInfo(
                    key = key,
                    displayName = profile?.displayName?.takeIf { it.isNotBlank() }
                        ?: nameForKey[key].orEmpty(),
                    rating = profile?.rating ?: 0,
                    styles = profile?.styles.orEmpty(),
                    note = profile?.note.orEmpty(),
                    songs = list.distinctBy { it.id }.sortedBy { it.titleLower }
                )
            }.sortedBy { it.displayName.lowercase(Locale.ROOT) }

            val albums = songs.groupBy { it.albumId }.map { (id, list) ->
                AlbumInfo(
                    albumId = id,
                    name = list.first().albumName,
                    artistName = list.first().artistName,
                    // Track order inside a record, which is the order it was
                    // meant to be heard in and the only place in the app where
                    // the track number is used for anything.
                    songs = list.sortedWith(compareBy({ it.trackNumber }, { it.titleLower }))
                )
            }.sortedBy { it.name.lowercase(Locale.ROOT) }

            val folders = songs.groupBy { it.folder }.map { (path, list) ->
                FolderInfo(
                    path = path,
                    name = folderName(path),
                    songs = list.sortedBy { it.titleLower }
                )
            }.sortedBy { it.name.lowercase(Locale.ROOT) }

            val lists = playlists.map { pl ->
                PlaylistInfo(
                    playlist = pl,
                    songs = items.filter { it.playlistId == pl.id }
                        .sortedBy { it.position }
                        .mapNotNull { byId[it.songId] }
                )
            }

            return LibraryModel(
                songs = songs.sortedBy { it.titleLower },
                artists = artists,
                albums = albums,
                folders = folders,
                playlists = lists
            )
        }

        /** The last segment of a path, which is the part people recognise. */
        fun folderName(path: String): String =
            path.trimEnd('/', '\\')
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .ifEmpty { path }
    }

    /**
     * Liked songs, newest like first.
     *
     * Derived from the likes rather than kept as a real list, exactly as on
     * the phone: a stored copy would drift the moment a like is taken back,
     * and there would then be two disagreeing answers to "what did I like".
     */
    fun liked(stats: Map<Long, SongStatsEntity>): List<SongEntity> =
        songs.filter { stats[it.id]?.liked == 1 }
            .sortedByDescending { stats[it.id]?.likedAt ?: 0L }
}
