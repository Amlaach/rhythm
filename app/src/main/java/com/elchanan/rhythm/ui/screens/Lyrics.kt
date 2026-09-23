package com.elchanan.rhythm.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import com.elchanan.rhythm.ui.theme.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elchanan.rhythm.engine.LyricLine
import com.elchanan.rhythm.engine.Lyrics
import com.elchanan.rhythm.data.LyricsSource
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.ui.MainViewModel
import com.elchanan.rhythm.ui.theme.Accent
import com.elchanan.rhythm.ui.theme.Surface1
import com.elchanan.rhythm.ui.theme.TextPrimary
import com.elchanan.rhythm.ui.theme.TextSecondary
import com.elchanan.rhythm.ui.theme.TextTertiary

/**
 * Lyrics pane inside the player. When timestamps exist the current line is
 * highlighted and the list follows playback; otherwise it is a plain scroll.
 */
@Composable
fun LyricsView(
    vm: MainViewModel,
    song: SongEntity,
    positionMs: Long,
    modifier: Modifier = Modifier
) {
    val lyrics by vm.lyrics.collectAsStateWithLifecycle()
    val lines by vm.lyricLines.collectAsStateWithLifecycle()
    var editorOpen by remember { mutableStateOf(false) }

    LaunchedEffect(song.id) { vm.loadLyrics(song) }

    val listState = rememberLazyListState()
    val currentIndex = remember(lines, positionMs) {
        if (lines.isEmpty()) -1
        else lines.indexOfLast { it.timeMs <= positionMs + 250 }
    }

    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0 && lines.isNotEmpty()) {
            runCatching {
                listState.animateScrollToItem((currentIndex - 2).coerceAtLeast(0))
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            when {
                lines.isNotEmpty() -> LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(vertical = 24.dp, horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(lines.size) { index ->
                        val line = lines[index]
                        val active = index == currentIndex
                        Text(
                            text = line.text.ifBlank { "♪" },
                            style = if (active) MaterialTheme.typography.titleLarge
                            else MaterialTheme.typography.bodyLarge,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            color = when {
                                active -> Accent
                                index < currentIndex -> TextTertiary
                                else -> TextSecondary
                            },
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { vm.player.seekTo(line.timeMs) }
                                .padding(vertical = 2.dp)
                        )
                    }
                }

                lyrics?.text?.isNotBlank() == true -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 20.dp)
                ) {
                    Text(
                        text = lyrics?.text.orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                else -> Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("אין מילים לשיר הזה", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "אפשר לחפש בתגיות הקובץ ובתיקיית המילים, או להדביק ידנית.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = { vm.refreshLyrics(song) }) { Text("חפש שוב") }
            Button(
                onClick = { editorOpen = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Surface1,
                    contentColor = TextPrimary
                )
            ) { Text("ערוך / הדבק") }
        }
    }

    if (editorOpen) {
        LyricsEditorDialog(vm = vm, song = song, onDismiss = { editorOpen = false })
    }
}

/**
 * Paste or type lyrics, then optionally stamp each line while the song plays
 * to turn them into a synced LRC.
 */
@Composable
fun LyricsEditorDialog(vm: MainViewModel, song: SongEntity, onDismiss: () -> Unit) {
    val stored by vm.lyrics.collectAsStateWithLifecycle()
    val playerState by vm.player.state.collectAsStateWithLifecycle()

    var text by remember { mutableStateOf(stored?.text.orEmpty()) }
    var syncing by remember { mutableStateOf(false) }
    var stampIndex by remember { mutableIntStateOf(0) }
    val stamps = remember { mutableStateOf(listOf<LyricLine>()) }

    val lines = remember(text) { text.lines().map { it.trim() }.filter { it.isNotEmpty() } }

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
                            .background(Surface1)
                            .padding(12.dp),
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${stampIndex} מתוך ${lines.size} שורות סומנו",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val line = lines.getOrNull(stampIndex) ?: return@Button
                                stamps.value = stamps.value + LyricLine(playerState.positionMs, line)
                                stampIndex++
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Accent)
                        ) { Text("סמן") }
                        OutlinedButton(onClick = {
                            if (stampIndex > 0) {
                                stampIndex--
                                stamps.value = stamps.value.dropLast(1)
                            }
                        }) { Text("אחורה") }
                        OutlinedButton(onClick = { vm.player.togglePlayPause() }) {
                            Text(if (playerState.isPlaying) "עצור" else "נגן")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (syncing) {
                    vm.saveLyrics(song.id, text.trim(), Lyrics.buildLrc(stamps.value))
                    onDismiss()
                } else {
                    val looksLikeLrc = text.contains('[') &&
                        Regex("\\[\\d{1,2}:\\d{2}").containsMatchIn(text)
                    vm.saveLyrics(
                        song.id,
                        if (looksLikeLrc) Lyrics.stripTimestamps(text) else text.trim(),
                        if (looksLikeLrc) text else ""
                    )
                    onDismiss()
                }
            }) { Text("שמור", color = Accent) }
        },
        dismissButton = {
            Row {
                if (!syncing && lines.isNotEmpty()) {
                    TextButton(onClick = {
                        syncing = true
                        stampIndex = 0
                        stamps.value = emptyList()
                    }) { Text("סנכרן", color = TextSecondary) }
                }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onDismiss) { Text("ביטול", color = TextSecondary) }
            }
        }
    )
}
