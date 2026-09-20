package com.elchanan.rhythm.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.automirrored.filled.Subject
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.elchanan.rhythm.data.db.ArtistEntity
import com.elchanan.rhythm.data.db.AudioFeatureEntity
import com.elchanan.rhythm.data.db.BookmarkEntity
import com.elchanan.rhythm.data.db.TagOverrideEntity
import com.elchanan.rhythm.data.TagFixer
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.data.db.SongStatsEntity
import com.elchanan.rhythm.desktop.audio.Analyzer
import com.elchanan.rhythm.desktop.audio.AudioPlayer
import com.elchanan.rhythm.desktop.audio.Equalizer
import com.elchanan.rhythm.desktop.data.Store
import com.elchanan.rhythm.engine.FeedSection
import com.elchanan.rhythm.engine.EngineTuning
import com.elchanan.rhythm.engine.Features
import com.elchanan.rhythm.engine.Mood
import com.elchanan.rhythm.engine.Names
import com.elchanan.rhythm.engine.Recap
import com.elchanan.rhythm.engine.RecapData
import com.elchanan.rhythm.engine.Recommender
import com.elchanan.rhythm.engine.Versions
import com.elchanan.rhythm.engine.SectionKind
import com.elchanan.rhythm.engine.Styles
import com.elchanan.rhythm.ui.theme.AppBackground
import com.elchanan.rhythm.ui.theme.RhythmTheme
import com.elchanan.rhythm.ui.theme.Accent
import com.elchanan.rhythm.ui.theme.Surface1
import com.elchanan.rhythm.ui.theme.TextSecondary
import com.elchanan.rhythm.ui.theme.gradientFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser
import javax.swing.UIManager

/**
 * The Windows build's entry point.
 *
 * The shelves on the home tab are built by the same [com.elchanan.rhythm.engine.Recommender]
 * the phone runs - the same compiled classes out of :engine, not a copy. This
 * file decides what a shelf looks like and nothing about what goes in one.
 */
fun main() = application {
    // Swing's own look, for the folder chooser. It is the one dialog this app
    // borrows rather than draws, because Windows users know their own file
    // picker and a hand-drawn one would only be worse.
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }

    Window(
        onCloseRequest = ::exitApplication,
        title = "Rhythm",
        state = rememberWindowState(width = 1100.dp, height = 760.dp)
    ) {
        // The app is Hebrew. Right to left is the default here exactly as it
        // is on the phone, not a setting.
        RhythmTheme {
            // The ground is a gradient, not a fill. A single flat colour
            // behind everything reads as an absence - the eye has nothing to
            // place the content against - and anything painted over it,
            // including a Surface's own container colour, hides it, which is
            // why this is a Box and not a Surface.
            Box(modifier = Modifier.fillMaxSize().background(AppBackground)) {
                RhythmApp()
            }
        }
    }
}

