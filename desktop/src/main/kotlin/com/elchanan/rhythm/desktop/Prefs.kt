package com.elchanan.rhythm.desktop

import com.elchanan.rhythm.desktop.data.Store
import com.elchanan.rhythm.engine.ShelfKind

/**
 * Every setting the Windows build remembers, as a typed property.
 *
 * The phone keeps these in SharedPreferences and this keeps them in the
 * settings table of the same database as everything else, but the names and
 * the defaults are the phone's. A setting that means one thing on one build
 * and something else on the other is worse than a setting that only exists on
 * one of them.
 *
 * Read straight through to SQLite rather than cached. These are read when a
 * screen opens and written when a switch is flipped - a handful of times a
 * session, against a query that costs microseconds - and a cache here would
 * be one more thing that can disagree with what is stored.
 */
class Prefs(private val store: Store) {

    private fun flag(key: String, fallback: Boolean): Boolean =
        store.get(key)?.toBooleanStrictOrNull() ?: fallback

    private fun set(key: String, value: Boolean) = store.put(key, value.toString())

    private fun number(key: String, fallback: Int): Int =
        store.get(key)?.toIntOrNull() ?: fallback

    /** Shown once, before anything has been scanned. */
    var welcomeSeen: Boolean
        get() = flag("welcomeSeen", false)
        set(value) = set("welcomeSeen", value)

    /** Measure every new file as soon as the scan finds it. */
    var autoAnalyze: Boolean
        get() = flag("autoAnalyze", true)
        set(value) = set("autoAnalyze", value)

    /**
     * Files shorter than this are not music.
     *
     * Ringtones, notification sounds and the two second clip left over from a
     * failed download all land in the same folders as the music and all of
     * them pollute a shelf.
     */
    var minDurationSec: Int
        get() = number("minDurationSec", 45)
        set(value) = store.put("minDurationSec", value.toString())

    /** Folders the scan walks past. Stored newline separated, as the roots are. */
    var excludedFolders: List<String>
        get() = store.get("excludedFolders").orEmpty()
            .split('\n').filter { it.isNotBlank() }
        set(value) = store.put("excludedFolders", value.joinToString("\n"))

    /** The mood chips, kept on the home screen rather than behind a refresh. */
    var pinMoodRow: Boolean
        get() = flag("pinMoodRow", true)
        set(value) = set("pinMoodRow", value)

    /** Long recordings start where they were left rather than at the beginning. */
    var resumeSpoken: Boolean
        get() = flag("resumeSpoken", true)
        set(value) = set("resumeSpoken", value)

    /**
     * Collapse the same song appearing twice.
     *
     * A library assembled from downloads has the same recording three times
     * under three names, and every one of them takes a place on a shelf.
     */
    var hideDuplicates: Boolean
        get() = flag("hideDuplicates", false)
        set(value) = set("hideDuplicates", value)

    /** Open the full player when something starts, rather than staying put. */
    var openPlayerOnPlay: Boolean
        get() = flag("openPlayerOnPlay", false)
        set(value) = set("openPlayerOnPlay", value)

    /** Search results weighted by what this listener actually plays. */
    var searchPersonalized: Boolean
        get() = flag("searchPersonalized", true)
        set(value) = set("searchPersonalized", value)

    /** Keep playing past the end of the queue, on what the engine suggests next. */
    var autoRadio: Boolean
        get() = flag("autoRadio", true)
        set(value) = set("autoRadio", value)

    /** Skip what the analyser heard as a recording of a room rather than a record. */
    var skipRecordings: Boolean
        get() = flag("skipRecordings", false)
        set(value) = set("skipRecordings", value)

    /** Drop latin text from a title the repair rewrites. */
    var tagStripForeign: Boolean
        get() = flag("tagStripForeign", false)
        set(value) = set("tagStripForeign", value)

    /**
     * Write a tag repair into the file as well as into the database.
     *
     * Off by default. The correction is a guess at a naming convention, and a
     * guess written into someone's files is not one they can take back.
     */
    var writeTagsToFiles: Boolean
        get() = flag("writeTagsToFiles", false)
        set(value) = set("writeTagsToFiles", value)

    /** Which library tab opens first, by [LibraryTabName]. */
    var libraryFirstTab: String
        get() = store.get("libraryFirstTab") ?: "PLAYLISTS"
        set(value) = store.put("libraryFirstTab", value)

    /** Where LRC files are kept, if they are kept anywhere. */
    var lyricsFolder: String
        get() = store.get("lyricsFolder").orEmpty()
        set(value) = store.put("lyricsFolder", value)

    /** The equaliser's six band gains, in dB, and whether it is on. */
    var eqEnabled: Boolean
        get() = flag("eqEnabled", false)
        set(value) = set("eqEnabled", value)

    var eqBands: List<Int>
        get() = store.get("eqBands").orEmpty()
            .split(',').mapNotNull { it.trim().toIntOrNull() }
            .takeIf { it.size == BAND_COUNT } ?: List(BAND_COUNT) { 0 }
        set(value) = store.put("eqBands", value.joinToString(","))

    /** How loud, kept between launches so a quiet setting is not a surprise. */
    var volume: Int
        get() = number("volume", 100)
        set(value) = store.put("volume", value.toString())

    /**
     * Which shelves the home screen may show.
     *
     * Everything on by default. Turning one off deletes nothing - it stops
     * appearing, and comes back as it was when it is switched on again.
     */
    var homeShelves: Set<String>
        get() = store.get("homeShelves")
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
            ?: ShelfKind.ALL_KEYS
        set(value) = store.put("homeShelves", value.joinToString(","))

    /**
     * Search inside the words as well as the titles.
     *
     * Off by default, and deliberately: on this build the words are read out
     * of the files themselves rather than from an index, so a search that
     * includes them opens every file in the library. It runs behind the title
     * matches and never delays them.
     */
    var searchLyrics: Boolean
        get() = flag("searchLyrics", false)
        set(value) = set("searchLyrics", value)

    /** Show folders as a tree to walk into, rather than one flat list. */
    var folderTree: Boolean
        get() = flag("folderTree", true)
        set(value) = set("folderTree", value)

    /**
     * Styles that should never be mixed into one another's shelves.
     *
     * Stored as the engine parses it, so the rule the user typed and the rule
     * the recommender applies are the same string.
     */
    var styleSeparations: String
        get() = store.get("styleSeparations").orEmpty()
        set(value) = store.put("styleSeparations", value)

    /** When the library was last walked, for the scan report. */
    var lastScanAt: Long
        get() = store.get("lastScanAt")?.toLongOrNull() ?: 0L
        set(value) = store.put("lastScanAt", value.toString())

    /** How many files the last scan found. */
    var lastScanCount: Int
        get() = number("lastScanCount", 0)
        set(value) = store.put("lastScanCount", value.toString())

    companion object {
        const val BAND_COUNT = 6
    }
}
