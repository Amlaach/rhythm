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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.data.db.SongStatsEntity
import com.elchanan.rhythm.desktop.audio.AudioPlayer
import com.elchanan.rhythm.desktop.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser
import javax.swing.UIManager

/**
 * The Windows build's entry point.
 *
 * A library scanned off the disk and a player that plays it. The scan goes
 * through the same [com.elchanan.rhythm.engine.Names] the phone uses and
 * produces the same [SongEntity], so the recommender that comes next has
 * nothing new to learn about this platform.
 *
 * The database and the fifteen screens are still to come; this is one screen
 * and no persistence, which means the folder has to be picked again each time.
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
    var playing by remember { mutableStateOf(-1) }
    var status by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var volume by remember { mutableStateOf(1f) }
    // Held rather than read from the database where it is used: that read
    // sits in the layout, and the layout is rebuilt several times a second
    // while a song plays.
    var folders by remember { mutableStateOf<List<File>>(emptyList()) }

    suspend fun reload() {
        val loaded = withContext(Dispatchers.IO) {
            Triple(store.songs(), store.stats(), store.folders)
        }
        songs = loaded.first
        stats = loaded.second
        folders = loaded.third
        status = if (loaded.first.isEmpty()) {
            "בחר תיקיית מוזיקה"
        } else {
            "${loaded.first.size} שירים · ${loaded.first.map { it.artistKey }.distinct().size} אמנים"
        }
    }

    // The library is on disk from the last run, so it is on screen before
    // anything is scanned. Being asked for the folder every launch was the
    // most obviously missing thing about the build that had no database.
    LaunchedEffect(Unit) { reload() }

    /**
     * Records what happened to the song that was playing, then starts another.
     *
     * The record has to happen here rather than when a track ends, because
     * most tracks do not end - they are skipped, and a skip is the single
     * most informative thing the user does. Heard out or moved on from is
     * exactly the distinction the recommender's skip rate is built on.
     */
    fun playAt(index: Int, previousCompleted: Boolean = false) {
        val leaving = songs.getOrNull(playing)
        if (leaving != null) {
            val heard = player.state.value.positionMs
            scope.launch {
                withContext(Dispatchers.IO) {
                    store.notePlay(leaving.id, heard, previousCompleted)
                }
                stats = withContext(Dispatchers.IO) { store.stats() }
            }
        }
        if (index !in songs.indices) return
        playing = index
        player.play(File(songs[index].path), songs[index].durationMs)
    }

    fun scan(folders: List<File>) {
        if (folders.isEmpty()) return
        scanning = true
        status = "סורק…"
        scope.launch {
            val found = withContext(Dispatchers.IO) {
                val list = LibraryScan.scan(folders)
                store.folders = folders
                store.replaceSongs(list)
                list
            }
            reload()
            scanning = false
            if (found.isEmpty()) status = "לא נמצאו קבצי שמע בתיקייה"
        }
    }

    // The end of a track arrives on the audio thread, and moving to the next
    // one touches state the composition reads, so it is handed back to the
    // composition's own dispatcher rather than acted on where it was noticed.
    DisposableEffect(player) {
        player.onEnded = { scope.launch { playAt(playing + 1, previousCompleted = true) } }
        onDispose {
            player.onEnded = null
            player.stop()
            store.close()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                enabled = !scanning,
                onClick = { chooseFolder()?.let { scan(listOf(it)) } }
            ) { Text("בחר תיקייה") }

            if (folders.isNotEmpty()) {
                Button(
                    enabled = !scanning,
                    onClick = { scan(folders) }
                ) { Text("סרוק מחדש") }
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

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            itemsIndexed(songs) { index, song ->
                SongRow(
                    song = song,
                    current = index == playing,
                    liked = stats[song.id]?.liked ?: 0,
                    onPlay = { playAt(index) },
                    onLike = {
                        scope.launch {
                            withContext(Dispatchers.IO) { store.setLike(song.id, 1) }
                            stats = withContext(Dispatchers.IO) { store.stats() }
                        }
                    }
                )
            }
        }

        NowPlaying(
            song = songs.getOrNull(playing),
            positionMs = state.positionMs,
            durationMs = state.durationMs,
            playing = state.playing,
            volume = volume,
            onToggle = { player.togglePause() },
            onPrevious = { playAt(playing - 1) },
            onNext = { playAt(playing + 1) },
            onSeek = { player.seekTo(it) },
            onVolume = {
                volume = it
                player.setVolume(it)
            }
        )
    }
}

@Composable
private fun SongRow(
    song: SongEntity,
    current: Boolean,
    liked: Int,
    onPlay: () -> Unit,
    onLike: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .background(
                if (current) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                else Color.Transparent
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
        IconButton(onClick = onLike) {
            // Filled and accented when marked, outlined and quiet when not:
            // the same two states the phone's player screen draws.
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
    // track. Without this it snaps back on every position update, which is
    // several times a second, and dragging it is impossible.
    var scrub by remember { mutableStateOf<Float?>(null) }

    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                song?.title ?: "לא מנוגן כלום",
                style = MaterialTheme.typography.titleSmall
            )
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
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