@Composable
private fun RhythmApp() {
    val player = remember { AudioPlayer() }
    val store = remember { Store.open() }
    val scope = rememberCoroutineScope()
    val state by player.state.collectAsState()

    var songs by remember { mutableStateOf<List<SongEntity>>(emptyList()) }
    var stats by remember { mutableStateOf<Map<Long, SongStatsEntity>>(emptyMap()) }
    var artists by remember { mutableStateOf<List<ArtistEntity>>(emptyList()) }
    // The songs grouped the way the screens ask for them - by album, by
    // artist, by folder, by list. Derived on every reload rather than stored,
    // because anything stored can disagree with the songs table and on a
    // rescan it would.
    var library by remember { mutableStateOf(LibraryModel()) }
    var feed by remember { mutableStateOf<List<FeedSection>>(emptyList()) }
    // Held rather than read from the database where they are used: those reads
    // would sit in the layout, and the layout is rebuilt several times a
    // second while a song plays.
    var folders by remember { mutableStateOf<List<File>>(emptyList()) }
    var seed by remember { mutableStateOf(1L) }

    // What is playing is a queue, not a position in the library. A shelf, a
    // mix and the library are all just lists, and what follows the current
    // song is whatever the list it came from says follows it.
    var queue by remember { mutableStateOf<List<SongEntity>>(emptyList()) }
    var queueIndex by remember { mutableStateOf(-1) }

    var tab by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var analysing by remember { mutableStateOf(false) }
    var features by remember { mutableStateOf<Map<Long, AudioFeatureEntity>>(emptyMap()) }
    var volume by remember { mutableStateOf(1f) }
    var query by remember { mutableStateOf("") }
    // The scored library, held so a feed and a search are two questions to one
    // engine rather than two engines.
    var engine by remember { mutableStateOf<Recommender?>(null) }
    var tuning by remember { mutableStateOf(EngineTuning()) }
    var showPlayer by remember { mutableStateOf(false) }
    // A stack and not a single screen, because an artist page opens an album
    // and going back from that album has to land on the artist rather than on
    // the tab the artist was reached from.
    var stack by remember { mutableStateOf<List<Route>>(emptyList()) }
    // The song the options dialog is open on, if any. Held here rather than
    // inside each screen so every list in the app opens the same one.
    var options by remember { mutableStateOf<SongEntity?>(null) }
    val prefs = remember(store) { Prefs(store) }
    var welcomeDone by remember { mutableStateOf(prefs.welcomeSeen) }
    var recap by remember { mutableStateOf<RecapData?>(null) }
    var proposals by remember { mutableStateOf<List<TagFixer.Proposal>>(emptyList()) }
    var bookmarks by remember { mutableStateOf<List<BookmarkEntity>>(emptyList()) }
    var resumePoints by remember { mutableStateOf<Map<Long, Long>>(emptyMap()) }
    var bookmarksOpen by remember { mutableStateOf(false) }
    var sleepOpen by remember { mutableStateOf(false) }
    // Redrawn once a second while the player is on screen, which is also what
    // keeps the sleep timer's remaining time honest in the dialog.
    var tick by remember { mutableStateOf(0) }

    suspend fun reload() {
        val loaded = withContext(Dispatchers.IO) {
            val s = store.songs()
            val st = store.stats()
            val ar = store.artists()
            val sd = store.feedSeed
            val ft = store.features()
            val tn = store.tuning
            val eng = if (s.isEmpty()) {
                null
            } else {
                Feed.engine(
                    filterLibrary(applyOverrides(s, store.overrides()), st, prefs),
                    st, ar, ft, sd, tn
                )
            }
            // The corrections are applied to the rows on the way out, so
            // every screen, the engine and the player all see the repaired
            // names without any of them knowing a repair happened.
            val fixed = filterLibrary(applyOverrides(s, store.overrides()), st, prefs)
            val model = LibraryModel.build(fixed, ar, store.playlists(), store.playlistItems())
            Loaded(
                fixed, st, ar, model, store.folders, sd,
                eng?.buildFeed().orEmpty(), ft, eng, tn,
                store.bookmarks(), store.positions()
            )
        }
        songs = loaded.songs
        stats = loaded.stats
        artists = loaded.artists
        library = loaded.library
        folders = loaded.folders
        seed = loaded.seed
        feed = loaded.feed
        features = loaded.analysed
        engine = loaded.engine
        tuning = loaded.tuning
        bookmarks = loaded.bookmarks
        resumePoints = loaded.positions
        status = if (loaded.songs.isEmpty()) {
            "בחר תיקיית מוזיקה"
        } else {
            "${loaded.songs.size} שירים · ${loaded.songs.map { it.artistKey }.distinct().size} אמנים"
        }
    }

    // The library is on disk from the last run, so it is on screen before
    // anything is scanned.
    LaunchedEffect(Unit) {
        reload()
        volume = prefs.volume / 100f
        player.setVolume(volume)
        player.equalizer.enabled = prefs.eqEnabled
        prefs.eqBands.forEachIndexed { band, gain ->
            player.equalizer.setGain(band, gain.toFloat())
        }
    }

    /**
     * Records what happened to the song being left, then starts another.
     *
     * The record happens on leaving rather than on ending, because most songs
     * do not end - they are skipped, and heard out against moved on from is
     * the distinction the recommender's skip rate is built on.
     */
    fun play(list: List<SongEntity>, index: Int, previousCompleted: Boolean = false) {
        val leaving = queue.getOrNull(queueIndex)
        if (leaving != null) {
            val heard = player.state.value.positionMs
            val leftAt = player.state.value.positionMs
            scope.launch {
                stats = withContext(Dispatchers.IO) {
                    store.notePlay(leaving.id, heard, previousCompleted)
                    // Only for the long ones. A song paused in the middle
                    // should start again from the top next time - resuming a
                    // four minute track two minutes in is not a convenience,
                    // it is half a song nobody asked to skip. The store drops
                    // the positions too near either end on top of this.
                    if (leaving.durationMs >= LONG_FORM_MS) {
                        store.setPosition(leaving.id, leftAt, leaving.durationMs)
                    }
                    store.stats()
                }
                resumePoints = withContext(Dispatchers.IO) { store.positions() }
            }
        }
        if (index !in list.indices) return
        queue = list
        queueIndex = index
        val song = list[index]
        player.play(File(song.path), song.durationMs)
        val resumeAt = resumePoints[song.id]
        if (prefs.resumeSpoken && resumeAt != null) player.seekTo(resumeAt)
        if (prefs.openPlayerOnPlay) showPlayer = true
    }

    /**
     * Measures every song that has not been measured yet.
     *
     * Only the ones missing, because analysis is the expensive thing this
     * app does - seconds per song, against milliseconds for everything else -
     * and a library is scanned far more often than it changes.
     *
     * Each row is written as it is produced rather than all of them at the
     * end, so a pass that is interrupted keeps what it had already measured.
     */
    fun analyze() {
        val todo = songs.filter { it.id !in features }
        if (todo.isEmpty()) return
        analysing = true
        scope.launch {
            var done = 0
            var unreadable = 0
            for (song in todo) {
                val row = withContext(Dispatchers.IO) {
                    val f = Analyzer.analyze(song)
                    if (f != null) store.putFeature(f)
                    f
                }
                done++
                if (row == null) unreadable++
                status = "מנתח… $done מתוך ${todo.size}"
            }
            reload()
            analysing = false
            // reload() has just written the ordinary count over the status,
            // so the files that could not be read are said afterwards or not
            // at all - and silently skipping them is how someone ends up
            // wondering why a shelf never mentions half their library.
            if (unreadable > 0) status = "$status · $unreadable קבצים לא נקראו"
        }
    }

    fun scan(roots: List<File>) {
        if (roots.isEmpty()) return
        scanning = true
        status = "סורק…"
        scope.launch {
            val found = withContext(Dispatchers.IO) {
                val list = LibraryScan.scan(
                    roots = roots,
                    minDurationSec = prefs.minDurationSec,
                    excluded = prefs.excludedFolders
                )
                store.folders = roots
                store.replaceSongs(list)
                list
            }
            reload()
            scanning = false
            if (found.isEmpty()) status = "לא נמצאו קבצי שמע בתיקייה"
            if (prefs.autoAnalyze && found.isNotEmpty()) analyze()
        }
    }

    fun rate(song: SongEntity, stars: Int) {
        scope.launch {
            stats = withContext(Dispatchers.IO) {
                store.setRating(song.id, stars)
                store.stats()
            }
        }
    }

    fun dislike(song: SongEntity) {
        scope.launch {
            stats = withContext(Dispatchers.IO) {
                store.setLike(song.id, -1)
                store.stats()
            }
        }
    }

    /**
     * Saves a moved slider and rebuilds everything that depended on it.
     *
     * The rebuild is the point: a weight that does not visibly change the
     * shelves is a weight nobody can tell they have changed, and these are
     * exactly the settings people move once and never look at again if they
     * see nothing happen.
     */
    fun retune(next: EngineTuning) {
        tuning = next
        scope.launch {
            withContext(Dispatchers.IO) { store.tuning = next }
            reload()
        }
    }

    fun rateArtist(artist: ArtistInfo, rating: Int) {
        scope.launch {
            withContext(Dispatchers.IO) {
                store.setArtistRating(artist.key, artist.displayName, rating)
            }
            reload()
        }
    }

    fun tagArtist(artist: ArtistInfo, style: String) {
        scope.launch {
            val now = Styles.parse(artist.styles).toMutableList()
            if (!now.removeIf { it.equals(style, ignoreCase = true) }) now.add(style)
            withContext(Dispatchers.IO) {
                store.setArtistStyles(artist.key, artist.displayName, Styles.join(now))
            }
            reload()
        }
    }

    fun like(song: SongEntity) {
        scope.launch {
            stats = withContext(Dispatchers.IO) {
                store.setLike(song.id, 1)
                store.stats()
            }
        }
    }

    /**
     * Plays a list in a random order, starting from a random song.
     *
     * Shuffled once into a fixed order rather than picked at random as it
     * goes: a queue that decides its next song at the last moment cannot show
     * what is coming and cannot go back to what just played.
     */
    fun shuffleList(list: List<SongEntity>) {
        if (list.isEmpty()) return
        play(list.shuffled(), 0)
    }

    /**
     * A station built out from one song.
     *
     * The same [Recommender] the shelves come from, asked a different
     * question - so a radio from a song and a shelf that recommended it agree
     * about what sounds like what.
     */
    fun startRadio(song: SongEntity) {
        val station = engine?.radio(song).orEmpty()
        play(if (station.isEmpty()) listOf(song) else station, 0)
    }

    fun createPlaylist(name: String) {
        if (name.isBlank()) return
        scope.launch {
            withContext(Dispatchers.IO) { store.createPlaylist(name) }
            reload()
        }
    }

    fun deletePlaylist(id: Long) {
        scope.launch {
            withContext(Dispatchers.IO) { store.deletePlaylist(id) }
            // A list that was open when it was deleted has nothing left to
            // show, so the screen it was on goes with it.
            stack = stack.filterNot { it is Route.Detail && it.list.playlistId == id }
            reload()
        }
    }

    fun addToPlaylist(id: Long, song: SongEntity) {
        scope.launch {
            withContext(Dispatchers.IO) { store.addToPlaylist(id, song.id) }
            reload()
        }
    }

    /** A new list with one song already on it, which is how most lists start. */
    fun createPlaylistWith(name: String, song: SongEntity) {
        if (name.isBlank()) return
        scope.launch {
            withContext(Dispatchers.IO) {
                val id = store.createPlaylist(name)
                store.addToPlaylist(id, song.id)
            }
            reload()
        }
    }

    fun openSettings() {
        stack = stack + Route.Settings
    }

    fun loadRecap() {
        scope.launch {
            recap = withContext(Dispatchers.IO) {
                Recap.build(store.history(), songs.associateBy { it.id })
            }
        }
    }

    fun buildProposals() {
        scope.launch {
            proposals = withContext(Dispatchers.Default) {
                TagFixer.propose(songs, dropForeign = prefs.tagStripForeign)
            }
        }
    }

    /**
     * Saves the corrections, and optionally pushes them into the files.
     *
     * The database write comes first and never depends on the file write,
     * which can fail for half a dozen ordinary Windows reasons. A repair that
     * only half applied because a share was offline would otherwise leave the
     * library in a state nobody can reason about.
     */
    fun applyTagFix(list: List<TagFixer.Proposal>) {
        val overrides = TagFixer.toOverrides(list)
        if (overrides.isEmpty()) {
            status = "אין מה לתקן"
            return
        }
        scope.launch {
            val note = withContext(Dispatchers.IO) {
                store.saveOverrides(overrides)
                if (!prefs.writeTagsToFiles) {
                    null
                } else {
                    val byId = songs.associateBy { it.id }
                    val result = TagWriter.write(overrides, byId)
                    if (result.failed > 0) "${result.failed} קבצים לא ניתנים לכתיבה" else null
                }
            }
            reload()
            buildProposals()
            status = note ?: "עודכנו ${overrides.size} שירים"
        }
    }

    fun editTags(songId: Long, title: String, artist: String) {
        scope.launch {
            withContext(Dispatchers.IO) {
                store.saveOverrides(
                    listOf(
                        TagOverrideEntity(
                            songId = songId,
                            title = title,
                            artistName = artist
                        )
                    )
                )
            }
            reload()
            buildProposals()
        }
    }

    fun addBookmark(song: SongEntity, positionMs: Long, label: String) {
        scope.launch {
            bookmarks = withContext(Dispatchers.IO) {
                store.addBookmark(song.id, positionMs, label)
                store.bookmarks()
            }
        }
    }

    fun deleteBookmark(id: Long) {
        scope.launch {
            bookmarks = withContext(Dispatchers.IO) {
                store.deleteBookmark(id)
                store.bookmarks()
            }
        }
    }

    /**
     * A mood chip: everything the measurements put in that corner of the plane.
     *
     * The judgement is [Mood.filter]'s, in :engine, so a mood on the phone and
     * the same mood here pick the same songs out of the same library.
     */
    fun openMood(mood: Mood) {
        val matching = Mood.filter(library.songs, features, mood)
        if (matching.isEmpty()) {
            status = "אין שירים שמתאימים ל\"${mood.label}\" בספרייה הזאת"
            return
        }
        stack = stack + Route.Detail(
            DetailList(
                title = mood.label,
                subtitle = mood.subtitle,
                songs = matching,
                gradientKey = "mood:${mood.name}"
            )
        )
    }

    fun removeFromPlaylist(id: Long, song: SongEntity) {
        scope.launch {
            withContext(Dispatchers.IO) { store.removeFromPlaylist(id, song.id) }
            reload()
        }
    }

    // The end of a track arrives on the audio thread, and moving to the next
    // one touches state the composition reads, so it is handed back to the
    // composition's own dispatcher rather than acted on where it was noticed.
    // Claimed globally rather than while focused, because a media key is
    // pressed exactly when the window is not the thing being looked at.
    DisposableEffect(player) {
        MediaKeys.start(
            onPlayPause = { player.togglePause() },
            onNext = { scope.launch { play(queue, queueIndex + 1) } },
            onPrevious = { scope.launch { play(queue, queueIndex - 1) } }
        )
        onDispose { MediaKeys.stop() }
    }

    DisposableEffect(player) {
        player.onEnded = {
            scope.launch {
                // The one sleep option that is not a countdown. Asked here
                // because the end of a track is the only moment it means
                // anything, and consuming it disarms it.
                if (SleepTimer.consumeAfterTrack()) {
                    play(queue, queueIndex, previousCompleted = true)
                    player.pause()
                    status = "טיימר השינה עצר את הנגינה"
                    return@launch
                }
                val next = queueIndex + 1
                if (next in queue.indices) {
                    play(queue, next, previousCompleted = true)
                    return@launch
                }
                // The queue is finished. Keep going on what the engine
                // suggests, rather than stopping dead in silence.
                val from = queue.getOrNull(queueIndex)
                val station = if (prefs.autoRadio && from != null) {
                    engine?.radio(from).orEmpty().filterNot { it.id == from.id }
                } else {
                    emptyList()
                }
                if (station.isEmpty()) {
                    play(queue, next, previousCompleted = true)
                } else {
                    play(station, 0, previousCompleted = true)
                }
            }
        }
        onDispose {
            player.onEnded = null
            player.stop()
            store.close()
        }
    }

    // Every second, so the sleep timer's remaining time and the player's
    // position stay honest without either of them polling on its own.
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tick++
        }
    }

    // Shown once, before anything has been scanned. The first minutes are the
    // worst the app ever looks and this is the only thing that says why.
    if (!welcomeDone) {
        WelcomeScreen(songCount = songs.size) {
            prefs.welcomeSeen = true
            welcomeDone = true
        }
        return
    }

    val current = queue.getOrNull(queueIndex)
    if (showPlayer && current != null) {
        PlayerScreen(
            song = current,
            feature = features[current.id],
            stat = stats[current.id],
            positionMs = state.positionMs,
            durationMs = state.durationMs,
            playing = state.playing,
            onClose = { showPlayer = false },
            onToggle = { player.togglePause() },
            onPrevious = { play(queue, queueIndex - 1) },
            onNext = { play(queue, queueIndex + 1) },
            onSeek = { player.seekTo(it) },
            onLike = { like(current) },
            onDislike = { dislike(current) },
            onRate = { rate(current, it) },
            sleepArmed = SleepTimer.remainingMs() != null || SleepTimer.stopAfterTrack,
            onSleep = { sleepOpen = true },
            onBookmarks = { bookmarksOpen = true },
            onLyrics = {
                showPlayer = false
                stack = stack + Route.Lyrics(current.id)
            },
            onQueue = {
                showPlayer = false
                stack = stack + Route.Queue
            }
        )
        SleepAndBookmarks(
            song = current,
            positionMs = state.positionMs,
            bookmarks = bookmarks.filter { it.songId == current.id },
            sleepOpen = sleepOpen,
            bookmarksOpen = bookmarksOpen,
            onSleepDismiss = { sleepOpen = false },
            onBookmarksDismiss = { bookmarksOpen = false },
            onAddBookmark = { at, label -> addBookmark(current, at, label) },
            onDeleteBookmark = { deleteBookmark(it) },
            onSeek = { player.seekTo(it) },
            onSleepMinutes = { minutes ->
                SleepTimer.startMinutes(minutes) { player.pause() }
                status = "הנגינה תיעצר בעוד $minutes דקות"
            },
            onSleepAfterTrack = {
                SleepTimer.stopAfterCurrentTrack()
                status = "ייעצר בסוף השיר הנוכחי"
            },
            onSleepCancel = { SleepTimer.cancel() }
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // A drilled-into screen covers the tabs but not the player or the
            // bar below it: what is playing should not disappear because an
            // album was opened, and the way back out should always be visible.
            when (val top = stack.lastOrNull()) {
                is Route.Detail -> {
                    // Re-read rather than shown as it was opened. A song taken
                    // off a list, or a like taken back, changes what the list
                    // holds, and a snapshot would go on showing the old one.
                    val data = when {
                        top.list.playlistId != null ->
                            library.playlists
                                .firstOrNull { it.playlist.id == top.list.playlistId }
                                ?.let {
                                    top.list.copy(
                                        title = it.playlist.name,
                                        subtitle = "${it.songs.size} שירים",
                                        songs = it.songs
                                    )
                                } ?: top.list
                        top.list.gradientKey == "auto:liked" ->
                            library.liked(stats).let {
                                top.list.copy(subtitle = "${it.size} שירים", songs = it)
                            }
                        else -> top.list
                    }
                    DetailListScreen(
                        data = data,
                        stats = stats,
                        current = current?.id,
                        onBack = { stack = stack.dropLast(1) },
                        onPlay = { list, index -> play(list, index) },
                        onShuffle = { shuffleList(it) },
                        onLike = { like(it) },
                        onDislike = { dislike(it) },
                        onMore = { options = it },
                        onRemove = data.playlistId?.let { id ->
                            { song: SongEntity -> removeFromPlaylist(id, song) }
                        }
                    )
                }

                is Route.Artist -> {
                    // Looked up rather than carried, so a rating or a style
                    // word set on this screen is on it the moment it is saved.
                    // A rescan can take an artist away while their page is
                    // open, which is what the empty state is for.
                    val info = library.artists.firstOrNull { it.key == top.key }
                    if (info == null) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            DetailTopBar("", onBack = { stack = stack.dropLast(1) })
                            EmptyState(
                                title = "האמן כבר לא בספרייה",
                                body = "אפשר לחזור אחורה ולבחור אחר."
                            )
                        }
                    } else {
                        ArtistDetailScreen(
                            artist = info,
                            stats = stats,
                            current = current?.id,
                            onBack = { stack = stack.dropLast(1) },
                            onPlay = { list, index -> play(list, index) },
                            onRadio = { startRadio(it) },
                            onRate = { rateArtist(info, it) },
                            onTag = { tagArtist(info, it) },
                            onLike = { like(it) },
                            onDislike = { dislike(it) },
                            onMore = { options = it }
                        )
                    }
                }

                Route.Albums -> AlbumsScreen(
                    albums = library.albums,
                    onBack = { stack = stack.dropLast(1) },
                    onOpen = { album ->
                        stack = stack + Route.Detail(
                            DetailList(
                                title = album.name,
                                subtitle = album.artistName,
                                songs = album.songs,
                                gradientKey = "album:${album.albumId}"
                            )
                        )
                    }
                )

                Route.Settings -> SettingsScreen(
                    prefs = prefs,
                    songs = songs.size,
                    analysed = features.size,
                    ratedArtists = artists.count { it.rating > 0 },
                    taggedArtists = artists.count { it.styles.isNotBlank() },
                    liked = stats.values.count { it.liked == 1 },
                    played = stats.values.sumOf { it.playCount },
                    folders = folders.map { it.absolutePath },
                    scanning = scanning,
                    analysing = analysing,
                    onBack = { stack = stack.dropLast(1) },
                    onOpenPlayerSettings = { stack = stack + Route.PlayerSettings },
                    onOpenAlgorithm = { stack = stack + Route.Algorithm },
                    onOpenTags = {
                        buildProposals()
                        stack = stack + Route.Tags
                    },
                    onPickFolder = { chooseFolder()?.let { scan(listOf(it)) } },
                    onRescan = { scan(folders) },
                    onAnalyze = { analyze() },
                    onResetAnalysis = {
                        scope.launch {
                            withContext(Dispatchers.IO) { store.clearFeatures() }
                            reload()
                        }
                    },
                    onResetStats = {
                        scope.launch {
                            withContext(Dispatchers.IO) { store.clearStats() }
                            recap = null
                            reload()
                        }
                    },
                    onPickLyricsFolder = {
                        chooseFolder()?.let { prefs.lyricsFolder = it.absolutePath }
                    }
                )

                Route.PlayerSettings -> PlayerSettingsScreen(
                    prefs = prefs,
                    equalizer = player.equalizer,
                    onBack = { stack = stack.dropLast(1) }
                )

                Route.Algorithm -> AlgorithmSettingsScreen(
                    tuning = tuning,
                    onChange = { retune(it) },
                    onBack = { stack = stack.dropLast(1) }
                )

                Route.Tags -> TagFixScreen(
                    proposals = proposals,
                    stripForeign = prefs.tagStripForeign,
                    writeToFiles = prefs.writeTagsToFiles,
                    onStripForeign = {
                        prefs.tagStripForeign = it
                        buildProposals()
                    },
                    onWriteToFiles = { prefs.writeTagsToFiles = it },
                    onApply = { applyTagFix(it) },
                    onEdit = { id, title, artist -> editTags(id, title, artist) },
                    onBack = { stack = stack.dropLast(1) }
                )

                Route.Recap -> RecapScreen(
                    recap = recap,
                    onBack = { stack = stack.dropLast(1) }
                )

                Route.Queue -> QueueScreen(
                    queue = queue,
                    index = queueIndex,
                    onBack = { stack = stack.dropLast(1) },
                    onPlay = { play(queue, it) },
                    onRemove = { position ->
                        // Removing what is playing is the one case that needs
                        // care: the index has to follow the song, not the slot.
                        val wasCurrent = position == queueIndex
                        val without = queue.toMutableList().also { it.removeAt(position) }
                        queue = without
                        if (position < queueIndex) queueIndex--
                        when {
                            without.isEmpty() -> {
                                queueIndex = -1
                                player.stop()
                            }
                            wasCurrent -> play(without, queueIndex.coerceIn(0, without.size - 1))
                        }
                    },
                    onClear = {
                        queue = emptyList()
                        queueIndex = -1
                        player.stop()
                    }
                )

                is Route.Lyrics -> {
                    val song = songs.firstOrNull { it.id == top.songId }
                    if (song == null) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            DetailTopBar("", onBack = { stack = stack.dropLast(1) })
                            EmptyState(
                                title = "השיר כבר לא בספרייה",
                                body = "אפשר לחזור אחורה."
                            )
                        }
                    } else {
                        // Read off the disk, not held: a lyric sheet is a few
                        // kilobytes and re-reading it on open is cheaper than
                        // an index of every one of them in memory.
                        var words by remember(song.id) { mutableStateOf<Words?>(null) }
                        LaunchedEffect(song.id) {
                            words = withContext(Dispatchers.IO) {
                                SongLyrics.find(song, prefs.lyricsFolder)
                            }
                        }
                        LyricsScreen(
                            song = song,
                            words = words,
                            positionMs = state.positionMs,
                            onBack = { stack = stack.dropLast(1) }
                        )
                    }
                }

                null -> when (tab) {
                    0 -> FeedPane(
                        feed = feed,
                        songs = songs.size,
                        ratedArtists = artists.count { it.rating > 0 },
                        scanning = scanning,
                        analysing = analysing,
                        unanalysed = songs.count { it.id !in features },
                        status = status,
                        onPick = { chooseFolder()?.let { scan(listOf(it)) } },
                        onRescan = { scan(folders) },
                        onAnalyze = { analyze() },
                        onShuffle = {
                            scope.launch {
                                withContext(Dispatchers.IO) { store.feedSeed = store.feedSeed + 1 }
                                reload()
                            }
                        },
                        hasFolders = folders.isNotEmpty(),
                        onPlay = { list, index -> play(list, index) },
                        moods = if (prefs.pinMoodRow && features.isNotEmpty()) {
                            Mood.entries.toList()
                        } else {
                            emptyList()
                        },
                        onMood = { openMood(it) },
                        onRecap = {
                            loadRecap()
                            stack = stack + Route.Recap
                        },
                        onSettings = { openSettings() },
                        onQueue = { stack = stack + Route.Queue }
                    )
                    1 -> SearchPane(
                        query = query,
                        onQuery = { query = it },
                        results = remember(query, engine) {
                            if (query.isBlank()) {
                                emptyList()
                            } else {
                                // Zero means text relevance alone; the
                                // default leans on what this listener plays.
                                val personal = if (prefs.searchPersonalized) 0.25 else 0.0
                                engine?.search(query, personal = personal).orEmpty()
                            }
                        },
                        stats = stats,
                        current = current?.id,
                        onPlay = { list, index -> play(list, index) },
                        onLike = { like(it) },
                        onDislike = { dislike(it) },
                        onMore = { options = it }
                    )
                    2 -> LibraryPane(
                        library = library,
                        stats = stats,
                        current = current?.id,
                        onPlay = { list, index -> play(list, index) },
                        onLike = { like(it) },
                        onDislike = { dislike(it) },
                        onMore = { options = it },
                        onOpenList = { stack = stack + Route.Detail(it) },
                        onOpenArtist = { stack = stack + Route.Artist(it.key) },
                        onOpenAlbums = { stack = stack + Route.Albums },
                        onCreatePlaylist = { createPlaylist(it) },
                        onDeletePlaylist = { deletePlaylist(it) }
                    )
                    else -> ArtistsPane(
                        artists = library.artists,
                        onOpen = { stack = stack + Route.Artist(it.key) }
                    )
                }
            }
        }

        MiniPlayer(
            song = current,
            positionMs = state.positionMs,
            durationMs = state.durationMs,
            playing = state.playing,
            volume = volume,
            onVolume = {
                volume = it
                player.setVolume(it)
                prefs.volume = (it * 100).toInt()
            },
            onOpen = { if (current != null) showPlayer = true },
            onToggle = { player.togglePause() },
            onNext = { play(queue, queueIndex + 1) }
        )

        // The same four the phone has, in the same order, with the same icons
        // and the same words. There is no fifth: settings open from the gear
        // in the corner of the home screen, exactly as on the phone, and a
        // tab for something opened twice a year would take a quarter of the
        // bar away from the four that are the app.
        NavigationBar(containerColor = Surface1) {
            // Tapping a tab always lands on that tab's root, including the
            // tab already showing: a detail screen pushed on top counts as
            // somewhere else, and the way back to the top of a tab should not
            // be several presses of the back arrow.
            fun go(index: Int) {
                tab = index
                stack = emptyList()
            }
            NavTab(tab, 0, "בית", Icons.Filled.Home) { go(0) }
            NavTab(tab, 1, "חיפוש", Icons.Filled.Search) { go(1) }
            NavTab(tab, 2, "ספרייה", Icons.Filled.LibraryMusic) { go(2) }
            NavTab(tab, 3, "אמנים", Icons.Filled.Star) { go(3) }
        }
    }

    // One dialog for the whole app rather than one per list. Opening an
    // artist or an album from it navigates, so it has to be able to reach the
    // same stack every screen is drawn from.
    options?.let { song ->
        SongOptionsDialog(
            song = song,
            stat = stats[song.id],
            playlists = library.playlists,
            onDismiss = { options = null },
            onRate = { rate(song, it) },
            onRadio = { startRadio(song) },
            onOpenArtist = {
                stack = stack + Route.Artist(song.artistKey)
                tab = 3
            },
            onOpenAlbum = {
                library.albums.firstOrNull { it.albumId == song.albumId }?.let { album ->
                    stack = stack + Route.Detail(
                        DetailList(
                            title = album.name,
                            subtitle = album.artistName,
                            songs = album.songs,
                            gradientKey = "album:${album.albumId}"
                        )
                    )
                }
            },
            onAddTo = { addToPlaylist(it, song) },
            onCreateWith = { createPlaylistWith(it, song) },
            // Inserted after what is playing, not started: queueing something
            // for later is the opposite of interrupting, and a menu entry
            // that says "next" and plays now is one nobody presses twice.
            onPlayNext = {
                if (queueIndex < 0) {
                    play(listOf(song), 0)
                } else {
                    queue = queue.toMutableList().also { it.add(queueIndex + 1, song) }
                    status = "יתנגן אחרי הנוכחי"
                }
            },
            onAddToQueue = {
                if (queueIndex < 0) {
                    play(listOf(song), 0)
                } else {
                    queue = queue + song
                    status = "נוסף לתור"
                }
            }
        )
    }
}

