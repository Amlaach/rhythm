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
    /** play counts per time-of-day bucket: 0 night, 1 morning, 2 afternoon, 3 evening */
    val b0: Int = 0,
    val b1: Int = 0,
    val b2: Int = 0,
    val b3: Int = 0
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
    val timbreVar: String
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
