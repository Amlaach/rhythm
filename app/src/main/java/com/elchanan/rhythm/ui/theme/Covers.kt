package com.elchanan.rhythm.ui.theme

import com.elchanan.rhythm.data.db.SongEntity

/**
 * The songs whose covers make up a mix's four-picture collage.
 *
 * The first four songs of a mix are often three from one album, and a collage
 * of the same sleeve three times says less about the mix than one picture
 * would. So each picture is a different album where the mix has that many,
 * and a different singer where it can be too; only a mix of fewer albums than
 * pictures repeats one.
 */
fun collageSongs(songs: List<SongEntity>, count: Int = 4): List<SongEntity> {
    val out = ArrayList<SongEntity>(count)
    val albums = HashSet<Long>()
    val artists = HashSet<String>()
    // A new album by a new singer first, then any new album, then anything.
    for (s in songs) {
        if (out.size == count) return out
        if (s.albumId !in albums && s.artistKey !in artists) {
            out.add(s); albums.add(s.albumId); artists.add(s.artistKey)
        }
    }
    for (s in songs) {
        if (out.size == count) return out
        if (s.albumId !in albums) {
            out.add(s); albums.add(s.albumId)
        }
    }
    for (s in songs) {
        if (out.size == count) return out
        if (s !in out) out.add(s)
    }
    return out
}

/**
 * An "album" that is really a folder: a file with no album tag is filed by the
 * system under the name of the folder it sits in, so a phone's downloads turn
 * into an album called "Download" and its music folder into one called
 * "Music". On a shelf of albums picked for you they read as noise.
 */
fun isFolderNamedAlbum(name: String, songs: List<SongEntity>): Boolean {
    val key = name.trim().lowercase()
    if (key.isEmpty() || key in GENERIC_ALBUMS) return true
    if (songs.isEmpty()) return false
    val fromFolder = songs.count { song ->
        song.folder.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').trim().lowercase() == key
    }
    return fromFolder * 2 > songs.size
}

private val GENERIC_ALBUMS = setOf(
    "music", "download", "downloads", "audio", "songs", "media", "sounds", "unknown", "<unknown>",
    "unknown album", "bluetooth", "whatsapp audio", "telegram audio", "recordings",
    "מוזיקה", "הורדות", "שירים", "ללא אלבום", "אלבום לא ידוע"
)
