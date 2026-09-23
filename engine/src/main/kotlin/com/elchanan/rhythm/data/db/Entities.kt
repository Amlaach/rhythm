package com.elchanan.rhythm.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single audio file discovered on the device.
 * The row is rebuilt on every media scan, so nothing that the user creates
 * (ratings, likes, statistics) is ever stored here.
 */
@Entity(
    tableName = "songs",
    indices = [Index("artistKey"), Index("albumId"), Index("titleLower")]
)
data class SongEntity(
    @PrimaryKey val id: Long,
    val title: String,
    val titleLower: String,
    val artistName: String,
    val artistKey: String,
    val albumName: String,
    val albumId: Long,
    val durationMs: Long,
    val trackNumber: Int,
    val year: Int,
    val genre: String?,
    val path: String,
    val folder: String,
    val dateAddedSec: Long,
    val sizeBytes: Long
)

/**
 * Everything the recommendation engine learns about a song.
 * Survives rescans because MediaStore ids are stable per file.
 */
@Entity(tableName = "song_stats")
data class SongStatsEntity(
    @PrimaryKey val songId: Long,
    val playCount: Int = 0,
    val skipCount: Int = 0,
    val completeCount: Int = 0,
    val listenedMs: Long = 0,
    val lastPlayedAt: Long = 0L,
    /** 1 = liked, -1 = disliked, 0 = neutral */
    val liked: Int = 0,
    val likedAt: Long = 0L,
    /** the user's own 1..5 score for this single song; 0 = not rated */
    val rating: Int = 0,
    /** style tags attached to this song alone, overriding the artist's */
    val styles: String = "",
    /**
     * Whether [styles] was written by the style learner rather than typed.
     *
     * The two have to be told apart because they deserve opposite treatment.
     * What the user typed is ground truth and must never be overwritten by
     * something derived from it. What the learner guessed is only as good as
     * the model that produced it, and that model gets better every time more
     * artists are tagged - so a guess made on the first run, when it had a
     * dozen examples, should not outlive the model that made it.
     *
     * Without this they were the same field, so the learner skipped every
     * song it had ever labelled and its own early mistakes were permanent.
     */
    val stylesAuto: Int = 0,
    /** play counts per time-of-day bucket: 0 night, 1 morning, 2 afternoon, 3 evening */
    val b0: Int = 0,
    val b1: Int = 0,
    val b2: Int = 0,
    val b3: Int = 0,
    /**
     * Plays on a Friday or a Saturday, and plays on the other five days.
     *
     * Two counters rather than seven. Seven would be the general answer and
     * would almost never reach significance - a song needs plays in a bucket
     * before the bucket means anything, and dividing a few dozen plays seven
     * ways leaves nothing anywhere. The split that carries the signal in a
     * library like this one is the weekend against the week, and two buckets
     * fill four times faster than seven.
     */
    val dWeekend: Int = 0,
    val dWeekday: Int = 0,
    /**
     * A genre the user set, replacing whatever the file said.
     *
     * The genre in a downloaded file is whoever tagged it's opinion, and on a
     * library built from downloads it is usually blank, wrong, or the name of
     * the site it came from. Empty means "use the file's".
     */
    val genre: String = "",
    /**
     * Whether this is speech rather than music: 1 yes, 0 no, -1 not decided.
     *
     * Set by the detector, and by the user when the detector is wrong. Kept
     * next to the rest of what is known about a track rather than worked out
     * fresh each time, because the audio evidence for it only exists while the
     * file is being analysed.
     */
    val spoken: Int = -1,
    /**
     * Moods the user said this song is, or is not: "CALM,-ENERGETIC". Read by
     * MoodMarks. What the user said always wins over what the audio suggests,
     * and the marks are what the mood reading learns this listener's ear from.
     */
    val moods: String = "",
    /**
     * Whether this is vocal-only music, by the user's word: 1 yes, 0 no, -1
     * left to the name and the sound. See Vocal.
     */
    val vocal: Int = -1,
    /**
     * On how many different days the song was played, and the last of them
     * as a local epoch day. Ten plays on ten days is a song someone loves;
     * ten plays in one evening can be one mood. Counted as plays arrive,
     * because the history that could tell them apart is trimmed.
     */
    val playDays: Int = 0,
    val lastPlayDay: Long = -1L,
    /**
     * Skips that came in a burst - the fourth or later within two minutes,
     * someone flicking through for something in particular. Counted inside
     * [skipCount] as well; the engine gives each of these a fraction of a
     * skip's weight.
     */
    val burstSkips: Int = 0
)

/** User supplied artist profile: rating 1..5 and free style tags. */
@Entity(tableName = "artists")
data class ArtistEntity(
    @PrimaryKey val artistKey: String,
    val displayName: String,
    /** 0 means "not rated yet" */
    val rating: Int = 0,
    /** comma separated style tags */
    val styles: String = "",
    val note: String = "",
    val updatedAt: Long = 0L
)

/**
 * Item-to-item co-occurrence learned from listening sessions.
 * Symmetric: every pair is written in both directions.
 */
@Entity(tableName = "affinity", primaryKeys = ["a", "b"])
data class AffinityEntity(
    val a: Long,
    val b: Long,
    val weight: Double,
    val updatedAt: Long
)

/**
 * Directed sequence memory: "after A, B was played".
 * Unlike [AffinityEntity] this is asymmetric - A -> B and B -> A are
 * different rows with different weights.
 */
@Entity(tableName = "transitions", primaryKeys = ["a", "b"])
data class TransitionEntity(
    val a: Long,
    val b: Long,
    /** how often B followed A and was actually listened to */
    val weight: Double,
    /** how often B followed A and was skipped away from */
    val penalty: Double,
    val updatedAt: Long
)

