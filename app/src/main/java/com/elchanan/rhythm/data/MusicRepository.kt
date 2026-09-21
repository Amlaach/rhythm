package com.elchanan.rhythm.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.elchanan.rhythm.data.db.AffinityEntity
import com.elchanan.rhythm.data.db.ArtistEntity
import com.elchanan.rhythm.data.db.AudioFeatureEntity
import com.elchanan.rhythm.data.db.BookmarkEntity
import com.elchanan.rhythm.data.db.HistoryEntity
import com.elchanan.rhythm.data.db.LyricsEntity
import com.elchanan.rhythm.data.db.MusicDao
import com.elchanan.rhythm.data.db.PlaybackPositionEntity
import com.elchanan.rhythm.data.db.PlaylistEntity
import com.elchanan.rhythm.data.db.PlaylistItemEntity
import com.elchanan.rhythm.data.db.RhythmDatabase
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.data.db.SongStatsEntity
import com.elchanan.rhythm.data.db.TagOverrideEntity
import com.elchanan.rhythm.data.db.TransitionEntity
import com.elchanan.rhythm.engine.AcousticSpace
import com.elchanan.rhythm.engine.Loudness
import com.elchanan.rhythm.engine.Names
import com.elchanan.rhythm.engine.Recap
import com.elchanan.rhythm.engine.RecapData
import com.elchanan.rhythm.engine.Recommender
import com.elchanan.rhythm.engine.TransitionEdge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class MusicRepository(
    private val context: Context,
    private val dao: MusicDao,
    val prefs: Prefs
) {

    val songs: Flow<List<SongEntity>> = dao.observeSongs()
    val stats: Flow<List<SongStatsEntity>> = dao.observeStats()
    val artists: Flow<List<ArtistEntity>> = dao.observeArtists()
    val playlists: Flow<List<PlaylistEntity>> = dao.observePlaylists()
    val playlistItems: Flow<List<PlaylistItemEntity>> = dao.observePlaylistItems()
    val history: Flow<List<HistoryEntity>> = dao.observeRecentHistory(200)
    val features: Flow<List<AudioFeatureEntity>> = dao.observeFeatures()

    // -----------------------------------------------------------------------
    // scanning
    // -----------------------------------------------------------------------

    /**
     * What the last scan found, and what each filter removed on the way.
     *
     * Kept because "the app only sees 200 of my 2000 songs" is otherwise
     * impossible to answer. Every number below is a place a file can vanish,
     * and until they were visible the only way to tell which one had eaten a
     * library was to guess.
     */
    data class ScanReport(
        val onDevice: Int,
        val tooShort: Int,
        val inExcludedFolder: Int,
        val looksLikeRecording: Int,
        val kept: Int,
        val at: Long
    )

    private val _lastScan = MutableStateFlow<ScanReport?>(null)
    val lastScan: StateFlow<ScanReport?> = _lastScan.asStateFlow()

    suspend fun rescan(): Int = withContext(Dispatchers.IO) {
        val excluded = prefs.excludedFolders.map { it.lowercase() }
        val overrides = dao.allOverrides().associateBy { it.songId }
        val skipRecordings = prefs.skipRecordings
        val minMs = prefs.minDurationSec * 1000L

        val onDevice = MediaScanner.scan(context)
        var tooShort = 0
        var inExcluded = 0
        var recordings = 0

        val found = onDevice
            .filter { song ->
                // A length of zero means MediaStore has not read the file yet,
                // not that the file is short. Keeping it is the safe mistake:
                // the next scan corrects the length, whereas dropping it hides
                // a song with nothing to point at.
                val keep = song.durationMs <= 0L || song.durationMs >= minMs
                if (!keep) tooShort++
                keep
            }
            .filter { song ->
                val keep = excluded.none { pattern -> song.folder.lowercase().contains(pattern) }
                if (!keep) inExcluded++
                keep
            }
            .filter { song ->
                val keep = !skipRecordings ||
                    !Names.looksLikeRecording(
                        song.folder,
                        song.path.substringAfterLast('/')
                    )
                if (!keep) recordings++
                keep
            }
            .map { song -> applyOverride(song, overrides[song.id]) }

        // In one transaction, so the observers never see the moment between
        // the old library being cleared and the new one arriving. Without it
        // every rescan empties the home screen for an instant.
        RhythmDatabase.get(context).withTransaction {
            dao.clearSongs()
            found.chunked(400).forEach { dao.insertSongs(it) }
        }
        _lastScan.value = ScanReport(
            onDevice = onDevice.size,
            tooShort = tooShort,
            inExcludedFolder = inExcluded,
            looksLikeRecording = recordings,
            kept = found.size,
            at = System.currentTimeMillis()
        )
        // make sure every artist that exists on the device has a profile row,
        // so the rating screen can list them without inventing anything
        val artistRows = found
            .groupBy { it.artistKey }
            .map { (key, list) ->
                ArtistEntity(
                    artistKey = key,
                    displayName = Names.primaryArtist(list.first().artistName),
                    rating = 0,
                    styles = "",
                    note = "",
                    updatedAt = 0L
                )
            }
        artistRows.chunked(300).forEach { dao.insertArtistsIfMissing(it) }
        prefs.lastScanAt = System.currentTimeMillis()
        found.size
    }

    // -----------------------------------------------------------------------
    // feedback
    // -----------------------------------------------------------------------

    suspend fun setLike(songId: Long, value: Int) = withContext(Dispatchers.IO) {
        val current = dao.stats(songId) ?: SongStatsEntity(songId = songId)
        val next = if (current.liked == value) 0 else value
        dao.putStats(
            current.copy(
                liked = next,
                likedAt = if (next != 0) System.currentTimeMillis() else 0L
            )
        )
    }

    /** What this song is marked: 1 liked, -1 disliked, 0 neither. */
    suspend fun likeOf(songId: Long): Int =
        withContext(Dispatchers.IO) { dao.stats(songId)?.liked ?: 0 }

    suspend fun setSongRating(songId: Long, rating: Int) = withContext(Dispatchers.IO) {
        val current = dao.stats(songId) ?: SongStatsEntity(songId = songId)
        dao.putStats(current.copy(rating = if (current.rating == rating) 0 else rating))
    }

    /**
     * @param auto true when the learner produced these rather than the user.
     *   A hand edit always lands as false, which is what promotes a guess the
     *   user has since corrected into something the learner will not touch.
     */
    suspend fun setSongStyles(songId: Long, styles: String, auto: Boolean = false) =
        withContext(Dispatchers.IO) {
            val current = dao.stats(songId) ?: SongStatsEntity(songId = songId)
            dao.putStats(current.copy(styles = styles, stylesAuto = if (auto) 1 else 0))
        }

    /**
     * Forgets every style tag the app guessed, keeping every one that was typed.
     *
     * The point of telling the two apart. The model improves as more artists
     * are tagged, and without this the songs labelled on the first run - when
     * it had the least to go on - would keep those labels forever.
     *
     * @return how many songs were cleared.
     */
    suspend fun clearLearnedStyles(): Int = withContext(Dispatchers.IO) {
        val cleared = dao.autoStyledCount()
        dao.clearAutoStyles()
        cleared
    }

    // -----------------------------------------------------------------------
    // where the listener stopped, and what they marked
    // -----------------------------------------------------------------------

    /**
     * Remembers a position, or forgets it once the end is near.
     *
     * The last few seconds count as finished. Someone who hears a shiur out
     * does not want it to resume three seconds from the end next time, and
     * players that do this are a small, recurring annoyance.
     */
    suspend fun savePosition(songId: Long, positionMs: Long, durationMs: Long) =
        withContext(Dispatchers.IO) {
            val nearEnd = durationMs > 0 && positionMs >= durationMs - END_MARGIN_MS
            if (nearEnd || positionMs < START_MARGIN_MS) {
                dao.clearPosition(songId)
                return@withContext
            }
            dao.putPosition(
                PlaybackPositionEntity(
                    songId = songId,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }

    suspend fun position(songId: Long): PlaybackPositionEntity? =
        withContext(Dispatchers.IO) { dao.position(songId) }

    suspend fun clearPosition(songId: Long) = withContext(Dispatchers.IO) {
        dao.clearPosition(songId)
    }

    fun positions(): Flow<List<PlaybackPositionEntity>> = dao.observePositions()

    fun bookmarks(songId: Long): Flow<List<BookmarkEntity>> = dao.observeBookmarks(songId)

    fun allBookmarks(): Flow<List<BookmarkEntity>> = dao.observeAllBookmarks()

    suspend fun addBookmark(songId: Long, positionMs: Long, label: String) =
        withContext(Dispatchers.IO) {
            dao.addBookmark(
                BookmarkEntity(
                    songId = songId,
                    positionMs = positionMs,
                    label = label,
                    createdAt = System.currentTimeMillis()
                )
            )
        }

    suspend fun deleteBookmark(id: Long) = withContext(Dispatchers.IO) { dao.deleteBookmark(id) }

    suspend fun renameBookmark(id: Long, label: String) =
        withContext(Dispatchers.IO) { dao.renameBookmark(id, label) }

    // -----------------------------------------------------------------------
    // genre and spoken word
    // -----------------------------------------------------------------------

    /** Sets a genre on many songs at once, creating stats rows as needed. */
    suspend fun setGenre(songIds: List<Long>, genre: String) = withContext(Dispatchers.IO) {
        for (id in songIds) dao.ensureStats(id)
        songIds.chunked(400).forEach { dao.setGenre(it, genre.trim()) }
    }

    /**
     * Drops everything the app knew about songs whose files are gone.
     *
     * A rescan would remove the song rows on its own, but not the stats, the
     * positions, the bookmarks or the learned edges - those are keyed on an id
     * MediaStore will eventually hand to a different file, and a stale row
     * would then attach one song's history to another.
     */
    suspend fun forgetSongs(ids: List<Long>) = withContext(Dispatchers.IO) {
        for (id in ids) {
            dao.clearPosition(id)
            dao.deleteBookmarksFor(id)
            dao.clearHistoryFor(id)
        }
        ids.chunked(400).forEach { dao.deleteStats(it) }
    }

    /** The user overruling the speech detector, either way. */
    suspend fun setSpoken(songId: Long, spoken: Boolean) = withContext(Dispatchers.IO) {
        dao.ensureStats(songId)
        dao.setSpoken(songId, if (spoken) 1 else 0)
    }

    /**
     * Clears the listening record for one song, keeping the user's own marks.
     *
     * Counts get inflated by things that were not really listening - a song
     * left on repeat overnight, a phone lent to someone - and once they are
     * wrong there is no arguing with the shelves built on top of them. This is
     * the way to argue with them. The like, the rating and the tags stay:
     * those were said on purpose.
     */
    suspend fun resetPlayCount(songId: Long) = withContext(Dispatchers.IO) {
        dao.resetPlayCount(songId)
        dao.clearHistoryFor(songId)
    }

    /**
     * The same for everything by one artist.
     *
     * @return how many songs were cleared.
     */
    suspend fun resetArtistPlayCounts(artistKey: String): Int = withContext(Dispatchers.IO) {
        val ids = dao.songIdsByArtist(artistKey)
        // SQLite caps how many values one statement may bind, and a prolific
        // artist in a large library goes past it.
        ids.chunked(400).forEach { chunk ->
            dao.resetPlayCounts(chunk)
            dao.clearHistoryFor(chunk)
        }
        ids.size
    }

    suspend fun rateArtist(key: String, displayName: String, rating: Int, styles: String, note: String) =
        withContext(Dispatchers.IO) {
            val existing = dao.artist(key)
            dao.putArtist(
                (existing ?: ArtistEntity(artistKey = key, displayName = displayName)).copy(
                    displayName = displayName,
                    rating = rating,
                    styles = styles,
                    note = note,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }

    // -----------------------------------------------------------------------
    // playback telemetry - called from the playback service
    // -----------------------------------------------------------------------

    suspend fun recordPlay(
        songId: Long,
        listenedMs: Long,
        completed: Boolean,
        sessionTail: List<Long>
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val current = dao.stats(songId) ?: SongStatsEntity(songId = songId)
        val bucket = Recommender.bucketOf(now)
        dao.putStats(
            current.copy(
                playCount = current.playCount + 1,
                completeCount = current.completeCount + if (completed) 1 else 0,
                listenedMs = current.listenedMs + listenedMs,
                lastPlayedAt = now,
                b0 = current.b0 + if (bucket == 0) 1 else 0,
                b1 = current.b1 + if (bucket == 1) 1 else 0,
                b2 = current.b2 + if (bucket == 2) 1 else 0,
                b3 = current.b3 + if (bucket == 3) 1 else 0,
                dWeekend = current.dWeekend + if (Recommender.isWeekend(now)) 1 else 0,
                dWeekday = current.dWeekday + if (Recommender.isWeekend(now)) 0 else 1
            )
        )
        dao.insertHistory(HistoryEntity(songId = songId, playedAt = now, completed = completed, listenedMs = listenedMs))
        dao.trimHistory(2000)

        // co-occurrence: the closer two songs were played, the heavier the edge
        sessionTail.forEachIndexed { index, other ->
            if (other == songId) return@forEachIndexed

            val w = 1.0 / (1.0 + index)
            bump(songId, other, w, now)
            bump(other, songId, w, now)
        }

        // These two tables have a row per pair of songs heard together, so they
        // grow with how much someone listens rather than with how much music
        // they own - and nothing was trimming them. Checked rarely because the
        // count query is not free and the tables only ever grow slowly.
        playsSinceTrim++
        if (playsSinceTrim >= TRIM_EVERY) {
            playsSinceTrim = 0
            dao.trimAffinity(EDGE_LIMIT)
            dao.trimTransitions(EDGE_LIMIT)
        }
    }

    private var playsSinceTrim = 0

    private suspend fun bump(a: Long, b: Long, w: Double, now: Long) {
        val current = dao.affinityWeight(a, b) ?: 0.0
        dao.putAffinity(AffinityEntity(a = a, b = b, weight = current + w, updatedAt = now))
    }

    suspend fun recordSkip(songId: Long, listenedMs: Long) = withContext(Dispatchers.IO) {
        val current = dao.stats(songId) ?: SongStatsEntity(songId = songId)
        dao.putStats(
            current.copy(
                skipCount = current.skipCount + 1,
                listenedMs = current.listenedMs + listenedMs,
                lastPlayedAt = System.currentTimeMillis()
            )
        )
    }

    /** Records that [to] followed [from]; [skipped] flips it into a penalty. */
    suspend fun recordTransition(from: Long, to: Long, skipped: Boolean) = withContext(Dispatchers.IO) {
        if (from == to || from <= 0L || to <= 0L) return@withContext
        val now = System.currentTimeMillis()
        val current = dao.transition(from, to)
        dao.putTransition(
            TransitionEntity(
                a = from,
                b = to,
                weight = (current?.weight ?: 0.0) + if (skipped) 0.0 else 1.0,
                penalty = (current?.penalty ?: 0.0) + if (skipped) 1.0 else 0.0,
                updatedAt = now
            )
        )
    }

    suspend fun transitionMap(): Map<Long, Map<Long, TransitionEdge>> = withContext(Dispatchers.IO) {
        val out = HashMap<Long, MutableMap<Long, TransitionEdge>>()
        for (row in dao.allTransitions()) {
            out.getOrPut(row.a) { HashMap() }[row.b] = TransitionEdge(row.weight, row.penalty)
        }
        out
    }

    // -----------------------------------------------------------------------
    // audio analysis storage
    // -----------------------------------------------------------------------

    suspend fun songsNeedingAnalysis(limit: Int): List<SongEntity> =
        withContext(Dispatchers.IO) { dao.songsNeedingAnalysis(limit) }

    suspend fun putFeature(feature: AudioFeatureEntity) =
        withContext(Dispatchers.IO) { dao.putFeature(feature) }

    // -----------------------------------------------------------------------
    // tag corrections
    // -----------------------------------------------------------------------

    suspend fun overrides(): List<TagOverrideEntity> =
        withContext(Dispatchers.IO) { dao.allOverrides() }

    /**
     * Saves corrections and rebuilds the library so artist keys, album grouping
     * and every derived screen pick them up in one pass.
     */
    suspend fun saveOverrides(rows: List<TagOverrideEntity>) = withContext(Dispatchers.IO) {
        dao.putOverrides(rows)
        rescan()
    }

    suspend fun clearOverrides() = withContext(Dispatchers.IO) {
        dao.clearOverrides()
        rescan()
    }

    suspend fun analyzedCount(): Int = withContext(Dispatchers.IO) { dao.featureCount() }

    /** Song ids whose lyrics contain [query], as a plain substring. */
    suspend fun songIdsWithLyrics(query: String): List<Long> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.length < 2) return@withContext emptyList()
        // Underscore and percent are wildcards in LIKE, so a query containing
        // either would quietly match far more than it asked for.
        val safe = trimmed.replace("%", "").replace("_", "")
        if (safe.isEmpty()) return@withContext emptyList()
        runCatching { dao.songIdsWithLyrics("%$safe%") }.getOrDefault(emptyList())
    }

    /**
     * Every measured feature, read straight from the database.
     *
     * For one-shot work that has to be right at the moment it runs. The
     * observable version only holds data while some screen is subscribed to it,
     * so an action started from a screen that is not watching it sees an empty
     * map and wrongly concludes that nothing has been analysed.
     */
    suspend fun featureMap(): Map<Long, AudioFeatureEntity> = withContext(Dispatchers.IO) {
        dao.allFeatures().filter { it.energy > 0f }.associateBy { it.songId }
    }

    /**
     * Per track playback gain in 0..1, keyed by song id.
     *
     * A stand-in for ReplayGain built from the mean RMS the analyser already
     * measures. Two honest limitations: RMS is a rougher proxy for perceived
     * loudness than EBU R128, and Media3 accepts no volume above 1, so the only
     * available move is pulling loud tracks down rather than lifting quiet ones.
     * The reference therefore sits high on purpose - most of the library plays
     * untouched and only the outliers above it are tamed, which evens the
     * collection out without making the whole thing quieter.
     */
    suspend fun loudnessGains(): Map<Long, Float> = withContext(Dispatchers.IO) {
        Loudness.gains(dao.allFeatures())
    }

    /**
     * The songs recently played, oldest first, for the engine's own check.
     *
     * recentHistory returns newest first because every screen that shows
     * history wants it that way; a sequence has to be read forwards.
     */
    suspend fun playOrder(limit: Int = 300): List<Long> = withContext(Dispatchers.IO) {
        dao.recentHistory(limit).asReversed().map { it.songId }
    }

    suspend fun songCount(): Int = withContext(Dispatchers.IO) { dao.songCount() }

    suspend fun clearFeatures() = withContext(Dispatchers.IO) { dao.clearFeatures() }

    suspend fun affinityMap(): Map<Long, Map<Long, Double>> = withContext(Dispatchers.IO) {
        val rows: List<AffinityEntity> = dao.allAffinity()
        val out = HashMap<Long, MutableMap<Long, Double>>()
        for (r in rows) {
            out.getOrPut(r.a) { HashMap() }[r.b] = r.weight
        }
        out
    }

    /** A ready-to-use engine snapshot, built off the database on a worker thread. */
    suspend fun buildRecommender(): Recommender = withContext(Dispatchers.IO) {
        // rows with zero energy are placeholders for files that failed to
        // decode; they must not enter the statistics of the acoustic space
        val featureRows = dao.allFeatures().filter { it.energy > 0f }
        Recommender(
            songs = dao.allSongs(),
            stats = dao.allStats().associateBy { it.songId },
            artists = dao.allArtists().associateBy { it.artistKey },
            affinity = affinityMap(),
            transitions = transitionMap(),
            features = featureRows.associateBy { it.songId },
            acoustic = if (featureRows.size >= 8) AcousticSpace(featureRows) else null,
            tuning = com.elchanan.rhythm.engine.EngineTuning(
                discovery = prefs.discovery,
                artistWeight = prefs.artistWeight,
                styleWeight = prefs.styleWeight,
                repeatGuard = prefs.repeatGuard,
                acousticWeight = prefs.acousticWeight,
                separations = prefs.styleSeparations,
                lastMood = prefs.lastMood
            ),
            now = System.currentTimeMillis(),
            feedSeed = prefs.feedSeed.toLong()
        )
    }

    suspend fun songById(id: Long): SongEntity? = withContext(Dispatchers.IO) { dao.song(id) }

    // -----------------------------------------------------------------------
    // lyrics
    // -----------------------------------------------------------------------

    val lyricsIds: Flow<List<Long>> = dao.observeLyricsIds()

    /**
     * Returns whatever is already stored; when nothing is stored yet it looks
     * once in the file's own tags and in the granted lyrics folder, caches the
     * result (including an empty one) and returns it.
     */
    suspend fun lyricsFor(song: SongEntity): LyricsEntity = withContext(Dispatchers.IO) {
        dao.lyrics(song.id)?.let { return@withContext it }
        val tree = prefs.lyricsFolderUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val found = runCatching { LyricsSource.find(context, song, tree) }.getOrNull()
            ?: LyricsEntity(song.id, "", "", "none", System.currentTimeMillis())
        dao.putLyrics(found)
        found
    }

    suspend fun saveLyrics(songId: Long, text: String, synced: String) = withContext(Dispatchers.IO) {
        dao.putLyrics(
            LyricsEntity(
                songId = songId,
                text = text,
                synced = synced,
                source = "manual",
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    /** Forces a fresh look in the tags and the lyrics folder. */
    suspend fun refreshLyrics(song: SongEntity): LyricsEntity = withContext(Dispatchers.IO) {
        dao.deleteLyrics(song.id)
        lyricsFor(song)
    }

    suspend fun lyricsCount(): Int = withContext(Dispatchers.IO) { dao.lyricsCount() }

    // -----------------------------------------------------------------------
    // recap
    // -----------------------------------------------------------------------

    /**
     * Everything the recap screen shows, computed from the play history.
     *
     * The counting itself is [Recap.build] over in :engine, so the phone and
     * the desktop build report the same numbers from the same rows. This end
     * only fetches them.
     */
    suspend fun buildRecap(): RecapData = withContext(Dispatchers.IO) {
        Recap.build(dao.recentHistory(20_000), dao.allSongs().associateBy { it.id })
    }

    suspend fun bulkSetRating(songIds: List<Long>, rating: Int) = withContext(Dispatchers.IO) {
        for (id in songIds) {
            val current = dao.stats(id) ?: SongStatsEntity(songId = id)
            dao.putStats(current.copy(rating = rating))
        }
    }

    suspend fun bulkSetLike(songIds: List<Long>, value: Int) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        for (id in songIds) {
            val current = dao.stats(id) ?: SongStatsEntity(songId = id)
            dao.putStats(current.copy(liked = value, likedAt = if (value != 0) now else 0L))
        }
    }

    suspend fun bulkAddToPlaylist(playlistId: Long, songIds: List<Long>) =
        withContext(Dispatchers.IO) {
            var position = dao.nextPosition(playlistId)
            for (id in songIds) {
                dao.insertPlaylistItem(
                    PlaylistItemEntity(playlistId = playlistId, songId = id, position = position)
                )
                position++
            }
        }

    /** Applies a rating and/or a style list to many artists at once. */
    suspend fun bulkUpdateArtists(
        keys: List<String>,
        names: Map<String, String>,
        rating: Int?,
        styles: List<String>?,
        replaceStyles: Boolean
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        for (key in keys) {
            val existing = dao.artist(key)
            val currentStyles = com.elchanan.rhythm.engine.Styles.parse(existing?.styles.orEmpty())
            val nextStyles = when {
                styles == null -> currentStyles
                replaceStyles -> styles
                else -> (currentStyles + styles).distinct()
            }
            dao.putArtist(
                (existing ?: ArtistEntity(
                    artistKey = key,
                    displayName = names[key] ?: key
                )).copy(
                    displayName = existing?.displayName?.ifBlank { names[key] ?: key }
                        ?: (names[key] ?: key),
                    rating = rating ?: (existing?.rating ?: 0),
                    styles = com.elchanan.rhythm.engine.Styles.join(nextStyles),
                    updatedAt = now
                )
            )
        }
    }

    // -----------------------------------------------------------------------
    // playlists
    // -----------------------------------------------------------------------

    suspend fun createPlaylist(name: String): Long = withContext(Dispatchers.IO) {
        dao.insertPlaylist(PlaylistEntity(name = name, createdAt = System.currentTimeMillis()))
    }

    suspend fun addToPlaylist(playlistId: Long, songId: Long) = withContext(Dispatchers.IO) {
        val pos = dao.nextPosition(playlistId)
        dao.insertPlaylistItem(PlaylistItemEntity(playlistId = playlistId, songId = songId, position = pos))
    }

    suspend fun removeFromPlaylist(playlistId: Long, songId: Long) = withContext(Dispatchers.IO) {
        dao.removeFromPlaylist(playlistId, songId)
    }

    suspend fun deletePlaylist(playlistId: Long) = withContext(Dispatchers.IO) {
        dao.deletePlaylistItems(playlistId)
        dao.deletePlaylist(playlistId)
    }

    // -----------------------------------------------------------------------
    // import / export of the artist knowledge base
    // -----------------------------------------------------------------------

    suspend fun exportArtists(): String = withContext(Dispatchers.IO) {
        val arr = JSONArray()
        for (a in dao.allArtists()) {
            if (a.rating == 0 && a.styles.isBlank() && a.note.isBlank()) continue
            arr.put(
                JSONObject()
                    .put("key", a.artistKey)
                    .put("name", a.displayName)
                    .put("rating", a.rating)
                    .put("styles", a.styles)
                    .put("note", a.note)
            )
        }
        val songArr = JSONArray()
        val byId = dao.allSongs().associateBy { it.id }
        for (st in dao.allStats()) {
            if (st.rating == 0 && st.styles.isBlank() && st.liked == 0) continue
            val song = byId[st.songId] ?: continue
            songArr.put(
                JSONObject()
                    .put("title", song.title)
                    .put("artist", song.artistName)
                    .put("rating", st.rating)
                    .put("styles", st.styles)
                    .put("liked", st.liked)
            )
        }
        JSONObject()
            .put("version", 2)
            .put("artists", arr)
            .put("songs", songArr)
            .toString(2)
    }

    /** Returns how many artist profiles were written. */
    suspend fun importArtists(json: String): Int = withContext(Dispatchers.IO) {
        val root = JSONObject(json)
        val arr = root.optJSONArray("artists") ?: JSONArray()
        var count = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name").trim()
            if (name.isEmpty()) continue
            val rawKey = o.optString("key").trim()
            val key = if (rawKey.isEmpty()) Names.normalizeKey(name) else rawKey
            val existing = dao.artist(key)
            dao.putArtist(
                (existing ?: ArtistEntity(artistKey = key, displayName = name)).copy(
                    displayName = if (existing?.displayName.isNullOrBlank()) name else existing!!.displayName,
                    rating = o.optInt("rating", existing?.rating ?: 0),
                    styles = o.optString("styles", existing?.styles ?: ""),
                    note = o.optString("note", existing?.note ?: ""),
                    updatedAt = System.currentTimeMillis()
                )
            )
            count++
        }
        count
    }

    /**
     * Bulk entry: one artist per line, in the format
     *   name | rating | style, style, style
     * Only the name is mandatory.
     */
    suspend fun importArtistLines(text: String): Int = withContext(Dispatchers.IO) {
        var count = 0
        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split('|').map { it.trim() }
            val name = parts.getOrNull(0).orEmpty()
            if (name.isEmpty()) continue
            val rating = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 5) ?: 0
            val styles = parts.getOrNull(2).orEmpty()
            val key = Names.normalizeKey(name)
            val existing = dao.artist(key)
            dao.putArtist(
                (existing ?: ArtistEntity(artistKey = key, displayName = name)).copy(
                    displayName = existing?.displayName?.ifBlank { name } ?: name,
                    rating = if (rating > 0) rating else (existing?.rating ?: 0),
                    styles = if (styles.isNotBlank()) styles else (existing?.styles ?: ""),
                    updatedAt = System.currentTimeMillis()
                )
            )
            count++
        }
        count
    }

    suspend fun resetLearning() = withContext(Dispatchers.IO) {
        dao.clearStats()
        dao.clearAffinity()
        dao.clearTransitions()
        dao.clearHistory()
    }

    companion object {
        /**
         * Edges kept per table. Twenty thousand is far more than the engine
         * ever reads and about a megabyte on disk, so the ceiling is generous
         * enough never to lose anything that matters.
         */
        private const val EDGE_LIMIT = 20_000
        private const val TRIM_EVERY = 200

        /**
         * How close to either end counts as "not worth remembering".
         *
         * At the end, because resuming three seconds before the finish is
         * worse than starting again. At the start, because a position of two
         * seconds is not a place anyone left off from.
         */
        private const val END_MARGIN_MS = 15_000L
        private const val START_MARGIN_MS = 10_000L

        fun create(context: Context): MusicRepository {
            val db = RhythmDatabase.get(context)
            return MusicRepository(context.applicationContext, db.musicDao(), Prefs(context))
        }
    }
}
