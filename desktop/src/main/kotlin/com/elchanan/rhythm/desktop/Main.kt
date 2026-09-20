package com.elchanan.rhythm.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.elchanan.rhythm.data.db.ArtistEntity
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.data.db.SongStatsEntity
import com.elchanan.rhythm.desktop.audio.AudioPlayer
import com.elchanan.rhythm.desktop.data.Store
import com.elchanan.rhythm.engine.FeedSection
import com.elchanan.rhythm.engine.SectionKind
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
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) { RhythmApp() }
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
    var volume by remember { mutableStateOf(1f) }

    suspend fun reload() {
        val loaded = withContext(Dispatchers.IO) {
            val s = store.songs()
            val st = store.stats()
            val ar = store.artists()
            val sd = store.feedSeed
            Loaded(s, st, ar, store.folders, sd, Feed.build(s, st, ar, sd))
        }
        songs = loaded.songs
        stats = loaded.stats
        artists = loaded.artists
        folders = loaded.folders
        seed = loaded.seed
        feed = loaded.feed
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

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("בית") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("ספרייה") })
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                enabled = !scanning,
                onClick = { chooseFolder()?.let { scan(listOf(it)) } }
            ) { Text("בחר תיקייה") }

            if (folders.isNotEmpty()) {
                Button(enabled = !scanning, onClick = { scan(folders) }) { Text("סרוק מחדש") }
            }
            if (feed.isNotEmpty()) {
                Button(
                    enabled = !scanning,
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { store.feedSeed = store.feedSeed + 1 }
                            reload()
                        }
                    }
                ) { Text("ערבב") }
            }

            Text(status, style = MaterialTheme.typography.bodyMedium)

            state.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (tab == 0) {
                FeedPane(feed = feed, onPlay = { list, index -> play(list, index) })
            } else {
                LibraryPane(
                    songs = songs,
                    stats = stats,
                    current = queue.getOrNull(queueIndex)?.id,
                    onPlay = { index -> play(songs, index) },
                    onLike = { song -> like(song) }
                )
            }
        }

        NowPlaying(
            song = queue.getOrNull(queueIndex),
            positionMs = state.positionMs,
            durationMs = state.durationMs,
            playing = state.playing,
            volume = volume,
            onToggle = { player.togglePause() },
            onPrevious = { play(queue, queueIndex - 1) },
            onNext = { play(queue, queueIndex + 1) },
            onSeek = { player.seekTo(it) },
            onVolume = {
                volume = it
                player.setVolume(it)
            }
        )
    }
}

/** Everything one reload reads, so the composition is updated once and not six times. */
private data class Loaded(
    val songs: List<SongEntity>,
    val stats: Map<Long, SongStatsEntity>,
    val artists: List<ArtistEntity>,
    val folders: List<File>,
    val seed: Long,
    val feed: List<FeedSection>
)

@Composable
private fun FeedPane(feed: List<FeedSection>, onPlay: (List<SongEntity>, Int) -> Unit) {
    if (feed.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("סרוק תיקייה כדי להתחיל", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }
    // A shelf the engine produced but could not fill is a heading with
    // nothing under it, which reads as breakage rather than as absence. The
    // daily mixes are empty until audio analysis exists, so this is not a
    // hypothetical case - it is the state the build is in right now.
    val visible = feed.filter { section ->
        when (section.kind) {
            SectionKind.MIX_ROW -> section.mixes.isNotEmpty()
            else -> section.songs.isNotEmpty()
        }
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(visible) { section ->
            Column(modifier = Modifier.padding(bottom = 18.dp)) {
                Text(
                    section.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                section.subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                    )
                }
                when (section.kind) {
                    SectionKind.MIX_ROW -> LazyRow(
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 16.dp
                        ),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        items(section.mixes) { mix ->
                            Tile(
                                title = mix.title,
                                subtitle = mix.subtitle,
                                onClick = { onPlay(mix.songs, 0) }
                            )
                        }
                    }
                    // Quick picks are columns of four on the phone. The same
                    // shape here, because the point of it is that a wide
                    // screen shows several at once without any of them being
                    // a full row of their own.
                    SectionKind.QUICK_PICKS -> LazyRow(
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 16.dp
                        ),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        items(section.songs.chunked(4)) { column ->
                            Column(modifier = Modifier.width(320.dp)) {
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
                    SectionKind.SONG_ROW -> LazyRow(
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 16.dp
                        ),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        itemsIndexed(section.songs) { index, song ->
                            Tile(
                                title = song.title,
                                subtitle = song.artistName.ifEmpty { "ללא אמן" },
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
private fun Tile(title: String, subtitle: String, onClick: () -> Unit) {
    Column(modifier = Modifier.width(150.dp).clickable(onClick = onClick)) {
        // Artwork is not read yet, so the square is a flat panel rather than
        // an empty hole: the row still reads as a row of cards.
        Box(
            modifier = Modifier
                .width(150.dp)
                .height(150.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
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
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        Box(
            modifier = Modifier
                .width(44.dp)
                .height(44.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
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
    onPlay: (Int) -> Unit,
    onLike: (SongEntity) -> Unit
) {
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
                Column(modifier = Modifier.weight(1f)) {
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

@Composable
private fun NowPlaying(
    song: SongEntity?,
    positionMs: Long,
    durationMs: Long,
    playing: Boolean,
    volume: Float,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onVolume: (Float) -> Unit
) {
    // While a thumb is held the slider has to follow the finger, not the
    // track. The position updates several times a second, and without this
    // the thumb snaps back under the cursor on every one of them.
    var scrub by remember { mutableStateOf<Float?>(null) }

    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(song?.title ?: "לא מנוגן כלום", style = MaterialTheme.typography.titleSmall)
            Text(
                song?.artistName?.ifEmpty { "ללא אמן" }.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(clock(positionMs), style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = scrub ?: positionMs.toFloat(),
                    valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
                    onValueChange = { scrub = it },
                    onValueChangeFinished = {
                        scrub?.let { onSeek(it.toLong()) }
                        scrub = null
                    },
                    enabled = song != null,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
                )
                Text(clock(durationMs), style = MaterialTheme.typography.labelSmall)
            }

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrevious, enabled = song != null) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "הקודם")
                }
                IconButton(onClick = onToggle, enabled = song != null) {
                    Icon(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playing) "השהה" else "נגן"
                    )
                }
                IconButton(onClick = onNext, enabled = song != null) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "הבא")
                }

                Box(modifier = Modifier.weight(1f))

                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "עוצמה")
                Slider(
                    value = volume,
                    onValueChange = onVolume,
                    modifier = Modifier.width(140.dp).padding(start = 8.dp)
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
