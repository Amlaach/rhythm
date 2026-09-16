package com.elchanan.rhythm.data

import android.content.Context
import androidx.core.content.edit

/**
 * Small, boring settings store. Everything the recommendation engine can be
 * tuned with lives here so the user can steer it without touching code.
 */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("rhythm_prefs", Context.MODE_PRIVATE)

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

    var onboarded: Boolean
        get() = sp.getBoolean(KEY_ONBOARDED, false)
        set(value) = sp.edit { putBoolean(KEY_ONBOARDED, value) }

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
        const val KEY_WELCOME = "welcome_seen"
        const val KEY_RATING_TIP = "rating_tip_seen"
        const val KEY_EQ_ON = "eq_enabled"
        const val KEY_EQ_PRESET = "eq_preset"
        const val KEY_EQ_BANDS = "eq_bands"
    }
}