@Composable
private fun RowScope.NavTab(
    current: Int,
    index: Int,
    label: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    NavigationBarItem(
        selected = current == index,
        onClick = onClick,
        icon = { Icon(icon, contentDescription = label) },
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = Accent,
            selectedTextColor = Accent,
            indicatorColor = Surface1,
            unselectedIconColor = TextSecondary,
            unselectedTextColor = TextSecondary
        )
    )
}

/**
 * Where the window is, above the tabs.
 *
 * An artist is held by key and a list by value, and the difference is
 * deliberate: an artist page is entirely derived from the library and can be
 * rebuilt from a key at any moment, while "the album I tapped" has no name
 * that survives a rescan. The two mutable lists - a playlist and the likes -
 * are re-read where they are drawn.
 */
private sealed interface Route {
    data class Detail(val list: DetailList) : Route
    data class Artist(val key: String) : Route
    data object Albums : Route
    data object Settings : Route
    data object PlayerSettings : Route
    data object Algorithm : Route
    data object Tags : Route
    data object Recap : Route
    data object Queue : Route
    data class Lyrics(val songId: Long) : Route
}

/**
 * Long enough that stopping half way through is a place to come back to.
 *
 * Twelve minutes. Below it a file is a song, and a song resumes from the
 * start; above it a file is a shiur, a story or a set, and losing your place
 * in one means finding it again by dragging the bar until it sounds right.
 */
