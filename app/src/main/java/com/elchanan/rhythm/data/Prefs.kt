package com.elchanan.rhythm.data

import android.content.Context
import androidx.core.content.edit
import com.elchanan.rhythm.engine.EqBands
import com.elchanan.rhythm.engine.Listening
import com.elchanan.rhythm.engine.ShelfKind
import com.elchanan.rhythm.engine.Styles

/**
 * Small, boring settings store. Everything the recommendation engine can be
 * tuned with lives here so the user can steer it without touching code.
 */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("rhythm_prefs", Context.MODE_PRIVATE)

    var language: String
        get() = sp.getString("ui_language", "he").orEmpty().let { if (it == "en") "en" else "he" }
        set(value) = sp.edit { putString("ui_language", if (value == "en") "en" else "he") }

    var minDurationSec: Int
        get() = sp.getInt(KEY_MIN_DURATION, 45)
        set(value) = sp.edit { putInt(KEY_MIN_DURATION, value) }

    /** 0f = play only what I already love, 1f = surprise me constantly */
    var discovery: Float
        get() = sp.getFloat(KEY_DISCOVERY, 0.35f)
        set(value) = sp.edit { putFloat(KEY_DISCOVERY, value) }

    /** How strongly the manual artist ratings override everything else. */
    var artistWeight: Float
        get() = sp.getFloat(KEY_ARTIST_WEIGHT, 1.0f)
        set(value) = sp.edit { putFloat(KEY_ARTIST_WEIGHT, value) }

    /** How strongly style tags steer the feed. */
    var styleWeight: Float
        get() = sp.getFloat(KEY_STYLE_WEIGHT, 1.0f)
        set(value) = sp.edit { putFloat(KEY_STYLE_WEIGHT, value) }

    /** Avoid repeating the same song too soon. */
    var repeatGuard: Float
        get() = sp.getFloat(KEY_REPEAT_GUARD, 1.0f)
        set(value) = sp.edit { putFloat(KEY_REPEAT_GUARD, value) }

    /** How strongly the measured sound of a file steers the feed. */
    var acousticWeight: Float
        get() = sp.getFloat(KEY_ACOUSTIC_WEIGHT, 1.0f)
        set(value) = sp.edit { putFloat(KEY_ACOUSTIC_WEIGHT, value) }

    /**
     * Learn the styles by itself once the sound has been measured.
     *
     * On by default, which is only defensible because of what learning does
     * before it writes anything: it tests itself on artists it never trained
     * on and refuses any style it cannot get right four times in five. A run
     * that has nothing trustworthy to say changes nothing at all, so the
     * ordinary outcome of leaving this on is either a correct tag or silence.
     *
     * It is still visible and still reversible - the settings screen says what
     * was written and to how many songs, lists them, and clears them on a
     * button - because a tag that appears in someone's library without being
     * asked for had better be easy to find.
     */
    var autoLearn: Boolean
        get() = sp.getBoolean(KEY_AUTO_LEARN, true)
        set(value) = sp.edit { putBoolean(KEY_AUTO_LEARN, value) }

    /**
     * How long a track must be heard before it is counted at all.
     *
     * Someone browsing by ear touches a dozen songs looking for one. Counting
     * those as plays teaches the recommender that they are liked; counting
     * them as skips buries songs nobody rejected. Neither is true, so below
     * this nothing is recorded either way.
     */
    var minPlaySeconds: Int
        get() = sp.getInt(KEY_MIN_PLAY_SECONDS, Listening.DEFAULT_MINIMUM_SEC)
        set(value) = sp.edit {
            putInt(
                KEY_MIN_PLAY_SECONDS,
                value.coerceIn(Listening.MIN_MINIMUM_SEC, Listening.MAX_MINIMUM_SEC)
            )
        }

    val minPlayMs: Long get() = Listening.minimumMsOf(minPlaySeconds)

    /** Keep analysing new files in the background without being asked. */
    var autoAnalyze: Boolean
        get() = sp.getBoolean(KEY_AUTO_ANALYZE, true)
        set(value) = sp.edit { putBoolean(KEY_AUTO_ANALYZE, value) }

    /** Folders whose files never enter the library, one per line. */
    var excludedFolders: List<String>
        get() = sp.getString(KEY_EXCLUDED, "")
            .orEmpty()
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        set(value) = sp.edit { putString(KEY_EXCLUDED, value.joinToString("\n")) }

    /** The queue is restored on the next launch so playback picks up where it stopped. */
    var savedQueue: List<Long>
        get() = sp.getString(KEY_QUEUE, "").orEmpty()
            .split(',')
            .mapNotNull { it.trim().toLongOrNull() }
        set(value) = sp.edit { putString(KEY_QUEUE, value.joinToString(",")) }

    var savedQueueIndex: Int
        get() = sp.getInt(KEY_QUEUE_INDEX, 0)
        set(value) = sp.edit { putInt(KEY_QUEUE_INDEX, value) }

    var savedQueuePosition: Long
        get() = sp.getLong(KEY_QUEUE_POSITION, 0L)
        set(value) = sp.edit { putLong(KEY_QUEUE_POSITION, value) }

    /** Folder granted through the system picker, used to find .lrc files. */
    var lyricsFolderUri: String?
        get() = sp.getString(KEY_LYRICS_FOLDER, null)
        set(value) = sp.edit { putString(KEY_LYRICS_FOLDER, value) }

    /** Length of the volume ramp between tracks, in milliseconds. 0 disables it. */
    var crossfadeMs: Int
        get() = sp.getInt(KEY_CROSSFADE, 0)
        set(value) = sp.edit { putInt(KEY_CROSSFADE, value.coerceIn(0, 12_000)) }

    /** Whether the "rate some artists" nudge has been turned down. */
    var ratingTipSeen: Boolean
        get() = sp.getBoolean(KEY_RATING_TIP, false)
        set(value) = sp.edit { putBoolean(KEY_RATING_TIP, value) }

    /** Whether the first-run explanation has been dismissed. */
    var welcomeSeen: Boolean
        get() = sp.getBoolean(KEY_WELCOME, false)
        set(value) = sp.edit { putBoolean(KEY_WELCOME, value) }

    /** Whether the one-off pointer to the tag repair tool has been shown. */
    var tagTipSeen: Boolean
        get() = sp.getBoolean(KEY_TAG_TIP, false)
        set(value) = sp.edit { putBoolean(KEY_TAG_TIP, value) }

    /**
     * How many tag corrections were waiting when the home banner about them
     * was closed. It comes back only when there are more than that - new
     * downloads - and not for the same ones again.
     */
    var tagFixBannerDismissedAt: Int
        get() = sp.getInt(KEY_TAG_FIX_BANNER, 0)
        set(value) = sp.edit { putInt(KEY_TAG_FIX_BANNER, value) }

    /**
     * Also strip Latin script leftovers from song names - producer credits, an
     * English gloss, whatever the video page's heading left behind.
     *
     * On by default: it only affects what the repair screen proposes, and every
     * proposal is shown before anything is applied.
     */
    var tagStripForeign: Boolean
        get() = sp.getBoolean(KEY_STRIP_FOREIGN, true)
        set(value) = sp.edit { putBoolean(KEY_STRIP_FOREIGN, value) }

    /**
     * Write corrections into the mp3 files themselves rather than only into the
     * app's own database.
     *
     * Off, and it stays off unless the user goes looking for it. Everything else
     * the app does is reversible from inside the app; this one reaches out and
     * edits files that other programs own, on a machine where the music may be
     * the only copy. The default has to be the one that changes nothing.
     */
    var writeTagsToFiles: Boolean
        get() = sp.getBoolean(KEY_WRITE_TAGS, false)
        set(value) = sp.edit { putBoolean(KEY_WRITE_TAGS, value) }

    /** System equaliser: on or off. */
    var eqEnabled: Boolean
        get() = sp.getBoolean(KEY_EQ_ON, false)
        set(value) = sp.edit { putBoolean(KEY_EQ_ON, value) }

    /** Index into the device's own presets, or -1 once bands are set by hand. */
    var eqPreset: Int
        get() = sp.getInt(KEY_EQ_PRESET, -1)
        set(value) = sp.edit { putInt(KEY_EQ_PRESET, value) }

    /** Per band gain in millibels. Band count varies by device. */
    var eqBands: List<Int>
        get() = sp.getString(KEY_EQ_BANDS, null)
            ?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }
            .orEmpty()
        set(value) = sp.edit { putString(KEY_EQ_BANDS, value.joinToString(",")) }

    /**
     * Which equaliser is in charge: the app's own thirty one bands, or the
     * device's.
     *
     * Two, because they are good at different things. The app's is the same
     * everywhere and has the resolution to be useful; the device's may be
     * implemented in hardware below the mixer, which costs nothing and on a
     * few phones is tied into effects the manufacturer ships. Only one runs at
     * a time - stacking them would be two sets of filters fighting over the
     * same frequencies.
     */
    var eqUseGraphic: Boolean
        get() = sp.getBoolean(KEY_EQ_GRAPHIC, true)
        set(value) = sp.edit { putBoolean(KEY_EQ_GRAPHIC, value) }

    /** The thirty one band equaliser: on or off. */
    var graphicEqEnabled: Boolean
        get() = sp.getBoolean(KEY_GEQ_ON, false)
        set(value) = sp.edit { putBoolean(KEY_GEQ_ON, value) }

    /** Thirty one gains in millibels, one per ISO third octave centre. */
    var graphicEqBands: List<Int>
        get() {
            val stored = sp.getString(KEY_GEQ_BANDS, null)
                ?.split(',')
                ?.mapNotNull { it.trim().toIntOrNull() }
                .orEmpty()
            // Always the full length, whatever is on disk: a short or missing
            // list would otherwise have to be handled at every use.
            return List(EqBands.COUNT) { stored.getOrNull(it) ?: 0 }
        }
        set(value) = sp.edit { putString(KEY_GEQ_BANDS, value.joinToString(",")) }

    /**
     * Gain applied before the filters, in millibels.
     *
     * Its own control because boosting and turning down are different
     * intentions. Someone adding 8 dB of bass wants more bass, not a louder
     * track, and without this the only way to get one without the other is to
     * pull the other thirty sliders down by hand.
     */
    var graphicEqPreamp: Int
        get() = sp.getInt(KEY_GEQ_PREAMP, 0)
        set(value) = sp.edit { putInt(KEY_GEQ_PREAMP, value) }

    /**
     * Keep voice recordings out of the library.
     *
     * Call recordings, voice notes and WhatsApp audio are not music, and a
     * good number of recorders mark them as music anyway, so MediaStore's own
     * flag does not filter them. On by default, because a library full of
     * recorded phone calls is nobody's idea of a music player - and it is off
     * by a switch for the few who really do keep shiurim in those folders.
     */
    var skipRecordings: Boolean
        get() = sp.getBoolean(KEY_SKIP_RECORDINGS, true)
        set(value) = sp.edit { putBoolean(KEY_SKIP_RECORDINGS, value) }

    /**
     * Keep the mood row under the header while the feed scrolls beneath it.
     *
     * On, because that is what it has always done and an update should not
     * rearrange someone's home screen without being asked. Turning it off
     * sends the chips into the feed, where they scroll away with everything
     * else - which is what to do on a short screen, where a strip that never
     * leaves costs a fifth of the page for something pressed once a session.
     */
    var pinMoodRow: Boolean
        get() = sp.getBoolean(KEY_PIN_MOODS, true)
        set(value) = sp.edit { putBoolean(KEY_PIN_MOODS, value) }

    /**
     * What a tap on the cover in the player does: one of [ArtworkTap]. Until it is chosen, what the
     * older on/off switch said.
     */
    var artworkTap: String
        get() = sp.getString(KEY_ARTWORK_TAP_MODE, null)
            ?: if (tapArtworkToggles) ArtworkTap.TOGGLE else ArtworkTap.NONE
        set(value) = sp.edit { putString(KEY_ARTWORK_TAP_MODE, value) }

    /** Tap the artwork in the player to pause and carry on. */
    var tapArtworkToggles: Boolean
        get() = sp.getBoolean(KEY_TAP_ARTWORK, true)
        set(value) = sp.edit { putBoolean(KEY_TAP_ARTWORK, value) }

    /**
     * Pick up spoken word where it was left, without asking.
     *
     * Separate from [resumePrompt], which offers to jump back and disappears.
     * An hour of speech should simply continue; a three minute song should
     * not, which is why this only applies to what the detector called speech.
     */
    var resumeSpoken: Boolean
        get() = sp.getBoolean(KEY_RESUME_SPOKEN, true)
        set(value) = sp.edit { putBoolean(KEY_RESUME_SPOKEN, value) }

    /** In the radio, move on where the sound ends rather than play out silence at a track's end. */
    var trimRadioSilence: Boolean
        get() = sp.getBoolean(KEY_TRIM_SILENCE, true)
        set(value) = sp.edit { putBoolean(KEY_TRIM_SILENCE, value) }

    /** The player's own volume slider, 0..1, apart from the phone's. See playback.AppVolume. */
    var appVolume: Float
        get() = sp.getFloat(KEY_APP_VOLUME, 1f)
        set(value) = sp.edit { putFloat(KEY_APP_VOLUME, value) }

    /** Small-screen mode: the whole app drawn a size smaller. Off by default. See ui.Display. */
    /** An artist's page lists their songs under their albums, rather than as one list. */
    var artistByAlbum: Boolean
        get() = sp.getBoolean(KEY_ARTIST_BY_ALBUM, true)
        set(value) = sp.edit { putBoolean(KEY_ARTIST_BY_ALBUM, value) }

    var compactMode: Boolean
        get() = sp.getBoolean(KEY_COMPACT, false)
        set(value) = sp.edit { putBoolean(KEY_COMPACT, value) }

    /** From how many minutes a track counts as a medley, 0 for the title alone. See EngineTuning.medleyMinutes. */
    var medleyMinutes: Int
        get() = sp.getInt(KEY_MEDLEY_MINUTES, 0)
        set(value) = sp.edit { putInt(KEY_MEDLEY_MINUTES, value) }

    /** In the Omer and the Three Weeks, recommend vocal-only songs and nothing else. */
    var onlyVocalInSeason: Boolean
        get() = sp.getBoolean(KEY_ONLY_VOCAL, true)
        set(value) = sp.edit { putBoolean(KEY_ONLY_VOCAL, value) }

    /**
     * Show folders nested, the way they sit on the device, rather than as one
     * flat list of every folder that contains a file.
     *
     * On by default: it is how the files actually are, and the flat list is
     * only easier when there are few enough folders for the difference not to
     * matter - in which case the tree is no harder either.
     */
    /**
     * The folders the library is made of, as absolute paths; empty for the
     * whole device. A song elsewhere is left out as an excluded folder's is -
     * hidden, not forgotten: everything learned about it comes back with it.
     */
    var musicFolders: List<String>
        get() = sp.getString(KEY_MUSIC_FOLDERS, "").orEmpty()
            .split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        set(value) = sp.edit { putString(KEY_MUSIC_FOLDERS, value.joinToString("\n")) }

    /** The folders as a tab of their own on the bottom bar. */
    var foldersTab: Boolean
        get() = sp.getBoolean(KEY_FOLDERS_TAB, false)
        set(value) = sp.edit { putBoolean(KEY_FOLDERS_TAB, value) }

    var folderTree: Boolean
        get() = sp.getBoolean(KEY_FOLDER_TREE, true)
        set(value) = sp.edit { putBoolean(KEY_FOLDER_TREE, value) }

    /**
     * Styles that must never share a mix, one rule per line.
     *
     * See [com.elchanan.rhythm.engine.Styles.Separations] for why this is a
     * setting and not something the engine works out for itself.
     */
    var styleSeparations: String
        get() = sp.getString(KEY_SEPARATIONS, null) ?: Styles.DEFAULT_SEPARATIONS
        set(value) = sp.edit { putString(KEY_SEPARATIONS, value) }

    /** Even out how loud tracks are relative to one another. */
    var normalizeVolume: Boolean
        get() = sp.getBoolean(KEY_NORMALIZE, true)
        set(value) = sp.edit { putBoolean(KEY_NORMALIZE, value) }

    /** ExoPlayer's built in silence skipper - kills dead air inside and around tracks. */
    var skipSilence: Boolean
        get() = sp.getBoolean(KEY_SKIP_SILENCE, false)
        set(value) = sp.edit { putBoolean(KEY_SKIP_SILENCE, value) }

    var autoRadio: Boolean
        get() = sp.getBoolean(KEY_AUTO_RADIO, true)
        set(value) = sp.edit { putBoolean(KEY_AUTO_RADIO, value) }

    var lastScanAt: Long
        get() = sp.getLong(KEY_LAST_SCAN, 0L)
        set(value) = sp.edit { putLong(KEY_LAST_SCAN, value) }

    var feedSeed: Int
        get() = sp.getInt(KEY_FEED_SEED, 1)
        set(value) = sp.edit { putInt(KEY_FEED_SEED, value) }

    /**
     * The mood the home feed last said the listener leans towards.
     *
     * Remembered so the shelf stays consistent between refreshes. A statement
     * about someone's taste that changes every time the page is rebuilt is
     * not a statement about their taste.
     */
    var lastMood: String
        get() = sp.getString(KEY_LAST_MOOD, "").orEmpty()
        set(value) = sp.edit { putString(KEY_LAST_MOOD, value) }

    /** When [lastMood] was chosen, 0 when not known - an install from before this was kept. */
    var lastMoodAt: Long
        get() = sp.getLong(KEY_LAST_MOOD_AT, 0L)
        set(value) = sp.edit { putLong(KEY_LAST_MOOD_AT, value) }

    /**
     * The signal weights learned from this listener's history, encoded by
     * SignalWeights.encode, or empty for the defaults. Only ever written when
     * the report card showed they predict better on held-out artists.
     */
    var learnedWeights: String
        get() = sp.getString(KEY_LEARNED_WEIGHTS, "").orEmpty()
        set(value) = sp.edit { putString(KEY_LEARNED_WEIGHTS, value) }

    var onboarded: Boolean
        get() = sp.getBoolean(KEY_ONBOARDED, false)
        set(value) = sp.edit { putBoolean(KEY_ONBOARDED, value) }

    // -----------------------------------------------------------------------
    // what the screens show
    // -----------------------------------------------------------------------

    /**
     * Which home shelves are switched on, by [ShelfKind] key.
     *
     * Absent means "never chosen", which is not the same as "all off" - a
     * missing value has to read as everything enabled or a fresh install would
     * open onto an empty home screen.
     */
    var homeShelves: Set<String>
        get() = sp.getString(KEY_HOME_SHELVES, null)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: ShelfKind.ALL_KEYS
        set(value) = sp.edit { putString(KEY_HOME_SHELVES, value.joinToString(",")) }

    fun isShelfEnabled(kind: ShelfKind?): Boolean =
        kind == null || kind.key in homeShelves

    /**
     * Let the learned taste break ties in search results.
     *
     * Only ties: text relevance always outranks it, so a title typed in full
     * still comes first whatever the engine thinks of it.
     */
    var searchPersonalized: Boolean
        get() = sp.getBoolean(KEY_SEARCH_PERSONAL, true)
        set(value) = sp.edit { putBoolean(KEY_SEARCH_PERSONAL, value) }

    /**
     * Also look inside the words of the song.
     *
     * On, because it is the thing people remember when they cannot remember a
     * title. It costs a database lookup per search, which is why the results
     * arrive after the title matches rather than with them.
     */
    var searchLyrics: Boolean
        get() = sp.getBoolean(KEY_SEARCH_LYRICS, true)
        set(value) = sp.edit { putBoolean(KEY_SEARCH_LYRICS, value) }

    /**
     * Whether starting a song opens the full player.
     *
     * Off, which is what the app does today: playback starts and the mini
     * player appears at the bottom, leaving the list you were browsing where it
     * was. Turning it on suits someone who plays one song at a time; leaving it
     * off suits someone working through a list.
     */
    var openPlayerOnPlay: Boolean
        get() = sp.getBoolean(KEY_OPEN_ON_PLAY, false)
        set(value) = sp.edit { putBoolean(KEY_OPEN_ON_PLAY, value) }

    /** Which library tab opens first. Playlists unless the user says otherwise. */
    var libraryFirstTab: String
        get() = sp.getString(KEY_LIBRARY_TAB, "PLAYLISTS").orEmpty().ifBlank { "PLAYLISTS" }
        set(value) = sp.edit { putString(KEY_LIBRARY_TAB, value) }

    /**
     * Collapse songs that are the same recording stored twice.
     *
     * Off by default: hiding a file the user can see in their own folders is
     * surprising, and it should be their decision that the copy is redundant.
     */
    var hideDuplicates: Boolean
        get() = sp.getBoolean(KEY_HIDE_DUPES, false)
        set(value) = sp.edit { putBoolean(KEY_HIDE_DUPES, value) }

    /**
     * Pause when the volume reaches zero, resume when it comes back.
     *
     * Off by default because it takes control of playback away from the
     * transport, and somebody who turns the volume down to talk does not always
     * want the song to stop counting.
     */
    var pauseOnSilence: Boolean
        get() = sp.getBoolean(KEY_PAUSE_SILENT, false)
        set(value) = sp.edit { putBoolean(KEY_PAUSE_SILENT, value) }

    /**
     * Offer to pick a song up where it was left, the way Musicolet does.
     *
     * Off by default. It is a genuinely useful thing on long tracks and a
     * distraction on a three minute song, and only the person listening knows
     * which kind of library theirs is.
     */
    var resumePrompt: Boolean
        get() = sp.getBoolean(KEY_RESUME_PROMPT, false)
        set(value) = sp.edit { putBoolean(KEY_RESUME_PROMPT, value) }

    /**
     * Where each song was last left, as `songId:positionMs` pairs.
     *
     * Deliberately not a database table. These are worth nothing the moment the
     * song is finished, and a bounded list in preferences cannot grow into a
     * problem the way a table quietly does.
     */
    var resumePoints: Map<Long, Long>
        get() = sp.getString(KEY_RESUME_POINTS, null)
            ?.split(',')
            ?.mapNotNull { pair ->
                val parts = pair.split(':')
                val id = parts.getOrNull(0)?.toLongOrNull()
                val at = parts.getOrNull(1)?.toLongOrNull()
                if (id != null && at != null) id to at else null
            }
            ?.toMap()
            .orEmpty()
        set(value) {
            // Newest kept, and only a handful: this is a convenience, not history.
            val trimmed = value.entries.toList().takeLast(40)
            sp.edit {
                putString(KEY_RESUME_POINTS, trimmed.joinToString(",") { "${it.key}:${it.value}" })
            }
        }

    /**
     * Where each player control lives, as `key=placement` pairs.
     *
     * Stored as one string rather than a preference each, so adding a control
     * later does not need a migration - an unknown key is simply ignored and a
     * missing one falls back to its own default.
     */
    /** The song menu's arrangement: row key to placement, as [playerActions] stores its own. */
    var songMenu: Map<String, String>
        get() = sp.getString(KEY_SONG_MENU, null)
            ?.split(',')
            ?.mapNotNull { pair ->
                val parts = pair.split('=')
                if (parts.size == 2 && parts[0].isNotBlank()) parts[0].trim() to parts[1].trim()
                else null
            }
            ?.toMap()
            .orEmpty()
        set(value) = sp.edit {
            putString(KEY_SONG_MENU, value.entries.joinToString(",") { "${it.key}=${it.value}" })
        }

    /** The home screen's header steps aside while the feed scrolls down. */
    var collapseHomeHeader: Boolean
        get() = sp.getBoolean(KEY_COLLAPSE_HEADER, false)
        set(value) = sp.edit { putBoolean(KEY_COLLAPSE_HEADER, value) }

    /** A queue button beside the others at the top of the home screen. */
    var homeQueueButton: Boolean
        get() = sp.getBoolean(KEY_HOME_QUEUE, false)
        set(value) = sp.edit { putBoolean(KEY_HOME_QUEUE, value) }

    var playerActions: Map<String, String>
        get() = sp.getString(KEY_PLAYER_ACTIONS, null)
            ?.split(',')
            ?.mapNotNull { pair ->
                val parts = pair.split('=')
                if (parts.size == 2 && parts[0].isNotBlank()) parts[0].trim() to parts[1].trim()
                else null
            }
            ?.toMap()
            .orEmpty()
        set(value) = sp.edit {
            putString(KEY_PLAYER_ACTIONS, value.entries.joinToString(",") { "${it.key}=${it.value}" })
        }

    private companion object {
        const val KEY_MIN_DURATION = "min_duration_sec"
        const val KEY_DISCOVERY = "discovery"
        const val KEY_ARTIST_WEIGHT = "artist_weight"
        const val KEY_STYLE_WEIGHT = "style_weight"
        const val KEY_REPEAT_GUARD = "repeat_guard"
        const val KEY_AUTO_RADIO = "auto_radio"
        const val KEY_LAST_SCAN = "last_scan"
        const val KEY_FEED_SEED = "feed_seed"
        const val KEY_ONBOARDED = "onboarded"
        const val KEY_LAST_MOOD = "last_mood"
        const val KEY_LAST_MOOD_AT = "last_mood_at"
        const val KEY_LEARNED_WEIGHTS = "learned_weights"
        const val KEY_ACOUSTIC_WEIGHT = "acoustic_weight"
        const val KEY_AUTO_ANALYZE = "auto_analyze"
        const val KEY_EXCLUDED = "excluded_folders"
        const val KEY_QUEUE = "saved_queue"
        const val KEY_QUEUE_INDEX = "saved_queue_index"
        const val KEY_QUEUE_POSITION = "saved_queue_position"
        const val KEY_LYRICS_FOLDER = "lyrics_folder"
        const val KEY_CROSSFADE = "crossfade_ms"
        const val KEY_SKIP_SILENCE = "skip_silence"
        const val KEY_NORMALIZE = "normalize_volume"
        const val KEY_TAG_TIP = "tag_tip_seen"
        const val KEY_TAG_FIX_BANNER = "tag_fix_banner_dismissed_at"
        const val KEY_STRIP_FOREIGN = "tag_strip_foreign"
        const val KEY_WRITE_TAGS = "tag_write_to_files"
        const val KEY_HOME_SHELVES = "home_shelves"
        const val KEY_LIBRARY_TAB = "library_first_tab"
        const val KEY_HIDE_DUPES = "hide_duplicates"
        const val KEY_PAUSE_SILENT = "pause_on_silence"
        const val KEY_PLAYER_ACTIONS = "player_actions"
        const val KEY_SEARCH_PERSONAL = "search_personalized"
        const val KEY_SEARCH_LYRICS = "search_lyrics"
        const val KEY_OPEN_ON_PLAY = "open_player_on_play"
        const val KEY_RESUME_PROMPT = "resume_prompt"
        const val KEY_RESUME_POINTS = "resume_points"
        const val KEY_WELCOME = "welcome_seen"
        const val KEY_RATING_TIP = "rating_tip_seen"
        const val KEY_EQ_ON = "eq_enabled"
        const val KEY_EQ_PRESET = "eq_preset"
        const val KEY_EQ_BANDS = "eq_bands"
        const val KEY_EQ_GRAPHIC = "eq_use_graphic"
        const val KEY_GEQ_ON = "geq_enabled"
        const val KEY_GEQ_BANDS = "geq_bands"
        const val KEY_GEQ_PREAMP = "geq_preamp"
        const val KEY_SEPARATIONS = "style_separations"
        const val KEY_FOLDER_TREE = "folder_tree"
        const val KEY_SKIP_RECORDINGS = "skip_recordings"
        const val KEY_MIN_PLAY_SECONDS = "min_play_seconds"
        const val KEY_AUTO_LEARN = "auto_learn"
        const val KEY_PIN_MOODS = "pin_mood_row"
        const val KEY_TAP_ARTWORK = "tap_artwork_toggles"
        const val KEY_RESUME_SPOKEN = "resume_spoken"
        const val KEY_ONLY_VOCAL = "only_vocal_in_season"
        const val KEY_MEDLEY_MINUTES = "medley_minutes"
        const val KEY_COMPACT = "compact_mode"
        const val KEY_SONG_MENU = "song_menu"
        const val KEY_HOME_QUEUE = "home_queue_button"
        const val KEY_COLLAPSE_HEADER = "collapse_home_header"
        const val KEY_MUSIC_FOLDERS = "music_folders"
        const val KEY_FOLDERS_TAB = "folders_tab"
        const val KEY_ARTIST_BY_ALBUM = "artist_by_album"
        const val KEY_ARTWORK_TAP_MODE = "artwork_tap_mode"
        const val KEY_APP_VOLUME = "app_volume"
        const val KEY_TRIM_SILENCE = "trim_radio_silence"
    }
}

/** What a tap on the cover in the player does. See [Prefs.artworkTap]. */
object ArtworkTap {
    /** Pause and carry on. */
    const val TOGGLE = "TOGGLE"
    /** Open the cover full size. */
    const val ZOOM = "ZOOM"
    const val NONE = "NONE"
}
