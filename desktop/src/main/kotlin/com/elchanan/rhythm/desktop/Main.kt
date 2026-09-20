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
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
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
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.data.db.SongStatsEntity
import com.elchanan.rhythm.desktop.audio.Analyzer
import com.elchanan.rhythm.desktop.audio.AudioPlayer
import com.elchanan.rhythm.desktop.audio.Equalizer
import com.elchanan.rhythm.desktop.data.Store
import com.elchanan.rhythm.engine.FeedSection
import com.elchanan.rhythm.engine.EngineTuning
import com.elchanan.rhythm.engine.Features
import com.elchanan.rhythm.engine.Recommender
import com.elchanan.rhythm.engine.SectionKind
import com.elchanan.rhythm.engine.Styles
import com.elchanan.rhythm.ui.theme.AppBackground
import com.elchanan.rhythm.ui.theme.RhythmTheme
import com.elchanan.rhythm.ui.theme.Accent
import com.elchanan.rhythm.ui.theme.Surface1
import com.elchanan.rhythm.ui.theme.TextSecondary
import com.elchanan.rhythm.ui.theme.gradientFor
import kotlinx.coroutines.Dispatchers
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
    var eqOpen by remember { mutableStateOf(false) }
    // A stack and not a single screen, because an artist page opens an album
    // and going back from that album has to land on the artist rather than on
    // the tab the artist was reached from.
    var stack by remember { mutableStateOf<List<Route>>(emptyList()) }
    // The song the options dialog is open on, if any. Held here rather than
    // inside each screen so every list in the app opens the same one.
    var options by remember { mutableStateOf<SongEntity?>(null) }

    suspend fun reload() {
        val loaded = withContext(Dispatchers.IO) {
            val s = store.songs()
            val st = store.stats()
            val ar = store.artists()
            val sd = store.feedSeed
            val ft = store.features()
            val tn = store.tuning
            val eng = if (s.isEmpty()) null else Feed.engine(s, st, ar, ft, sd, tn)
            val model = LibraryModel.build(s, ar, store.playlists(), store.playlistItems())
            Loaded(s, st, ar, model, store.folders, sd, eng?.buildFeed().orEmpty(), ft, eng, tn)
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
        status = if (loaded.songs.isEmpty()) {
            "בחר תיקיית מוזיקה"
        } else {
            "${loaded.songs.size} שירים · ${loaded.songs.map { it.artistKey }.distinct().size} אמנים"
        }
    }

    // The library is on disk from the last run, so it is on screen before
    // anything is scanned.
    LaunchedEffect(Unit) { reload() }

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
            scope.launch {
                stats = withContext(Dispatchers.IO) {
                    store.notePlay(leaving.id, heard, previousCompleted)
                    store.stats()
                }
            }
        }
        if (index !in list.indices) return
        queue = list
        queueIndex = index
        player.play(File(list[index].path), list[index].durationMs)
    }

    fun scan(roots: List<File>) {
        if (roots.isEmpty()) return
        scanning = true
        status = "סורק…"
        scope.launch {
            val found = withContext(Dispatchers.IO) {
                val list = LibraryScan.scan(roots)
                store.folders = roots
                store.replaceSongs(list)
                list
            }
            reload()
            scanning = false
            if (found.isEmpty()) status = "לא נמצאו קבצי שמע בתיקייה"
        }
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
            scope.launch { play(queue, queueIndex + 1, previousCompleted = true) }
        }
        onDispose {
            player.onEnded = null
            player.stop()
            store.close()
        }
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
            onRate = { rate(current, it) }
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
                        onPlay = { list, index -> play(list, index) }
                    )
                    1 -> SearchPane(
                        query = query,
                        onQuery = { query = it },
                        results = remember(query, engine) {
                            if (query.isBlank()) emptyList() else engine?.search(query).orEmpty()
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
                    3 -> ArtistsPane(
                        artists = library.artists,
                        onOpen = { stack = stack + Route.Artist(it.key) }
                    )
                    else -> TuningPane(
                        equalizer = player.equalizer,
                        eqOpen = eqOpen,
                        onEqOpen = { eqOpen = it },
                        tuning = tuning,
                        songs = songs.size,
                        analysed = features.size,
                        ratedArtists = artists.count { it.rating > 0 },
                        taggedArtists = artists.count { it.styles.isNotBlank() },
                        liked = stats.values.count { it.liked == 1 },
                        played = stats.values.sumOf { it.playCount },
                        onChange = { retune(it) }
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
            },
            onOpen = { if (current != null) showPlayer = true },
            onToggle = { player.togglePause() },
            onNext = { play(queue, queueIndex + 1) }
        )

        // The same four the phone has, in the same order, with the same icons
        // and the same words. A fifth for tuning, which the phone reaches from
        // inside the home screen and a window has room to show outright.
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
            NavTab(tab, 4, "כוונון", Icons.Filled.Tune) { go(4) }
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
            onCreateWith = { createPlaylistWith(it, song) }
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
    val tuning: EngineTuning
)

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
    onPlay: (List<SongEntity>, Int) -> Unit
) {
    val visible = feed.filter { section ->
        when (section.kind) {
            SectionKind.MIX_ROW -> section.mixes.isNotEmpty()
            else -> section.songs.isNotEmpty()
        }
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
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
    onRate: (Int) -> Unit
) {
    var scrub by remember { mutableStateOf<Float?>(null) }
    val liked = stat?.liked ?: 0
    val rating = stat?.rating ?: 0

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.ExpandMore, contentDescription = "סגור")
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

@Composable
private fun TuningPane(
    equalizer: Equalizer,
    eqOpen: Boolean,
    onEqOpen: (Boolean) -> Unit,
    tuning: EngineTuning,
    songs: Int,
    analysed: Int,
    ratedArtists: Int,
    taggedArtists: Int,
    liked: Int,
    played: Int,
    onChange: (EngineTuning) -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Text(
                "מה המנוע יודע",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 10.dp)
            )
            // Said in one place because every one of these is a thing the
            // engine is waiting for more of, and none of them is visible
            // anywhere else.
            Fact("שירים בספרייה", "$songs")
            Fact("שירים שנותחו", "$analysed מתוך $songs")
            Fact("אמנים שדורגו", "$ratedArtists")
            Fact("אמנים עם סגנון", "$taggedArtists")
            Fact("שירים עם לייק", "$liked")
            Fact("סך הנגינות", "$played")
            Text(
                "אקולייזר",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 20.dp, bottom = 4.dp)
            )
        }
        item { EqualizerPanel(equalizer = equalizer, open = eqOpen, onOpen = onEqOpen) }
        item {
            Text(
                "כוונון האלגוריתם",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 20.dp, bottom = 4.dp)
            )
        }
        item {
            Knob("גילוי מול מוכר", tuning.discovery, 0f..1f,
                "ככל שגבוה יותר, יופיעו יותר שירים שלא שמעת") {
                onChange(tuning.copy(discovery = it))
            }
            Knob("משקל דירוג האמן", tuning.artistWeight, 0f..2f,
                "כמה הדירוג שנתת לאמן משפיע על השירים שלו") {
                onChange(tuning.copy(artistWeight = it))
            }
            Knob("משקל הסגנון", tuning.styleWeight, 0f..2f,
                "כמה התאמת הסגנון מושכת שיר למעלה") {
                onChange(tuning.copy(styleWeight = it))
            }
            Knob("מניעת חזרתיות", tuning.repeatGuard, 0f..2f,
                "ככל שגבוה יותר, שיר שהתנגן לאחרונה ירד בדירוג") {
                onChange(tuning.copy(repeatGuard = it))
            }
            Knob("משקל הדמיון האקוסטי", tuning.acousticWeight, 0f..2f,
                "כמה הצליל עצמו קובע, לעומת מה שכתוב על השיר") {
                onChange(tuning.copy(acousticWeight = it))
            }
        }
    }
}

