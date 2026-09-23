package com.elchanan.rhythm.desktop

import com.elchanan.rhythm.desktop.data.Store
import com.elchanan.rhythm.engine.EqBands
import com.elchanan.rhythm.engine.PlayerAction
import com.elchanan.rhythm.engine.ShelfKind
import com.elchanan.rhythm.engine.Listening

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

    var language: String
        get() = store.get("ui_language").let { if (it == "en") "en" else "he" }
        set(value) = store.put("ui_language", if (value == "en") "en" else "he")

    private fun flag(key: String, fallback: Boolean): Boolean =
        store.get(key)?.toBooleanStrictOrNull() ?: fallback

    private fun set(key: String, value: Boolean) = store.put(key, value.toString())

    private fun number(key: String, fallback: Int): Int =
        store.get(key)?.toIntOrNull() ?: fallback

    /** Shown once, before anything has been scanned. */
    var welcomeSeen: Boolean
        get() = flag("welcomeSeen", false)
        set(value) = set("welcomeSeen", value)

    /**
     * Learn the styles by itself once the sound has been measured.
     *
     * On by default, which is only defensible because of what learning does
     * before it writes anything: it tests itself on artists it never trained
     * on and refuses any style it cannot get right four times in five. A run
     * with nothing trustworthy to say changes nothing at all.
     */
    var autoLearn: Boolean
        get() = flag("autoLearn", true)
        set(value) = set("autoLearn", value)

    /**
     * How long a track must be heard before it is counted at all.
     *
     * Someone browsing by ear touches a dozen songs looking for one. Counting
     * those as plays teaches the recommender that they are liked; counting
     * them as skips buries songs nobody rejected. Neither is true, so below
     * this nothing is recorded either way.
     */
    var minPlaySeconds: Int
        get() = number("minPlaySeconds", Listening.DEFAULT_MINIMUM_SEC)
        set(value) = store.put(
            "minPlaySeconds",
            value.coerceIn(Listening.MIN_MINIMUM_SEC, Listening.MAX_MINIMUM_SEC).toString()
        )

    val minPlayMs: Long get() = Listening.minimumMsOf(minPlaySeconds)

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

    /** Dragging the app's volume to zero pauses, and raising it resumes. The phone's pauseOnSilence. */
    var pauseOnSilence: Boolean
        get() = flag("pauseOnSilence", false)
        set(value) = set("pauseOnSilence", value)

    /** Search finds songs by a line of their words too. On by default, as on the phone. */
    var searchLyrics: Boolean
        get() = flag("searchLyrics", true)
        set(value) = set("searchLyrics", value)

    /** Keep playing past the end of the queue, on what the engine suggests next. */
    var autoRadio: Boolean
        get() = flag("autoRadio", true)
        set(value) = set("autoRadio", value)

    /**
     * Keep voice recordings out of the library.
     *
     * Call recordings, voice notes and WhatsApp audio are not music, and a
     * fair number of recorders tag them as though they were - so they are
     * recognised by the folder and the file name instead, which is cruder
     * and is what actually works. On, as on the phone, because a library
     * full of recorded phone calls is nobody's idea of a music player.
     */
    var skipRecordings: Boolean
        get() = flag("skipRecordings", true)
        set(value) = set("skipRecordings", value)

    /** Drop latin text from a title the repair rewrites. */
    var tagStripForeign: Boolean
        get() = flag("tagStripForeign", true)
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

    /**
     * Where each player control sits, as `key=placement` pairs.
     *
     * One string rather than a setting each, so adding a control later needs
     * no migration: an unknown key is ignored and a missing one falls back
     * to its own default. The keys are [PlayerAction]'s, shared with the
     * phone, so an arrangement means the same thing on both.
     */
    var playerActions: Map<String, String>
        get() = store.get("playerActions").orEmpty()
            .split(',')
            .mapNotNull { pair ->
                val parts = pair.split('=')
                if (parts.size == 2 && parts[0].isNotBlank()) {
                    parts[0].trim() to parts[1].trim()
                } else {
                    null
                }
            }
            .toMap()
        set(value) = store.put(
            "playerActions",
            value.entries.joinToString(",") { "${it.key}=${it.value}" }
        )

    /**
     * Even out the volume between tracks.
     *
     * Off by default, because it needs the library analysed before it can do
     * anything and silently doing nothing is a worse first impression than
     * a switch waiting to be turned on.
     */
    var normalizeVolume: Boolean
        get() = flag("normalizeVolume", false)
        set(value) = set("normalizeVolume", value)

    /**
     * Offer to pick a song up where it was left.
     *
     * Only ever an offer: the song starts from the beginning and a strip at
     * the top says where it was left, for a few seconds. Jumping straight
     * back into the middle of a track nobody asked to resume is the more
     * annoying half of this feature.
     */
    var resumePrompt: Boolean
        get() = flag("resumePrompt", true)
        set(value) = set("resumePrompt", value)

    /** Clicking the artwork stops and starts it. */
    var tapArtworkToggles: Boolean
        get() = flag("tapArtworkToggles", true)
        set(value) = set("tapArtworkToggles", value)

    /**
     * Whether the "rate some artists" nudge has been turned down.
     *
     * A nudge with no way out stops being a nudge, so the dismissal is kept
     * rather than held in the screen: turning a suggestion down once has to
     * mean it stays down across restarts.
     */
    var ratingTipSeen: Boolean
        get() = flag("ratingTipSeen", false)
        set(value) = set("ratingTipSeen", value)

    /** Whether the tag repair pointer has been turned down. */
    var tagTipSeen: Boolean
        get() = flag("tagTipSeen", false)
        set(value) = set("tagTipSeen", value)

    /** Whether the thirty one band equaliser is doing anything. */
    var eqEnabled: Boolean
        get() = flag("eqEnabled", false)
        set(value) = set("eqEnabled", value)

    /**
     * One gain per ISO third octave centre, in millibels.
     *
     * Millibels and not decibels, and thirty one of them and not six,
     * because these are the numbers [com.elchanan.rhythm.engine.EqSettings]
     * takes - the same store the phone writes. A list stored short or
     * missing reads as flat rather than having to be handled at every use.
     */
    var eqBands: List<Int>
        get() {
            val stored = store.get("eqBands").orEmpty()
                .split(',').mapNotNull { it.trim().toIntOrNull() }
            return List(EqBands.COUNT) { stored.getOrNull(it) ?: 0 }
        }
        set(value) = store.put("eqBands", value.joinToString(","))

    /**
     * Gain applied before the filters, in millibels.
     *
     * Its own control because boosting and turning down are different
     * intentions: someone adding 8 dB of bass wants more bass, not a louder
     * track, and without this the only way to get one without the other is
     * to pull the other thirty sliders down by hand.
     */
    var eqPreamp: Int
        get() = number("eqPreamp", 0)
        set(value) = store.put("eqPreamp", value.toString())

    /** How loud, kept between launches so a quiet setting is not a surprise. */
    /**
     * The queue as it was left, and where in it: put back on the next start,
     * paused, as the phone puts its queue back. Ids rather than paths, so a
     * song that moved is still found and one that is gone is simply dropped.
     */
    var savedQueue: List<Long>
        get() = store.get("savedQueue").orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }
        set(value) = store.put("savedQueue", value.joinToString(","))

    var savedQueueIndex: Int
        get() = number("savedQueueIndex", 0)
        set(value) = store.put("savedQueueIndex", value.toString())

    var savedQueuePosition: Long
        get() = store.get("savedQueuePosition")?.toLongOrNull() ?: 0L
        set(value) = store.put("savedQueuePosition", value.toString())

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

    // Searching inside the words is the phone's, and is not here. It needs
    // the lyrics in a table to search: this build reads them out of the file
    // when a song is opened, and doing that across a whole library on every
    // keystroke is thousands of file opens per letter typed. The switch used
    // to exist here and did nothing at all, which is worse than not offering
    // it - a setting that cannot work should not be on screen.

    /**
     * Show folders nested, the way they sit on the disk, rather than as one
     * flat list of every folder that contains a file.
     *
     * On by default: it is how the files actually are, and the flat list is
     * only easier when there are few enough folders for the difference not to
     * matter - in which case the tree is no harder either.
     */
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

}
