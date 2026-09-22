package com.elchanan.rhythm.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.Subject
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.elchanan.rhythm.data.PlaylistExport
import com.elchanan.rhythm.data.PlaylistImport
import com.elchanan.rhythm.data.TagFixer
import com.elchanan.rhythm.data.db.ArtistEntity
import com.elchanan.rhythm.data.db.AudioFeatureEntity
import com.elchanan.rhythm.data.db.BookmarkEntity
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.data.db.SongStatsEntity
import com.elchanan.rhythm.data.db.TagOverrideEntity
import com.elchanan.rhythm.desktop.audio.Analyzer
import com.elchanan.rhythm.desktop.audio.AudioPlayer
import com.elchanan.rhythm.desktop.audio.Equalizer
import com.elchanan.rhythm.desktop.data.Store
import com.elchanan.rhythm.engine.ActionPlacement
import com.elchanan.rhythm.engine.AudioTags
import com.elchanan.rhythm.engine.EngineTuning
import com.elchanan.rhythm.engine.Features
import com.elchanan.rhythm.engine.FeedSection
import com.elchanan.rhythm.engine.Loudness
import com.elchanan.rhythm.engine.LyricLine
import com.elchanan.rhythm.engine.Lyrics
import com.elchanan.rhythm.engine.Mood
import com.elchanan.rhythm.engine.Names
import com.elchanan.rhythm.engine.PlayerAction
import com.elchanan.rhythm.engine.Recap
import com.elchanan.rhythm.engine.RecapData
import com.elchanan.rhythm.engine.Recommender
import com.elchanan.rhythm.engine.ScoreTerm
import com.elchanan.rhythm.engine.SectionKind
import com.elchanan.rhythm.engine.ShelfKind
import com.elchanan.rhythm.engine.Spoken
import com.elchanan.rhythm.engine.StyleLearning
import com.elchanan.rhythm.engine.Styles
import com.elchanan.rhythm.engine.Versions
import com.elchanan.rhythm.ui.theme.Accent
import com.elchanan.rhythm.ui.theme.AppBackground
import com.elchanan.rhythm.ui.theme.RhythmTheme
import com.elchanan.rhythm.ui.theme.Surface1
import com.elchanan.rhythm.ui.theme.Surface2
import com.elchanan.rhythm.ui.theme.TextPrimary
import com.elchanan.rhythm.ui.theme.TextSecondary
import com.elchanan.rhythm.ui.theme.TextTertiary
import com.elchanan.rhythm.ui.theme.gradientFor
import java.awt.Toolkit
import java.io.File
import java.lang.Runtime
import java.util.Locale
import javax.swing.JFileChooser
import javax.swing.UIManager
import javax.swing.filechooser.FileNameExtensionFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
        // The mark, on the window itself: the title bar, Alt-Tab and the
        // taskbar all read it from here. The installed program's icon is a
        // separate thing and comes from the .ico jpackage puts in the exe.
        icon = WindowIcon.painter,
        // Centred. The platform default cascades new windows down and to the
        // side of wherever the last one opened, which on a wide monitor puts
        // a first launch off towards an edge for no reason anyone asked for.
        state = rememberWindowState(
            width = windowSize().first,
            height = windowSize().second,
            position = WindowPosition(Alignment.Center)
        )
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
    var analysisJob by remember { mutableStateOf<Job?>(null) }
    var features by remember { mutableStateOf<Map<Long, AudioFeatureEntity>>(emptyMap()) }
    // One pass over the measured library, redone only when the measurements
    // change. Empty until enough of the library has been analysed for a
    // percentile to mean anything, which is the same as the feature being off.
    val loudnessGains = remember(features) { Loudness.gains(features.values) }
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
    // Read once: a nudge that has been turned down stays down, and the flag
    // only ever changes from this screen.
    var tagTipVisible by remember { mutableStateOf(!prefs.tagTipSeen) }
    var ratingTipVisible by remember { mutableStateOf(!prefs.ratingTipSeen) }
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
    // Learning scores the whole library twice and can take a while on a big
    // one, so the buttons that would start it again are off while it runs.
    var busy by remember { mutableStateOf(false) }
    var learning by remember { mutableStateOf(false) }
    var learningReport by remember { mutableStateOf<String?>(null) }
    var shuffling by remember { mutableStateOf(false) }
    var engineReport by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf(RepeatMode.OFF) }
    // The queue as it was before it was shuffled, so turning shuffle off puts
    // it back rather than leaving a scrambled order nobody can undo.
    var unshuffled by remember { mutableStateOf<List<SongEntity>>(emptyList()) }
    // What was actually listened to in this sitting, most recent first, and
    // the last of them. Both exist only to say what was heard near what and
    // what followed what - the two things the recommender cannot work out
    // from the library, because they are facts about an evening rather than
    // about the music. Held here rather than stored: a sitting is over when
    // the app closes.
    var sessionTail by remember { mutableStateOf<List<Long>>(emptyList()) }
    var lastCounted by remember { mutableStateOf(0L) }
    var lastCountedAt by remember { mutableStateOf(0L) }

    suspend fun reload() {
        val loaded = withContext(Dispatchers.IO) {
            val s = store.songs()
            val st = store.stats()
            val ar = store.artists()
            val sd = store.feedSeed
            val ft = store.features()
            val tn = store.tuning
            // What the listening has taught, which is the half of the
            // engine's input that does not come from the files.
            val aff = store.affinityMap()
            val trans = store.transitionMap()
            val eng = if (s.isEmpty()) {
                null
            } else {
                Feed.engine(
                    filterLibrary(applyOverrides(s, store.overrides()), st, prefs),
                    st, ar, ft, sd, tn, aff, trans
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
        // Filtered here rather than inside the engine: the engine's job is to
        // decide what is worth showing, and which of those someone wants to
        // see is a different question with a different answer per person.
        val wanted = prefs.homeShelves
        feed = loaded.feed.filter { section ->
            ShelfKind.of(section.id)?.key?.let { it in wanted } ?: true
        }
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
        player.equalizer.restore(prefs.eqEnabled, prefs.eqBands, prefs.eqPreamp)
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
            val counted = countsAsPlay(heard, leaving.durationMs, previousCompleted)
            // Heard nearly to the end, whether the file ran out or the
            // listener moved on with seconds to go. This is what separates a
            // track someone sat through from one they merely did not skip.
            val finished = previousCompleted ||
                (leaving.durationMs > 0 && heard >= leaving.durationMs * 0.9)
            // A skip is a deliberate move away from something unheard. A
            // track that simply ended is neither a skip nor, if it was
            // short of the bar, a play - it is nothing, and nothing is the
            // right thing to record about it.
            val skipped = !counted && !previousCompleted && heard >= MIN_MEASURABLE_MS
            val now = System.currentTimeMillis()
            // A gap this long means this is a new sitting. The check comes
            // before the edges are written rather than after, or the first
            // song of the evening would be linked to the last song of the
            // previous one - a pairing nobody listened to.
            if (lastCountedAt > 0L && now - lastCountedAt > SESSION_GAP_MS) {
                sessionTail = emptyList()
                lastCounted = 0L
            }
            val previous = lastCounted
            val fresh = previous > 0L && now - lastCountedAt < TRANSITION_WINDOW_MS
            val tail = sessionTail.toList()
            scope.launch {
                stats = withContext(Dispatchers.IO) {
                    if (counted) {
                        store.notePlay(leaving.id, heard, finished)
                        // Everything heard near this one, most recent
                        // first, so the closer two songs were the heavier
                        // the edge between them. Written both ways: the
                        // question is symmetric.
                        tail.forEachIndexed { distance, other ->
                            val weight = 1.0 / (1.0 + distance)
                            store.bumpAffinity(leaving.id, other, weight)
                            store.bumpAffinity(other, leaving.id, weight)
                        }
                        if (fresh) store.noteTransition(previous, leaving.id, skipped = false)
                        if (store.dueForTrim()) store.trimEdges()
                    } else if (skipped) {
                        store.noteSkip(leaving.id, heard)
                        // A skip is evidence about the transition too, and
                        // the more useful half of it: it says these two do
                        // not follow each other.
                        if (fresh) store.noteTransition(previous, leaving.id, skipped = true)
                    }
                    // Only for the long ones. A song paused in the middle
                    // should start again from the top next time - resuming a
                    // four minute track two minutes in is not a convenience,
                    // it is half a song nobody asked to skip. The store drops
                    // the positions too near either end on top of this.
                    if (leaving.durationMs >= LONG_FORM_MS) {
                        store.setPosition(leaving.id, heard, leaving.durationMs)
                    }
                    store.stats()
                }
                resumePoints = withContext(Dispatchers.IO) { store.positions() }
            }
            if (counted) {
                sessionTail = (listOf(leaving.id) + sessionTail).take(SESSION_TAIL)
                lastCounted = leaving.id
                lastCountedAt = now
            }
        }
        if (index !in list.indices) return
        queue = list
        queueIndex = index
        val song = list[index]
        // Before the track starts, or the first second of it plays at the
        // previous song's correction.
        player.setTrackGain(
            if (prefs.normalizeVolume) loudnessGains[song.id] ?: 1f else 1f
        )
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
        // Said before any decoding starts. The first song used to take long
        // enough that the counter sat at nothing for minutes, which reads as
        // a button that did not work.
        status = "מנתח… 0 מתוך ${todo.size}"
        analysisJob = scope.launch {
            var done = 0
            var unreadable = 0
            try {
                // Several at a time. Decoding is arithmetic on one core and a
                // desktop has several idle ones, where the phone has a
                // hardware decoder and one job to give it. Bounded rather than
                // unbounded: past the core count the passes only take turns,
                // and every one of them is holding a decode buffer.
                //
                // One less than the cores, floor of two, so the machine is
                // still usable while a library is being measured.
                val lanes = (Runtime.getRuntime().availableProcessors() - 1)
                    .coerceIn(2, 8)
                val counter = Mutex()
                for (batch in todo.chunked(lanes)) {
                    if (!isActive) break
                    coroutineScope {
                        for (song in batch) {
                            launch(Dispatchers.IO) {
                                val f = runCatching { Analyzer.analyze(song) }.getOrNull()
                                // The store serialises its own writes, but
                                // the two counters and the status line are
                                // this coroutine's and are touched from every
                                // lane.
                                if (f != null) store.putFeature(f)
                                counter.withLock {
                                    done++
                                    if (f == null) unreadable++
                                    status = "מנתח… $done מתוך ${todo.size}"
                                }
                            }
                        }
                    }
                }
            } finally {
                analysing = false
                analysisJob = null
                // Every row measured so far is already on disk, so the
                // shelves are rebuilt whether the pass finished or was
                // stopped: a halted analysis still leaves the feed better
                // than it found it. NonCancellable because the stop button
                // cancels this very coroutine, and without it the refresh
                // would be dropped along with the loop.
                withContext(NonCancellable) {
                    reload()
                    // reload() has just written the ordinary count over the
                    // status, so the files that could not be read are said
                    // afterwards or not at all - and silently skipping them
                    // is how someone ends up wondering why a shelf never
                    // mentions half their library.
                    if (unreadable > 0) status = "$status · $unreadable קבצים לא נקראו"
                }
            }
        }
    }

    /**
     * Stop an analysis pass part way.
     *
     * Analysis is the one thing here that takes minutes, and someone who
     * started it on a large library needs a way out that is not quitting the
     * app. What was measured is kept.
     */
    fun stopAnalysis() {
        analysisJob?.cancel()
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
                prefs.lastScanAt = System.currentTimeMillis()
                prefs.lastScanCount = list.size
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

    /**
     * Lands on a tab's root, including the tab already showing.
     *
     * A detail screen pushed on top counts as somewhere else, and the way
     * back to the top of a tab should not be several presses of the back
     * arrow.
     */
    /**
     * Shuffles the queue in place, or puts it back.
     *
     * The song playing stays playing and moves to the front of what follows:
     * shuffling is about what comes next, and a shuffle that also jumps to a
     * different track is one people press once.
     */
    fun toggleShuffle() {
        val playing = queue.getOrNull(queueIndex)
        if (shuffling) {
            shuffling = false
            val restored = unshuffled.ifEmpty { queue }
            queue = restored
            queueIndex = restored.indexOfFirst { it.id == playing?.id }.coerceAtLeast(0)
            unshuffled = emptyList()
            return
        }
        shuffling = true
        unshuffled = queue
        if (playing == null) return
        val rest = queue.filterNot { it.id == playing.id }.shuffled()
        queue = listOf(playing) + rest
        queueIndex = 0
    }

    fun cycleRepeat() {
        repeat = when (repeat) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
    }

    /**
     * Asks the engine how often it would have guessed what was played next.
     *
     * Scored against the real listening history rather than a benchmark: the
     * only question worth asking of a recommender is whether it would have
     * picked this person's next song, and that answer exists only here.
     */
    fun evaluateEngine() {
        busy = true
        scope.launch {
            val report = withContext(Dispatchers.Default) {
                val order = withContext(Dispatchers.IO) {
                    store.history().sortedBy { it.playedAt }.map { it.songId }
                }
                runCatching { engine?.evaluateSequence(order) }.getOrNull()
            }
            busy = false
            engineReport = if (report == null) {
                "אין עדיין מספיק היסטוריה כדי לבדוק. צריך רצף השמעות בספרייה של 20 שירים ומעלה."
            } else {
                "נבדקו ${report.pairs} מעברים · " +
                    "בעשירייה הראשונה: ${(report.recallAt10 * 100).toInt()}%"
            }
        }
    }

    fun go(index: Int) {
        tab = index
        stack = emptyList()
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
    /**
     * Learns the user's own style words from their own library.
     *
     * The reasoning is [StyleLearning] in :engine, which the phone runs too -
     * the same library and the same tags have to produce the same model on
     * both, and a difference there would be a bug nobody could see.
     */
    fun learnStyles() {
        if (busy || learning) return
        busy = true
        learning = true
        learningReport = "הלמידה מתבצעת — בודק על אמנים שלא השתתפו באימון…"
        val songsSnapshot = library.songs
        val statsSnapshot = stats
        val stylesSnapshot = library.artists.associate { it.key to it.styles }
        val featuresSnapshot = features
        scope.launch {
            var saved = 0
            try {
                val outcome = withContext(Dispatchers.Default) {
                    StyleLearning.learn(songsSnapshot, statsSnapshot, stylesSnapshot, featuresSnapshot)
                }
                withContext(Dispatchers.IO) {
                    for ((songId, styles) in outcome.predictions) {
                        store.setSongStyles(songId, styles, auto = true)
                        saved++
                    }
                }
                learningReport = StyleLearning.report(outcome)
                status = StyleLearning.message(outcome)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                learningReport = "הלמידה הופסקה. נשמרו עד כה תגיות ל-$saved שירים."
                throw cancelled
            } catch (_: Exception) {
                learningReport = "הלמידה לא הושלמה. נשמרו תגיות ל-$saved שירים לפני השגיאה. נסה שוב."
                status = learningReport.orEmpty()
            } finally {
                learning = false
                busy = false
                if (saved > 0) reload()
            }
        }
    }

    fun clearLearnedStyles() {
        busy = true
        scope.launch {
            withContext(Dispatchers.IO) { store.clearLearnedStyles() }
            busy = false
            status = "התגיות שנוחשו נמחקו"
            reload()
        }
    }

    /**
     * Reads an m3u or pls and makes a list of what it could match.
     *
     * What could not be matched is counted and said rather than dropped in
     * silence: "יובאו 12 מתוך 30" is the difference between a working import
     * and one the user has to guess at.
     */
    fun importPlaylist(file: File) {
        scope.launch {
            val note = withContext(Dispatchers.IO) {
                val text = runCatching { file.readText() }.getOrNull()
                    ?: return@withContext "לא הצלחתי לקרוא את הקובץ"
                val parsed = PlaylistImport.parse(text, file.name)
                val (matched, missing) = PlaylistImport.match(parsed.entries, library.songs)
                if (matched.isEmpty()) {
                    return@withContext "אף שיר מהרשימה לא נמצא בספרייה"
                }
                val id = store.createPlaylist(parsed.name)
                store.bulkAddToPlaylist(id, matched.map { it.id })
                if (missing > 0) {
                    "יובאו ${matched.size} שירים · $missing לא נמצאו בספרייה"
                } else {
                    "יובאו ${matched.size} שירים"
                }
            }
            reload()
            status = note
        }
    }

    /**
     * Writes every list out as m3u, the auto ones included.
     *
     * Everything that behaves like a list, not only the ones the user made by
     * hand: the likes are a list people have spent years building and nobody
     * thinks of them as different.
     */
    fun exportPlaylists(folder: File) {
        scope.launch {
            val written = withContext(Dispatchers.IO) {
                val lists = ArrayList<Pair<String, List<SongEntity>>>()
                val liked = library.liked(stats)
                if (liked.isNotEmpty()) lists.add("השירים שאהבתי" to liked)
                for (info in library.playlists) {
                    if (info.songs.isNotEmpty()) lists.add(info.playlist.name to info.songs)
                }
                val taken = HashSet<String>()
                var count = 0
                for ((name, list) in lists) {
                    val fileName = PlaylistExport.uniqueName(name, taken)
                    val ok = runCatching {
                        File(folder, fileName).writeText(PlaylistExport.write(name, list))
                    }.isSuccess
                    if (ok) count++
                }
                count
            }
            status = if (written == 0) "אין רשימות לייצא" else "יוצאו $written רשימות"
        }
    }

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

    /**
     * A list of sixty built out from one song, opened rather than played.
     *
     * The phone's "create a mix from this": it does not interrupt what is
     * playing, it hands you a list to look at and decide about.
     */
    fun createMix(song: SongEntity) {
        val list = engine?.radio(song, 60).orEmpty()
        if (list.isEmpty()) {
            status = "אין עדיין מספיק בספרייה כדי לבנות מיקס"
            return
        }
        stack = stack + Route.Detail(
            DetailList(
                title = "מיקס: ${song.title}",
                subtitle = "${list.size} שירים סביב ${song.artistName.ifEmpty { "השיר" }}",
                songs = list,
                gradientKey = "mix:seed:${song.id}"
            )
        )
    }

    fun setSongStyles(song: SongEntity, styles: String) {
        scope.launch {
            withContext(Dispatchers.IO) { store.setSongStyles(song.id, styles, auto = false) }
            reload()
        }
    }

    fun setGenre(song: SongEntity, genre: String) {
        scope.launch {
            withContext(Dispatchers.IO) { store.setGenre(listOf(song.id), genre) }
            reload()
            status = if (genre.isBlank()) "הז'אנר נוקה" else "$genre הוגדר"
        }
    }

    fun setSpoken(song: SongEntity, spoken: Boolean) {
        scope.launch {
            stats = withContext(Dispatchers.IO) {
                store.setSpoken(song.id, spoken)
                store.stats()
            }
            status = if (spoken) "סומן כהרצאה" else "סומן כמוזיקה"
        }
    }

    fun resetPlayCount(song: SongEntity) {
        scope.launch {
            withContext(Dispatchers.IO) { store.resetPlayCount(song.id) }
            reload()
            status = "אופסו ההשמעות של ${song.title}"
        }
    }

    /**
     * Deletes the files, then forgets everything that was keyed to them.
     *
     * The order matters: if a delete fails - read only, on a share that has
     * gone away, open in something else - nothing is forgotten, and the song
     * stays exactly as it was rather than becoming a row pointing at a file
     * that is still there. One failure does not stop the rest, because a
     * selection of forty with one locked file should still lose thirty nine.
     */
    fun deleteSongs(list: List<SongEntity>) {
        if (list.isEmpty()) return
        scope.launch {
            val gone = withContext(Dispatchers.IO) {
                list.filter { song ->
                    val deleted = runCatching { File(song.path).delete() }.getOrDefault(false)
                    if (deleted) store.forget(song.id)
                    deleted
                }
            }
            if (gone.isEmpty()) {
                status = if (list.size == 1) {
                    "לא הצלחתי למחוק את הקובץ"
                } else {
                    "לא הצלחתי למחוק אף קובץ"
                }
                return@launch
            }
            // Out of the queue too, or the player would walk into a file
            // that is no longer there.
            val removed = gone.mapTo(HashSet()) { it.id }
            val without = queue.filterNot { it.id in removed }
            if (without.size != queue.size) {
                val playing = queue.getOrNull(queueIndex)
                queue = without
                queueIndex = without.indexOfFirst { it.id == playing?.id }
                if (playing != null && playing.id in removed) {
                    if (without.isEmpty()) {
                        queueIndex = -1
                        player.stop()
                    } else {
                        play(without, 0)
                    }
                }
            }
            reload()
            val failed = list.size - gone.size
            status = when {
                list.size == 1 -> "${gone[0].title} נמחק"
                failed == 0 -> "${gone.size} קבצים נמחקו"
                else -> "${gone.size} קבצים נמחקו · $failed לא נמחקו"
            }
        }
    }

    fun deleteSong(song: SongEntity) = deleteSongs(listOf(song))

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
                if (repeat == RepeatMode.ONE) {
                    play(queue, queueIndex, previousCompleted = true)
                    return@launch
                }
                val next = if (repeat == RepeatMode.ALL && queueIndex + 1 !in queue.indices) {
                    0
                } else {
                    queueIndex + 1
                }
                if (next in queue.indices) {
                    play(queue, next, previousCompleted = true)
                    return@launch
                }
                // The queue is finished. Keep going on what the engine
                // suggests, rather than stopping dead in silence.
                //
                // `continuation` rather than `radio`, which is the same
                // engine asked the question that actually fits: a radio is
                // built outward from one song and knows nothing about what
                // has already been queued, so it happily offers back the
                // track that just finished and the four before it. A
                // continuation is told what has been heard and what is
                // already in the queue, and leans hardest on which song has
                // historically followed which.
                val from = queue.getOrNull(queueIndex)
                val station = if (prefs.autoRadio && from != null) {
                    val recent = queue.take(queueIndex + 1).asReversed().map { it.id }
                    engine?.continuation(recent, queue.mapTo(HashSet()) { it.id }).orEmpty()
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

    // Read once per composition: the map lives in the settings table, and
    // asking it for every control on every frame would be a query inside
    // layout.
    val actionPrefs = remember(showPlayer) { prefs.playerActions }
    val placement: (PlayerAction) -> ActionPlacement = remember(actionPrefs) {
        { action ->
            // Two of the phone's actions have nothing behind them here - see
            // DESKTOP_PLAYER_ACTIONS - so they are hidden whatever is stored,
            // rather than drawn as a button that does nothing when an old
            // arrangement or a shared database says they were on.
            if (action in DESKTOP_PLAYER_ACTIONS) {
                PlayerAction.placementOf(actionPrefs, action)
            } else {
                ActionPlacement.HIDDEN
            }
        }
    }
    // The words for what is playing, read off the disk when the track
    // changes rather than held for the whole library.
    var playerWords by remember { mutableStateOf<Words?>(null) }
    // Bumped when the editor saves, so the panel behind it redraws with what
    // was just written instead of what the file said a moment ago.
    var lyricsRevision by remember { mutableStateOf(0) }
    LaunchedEffect(current?.id, showPlayer, lyricsRevision) {
        val song = current
        playerWords = if (song == null || !showPlayer) {
            null
        } else {
            withContext(Dispatchers.IO) {
                // What someone typed wins over what the file says: they
                // typed it because the file was wrong or empty.
                val saved = store.lyrics(song.id)
                if (saved != null) {
                    val (plain, lrc) = saved
                    Words(plain = plain, lrc = lrc)
                } else {
                    SongLyrics.find(song, prefs.lyricsFolder)
                }
            }
        }
    }

    // Above the player on purpose.
    //
    // The player takes the whole window and returns from this function when
    // it is open, so anything composed after it is not composed at all while
    // something is playing full screen - which is exactly where the three
    // dot menu was, and why it did nothing there.

    // One dialog for the whole app rather than one per list. Opening an
    // artist or an album from it navigates, so it has to be able to reach the
    // same stack every screen is drawn from.
    options?.let { song ->
        SongOptionsDialog(
            song = song,
            stat = stats[song.id],
            feature = features[song.id],
            playlists = library.playlists,
            scoreTerms = remember(song.id, engine) { engine?.explain(song).orEmpty() },
            totalScore = remember(song.id, engine) { engine?.totalScore(song) ?: 0.0 },
            // Only when the menu was opened from inside a list, which is the
            // only place taking a song off one means anything.
            inPlaylist = (stack.lastOrNull() as? Route.Detail)?.list?.playlistId,
            onDismiss = { options = null },
            onRate = { rate(song, it) },
            onRadio = { startRadio(song) },
            onMix = { createMix(song) },
            onRemoveFromPlaylist = {
                (stack.lastOrNull() as? Route.Detail)?.list?.playlistId?.let {
                    removeFromPlaylist(it, song)
                }
            },
            onStyles = { setSongStyles(song, it) },
            onGenre = { setGenre(song, it) },
            onSpoken = { setSpoken(song, it) },
            onResetPlays = { resetPlayCount(song) },
            onDelete = { deleteSong(song) },
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
            onLyrics = {
                showPlayer = false
                stack = stack + Route.Lyrics(song.id)
            },
            onBookmarks = { bookmarksOpen = true },
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

    if (showPlayer && current != null) {
        PlayerScreen(
            song = current,
            feature = features[current.id],
            stat = stats[current.id],
            positionMs = state.positionMs,
            durationMs = state.durationMs,
            playing = state.playing,
            placement = placement,
            tapArtwork = prefs.tapArtworkToggles,
            queue = queue,
            queueIndex = queueIndex,
            words = playerWords,
            scoreTerms = remember(current.id, engine) {
                engine?.explain(current).orEmpty()
            },
            totalScore = remember(current.id, engine) { engine?.totalScore(current) ?: 0.0 },
            onRadio = { startRadio(current) },
            onEqualizer = { stack = stack + Route.Equalizer; showPlayer = false },
            onBookmarks = { bookmarksOpen = true },
            onJumpTo = { play(queue, it) },
            onRemoveFromQueue = { position ->
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
            // Only for a song that was left properly in the middle, and only
            // when the setting is on. The long-form auto-resume above has
            // already jumped by the time this would have been offered, so the
            // two never both speak about the same song.
            resumeAt = if (prefs.resumePrompt) resumePoints[current.id] else null,
            onSaveLyrics = { plain, lrc ->
                val id = current.id
                scope.launch {
                    withContext(Dispatchers.IO) { store.setLyrics(id, plain, lrc) }
                    lyricsRevision++
                    status = if (plain.isBlank() && lrc.isBlank()) {
                        "המילים נמחקו"
                    } else {
                        "המילים נשמרו"
                    }
                }
            },
            onClose = { showPlayer = false },
            onToggle = { player.togglePause() },
            onPrevious = { play(queue, queueIndex - 1) },
            onNext = { play(queue, queueIndex + 1) },
            onSeek = { player.seekTo(it) },
            onLike = { like(current) },
            onDislike = { dislike(current) },
            onRate = { rate(current, it) },
            shuffling = shuffling,
            repeat = repeat,
            onShuffle = { toggleShuffle() },
            onRepeat = { cycleRepeat() },
            sleepArmed = SleepTimer.remainingMs() != null || SleepTimer.stopAfterTrack,
            onSleep = { sleepOpen = true },
            onMore = { options = current }
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
                    },
                    onImportPlaylist = { choosePlaylistFile()?.let { importPlaylist(it) } },
                    onExportPlaylists = { chooseFolder()?.let { exportPlaylists(it) } },
                    busy = busy,
                    engineReport = engineReport,
                    // Cheap enough to derive on the spot: it is a few sums
                    // over maps the recommender is already holding.
                    taste = remember(engine) { engine?.tasteReport() },
                    onEvaluate = { evaluateEngine() },
                    onExcludedChanged = { scan(folders) },
                    onShelvesChanged = { scope.launch { reload() } }
                )

                Route.PlayerSettings -> PlayerSettingsScreen(
                    prefs = prefs,
                    onBack = { stack = stack.dropLast(1) },
                    onOpenEqualizer = { stack = stack + Route.Equalizer }
                )
                Route.Equalizer -> EqualizerScreen(
                    prefs = prefs,
                    equalizer = player.equalizer,
                    onBack = { stack = stack.dropLast(1) }
                )

                Route.Algorithm -> AlgorithmSettingsScreen(
                    tuning = tuning,
                    learning = learning,
                    learningReport = learningReport,
                    busy = busy,
                    onChange = { retune(it) },
                    onLearn = { learnStyles() },
                    onClearLearned = { clearLearnedStyles() },
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
                    0 -> HomeScreen(
                        feed = feed,
                        songCount = songs.size,
                        ratedArtists = artists.count { it.rating > 0 },
                        albums = library.albums,
                        stats = stats,
                        moods = if (prefs.pinMoodRow) Mood.entries.toList() else emptyList(),
                        scanning = scanning,
                        analysing = analysing,
                        unanalysed = songs.count { it.id !in features },
                        status = status,
                        hasFolders = folders.isNotEmpty(),
                        // The one library shape where every other nudge is
                        // pointless: everything filed under one name.
                        singleArtist = library.artists.size <= 2 && songs.size >= 8,
                        tagTipVisible = tagTipVisible,
                        ratingTipVisible = ratingTipVisible,
                        onMood = { openMood(it) },
                        onRefresh = {
                            scope.launch {
                                withContext(Dispatchers.IO) { store.feedSeed = store.feedSeed + 1 }
                                reload()
                            }
                        },
                        onRecap = {
                            loadRecap()
                            stack = stack + Route.Recap
                        },
                        onSettings = { openSettings() },
                        onPickFolder = { chooseFolder()?.let { scan(listOf(it)) } },
                        onRescan = { scan(folders) },
                        onAnalyze = { analyze() },
                        onRateArtists = { go(3) },
                        onStopAnalysis = { stopAnalysis() },
                        onDismissTagTip = {
                            tagTipVisible = false
                            scope.launch { withContext(Dispatchers.IO) { prefs.tagTipSeen = true } }
                        },
                        onDismissRatingTip = {
                            ratingTipVisible = false
                            scope.launch { withContext(Dispatchers.IO) { prefs.ratingTipSeen = true } }
                        },
                        onPlay = { list, index -> play(list, index) },
                        onOpenList = { stack = stack + Route.Detail(it) },
                        onMore = { options = it }
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
                        library = library,
                        stats = stats,
                        current = current?.id,
                        onPlay = { list, index -> play(list, index) },
                        onLike = { like(it) },
                        onDislike = { dislike(it) },
                        onMore = { options = it },
                        onOpenList = { stack = stack + Route.Detail(it) }
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
                        onDeletePlaylist = { deletePlaylist(it) },
                        // Longest first, because the long ones are what this
                        // shelf exists for.
                        spoken = remember(library.songs, features, stats) {
                            library.songs.filter { song ->
                                val feature = features[song.id]
                                val tags = feature?.tags?.let {
                                    AudioTags.pick(it, AudioTags.SPEECH_INDICES)
                                }
                                Spoken.isSpoken(
                                    song, feature, tags, stats[song.id]?.spoken ?: -1
                                )
                            }.sortedByDescending { it.durationMs }
                        },
                        resumePoints = resumePoints,
                        firstTab = prefs.libraryFirstTab,
                        folderTree = prefs.folderTree,
                        onShuffle = { shuffleList(it) },
                        onBulkRate = { ids, rating ->
                            scope.launch {
                                stats = withContext(Dispatchers.IO) {
                                    store.bulkSetRating(ids, rating)
                                    store.stats()
                                }
                                status = "דורגו ${ids.size} שירים"
                            }
                        },
                        onBulkLike = { ids ->
                            scope.launch {
                                stats = withContext(Dispatchers.IO) {
                                    store.bulkSetLike(ids, 1)
                                    store.stats()
                                }
                                status = "${ids.size} שירים סומנו באהבתי"
                            }
                        },
                        onBulkQueue = { list ->
                            if (queueIndex < 0) {
                                play(list, 0)
                            } else {
                                queue = queue + list
                                status = "${list.size} שירים נוספו לתור"
                            }
                        },
                        onBulkGenre = { ids, genre ->
                            scope.launch {
                                withContext(Dispatchers.IO) { store.setGenre(ids, genre) }
                                reload()
                                status = if (genre.isBlank()) {
                                    "הז'אנר נוקה מ־${ids.size} שירים"
                                } else {
                                    "$genre הוגדר ל־${ids.size} שירים"
                                }
                            }
                        },
                        onBulkDelete = { deleteSongs(it) },
                        onBulkAddTo = { playlistId, ids ->
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    store.bulkAddToPlaylist(playlistId, ids)
                                }
                                reload()
                                status = "${ids.size} שירים נוספו לרשימה"
                            }
                        }
                    )
                    else -> ArtistsPane(
                        artists = library.artists,
                        onOpen = { stack = stack + Route.Artist(it.key) },
                        onBulkUpdate = { keys, rating, styles, replace ->
                            scope.launch {
                                // The display names come from the library
                                // rather than the keys, so an artist who has
                                // no row yet is created with their real name
                                // and not a normalised one.
                                val names = library.artists.associate {
                                    it.key to it.displayName
                                }
                                withContext(Dispatchers.IO) {
                                    store.bulkUpdateArtists(
                                        keys, names, rating, styles, replace
                                    )
                                }
                                reload()
                                status = "עודכנו ${keys.size} אמנים"
                            }
                        },
                        onBulkImport = { text ->
                            scope.launch {
                                val n = withContext(Dispatchers.IO) {
                                    store.importArtistLines(text)
                                }
                                reload()
                                status = "עודכנו $n אמנים"
                            }
                        }
                    )
                }
            }
        }

        MiniPlayer(
            song = current,
            positionMs = state.positionMs,
            durationMs = state.durationMs,
            playing = state.playing,
            liked = current?.let { stats[it.id]?.liked } ?: 0,
            volume = volume,
            onVolume = {
                volume = it
                player.setVolume(it)
                prefs.volume = (it * 100).toInt()
            },
            onLike = { current?.let { like(it) } },
            onOpen = { if (current != null) showPlayer = true },
            onToggle = { player.togglePause() },
            onNext = { play(queue, queueIndex + 1) }
        )

        // The same four the phone has, in the same order, with the same icons
        // and the same words. There is no fifth: settings open from the gear
        // in the corner of the home screen, exactly as on the phone, and a
        // tab for something opened twice a year would take a quarter of the
        // bar away from the four that are the app.
        NavigationBar(containerColor = Color.Transparent) {
            NavTab(tab, 0, "בית", Icons.Filled.Home) { go(0) }
            NavTab(tab, 1, "חיפוש", Icons.Filled.Search) { go(1) }
            NavTab(tab, 2, "ספרייה", Icons.Filled.LibraryMusic) { go(2) }
            NavTab(tab, 3, "אמנים", Icons.Filled.Star) { go(3) }
        }
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
            indicatorColor = Color.Transparent,
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
/**
 * What happens when a track ends.
 *
 * OFF moves on, ONE plays the same file again, ALL wraps the queue back to
 * its start instead of letting it run out.
 */
internal enum class RepeatMode { OFF, ALL, ONE }

private sealed interface Route {
    data class Detail(val list: DetailList) : Route
    data class Artist(val key: String) : Route
    data object Albums : Route
    data object Settings : Route
    data object PlayerSettings : Route
    data object Equalizer : Route
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

/**
 * Below this, nothing happened worth writing down.
 *
 * Three seconds is someone landing on the wrong track and moving off it. It
 * is not a skip - a skip is a judgement about a song, and nobody judges a
 * song in three seconds - and counting it as one would let a few mis-clicks
 * bury a track the listener actually likes.
 */
private const val MIN_MEASURABLE_MS = 3_000L

/** Half the track, or a minute and a half, is a play. */
private const val PLAY_FRACTION = 0.5

private const val PLAY_MS = 90_000L

/**
 * What to do when the length is not known.
 *
 * A decoded stream often cannot say how long it is, and a fraction of an
 * unknown length is not a number. A flat minute is the fallback, and it is
 * the same one the phone uses for the same reason.
 */
private const val PLAY_MS_UNKNOWN_LENGTH = 60_000L

/** How long a pair of songs may be apart and still count as a sequence. */
private const val TRANSITION_WINDOW_MS = 15 * 60 * 1000L

/** A gap this long ends a sitting, and the pairings it was accumulating. */
private const val SESSION_GAP_MS = 40 * 60 * 1000L

/** How far back a new play is linked. Beyond this the link means little. */
private const val SESSION_TAIL = 5

/**
 * Whether a track was listened to, as opposed to passed over.
 *
 * The same rule the phone measures by, and it has to be the same rule: the
 * recommender divides skips by attempts, and two builds that disagree about
 * what an attempt is will rank the same library differently. Half the track
 * or ninety seconds, whichever comes first, so a four minute song needs two
 * minutes and an hour of speech needs ninety seconds rather than half an
 * hour.
 *
 * A track that played to its end is a play whatever its length, which is the
 * case the thresholds cannot see: a forty second interlude never reaches
 * either bar and was still heard in full.
 */
internal fun countsAsPlay(heardMs: Long, durationMs: Long, endedOnItsOwn: Boolean): Boolean {
    if (heardMs < MIN_MEASURABLE_MS) return false
    if (endedOnItsOwn) return true
    if (durationMs <= 0L) return heardMs >= PLAY_MS_UNKNOWN_LENGTH
    return heardMs >= durationMs * PLAY_FRACTION || heardMs >= PLAY_MS
}

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
            Names.looksLikeRecording(
                it.folder,
                it.path.substringAfterLast(File.separatorChar),
                it.durationMs,
                Names.hasRealArtist(it.artistName)
            )
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
private fun SearchPane(
    query: String,
    onQuery: (String) -> Unit,
    results: List<SongEntity>,
    library: LibraryModel,
    stats: Map<Long, SongStatsEntity>,
    current: Long?,
    onPlay: (List<SongEntity>, Int) -> Unit,
    onLike: (SongEntity) -> Unit,
    onDislike: (SongEntity) -> Unit,
    onMore: (SongEntity) -> Unit,
    onOpenList: (DetailList) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            label = { Text("חיפוש שיר, אמן או אלבום") },
            singleLine = true,
            leadingIcon = {
                Icon(Icons.Filled.Search, contentDescription = null, tint = TextSecondary)
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQuery("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "נקה", tint = TextSecondary)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().padding(GUTTER)
        )
        if (query.isBlank()) {
            // Nothing typed yet, so the screen offers the ways in that do not
            // need a name remembered. The phone has the same three, and the
            // same row of styles under them - which come from the tags the
            // user typed onto artists, so the list is theirs rather than a
            // fixed genre list.
            BrowsePane(
                library = library,
                stats = stats,
                onOpenList = onOpenList
            )
            return@Column
        }
        SongList(
            songs = results,
            stats = stats,
            current = current,
            empty = "לא נמצא כלום",
            onPlay = { index -> onPlay(results, index) },
            onLike = onLike,
            onDislike = onDislike,
            onMore = onMore
        )
    }
}

/** The ways into a library that do not start with remembering a name. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BrowsePane(
    library: LibraryModel,
    stats: Map<Long, SongStatsEntity>,
    onOpenList: (DetailList) -> Unit
) {
    val liked = library.liked(stats)
    val unheard = library.songs.filter { (stats[it.id]?.playCount ?: 0) == 0 }
    val played = library.songs
        .filter { (stats[it.id]?.playCount ?: 0) > 0 }
        .sortedByDescending { stats[it.id]?.playCount ?: 0 }
    val styles = library.artists
        .flatMap { Styles.parse(it.styles) }
        .groupingBy { it }
        .eachCount()
        .entries
        .sortedByDescending { it.value }
        .take(18)

    LazyColumn(contentPadding = PaddingValues(bottom = 40.dp)) {
        item { SectionHeader("עיון מהיר", null) }
        item {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = GUTTER),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Chip(label = "השירים האהובים · ${liked.size}", selected = false) {
                    onOpenList(
                        DetailList("אהובים", "כל מה שסימנת בלייק", liked, "auto:liked")
                    )
                }
                Chip(label = "עדיין לא שמעת · ${unheard.size}", selected = false) {
                    onOpenList(DetailList("עדיין לא שמעת", null, unheard, "unheard"))
                }
                Chip(label = "הכי מושמעים · ${played.size}", selected = false) {
                    onOpenList(DetailList("הכי מושמעים", null, played, "top"))
                }
            }
        }
        if (styles.isNotEmpty()) {
            item {
                SectionHeader("לפי סגנון", "מגיע מהתגיות שהגדרת לאמנים")
            }
            item {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = GUTTER, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (entry in styles) {
                        val style = entry.key
                        Chip(label = style, selected = false) {
                            val lower = style.lowercase(Locale.ROOT)
                            val keys = library.artists
                                .filter { a ->
                                    Styles.parse(a.styles)
                                        .any { it.lowercase(Locale.ROOT) == lower }
                                }
                                .mapTo(HashSet()) { it.key }
                            val list = library.songs.filter { it.artistKey in keys }
                            onOpenList(
                                DetailList(
                                    "סגנון: $style",
                                    "${list.size} שירים",
                                    list,
                                    "style:$style"
                                )
                            )
                        }
                    }
                }
            }
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
    placement: (PlayerAction) -> ActionPlacement,
    tapArtwork: Boolean,
    queue: List<SongEntity>,
    queueIndex: Int,
    words: Words?,
    scoreTerms: List<ScoreTerm>,
    totalScore: Double,
    onClose: () -> Unit,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onLike: () -> Unit,
    onDislike: () -> Unit,
    onRate: (Int) -> Unit,
    shuffling: Boolean,
    repeat: RepeatMode,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    sleepArmed: Boolean,
    onSleep: () -> Unit,
    onMore: () -> Unit,
    onRadio: () -> Unit,
    onEqualizer: () -> Unit,
    onBookmarks: () -> Unit,
    onJumpTo: (Int) -> Unit,
    onRemoveFromQueue: (Int) -> Unit,
    resumeAt: Long?,
    onSaveLyrics: (String, String) -> Unit
) {
    var scrub by remember { mutableStateOf<Float?>(null) }
    // The two panels the phone opens inside the player rather than beside it:
    // the queue and the words. Only one at a time, because both want the
    // whole sheet and neither is any use at half of it.
    var showQueue by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    var editingLyrics by remember(song.id) { mutableStateOf(false) }
    // An offer, not a jump. It is keyed to the song so opening a different
    // one gets its own offer, and it takes itself away after a few seconds:
    // a strip that stays forever is permanent clutter, and the moment for
    // this one has passed once the song is properly under way.
    var resumeVisible by remember(song.id) { mutableStateOf(resumeAt != null) }
    LaunchedEffect(song.id, resumeAt) {
        if (resumeAt == null) return@LaunchedEffect
        delay(8_000)
        resumeVisible = false
    }
    var whyOpen by remember { mutableStateOf(false) }
    var detailsOpen by remember { mutableStateOf(false) }
    val liked = stat?.liked ?: 0
    val rating = stat?.rating ?: 0
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "סגור")
            }
            // No "now playing" caption: the cover, the title and the transport
            // directly below already say it.
            Spacer(Modifier.weight(1f))
            // The menu is always here. It is the one control that cannot be
            // switched off, because it is what anything switched off the
            // header goes into.
            IconButton(onClick = onMore) {
                Icon(Icons.Filled.MoreVert, contentDescription = "עוד", tint = TextSecondary)
            }
            if (placement(PlayerAction.SLEEP) == ActionPlacement.BUTTON) {
                IconButton(onClick = onSleep) {
                    Icon(
                        Icons.Filled.Bedtime,
                        contentDescription = "טיימר שינה",
                        tint = if (sleepArmed) Accent else TextSecondary
                    )
                }
            }
            if (placement(PlayerAction.LYRICS) == ActionPlacement.BUTTON) {
                IconButton(onClick = {
                    showLyrics = !showLyrics
                    if (showLyrics) showQueue = false
                }) {
                    Icon(
                        Icons.Filled.FormatQuote,
                        contentDescription = "מילות השיר",
                        tint = if (showLyrics) Accent else TextSecondary
                    )
                }
            }
            if (placement(PlayerAction.QUEUE) == ActionPlacement.BUTTON) {
                IconButton(onClick = {
                    showQueue = !showQueue
                    if (showQueue) showLyrics = false
                }) {
                    Icon(
                        Icons.AutoMirrored.Filled.QueueMusic,
                        contentDescription = "תור",
                        tint = if (showQueue) Accent else TextSecondary
                    )
                }
            }
        }
        when {
            showQueue -> QueuePanel(
                queue = queue,
                index = queueIndex,
                modifier = Modifier.weight(1f),
                onPlay = onJumpTo,
                onRemove = onRemoveFromQueue
            )
            showLyrics -> LyricsPanel(
                words = words,
                positionMs = positionMs,
                modifier = Modifier.weight(1f),
                onEdit = { editingLyrics = true },
                onSeek = onSeek
            )
            else -> Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Art(
                    song = song,
                    size = 300.dp,
                    corner = 12.dp,
                    modifier = Modifier
                        .pointerInput(song.id, tapArtwork) {
                            if (!tapArtwork) return@pointerInput
                            // The cover is the biggest thing on the screen and
                            // the easiest thing to hit without looking, which
                            // is most of why people want this.
                            detectTapGestures(onTap = { onToggle() })
                        }
                        .pointerInput(song.id) {
                            var drag = 0f
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    // RTL: dragging right goes forward.
                                    if (drag > 70f) onNext() else if (drag < -70f) onPrevious()
                                    drag = 0f
                                }
                            ) { _, amount -> drag += amount }
                        }
                )
            }
        }
        if (!showQueue) {
            Text(
                song.title,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 12.dp)
            )
            Text(
                song.artistName.ifEmpty { "ללא אמן" },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // What you can do to the song that is playing, on one line. It
            // scrolls sideways rather than wrapping, so a narrow window can
            // still reach the last button.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (placement(PlayerAction.LIKE) == ActionPlacement.BUTTON) {
                    IconButton(onClick = onDislike) {
                        Icon(
                            if (liked == -1) Icons.Filled.ThumbDown else Icons.Outlined.ThumbDown,
                            contentDescription = if (liked == -1) "בטל דיסלייק" else "דיסלייק",
                            tint = if (liked == -1) Accent else TextSecondary
                        )
                    }
                    IconButton(onClick = onLike) {
                        Icon(
                            if (liked == 1) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                            contentDescription = if (liked == 1) "בטל לייק" else "לייק",
                            tint = if (liked == 1) Accent else TextSecondary
                        )
                    }
                }
                if (placement(PlayerAction.RADIO) == ActionPlacement.BUTTON) {
                    IconButton(onClick = onRadio) {
                        Icon(
                            Icons.Filled.Radio,
                            contentDescription = "התחל רדיו מהשיר",
                            tint = TextSecondary
                        )
                    }
                }
                if (placement(PlayerAction.DETAILS) == ActionPlacement.BUTTON) {
                    IconButton(onClick = { detailsOpen = true }) {
                        Icon(
                            Icons.Filled.Info,
                            contentDescription = "פרטי השיר",
                            tint = TextSecondary
                        )
                    }
                }
                if (placement(PlayerAction.WHY) == ActionPlacement.BUTTON) {
                    IconButton(onClick = { whyOpen = true }) {
                        Icon(
                            Icons.Filled.Insights,
                            contentDescription = "למה זה הומלץ",
                            tint = TextSecondary
                        )
                    }
                }
                if (placement(PlayerAction.EQUALIZER) == ActionPlacement.BUTTON) {
                    IconButton(onClick = onEqualizer) {
                        Icon(
                            Icons.Filled.GraphicEq,
                            contentDescription = "אקולייזר",
                            tint = TextSecondary
                        )
                    }
                }
                if (placement(PlayerAction.BOOKMARK) == ActionPlacement.BUTTON) {
                    IconButton(onClick = onBookmarks) {
                        Icon(
                            Icons.Filled.BookmarkBorder,
                            contentDescription = "סימניות",
                            tint = TextSecondary
                        )
                    }
                }
            }
            if (placement(PlayerAction.RATING) == ActionPlacement.BUTTON) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Without this the stars sit directly under the artist
                    // line and read as a rating of the artist, which is a
                    // different thing the app also offers.
                    Text(
                        "דירוג השיר",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )
                    Spacer(Modifier.width(8.dp))
                    StarRow(rating = rating, onRate = onRate, size = 20)
                    Spacer(Modifier.width(10.dp))
                    feature?.let { f ->
                        Text(
                            // Key only. The modal estimate drives the engine
                            // but reads as jargon on screen.
                            "${f.bpm.toInt()} BPM · ${Features.keyLabel(f.musicalKey, f.mode)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }
            }
        }
        // Time runs one way whatever the language, so the scrubber and the
        // transport keep the left to right reading every media player uses:
        // the head advances rightwards, elapsed sits under its start, and
        // "previous" stays to the left of "next". Without this the whole row
        // mirrors with the rest of the app and the skip arrows point at the
        // wrong songs.
        if (resumeVisible && resumeAt != null && !showQueue && !showLyrics) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Surface1)
                    .clickable {
                        onSeek(resumeAt)
                        resumeVisible = false
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "המשך מ־${formatDuration(resumeAt)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Accent
                )
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = { resumeVisible = false },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "סגור",
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            if (!showQueue) {
                Column(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    Slider(
                        value = scrub ?: positionMs.toFloat(),
                        valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
                        onValueChange = { scrub = it },
                        onValueChangeFinished = {
                            scrub?.let { onSeek(it.toLong()) }
                            scrub = null
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = Accent,
                            activeTrackColor = Accent,
                            inactiveTrackColor = Surface1
                        )
                    )
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            clock(scrub?.toLong() ?: positionMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            clock(durationMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onShuffle) {
                    Icon(
                        Icons.Filled.Shuffle,
                        contentDescription = "ערבוב",
                        tint = if (shuffling) Accent else TextSecondary
                    )
                }
                if (placement(PlayerAction.SEEK) == ActionPlacement.BUTTON) {
                    IconButton(onClick = { onSeek((positionMs - 10_000L).coerceAtLeast(0L)) }) {
                        Icon(
                            Icons.Filled.Replay10,
                            contentDescription = "אחורה 10 שניות",
                            tint = TextSecondary
                        )
                    }
                }
                IconButton(onClick = onPrevious) {
                    Icon(
                        Icons.Filled.SkipPrevious,
                        contentDescription = "הקודם",
                        // Without an explicit tint these two inherit a colour
                        // near the background and read as missing.
                        tint = TextPrimary,
                        modifier = Modifier.size(40.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(66.dp)
                        .clip(CircleShape)
                        .background(Accent)
                        .clickable(onClick = onToggle),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playing) "השהה" else "נגן",
                        tint = Color.White,
                        modifier = Modifier.size(34.dp)
                    )
                }
                IconButton(onClick = onNext) {
                    Icon(
                        Icons.Filled.SkipNext,
                        contentDescription = "הבא",
                        tint = TextPrimary,
                        modifier = Modifier.size(40.dp)
                    )
                }
                if (placement(PlayerAction.SEEK) == ActionPlacement.BUTTON) {
                    IconButton(onClick = { onSeek(positionMs + 10_000L) }) {
                        Icon(
                            Icons.Filled.Forward10,
                            contentDescription = "קדימה 10 שניות",
                            tint = TextSecondary
                        )
                    }
                }
                IconButton(onClick = onRepeat) {
                    Icon(
                        if (repeat == RepeatMode.ONE) Icons.Filled.RepeatOne
                        else Icons.Filled.Repeat,
                        contentDescription = "חזרה",
                        tint = if (repeat == RepeatMode.OFF) TextSecondary else Accent
                    )
                }
            }
        }
    }
    if (whyOpen) {
        WhyDialog(
            title = song.title,
            terms = scoreTerms,
            total = totalScore,
            onDismiss = { whyOpen = false }
        )
    }
    if (detailsOpen) {
        SongDetailsDialog(song = song, feature = feature, onDismiss = { detailsOpen = false })
    }
    if (editingLyrics) {
        LyricsEditorDialog(
            initial = words?.lrc?.takeIf { it.isNotBlank() } ?: words?.plain.orEmpty(),
            positionMs = positionMs,
            playing = playing,
            onTogglePlay = onToggle,
            onDismiss = { editingLyrics = false },
            onSave = { plain, lrc ->
                onSaveLyrics(plain, lrc)
                editingLyrics = false
            }
        )
    }
}

/**
 * Typing, pasting or timing a song's words.
 *
 * Two modes in one dialog, because they are two halves of the same job. The
 * first is a text box: paste what you have, whether that is plain words or a
 * whole LRC file with timestamps already in it - which of the two it is is
 * decided by looking at the text rather than by asking.
 *
 * The second times the lines against the song as it plays. One button, one
 * line at a time, pressed on the beat: this is the only way timings get made
 * that is not slower than writing them out by hand, and it is why the dialog
 * can keep playing underneath itself.
 */
@Composable
private fun LyricsEditorDialog(
    initial: String,
    positionMs: Long,
    playing: Boolean,
    onTogglePlay: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    var syncing by remember { mutableStateOf(false) }
    var stampIndex by remember { mutableStateOf(0) }
    var stamps by remember { mutableStateOf(listOf<LyricLine>()) }

    val lines = remember(text) {
        // Stripped first, so timing an LRC that is already timed starts from
        // its words rather than from its timestamps.
        Lyrics.stripTimestamps(text).lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        title = { Text(if (syncing) "סנכרון שורות" else "מילות השיר") },
        text = {
            if (!syncing) {
                Column(modifier = Modifier.heightIn(max = 380.dp)) {
                    Text(
                        "אפשר להדביק כאן טקסט רגיל, או קובץ LRC שלם עם חותמות זמן.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 280.dp)
                    )
                }
            } else {
                Column(modifier = Modifier.heightIn(max = 380.dp)) {
                    Text(
                        "השיר מתנגן — לחיצה על \"סמן\" מצמידה את הזמן הנוכחי לשורה המסומנת.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = lines.getOrNull(stampIndex) ?: "הסתיים",
                        style = MaterialTheme.typography.titleMedium,
                        color = Accent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Surface2)
                            .padding(12.dp),
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "$stampIndex מתוך ${lines.size} שורות סומנו",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val line = lines.getOrNull(stampIndex) ?: return@Button
                                stamps = stamps + LyricLine(positionMs, line)
                                stampIndex++
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Accent)
                        ) { Text("סמן") }
                        // A mistimed line is the normal case, not the
                        // exception, so undo is a first class button rather
                        // than a reason to start the song again.
                        OutlinedButton(onClick = {
                            if (stampIndex > 0) {
                                stampIndex--
                                stamps = stamps.dropLast(1)
                            }
                        }) { Text("אחורה") }
                        OutlinedButton(onClick = onTogglePlay) {
                            Text(if (playing) "עצור" else "נגן")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (syncing) {
                    onSave(Lyrics.stripTimestamps(text).trim(), Lyrics.buildLrc(stamps))
                } else {
                    // What was pasted decides which of the two it is. Someone
                    // who pastes an LRC has already done the timing and
                    // should not be asked to say so.
                    val looksLikeLrc = text.contains('[') &&
                        Regex("\\[\\d{1,2}:\\d{2}").containsMatchIn(text)
                    onSave(
                        if (looksLikeLrc) Lyrics.stripTimestamps(text) else text.trim(),
                        if (looksLikeLrc) text else ""
                    )
                }
            }) { Text("שמור", color = Accent) }
        },
        dismissButton = {
            Row {
                if (!syncing && lines.isNotEmpty()) {
                    TextButton(onClick = {
                        syncing = true
                        stampIndex = 0
                        stamps = emptyList()
                    }) { Text("סנכרן", color = TextSecondary) }
                }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onDismiss) { Text("ביטול", color = TextSecondary) }
            }
        }
    )
}

/**
 * What follows what, inside the player.
 *
 * The phone shows the queue here rather than only as a screen of its own,
 * because "what is next" is a question asked while looking at what is
 * playing. The full screen version is still reachable from the menu.
 */
@Composable
private fun QueuePanel(
    queue: List<SongEntity>,
    index: Int,
    modifier: Modifier = Modifier,
    onPlay: (Int) -> Unit,
    onRemove: (Int) -> Unit
) {
    if (queue.isEmpty()) {
        Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text("התור ריק", style = MaterialTheme.typography.bodyLarge, color = TextSecondary)
        }
        return
    }
    val listState = rememberLazyListState()
    // Open on what is playing rather than at the top, but only on open:
    // keying this to the contents would yank the list back on every removal.
    LaunchedEffect(Unit) {
        if (index in queue.indices) listState.scrollToItem(index)
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 8.dp)
    ) {
        itemsIndexed(queue, key = { position, song -> "$position:${song.id}" }) { position, song ->
            // The one place the phone separates what you queued from what the
            // radio appended, so the heading says which is which.
            if (position == index + 1) {
                Text(
                    "הבא בתור",
                    style = MaterialTheme.typography.labelLarge,
                    color = TextSecondary,
                    modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp)
                )
            }
            SongRow(
                song = song,
                isCurrent = position == index,
                onClick = { onPlay(position) },
                trailing = {
                    IconButton(
                        onClick = { onRemove(position) },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "הסר מהתור",
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            )
        }
    }
}

/**
 * The words, inside the player, following the song.
 *
 * The same panel the phone opens over its artwork: when there are
 * timestamps the current line is lit and the list scrolls to it, and
 * pressing a line jumps to its moment.
 */
@Composable
private fun LyricsPanel(
    words: Words?,
    positionMs: Long,
    modifier: Modifier = Modifier,
    onEdit: () -> Unit,
    onSeek: (Long) -> Unit
) {
    if (words == null) {
        Column(
            modifier = modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                "אין מילים לשיר הזה",
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary
            )
            Spacer(Modifier.height(12.dp))
            // The way in when the file has nothing: paste what you have.
            // Without this the panel is a dead end on exactly the songs
            // that need it most.
            Button(
                onClick = onEdit,
                colors = ButtonDefaults.buttonColors(containerColor = Accent)
            ) { Text("ערוך / הדבק") }
        }
        return
    }
    val timed = remember(words.lrc) { Lyrics.parseLrc(words.lrc) }
    if (timed.isEmpty()) {
        Column(
            modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                words.plain,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onEdit) { Text("ערוך / הדבק", color = TextSecondary) }
        }
        return
    }
    val active = timed.indexOfLast { it.timeMs <= positionMs + 250 }
    val listState = rememberLazyListState()
    LaunchedEffect(active) {
        if (active >= 0) {
            // Kept a couple of lines down rather than at the top, so what is
            // about to be sung is visible too.
            runCatching { listState.animateScrollToItem(maxOf(0, active - 2)) }
        }
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(timed) { i, line ->
            Text(
                text = line.text.ifBlank { "♪" },
                style = if (i == active) MaterialTheme.typography.titleLarge
                else MaterialTheme.typography.bodyLarge,
                fontWeight = if (i == active) FontWeight.Bold else FontWeight.Normal,
                color = when {
                    i == active -> Accent
                    i < active -> TextTertiary
                    else -> TextSecondary
                },
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clickable { onSeek(line.timeMs) }
            )
        }
    }
}

