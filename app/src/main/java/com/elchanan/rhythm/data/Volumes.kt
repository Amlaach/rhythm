package com.elchanan.rhythm.data

import java.io.File

/**
 * Which pieces of storage are attached right now.
 *
 * This exists because of one failure that costs people their library. A song
 * on a memory card disappears from MediaStore the moment the card is
 * unmounted - pulled out, or remounted by the system, or unavailable for the
 * few seconds around a reboot. A scan that runs in that window sees a library
 * with a hole in it, and a scan that treats "not in MediaStore" as "deleted"
 * writes that hole to disk: ratings, play counts and playlists gone, for
 * files that are sitting safely on a card in someone's pocket.
 *
 * Absence of evidence is not evidence of absence. So: a song may only be
 * forgotten when the storage it lives on is present and the song is not on
 * it. If the storage itself is missing, nothing is concluded about anything
 * filed there.
 */
object Volumes {

    /**
     * The storage root a path belongs to, or empty when it has none.
     *
     * `/storage/emulated/0/Music/x.mp3` -> `/storage/emulated/0`
     * `/storage/1A2B-3C4D/Music/x.mp3` -> `/storage/1A2B-3C4D`
     *
     * Three segments for emulated storage because the user number is part of
     * the mount, two for everything else. Anything that does not look like
     * either is left alone rather than guessed at, and a song with no root is
     * treated as unverifiable - which is the cautious answer.
     */
    fun rootOf(path: String): String {
        if (!path.startsWith("/storage/")) return ""
        val parts = path.split('/')
        // parts[0] is empty, parts[1] is "storage".
        if (parts.size < 4) return ""
        return if (parts[2] == "emulated") {
            if (parts.size < 5) "" else "/storage/emulated/${parts[3]}"
        } else {
            "/storage/${parts[2]}"
        }
    }

    /**
     * Whether a root is attached and readable.
     *
     * An unmounted card's directory under /storage stops existing, so the
     * check is the plain one. Scoped storage can refuse the listing of a
     * volume that is genuinely there, which would read as "missing" - so the
     * question asked is only whether the directory is there at all, not
     * whether its contents can be read.
     */
    fun isMounted(root: String): Boolean =
        root.isNotEmpty() && runCatching { File(root).exists() }.getOrDefault(false)

    /**
     * The roots that a set of paths lives on, each answered once.
     *
     * Called with a whole library, so the file system check is done per root
     * rather than per song - a few calls instead of a few thousand.
     */
    fun mountedRoots(paths: Collection<String>): Set<String> {
        val roots = paths.mapTo(HashSet()) { rootOf(it) }
        return roots.filterTo(HashSet()) { isMounted(it) }
    }
}
