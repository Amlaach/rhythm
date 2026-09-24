package com.elchanan.rhythm.ui

import android.app.Application
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.elchanan.rhythm.RhythmApp
import com.elchanan.rhythm.data.AnalysisManager
import com.elchanan.rhythm.data.AnalysisTransfer
import com.elchanan.rhythm.data.FileActions
import com.elchanan.rhythm.data.LibraryCatalogExport
import com.elchanan.rhythm.data.LibraryWorkService
import com.elchanan.rhythm.data.LyricsSource
import com.elchanan.rhythm.data.MusicRepository
import com.elchanan.rhythm.data.PlaylistExport
import com.elchanan.rhythm.data.PlayCountImport
import com.elchanan.rhythm.data.PlaylistImport
import com.elchanan.rhythm.data.YouTubeMusicImport
import com.elchanan.rhythm.data.TagEdit
import com.elchanan.rhythm.data.TagFileWriter
import com.elchanan.rhythm.data.TagFixer
import com.elchanan.rhythm.engine.HebrewSpelling
import com.elchanan.rhythm.data.db.ArtistEntity
import com.elchanan.rhythm.data.db.AudioFeatureEntity
import com.elchanan.rhythm.data.db.BookmarkEntity
import com.elchanan.rhythm.data.db.LyricsEntity
import com.elchanan.rhythm.data.db.PlaybackPositionEntity
import com.elchanan.rhythm.data.db.PlaylistEntity
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.data.db.SongStatsEntity
import com.elchanan.rhythm.data.db.TagOverrideEntity
import com.elchanan.rhythm.engine.ArtistStyles
import com.elchanan.rhythm.engine.AcousticSpace
import com.elchanan.rhythm.engine.AudioTags
import com.elchanan.rhythm.engine.FeedSection
import com.elchanan.rhythm.engine.LearnResult
import com.elchanan.rhythm.engine.SoundCheck
import com.elchanan.rhythm.engine.LyricLine
import com.elchanan.rhythm.engine.Lyrics
import com.elchanan.rhythm.engine.Mix
import com.elchanan.rhythm.engine.Mood
import com.elchanan.rhythm.engine.MusicModelEvaluation
import com.elchanan.rhythm.engine.MoodModel
import com.elchanan.rhythm.engine.JewishSeasons
import com.elchanan.rhythm.engine.Vocal
import com.elchanan.rhythm.engine.MoodMarks
import com.elchanan.rhythm.engine.Names
import com.elchanan.rhythm.engine.RecapData
import com.elchanan.rhythm.engine.Recommender
import com.elchanan.rhythm.engine.ScoreTerm
import com.elchanan.rhythm.engine.ShelfKind
import com.elchanan.rhythm.engine.SignalCalibration
import com.elchanan.rhythm.engine.SignalWeights
import com.elchanan.rhythm.engine.Spoken
import com.elchanan.rhythm.engine.isMedley
import com.elchanan.rhythm.playback.HookFinder
import com.elchanan.rhythm.engine.StyleLearner
import com.elchanan.rhythm.engine.StyleLearning
import com.elchanan.rhythm.engine.StyleTraining
import com.elchanan.rhythm.engine.Styles
import com.elchanan.rhythm.engine.TasteReport
import com.elchanan.rhythm.engine.Versions
import com.elchanan.rhythm.playback.PlayerConnection
import com.elchanan.rhythm.playback.QueueMeta
import com.elchanan.rhythm.playback.SleepTimer
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * How long MediaStore has to stay quiet before the library is rebuilt.
 *
 * A bulk index fires change notifications continuously; rescanning on
 * each one would rewrite the song table hundreds of times and finish with
 * the same answer as waiting for the end.
 */
private const val MEDIA_SETTLE_MS = 3_000L

/**
 * How long to believe a scan handed to the service is still going.
 *
 * Longer than any real scan, because cutting a live one short would show
 * the wrong thing; short enough that a service killed by the system does
 * not leave the app unable to scan again until it is restarted.
 */
private const val SCAN_WATCHDOG_MS = 15L * 60L * 1000L

