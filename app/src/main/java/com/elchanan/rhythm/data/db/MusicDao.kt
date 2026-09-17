package com.elchanan.rhythm.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MusicDao {

    // ---------- songs ----------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSongs(songs: List<SongEntity>)

    @Query("DELETE FROM songs")
    suspend fun clearSongs()

    @Query("SELECT * FROM songs")
    fun observeSongs(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs")
    suspend fun allSongs(): List<SongEntity>

    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun song(id: Long): SongEntity?

    @Query("SELECT COUNT(*) FROM songs")
    suspend fun songCount(): Int

    // ---------- stats ----------

    @Query("SELECT * FROM song_stats")
    fun observeStats(): Flow<List<SongStatsEntity>>

    @Query("SELECT * FROM song_stats")
    suspend fun allStats(): List<SongStatsEntity>

    @Query("SELECT * FROM song_stats WHERE songId = :id")
    suspend fun stats(id: Long): SongStatsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putStats(stats: SongStatsEntity)

    @Query("UPDATE song_stats SET liked = 0, likedAt = 0")
    suspend fun clearAllLikes()

    /**
     * Forgets that a song was ever played, without forgetting what the user
     * said about it.
     *
     * The like, the rating and the style tags are choices the user made and
     * are left alone; the counts are a record of behaviour, and the whole
     * point of offering to clear them is that the record is sometimes wrong -
     * a song left on repeat by accident, or a phone handed to someone else.
     * The time-of-day buckets go too, or the engine would keep recommending
     * by an hour that no longer has any plays behind it.
     */
    @Query(
        """
        UPDATE song_stats
        SET playCount = 0, skipCount = 0, completeCount = 0, listenedMs = 0,
            lastPlayedAt = 0, b0 = 0, b1 = 0, b2 = 0, b3 = 0
        WHERE songId = :id
        """
    )
    suspend fun resetPlayCount(id: Long)

    @Query(
        """
        UPDATE song_stats
        SET playCount = 0, skipCount = 0, completeCount = 0, listenedMs = 0,
            lastPlayedAt = 0, b0 = 0, b1 = 0, b2 = 0, b3 = 0
        WHERE songId IN (:ids)
        """
    )
    suspend fun resetPlayCounts(ids: List<Long>)

    // ---------- positions and bookmarks ----------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putPosition(position: PlaybackPositionEntity)

    @Query("SELECT * FROM playback_positions WHERE songId = :id")
    suspend fun position(id: Long): PlaybackPositionEntity?

    @Query("SELECT * FROM playback_positions WHERE finished = 0")
    fun observePositions(): Flow<List<PlaybackPositionEntity>>

    @Query("DELETE FROM playback_positions WHERE songId = :id")
    suspend fun clearPosition(id: Long)

    @Query("SELECT * FROM bookmarks WHERE songId = :id ORDER BY positionMs")
    fun observeBookmarks(id: Long): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    fun observeAllBookmarks(): Flow<List<BookmarkEntity>>

    @Insert
    suspend fun addBookmark(bookmark: BookmarkEntity): Long

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteBookmark(id: Long)

    @Query("UPDATE bookmarks SET label = :label WHERE id = :id")
    suspend fun renameBookmark(id: Long, label: String)

    @Query("DELETE FROM bookmarks WHERE songId = :id")
    suspend fun deleteBookmarksFor(id: Long)

    // ---------- genre and spoken word ----------

    @Query("UPDATE song_stats SET genre = :genre WHERE songId IN (:ids)")
    suspend fun setGenre(ids: List<Long>, genre: String)

    /**
     * Stats rows have to exist before they can be updated, and a song the user
     * has never played has none.
     */
    @Query("INSERT OR IGNORE INTO song_stats (songId) VALUES (:id)")
    suspend fun ensureStats(id: Long)

    @Query("UPDATE song_stats SET spoken = :spoken WHERE songId = :id")
    suspend fun setSpoken(id: Long, spoken: Int)

    @Query("DELETE FROM song_stats WHERE songId IN (:ids)")
    suspend fun deleteStats(ids: List<Long>)

    /** Ids only: the caller wants to clear them, not to read them. */
    @Query("SELECT id FROM songs WHERE artistKey = :key")
    suspend fun songIdsByArtist(key: String): List<Long>

    /** The history rows too, or "recently played" would still show it. */
    @Query("DELETE FROM history WHERE songId = :id")
    suspend fun clearHistoryFor(id: Long)

    @Query("DELETE FROM history WHERE songId IN (:ids)")
    suspend fun clearHistoryFor(ids: List<Long>)

    @Query("DELETE FROM song_stats")
    suspend fun clearStats()

    // ---------- artists ----------

    @Query("SELECT * FROM artists")
    fun observeArtists(): Flow<List<ArtistEntity>>

    @Query("SELECT * FROM artists")
    suspend fun allArtists(): List<ArtistEntity>

    @Query("SELECT * FROM artists WHERE artistKey = :key")
    suspend fun artist(key: String): ArtistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putArtist(artist: ArtistEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertArtistsIfMissing(artists: List<ArtistEntity>)

    // ---------- affinity ----------

    @Query("SELECT * FROM affinity")
    suspend fun allAffinity(): List<AffinityEntity>

    @Query("SELECT weight FROM affinity WHERE a = :a AND b = :b")
    suspend fun affinityWeight(a: Long, b: Long): Double?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAffinity(edge: AffinityEntity)

    @Query("DELETE FROM affinity WHERE weight < :min")
    suspend fun pruneAffinity(min: Double)

    @Query("DELETE FROM affinity")
    suspend fun clearAffinity()

    // ---------- transitions ----------

    @Query("SELECT * FROM transitions")
    suspend fun allTransitions(): List<TransitionEntity>

    @Query("SELECT * FROM transitions WHERE a = :a AND b = :b")
    suspend fun transition(a: Long, b: Long): TransitionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putTransition(edge: TransitionEntity)

    @Query("DELETE FROM transitions")
    suspend fun clearTransitions()

    /**
     * Drops the weakest edges once the table has grown past what the engine can
     * use.
     *
     * Both of these tables have one row per pair of songs that followed each
     * other, so they grow with listening rather than with the library, and
     * nothing was ever removing them. An edge seen once years ago carries no
     * signal the engine misses; the strong ones are the whole point.
     */
    @Query(
        "DELETE FROM transitions WHERE rowid NOT IN " +
            "(SELECT rowid FROM transitions ORDER BY weight DESC LIMIT :keep)"
    )
    suspend fun trimTransitions(keep: Int)

    @Query(
        "DELETE FROM affinity WHERE rowid NOT IN " +
            "(SELECT rowid FROM affinity ORDER BY weight DESC LIMIT :keep)"
    )
    suspend fun trimAffinity(keep: Int)

    // ---------- tag overrides ----------

    @Query("SELECT * FROM tag_overrides")
    suspend fun allOverrides(): List<TagOverrideEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putOverrides(rows: List<TagOverrideEntity>)

    @Query("DELETE FROM tag_overrides")
    suspend fun clearOverrides()

    // ---------- audio features ----------

    @Query("SELECT * FROM audio_features")
    fun observeFeatures(): Flow<List<AudioFeatureEntity>>

    @Query("SELECT * FROM audio_features")
    suspend fun allFeatures(): List<AudioFeatureEntity>

    @Query("SELECT songId FROM audio_features")
    suspend fun analyzedIds(): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putFeature(feature: AudioFeatureEntity)

    @Query("SELECT COUNT(*) FROM audio_features")
    suspend fun featureCount(): Int

    @Query("DELETE FROM audio_features")
    suspend fun clearFeatures()

    @Query("SELECT * FROM songs WHERE id NOT IN (SELECT songId FROM audio_features) LIMIT :limit")
    suspend fun songsNeedingAnalysis(limit: Int): List<SongEntity>

    // ---------- history ----------

    @Insert
    suspend fun insertHistory(item: HistoryEntity)

    @Query("SELECT * FROM history ORDER BY playedAt DESC LIMIT :limit")
    suspend fun recentHistory(limit: Int): List<HistoryEntity>

    @Query("SELECT * FROM history ORDER BY playedAt DESC LIMIT :limit")
    fun observeRecentHistory(limit: Int): Flow<List<HistoryEntity>>

    @Query("DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY playedAt DESC LIMIT :keep)")
    suspend fun trimHistory(keep: Int)

    @Query("DELETE FROM history")
    suspend fun clearHistory()

    // ---------- lyrics ----------

    @Query("SELECT * FROM lyrics WHERE songId = :songId")
    suspend fun lyrics(songId: Long): LyricsEntity?

    @Query("SELECT songId FROM lyrics WHERE text != '' OR synced != ''")
    fun observeLyricsIds(): Flow<List<Long>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putLyrics(lyrics: LyricsEntity)

    @Query("DELETE FROM lyrics WHERE songId = :songId")
    suspend fun deleteLyrics(songId: Long)

    @Query("SELECT COUNT(*) FROM lyrics WHERE text != '' OR synced != ''")
    suspend fun lyricsCount(): Int

    /**
     * Songs whose words contain [pattern], which arrives with its own wildcards.
     *
     * Matched in the database rather than in memory: lyrics are the largest
     * text this app stores, and pulling every one of them across on each
     * keystroke to look for three words would be the wrong trade by a wide
     * margin.
     */
    @Query("SELECT songId FROM lyrics WHERE text LIKE :pattern OR synced LIKE :pattern LIMIT 80")
    suspend fun songIdsWithLyrics(pattern: String): List<Long>

    // ---------- playlists ----------

    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun observePlaylists(): Flow<List<PlaylistEntity>>

    @Insert
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylist(id: Long)

    @Query("DELETE FROM playlist_items WHERE playlistId = :id")
    suspend fun deletePlaylistItems(id: Long)

    @Insert
    suspend fun insertPlaylistItem(item: PlaylistItemEntity)

    @Query("SELECT * FROM playlist_items")
    fun observePlaylistItems(): Flow<List<PlaylistItemEntity>>

    @Query("SELECT IFNULL(MAX(position), -1) + 1 FROM playlist_items WHERE playlistId = :id")
    suspend fun nextPosition(id: Long): Int

    @Query("DELETE FROM playlist_items WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun removeFromPlaylist(playlistId: Long, songId: Long)
}