private const val LONG_FORM_MS = 12 * 60 * 1000L

/** Everything one reload reads, so the composition is updated once and not six times. */
private data class Loaded(
    val songs: List<SongEntity>,
    val stats: Map<Long, SongStatsEntity>,
    val artists: List<ArtistEntity>,
    val library: LibraryModel,
    val folders: List<File>,
    val seed: Long,
    val feed: List<FeedSection>,
    val analysed: Map<Long, AudioFeatureEntity>,
    val engine: Recommender?,
    val tuning: EngineTuning,
    val bookmarks: List<BookmarkEntity>,
    val positions: Map<Long, Long>
)

/**
 * The two settings that decide what counts as part of the library.
 *
 * Applied before anything is derived from the list, so a hidden copy is gone
 * from the artist counts, the albums and the shelves too rather than only
 * from the songs tab - which is what "hidden" has to mean for it to be worth
 * having at all.
 */
private fun filterLibrary(
    songs: List<SongEntity>,
    stats: Map<Long, SongStatsEntity>,
    prefs: Prefs
): List<SongEntity> {
    var out = songs
    if (prefs.skipRecordings) {
        out = out.filterNot {
            Names.looksLikeRecording(it.folder, it.path.substringAfterLast(File.separatorChar))
        }
    }
    if (prefs.hideDuplicates) {
        // Indexed first: classify asks for a play count once per song, and a
        // linear scan inside that would make building the library quadratic.
        val plays = stats.mapValues { it.value.playCount }
        val types = Versions.classify(out) { id -> plays[id] ?: 0 }
        out = Versions.withoutDuplicates(out, types)
    }
    return out
}

