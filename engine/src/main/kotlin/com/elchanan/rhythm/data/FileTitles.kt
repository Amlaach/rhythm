package com.elchanan.rhythm.data

import com.elchanan.rhythm.data.db.SongEntity
import java.util.Locale

/**
 * A song named by its file rather than by its tags.
 *
 * Downloaded files often carry tags that were never meant for them - the
 * site's name, "Track 01", another song's title - while the file name is the
 * one thing someone chose. When the listener asks for it, the file name is
 * the song's name everywhere the app shows or reads one. The tags in the file
 * are not touched, and switching back brings them back as they were.
 */
object FileTitles {

    /** The file's name without its folder or extension, or null when there is none to use. */
    fun of(path: String): String? {
        val name = path.replace('\\', '/').substringAfterLast('/')
        val bare = if ('.' in name) name.substringBeforeLast('.') else name
        return bare.trim().takeIf { it.isNotEmpty() }
    }

    fun apply(song: SongEntity): SongEntity {
        val title = of(song.path) ?: return song
        if (title == song.title) return song
        return song.copy(title = title, titleLower = title.lowercase(Locale.ROOT))
    }

    fun apply(songs: List<SongEntity>): List<SongEntity> = songs.map(::apply)
}
