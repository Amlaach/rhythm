package com.elchanan.rhythm.desktop

import com.elchanan.rhythm.desktop.data.Store

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

    companion object {
        const val BAND_COUNT = 6
    }
}