/**
 * Puts the tag repairs over the scanned rows.
 *
 * Applied here rather than written into the songs table, because a rescan
 * rebuilds that table from the files and would throw every correction away.
 * The keys are recomputed from the corrected names for the same reason they
 * exist at all - an artist key derived from the wrong name groups the library
 * by the wrong name.
 */
private fun applyOverrides(
    songs: List<SongEntity>,
    overrides: Map<Long, TagOverrideEntity>
): List<SongEntity> {
    if (overrides.isEmpty()) return songs
    return songs.map { song ->
        val fix = overrides[song.id] ?: return@map song
        val title = fix.title.ifBlank { song.title }
        val artist = fix.artistName.ifBlank { song.artistName }
        song.copy(
            title = title,
            titleLower = title.lowercase(),
            artistName = artist,
            artistKey = Names.normalizeKey(Names.primaryArtist(artist)),
            albumName = fix.albumName.ifBlank { song.albumName }
        )
    }
}

/**
 * A shelf heading: the name in full size, what it is under it in grey.
 *
 * The same two lines the phone draws, because the subtitle is where a shelf
 * says why it exists - "מתוך הספרייה שלך", "כי שמעת" - and a row of covers
 * with no explanation is a row of covers.
 */
@Composable
private fun SectionHeader(title: String, subtitle: String?) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = GUTTER, vertical = 6.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun FeedPane(
    feed: List<FeedSection>,
    songs: Int,
    ratedArtists: Int,
    scanning: Boolean,
    analysing: Boolean,
    unanalysed: Int,
    status: String,
    hasFolders: Boolean,
    onPick: () -> Unit,
    onRescan: () -> Unit,
    onAnalyze: () -> Unit,
    onShuffle: () -> Unit,
    onPlay: (List<SongEntity>, Int) -> Unit,
    moods: List<Mood>,
    onMood: (Mood) -> Unit,
    onRecap: () -> Unit,
    onSettings: () -> Unit,
    onQueue: () -> Unit
) {
    val visible = feed.filter { section ->
        when (section.kind) {
            SectionKind.MIX_ROW -> section.mixes.isNotEmpty()
            else -> section.songs.isNotEmpty()
        }
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            HomeTopBar(
                onRefresh = onShuffle,
                onQueue = onQueue,
                onRecap = onRecap,
                onSettings = onSettings
            )
            HomeHeader(
                songs = songs,
                ratedArtists = ratedArtists,
                scanning = scanning,
                analysing = analysing,
                unanalysed = unanalysed,
                status = status,
                hasFolders = hasFolders,
                hasFeed = visible.isNotEmpty(),
                onPick = onPick,
                onRescan = onRescan,
                onAnalyze = onAnalyze,
                onShuffle = onShuffle
            )
            // The mood chips, above the shelves. They filter on what was
            // measured rather than on anything typed, so they are the one row
            // that works on a library with no ratings and no history at all -
            // which on a first run is every library.
            if (moods.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = GUTTER),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 14.dp)
                ) {
                    items(moods) { mood ->
                        Chip(label = mood.label, selected = false) { onMood(mood) }
                    }
                }
            }
        }
        items(visible) { section ->
            Column(modifier = Modifier.padding(bottom = 14.dp)) {
                SectionHeader(section.title, section.subtitle)
                when (section.kind) {
                    SectionKind.MIX_ROW -> Shelf {
                        items(section.mixes) { mix ->
                            MixTile(
                                title = mix.title,
                                subtitle = mix.subtitle,
                                seed = mix.id,
                                covers = mix.songs.take(4),
                                onClick = { onPlay(mix.songs, 0) }
                            )
                        }
                    }
                    // Columns of four, as on the phone. On a wide screen
                    // several columns show at once, which is the point of the
                    // shape: a quick pick is a thing to glance down, not a
                    // row to scroll along.
                    SectionKind.QUICK_PICKS -> Shelf {
                        items(section.songs.chunked(4)) { column ->
                            Column(modifier = Modifier.width(340.dp)) {
                                for (song in column) {
                                    CompactRow(
                                        song = song,
                                        onClick = {
                                            onPlay(section.songs, section.songs.indexOf(song))
                                        }
                                    )
                                }
                            }
                        }
                    }
                    SectionKind.SONG_ROW -> Shelf {
                        itemsIndexed(section.songs) { index, song ->
                            Tile(
                                title = song.title,
                                subtitle = song.artistName.ifEmpty { "ללא אמן" },
                                song = song,
                                onClick = { onPlay(section.songs, index) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Shelf(content: LazyListScope.() -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = GUTTER - 4.dp),
        modifier = Modifier.padding(top = 4.dp),
        content = content
    )
}

/**
 * The top of the home screen: what the library is, and what it still needs.
 *
 * On the phone this is a greeting card. Here it also carries the scan and
 * analyse actions, because a desktop has no other obvious place to put them
 * and hiding the one button a new install needs behind a settings screen is
 * how a first run ends with an empty window and no idea why.
 */
/**
 * The mark, the name, and the four things reached from the home screen.
 *
 * The gear is here and not in the navigation bar because that is where the
 * phone puts it, and because four tabs are the app.
 */
@Composable
private fun HomeTopBar(
    onRefresh: () -> Unit,
    onQueue: () -> Unit,
    onRecap: () -> Unit,
    onSettings: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RhythmMark(size = 30.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            text = "Rhythm",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onRefresh) {
            Icon(Icons.Filled.Autorenew, contentDescription = "רענון", tint = TextSecondary)
        }
        IconButton(onClick = onQueue) {
            Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = "התור", tint = TextSecondary)
        }
        IconButton(onClick = onRecap) {
            Icon(Icons.Filled.BarChart, contentDescription = "הסיכום שלך", tint = TextSecondary)
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Filled.Settings, contentDescription = "הגדרות", tint = TextSecondary)
        }
    }
}

@Composable
private fun HomeHeader(
    songs: Int,
    ratedArtists: Int,
    scanning: Boolean,
    analysing: Boolean,
    unanalysed: Int,
    status: String,
    hasFolders: Boolean,
    hasFeed: Boolean,
    onPick: () -> Unit,
    onRescan: () -> Unit,
    onAnalyze: () -> Unit,
    onShuffle: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = GUTTER, vertical = 14.dp)
    ) {
        Text(
            if (songs == 0) "ברוך הבא" else "הספרייה שלך",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            when {
                songs == 0 -> "בחר תיקיית מוזיקה כדי להתחיל"
                ratedArtists == 0 -> "$songs שירים · דרג אמנים בטאב \"אמנים\" כדי שהמנוע ילמד"
                else -> "$songs שירים · $ratedArtists אמנים מדורגים"
            },
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )

        Row(
            modifier = Modifier.padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(enabled = !scanning && !analysing, onClick = onPick) { Text("בחר תיקייה") }
            if (hasFolders) {
                Button(enabled = !scanning && !analysing, onClick = onRescan) { Text("סרוק מחדש") }
            }
            if (unanalysed > 0) {
                Button(enabled = !scanning && !analysing, onClick = onAnalyze) {
                    Text("נתח ($unanalysed)")
                }
            }
            if (hasFeed) {
                Button(enabled = !scanning && !analysing, onClick = onShuffle) { Text("ערבב") }
            }
        }

        if (status.isNotBlank()) {
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun SearchPane(
    query: String,
    onQuery: (String) -> Unit,
    results: List<SongEntity>,
    stats: Map<Long, SongStatsEntity>,
    current: Long?,
    onPlay: (List<SongEntity>, Int) -> Unit,
    onLike: (SongEntity) -> Unit,
    onDislike: (SongEntity) -> Unit,
    onMore: (SongEntity) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            label = { Text("חיפוש") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(GUTTER)
        )
        SongList(
            songs = results,
            stats = stats,
            current = current,
            empty = if (query.isBlank()) "הקלד כדי לחפש" else "לא נמצא כלום",
            onPlay = { index -> onPlay(results, index) },
            onLike = onLike,
            onDislike = onDislike,
            onMore = onMore
        )
    }
}

/**
 * A mix, shown as what is inside it over the colour its id picks.
 *
 * The collage rather than one cover, because a mix is several records and one
 * of their covers would claim it for that record. The gradient stays under a
 * mix with too few covers to fill the grid.
 */
@Composable
private fun MixTile(
    title: String,
    subtitle: String,
    seed: String,
    covers: List<SongEntity>,
    onClick: () -> Unit
) {
    val (c1, c2) = gradientFor(seed)
    Column(modifier = Modifier.width(150.dp).clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .width(150.dp)
                .height(150.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Brush.linearGradient(listOf(c1, c2)))
        ) {
            if (covers.size >= 4) {
                Column(modifier = Modifier.fillMaxSize()) {
                    for (row in 0 until 2) {
                        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                            for (column in 0 until 2) {
                                val cover = rememberArtwork(covers[row * 2 + column])
                                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                    if (cover != null) {
                                        Image(
                                            bitmap = cover,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp)
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun Tile(title: String, subtitle: String, song: SongEntity?, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(CARD)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 4.dp)
    ) {
        Art(song = song, size = CARD - 8.dp, corner = 12.dp)
        Spacer(Modifier.height(8.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CompactRow(song: SongEntity, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Art(song = song, size = 44.dp, corner = 4.dp)
        Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(
                song.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                song.artistName.ifEmpty { "ללא אמן" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun PlayerScreen(
    song: SongEntity,
    feature: AudioFeatureEntity?,
    stat: SongStatsEntity?,
    positionMs: Long,
    durationMs: Long,
    playing: Boolean,
    onClose: () -> Unit,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onLike: () -> Unit,
    onDislike: () -> Unit,
    onRate: (Int) -> Unit,
    sleepArmed: Boolean,
    onSleep: () -> Unit,
    onBookmarks: () -> Unit,
    onLyrics: () -> Unit,
    onQueue: () -> Unit
) {
    var scrub by remember { mutableStateOf<Float?>(null) }
    val liked = stat?.liked ?: 0
    val rating = stat?.rating ?: 0

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.ExpandMore, contentDescription = "סגור")
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onQueue) {
                Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = "התור", tint = TextSecondary)
            }
            IconButton(onClick = onLyrics) {
                Icon(
                    Icons.AutoMirrored.Filled.Subject,
                    contentDescription = "מילות השיר",
                    tint = TextSecondary
                )
            }
            IconButton(onClick = onBookmarks) {
                Icon(Icons.Filled.Bookmark, contentDescription = "סימניות", tint = TextSecondary)
            }
            IconButton(onClick = onSleep) {
                // Accented while armed. A timer nobody can see is one people
                // set twice and then wonder why the music stopped.
                Icon(
                    Icons.Filled.Bedtime,
                    contentDescription = "טיימר שינה",
                    tint = if (sleepArmed) Accent else TextSecondary
                )
            }
        }

        Art(song = song, size = 300.dp, corner = 12.dp)

        Text(
            song.title,
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 20.dp)
        )
        Text(
            song.artistName.ifEmpty { "ללא אמן" },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // What the analyser measured, said plainly. The engine reasons about
        // these numbers constantly and never shows them, which makes a shelf
        // it built out of them look like a guess.
        feature?.let { f ->
            Text(
                "${f.bpm.toInt()} BPM · ${Features.modeLabel(f)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
        ) {
            Text(clock(positionMs), style = MaterialTheme.typography.labelSmall)
            Slider(
                value = scrub ?: positionMs.toFloat(),
                valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
                onValueChange = { scrub = it },
                onValueChangeFinished = {
                    scrub?.let { onSeek(it.toLong()) }
                    scrub = null
                },
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
            )
            Text(clock(durationMs), style = MaterialTheme.typography.labelSmall)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrevious) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "הקודם")
            }
            IconButton(onClick = onToggle) {
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "השהה" else "נגן"
                )
            }
            IconButton(onClick = onNext) {
                Icon(Icons.Filled.SkipNext, contentDescription = "הבא")
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            IconButton(onClick = onDislike) {
                Icon(
                    if (liked == -1) Icons.Filled.ThumbDown else Icons.Outlined.ThumbDown,
                    contentDescription = if (liked == -1) "בטל דיסלייק" else "דיסלייק",
                    tint = if (liked == -1) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            for (star in 1..5) {
                IconButton(onClick = { onRate(star) }) {
                    Icon(
                        if (star <= rating) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = "$star",
                        tint = if (star <= rating) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
            IconButton(onClick = onLike) {
                Icon(
                    if (liked == 1) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                    contentDescription = if (liked == 1) "בטל לייק" else "לייק",
                    tint = if (liked == 1) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

/**
 * The player's two dialogs.
 *
 * Together in one composable because both belong to the song on screen and
 * both are opened from the same row of icons; splitting them would mean the
 * player screen's caller passing eleven more parameters to say the same thing.
 */
@Composable
private fun SleepAndBookmarks(
    song: SongEntity,
    positionMs: Long,
    bookmarks: List<BookmarkEntity>,
    sleepOpen: Boolean,
    bookmarksOpen: Boolean,
    onSleepDismiss: () -> Unit,
    onBookmarksDismiss: () -> Unit,
    onAddBookmark: (Long, String) -> Unit,
    onDeleteBookmark: (Long) -> Unit,
    onSeek: (Long) -> Unit,
    onSleepMinutes: (Int) -> Unit,
    onSleepAfterTrack: () -> Unit,
    onSleepCancel: () -> Unit
) {
    if (bookmarksOpen) {
        BookmarksDialog(
            song = song,
            bookmarks = bookmarks,
            positionMs = positionMs,
            onAdd = onAddBookmark,
            onDelete = onDeleteBookmark,
            onSeek = onSeek,
            onDismiss = onBookmarksDismiss
        )
    }
    if (sleepOpen) {
        SleepDialog(
            armedMs = SleepTimer.remainingMs(),
            afterTrack = SleepTimer.stopAfterTrack,
            onMinutes = onSleepMinutes,
            onAfterTrack = onSleepAfterTrack,
            onCancel = onSleepCancel,
            onDismiss = onSleepDismiss
        )
    }
}

/**
 * The bar above the navigation, the way the phone carries it.
 *
 * Deliberately small and deliberately not a control surface: artwork, what is
 * playing, play and next. Everything else - seeking, the thumbs, the stars -
 * belongs to the player screen, and this is the thing that opens it.
 */
@Composable
private fun MiniPlayer(
    song: SongEntity?,
    positionMs: Long,
    durationMs: Long,
    playing: Boolean,
    volume: Float,
    onVolume: (Float) -> Unit,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit
) {
    if (song == null) return
    Column(modifier = Modifier.fillMaxWidth().background(Surface1)) {
        // A line rather than a slider. It says how far through the song is,
        // which is all this bar needs to say; dragging happens upstairs.
        val fraction = if (durationMs > 0) {
            (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        } else {
            0f
        }
        Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(Surface1)) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .background(Accent)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.clickable(onClick = onOpen)) {
                Art(song = song, size = 44.dp, corner = 6.dp)
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp)
                    .clickable(onClick = onOpen)
            ) {
                Text(
                    song.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    song.artistName.ifEmpty { "ללא אמן" },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onToggle) {
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "השהה" else "נגן",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            IconButton(onClick = onNext) {
                Icon(
                    Icons.Filled.SkipNext,
                    contentDescription = "הבא",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            // A volume of its own, which the phone has no need for - Android
            // has one set of volume keys for the whole device, and Windows
            // gives every application its own level in the mixer. Without
            // this, the only way to make this app quieter is to make
            // everything quieter.
            Icon(
                Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = "עוצמה",
                tint = TextSecondary
            )
            Slider(
                value = volume,
                onValueChange = onVolume,
                modifier = Modifier.width(110.dp).padding(start = 6.dp)
            )
        }
    }
}

/** Milliseconds as m:ss, which is how long a song is said out loud. */
private fun clock(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}

private fun chooseFolder(): File? {
    val chooser = JFileChooser().apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = "בחר תיקיית מוזיקה"
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile
    } else {
        null
    }
}
