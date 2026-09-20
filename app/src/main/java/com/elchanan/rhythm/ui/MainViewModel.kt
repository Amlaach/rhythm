package com.elchanan.rhythm.ui

import android.app.Application
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.elchanan.rhythm.RhythmApp
import com.elchanan.rhythm.data.MediaScanner
import androidx.documentfile.provider.DocumentFile
import com.elchanan.rhythm.data.FileActions
import com.elchanan.rhythm.data.db.BookmarkEntity
import com.elchanan.rhythm.data.db.PlaybackPositionEntity
import com.elchanan.rhythm.data.PlaylistExport
import com.elchanan.rhythm.data.PlaylistImport
import com.elchanan.rhythm.engine.MoodModel
import com.elchanan.rhythm.engine.Spoken
import com.elchanan.rhythm.data.TagFileWriter
import com.elchanan.rhythm.data.TagFixer
import com.elchanan.rhythm.data.MusicRepository
import com.elchanan.rhythm.data.AnalysisManager
import com.elchanan.rhythm.data.db.ArtistEntity
import com.elchanan.rhythm.data.LyricLine
import com.elchanan.rhythm.data.LyricsSource
import com.elchanan.rhythm.data.db.AudioFeatureEntity
import com.elchanan.rhythm.data.db.LyricsEntity
import com.elchanan.rhythm.data.db.PlaylistEntity
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.data.db.SongStatsEntity
import com.elchanan.rhythm.data.db.TagOverrideEntity
import com.elchanan.rhythm.engine.FeedSection
import com.elchanan.rhythm.engine.Mix
import com.elchanan.rhythm.engine.Mood
import com.elchanan.rhythm.engine.Recommender
import com.elchanan.rhythm.engine.ScoreTerm
import com.elchanan.rhythm.engine.AcousticSpace
import com.elchanan.rhythm.engine.AudioTags
import com.elchanan.rhythm.engine.ShelfKind
import com.elchanan.rhythm.engine.StyleLearner
import com.elchanan.rhythm.engine.StyleTraining
import com.elchanan.rhythm.engine.Styles
import com.elchanan.rhythm.engine.TasteReport
import com.elchanan.rhythm.engine.Versions
import com.elchanan.rhythm.playback.PlayerConnection
import com.elchanan.rhythm.playback.QueueMeta
import com.elchanan.rhythm.playback.SleepTimer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * How long MediaStore has to stay quiet before the library is rebuilt.
 *
 * A bulk index fires change notifications continuously; rescanning on
 * each one would rewrite the song table hundreds of times and finish with
 * the same answer as waiting for the end.
 */
private const val MEDIA_SETTLE_MS = 3_000L

data class AlbumInfo(
    val albumId: Long,
    val name: String,
    val artistName: String,
    val songs: List<SongEntity>
)

data class ArtistInfo(
    val key: String,
    val displayName: String,
    val rating: Int,
    val styles: String,
    val note: String,
    val songs: List<SongEntity>
)

data class LibraryState(
    val loaded: Boolean = false,
    val songs: List<SongEntity> = emptyList(),
    val songsById: Map<Long, SongEntity> = emptyMap(),
    val stats: Map<Long, SongStatsEntity> = emptyMap(),
    val artists: List<ArtistInfo> = emptyList(),
    val albums: List<AlbumInfo> = emptyList()
) {
    val liked: List<SongEntity>
        get() = songs.filter { stats[it.id]?.liked == 1 }

    /**
     * Anything the user marked as speech by hand.
     *
     * The detector's own verdict needs the measured features, which this state
     * does not carry, so the full list is assembled in the view model where
     * they are available. This is the part that needs nothing but the stats.
     */
    val markedSpoken: List<SongEntity>
        get() = songs.filter { stats[it.id]?.spoken == 1 }
}

/** A list the user drilled into. Held in the view model so navigation routes
 *  stay free of encoded Hebrew keys. */
data class DetailList(
    val title: String,
    val subtitle: String?,
    val songs: List<SongEntity>,
    val gradientKey: String,
    val playlistId: Long? = null
)

