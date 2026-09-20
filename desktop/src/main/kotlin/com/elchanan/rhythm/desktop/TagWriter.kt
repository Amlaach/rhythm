package com.elchanan.rhythm.desktop

import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.data.db.TagOverrideEntity
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Pushes a tag correction into the file itself.
 *
 * Optional, and off by default, because this is the one thing the app does
 * that someone cannot undo by pressing something: the correction is a guess
 * at a naming convention, and a guess written into a thousand files is a
 * thousand files to fix by hand if the guess was wrong. The correction is
 * always kept in the database first; this only mirrors it outward.
 *
 * Windows makes this fail in ordinary ways - the file is read only, it sits
 * on a network share that has gone away, another program has it open - so
 * every write is guarded and the count that could not be written is reported
 * rather than swallowed. Silently skipping half a library is how someone ends
 * up believing the feature does nothing.
 */
object TagWriter {

    init {
        // jaudiotagger logs an INFO line per field per file. On a library of
        // thousands that is the slowest part of the write by a wide margin,
        // and none of it is read by anyone.
        Logger.getLogger("org.jaudiotagger").level = Level.SEVERE
    }

    data class Result(val written: Int, val failed: Int)

    fun write(overrides: List<TagOverrideEntity>, songs: Map<Long, SongEntity>): Result {
        var written = 0
        var failed = 0
        for (row in overrides) {
            val song = songs[row.songId] ?: continue
            val ok = runCatching {
                val file = File(song.path)
                if (!file.canWrite()) return@runCatching false
                val audio = AudioFileIO.read(file)
                val tag = audio.tagOrCreateAndSetDefault ?: return@runCatching false
                if (row.title.isNotBlank()) tag.setField(FieldKey.TITLE, row.title)
                if (row.artistName.isNotBlank()) tag.setField(FieldKey.ARTIST, row.artistName)
                if (row.albumName.isNotBlank()) tag.setField(FieldKey.ALBUM, row.albumName)
                AudioFileIO.write(audio)
                true
            }.getOrDefault(false)
            if (ok) written++ else failed++
        }
        return Result(written, failed)
    }
}
