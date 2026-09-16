package com.elchanan.rhythm.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.elchanan.rhythm.RhythmApp
import com.elchanan.rhythm.data.MediaScanner
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
import com.elchanan.rhythm.engine.TasteReport
import com.elchanan.rhythm.playback.PlayerConnection
import com.elchanan.rhythm.playback.QueueMeta
import com.elchanan.rhythm.playback.SleepTimer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

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
        combine(repo.songs, repo.stats, repo.artists) { songs, stats, artists ->
            val artistMap = artists.associateBy { it.artistKey }
            val byArtist = songs.groupBy { it.artistKey }
            val artistInfos = byArtist.map { (key, list) ->
                val profile = artistMap[key]
                ArtistInfo(
                    key = key,
                    displayName = profile?.displayName?.takeIf { it.isNotBlank() }
                        ?: MediaScanner.primaryArtist(list.first().artistName),
                    rating = profile?.rating ?: 0,
                    styles = profile?.styles.orEmpty(),
                    note = profile?.note.orEmpty(),
                    songs = list.sortedBy { it.titleLower }
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

    init {
        player.connect()
        _lyricsFolder.value = repo.prefs.lyricsFolderUri
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
            _feed.value = built.first
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

    private fun doSearch(q: String) {
        if (q.isBlank()) {
            _searchResults.value = emptyList()
            return
        }
        val e = engine
        _searchResults.value = if (e != null) {
            e.search(q)
        } else {
            val lower = q.lowercase(Locale.ROOT)
            library.value.songs.filter {
                it.titleLower.contains(lower) ||
                    it.artistName.lowercase(Locale.ROOT).contains(lower) ||
                    it.albumName.lowercase(Locale.ROOT).contains(lower)
            }.take(60)
        }
    }

    // -----------------------------------------------------------------------
    // playback
    // -----------------------------------------------------------------------

    fun playList(songs: List<SongEntity>, index: Int = 0, source: String? = null) {
        if (songs.isEmpty()) return
        QueueMeta.reset()
        QueueMeta.setSource(source ?: _detail.value?.title)
        player.play(songs, index)
    }

    fun shuffleList(songs: List<SongEntity>) {
        if (songs.isEmpty()) return
        player.playShuffled(songs)
    }

    /** "Start radio": one seed song plus an endless, ranked continuation. */
    fun startRadio(song: SongEntity) {
        viewModelScope.launch {
            val e = engine ?: repo.buildRecommender().also { engine = it }
            val list = e.radio(song, 40)
            QueueMeta.reset()
            QueueMeta.markAuto(list.drop(1).map { it.id })
            player.play(list, 0)
            _message.value = "רדיו: ${song.title}"
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
            if (andPlay) player.play(list, 0)
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

    fun buildTagProposals() {
        _tagProposals.value = TagFixer.propose(library.value.songs)
    }

    fun applyTagFix(proposals: List<TagFixer.Proposal>) {
        viewModelScope.launch {
            val changed = proposals.count { it.changed }
            repo.saveOverrides(TagFixer.toOverrides(proposals))
            prefs.tagTipSeen = true
            _message.value = if (changed == 0) "אין מה לתקן" else "עודכנו $changed שירים"
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
            _message.value = "התגית עודכנה"
        }
    }

    fun resetTagFix() {
        viewModelScope.launch {
            repo.clearOverrides()
            _tagProposals.value = emptyList()
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

    fun requestHomeTop() {
        _homeTopSignal.value = _homeTopSignal.value + 1
    }

    fun openMood(mood: Mood, onReady: () -> Unit) {
        val features = featuresById.value
        val list = Mood.filter(library.value.songs, features, mood)
            .filter { (library.value.stats[it.id]?.liked ?: 0) != -1 }
        if (list.isEmpty()) {
            _message.value = "אין מספיק שירים מנותחים לקטגוריה הזאת"
            return
        }
        val ordered = engine?.let { e ->
            val head = list.maxByOrNull { e.totalScore(it) } ?: list.first()
            e.sequence(head, list.filter { it.id != head.id }.take(80))
        } ?: list
        openList(mood.label, mood.subtitle, ordered, "mood:${mood.name}")
        onReady()
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