data class PlaylistInfo(
    val playlist: PlaylistEntity,
    val songs: List<SongEntity>
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo: MusicRepository = (app as RhythmApp).repository
    private val analysis: AnalysisManager = (app as RhythmApp).analysis
    val player = PlayerConnection(app, viewModelScope)

    val analysisProgress: StateFlow<AnalysisManager.Progress> = analysis.progress

    val featuresById: StateFlow<Map<Long, AudioFeatureEntity>> =
        repo.features
            .map { list -> list.filter { it.energy > 0f }.associateBy { it.songId } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val library: StateFlow<LibraryState> =
        combine(repo.songs, repo.stats, repo.artists) { all, stats, artists ->
            // Collapsed before anything is derived from the list, so a hidden
            // copy is gone from the artist counts and the albums too rather
            // than only from the songs tab.
            val songs = if (repo.prefs.hideDuplicates) {
                // Indexed first: classify calls this once per song, and a linear
                // scan inside it would make building the library quadratic.
                val playsById = stats.associate { it.songId to it.playCount }
                val types = Versions.classify(all) { id -> playsById[id] ?: 0 }
                Versions.withoutDuplicates(all, types)
            } else {
                all
            }
            val artistMap = artists.associateBy { it.artistKey }
            // Every artist named on a song, not only the first one. A duet
            // belongs on both singers' pages; the first name is still the one
            // the song is keyed on everywhere else, this only decides where it
            // is listed. Names are gathered as we go so a guest who never
            // appears alone still gets a page.
            val byArtist = LinkedHashMap<String, MutableList<SongEntity>>()
            val nameForKey = HashMap<String, String>()
            for (song in songs) {
                byArtist.getOrPut(song.artistKey) { ArrayList() }.add(song)
                nameForKey.putIfAbsent(song.artistKey, MediaScanner.primaryArtist(song.artistName))
                for (credit in MediaScanner.credits(song.artistName)) {
                    val key = MediaScanner.normalizeKey(credit)
                    if (key == song.artistKey) continue
                    byArtist.getOrPut(key) { ArrayList() }.add(song)
                    nameForKey.putIfAbsent(key, credit)
                }
            }
            val artistInfos = byArtist.map { (key, list) ->
                val profile = artistMap[key]
                ArtistInfo(
                    key = key,
                    displayName = profile?.displayName?.takeIf { it.isNotBlank() }
                        ?: nameForKey[key].orEmpty(),
                    rating = profile?.rating ?: 0,
                    styles = profile?.styles.orEmpty(),
                    note = profile?.note.orEmpty(),
                    songs = list.distinctBy { it.id }.sortedBy { it.titleLower }
                )
            }.sortedBy { it.displayName.lowercase(Locale.ROOT) }

            val albums = songs.groupBy { it.albumId }.map { (id, list) ->
                AlbumInfo(
                    albumId = id,
                    name = list.first().albumName,
                    artistName = list.first().artistName,
                    songs = list.sortedWith(compareBy({ it.trackNumber }, { it.titleLower }))
                )
            }.sortedBy { it.name.lowercase(Locale.ROOT) }

            LibraryState(
                loaded = true,
                songs = songs.sortedBy { it.titleLower },
                songsById = songs.associateBy { it.id },
                stats = stats.associateBy { it.songId },
                artists = artistInfos,
                albums = albums
            )
        }
            // Grouping, sorting and rebuilding every artist and album happens on
            // each rating, like or play count change. viewModelScope collects on
            // the main thread, so without this it all lands there.
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryState())

    /**
     * Shiurim, stories and anything else that is talking rather than music.
     *
     * Built from three things because no one of them is enough on its own:
     * what the user said, what the tagging model heard, and how long the file
     * is. The reasoning lives in [Spoken]; this is where it meets the library.
     *
     * Longest first, because the long ones are what this shelf exists for.
     *
     * Declared after [library] and [featuresById]: Kotlin builds properties in
     * the order they are written, and a flow combining two that do not exist
     * yet would combine nulls.
     */
    val spokenWord: StateFlow<List<SongEntity>> =
        combine(library, featuresById) { state, features ->
            if (state.songs.isEmpty()) return@combine emptyList()
            state.songs
                .filter { song ->
                    val feature = features[song.id]
                    val tags = feature?.tags
                        ?.let { AudioTags.pick(it, AudioTags.SPEECH_INDICES) }
                    Spoken.isSpoken(song, feature, tags, state.stats[song.id]?.spoken ?: -1)
                }
                .sortedByDescending { it.durationMs }
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val playlists: StateFlow<List<PlaylistInfo>> =
        combine(repo.playlists, repo.playlistItems, repo.songs) { lists, items, songs ->
            val byId = songs.associateBy { it.id }
            lists.map { pl ->
                PlaylistInfo(
                    playlist = pl,
                    songs = items.filter { it.playlistId == pl.id }
                        .sortedBy { it.position }
                        .mapNotNull { byId[it.songId] }
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _feed = MutableStateFlow<List<FeedSection>>(emptyList())
    val feed: StateFlow<List<FeedSection>> = _feed.asStateFlow()

    private val _report = MutableStateFlow<TasteReport?>(null)
    val report: StateFlow<TasteReport?> = _report.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _detail = MutableStateFlow<DetailList?>(null)
    val detail: StateFlow<DetailList?> = _detail.asStateFlow()

    private val _artistDetail = MutableStateFlow<ArtistInfo?>(null)
    val artistDetail: StateFlow<ArtistInfo?> = _artistDetail.asStateFlow()

    private val _lyrics = MutableStateFlow<LyricsEntity?>(null)
    val lyrics: StateFlow<LyricsEntity?> = _lyrics.asStateFlow()

    private val _lyricLines = MutableStateFlow<List<LyricLine>>(emptyList())
    val lyricLines: StateFlow<List<LyricLine>> = _lyricLines.asStateFlow()

    private var lyricsLoadedFor: Long = -1L

    private val _lyricsFolder = MutableStateFlow<String?>(null)
    val lyricsFolder: StateFlow<String?> = _lyricsFolder.asStateFlow()

    private val _recap = MutableStateFlow<MusicRepository.RecapData?>(null)
    val recap: StateFlow<MusicRepository.RecapData?> = _recap.asStateFlow()

    val autoAddedIds: StateFlow<Set<Long>> = QueueMeta.autoAdded

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<SongEntity>>(emptyList())
    val searchResults: StateFlow<List<SongEntity>> = _searchResults.asStateFlow()

    @Volatile
    private var engine: Recommender? = null

    val prefs get() = repo.prefs

    private var queueRestored = false

    /** What the last scan found, and where the files it dropped went. */
    val scanReport: StateFlow<MusicRepository.ScanReport?> = repo.lastScan

    private var launchScanDone = false

    /**
     * Brings the library up to date with the device, once per launch.
     *
     * This used to run only when nothing had ever been scanned, which made the
     * very first scan final. It happens moments after the permission is
     * granted, which on a freshly filled phone is while the system is still
     * indexing - so the app would catch a fraction of the library, write down
     * that it had scanned, and never look again. Someone with two thousand
     * songs could be left with two hundred and no way to tell why.
     */
    fun scanOnLaunch() {
        if (launchScanDone) return
        launchScanDone = true
        rescan(showMessage = false)
    }

    /**
     * Follows the device while the app is open.
     *
     * The library is a view of MediaStore, and MediaStore changes underneath
     * it: the system finishes indexing, a file is copied in over USB, another
     * app deletes one. Without this the only cure is the rescan button in
     * settings, which is a strange thing to need on a music player.
     */
    private val mediaObserver = object : android.database.ContentObserver(
        android.os.Handler(android.os.Looper.getMainLooper())
    ) {
        override fun onChange(selfChange: Boolean) = onMediaStoreChanged()
    }

    private var mediaChangeJob: Job? = null

    /**
     * Debounced, because a bulk index fires this hundreds of times a second
     * and each rescan rewrites the whole song table.
     */
    private fun onMediaStoreChanged() {
        mediaChangeJob?.cancel()
        mediaChangeJob = viewModelScope.launch {
            delay(MEDIA_SETTLE_MS)
            if (!_busy.value) rescan(showMessage = false)
        }
    }

    init {
        player.connect()
        _lyricsFolder.value = repo.prefs.lyricsFolderUri
        runCatching {
            app.contentResolver.registerContentObserver(
                android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                true,
                mediaObserver
            )
        }
        viewModelScope.launch {
            analysis.refreshCounts()
            if (repo.prefs.lastScanAt == 0L) return@launch
            refreshFeed()
        }
    }

    /**
     * Puts the queue from the previous session back, once, as soon as both the
     * library and the media controller are ready.
     */
    fun restoreQueueIfNeeded() {
        if (queueRestored) return
        val lib = library.value
        if (!lib.loaded || !player.state.value.connected) return
        if (player.state.value.queueIds.isNotEmpty()) {
            queueRestored = true
            return
        }
        val saved = repo.prefs.savedQueue
        if (saved.isEmpty()) return
        val songs = saved.mapNotNull { lib.songsById[it] }
        if (songs.isEmpty()) {
            queueRestored = true
            return
        }
        queueRestored = true
        player.restore(songs, repo.prefs.savedQueueIndex, repo.prefs.savedQueuePosition)
    }

    override fun onCleared() {
        runCatching {
            getApplication<Application>().contentResolver
                .unregisterContentObserver(mediaObserver)
        }
        player.release()
        super.onCleared()
    }

    // -----------------------------------------------------------------------
    // library maintenance
    // -----------------------------------------------------------------------

    fun rescan(showMessage: Boolean = true) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            val count = runCatching { repo.rescan() }.getOrDefault(0)
            refreshFeed()
            analysis.refreshCounts()
            if (repo.prefs.autoAnalyze) analysis.start()
            _busy.value = false
            if (showMessage) _message.value = "נסרקו $count שירים"
        }
    }

    fun refreshFeed(reshuffle: Boolean = false) {
        viewModelScope.launch {
            if (reshuffle) repo.prefs.feedSeed = repo.prefs.feedSeed + 1
            val e = runCatching { repo.buildRecommender() }.getOrNull() ?: return@launch
            engine = e
            // Building the feed is the heaviest thing the app does - a dozen
            // shelves, every song scored and ordered, and a greedy sequencer for
            // the mixes. Only the recommender snapshot was being taken off the
            // main thread; the rest ran on it, so anything that refreshed the
            // feed - rating an artist, for one - froze the whole interface and
            // taps simply went nowhere.
            val built = withContext(Dispatchers.Default) { e.buildFeed() to e.tasteReport() }
            // Written back so the next feed can defend this answer instead of
            // forming a fresh opinion about the listener every refresh.
            e.pickedMood?.let { repo.prefs.lastMood = it }
            // Filtered here rather than inside the engine: the engine's job is to
            // decide what is worth showing, and this is the user overruling that
            // afterwards. Keeping them apart means a shelf switched off still
            // costs nothing and comes back intact when it is switched on.
            val allowed = prefs.homeShelves
            _feed.value = built.first.filter { section ->
                ShelfKind.of(section.id)?.key?.let { it in allowed } ?: true
            }
            _report.value = built.second
            if (_searchQuery.value.isNotBlank()) doSearch(_searchQuery.value)
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    // -----------------------------------------------------------------------
    // search
    // -----------------------------------------------------------------------

    fun onSearchQuery(q: String) {
        _searchQuery.value = q
        doSearch(q)
    }

    private var lyricsSearchJob: Job? = null

    private fun doSearch(q: String) {
        lyricsSearchJob?.cancel()
        if (q.isBlank()) {
            _searchResults.value = emptyList()
            return
        }
        val e = engine
        val byText = if (e != null) {
            e.search(q, personal = if (prefs.searchPersonalized) 0.25 else 0.0)
        } else {
            val lower = q.lowercase(Locale.ROOT)
            library.value.songs.filter {
                it.titleLower.contains(lower) ||
                    it.artistName.lowercase(Locale.ROOT).contains(lower) ||
                    it.albumName.lowercase(Locale.ROOT).contains(lower)
            }.take(60)
        }
        _searchResults.value = byText

        // Lyrics come second, and separately. The database round trip is slower
        // than matching titles in memory, and holding the whole result back for
        // it would make every keystroke feel laggy. Title matches appear at
        // once; anything found only in the words joins them a moment later, and
        // always below - someone typing a title wants the title.
        if (!prefs.searchLyrics) return
        lyricsSearchJob = viewModelScope.launch {
            val ids = repo.songIdsWithLyrics(q).toSet()
            if (ids.isEmpty()) return@launch
            if (_searchQuery.value != q) return@launch
            val already = byText.mapTo(HashSet()) { it.id }
            val extra = library.value.songs.filter { it.id in ids && it.id !in already }
            if (extra.isNotEmpty()) {
                _searchResults.value = byText + extra
            }
        }
    }

    // -----------------------------------------------------------------------
    // playback
    // -----------------------------------------------------------------------

    /**
     * Bumped whenever the user deliberately starts something playing.
     *
     * The setting that opens the full player on play needs to know the
     * difference between "the user tapped a song" and "the queue moved on by
     * itself", and the player's own state cannot tell them apart - both look
     * like a new current song. A counter rather than a flag because two taps
     * on the same song are two events, and the screen has to react to the
     * second one as well.
     *
     * Queueing something for later is not a start: [playNext] and
     * [addToQueue] deliberately leave it alone.
     */
    private val _playbackStarted = MutableStateFlow(0)
    val playbackStarted: StateFlow<Int> = _playbackStarted.asStateFlow()

    private fun markStarted() {
        _playbackStarted.value = _playbackStarted.value + 1
    }

    fun playList(songs: List<SongEntity>, index: Int = 0, source: String? = null) {
        if (songs.isEmpty()) return
        QueueMeta.reset()
        QueueMeta.setSource(source ?: _detail.value?.title)
        player.play(songs, index)
        markStarted()
    }

    fun shuffleList(songs: List<SongEntity>) {
        if (songs.isEmpty()) return
        player.playShuffled(songs)
        markStarted()
    }

    /** "Start radio": one seed song plus an endless, ranked continuation. */
    fun startRadio(song: SongEntity) {
        viewModelScope.launch {
            val e = engine ?: repo.buildRecommender().also { engine = it }
            val list = e.radio(song, 40)
            QueueMeta.reset()
            QueueMeta.markAuto(list.drop(1).map { it.id })
            player.play(list, 0)
            markStarted()
            _message.value = "רדיו: ${song.title}"
        }
    }

    /**
     * Stores the "never mix these styles" rules and rebuilds the feed.
     *
     * The engine holds them as a parsed value taken at construction, so a
     * change only takes effect once it is thrown away.
     */
    fun setStyleSeparations(rules: String) {
        repo.prefs.styleSeparations = rules
        engine = null
        refreshFeed()
    }

    // -----------------------------------------------------------------------
    // files: sharing, deleting, genre
    // -----------------------------------------------------------------------

    /**
     * A delete the system wants the user to confirm, waiting to be shown.
     *
     * The dialog belongs to the platform and can only be launched from an
     * activity, so it is published here and the screen picks it up. Null when
     * there is nothing pending.
     */
    private val _deleteRequest = MutableStateFlow<android.content.IntentSender?>(null)
    val deleteRequest: StateFlow<android.content.IntentSender?> = _deleteRequest.asStateFlow()

    /** Remembered so the library can be refreshed once the dialog comes back. */
    private var deletePending: List<Long> = emptyList()

    fun shareSongs(songs: List<SongEntity>) {
        val intent = FileActions.shareIntent(songs) ?: return
        val chooser = Intent.createChooser(intent, "שיתוף")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { getApplication<Application>().startActivity(chooser) }
            .onFailure { _message.value = "אין אפליקציה שיכולה לקבל את הקובץ" }
    }

    /**
     * Deletes files from the device.
     *
     * The caller has already asked. This is the point of no return, and on
     * Android 11 and up the system asks a second time on top - which is not
     * duplication worth removing, because the app's own question names the
     * songs and the system's names the consequence.
     */
    fun deleteSongs(songs: List<SongEntity>) {
        if (songs.isEmpty()) return
        deletePending = songs.map { it.id }
        when (val outcome = FileActions.delete(getApplication(), songs)) {
            is FileActions.DeleteOutcome.Done -> {
                _message.value = "נמחקו ${outcome.count} קבצים"
                finishDelete(true)
            }
            is FileActions.DeleteOutcome.NeedsConfirmation ->
                _deleteRequest.value = outcome.request
            is FileActions.DeleteOutcome.Failed -> {
                deletePending = emptyList()
                _message.value = outcome.reason.ifBlank { "המחיקה נכשלה" }
            }
        }
    }

    /** Called once the system's dialog closes, either way. */
    fun onDeleteResult(confirmed: Boolean) {
        _deleteRequest.value = null
        finishDelete(confirmed)
    }

    private fun finishDelete(confirmed: Boolean) {
        val ids = deletePending
        deletePending = emptyList()
        if (!confirmed || ids.isEmpty()) return
        viewModelScope.launch {
            // The rows have to go too, or the songs stay in the library
            // pointing at files that are no longer there.
            repo.forgetSongs(ids)
            engine = null
            rescan(showMessage = false)
        }
    }

    // -----------------------------------------------------------------------
    // bookmarks
    // -----------------------------------------------------------------------

    fun bookmarksFor(songId: Long): Flow<List<BookmarkEntity>> = repo.bookmarks(songId)

    /**
     * Where each part-heard recording was left, keyed by song.
     *
     * Only the unfinished ones: a recording heard to the end has its row
     * removed, so this is exactly the list of things worth carrying on with.
     */
    val positions: StateFlow<Map<Long, PlaybackPositionEntity>> =
        repo.positions()
            .map { list -> list.associateBy { it.songId } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun addBookmark(songId: Long, positionMs: Long, label: String) {
        viewModelScope.launch {
            repo.addBookmark(songId, positionMs, label.trim())
            _message.value = "סימנייה נשמרה"
        }
    }

    fun deleteBookmark(id: Long) {
        viewModelScope.launch { repo.deleteBookmark(id) }
    }

    /** The user correcting the speech detector, in either direction. */
    fun setSpoken(songId: Long, spoken: Boolean) {
        viewModelScope.launch {
            repo.setSpoken(songId, spoken)
            _message.value = if (spoken) "סומן כהרצאה" else "סומן כמוזיקה"
        }
    }

    /** Sets a genre on one song, a whole album, or a selection. */
    fun setGenre(songIds: List<Long>, genre: String) {
        if (songIds.isEmpty()) return
        viewModelScope.launch {
            repo.setGenre(songIds, genre)
            engine = null
            refreshFeed()
            _message.value = if (genre.isBlank()) {
                "הז'אנר נוקה מ-${songIds.size} שירים"
            } else {
                "$genre הוגדר ל-${songIds.size} שירים"
            }
        }
    }

    /** Forgets that a song was played, keeping the like, rating and tags. */
    fun resetPlayCount(song: SongEntity) {
        viewModelScope.launch {
            repo.resetPlayCount(song.id)
            engine = null
            _message.value = "אופסו ההשמעות של ${song.title}"
        }
    }

    /** The same for a whole artist. */
    fun resetArtistPlayCounts(artistKey: String, displayName: String) {
        viewModelScope.launch {
            val cleared = repo.resetArtistPlayCounts(artistKey)
            engine = null
            _message.value = if (cleared == 0) {
                "אין השמעות ל$displayName"
            } else {
                "אופסו ההשמעות של $displayName ($cleared שירים)"
            }
        }
    }

    fun playNext(song: SongEntity) {
        QueueMeta.markManual(listOf(song.id))
        player.playNext(song)
        _message.value = "יתנגן הבא: ${song.title}"
    }

    fun addToQueue(song: SongEntity) {
        QueueMeta.markManual(listOf(song.id))
        player.addToQueue(listOf(song))
        _message.value = "נוסף לתור"
    }

    // -----------------------------------------------------------------------
    // feedback
    // -----------------------------------------------------------------------

    fun like(songId: Long) = feedback(songId, 1)
    fun dislike(songId: Long) = feedback(songId, -1)

    private fun feedback(songId: Long, value: Int) {
        viewModelScope.launch {
            repo.setLike(songId, value)
            val nowDisliked = library.value.stats[songId]?.liked != value && value == -1
            if (nowDisliked) {
                // behave like YouTube Music: a dislike on the current song moves on
                if (player.state.value.currentSongId == songId) player.next()
            }
            refreshFeed()
        }
    }

    // -----------------------------------------------------------------------
    // lyrics
    // -----------------------------------------------------------------------

    /** Loads once per song; repeated calls for the same song are ignored. */
    fun loadLyrics(song: SongEntity) {
        if (lyricsLoadedFor == song.id) return
        lyricsLoadedFor = song.id
        viewModelScope.launch {
            val found = runCatching { repo.lyricsFor(song) }.getOrNull()
            _lyrics.value = found
            _lyricLines.value = found?.synced?.takeIf { it.isNotBlank() }
                ?.let { LyricsSource.parseLrc(it) }
                .orEmpty()
        }
    }

    fun refreshLyrics(song: SongEntity) {
        viewModelScope.launch {
            _busy.value = true
            val found = runCatching { repo.refreshLyrics(song) }.getOrNull()
            _lyrics.value = found
            _lyricLines.value = found?.synced?.takeIf { it.isNotBlank() }
                ?.let { LyricsSource.parseLrc(it) }
                .orEmpty()
            _busy.value = false
            _message.value = when {
                found == null || (found.text.isBlank() && found.synced.isBlank()) ->
                    "לא נמצאו מילים לשיר הזה"
                found.source == "file" -> "נטענו מילים מקובץ"
                else -> "נטענו מילים מתוך תגיות הקובץ"
            }
        }
    }

    fun saveLyrics(songId: Long, text: String, synced: String) {
        viewModelScope.launch {
            repo.saveLyrics(songId, text, synced)
            lyricsLoadedFor = -1L
            library.value.songsById[songId]?.let { loadLyrics(it) }
            _message.value = "המילים נשמרו"
        }
    }

    fun setLyricsFolder(uri: String?) {
        repo.prefs.lyricsFolderUri = uri
        _lyricsFolder.value = uri
        _message.value = if (uri == null) "תיקיית המילים נוקתה" else "תיקיית המילים נבחרה"
    }

    // -----------------------------------------------------------------------
    // mixes built from a single song
    // -----------------------------------------------------------------------

    /**
     * The YouTube Music style "start a mix from this song": builds a full,
     * ordered list around one seed and opens it as its own screen instead of
     * replacing what is currently playing.
     */
    fun createMix(song: SongEntity, andPlay: Boolean = false, onReady: () -> Unit = {}) {
        viewModelScope.launch {
            val e = engine ?: repo.buildRecommender().also { engine = it }
            val list = e.radio(song, 60)
            openList(
                title = "מיקס: ${song.title}",
                subtitle = "${list.size} שירים סביב ${song.artistName}",
                songs = list,
                key = "mix:seed:${song.id}"
            )
            if (andPlay) {
                player.play(list, 0)
                markStarted()
            }
            onReady()
        }
    }

    fun rateSong(songId: Long, rating: Int) {
        viewModelScope.launch {
            repo.setSongRating(songId, rating)
            refreshFeed()
        }
    }

    fun setSongStyles(songId: Long, styles: String) {
        viewModelScope.launch {
            repo.setSongStyles(songId, styles)
            refreshFeed()
        }
    }

    /** Score breakdown for the "why was this picked" sheet. */
    fun explain(song: SongEntity): List<ScoreTerm> = engine?.explain(song).orEmpty()

    fun totalScore(song: SongEntity): Double = engine?.totalScore(song) ?: 0.0

    // -----------------------------------------------------------------------
    // audio analysis
    // -----------------------------------------------------------------------

    fun startAnalysis() = analysis.start()

    fun stopAnalysis() = analysis.stop()

    fun resetAnalysis() {
        viewModelScope.launch {
            analysis.reset()
            refreshFeed()
            _message.value = "ניתוח האודיו אופס"
        }
    }

    // -----------------------------------------------------------------------
    // sleep timer
    // -----------------------------------------------------------------------

    fun sleepIn(minutes: Int) {
        SleepTimer.startMinutes(minutes)
        _message.value = "הנגינה תיעצר בעוד $minutes דקות"
    }

    fun sleepAfterTrack() {
        SleepTimer.stopAfterCurrentTrack()
        _message.value = "ייעצר בסוף השיר הנוכחי"
    }

    fun cancelSleep() {
        SleepTimer.cancel()
        _message.value = "טיימר השינה בוטל"
    }

    fun rateArtist(key: String, name: String, rating: Int, styles: String, note: String = "") {
        viewModelScope.launch {
            repo.rateArtist(key, name, rating, styles, note)
            refreshFeed()
        }
    }

    fun bulkImportArtists(text: String) {
        viewModelScope.launch {
            _busy.value = true
            val n = runCatching { repo.importArtistLines(text) }.getOrDefault(0)
            refreshFeed()
            _busy.value = false
            _message.value = "עודכנו $n אמנים"
        }
    }

    fun importArtistsJson(json: String) {
        viewModelScope.launch {
            _busy.value = true
            val n = runCatching { repo.importArtists(json) }.getOrDefault(0)
            refreshFeed()
            _busy.value = false
            _message.value = "יובאו $n אמנים"
        }
    }

    suspend fun exportArtistsJson(): String = repo.exportArtists()

    // -----------------------------------------------------------------------
    // playlists
    // -----------------------------------------------------------------------

    fun createPlaylist(name: String, initial: SongEntity? = null) {
        viewModelScope.launch {
            val id = repo.createPlaylist(name)
            initial?.let { repo.addToPlaylist(id, it.id) }
            _message.value = "נוצרה רשימה: $name"
        }
    }

    /**
     * Writes every list the app knows about into a folder the user picked.
     *
     * Not only the playlists they made. The mixes, the daily mixes and the
     * mood filters are worked out on this device and exist nowhere else, so
     * without this they cannot be taken anywhere - not to another player, not
     * to a new phone, not even backed up. Everything goes out as M3U8, which
     * every player reads.
     *
     * @param tree the folder, from OpenDocumentTree.
     */
    fun exportAllPlaylists(tree: Uri) {
        viewModelScope.launch {
            _busy.value = true
            val result = runCatching {
                val lists = gatherExportable()
                withContext(Dispatchers.IO) { writeLists(tree, lists) }
            }
            _busy.value = false
            val written = result.getOrNull()
            _message.value = when {
                written == null -> "הייצוא נכשל - בדוק את ההרשאה לתיקייה"
                written == 0 -> "אין מה לייצא עדיין"
                else -> "יוצאו $written רשימות"
            }
        }
    }

    /**
     * Everything worth writing out, as name-to-songs.
     *
     * Deliberately built here rather than read from one place, because these
     * come from three different mechanisms: the playlist table, the engine's
     * clustering, and a filter over the measured features. What they have in
     * common is only that the user thinks of all of them as their lists.
     */
    private suspend fun gatherExportable(): List<Pair<String, List<SongEntity>>> {
        val out = ArrayList<Pair<String, List<SongEntity>>>()

        for (info in playlists.value) {
            if (info.songs.isNotEmpty()) out.add(info.playlist.name to info.songs)
        }

        val engineNow = engine ?: repo.buildRecommender().also { engine = it }
        for (mix in engineNow.dailyMixes()) {
            if (mix.songs.isNotEmpty()) out.add("מיקס - ${mix.title}" to mix.songs)
        }

        val songs = library.value.songs
        if (songs.isNotEmpty()) {
            val features = repo.featureMap()
            if (features.isNotEmpty()) {
                val model = MoodModel(features.values)
                if (model.ready) {
                    for (mood in Mood.entries) {
                        val matching = songs.filter { model.matches(mood, features[it.id]) }
                        // A mood that matched two songs is not a list.
                        if (matching.size >= 5) {
                            out.add("מצב רוח - ${mood.label}" to matching)
                        }
                    }
                }
            }
        }
        return out
    }

    private fun writeLists(tree: Uri, lists: List<Pair<String, List<SongEntity>>>): Int {
        if (lists.isEmpty()) return 0
        val context = getApplication<Application>()
        val folder = DocumentFile.fromTreeUri(context, tree) ?: return 0
        val taken = HashSet<String>()
        var written = 0
        for ((name, songs) in lists) {
            val fileName = PlaylistExport.uniqueName(name, taken)
            val file = folder.createFile(PlaylistExport.MIME, fileName) ?: continue
            val ok = runCatching {
                context.contentResolver.openOutputStream(file.uri)?.use { stream ->
                    stream.write(PlaylistExport.write(name, songs).toByteArray(Charsets.UTF_8))
                } ?: return@runCatching false
                true
            }.getOrDefault(false)
            if (ok) written++
        }
        return written
    }

    /**
     * Imports an .m3u, .m3u8 or .pls written by another player.
     *
     * Reported honestly: a playlist that half matched looks identical to one
     * that fully matched unless the count says otherwise, and the usual reason
     * for a miss is that the other app's library covers folders this one is not
     * scanning.
     */
    fun importPlaylist(uri: Uri, displayName: String) {
        viewModelScope.launch {
            _busy.value = true
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    val content = getApplication<Application>().contentResolver
                        .openInputStream(uri)?.use { stream ->
                            stream.readBytes().toString(Charsets.UTF_8)
                        } ?: return@withContext null
                    val parsed = PlaylistImport.parse(content, displayName)
                    val (songs, missing) = PlaylistImport.match(parsed.entries, library.value.songs)
                    Triple(parsed.name, songs, missing)
                }
            }.getOrNull()
            _busy.value = false

            if (outcome == null) {
                _message.value = "לא הצלחתי לקרוא את הקובץ"
                return@launch
            }
            val (name, songs, missing) = outcome
            if (songs.isEmpty()) {
                _message.value = "לא נמצאו שירים מהרשימה בספרייה שלך"
                return@launch
            }
            val id = repo.createPlaylist(name)
            repo.bulkAddToPlaylist(id, songs.map { it.id })
            _message.value = if (missing > 0) {
                "יובאו ${songs.size} שירים · $missing לא נמצאו"
            } else {
                "יובאה הרשימה \"$name\" עם ${songs.size} שירים"
            }
        }
    }

    fun addToPlaylist(playlistId: Long, songId: Long) {
        viewModelScope.launch {
            repo.addToPlaylist(playlistId, songId)
            _message.value = "נוסף לרשימה"
        }
    }

    fun removeFromPlaylist(playlistId: Long, songId: Long) {
        viewModelScope.launch { repo.removeFromPlaylist(playlistId, songId) }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch { repo.deletePlaylist(playlistId) }
    }

    // -----------------------------------------------------------------------
    // moods, recap and bulk editing
    // -----------------------------------------------------------------------

    // -----------------------------------------------------------------------
    // tag corrections
    // -----------------------------------------------------------------------

    private val _tagProposals = MutableStateFlow<List<TagFixer.Proposal>>(emptyList())
    val tagProposals: StateFlow<List<TagFixer.Proposal>> = _tagProposals.asStateFlow()

    private val tagFiles = TagFileWriter(getApplication())

    fun buildTagProposals() {
        _tagProposals.value =
            TagFixer.propose(library.value.songs, dropForeign = prefs.tagStripForeign)
    }

    /** Re-proposes with the new setting so the preview updates as it is flipped. */
    fun setTagStripForeign(enabled: Boolean) {
        prefs.tagStripForeign = enabled
        buildTagProposals()
    }

    /**
     * Files waiting on the system's permission dialog.
     *
     * The corrections are already saved in the app at this point; this is only
     * about the optional second step of pushing them into the files.
     */
    private var pendingWrites: List<TagFileWriter.Item> = emptyList()

    private val _writePermissionRequest = MutableStateFlow<IntentSender?>(null)
    val writePermissionRequest: StateFlow<IntentSender?> = _writePermissionRequest.asStateFlow()

    /** The older, broader storage permission, for devices below Android 11. */
    private val _legacyPermissionRequest = MutableStateFlow(false)
    val legacyPermissionRequest: StateFlow<Boolean> = _legacyPermissionRequest.asStateFlow()

    fun applyTagFix(proposals: List<TagFixer.Proposal>) {
        viewModelScope.launch {
            val changed = proposals.filter { it.changed }
            repo.saveOverrides(TagFixer.toOverrides(proposals))
            prefs.tagTipSeen = true
            // The shelves hold a snapshot of the songs taken when the feed was
            // built, so without this the home screen keeps showing the old
            // titles while the player shows the corrected ones.
            refreshFeed()
            _message.value =
                if (changed.isEmpty()) "אין מה לתקן" else "עודכנו ${changed.size} שירים"
            if (changed.isNotEmpty() && prefs.writeTagsToFiles) startFileWrite(changed)
        }
    }

    private fun startFileWrite(changed: List<TagFixer.Proposal>) {
        pendingWrites = changed.map {
            TagFileWriter.Item(it.songId, it.newTitle, it.newArtist)
        }
        val request = tagFiles.permissionRequest(pendingWrites)
        when {
            // Android 11 and up: the system asks about these exact files.
            request != null -> _writePermissionRequest.value = request
            // Below that, one broad permission covers writing, and the app has
            // only ever asked for the reading half of it.
            tagFiles.needsLegacyPermission() -> _legacyPermissionRequest.value = true
            else -> runPendingWrites()
        }
    }

    /** Called once the system dialog has been answered. */
    fun onWritePermissionResult(granted: Boolean) {
        _writePermissionRequest.value = null
        _legacyPermissionRequest.value = false
        if (granted) {
            runPendingWrites()
        } else {
            pendingWrites = emptyList()
            writeRetried = false
            _message.value = "התיקון נשמר באפליקציה. הקבצים לא שונו."
        }
    }

    /**
     * Guards the one retry Android 10 is allowed.
     *
     * There the permission dialog only appears after a write has been refused,
     * so the sequence is write, ask, write again. Without this flag a refusal
     * that keeps repeating would bounce the dialog back forever.
     */
    private var writeRetried = false

    private fun runPendingWrites() {
        val items = pendingWrites
        pendingWrites = emptyList()
        if (items.isEmpty()) return
        viewModelScope.launch {
            _message.value = "כותב לקבצים..."
            val outcome = tagFiles.write(items)
            val recovery = outcome.recovery
            if (recovery != null && !writeRetried) {
                writeRetried = true
                pendingWrites = items
                _writePermissionRequest.value = recovery
                return@launch
            }
            writeRetried = false
            _message.value = when {
                outcome.written == 0 -> "לא הצלחתי לכתוב לקבצים. התיקון נשמר באפליקציה."
                outcome.ok -> "נכתבו ${outcome.written} קבצים"
                else -> "נכתבו ${outcome.written}, נכשלו ${outcome.failed}"
            }
        }
    }

    /** Saves one hand typed correction. Blank fields fall back to the file. */
    fun saveTagOverride(songId: Long, title: String, artist: String) {
        viewModelScope.launch {
            repo.saveOverrides(
                listOf(
                    TagOverrideEntity(
                        songId = songId,
                        title = title.trim(),
                        artistName = artist.trim(),
                        albumName = ""
                    )
                )
            )
            prefs.tagTipSeen = true
            refreshFeed()
            _message.value = "התגית עודכנה"
            if (prefs.writeTagsToFiles) {
                startFileWrite(
                    listOf(
                        TagFixer.Proposal(
                            songId = songId,
                            oldTitle = "",
                            oldArtist = "",
                            newTitle = title.trim(),
                            newArtist = artist.trim()
                        )
                    )
                )
            }
        }
    }

    fun resetTagFix() {
        viewModelScope.launch {
            repo.clearOverrides()
            refreshFeed()
            _message.value = "התגיות המקוריות שוחזרו"
        }
    }

    /**
     * Whether each home nudge should still be shown.
     *
     * Held as state rather than read straight from preferences: Compose can only
     * recompose against something it has observed, and a preference read gives
     * it nothing to watch - the dismiss button worked and the banner simply
     * stayed on screen.
     */
    private val _tagTipVisible = MutableStateFlow(!repo.prefs.tagTipSeen)
    val tagTipVisible: StateFlow<Boolean> = _tagTipVisible.asStateFlow()

    private val _ratingTipVisible = MutableStateFlow(!repo.prefs.ratingTipSeen)
    val ratingTipVisible: StateFlow<Boolean> = _ratingTipVisible.asStateFlow()

    fun dismissTagTip() {
        prefs.tagTipSeen = true
        _tagTipVisible.value = false
    }

    fun dismissRatingTip() {
        prefs.ratingTipSeen = true
        _ratingTipVisible.value = false
    }

    /**
     * Bumped when the home tab is tapped while home is already showing.
     *
     * Navigation has nothing to do there, so without this the tap looks dead -
     * which reads as "the home button does not work" even though it did exactly
     * what it was asked. Scrolling back to the top is what the tap was for.
     */
    private val _homeTopSignal = MutableStateFlow(0)
    val homeTopSignal: StateFlow<Int> = _homeTopSignal.asStateFlow()

    /**
     * How the last style-learning run went, so the screen can say something
     * honest rather than just "done".
     */
    data class LearnResult(
        val accuracy: Double?,
        val trained: Int,
        val labelled: Int,
        val applied: Int,
        /** Songs whose artist carries style tags: the labels to learn from. */
        val withStyles: Int = 0,
        /** Songs with something measured or heard: the evidence to learn from. */
        val withEvidence: Int = 0
    )

    private val _learnResult = MutableStateFlow<LearnResult?>(null)
    val learnResult: StateFlow<LearnResult?> = _learnResult.asStateFlow()

    /**
     * Learns the user's own style words from their own library and fills in the
     * songs they never tagged.
     *
     * Measured before it is trusted. The model is fitted on half the tagged
     * songs and scored on the other half, and if it cannot beat a coin toss by
     * a clear margin nothing is written - a library quietly filled with wrong
     * labels is worse than one with no labels, because the wrong ones then feed
     * the recommender as though somebody had confirmed them.
     */
    fun learnStyles() {
        viewModelScope.launch {
            _busy.value = true
            val outcome = runCatching {
                withContext(Dispatchers.Default) {
                    val features = repo.featureMap()
                    val tagsBySong = features.mapValues { it.value.tags }
                    val lib = library.value
                    val stylesByArtist = lib.artists.associate { it.key to it.styles }

                    // The measured half of the evidence. Built from the same
                    // rows the recommender uses, so "loud" and "fast" mean
                    // here exactly what they mean everywhere else in the app.
                    val space = if (features.size >= 8) {
                        AcousticSpace(features.values)
                    } else {
                        null
                    }

                    // Counted separately so the screen can name the half that
                    // is missing. "Not enough information" is true of every
                    // failure here and useless in all of them.
                    val withStyles = lib.songs.count {
                        Styles.parse(stylesByArtist[it.artistKey].orEmpty()).isNotEmpty()
                    }
                    val withEvidence = lib.songs.count {
                        StyleTraining.featuresFor(
                            it.id, tagsBySong[it.id].orEmpty(), space
                        ) != null
                    }

                    val rows = StyleTraining.rows(lib.songs, tagsBySong, stylesByArtist, space)
                    if (rows.isEmpty()) {
                        return@withContext LearnResult(
                            null, 0, 0, 0, withStyles, withEvidence
                        )
                    }

                    // The held-out half also picks how hard to regularise;
                    // the final model is then fitted with that same strength,
                    // not a different one.
                    val validation = StyleLearner.crossValidate(rows)
                    val accuracy = validation?.accuracy
                    val model = StyleLearner.fit(rows, l2 = validation?.l2 ?: 0.1)
                        ?: return@withContext LearnResult(
                            accuracy, 0, rows.size, 0, withStyles, withEvidence
                        )

                    // Only worth applying when the held-out score says the model
                    // actually generalises.
                    if (accuracy == null || accuracy < 0.70) {
                        return@withContext LearnResult(
                            accuracy, rows.size, rows.size, 0, withStyles, withEvidence
                        )
                    }

                    // Written where the user left a blank, and over the app's
                    // own earlier guesses. Their words are the ground truth
                    // this was trained on and are never touched; a guess is
                    // only as good as the model that made it, and the model is
                    // better now than it was the first time this ran.
                    var applied = 0
                    for (song in lib.songs) {
                        val own = lib.stats[song.id]
                        val hasOwn = Styles.parse(own?.styles.orEmpty()).isNotEmpty()
                        if (hasOwn && own?.stylesAuto != 1) continue
                        if (Styles.parse(stylesByArtist[song.artistKey].orEmpty()).isNotEmpty()) continue
                        // The same vector the model was fitted on. Predicting
                        // from a different shape than it was trained on is the
                        // easiest way to get confident nonsense.
                        val x = StyleTraining.featuresFor(
                            song.id,
                            tagsBySong[song.id].orEmpty(),
                            space
                        ) ?: continue
                        val predicted = model.predict(x)
                        if (predicted.isEmpty()) continue
                        repo.setSongStyles(song.id, Styles.join(predicted), auto = true)
                        applied++
                    }
                    LearnResult(
                        accuracy, rows.size, rows.size, applied, withStyles, withEvidence
                    )
                }
            }.getOrNull()
            _busy.value = false
            _learnResult.value = outcome

            _message.value = when {
                outcome == null -> "הלמידה נכשלה"
                outcome.labelled == 0 && outcome.withStyles == 0 ->
                    "אף אמן לא תויג בסגנון. הלמידה לומדת מהתגיות שלך — סמן סגנונות " +
                        "לכמה אמנים בטאב \"אמנים\" ונסה שוב."
                outcome.labelled == 0 && outcome.withEvidence == 0 ->
                    "אף שיר עוד לא נותח. הרץ ניתוח אודיו בהגדרות וחזור לכאן."
                outcome.labelled == 0 ->
                    "${outcome.withStyles} שירים מתויגים ו-${outcome.withEvidence} מנותחים, " +
                        "אבל אלה לא אותם שירים."
                outcome.accuracy == null ->
                    "יש ${outcome.labelled} שירים מתויגים. צריך לפחות " +
                        "${StyleLearner.MIN_ROWS_TO_VALIDATE} כדי לבדוק אם הלמידה נכונה."
                outcome.trained == 0 ->
                    "יש ${outcome.labelled} שירים מתויגים, אבל אף סגנון לא הגיע ל-" +
                        "${StyleLearner.DEFAULT_MIN_PER_STYLE} דוגמאות עם מספיק דוגמאות נגדיות."
                outcome.applied == 0 ->
                    "דיוק נמדד: ${percent(outcome.accuracy)} — נמוך מדי, לא שיניתי כלום"
                else ->
                    "דיוק נמדד: ${percent(outcome.accuracy)} · תויגו ${outcome.applied} שירים"
            }
            if (outcome != null && outcome.applied > 0) refreshFeed()
        }
    }

    /**
     * Throws away every tag the app guessed, keeping every one that was typed.
     *
     * Worth having because the guesses are not revised on their own schedule:
     * they are rewritten the next time learning runs, and until then an early,
     * weak guess stays. This is how to start that over deliberately.
     */
    fun clearLearnedStyles() {
        viewModelScope.launch {
            val cleared = repo.clearLearnedStyles()
            engine = null
            refreshFeed()
            _message.value = if (cleared == 0) {
                "אין תגיות שהאפליקציה ניחשה"
            } else {
                "נוקו $cleared תגיות אוטומטיות. התגיות שהקלדת נשארו."
            }
        }
    }

    private val _sequenceReport = MutableStateFlow<Recommender.SequenceReport?>(null)
    val sequenceReport: StateFlow<Recommender.SequenceReport?> = _sequenceReport.asStateFlow()

    /**
     * Measures the ranking against what was actually played next.
     *
     * Exists so that changes to the scoring stop being arguments. The number
     * is optimistic in absolute terms - the statistics it ranks with already
     * contain the plays being predicted - but it is biased the same way on
     * every run, which is what makes two runs comparable.
     */
    fun evaluateEngine() {
        viewModelScope.launch {
            _busy.value = true
            val report = runCatching {
                val order = repo.playOrder()
                val e = repo.buildRecommender()
                withContext(Dispatchers.Default) { e.evaluateSequence(order) }
            }.getOrNull()
            _busy.value = false
            _sequenceReport.value = report
            _message.value = when {
                report == null ->
                    "אין עדיין מספיק היסטוריה כדי לבדוק. צריך רצף השמעות בספרייה של 20 שירים ומעלה."
                else ->
                    "נבדקו ${report.pairs} מעברים · " +
                        "בעשירייה הראשונה: ${percent(report.recallAt10)}"
            }
        }
    }

    private fun percent(value: Double): String = "${(value * 100).toInt()}%"

    /** Switching a shelf on or off rebuilds the feed so the change is immediate. */
    fun setHomeShelves(keys: Set<String>) {
        prefs.homeShelves = keys
        refreshFeed()
    }

    fun setHideDuplicates(enabled: Boolean) {
        prefs.hideDuplicates = enabled
        refreshFeed()
    }

    fun requestHomeTop() {
        _homeTopSignal.value = _homeTopSignal.value + 1
    }

    fun openMood(mood: Mood, onReady: () -> Unit) {
        viewModelScope.launch {
            // Read from the database rather than from featuresById. That flow is
            // only alive while a screen is subscribed to it, and no screen on the
            // home tab is - so tapping a mood chip there found an empty map and
            // reported that nothing had been analysed, however long the analysis
            // had been finished.
            val features = runCatching { repo.featureMap() }.getOrDefault(emptyMap())
            val list = withContext(Dispatchers.Default) {
                Mood.filter(library.value.songs, features, mood)
                    .filter { (library.value.stats[it.id]?.liked ?: 0) != -1 }
            }
            if (list.isEmpty()) {
                _message.value = if (features.isEmpty()) {
                    "השירים עדיין לא נותחו. אפשר להתחיל ניתוח בהגדרות."
                } else {
                    "אין שירים שמתאימים ל\"${mood.label}\" בספרייה הזאת"
                }
                return@launch
            }
            val ordered = engine?.let { e ->
                val head = list.maxByOrNull { e.totalScore(it) } ?: list.first()
                e.sequence(head, list.filter { it.id != head.id }.take(80))
            } ?: list
            openList(mood.label, mood.subtitle, ordered, "mood:${mood.name}")
            onReady()
        }
    }

    fun loadRecap() {
        viewModelScope.launch {
            _recap.value = runCatching { repo.buildRecap() }.getOrNull()
        }
    }

    fun bulkRateSongs(ids: List<Long>, rating: Int) {
        viewModelScope.launch {
            repo.bulkSetRating(ids, rating)
            refreshFeed()
            _message.value = "${ids.size} שירים דורגו"
        }
    }

    fun bulkLikeSongs(ids: List<Long>, value: Int) {
        viewModelScope.launch {
            repo.bulkSetLike(ids, value)
            refreshFeed()
            _message.value = if (value == 1) "${ids.size} שירים סומנו בלייק" else "עודכנו ${ids.size} שירים"
        }
    }

    fun bulkAddToPlaylist(playlistId: Long, ids: List<Long>) {
        viewModelScope.launch {
            repo.bulkAddToPlaylist(playlistId, ids)
            _message.value = "${ids.size} שירים נוספו לרשימה"
        }
    }

    fun bulkQueue(songs: List<SongEntity>) {
        player.addToQueue(songs)
        QueueMeta.markManual(songs.map { it.id })
        _message.value = "${songs.size} שירים נוספו לתור"
    }

    fun bulkUpdateArtists(
        keys: List<String>,
        rating: Int?,
        styles: List<String>?,
        replaceStyles: Boolean
    ) {
        if (keys.isEmpty()) return
        viewModelScope.launch {
            val names = library.value.artists.associate { it.key to it.displayName }
            repo.bulkUpdateArtists(keys, names, rating, styles, replaceStyles)
            refreshFeed()
            _message.value = "עודכנו ${keys.size} אמנים"
        }
    }

    // -----------------------------------------------------------------------
    // settings
    // -----------------------------------------------------------------------

    fun updateTuning(
        discovery: Float? = null,
        artistWeight: Float? = null,
        styleWeight: Float? = null,
        repeatGuard: Float? = null,
        autoRadio: Boolean? = null,
        minDurationSec: Int? = null,
        acousticWeight: Float? = null,
        autoAnalyze: Boolean? = null,
        excludedFolders: List<String>? = null,
        crossfadeMs: Int? = null,
        skipSilence: Boolean? = null
    ) {
        discovery?.let { repo.prefs.discovery = it }
        artistWeight?.let { repo.prefs.artistWeight = it }
        styleWeight?.let { repo.prefs.styleWeight = it }
        repeatGuard?.let { repo.prefs.repeatGuard = it }
        autoRadio?.let { repo.prefs.autoRadio = it }
        minDurationSec?.let { repo.prefs.minDurationSec = it }
        acousticWeight?.let { repo.prefs.acousticWeight = it }
        autoAnalyze?.let { repo.prefs.autoAnalyze = it }
        excludedFolders?.let { repo.prefs.excludedFolders = it }
        crossfadeMs?.let { repo.prefs.crossfadeMs = it }
        skipSilence?.let { repo.prefs.skipSilence = it }
        refreshFeed()
    }

    fun resetLearning() {
        viewModelScope.launch {
            repo.resetLearning()
            refreshFeed()
            _message.value = "היסטוריית הלמידה אופסה"
        }
    }

    // -----------------------------------------------------------------------
    // lookups used by the UI
    // -----------------------------------------------------------------------

    fun openList(title: String, subtitle: String?, songs: List<SongEntity>, key: String, playlistId: Long? = null) {
        _detail.value = DetailList(title, subtitle, songs, key, playlistId)
    }

    fun openMix(mix: Mix) = openList(mix.title, mix.subtitle, mix.songs, mix.id)

    fun openArtist(info: ArtistInfo) {
        _artistDetail.value = info
    }

    fun refreshOpenArtist() {
        val key = _artistDetail.value?.key ?: return
        _artistDetail.value = library.value.artists.firstOrNull { it.key == key } ?: _artistDetail.value
    }

    fun mixById(id: String): Mix? =
        _feed.value.flatMap { it.mixes }.firstOrNull { it.id == id }

    fun sectionById(id: String): FeedSection? = _feed.value.firstOrNull { it.id == id }

    fun artistProfile(key: String): ArtistEntity? =
        library.value.artists.firstOrNull { it.key == key }?.let {
            ArtistEntity(it.key, it.displayName, it.rating, it.styles, it.note, 0L)
        }
}
