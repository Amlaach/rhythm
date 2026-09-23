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

    @Query("SELECT COUNT(*) FROM song_stats WHERE stylesAuto = 1 AND styles != ''")
    suspend fun autoStyledCount(): Int

    /** Drops the guessed tags only; anything typed has stylesAuto = 0. */
    @Query("UPDATE song_stats SET styles = '', stylesAuto = 0 WHERE stylesAuto = 1")
    suspend fun clearAutoStyles()

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
            lastPlayedAt = 0, b0 = 0, b1 = 0, b2 = 0, b3 = 0,
            dWeekend = 0, dWeekday = 0
        WHERE songId = :id
        """
    )
    suspend fun resetPlayCount(id: Long)

    @Query(
        """
        UPDATE song_stats
        SET playCount = 0, skipCount = 0, completeCount = 0, listenedMs = 0,
            lastPlayedAt = 0, b0 = 0, b1 = 0, b2 = 0, b3 = 0,
            dWeekend = 0, dWeekday = 0
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

    @Query("UPDATE song_stats SET vocal = :vocal WHERE songId = :id")
    suspend fun setVocal(id: Long, vocal: Int)

    @Query("UPDATE song_stats SET moods = :moods WHERE songId = :id")
    suspend fun setMoods(id: Long, moods: String)

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

    @Query("SELECT * FROM affinity WHERE a = :a AND b = :b")
    suspend fun affinityEdge(a: Long, b: Long): AffinityEntity?

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

    /** One Room transaction, so an interrupted import is all old or all new. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putFeatures(features: List<AudioFeatureEntity>)

    /**
     * Songs whose analysis is complete, sound print included - or settled as
     * impossible. A row analysed before prints existed is not done: the pass
     * goes back for it, and progress has to say so rather than read 100%.
     */
    @Query(
        "SELECT COUNT(*) FROM audio_features WHERE " +
            "(soundPrint != '' AND (:music = 0 OR musicPrint != '')) OR energy <= 0"
    )
    suspend fun featureCount(music: Boolean): Int

    @Query("DELETE FROM audio_features")
    suspend fun clearFeatures()

    @Query("SELECT * FROM audio_features WHERE songId = :id")
    suspend fun feature(id: Long): AudioFeatureEntity?

    /**
     * Songs never analysed, then songs analysed before the sound print existed.
     *
     * The second half is what fills prints in for a library measured long ago.
     * It cannot loop: every analysis writes either a print or
     * [com.elchanan.rhythm.engine.SoundPrint.TRIED], and a file that will not
     * decode is marked tried as well, so no row is empty twice. Placeholder
     * rows for undecodable files have no energy and are never picked.
     */
    //
    // Walked in id order from a cursor rather than asked for "the first
    // twelve" each time. A song that cannot be reached - on a card that is out
    // - gets no row, so it stayed first in line for ever; once twelve of those
    // filled a batch the pass concluded there was nothing to do and stopped,
    // however many reachable songs were waiting behind them.
    @Query(
        "SELECT * FROM songs WHERE id > :after AND (" +
            "id NOT IN (SELECT songId FROM audio_features) " +
            "OR id IN (SELECT songId FROM audio_features WHERE " +
            "(soundPrint = '' OR (:music = 1 AND musicPrint = '')) AND energy > 0)" +
            ") ORDER BY id LIMIT :limit"
    )
    suspend fun songsNeedingAnalysis(after: Long, limit: Int, music: Boolean): List<SongEntity>

    /**
     * Marks an already analysed song as having had its print attempted.
     *
     * For a re-analysis that fails - a file that decoded once and does not
     * now. Its measurements are kept; writing the blank placeholder over
     * them, as a first analysis would, would throw away good data.
     *
     * @return rows changed: 0 when there was no analysed row to keep.
     */
    @Query(
        "UPDATE audio_features SET " +
            "soundPrint = CASE WHEN soundPrint = '' THEN '-' ELSE soundPrint END, " +
            "musicPrint = CASE WHEN musicPrint = '' THEN '-' ELSE musicPrint END " +
            "WHERE songId = :id AND energy > 0"
    )
    suspend fun markPrintTried(id: Long): Int

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

    /** A file that is gone leaves every playlist it was in. */
    @Query("DELETE FROM playlist_items WHERE songId IN (:ids)")
    suspend fun removeFromAllPlaylists(ids: List<Long>)

    // ---------- keeping what was learned when the id underneath it moves ----------

    /**
     * Songs that are no longer on the device, by id.
     *
     * Only ever called with ids the scan proved absent on a volume that is
     * actually mounted - see MusicRepository.rescan. Everything keyed to them
     * goes with them, because an id nothing points at is an id that will be
     * handed to a different file one day.
     */
    @Query("DELETE FROM songs WHERE id IN (:ids)")
    suspend fun deleteSongsById(ids: List<Long>)

    /**
     * Moves everything learned about a song from one id to another.
     *
     * MediaStore ids are not stable. Pull a memory card out and put it back,
     * or let the system reindex it, and the same file comes back under a new
     * number - at which point every rating, play count, like, bookmark and
     * measurement keyed to the old number belongs to nothing. Matching on the
     * path instead and carrying the rows across is what makes months of
     * listening survive the card being moved.
     *
     * OR REPLACE rather than plain UPDATE: if a row already exists under the
     * new id - a fresh scan will have written a bare one - the carried row
     * wins, which is the whole point.
     */
    @Query("UPDATE OR REPLACE song_stats SET songId = :to WHERE songId = :from")
    suspend fun moveStats(from: Long, to: Long)

    @Query("UPDATE OR REPLACE audio_features SET songId = :to WHERE songId = :from")
    suspend fun moveFeature(from: Long, to: Long)

    @Query("UPDATE OR REPLACE playback_positions SET songId = :to WHERE songId = :from")
    suspend fun movePosition(from: Long, to: Long)

    @Query("UPDATE OR REPLACE lyrics SET songId = :to WHERE songId = :from")
    suspend fun moveLyrics(from: Long, to: Long)

    @Query("UPDATE OR REPLACE tag_overrides SET songId = :to WHERE songId = :from")
    suspend fun moveOverride(from: Long, to: Long)

    @Query("UPDATE bookmarks SET songId = :to WHERE songId = :from")
    suspend fun moveBookmarks(from: Long, to: Long)

    @Query("UPDATE history SET songId = :to WHERE songId = :from")
    suspend fun moveHistory(from: Long, to: Long)

    @Query("UPDATE OR REPLACE playlist_items SET songId = :to WHERE songId = :from")
    suspend fun movePlaylistItems(from: Long, to: Long)

    /**
     * The learned edges, which are keyed by a pair of ids rather than one.
     *
     * OR IGNORE and not OR REPLACE: an edge that would collide with one that
     * already exists is dropped rather than overwriting it, because the
     * surviving edge carries its own weight and the two cannot be added up
     * from here. Losing one edge of thousands costs nothing; losing the
     * weight on the one that stays would.
     */
    @Query("UPDATE OR IGNORE affinity SET a = :to WHERE a = :from")
    suspend fun moveAffinityA(from: Long, to: Long)

    @Query("UPDATE OR IGNORE affinity SET b = :to WHERE b = :from")
    suspend fun moveAffinityB(from: Long, to: Long)

    @Query("UPDATE OR IGNORE transitions SET a = :to WHERE a = :from")
    suspend fun moveTransitionA(from: Long, to: Long)

    @Query("UPDATE OR IGNORE transitions SET b = :to WHERE b = :from")
    suspend fun moveTransitionB(from: Long, to: Long)
}
