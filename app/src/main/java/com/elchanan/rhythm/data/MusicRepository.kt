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
import com.elchanan.rhythm.data.PlayCountImport
import com.elchanan.rhythm.engine.ArtistStyles
import com.elchanan.rhythm.engine.AudioTags
import com.elchanan.rhythm.engine.BulkTagging
import com.elchanan.rhythm.engine.EdgeDecay
import com.elchanan.rhythm.engine.Spoken
import com.elchanan.rhythm.engine.AcousticSpace
import com.elchanan.rhythm.engine.Loudness
import com.elchanan.rhythm.engine.Names
import com.elchanan.rhythm.engine.Recap
import com.elchanan.rhythm.engine.RecapData
import com.elchanan.rhythm.engine.Recommender
import com.elchanan.rhythm.engine.TransitionEdge
import com.elchanan.rhythm.engine.Versions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.withLock
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
        /**
         * Songs held on to although this scan did not see them, because the
         * storage they live on is not attached. Worth saying out loud: it is
         * the difference between "your card is out" and "half your library
         * has vanished", and those look identical from the home screen.
         */
        val onAbsentStorage: Int,
        val at: Long
    )

    private val _lastScan = MutableStateFlow<ScanReport?>(null)
    val lastScan: StateFlow<ScanReport?> = _lastScan.asStateFlow()

    /**
     * Bumped once at the end of every completed scan.
     *
     * The scan can now be run by the foreground service instead of by the
     * screen that asked for it, so whoever wants to know it has finished
     * watches this rather than awaiting the call. One counter rather than a
     * callback, because there may be nobody listening at the time - the app
     * can be closed while the service carries on - and a counter that was
     * missed is simply read as a higher number next time.
     */
    private val _scans = MutableStateFlow(0L)
    val scans: StateFlow<Long> = _scans.asStateFlow()

    /**
     * One scan at a time. A scan asked for while another runs - after a
     * delete, during the first analysis - waits for it rather than racing it
     * over the same rows.
     */
    private val scanLock = kotlinx.coroutines.sync.Mutex()

    suspend fun rescan(): Int = withContext(Dispatchers.IO) { scanLock.withLock { rescanLocked() } }

    private suspend fun rescanLocked(): Int {
        val excluded = prefs.excludedFolders.map { it.lowercase() }
        // The folders the library is made of, when the listener chose some.
        val roots = prefs.musicFolders.map { it.trimEnd('/').lowercase() + "/" }
        val overrides = dao.allOverrides().associateBy { it.songId }
        val skipRecordings = prefs.skipRecordings
        val minMs = prefs.minDurationSec * 1000L

        // Ask MediaStore what it has, then ask the disk whether that was
        // everything - because it frequently is not.
        //
        // MediaStore is a list the system keeps, not the storage itself, and
        // it only learns about a file when something tells it. Copying music
        // in over adb or a card reader, restoring a backup, or using a file
        // manager that does not announce what it wrote, all leave files that
        // are on the phone and playable and in no app's library. A rescan
        // could never fix that: it asked MediaStore, and MediaStore had never
        // heard of them.
        //
        // So anything on the storage that MediaStore has no row for is handed
        // to the system scanner, and this waits for it rather than hoping the
        // change observer picks it up later - a scan that finishes and says
        // "nothing new" while the songs are sitting there is the complaint.
        var onDevice = MediaScanner.scan(context)
        val indexed = runCatching {
            val known = onDevice.mapTo(HashSet(onDevice.size * 2)) { it.path }
            MediaScanner.indexNow(context, MediaScanner.unindexedFiles(context, known))
        }.getOrDefault(0)
        if (indexed > 0) onDevice = MediaScanner.scan(context)
        // And the other way round: MediaStore keeps listing a file that was
        // deleted or moved by something that did not tell it - a file
        // manager, a computer over USB - and the scan believed it. The song
        // stayed in the library and in its playlists, pointing at nothing,
        // and no rescan could remove it. So a row whose file is not there is
        // not taken as found, and the system is asked to look again, which
        // drops its stale row. Not when nearly all of them are missing: that
        // is the check being unable to see the files, not a library deleted,
        // and forgetting a library on that evidence cannot be undone.
        val stale = onDevice.filter { song ->
            song.path.isNotEmpty() && !runCatching { java.io.File(song.path).exists() }.getOrDefault(true)
        }
        if (stale.isNotEmpty() && stale.size * 10 < onDevice.size * STALE_OF_TEN) {
            val stalePaths = stale.mapTo(HashSet()) { it.path }
            onDevice = onDevice.filter { it.path !in stalePaths }
            runCatching { MediaScanner.indexNow(context, stalePaths.toList()) }
        }
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
                val path = song.path.lowercase()
                val keep = (roots.isEmpty() || roots.any { path.startsWith(it) }) &&
                    excluded.none { pattern -> song.folder.lowercase().contains(pattern) }
                if (!keep) inExcluded++
                keep
            }
            .filter { song ->
                val keep = !skipRecordings ||
                    !Names.looksLikeRecording(
                        song.folder,
                        song.path.substringAfterLast('/'),
                        song.durationMs,
                        Names.hasRealArtist(song.artistName)
                    )
                if (!keep) recordings++
                keep
            }
            .map { song -> applyOverride(song, overrides[song.id]) }

        // What is already known, by path. The path is the only thing about a
        // song that survives a card being pulled out and put back: MediaStore
        // hands the same file a new id, and every rating, play count and
        // bookmark keyed to the old one would otherwise belong to nothing.
        val existing = dao.allSongs()
        val byPath = existing.associateBy { it.path }

        // Which storage is actually attached. A song may only be forgotten
        // when the volume it lives on is present and the song is not on it -
        // see Volumes for why this is the whole of the difference between a
        // missing card and a deleted library.
        val mounted = Volumes.mountedRoots(existing.map { it.path } + found.map { it.path })

        val foundPaths = found.mapTo(HashSet()) { it.path }
        // A device that reports no audio at all has almost certainly refused
        // the question - a permission withdrawn, a provider that failed, a
        // media store still rebuilding after an update - rather than had its
        // music deleted. Forgetting a whole library on that evidence is the
        // one mistake here that cannot be undone, so nothing is forgotten
        // until something is found.
        val gone = if (onDevice.isEmpty()) {
            emptyList()
        } else {
            existing.filter { song ->
                song.path !in foundPaths && Volumes.rootOf(song.path) in mounted
            }
        }
        // Where the same file came back under a different number.
        val renumbered = found.mapNotNull { song ->
            val old = byPath[song.path] ?: return@mapNotNull null
            if (old.id == song.id) null else old.id to song.id
        }
        // And where a file went to another folder. A move is a delete and a
        // new file as far as MediaStore is concerned, so the song lost its
        // plays, its likes and its place in every playlist, and the old one
        // stayed behind. The same name and the same size, on one gone song
        // and one new one, is the same file.
        val existingIds = existing.mapTo(HashSet()) { it.id }
        fun fileKey(song: SongEntity) =
            song.path.substringAfterLast('/').lowercase() + "|" + song.sizeBytes
        val arrivedByKey = found
            .filter { it.id !in existingIds && byPath[it.path] == null && it.sizeBytes > 0L }
            .groupBy(::fileKey).filterValues { it.size == 1 }.mapValues { it.value[0] }
        val relocated = gone
            .filter { it.sizeBytes > 0L }
            .groupBy(::fileKey).filterValues { it.size == 1 }
            .mapNotNull { (key, list) -> arrivedByKey[key]?.let { list[0].id to it.id } }
        val moved = renumbered + relocated
        val relocatedIds = relocated.mapTo(HashSet()) { it.first }
        val goneIds = gone.mapTo(HashSet()) { it.id } - relocatedIds
        val kept = existing.filter { song ->
            song.path !in foundPaths && song.id !in goneIds && song.id !in relocatedIds
        }
        // The old number's row goes once everything on it has moved: it was
        // left behind as a second copy of the song that nothing could play.
        val foundIds = found.mapTo(HashSet()) { it.id }
        val leftBehind = moved.map { it.first }.filter { it !in foundIds }

        // In one transaction, so the observers never see the moment between
        // the old library being cleared and the new one arriving. Without it
        // every rescan empties the home screen for an instant - and, more to
        // the point, a rescan interrupted half way can no longer leave the
        // library in a state that is neither the old one nor the new one.
        RhythmDatabase.get(context).withTransaction {
            // The ids move before the new rows land, so what is carried over
            // is not immediately overwritten by the bare row a fresh scan
            // writes for the same file.
            for ((from, to) in moved) {
                dao.moveStats(from, to)
                dao.moveFeature(from, to)
                dao.movePosition(from, to)
                dao.moveLyrics(from, to)
                dao.moveOverride(from, to)
                dao.moveBookmarks(from, to)
                dao.moveHistory(from, to)
                dao.movePlaylistItems(from, to)
                dao.moveAffinityA(from, to)
                dao.moveAffinityB(from, to)
                dao.moveTransitionA(from, to)
                dao.moveTransitionB(from, to)
            }
            // Only what is provably gone, and never the whole table. Songs on
            // a volume that is not attached stay exactly as they were. A song
            // that is gone leaves its playlists too, where it could be seen
            // and not played.
            if (goneIds.isNotEmpty()) {
                goneIds.toList().chunked(400).forEach {
                    dao.deleteSongsById(it)
                    dao.removeFromAllPlaylists(it)
                }
            }
            leftBehind.chunked(400).forEach { dao.deleteSongsById(it) }
            found.chunked(400).forEach { dao.insertSongs(it) }
        }
        _lastScan.value = ScanReport(
            onDevice = onDevice.size,
            tooShort = tooShort,
            inExcludedFolder = inExcluded,
            looksLikeRecording = recordings,
            kept = found.size,
            onAbsentStorage = kept.size,
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
        // Ratings and styles left on a name that no longer has songs - by a
        // tag fix made before profiles followed their songs, by tags written
        // into the files, or by a key that now reads a spelling as the same
        // name - go to where those songs are now. See ArtistMerge.orphanMoves.
        runCatching {
            val rawKey = onDevice.associate { it.id to it.artistKey }
            rehomeArtistProfiles(found.mapNotNull { song -> rawKey[song.id]?.let { it to song.artistKey } })
        }
        prefs.lastScanAt = System.currentTimeMillis()
        _scans.value = _scans.value + 1
        // The songs still filed under storage that is not attached count as
        // part of the library, because they are: they come back the moment
        // the card does, with everything that was learned about them intact.
        return found.size + kept.size
    }

    // -----------------------------------------------------------------------
    // feedback
    // -----------------------------------------------------------------------

    suspend fun setLike(songId: Long, value: Int) = withContext(Dispatchers.IO) {
        statsLock.withLock {
            val current = dao.stats(songId) ?: SongStatsEntity(songId = songId)
            val next = if (current.liked == value) 0 else value
            dao.putStats(
                current.copy(
                    liked = next,
                    likedAt = if (next != 0) System.currentTimeMillis() else 0L
                )
            )
        }
    }

    /** What this song is marked: 1 liked, -1 disliked, 0 neither. */
    suspend fun likeOf(songId: Long): Int =
        withContext(Dispatchers.IO) { dao.stats(songId)?.liked ?: 0 }

    suspend fun setSongRating(songId: Long, rating: Int) = withContext(Dispatchers.IO) {
        statsLock.withLock {
            val current = dao.stats(songId) ?: SongStatsEntity(songId = songId)
            dao.putStats(current.copy(rating = if (current.rating == rating) 0 else rating))
        }
    }

    /**
     * @param auto true when the learner produced these rather than the user.
     *   A hand edit always lands as false, which is what promotes a guess the
     *   user has since corrected into something the learner will not touch.
     */
    suspend fun setSongStyles(songId: Long, styles: String, auto: Boolean = false) =
        withContext(Dispatchers.IO) {
            statsLock.withLock {
                val current = dao.stats(songId) ?: SongStatsEntity(songId = songId)
                dao.putStats(current.copy(styles = styles, stylesAuto = if (auto) 1 else 0))
            }
        }

    /**
     * Writes imported listening history onto the library.
     *
     * The larger of the two counts wins rather than the sum, so importing the
     * same export twice does not double anybody's history - which is the one
     * mistake a person is almost certain to make with this, since there is no
     * way to tell by looking whether a file has already been read.
     *
     * @return how many songs were changed.
     */
    suspend fun applyImportedPlays(matches: List<PlayCountImport.Match>): Int =
        withContext(Dispatchers.IO) { statsLock.withLock {
            var changed = 0
            for (match in matches) {
                val current = dao.stats(match.songId) ?: SongStatsEntity(songId = match.songId)
                val plays = maxOf(current.playCount, match.plays)
                val at = maxOf(current.lastPlayedAt, match.lastPlayedAt)
                if (plays == current.playCount && at == current.lastPlayedAt) continue
                dao.putStats(current.copy(playCount = plays, lastPlayedAt = at))
                changed++
            }
            changed
        } }

    /**
     * Puts one set of style tags on many songs at once.
     *
     * What a folder tag runs on. The decision about what may be overwritten is
     * [BulkTagging]'s and is shared with the desktop build, because it is the
     * one place here that can destroy tagging the user cannot get back.
     *
     * @return how many songs actually changed.
     */
    suspend fun setStylesForSongs(
        songIds: List<Long>,
        styles: List<String>,
        replace: Boolean
    ): Int = withContext(Dispatchers.IO) { statsLock.withLock {
        var changed = 0
        for (id in songIds) {
            val current = dao.stats(id)
            val next = BulkTagging.tagsFor(current, styles, replace) ?: continue
            dao.putStats(
                (current ?: SongStatsEntity(songId = id)).copy(styles = next, stylesAuto = 0)
            )
            changed++
        }
        changed
    } }

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
        statsLock.withLock {
            for (id in songIds) dao.ensureStats(id)
            songIds.chunked(400).forEach { dao.setGenre(it, genre.trim()) }
        }
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
        ids.chunked(400).forEach {
            statsLock.withLock { dao.deleteStats(it) }
            // The song itself and its playlist places too, now. Those were
            // left to the scan that followed - and a scan asked for during
            // an analysis pass never ran, so a song deleted from inside the
            // app stayed in the library and in every playlist it was in.
            dao.removeFromAllPlaylists(it)
            dao.deleteSongsById(it)
        }
    }

    /** The user saying a song is, or is not, in a mood - or (null) handing it back to the audio. */
    suspend fun setMoodMark(songId: Long, mood: com.elchanan.rhythm.engine.Mood, value: Boolean?) =
        withContext(Dispatchers.IO) {
            // Read, change, write: two chips tapped quickly must not both read
            // the old marks and have the second write erase the first.
            statsLock.withLock {
                dao.ensureStats(songId)
                val current = dao.stats(songId)?.moods.orEmpty()
                dao.setMoods(songId, com.elchanan.rhythm.engine.MoodMarks.with(current, mood, value))
            }
        }

    /**
     * Every read, change and write of a song's stats, one at a time.
     *
     * Each of them reads the row, changes one field and writes the whole row
     * back. Two at once - a like pressed as a play is being counted, a mood
     * chip tapped as the song ends - both read the old row, and whichever
     * wrote second erased the other: a like that did not stay, a play that
     * was never counted.
     */
    private val statsLock = kotlinx.coroutines.sync.Mutex()

    /** The user saying a song is vocal-only (true), is not (false), or handing it back (null). */
    suspend fun setVocal(songId: Long, vocal: Boolean?) = withContext(Dispatchers.IO) {
        statsLock.withLock {
            dao.ensureStats(songId)
            dao.setVocal(songId, when (vocal) { true -> 1; false -> 0; null -> -1 })
        }
    }

    /** The user overruling the speech detector, either way. */
    suspend fun setSpoken(songId: Long, spoken: Boolean) = withContext(Dispatchers.IO) {
        statsLock.withLock {
            dao.ensureStats(songId)
            dao.setSpoken(songId, if (spoken) 1 else 0)
        }
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
        statsLock.withLock { dao.resetPlayCount(songId) }
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
            statsLock.withLock { dao.resetPlayCounts(chunk) }
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
        val bucket = Recommender.bucketOf(now)
        val today = localDay(now)
        statsLock.withLock {
            val current = dao.stats(songId) ?: SongStatsEntity(songId = songId)
            val newDay = today != current.lastPlayDay
            dao.putStats(
                current.copy(
                    playDays = current.playDays + if (newDay) 1 else 0,
                    lastPlayDay = today,
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
        }
        dao.insertHistory(HistoryEntity(songId = songId, playedAt = now, completed = completed, listenedMs = listenedMs))
        dao.trimHistory(HISTORY_FOR_RECENCY)

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

    /** When the last few skips happened, to tell flicking through from turning a song off. */
    private val recentSkips = ArrayDeque<Long>()

    /** The local calendar day, so a play at 23:59 and one at 00:01 are two days. */
    private fun localDay(millis: Long): Long {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = millis }
        return com.elchanan.rhythm.engine.JewishSeasons.epochDay(
            c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH)
        )
    }

    private suspend fun bump(a: Long, b: Long, w: Double, now: Long) {
        // Faded to now before anything is added - see EdgeDecay.
        val current = dao.affinityEdge(a, b)
        val weight = if (current == null) w else EdgeDecay.bump(current.weight, current.updatedAt, now, w)
        dao.putAffinity(AffinityEntity(a = a, b = b, weight = weight, updatedAt = now))
    }

    suspend fun recordSkip(songId: Long, listenedMs: Long) = withContext(Dispatchers.IO) { statsLock.withLock {
        val current = dao.stats(songId) ?: SongStatsEntity(songId = songId)
        val now = System.currentTimeMillis()
        val burst = synchronized(recentSkips) {
            while (recentSkips.isNotEmpty() && now - recentSkips.first() > BURST_WINDOW_MS) recentSkips.removeFirst()
            val inBurst = recentSkips.size >= BURST_BEFORE
            recentSkips.addLast(now)
            inBurst
        }
        dao.putStats(
            current.copy(
                burstSkips = current.burstSkips + if (burst) 1 else 0,
                lastSkipAt = now,
                skipCount = current.skipCount + 1,
                listenedMs = current.listenedMs + listenedMs,
                lastPlayedAt = System.currentTimeMillis()
            )
        )
    } }

    /** Records that [to] followed [from]; [skipped] flips it into a penalty. */
    suspend fun recordTransition(from: Long, to: Long, skipped: Boolean) = withContext(Dispatchers.IO) {
        if (from == to || from <= 0L || to <= 0L) return@withContext
        val now = System.currentTimeMillis()
        val current = dao.transition(from, to)
        val then = current?.updatedAt ?: 0L
        dao.putTransition(
            TransitionEntity(
                a = from,
                b = to,
                weight = EdgeDecay.bump(current?.weight ?: 0.0, then, now, if (skipped) 0.0 else 1.0),
                penalty = EdgeDecay.bump(current?.penalty ?: 0.0, then, now, if (skipped) 1.0 else 0.0),
                updatedAt = now
            )
        )
    }

    suspend fun transitionMap(): Map<Long, Map<Long, TransitionEdge>> = withContext(Dispatchers.IO) {
        val out = HashMap<Long, MutableMap<Long, TransitionEdge>>()
        val now = System.currentTimeMillis()
        for (row in dao.allTransitions()) {
            out.getOrPut(row.a) { HashMap() }[row.b] = TransitionEdge(
                EdgeDecay.at(row.weight, row.updatedAt, now),
                EdgeDecay.at(row.penalty, row.updatedAt, now)
            )
        }
        out
    }

    // -----------------------------------------------------------------------
    // audio analysis storage
    // -----------------------------------------------------------------------

    /** Songs still to analyse with an id above [after], in id order. */
    suspend fun songsNeedingAnalysis(after: Long, limit: Int): List<SongEntity> =
        withContext(Dispatchers.IO) {
            dao.songsNeedingAnalysis(after, limit, com.elchanan.rhythm.engine.AudioAnalyzer.musicAvailable(context))
        }

    /** Raw inventory, before the UI hides duplicate files. */
    suspend fun allSongsForExport(): List<SongEntity> =
        withContext(Dispatchers.IO) { dao.allSongs() }

    suspend fun putFeature(feature: AudioFeatureEntity) =
        withContext(Dispatchers.IO) { dao.putFeature(feature) }

    /** See [MusicDao.markPrintTried]. True when an analysed row was kept. */
    suspend fun markPrintTried(songId: Long): Boolean =
        withContext(Dispatchers.IO) { dao.markPrintTried(songId) > 0 }

    suspend fun putFeatures(features: List<AudioFeatureEntity>) =
        withContext(Dispatchers.IO) { dao.putFeatures(features) }

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
        val before = dao.allSongs().associate { it.id to it.artistKey }
        dao.putOverrides(rows)
        rescan()
        carryArtistProfiles(before)
    }

    /**
     * What was said about an artist follows their songs to their new name.
     *
     * A tag fix or a merge moves songs from one spelling of an artist to
     * another. The songs moved and the rating, the styles and the note did
     * not: they stayed on a name with no songs left, which drops out of the
     * artist list - so the artist looked gone, and the new name started from
     * nothing. Now, when every song of an artist has gone to one other name,
     * whatever the new name has not been told yet is taken from the old one;
     * what it has been told stays.
     */
    private suspend fun carryArtistProfiles(before: Map<Long, String>) {
        val songs = dao.allSongs()
        val moves = ArtistMerge.profileMoves(before, songs.associate { it.id to it.artistKey })
        for ((old, key) in moves) {
            val from = dao.artist(old) ?: continue
            if (from.rating == 0 && from.styles.isBlank() && from.note.isBlank()) continue
            val name = songs.firstOrNull { it.artistKey == key }?.let { Names.primaryArtist(it.artistName) } ?: key
            val to = dao.artist(key) ?: ArtistEntity(artistKey = key, displayName = name)
            dao.putArtist(
                to.copy(
                    rating = if (to.rating == 0) from.rating else to.rating,
                    styles = to.styles.ifBlank { from.styles },
                    note = to.note.ifBlank { from.note },
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    /**
     * Moves the profiles of artists with no songs to the artist their songs
     * now belong to, and forgets the empty name - so a rating given there
     * is not put back after being changed. Whatever the new name has been
     * told already stays.
     */
    private suspend fun rehomeArtistProfiles(rawToNow: List<Pair<String, String>>) {
        val songs = dao.allSongs()
        val present = HashSet<String>()
        for (song in songs) {
            present.add(song.artistKey)
            for (credit in Names.credits(song.artistName)) present.add(Names.normalizeKey(credit))
        }
        val orphans = dao.allArtists().filter { a ->
            a.artistKey !in present && (a.rating != 0 || a.styles.isNotBlank() || a.note.isNotBlank())
        }
        if (orphans.isEmpty()) return
        val moves = ArtistMerge.orphanMoves(orphans.map { it.artistKey }, present, rawToNow)
        for (from in orphans) {
            val key = moves[from.artistKey] ?: continue
            val name = songs.firstOrNull { it.artistKey == key }?.let { Names.primaryArtist(it.artistName) } ?: key
            val to = dao.artist(key) ?: ArtistEntity(artistKey = key, displayName = name)
            dao.putArtist(
                to.copy(
                    rating = if (to.rating == 0) from.rating else to.rating,
                    styles = to.styles.ifBlank { from.styles },
                    note = to.note.ifBlank { from.note },
                    updatedAt = System.currentTimeMillis()
                )
            )
            dao.deleteArtist(from.artistKey)
        }
    }

    /** Preserve track IDs, stats and existing title/album corrections. */
    suspend fun mergeArtist(sourceKey: String, targetName: String): Int = withContext(Dispatchers.IO) {
        require(targetName.isNotBlank())
        val before = dao.allSongs().associate { it.id to it.artistKey }
        val moved = RhythmDatabase.get(context).withTransaction {
            val overrides = dao.allOverrides().associateBy { it.songId }
            val changes = dao.allSongs().mapNotNull { song ->
                val renamed = ArtistMerge.renameCredit(song.artistName, sourceKey, targetName)
                if (renamed == song.artistName) null else {
                    val row = (overrides[song.id] ?: TagOverrideEntity(songId = song.id))
                        .copy(artistName = renamed)
                    row to applyOverride(song, row)
                }
            }
            dao.putOverrides(changes.map { it.first })
            dao.insertSongs(changes.map { it.second })
            changes.size
        }
        carryArtistProfiles(before)
        moved
    }

    suspend fun clearOverrides() = withContext(Dispatchers.IO) {
        dao.clearOverrides()
        rescan()
    }

    suspend fun analyzedCount(): Int = withContext(Dispatchers.IO) {
        dao.featureCount(com.elchanan.rhythm.engine.AudioAnalyzer.musicAvailable(context))
    }

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
    suspend fun feature(songId: Long): AudioFeatureEntity? = withContext(Dispatchers.IO) { dao.feature(songId) }

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
        val now = System.currentTimeMillis()
        for (r in rows) {
            out.getOrPut(r.a) { HashMap() }[r.b] = EdgeDecay.at(r.weight, r.updatedAt, now)
        }
        out
    }

    /** A ready-to-use engine snapshot, built off the database on a worker thread. */
    suspend fun buildRecommender(): Recommender = withContext(Dispatchers.IO) {
        // rows with zero energy are placeholders for files that failed to
        // decode; they must not enter the statistics of the acoustic space
        val featureRows = dao.allFeatures().filter { it.energy > 0f }
        val statsById = dao.allStats().associateBy { it.songId }
        // The same collapse the library applies, and for the same reason: a
        // copy the user has hidden is hidden. The library dropped them and the
        // engine was never told, so search - which runs through the engine -
        // went on listing both copies, and hiding duplicates looked like it
        // worked everywhere except the one place people check it.
        val allSongs = dao.allSongs().let { all ->
            if (!prefs.hideDuplicates) all else {
                val types = Versions.classify(all) { id -> statsById[id]?.playCount ?: 0 }
                Versions.withoutDuplicates(all, types)
            }
        }
        val featuresById = featureRows.associateBy { it.songId }
        // Worked out here rather than inside the engine, because deciding what
        // is speech needs the tag scores unpacked from their stored form and
        // the user's own answer where they gave one - neither of which the
        // engine is handed.
        val spokenIds = allSongs.filterTo(HashSet()) { song ->
            val feature = featuresById[song.id]
            Spoken.isSpoken(
                song,
                feature,
                feature?.tags?.let { AudioTags.pick(it, AudioTags.SPEECH_INDICES) },
                statsById[song.id]?.spoken ?: -1
            )
        }.mapTo(HashSet()) { it.id }
        // Vocal-only songs, held back outside the Omer and the Three Weeks.
        val engineArtists = ArtistStyles.withCatalogue(dao.allArtists().associateBy { it.artistKey }, allSongs)
        val vocalIds = allSongs.filter { song ->
            com.elchanan.rhythm.engine.Vocal.isVocal(
                song, statsById[song.id], featuresById[song.id],
                engineArtists[song.artistKey]?.styles.orEmpty()
            )
        }.mapTo(HashSet()) { it.id }
        // When each song was last actually heard. The history records plays
        // and never skips, where lastPlayedAt in the stats is also moved by a
        // skip. Newest first, so the first row seen per song is its latest.
        val lastHeard = HashMap<Long, Long>()
        for (row in dao.recentHistory(HISTORY_FOR_RECENCY)) {
            lastHeard.putIfAbsent(row.songId, row.playedAt)
        }
        Recommender(
            songs = allSongs,
            stats = statsById,
            artists = engineArtists,
            affinity = affinityMap(),
            transitions = transitionMap(),
            // The space is made from the full rows; the engine keeps them
            // without what only the space reads. See Recommender.leanFeatures.
            features = Recommender.leanFeatures(featureRows),
            acoustic = if (featureRows.size >= 8) AcousticSpace(featureRows) else null,
            tuning = com.elchanan.rhythm.engine.EngineTuning(
                discovery = prefs.discovery,
                artistWeight = prefs.artistWeight,
                styleWeight = prefs.styleWeight,
                repeatGuard = prefs.repeatGuard,
                acousticWeight = prefs.acousticWeight,
                separations = prefs.styleSeparations,
                lastMood = prefs.lastMood,
                lastMoodAt = prefs.lastMoodAt,
                learned = com.elchanan.rhythm.engine.SignalWeights.decode(prefs.learnedWeights),
                onlyVocalInSeason = prefs.onlyVocalInSeason,
                medleyMinutes = prefs.medleyMinutes
            ),
            now = System.currentTimeMillis(),
            feedSeed = prefs.feedSeed.toLong(),
            spoken = spokenIds,
            lastHeard = lastHeard,
            vocal = vocalIds
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
        // One transaction: a folder can hold a thousand songs, and a commit
        // per song made rating one take seconds.
        statsLock.withLock {
            RhythmDatabase.get(context).withTransaction {
                for (id in songIds) {
                    val current = dao.stats(id) ?: SongStatsEntity(songId = id)
                    dao.putStats(current.copy(rating = rating))
                }
            }
        }
    }

    suspend fun bulkSetLike(songIds: List<Long>, value: Int) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        statsLock.withLock {
            for (id in songIds) {
                val current = dao.stats(id) ?: SongStatsEntity(songId = id)
                dao.putStats(current.copy(liked = value, likedAt = if (value != 0) now else 0L))
            }
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

    /** Save the queue atomically, including repeated tracks and their order. */
    suspend fun createPlaylistFromQueue(name: String, songIds: List<Long>): Long =
        withContext(Dispatchers.IO) {
            require(name.isNotBlank() && songIds.isNotEmpty())
            RhythmDatabase.get(context).withTransaction {
                val id = createPlaylist(name.trim())
                bulkAddToPlaylist(id, songIds)
                id
            }
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

        /**
         * Listed files missing from the disk are dropped unless more than
         * nine in ten are, which is the check failing rather than a delete.
         */
        private const val STALE_OF_TEN = 9

        /**
         * How much history the engine reads recency from. The whole of it:
         * recordPlay trims it to this size, so asking for more finds nothing.
         */
        private const val HISTORY_FOR_RECENCY = 2000
        /** Skips within this long of each other are one act of looking for something. */
        const val BURST_WINDOW_MS = 120_000L

        /** This many earlier skips inside the window make the next one part of a burst. */
        const val BURST_BEFORE = 3

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