/** Large enough for hundreds of thousands of rows, bounded before parsing. */
private const val MAX_ANALYSIS_TRANSFER_BYTES = 64 * 1024 * 1024

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
    val playlistId: Long? = null,
    /** Still being worked out: the screen is already open and says so. */
    val loading: Boolean = false
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
            .flowOn(Dispatchers.Default)
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
            // A Hebrew duet - "ישי ריבו ומוטי שטיינמץ" - belongs on both
            // pages too, when both singers are artists here in their own right.
            val known = Names.soloArtists(songs.map { it.artistName })
            for (song in songs) {
                byArtist.getOrPut(song.artistKey) { ArrayList() }.add(song)
                nameForKey.putIfAbsent(song.artistKey, Names.primaryArtist(song.artistName))
                for (credit in Names.credits(song.artistName, known)) {
                    val key = Names.normalizeKey(credit)
                    if (key == song.artistKey) continue
                    byArtist.getOrPut(key) { ArrayList() }.add(song)
                    nameForKey.putIfAbsent(key, credit)
                }
            }
            val artistInfos = byArtist.map { (key, list) ->
                val profile = artistMap[key]
                val name = profile?.displayName?.takeIf { it.isNotBlank() }
                    ?: nameForKey[key].orEmpty()
                ArtistInfo(
                    key = key,
                    displayName = name,
                    rating = profile?.rating ?: 0,
                    // The shipped catalogue fills in only where nothing was
                    // typed, and is read here rather than written to the
                    // database. A correction is therefore permanent - it is a
                    // stored style, and a stored style is never asked about
                    // again - while a later version's catalogue still arrives
                    // without a migration and without touching anyone's edits.
                    styles = profile?.styles?.takeIf { it.isNotBlank() }
                        ?: ArtistStyles.styleFor(name).orEmpty(),
                    note = profile?.note.orEmpty(),
                    songs = list.distinctBy { it.id }.sortedBy { it.titleLower }
                )
            }.sortedBy { it.displayName.lowercase(Locale.ROOT) }

            com.elchanan.rhythm.playback.MediaItems.noteLibrary(songs)
            val albums = songs.groupBy { it.albumId }.map { (id, list) ->
                AlbumInfo(
                    albumId = id,
                    name = list.first().albumName,
                    artistName = list.first().artistName,
                    songs = list.sortedWith(compareBy({ it.trackNumber }, { it.titleLower }))
                )
            }.sortedBy { it.name.lowercase(Locale.ROOT) }

            // The library's own names are shown as they are, never translated.
            com.elchanan.rhythm.ui.theme.UiStrings.protectNames(
                buildList {
                    for (song in songs) { add(song.title); add(song.artistName); add(song.albumName) }
                    for (artist in artistInfos) add(artist.displayName)
                }
            )
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
            val itemsByPlaylist = items.groupBy { it.playlistId }
            lists.map { pl ->
                PlaylistInfo(
                    playlist = pl,
                    songs = itemsByPlaylist[pl.id].orEmpty()
                        .sortedBy { it.position }
                        .mapNotNull { byId[it.songId] }
                )
            }
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    init {
        // An open list is a picture taken when it was opened. A playlist
        // follows its playlist - a song added or removed shows at once - and
        // any list loses a song whose file is gone, rather than offering a
        // song that no longer exists until the screen is left and reopened.
        viewModelScope.launch {
            combine(playlists, repo.songs) { lists, songs -> lists to songs.mapTo(HashSet()) { it.id } }
                .collect { (lists, present) ->
                    val shown = _detail.value ?: return@collect
                    val id = shown.playlistId
                    val next = if (id != null) {
                        lists.firstOrNull { it.playlist.id == id }?.songs ?: shown.songs
                    } else {
                        shown.songs.filter { it.id in present }
                    }
                    if (next != shown.songs) _detail.value = shown.copy(songs = next)
                }
        }
    }

    private val _artistDetail = MutableStateFlow<ArtistInfo?>(null)
    val artistDetail: StateFlow<ArtistInfo?> = _artistDetail.asStateFlow()

    private val _lyrics = MutableStateFlow<LyricsEntity?>(null)
    val lyrics: StateFlow<LyricsEntity?> = _lyrics.asStateFlow()

    private val _lyricLines = MutableStateFlow<List<LyricLine>>(emptyList())
    val lyricLines: StateFlow<List<LyricLine>> = _lyricLines.asStateFlow()

    private var lyricsLoadedFor: Long = -1L

    private val _lyricsFolder = MutableStateFlow<String?>(null)
    val lyricsFolder: StateFlow<String?> = _lyricsFolder.asStateFlow()

    private val _recap = MutableStateFlow<RecapData?>(null)
    val recap: StateFlow<RecapData?> = _recap.asStateFlow()

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

    // -----------------------------------------------------------------------
    // selection
    // -----------------------------------------------------------------------

    /**
     * The songs currently ticked, wherever they were ticked from.
     *
     * Here rather than inside a screen because a selection is not a property
     * of the screen it started on. Someone who picks four songs off a shelf
     * on the home page, two more from a search and an album from the library
     * has made one selection, and the bar that acts on it has to be the same
     * bar. Every screen was keeping its own set, so each of those was a
     * separate selection that the others could not see and switching tabs
     * silently threw away.
     *
     * Ids and not songs: a song row can be rebuilt by a rescan while the
     * selection is open, and an id survives that where an object does not.
     */
    private val _selection = MutableStateFlow<Set<Long>>(emptySet())
    val selection: StateFlow<Set<Long>> = _selection.asStateFlow()

    private val _selectionScope = MutableStateFlow<List<Long>>(emptyList())

    /**
     * The list a selection was started in - the songs on the screen, the
     * folder, the album - which is what "select all" means.
     *
     * Told by the screen at the moment of the long press rather than worked
     * out here, because only the screen knows what it is showing: the same
     * song can be in a folder, an album and a search at once.
     */
    val selectionScope: StateFlow<List<Long>> = _selectionScope.asStateFlow()

    fun noteSelectionScope(ids: List<Long>) {
        _selectionScope.value = ids
    }

    /** Everything in the list the selection was started in. */
    fun selectAll() {
        _selection.value = _selection.value + _selectionScope.value
    }

    /** Ticking the last one off ends selection mode, because empty is the mode. */
    fun toggleSelect(id: Long) {
        _selection.value = _selection.value.let { if (id in it) it - id else it + id }
    }

    /**
     * A whole group at once - an album, an artist, a folder, a shelf.
     *
     * Already entirely selected means take it back out, so the same gesture
     * undoes itself rather than doing nothing the second time.
     */
    fun toggleGroup(ids: List<Long>) {
        if (ids.isEmpty()) return
        val current = _selection.value
        _selection.value =
            if (current.containsAll(ids)) current - ids.toSet() else current + ids
    }

    fun clearSelection() {
        _selection.value = emptySet()
        _selectionScope.value = emptyList()
    }

    /** The selected songs, in the order the library holds them. */
    fun selectedSongs(): List<SongEntity> {
        val picked = _selection.value
        if (picked.isEmpty()) return emptyList()
        return library.value.songs.filter { it.id in picked }
    }

    /** Whether the scan now running was asked for by someone who wants telling. */
    private var announceScan = false
    private var busyWatchdog: Job? = null

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
        // Learning follows analysis, when it is left on.
        //
        // Here rather than after a scan, because a scan finds files and
        // analysis is what turns them into the sound measurements the styles
        // are learned from - running before that would learn from whatever
        // happened to be measured already. Only on the falling edge, so a pass
        // that is still working is never interrupted.
        viewModelScope.launch {
            var wasRunning = false
            analysis.progress.collect { progress ->
                val finished = wasRunning && !progress.running
                wasRunning = progress.running
                if (finished && repo.prefs.autoLearn) learnStyles(automatic = true)
            }
        }

        // Every finished scan rebuilds the feed, whoever ran it. Dropping the
        // first value because it is the starting count and not a scan.
        viewModelScope.launch {
            repo.scans.drop(1).collect {
                busyWatchdog?.cancel()
                busyWatchdog = null
                refreshFeed()
                analysis.refreshCounts()
                _busy.value = false
                if (announceScan) {
                    _message.value = "נסרקו ${repo.songCount()} שירים"
                    announceScan = false
                }
            }
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

    /**
     * Rebuilds the library from what the device says is on it.
     *
     * Handed to a foreground service when the system will take it, because a
     * scan over a large library is minutes of work and a cached process is
     * frozen the moment the screen goes off - which used to stop the pass
     * dead. The service runs the same repository call this would have; the
     * branch below is the fallback for when a foreground service cannot be
     * started, and is what the app did before.
     */
    fun rescan(showMessage: Boolean = true) {
        if (_busy.value) return
        // The service owns the pass when it takes it. Nothing is awaited here:
        // repo.scans is watched from init and rebuilds the feed when the scan
        // lands, whether this screen is still open by then or not.
        if (LibraryWorkService.start(getApplication(), scan = true, analyze = false)) {
            _busy.value = true
            announceScan = showMessage
            // A service that is killed before it finishes would otherwise
            // leave the app looking busy for ever, and busy is what stops
            // the next scan from being attempted. The counter clears this
            // the moment a scan really lands; this only catches the case
            // where none ever does.
            busyWatchdog?.cancel()
            busyWatchdog = viewModelScope.launch {
                delay(SCAN_WATCHDOG_MS)
                _busy.value = false
                announceScan = false
            }
            return
        }
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
            // The time is only moved when the answer changes: it is when the
            // shelf started saying this, which is what the hold counts from.
            e.pickedMood?.let { picked ->
                if (picked != repo.prefs.lastMood) {
                    repo.prefs.lastMood = picked
                    repo.prefs.lastMoodAt = System.currentTimeMillis()
                }
            }
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

    /** A short confirmation from a screen, shown the way every other one is. */
    fun toast(text: String) {
        _message.value = text
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
            val list = withContext(Dispatchers.Default) { e.radio(song, 40) }
            QueueMeta.reset()
            QueueMeta.markAuto(list.drop(1).map { it.id })
            // From the song that is playing, it simply carries on.
            if (!player.continueFromCurrent(list)) {
                player.play(list, 0)
                markStarted()
            }
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
    private var deletePending: List<SongEntity> = emptyList()

    fun shareSongs(songs: List<SongEntity>) {
        val intent = FileActions.shareIntent(songs) ?: return
        val chooser = Intent.createChooser(intent, com.elchanan.rhythm.ui.theme.localized("שיתוף"))
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
        deletePending = songs
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
        val songs = deletePending
        deletePending = emptyList()
        if (!confirmed || songs.isEmpty()) return
        viewModelScope.launch {
            // What is actually gone. Android 10 asks about one file at a
            // time, and a yes there only allows the delete - it does not do
            // it - so the rest are asked for again below.
            val (gone, still) = withContext(Dispatchers.IO) {
                songs.partition { song ->
                    song.path.isEmpty() || !runCatching { java.io.File(song.path).exists() }.getOrDefault(false)
                }
            }
            // The rows have to go too, or the songs stay in the library and
            // its playlists, pointing at files that are no longer there.
            if (gone.isNotEmpty()) repo.forgetSongs(gone.map { it.id })
            engine = null
            if (still.isNotEmpty() && gone.isNotEmpty() &&
                android.os.Build.VERSION.SDK_INT == android.os.Build.VERSION_CODES.Q
            ) {
                deleteSongs(still)
                return@launch
            }
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

    /** Whether a song counts as vocal-only now: the user's word, else its name and sound. */
    fun isVocal(song: SongEntity, feature: AudioFeatureEntity?): Boolean {
        val lib = library.value
        val artistStyles = lib.artists.firstOrNull { it.key == song.artistKey }?.styles.orEmpty()
        return Vocal.isVocal(song, lib.stats[song.id], feature, artistStyles)
    }

    /** The Omer or the Three Weeks, if today is in one. */
    val season: JewishSeasons.Season? get() = JewishSeasons.at(System.currentTimeMillis())

    fun setVocal(song: SongEntity, vocal: Boolean?) {
        viewModelScope.launch {
            repo.setVocal(song.id, vocal)
            _message.value = when (vocal) {
                true -> "סומן כווקאלי — יוצג רק בספירה ובשלושת השבועות"
                false -> "סומן כלא ווקאלי"
                null -> "חזר לזיהוי האוטומטי"
            }
            refreshFeed()
        }
    }

    fun setOnlyVocalInSeason(value: Boolean) {
        prefs.onlyVocalInSeason = value
        refreshFeed()
    }

    fun setMedleyMinutes(value: Int) {
        prefs.medleyMinutes = value
        refreshFeed()
    }

    /** What the user said about songs' moods, from the live stats. */
    private fun moodMarks(): Map<Long, Map<Mood, Boolean>> = MoodMarks.of(library.value.stats)

    /**
     * The user correcting the mood reading for one song. A "no" said from
     * inside that mood's list also takes the song out of the list on screen,
     * so the correction is visible where it was made.
     */
    fun setMoodMark(song: SongEntity, mood: Mood, value: Boolean?) {
        viewModelScope.launch {
            repo.setMoodMark(song.id, mood, value)
            val shown = _detail.value
            if (value == false && shown != null && shown.gradientKey == "mood:${mood.name}") {
                _detail.value = shown.copy(songs = shown.songs.filter { it.id != song.id })
            }
            _message.value = when (value) {
                true -> "סומן כ\"${mood.label}\" — האפליקציה תלמד מזה"
                false -> "סומן כלא \"${mood.label}\" — האפליקציה תלמד מזה"
                null -> "\"${mood.label}\" חזר לזיהוי האוטומטי"
            }
            refreshFeed()
        }
    }

    /**
     * What the audio says about this song's moods, with the user's own marks
     * on this song left out - so the dialog can show what the automatic
     * reading would say next to what the user said.
     */
    suspend fun moodReading(songId: Long): Map<Mood, Boolean> {
        val features = runCatching { repo.featureMap() }.getOrDefault(emptyMap())
        val f = features[songId] ?: return emptyMap()
        val marks = moodMarks() - songId
        return withContext(Dispatchers.Default) {
            val model = MoodModel(features.values, marks)
            Mood.entries.associateWith { model.matches(it, f) }
        }
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
                ?.let { Lyrics.parseLrc(it) }
                .orEmpty()
        }
    }

    fun refreshLyrics(song: SongEntity) {
        viewModelScope.launch {
            _busy.value = true
            val found = runCatching { repo.refreshLyrics(song) }.getOrNull()
            _lyrics.value = found
            _lyricLines.value = found?.synced?.takeIf { it.isNotBlank() }
                ?.let { Lyrics.parseLrc(it) }
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
            val list = withContext(Dispatchers.Default) { e.radio(song, 60) }
            openList(
                title = "מיקס: ${song.title}",
                subtitle = "${list.size} שירים סביב ${song.artistName}",
                songs = list,
                key = "mix:seed:${song.id}"
            )
            if (andPlay && !player.continueFromCurrent(list)) {
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

    /**
     * Tags every song in a folder, subfolders included.
     *
     * The shortcut the app was missing. A library arrives as folders and the
     * folder is usually the answer for everything in it, so tagging one by one
     * was the bulk of the manual work - and the reason a library in use for
     * months still had too few labels for the learner to fit anything.
     */
    fun tagFolder(songs: List<SongEntity>, styles: List<String>, replace: Boolean) {
        viewModelScope.launch {
            val changed = repo.setStylesForSongs(songs.map { it.id }, styles, replace)
            _message.value =
                if (changed == 0) "כל השירים כבר מתויגים כך"
                else "תויגו $changed שירים"
            refreshFeed()
        }
    }

    /** Score breakdown for the "why was this picked" sheet. */
    fun explain(song: SongEntity): List<ScoreTerm> = engine?.explain(song).orEmpty()

    fun totalScore(song: SongEntity): Double = engine?.totalScore(song) ?: 0.0

    // -----------------------------------------------------------------------
    // audio analysis
    // -----------------------------------------------------------------------

    /**
     * Measures whatever has not been measured yet.
     *
     * Same reasoning as [rescan]: analysis is seconds per track, so on a real
     * library it is the one thing here that genuinely needs to keep running
     * with the screen off. Falls back to the plain pass when the system will
     * not start a foreground service.
     */
    fun startAnalysis() {
        if (!LibraryWorkService.start(getApplication(), scan = false, analyze = true)) {
            analysis.start()
        }
    }

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

    private val _mergingArtist = MutableStateFlow(false)
    val mergingArtist: StateFlow<Boolean> = _mergingArtist.asStateFlow()

    fun mergeArtists(source: ArtistInfo, target: ArtistInfo) {
        if (_mergingArtist.value || source.key == target.key) return
        _mergingArtist.value = true
        viewModelScope.launch {
            try {
                val count = repo.mergeArtist(source.key, target.displayName)
                _message.value = "אוחדו $count שירים תחת ${target.displayName}"
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _message.value = "האיחוד נכשל. אפשר לנסות שוב."
            } finally {
                _mergingArtist.value = false
            }
        }
    }

    fun saveQueueAsPlaylist(name: String) {
        val title = name.trim()
        if (title.isEmpty()) return
        // Snapshot before launching: playback and radio can change the queue.
        val ids = player.state.value.queueIds.toList()
        if (ids.isEmpty()) {
            _message.value = "התור ריק"
            return
        }
        viewModelScope.launch {
            try {
                repo.createPlaylistFromQueue(title, ids)
                _message.value = "התור נשמר כפלייליסט: $title"
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _message.value = "שמירת התור נכשלה. אפשר לנסות שוב."
            }
        }
    }

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
                val model = MoodModel(features.values, moodMarks())
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

    /**
     * Imports playlists from YouTube Music, by way of Google Takeout: the zip
     * (or zips) itself, or files from inside it, or a converter's CSV. See
     * [YouTubeMusicImport]. Every playlist that matched anything is created;
     * what did not match is counted, as with any import.
     */
    fun importYouTubeMusic(files: List<Pair<Uri, String>>) {
        viewModelScope.launch {
            _busy.value = true
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    val collector = YouTubeMusicImport.Collector()
                    for ((uri, name) in files) {
                        resolver.openInputStream(uri)?.use { stream ->
                            if (name.endsWith(".zip", ignoreCase = true)) {
                                java.util.zip.ZipInputStream(stream.buffered()).use { zip ->
                                    while (true) {
                                        val entry = zip.nextEntry ?: break
                                        if (!entry.isDirectory && collector.wants(entry.name)) {
                                            collector.add(entry.name, readCapped(zip))
                                        }
                                    }
                                }
                            } else {
                                collector.add(name, readCapped(stream))
                            }
                        }
                    }
                    YouTubeMusicImport.match(collector, library.value.songs)
                }
            }.getOrNull()

            if (outcome == null) {
                _busy.value = false
                _message.value = "לא הצלחתי לקרוא את הקבצים"
                return@launch
            }
            if (outcome.isEmpty()) {
                _busy.value = false
                _message.value = "לא נמצאו פלייליסטים — צריך את ה־ZIP מ־Google Takeout או קובץ CSV של פלייליסט"
                return@launch
            }
            val found = outcome.filter { it.songs.isNotEmpty() }
            for (list in found) {
                val id = repo.createPlaylist(list.name)
                repo.bulkAddToPlaylist(id, list.songs.map { it.id })
            }
            _busy.value = false
            val songs = found.sumOf { it.songs.size }
            val missing = outcome.sumOf { it.missing }
            _message.value = when {
                found.isEmpty() -> "אף שיר מהפלייליסטים לא נמצא בספרייה שלך"
                missing > 0 -> "יובאו ${found.size} פלייליסטים עם $songs שירים · $missing לא נמצאו"
                else -> "יובאו ${found.size} פלייליסטים עם $songs שירים"
            }
        }
    }

    /** A file's text, up to what a watch history needs; the rest of a huge one is skipped. */
    private fun readCapped(input: java.io.InputStream): String {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        val cap = YouTubeMusicImport.HISTORY_MAX_CHARS
        while (out.size() < cap) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, minOf(n, cap - out.size()))
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /**
     * Imports listening history from another player's CSV export.
     *
     * Someone arriving with years of history elsewhere starts here with every
     * model in the engine knowing nothing - and all of them need listening
     * before they say anything useful. The evidence existed; there was no way
     * to hand it over.
     *
     * Reported in full, including what did not match, because the usual
     * reason for a miss is a spelling difference the user can actually go and
     * fix, and a silent partial import looks exactly like a complete one.
     */
    fun importPlayCounts(uri: Uri) {
        viewModelScope.launch {
            _busy.value = true
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    val content = getApplication<Application>().contentResolver
                        .openInputStream(uri)?.use { stream ->
                            stream.readBytes().toString(Charsets.UTF_8)
                        } ?: return@withContext null
                    PlayCountImport.read(content, library.value.songs)
                }
            }.getOrNull()
            _busy.value = false

            if (outcome == null) {
                _message.value = "לא הצלחתי לקרוא את הקובץ"
                return@launch
            }
            if (outcome.parsed.entries.isEmpty()) {
                _message.value = "לא זוהתה עמודת שם שיר בקובץ"
                return@launch
            }
            if (outcome.matched.isEmpty()) {
                _message.value = "אף שיר מהקובץ לא נמצא בספרייה שלך"
                return@launch
            }
            val changed = repo.applyImportedPlays(outcome.matched)
            _message.value = buildString {
                append("עודכנו $changed שירים · ${outcome.totalPlays} השמעות")
                if (outcome.unmatched.isNotEmpty()) {
                    append(" · ${outcome.unmatched.size} לא נמצאו")
                }
            }
            refreshFeed()
        }
    }

    /**
     * Imports measurements made by the desktop build.
     *
     * Parsing, checksum validation and matching all finish before the analyser
     * is stopped or one database row is touched. The final list insert is one
     * Room transaction, so a killed process cannot leave half an import.
     */
    fun importAnalysis(uri: Uri) {
        viewModelScope.launch {
            _busy.value = true
            val attempt = runCatching {
                withContext(Dispatchers.IO) {
                    val bytes = getApplication<Application>().contentResolver
                        .openInputStream(uri)?.use { input ->
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(16 * 1024)
                            var total = 0
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                total += read
                                require(total <= MAX_ANALYSIS_TRANSFER_BYTES) { "file too large" }
                                output.write(buffer, 0, read)
                            }
                            output.toByteArray()
                        } ?: throw IllegalArgumentException("empty file")
                    val bundle = AnalysisTransfer.decode(bytes.toString(Charsets.UTF_8))
                    val current = repo.featureMap()
                    bundle to AnalysisTransfer.match(bundle, library.value.songs, current)
                }
            }
            val prepared = attempt.getOrNull()

            if (prepared == null) {
                _busy.value = false
                _message.value = if (attempt.exceptionOrNull() is AnalysisTransfer.NewerVersionException) {
                    "הקובץ נוצר בגרסה חדשה יותר של Rhythm. צריך לעדכן את האפליקציה בטלפון ולייבא שוב"
                } else {
                    "קובץ הניתוח פגום או חלקי"
                }
                return@launch
            }
            val (bundle, matched) = prepared
            if (matched.features.isEmpty()) {
                _busy.value = false
                _message.value = if (bundle.tracks.isEmpty()) {
                    "אין בקובץ תוצאות ניתוח"
                } else {
                    "לא נמצאה אף התאמה בטוחה לשירים שבטלפון"
                }
                return@launch
            }

            val saved = runCatching {
                analysis.stopAndWait()
                repo.putFeatures(matched.features)
                analysis.refreshCounts()
            }.isSuccess
            _busy.value = false
            if (!saved) {
                _message.value = "הייבוא נכשל ולא נשמרו תוצאות חלקיות"
                return@launch
            }

            val details = buildList {
                if (matched.unmatched > 0) add("${matched.unmatched} לא נמצאו")
                if (matched.ambiguous > 0) add("${matched.ambiguous} לא חד־משמעיים")
                if (matched.invalid > 0) add("${matched.invalid} לא תקינים")
            }
            _message.value = buildString {
                append("יובאו תוצאות ניתוח עבור ${matched.features.size} שירים")
                if (details.isNotEmpty()) append(" · ").append(details.joinToString(" · "))
            }
        }
    }

    /** Writes a complete, shareable inventory with no listening or file data. */
    fun exportLibraryCatalog(uri: Uri) {
        viewModelScope.launch {
            _busy.value = true
            val catalog = runCatching {
                withContext(Dispatchers.IO) {
                    val result = LibraryCatalogExport.create(repo.allSongsForExport())
                    getApplication<Application>().contentResolver
                        .openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use {
                            it.write(result.text)
                        } ?: throw IllegalStateException("cannot open destination")
                    result
                }
            }.getOrNull()
            _busy.value = false
            _message.value = if (catalog == null) {
                "ייצוא רשימת הספרייה נכשל — בדוק הרשאה ומקום פנוי"
            } else {
                buildString {
                    append("יוצאו ${catalog.artists} אמנים, ${catalog.albums} אלבומים")
                    append(" ו־${catalog.songs} שירים")
                    if (catalog.unnamed > 0) {
                        append(" · ${catalog.unnamed} שמות חסרים סומנו ולא הושמטו")
                    }
                }
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
        // The open list is a picture taken when it was opened, so the song
        // stayed on screen after it had left the playlist - which read as
        // "it cannot be removed". It leaves the picture too.
        val shown = _detail.value
        if (shown != null && shown.playlistId == playlistId) {
            _detail.value = shown.copy(songs = shown.songs.filter { it.id != songId })
        }
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

    /**
     * Whether the pending edit lives in the file alone - an album artist, a
     * year, a track number - so a file that cannot be written loses it, and
     * saying "kept in the app" would be untrue.
     */
    private var pendingFileOnly = false

    private val _writePermissionRequest = MutableStateFlow<IntentSender?>(null)
    val writePermissionRequest: StateFlow<IntentSender?> = _writePermissionRequest.asStateFlow()

    /** The older, broader storage permission, for devices below Android 11. */
    private val _legacyPermissionRequest = MutableStateFlow(false)
    val legacyPermissionRequest: StateFlow<Boolean> = _legacyPermissionRequest.asStateFlow()

    fun applyTagFix(proposals: List<TagFixer.Proposal>, includeUncertain: Boolean = false) {
        viewModelScope.launch {
            val changed = proposals.filter { it.changed && (it.certain || includeUncertain) }
            repo.saveOverrides(TagFixer.toOverrides(proposals, includeUncertain))
            prefs.tagTipSeen = true
            // Dealt with: the banner may come back for the next downloads.
            prefs.tagFixBannerDismissedAt = 0
            _tagFixDismissedAt.value = 0
            // The shelves hold a snapshot of the songs taken when the feed was
            // built, so without this the home screen keeps showing the old
            // titles while the player shows the corrected ones.
            refreshFeed()
            _message.value =
                if (changed.isEmpty()) "אין מה לתקן" else "עודכנו ${changed.size} שירים"
            if (changed.isNotEmpty() && prefs.writeTagsToFiles) startFileWrite(changed)
        }
    }

    /**
     * A song's tags as the listener typed them - one song, or a selection.
     *
     * Written into the file itself whatever the tag-fixing setting says:
     * editing the file is what this is for. The name, the artist and the
     * album are also kept in the app, so they show at once and survive a
     * file that cannot be written; the genre goes to the app's own genre as
     * well, which is what the engine reads. Year, track number and album
     * artist live in the file only, and the library picks them up once
     * Android has read the file again.
     */
    fun editSongTags(songIds: List<Long>, edit: TagEdit) {
        if (songIds.isEmpty() || edit.isEmpty) return
        viewModelScope.launch {
            if (edit.title != null || edit.artist != null || edit.album != null) {
                val existing = repo.overrides().associateBy { it.songId }
                repo.saveOverrides(
                    songIds.map { id ->
                        val row = existing[id] ?: TagOverrideEntity(songId = id)
                        row.copy(
                            title = edit.title?.trim() ?: row.title,
                            artistName = edit.artist?.trim() ?: row.artistName,
                            albumName = edit.album?.trim() ?: row.albumName
                        )
                    }
                )
            }
            edit.genre?.let { repo.setGenre(songIds, it.trim()) }
            refreshFeed()
            val fileOnly = edit.title == null && edit.artist == null && edit.album == null && edit.genre == null
            if (!fileOnly) {
                _message.value = if (songIds.size == 1) "הפרטים עודכנו" else "עודכנו ${songIds.size} שירים"
            }
            val songs = library.value.songsById
            startFileWriteItems(
                songIds.mapNotNull { id -> songs[id]?.let { TagFileWriter.Item(id, it.path, edit) } },
                fileOnly = fileOnly
            )
        }
    }

    // -----------------------------------------------------------------------
    // samples: a taste of each song, from its chorus, one after another
    // -----------------------------------------------------------------------

    private val _samples = MutableStateFlow<List<SongEntity>?>(null)

    /** The songs to taste, in order; null while they are being chosen. */
    val samples: StateFlow<List<SongEntity>?> = _samples.asStateFlow()

    /**
     * What to taste, and in what order.
     *
     * For finding songs in the library, so the ones never played come first,
     * each group in the order the engine likes them for this listener, with a
     * little shuffle so the same few do not open every time. Left out: what
     * was disliked, medleys and anything over six minutes - a taste of the
     * fourth tune of a set is no taste of it - lectures, and vocal-only songs
     * outside their season, as everywhere else.
     */
    fun buildSamples() {
        viewModelScope.launch {
            val built = samplesFor(library.value.songs)
            // The ones already tasted this time go to the end: coming back
            // to the tastes starts on something new, not on what was just
            // scrolled past.
            val (fresh, tasted) = built.partition { it.id !in tastedIds }
            _samples.value = fresh + tasted
        }
    }

    /**
     * The order is chosen once per library and kept, so the choruses found
     * ahead of time are the ones the tastes then open with.
     */
    private var samplesCache: Pair<Long, List<SongEntity>>? = null
    private val tastedIds = HashSet<Long>()

    /**
     * Which library the order was chosen for: the songs in it, not the list
     * object, which is made again on every like and play.
     */
    private fun librarySignature(songs: List<SongEntity>): Long =
        songs.fold(songs.size.toLong()) { acc, s -> acc * 31 + s.id }

    fun noteTasted(songId: Long) {
        tastedIds.add(songId)
    }

    private suspend fun samplesFor(songs: List<SongEntity>): List<SongEntity> {
        val signature = librarySignature(songs)
        samplesCache?.let { (forLibrary, list) -> if (forLibrary == signature) return list }
        val list = chooseSamples()
        samplesCache = signature to list
        return list
    }

    /**
     * A few moments after the app opens, the first tastes' choruses are found
     * in the background, on a thread that gives way to everything else, so
     * the tastes open straight on a chorus. Once per run of the app.
     */
    private var tastesWarmed = false

    fun warmTastes() {
        if (tastesWarmed) return
        tastesWarmed = true
        viewModelScope.launch {
            delay(TASTES_WARM_DELAY_MS)
            val songs = library.value.songs
            if (songs.isEmpty()) return@launch
            val first = samplesFor(songs).take(TASTES_AHEAD)
            HookFinder.prefetch(getApplication(), first)
        }
    }

    private suspend fun chooseSamples(): List<SongEntity> {
        return run {
            val lib = library.value
            val stats = lib.stats
            val features = runCatching { repo.featureMap() }.getOrDefault(emptyMap())
            val e = engine ?: runCatching { repo.buildRecommender() }.getOrNull()?.also { engine = it }
            val inSeason = season != null
            withContext(Dispatchers.Default) {
                val pool = lib.songs.filter { song ->
                    val feature = features[song.id]
                    song.durationMs in SAMPLE_MIN_MS..SAMPLE_MAX_MS &&
                        !isMedley(song.title) &&
                        (stats[song.id]?.liked ?: 0) != -1 &&
                        (inSeason || !isVocal(song, feature)) &&
                        !Spoken.isSpoken(
                            song, feature,
                            feature?.tags?.let { AudioTags.pick(it, AudioTags.SPEECH_INDICES) },
                            stats[song.id]?.spoken ?: -1
                        )
                }
                val scores = pool.associate { it.id to (e?.totalScore(it) ?: 0.0) }
                val spread = scores.values.let { v ->
                    val mean = v.average().takeIf { !it.isNaN() } ?: 0.0
                    kotlin.math.sqrt(v.sumOf { (it - mean) * (it - mean) } / maxOf(1, v.size))
                }
                val random = kotlin.random.Random(System.nanoTime())
                val jittered = pool.associate { it.id to (scores.getValue(it.id) + random.nextDouble() * 0.5 * spread) }
                val (unheard, heard) = pool.partition { (stats[it.id]?.playCount ?: 0) == 0 }
                (unheard.sortedByDescending { jittered.getValue(it.id) } +
                    heard.sortedByDescending { jittered.getValue(it.id) }).take(SAMPLE_LIMIT)
            }
        }
    }

    /**
     * How often the taste really started on the chorus, from the listener's
     * "not the chorus": tastes heard, and how many of them were marked.
     */
    private val _hookMisses = MutableStateFlow(prefs.hookTastes to prefs.hookMisses)
    val hookAccuracy: StateFlow<Pair<Int, Int>> = _hookMisses.asStateFlow()

    fun noteTasteHeard() {
        prefs.hookTastes = prefs.hookTastes + 1
        _hookMisses.value = prefs.hookTastes to prefs.hookMisses
    }

    fun noteNotTheChorus() {
        prefs.hookMisses = prefs.hookMisses + 1
        _hookMisses.value = prefs.hookTastes to prefs.hookMisses
    }

    // -----------------------------------------------------------------------
    // Hebrew spellings for names written in English letters
    // -----------------------------------------------------------------------

    private val _hebrewSuggestions = MutableStateFlow<List<HebrewSpelling.Suggestion>?>(null)

    /** Null while they are being worked out. */
    val hebrewSuggestions: StateFlow<List<HebrewSpelling.Suggestion>?> = _hebrewSuggestions.asStateFlow()

    /** Which way the offers go: Latin names to Hebrew, or - for English readers - Hebrew names to Latin. */
    private var spellingToLatin = false

    fun buildHebrewSuggestions(toLatin: Boolean = spellingToLatin) {
        spellingToLatin = toLatin
        _hebrewSuggestions.value = null
        viewModelScope.launch {
            val songs = library.value.songs
            _hebrewSuggestions.value = withContext(Dispatchers.Default) {
                if (toLatin) HebrewSpelling.suggestLatin(songs) else HebrewSpelling.suggest(songs)
            }
        }
    }

    /**
     * The spellings the listener accepted, as the name the app shows - the
     * same corrections the tag fixer saves, so they survive a rescan and an
     * artist's ratings follow the new name. Into the files too, when the
     * settings say corrections go there.
     */
    fun applyHebrewNames(chosen: List<HebrewSpelling.Suggestion>) {
        if (chosen.isEmpty()) return
        viewModelScope.launch {
            val existing = repo.overrides().associateBy { it.songId }.toMutableMap()
            val edits = LinkedHashMap<Long, TagEdit>()
            for (s in chosen) {
                for (id in s.songIds) {
                    val row = existing[id] ?: TagOverrideEntity(songId = id)
                    val edit = edits[id] ?: TagEdit()
                    when (s.field) {
                        HebrewSpelling.Field.ARTIST -> {
                            existing[id] = row.copy(artistName = s.proposed)
                            edits[id] = edit.copy(artist = s.proposed)
                        }
                        HebrewSpelling.Field.TITLE -> {
                            existing[id] = row.copy(title = s.proposed)
                            edits[id] = edit.copy(title = s.proposed)
                        }
                    }
                }
            }
            repo.saveOverrides(edits.keys.mapNotNull { existing[it] })
            refreshFeed()
            _message.value = "עודכנו ${edits.size} שירים"
            buildHebrewSuggestions()
            if (prefs.writeTagsToFiles) {
                val songs = library.value.songsById
                startFileWriteItems(edits.mapNotNull { (id, edit) ->
                    songs[id]?.let { TagFileWriter.Item(id, it.path, edit) }
                })
            }
        }
    }

    /** What a song's own file says, for the tag editor to start from. */
    suspend fun readFileTags(songId: Long): TagEdit? = tagFiles.read(songId)

    private fun startFileWrite(changed: List<TagFixer.Proposal>) {
        val songs = library.value.songsById
        startFileWriteItems(changed.mapNotNull { p ->
            songs[p.songId]?.let {
                TagFileWriter.Item(
                    p.songId, it.path,
                    TagEdit(title = p.newTitle, artist = p.newArtist, album = if (p.albumChanged) p.newAlbum else null)
                )
            }
        })
    }

    private fun startFileWriteItems(items: List<TagFileWriter.Item>, fileOnly: Boolean = false) {
        pendingWrites = items
        pendingFileOnly = fileOnly
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
            _message.value = if (pendingFileOnly) {
                "הקבצים לא שונו, ולכן השינוי לא נשמר"
            } else {
                "התיקון נשמר באפליקציה. הקבצים לא שונו."
            }
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
            // Year, track and genre come from Android's own index, which has
            // now read the new tags; the library reads them from there.
            if (outcome.written > 0) {
                repo.rescan()
                refreshFeed()
            }
            _message.value = when {
                outcome.written == 0 && outcome.failed == 0 && outcome.notMp3 > 0 ->
                    if (pendingFileOnly) "הקובץ אינו MP3, ולכן השינוי לא נשמר"
                    else "נשמר באפליקציה. הקובץ אינו MP3, ולכן הוא עצמו לא שונה."
                outcome.written == 0 ->
                    if (pendingFileOnly) "לא הצלחתי לכתוב לקבצים, ולכן השינוי לא נשמר"
                    else "לא הצלחתי לכתוב לקבצים. התיקון נשמר באפליקציה."
                outcome.notMp3 > 0 -> "נכתבו ${outcome.written} קבצים · ${outcome.notMp3} אינם MP3 ונשמרו רק באפליקציה"
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

    /** How many songs the tag fixer has a sure correction for; the home banner's count. */
    private val _tagFixPending = MutableStateFlow(0)
    val tagFixPending: StateFlow<Int> = _tagFixPending.asStateFlow()

    private val _tagFixDismissedAt = MutableStateFlow(repo.prefs.tagFixBannerDismissedAt)
    val tagFixDismissedAt: StateFlow<Int> = _tagFixDismissedAt.asStateFlow()

    /** Counted off the main thread: proposing is a pass over the whole library. */
    fun refreshTagFixPending() {
        viewModelScope.launch {
            val songs = library.value.songs
            if (songs.isEmpty()) return@launch
            _tagFixPending.value = withContext(Dispatchers.Default) {
                TagFixer.propose(songs, dropForeign = prefs.tagStripForeign).count { it.changed && it.certain }
            }
        }
    }

    fun dismissTagFixBanner() {
        prefs.tagFixBannerDismissedAt = _tagFixPending.value
        _tagFixDismissedAt.value = _tagFixPending.value
    }

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
    private val _learnResult = MutableStateFlow<LearnResult?>(null)
    val learnResult: StateFlow<LearnResult?> = _learnResult.asStateFlow()
    private val _learning = MutableStateFlow(false)
    val learning: StateFlow<Boolean> = _learning.asStateFlow()
    private val _soundCheck = MutableStateFlow<String?>(null)

    /** The last sound print check, in the words the settings screen shows. */
    val soundCheck: StateFlow<String?> = _soundCheck.asStateFlow()

    /**
     * Measures whether songs that sound alike share a style, by the current
     * sound features and by the sound print, on this library. Changes
     * nothing: it is how a change to the sound model gets judged by results
     * rather than by argument.
     */
    fun runSoundCheck() {
        viewModelScope.launch {
            _soundCheck.value = "בודק…"
            val lib = library.value
            val features = repo.featureMap()
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    SoundCheck.measure(
                        lib.songs,
                        features,
                        lib.artists.associate { it.key to it.styles }
                    )
                }.getOrNull()
            }
            _soundCheck.value = SoundCheck.describe(result)
        }
    }

    private val _musicModelReport = MutableStateFlow<String?>(null)
    val musicModelReport: StateFlow<String?> = _musicModelReport.asStateFlow()
    private val _musicModelChecking = MutableStateFlow(false)
    val musicModelChecking: StateFlow<Boolean> = _musicModelChecking.asStateFlow()

    /** Compare the music model against the same labelled songs without writing tags. */
    fun runMusicModelEvaluation() {
        if (_musicModelChecking.value) return
        _musicModelChecking.value = true
        _musicModelReport.value = "בודק את תרומת המודל המוזיקלי…"
        viewModelScope.launch {
            try {
                val lib = library.value
                val features = repo.featureMap()
                val manualArtistStyles = repo.artists.first()
                    .filter { it.styles.isNotBlank() }
                    .associate { it.artistKey to it.styles }
                val report = withContext(Dispatchers.Default) {
                    MusicModelEvaluation.measure(
                        lib.songs, lib.stats,
                        manualArtistStyles,
                        features
                    )
                }
                _musicModelReport.value = MusicModelEvaluation.describe(report, prefs.language)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _musicModelReport.value = "בדיקת המודל לא הושלמה. נסה שוב."
            } finally {
                _musicModelChecking.value = false
            }
        }
    }

    private val _calibration = MutableStateFlow<SignalCalibration.Report?>(null)

    /** The last report card, or null before one was asked for. */
    val calibration: StateFlow<SignalCalibration.Report?> = _calibration.asStateFlow()
    private val _calibrating = MutableStateFlow(false)
    val calibrating: StateFlow<Boolean> = _calibrating.asStateFlow()

    private val _moodReport = MutableStateFlow<List<MoodModel.MoodReport>?>(null)

    /** How the mood reading does on the songs the user corrected, per mood. */
    val moodReport: StateFlow<List<MoodModel.MoodReport>?> = _moodReport.asStateFlow()

    /** Whether weights learned from the listening are in force. */
    val usingLearnedWeights: Boolean get() = SignalWeights.decode(prefs.learnedWeights) != null

    /**
     * Scores every song the listening has answered for without its own
     * history, and learns this listener's own signal weights from it. Measured
     * with the defaults, so the comparison is always against the same baseline.
     * Changes nothing until [applyLearnedWeights].
     */
    fun runCalibration() {
        viewModelScope.launch {
            _calibrating.value = true
            val report = runCatching {
                val e = repo.buildRecommender()
                withContext(Dispatchers.Default) {
                    val rows = e.calibrationRows()
                    SignalCalibration.run(rows)
                }
            }.getOrNull()
            _moodReport.value = runCatching {
                val features = repo.featureMap()
                withContext(Dispatchers.Default) {
                    MoodModel(features.values, moodMarks()).report()
                }
            }.getOrNull()
            _calibrating.value = false
            _calibration.value = report
            if (report == null) _message.value = "הבדיקה נכשלה"
        }
    }

    fun applyLearnedWeights() {
        // Only weights that beat the defaults on held-out artists.
        val report = _calibration.value?.takeIf { it.accepted } ?: return
        val weights = report.weights ?: return
        prefs.learnedWeights = weights.encode()
        _message.value = "המשקלים האישיים הופעלו"
        refreshFeed()
    }

    fun resetLearnedWeights() {
        prefs.learnedWeights = ""
        _message.value = "חזרה למשקלים הרגילים"
        refreshFeed()
    }

    private val _learningReport = MutableStateFlow<String?>(null)
    val learningReport: StateFlow<String?> = _learningReport.asStateFlow()

    /**
     * Learns the user's own style words from their own library.
     *
     * The whole of the reasoning - what counts as evidence, whether the model
     * generalises well enough to trust, and what to say when it does not - is
     * [StyleLearning] over in :engine, which the desktop build runs too. This
     * end supplies the rows and stores what comes back.
     */
    /**
     * @param automatic true when nothing was pressed - learning ran itself
     *   after analysis. It then speaks only when it has something to say: a
     *   toast reading "no new tags confident enough" every time a few files
     *   are analysed is noise about a decision that was made correctly.
     */
    fun learnStyles(automatic: Boolean = false) {
        if (_busy.value || _learning.value) return
        _busy.value = true
        _learning.value = true
        _learnResult.value = null
        _learningReport.value = "הלמידה מתבצעת — בודק על אמנים שלא השתתפו באימון…"
        viewModelScope.launch {
            var saved = 0
            try {
                val lib = library.value
                val features = repo.featureMap()
                val outcome = withContext(Dispatchers.Default) {
                    StyleLearning.learn(
                        songs = lib.songs,
                        stats = lib.stats,
                        stylesByArtist = lib.artists.associate { it.key to it.styles },
                        features = features
                    )
                }
                for ((songId, styles) in outcome.predictions) {
                    repo.setSongStyles(songId, styles, auto = true)
                    saved++
                }
                _learnResult.value = outcome
                _learningReport.value = StyleLearning.report(outcome)
                // The report is kept either way; only the toast is withheld.
                if (!automatic || saved > 0) {
                    _message.value = StyleLearning.message(outcome)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                _learningReport.value = "הלמידה הופסקה. נשמרו עד כה תגיות ל-$saved שירים."
                throw cancelled
            } catch (_: Exception) {
                _learningReport.value = "הלמידה לא הושלמה. נשמרו תגיות ל-$saved שירים לפני השגיאה. נסה שוב."
                if (!automatic) _message.value = _learningReport.value
            } finally {
                _learning.value = false
                _busy.value = false
                if (saved > 0) refreshFeed()
            }
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

    /**
     * A folder asked for from outside the folder view - the artist line in
     * the player - for the folder view to open on, once it is on screen.
     */
    val folderRequest = MutableStateFlow<String?>(null)

    fun openFolder(path: String) {
        if (path.isNotBlank()) folderRequest.value = path
    }

    /** The player asked to open on its queue - the home screen's queue button. */
    val queueRequest = MutableStateFlow(false)

    fun openQueue() {
        queueRequest.value = true
    }

    fun requestHomeTop() {
        _homeTopSignal.value = _homeTopSignal.value + 1
    }

    fun openMood(mood: Mood, onReady: () -> Unit) {
        // The screen opens at once and fills when the list is ready. Working
        // it out means reading every song's analysis from the database, which
        // on a large library takes seconds - and the chip used to wait for all
        // of it before anything happened, so it felt as if the tap was lost.
        val key = "mood:${mood.name}"
        _detail.value = DetailList(mood.label, mood.subtitle, emptyList(), key, loading = true)
        onReady()
        // Only the list this call opened: if the listener has gone back and
        // opened something else meanwhile, the late answer must not replace it.
        fun stillOpen() = _detail.value?.let { it.gradientKey == key && it.loading } == true
        viewModelScope.launch {
            // From the engine the feed was built with, when there is one: it
            // already holds the analysis and a mood model over it, so the list
            // is there at once. Reading every song's analysis back from the
            // database took seconds on a large library, on every tap.
            val snapshot = engine
            if (snapshot != null) {
                val fromEngine = withContext(Dispatchers.Default) { snapshot.strongestIn(mood) }
                if (fromEngine.isEmpty()) {
                    val why = if (!snapshot.hasAnalysis) {
                        "השירים עדיין לא נותחו. אפשר להתחיל ניתוח בהגדרות."
                    } else {
                        "אין שירים שמתאימים ל\"${mood.label}\" בספרייה הזאת"
                    }
                    if (stillOpen()) _detail.value = DetailList(mood.label, why, emptyList(), key)
                    return@launch
                }
                val ordered = withContext(Dispatchers.Default) {
                    snapshot.sequence(fromEngine.first(), fromEngine.drop(1).take(80))
                }
                if (stillOpen()) openList(mood.label, mood.subtitle, ordered, key)
                return@launch
            }
            // No feed built yet - the first moments after launch. Read from the
            // database rather than from featuresById. That flow is only alive
            // while a screen is subscribed to it, and no screen on the home tab
            // is - so tapping a mood chip there found an empty map and reported
            // that nothing had been analysed, however long the analysis had
            // been finished.
            val features = runCatching { repo.featureMap() }.getOrDefault(emptyMap())
            val list = withContext(Dispatchers.Default) {
                // Strongest first. Disliked songs are dropped before the sort
                // rather than after, so a thumbed down track cannot take one
                // of the places the list is later trimmed to.
                // Vocal-only songs follow the same season as the feed.
                val inSeason = season != null
                val stats = library.value.stats
                Mood.strongest(
                    library.value.songs
                        .filter { (stats[it.id]?.liked ?: 0) != -1 }
                        .filter { inSeason || !isVocal(it, features[it.id]) }
                        // Talking is not a mood. Shiurim came into these lists
                        // the same way they came into the mixes, and a calm
                        // list is exactly where a quiet lecture lands.
                        .filter { song ->
                            val feature = features[song.id]
                            val tags = feature?.tags?.let { AudioTags.pick(it, AudioTags.SPEECH_INDICES) }
                            !Spoken.isSpoken(song, feature, tags, stats[song.id]?.spoken ?: -1)
                        },
                    features,
                    mood,
                    moodMarks()
                )
            }
            if (list.isEmpty()) {
                val why = if (features.isEmpty()) {
                    "השירים עדיין לא נותחו. אפשר להתחיל ניתוח בהגדרות."
                } else {
                    "אין שירים שמתאימים ל\"${mood.label}\" בספרייה הזאת"
                }
                if (stillOpen()) _detail.value = DetailList(mood.label, why, emptyList(), key)
                return@launch
            }
            val currentEngine = engine
            val ordered = withContext(Dispatchers.Default) {
                currentEngine?.let { e ->
                    // The head is the strongest example of the mood, not the
                    // highest scoring song that happens to match it. Asking
                    // for קצבי and getting the quietest track that cleared the
                    // bar is what this list used to do, because the taste
                    // score knows nothing about the chip that was pressed.
                    //
                    // And the eighty are the eighty strongest rather than
                    // whichever eighty the library happened to store first.
                    // The sequencer still orders them, so the flow is intact;
                    // it is only the selection that stops being arbitrary.
                    val head = list.first()
                    e.sequence(head, list.drop(1).take(80))
                } ?: list
            }
            if (stillOpen()) openList(mood.label, mood.subtitle, ordered, key)
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

    fun bulkPlayNext(songs: List<SongEntity>) {
        if (songs.isEmpty()) return
        player.playNext(songs)
        QueueMeta.markManual(songs.map { it.id })
        _message.value = if (songs.size == 1) "יתנגן הבא: ${songs[0].title}"
        else "${songs.size} שירים יתנגנו הבא"
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

    /**
     * The sliders on the algorithm screen back where a fresh install has them.
     *
     * They are easy to nudge with a thumb on the way to scrolling past, and
     * nothing on the screen said where they started. The defaults are the
     * engine's own, so there is one place that says what they are.
     */
    fun resetTuning() {
        val defaults = com.elchanan.rhythm.engine.EngineTuning()
        repo.prefs.minPlaySeconds = com.elchanan.rhythm.engine.Listening.DEFAULT_MINIMUM_SEC
        updateTuning(
            discovery = defaults.discovery,
            artistWeight = defaults.artistWeight,
            styleWeight = defaults.styleWeight,
            repeatGuard = defaults.repeatGuard,
            acousticWeight = defaults.acousticWeight
        )
        _message.value = "הכוונונים הוחזרו לברירת המחדל"
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

    /** Songs carrying a tag the app guessed rather than one the user typed. */
    fun guessedSongs(): List<SongEntity> {
        val lib = library.value
        return lib.songs.filter { song ->
            val own = lib.stats[song.id]
            own?.stylesAuto == 1 && own.styles.isNotBlank()
        }
    }

    /**
     * Opens what learning wrote, so it can be read and corrected.
     *
     * The report could say how many songs it had tagged and nothing about
     * which, which left the one question anyone actually has - "is it right?"
     * - with no way to answer it short of opening every song in the library
     * one at a time. A guess nobody can find is a guess nobody can check.
     *
     * Correcting one from here is permanent: a typed tag is never overwritten
     * by a later run, and it becomes a training example for the next one.
     */
    fun openGuessed() {
        val songs = guessedSongs()
        openList(
            "מה האפליקציה ניחשה",
            "${songs.size} שירים · פתח שיר כדי לאשר או לתקן",
            songs,
            "guessed"
        )
    }

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

/** A taste is only for songs between these lengths: long files are medleys and sets. */
private const val SAMPLE_MIN_MS = 45_000L
private const val SAMPLE_MAX_MS = 6 * 60_000L

/** Enough to scroll through for a long while; more is chosen again next time. */
private const val SAMPLE_LIMIT = 300

/** How long after the app opens the first tastes are prepared - after the opening, not during it. */
private const val TASTES_WARM_DELAY_MS = 12_000L

/** How many choruses are found ahead: at the start, and past the one being tasted. */
internal const val TASTES_AHEAD = 10
