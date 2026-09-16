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
