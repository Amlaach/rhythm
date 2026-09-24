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
