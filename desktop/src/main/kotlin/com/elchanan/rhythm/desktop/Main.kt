package com.elchanan.rhythm.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.elchanan.rhythm.data.db.SongEntity
import java.io.File
import javax.swing.JFileChooser
import javax.swing.UIManager

/**
 * The Windows build's entry point.
 *
 * Right now this proves the one thing that had to be proved before anything
 * else was worth writing: that the engine compiled for the phone runs here
 * unchanged. The folder it scans is walked by desktop code, but the artist
 * keys come out of the same [com.elchanan.rhythm.engine.Names] the phone uses
 * and the rows are the same [SongEntity].
 *
 * Playback, the database and the fifteen screens come next. This is the
 * skeleton they hang on.
 */
fun main() = application {
    // Swing's own look, for the folder chooser. It is the one place this app
    // borrows a dialog rather than drawing it, because Windows users know
    // their own file picker and a hand-drawn one would only be worse.
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
                Surface(modifier = Modifier.fillMaxSize()) { LibraryPane() }
            }
        }
    }
}

@Composable
private fun LibraryPane() {
    var songs by remember { mutableStateOf<List<SongEntity>>(emptyList()) }
    var status by remember { mutableStateOf("בחר תיקיית מוזיקה") }
    var scanning by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                enabled = !scanning,
                onClick = {
                    val folder = chooseFolder() ?: return@Button
                    scanning = true
                    status = "סורק…"
                    // Off the UI thread: a library of thousands means
                    // thousands of files opened for their tags.
                    Thread {
                        val found = LibraryScan.scan(listOf(folder))
                        songs = found.sortedBy { it.titleLower }
                        status = "${found.size} שירים · " +
                            "${found.map { it.artistKey }.distinct().size} אמנים"
                        scanning = false
                    }.start()
                }
            ) { Text("בחר תיקייה") }

            Text(status, style = MaterialTheme.typography.bodyMedium)
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(songs) { song ->
                Column {
                    Text(song.title, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        song.artistName.ifEmpty { "ללא אמן" },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
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