@Composable
private fun EqualizerPanel(equalizer: Equalizer, open: Boolean, onOpen: (Boolean) -> Unit) {
    // The sliders read from the filter and write to it directly. There is no
    // copy of these six numbers anywhere else, which is what stops a slider
    // and the sound it is meant to change from disagreeing.
    var version by remember { mutableStateOf(0) }
    var on by remember { mutableStateOf(equalizer.enabled) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    on = !on
                    equalizer.enabled = on
                }
            ) { Text(if (on) "כבוי" else "הפעל") }
            Button(
                onClick = { onOpen(!open) },
                modifier = Modifier.padding(start = 8.dp)
            ) { Text(if (open) "סגור" else "פתח") }
            if (on) {
                Button(
                    onClick = {
                        equalizer.reset()
                        version++
                    },
                    modifier = Modifier.padding(start = 8.dp)
                ) { Text("אפס") }
            }
        }
        if (open) {
            for (band in Equalizer.FREQUENCIES.indices) {
                val hz = Equalizer.FREQUENCIES[band].toInt()
                val label = if (hz >= 1000) "${hz / 1000}kHz" else "${hz}Hz"
                var live by remember(version, band) { mutableStateOf(equalizer.gain(band)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.width(56.dp)
                    )
                    Slider(
                        value = live,
                        valueRange = -Equalizer.MAX_DB..Equalizer.MAX_DB,
                        onValueChange = {
                            live = it
                            equalizer.setGain(band, it)
                        },
                        enabled = on,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                    )
                    Text(
                        "${live.toInt()} dB",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.width(52.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun Knob(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    hint: String,
    onDone: (Float) -> Unit
) {
    // The slider follows the finger locally and the engine is only rebuilt
    // when it is let go. Rebuilding on every pixel would score the whole
    // library a hundred times for one drag.
    var live by remember(value) { mutableStateOf(value) }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(
            value = live,
            valueRange = range,
            onValueChange = { live = it },
            onValueChangeFinished = { onDone(live) }
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
