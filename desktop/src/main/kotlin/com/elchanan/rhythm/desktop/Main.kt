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

    suspend fun reload() {
        val loaded = withContext(Dispatchers.IO) {
            val s = store.songs()
            val st = store.stats()
            val ar = store.artists()
            val sd = store.feedSeed
            val ft = store.features()
            val tn = store.tuning
            val eng = if (s.isEmpty()) null else Feed.engine(s, st, ar, ft, sd, tn)
            Loaded(s, st, ar, store.folders, sd, eng?.buildFeed().orEmpty(), ft, eng, tn)
        }
        songs = loaded.songs
        stats = loaded.stats
        artists = loaded.artists
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

    fun rateArtist(artist: ArtistEntity, rating: Int) {
        scope.launch {
            withContext(Dispatchers.IO) { store.setArtistRating(artist.artistKey, rating) }
            reload()
        }
    }

    fun tagArtist(artist: ArtistEntity, style: String) {
        scope.launch {
            val now = Styles.parse(artist.styles).toMutableList()
            if (!now.remove(style)) now.add(style)
            withContext(Dispatchers.IO) {
                store.setArtistStyles(artist.artistKey, Styles.join(now))
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
            when (tab) {
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
                    onLike = { like(it) }
                )
                2 -> LibraryPane(
                    songs = songs,
                    stats = stats,
                    current = current?.id,
                    empty = "סרוק תיקייה כדי להתחיל",
                    onPlay = { index -> play(songs, index) },
                    onLike = { song -> like(song) }
                )
                3 -> ArtistsPane(
                    artists = artists,
                    onRate = { artist, rating -> rateArtist(artist, rating) },
                    onTag = { artist, style -> tagArtist(artist, style) }
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

        MiniPlayer(
            song = current,
            positionMs = state.positionMs,
            durationMs = state.durationMs,
            playing = state.playing,
            onOpen = { if (current != null) showPlayer = true },
            onToggle = { player.togglePause() },
            onNext = { play(queue, queueIndex + 1) }
        )

        // The same four the phone has, in the same order, with the same icons
        // and the same words. A fifth for tuning, which the phone reaches from
        // inside the home screen and a window has room to show outright.
        NavigationBar(containerColor = Surface1) {
            NavTab(tab, 0, "בית", Icons.Filled.Home) { tab = 0 }
            NavTab(tab, 1, "חיפוש", Icons.Filled.Search) { tab = 1 }
            NavTab(tab, 2, "ספרייה", Icons.Filled.LibraryMusic) { tab = 2 }
            NavTab(tab, 3, "אמנים", Icons.Filled.Star) { tab = 3 }
            NavTab(tab, 4, "כוונון", Icons.Filled.Tune) { tab = 4 }
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
            indicatorColor = Surface1,
            unselectedIconColor = TextSecondary,
            unselectedTextColor = TextSecondary
        )
    )
}

/** Everything one reload reads, so the composition is updated once and not six times. */
private data class Loaded(
    val songs: List<SongEntity>,
    val stats: Map<Long, SongStatsEntity>,
    val artists: List<ArtistEntity>,
    val folders: List<File>,
    val seed: Long,
    val feed: List<FeedSection>,
    val analysed: Map<Long, AudioFeatureEntity>,
    val engine: Recommender?,
    val tuning: EngineTuning
)

private val GUTTER = 16.dp
private val CARD = 156.dp

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
    onLike: (SongEntity) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            label = { Text("חיפוש") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(GUTTER)
        )
        LibraryPane(
            songs = results,
            stats = stats,
            current = current,
            empty = if (query.isBlank()) "הקלד כדי לחפש" else "לא נמצא כלום",
            onPlay = { index -> onPlay(results, index) },
            onLike = onLike
        )
    }
}

/**
 * A cover, or the panel that stands in for one.
 *
 * A flat surface rather than a placeholder picture or an empty hole: a row of
 * cards should read as a row of cards whether or not the files happen to
 * carry artwork, and most files in a library of downloads do not.
 */
@Composable
private fun Art(song: SongEntity?, size: Dp, corner: Dp) {
    val image = rememberArtwork(song)
    val (c1, c2) = gradientFor(song?.artistKey.orEmpty())
    Box(
        modifier = Modifier
            .width(size)
            .height(size)
            .clip(RoundedCornerShape(corner))
            .background(Brush.linearGradient(listOf(c1, c2)))
    ) {
        if (image != null) {
            // Covers taken from video thumbnails are 16:9. Fitting one into a
            // square leaves two thick bands of gradient behind it and makes
            // the artwork small; cropping fills the tile, which is what a
            // cover is for.
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
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
private fun LibraryPane(
    songs: List<SongEntity>,
    stats: Map<Long, SongStatsEntity>,
    current: Long?,
    empty: String,
    onPlay: (Int) -> Unit,
    onLike: (SongEntity) -> Unit
) {
    if (songs.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(empty, style = MaterialTheme.typography.bodyLarge)
        }
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        itemsIndexed(songs) { index, song ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPlay(index) }
                    .background(
                        if (song.id == current) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                        } else {
                            Color.Transparent
                        }
                    )
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Art(song = song, size = 44.dp, corner = 4.dp)
                Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(song.title, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        song.artistName.ifEmpty { "ללא אמן" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val liked = stats[song.id]?.liked ?: 0
                IconButton(onClick = { onLike(song) }) {
                    // Filled and accented when marked, outlined and quiet when
                    // not: the same two states the phone's player screen draws.
                    Icon(
                        imageVector = if (liked == 1) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
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
}

// Twenty six style words do not fit on one line of any window, so they wrap.
// FlowRow is the only layout in Compose that does that, and it is still
// marked experimental, which is what the opt in is for.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ArtistsPane(
    artists: List<ArtistEntity>,
    onRate: (ArtistEntity, Int) -> Unit,
    onTag: (ArtistEntity, String) -> Unit
) {
    if (artists.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("סרוק תיקייה כדי להתחיל", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }
    // Rated first, because the point of this screen is to work through the
    // ones that are not rated yet, and an alphabetical list gives no sense of
    // how far that has got.
    val ordered = artists.sortedWith(
        compareByDescending<ArtistEntity> { it.rating }.thenBy { it.displayName }
    )
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(ordered) { artist ->
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    artist.displayName.ifEmpty { "ללא שם" },
                    style = MaterialTheme.typography.bodyLarge
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    for (star in 1..5) {
                        IconButton(onClick = { onRate(artist, star) }) {
                            Icon(
                                imageVector = if (star <= artist.rating) {
                                    Icons.Filled.Star
                                } else {
                                    Icons.Filled.StarBorder
                                },
                                contentDescription = "$star",
                                tint = if (star <= artist.rating) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                }
                // The style words are what the learner trains on: every song
                // by a tagged artist becomes a labelled example, which is how
                // a few minutes here turns into a few hundred of them.
                val chosen = Styles.parse(artist.styles)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (style in Styles.SUGGESTED) {
                        StyleChip(
                            label = style,
                            selected = style in chosen,
                            onClick = { onTag(artist, style) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StyleChip(label: String, selected: Boolean, onClick: () -> Unit) {
    // Drawn by hand rather than with a chip component, because the one thing
    // it has to do is be obviously on or off at a glance and that is a
    // background colour.
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
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
