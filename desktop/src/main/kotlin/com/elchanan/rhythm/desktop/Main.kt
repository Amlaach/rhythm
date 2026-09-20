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
import androidx.compose.material.icons.filled.VolumeUp
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
import com.elchanan.rhythm.desktop.audio.AudioPlayer
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
    val scope = rememberCoroutineScope()
    val state by player.state.collectAsState()

    var songs by remember { mutableStateOf<List<SongEntity>>(emptyList()) }
    var playing by remember { mutableStateOf(-1) }
    var status by remember { mutableStateOf("בחר תיקיית מוזיקה") }
    var scanning by remember { mutableStateOf(false) }
    var volume by remember { mutableStateOf(1f) }

    fun playAt(index: Int) {
        if (index !in songs.indices) return
        playing = index
        player.play(File(songs[index].path), songs[index].durationMs)
    }

    // The end of a track arrives on the audio thread, and moving to the next
    // one touches state the composition reads, so it is handed back to the
    // composition's own dispatcher rather than done where it was noticed.
    DisposableEffect(player) {
        player.onEnded = { scope.launch { playAt(playing + 1) } }
        onDispose {
            player.onEnded = null
            player.stop()
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
                onClick = {
                    val folder = chooseFolder() ?: return@Button
                    scanning = true
                    status = "סורק…"
                    // Off the composition's thread: a library of thousands
                    // means thousands of files opened for their tags.
                    scope.launch {
                        val found = withContext(Dispatchers.IO) {
                            LibraryScan.scan(listOf(folder)).sortedBy { it.titleLower }
                        }
                        songs = found
                        status = "${found.size} שירים · " +
                            "${found.map { it.artistKey }.distinct().size} אמנים"
                        scanning = false
                    }
                }
            ) { Text("בחר תיקייה") }

            Text(status, style = MaterialTheme.typography.bodyMedium)

            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            itemsIndexed(songs) { index, song ->
                val current = index == playing
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { playAt(index) }
                        .background(
                            if (current) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                            else Color.Transparent
                        )
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text(song.title, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        song.artistName.ifEmpty { "ללא אמן" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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

                Icon(Icons.Filled.VolumeUp, contentDescription = "עוצמה")
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