/**
 * What the file actually is: where it lives, how long, and what the analyser
 * measured. The measured half is only there once the song has been analysed,
 * and saying so beats showing zeroes.
 */
@Composable
private fun SongDetailsDialog(
    song: SongEntity,
    feature: AudioFeatureEntity?,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        title = { Text("פרטי השיר") },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                DetailLine("שם", song.title)
                DetailLine("אמן", song.artistName.ifEmpty { "ללא אמן" })
                if (song.albumName.isNotBlank()) DetailLine("אלבום", song.albumName)
                DetailLine("אורך", formatDuration(song.durationMs))
                DetailLine("תיקייה", song.folder)
                DetailLine("קובץ", song.path.substringAfterLast(java.io.File.separatorChar))
                if (feature != null) {
                    Spacer(Modifier.height(8.dp))
                    DetailLine("קצב", "${feature.bpm.toInt()} BPM")
                    DetailLine("סולם", Features.modeLabel(feature))
                } else {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "השיר עדיין לא נותח, אז אין קצב וסולם להציג.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("סגור", color = Accent) } }
    )
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary,
            modifier = Modifier.width(70.dp)
        )
        Text(value, style = MaterialTheme.typography.bodySmall, maxLines = 3)
    }
}

/** The actual score terms the ranker used for this track. */
@Composable
internal fun WhyDialog(
    title: String,
    terms: List<ScoreTerm>,
    total: Double,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        title = { Text("למה \"$title\"") },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (terms.isEmpty()) {
                    Text(
                        "הפיד עוד לא נבנה. אחרי רענון יופיע כאן הפירוק המלא.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                } else {
                    Text("ניקוד כולל: %.2f".format(total), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    for (term in terms) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(term.label, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    term.detail,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                            }
                            Text(
                                (if (term.value >= 0) "+" else "") + "%.2f".format(term.value),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (term.value >= 0) Accent else TextSecondary
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("סגור", color = Accent) } }
    )
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
    liked: Int,
    volume: Float,
    onVolume: (Float) -> Unit,
    onLike: () -> Unit,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit
) {
    if (song == null) return
    Column(modifier = Modifier.fillMaxWidth().background(Surface1)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Art(song = song, size = 42.dp, corner = 7.dp)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    song.title,
                    style = MaterialTheme.typography.titleSmall,
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
            IconButton(onClick = onLike) {
                Icon(
                    imageVector = if (liked == 1) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                    contentDescription = "לייק",
                    tint = if (liked == 1) Accent else TextSecondary
                )
            }
            IconButton(onClick = onToggle) {
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = "נגן",
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
            // A volume of its own, which the phone has no need for: Android
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
        // Under the row, not over it, and matching the full player: elapsed
        // time grows rightwards regardless of the language, so the two views
        // never disagree about which way the song runs.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            LinearProgressIndicator(
                progress = {
                    if (durationMs > 0) {
                        (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = Accent,
                trackColor = Surface1
            )
        }
    }
}

private fun clock(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}

/**
 * How big to open, given the screen it is opening on.
 *
 * A fixed size is wrong in both directions. 1100 by 760 is most of a laptop
 * screen and a corner of a desk monitor, and on a display running at 150%
 * scaling it is larger than the desktop it sits on - which is how this opened
 * "ענק מידיי", filling the screen edge to edge.
 *
 * Toolkit reports the screen in the same scaled units Compose measures dp in,
 * so the two can simply be compared. A share of the screen with a ceiling:
 * never bigger than a comfortable window, never wider than the screen.
 */
private fun windowSize(): Pair<Dp, Dp> {
    val screen = runCatching { Toolkit.getDefaultToolkit().screenSize }.getOrNull()
        ?: return 1100.dp to 760.dp
    val width = minOf(1180f, screen.width * 0.82f).coerceAtLeast(880f)
    val height = minOf(800f, screen.height * 0.86f).coerceAtLeast(620f)
    return width.dp to height.dp
}

/** The system's own file picker, filtered to the two playlist formats. */
private fun choosePlaylistFile(): File? {
    val chooser = JFileChooser().apply {
        fileSelectionMode = JFileChooser.FILES_ONLY
        dialogTitle = "בחר קובץ רשימת השמעה"
        fileFilter = FileNameExtensionFilter("רשימות השמעה (m3u, m3u8, pls)", "m3u", "m3u8", "pls")
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile
    } else {
        null
    }
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
