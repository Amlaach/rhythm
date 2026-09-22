package com.elchanan.rhythm.engine

import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.data.db.SongStatsEntity

/**
 * Tagging a whole folder at once.
 *
 * Libraries on a phone are not a flat pile: they arrive as folders, and the
 * folder is usually the answer. A folder called "הייליגע מינוטן" is חסידי all
 * the way down, and tagging it song by song is a hundred taps to say one
 * thing. That was the single largest piece of manual work the app asked for,
 * and the reason a library that had been used for months still had too few
 * labels for the learner to fit anything.
 *
 * The rules about what a bulk tag may overwrite live here rather than in
 * either platform's screen, because getting them wrong destroys work the user
 * cannot get back.
 */
object BulkTagging {

    /**
     * What one song's tags become when a folder is tagged.
     *
     * Null means leave it alone.
     *
     * @param replace true to overwrite tags the user typed by hand, which is
     *   what someone re-tagging a folder they got wrong is asking for. False
     *   adds to them. Either way a guess made by the learner is replaced
     *   outright: it was never the user's answer, and this is.
     */
    fun tagsFor(
        current: SongStatsEntity?,
        styles: List<String>,
        replace: Boolean
    ): String? {
        val wanted = styles.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (wanted.isEmpty()) return null
        val existing = Styles.parse(current?.styles.orEmpty())
        val typedByHand = existing.isNotEmpty() && current?.stylesAuto != 1
        val merged = when {
            replace || !typedByHand -> wanted
            else -> existing + wanted.filterNot { new ->
                existing.any { it.equals(new, ignoreCase = true) }
            }
        }
        if (merged.size == existing.size &&
            merged.all { m -> existing.any { it.equals(m, ignoreCase = true) } } &&
            current?.stylesAuto != 1
        ) {
            return null
        }
        return Styles.join(merged)
    }

    /**
     * Everything under a folder, including its subfolders.
     *
     * Path matching rather than tree walking, so a caller that only has the
     * path - which is every caller on the desktop side - does not have to
     * build the tree first. The separator check is what keeps "/Music/Shiur"
     * from also matching "/Music/Shiurim".
     */
    fun songsUnder(songs: List<SongEntity>, folder: String): List<SongEntity> {
        val root = folder.trimEnd('/', '\\')
        if (root.isEmpty()) return emptyList()
        return songs.filter { song ->
            val f = song.folder.trimEnd('/', '\\')
            f == root || f.startsWith("$root/") || f.startsWith("$root\\")
        }
    }
}