/**
 * Everything measured from the audio itself, on device, once per file.
 * Nothing here comes from a server - it is all computed by [com.elchanan.rhythm.engine.AudioAnalyzer].
 */
@Entity(tableName = "audio_features")
data class AudioFeatureEntity(
    @PrimaryKey val songId: Long,
    val analyzedAt: Long,
    /** estimated beats per minute, 0 when the estimate failed */
    val bpm: Float,
    /** 0..1, how peaked the tempo autocorrelation was */
    val bpmConfidence: Float,
    /** pitch class 0..11 (0 = C), -1 when unknown */
    val musicalKey: Int,
    /** 1 major, 0 minor, -1 unknown */
    val mode: Int,
    /** mean RMS of the excerpt */
    val energy: Float,
    /** spectral centroid divided by nyquist: how bright the mix is */
    val brightness: Float,
    /** spectral flatness: 0 tonal, 1 noise-like */
    val flatness: Float,
    /** std(rms)/mean(rms): how much the loudness moves */
    val dynamics: Float,
    /** detected onsets per second: how percussive it is */
    val onsetRate: Float,
    /** 12 comma separated values, rotated so index 0 is the tonic */
    val chroma: String,
    /** 12 comma separated MFCC means - the timbre fingerprint */
    val timbre: String,
    /** 12 comma separated MFCC standard deviations */
    val timbreVar: String,
    /**
     * How the track moves over its length, as six comma separated numbers:
     * energy rise, energy spread, brightness rise, onset rise, timbre drift and
     * loud-to-quiet contrast. Averaged features describe what a song is made of;
     * these describe what it does.
     */
    val shape: String = "",
    /**
     * Index into MusicalMode, or -1 when undetermined. Major and minor alone
     * cannot tell a niggun from a pop ballad; the shtaygerim and the maqam
     * families can.
     */
    val scaleMode: Int = -1,
    /** How far the winning mode beat the runner up, 0..1. */
    val scaleConfidence: Float = 0f,
    /**
     * 24 comma separated values: the same chroma at quarter tone resolution,
     * rotated to the tonic. Twelve bins round a neutral third to its nearest
     * semitone, which erases the very interval that identifies Rast or Bayati.
     */
    val chroma24: String = "",
    /**
     * What the tagging model heard, as `classIndex:score` pairs.
     *
     * Only the classes that fired are kept. All 521 would be about two
     * kilobytes of mostly zeros per song - for music the distribution is
     * sharply peaked, so the top slice holds everything a classifier can use.
     *
     * Empty where the model failed to load, and empty for anything analysed
     * before the model arrived. Both cases have to read as "not known" rather
     * than "nothing there".
     */
    val tags: String = "",
    /**
     * YAMNet's 1024 channel summary of the recording, packed by [SoundPrint].
     * Empty means never made; [SoundPrint.TRIED] means attempted and failed,
     * so the pass that fills these in does not retry it for ever.
     */
    val soundPrint: String = "",
    /**
     * Discogs-EffNet's 1280 value summary, packed by MusicPrint. Empty means
     * never made; MusicPrint.TRIED means attempted and failed.
     */
    val musicPrint: String = "",
    /** What MTG's mood heads read off [musicPrint], encoded by MusicMoods. */
    val musicMoods: String = ""
)

/**
 * Where the listener stopped, and any places they marked on the way.
 *
 * Songs do not need this - a song is three minutes and starting it again
 * costs nothing. An hour of speech is a different thing entirely: losing the
 * position means finding it again by dragging a bar, which is the single most
 * annoying thing a player can do to someone who listens to shiurim.
 *
 * Kept in its own table rather than as a column on the stats, because a
 * bookmark is a list and a position is a single value, and because a position
 * has to be written every few seconds while playing - which is a poor reason
 * to rewrite a row that also holds ratings and play counts.
 */
@Entity(tableName = "playback_positions")
data class PlaybackPositionEntity(
    @PrimaryKey val songId: Long,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    /** True once it has been heard to the end, so it starts over next time. */
    val finished: Boolean = false
)

/**
 * A place in a track the listener marked on purpose.
 *
 * @param label what they called it, or empty for a plain mark.
 */
@Entity(tableName = "bookmarks", indices = [Index("songId")])
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: Long,
    val positionMs: Long,
    val label: String = "",
    val createdAt: Long
)

@Entity(tableName = "history", indices = [Index("playedAt"), Index("songId")])
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: Long,
    val playedAt: Long,
    val completed: Boolean,
    val listenedMs: Long
)

/**
 * A correction to what a file's own tags claim.
 *
 * Deliberately kept beside the library rather than written back into the mp3.
 * Editing tags in place needs storage permission that modern Android grants
 * only per file, and a write that fails halfway damages the only copy of the
 * music. A blank field means "keep whatever the file said".
 */
@Entity(tableName = "tag_overrides")
data class TagOverrideEntity(
    @PrimaryKey val songId: Long,
    val title: String = "",
    val artistName: String = "",
    val albumName: String = ""
)

/**
 * Lyrics for one song. [synced] holds LRC content when timestamps are known,
 * [text] always holds the plain fallback.
 */
@Entity(tableName = "lyrics")
data class LyricsEntity(
    @PrimaryKey val songId: Long,
    val text: String,
    val synced: String,
    /** embedded | file | manual | none */
    val source: String,
    val updatedAt: Long
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long
)

@Entity(tableName = "playlist_items", indices = [Index("playlistId")])
data class PlaylistItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val songId: Long,
    val position: Int
)
