package com.elchanan.rhythm.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistRemove
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.engine.AudioAnalyzer
import com.elchanan.rhythm.engine.Styles
import com.elchanan.rhythm.ui.MainViewModel
import com.elchanan.rhythm.ui.components.Artwork
import com.elchanan.rhythm.ui.components.Chip
import com.elchanan.rhythm.ui.components.StarRow
import com.elchanan.rhythm.ui.theme.Accent
import com.elchanan.rhythm.ui.theme.BgElevated
import com.elchanan.rhythm.ui.theme.Surface1
import com.elchanan.rhythm.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongOptionsSheet(
    vm: MainViewModel,
    song: SongEntity,
    onDismiss: () -> Unit,
    onOpenArtist: (() -> Unit)? = null,
    onOpenAlbum: (() -> Unit)? = null,
    onOpenDetail: (() -> Unit)? = null,
    onRemoveFromPlaylist: (() -> Unit)? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val library by vm.library.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val features by vm.featuresById.collectAsStateWithLifecycle()
    val stats = library.stats[song.id]
    val liked = stats?.liked ?: 0
    val rating = stats?.rating ?: 0
    var showWhy by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    var showTags by remember { mutableStateOf(false) }
    var newPlaylist by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = BgElevated
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 28.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Artwork(song.id, song.albumId, song.artistKey, Modifier.size(56.dp), corner = 8)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(song.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(
                        song.artistName,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 1
                    )
                    features[song.id]?.let { f ->
                        Text(
                            text = "${f.bpm.toInt()} BPM · ${AudioAnalyzer.modeLabel(f)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }
            }

            // per song rating - the most specific thing the user can say
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                Text("דירוג השיר", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = if (rating == 0) "דורס את דירוג האמן כשמגדירים אותו"
                    else "השיר מדורג $rating מתוך 5",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(Modifier.height(6.dp))
                StarRow(rating = rating, onRate = { vm.rateSong(song.id, it) }, size = 26)
            }
            Spacer(Modifier.height(6.dp))

            OptionRow(
                icon = Icons.Filled.ThumbUp,
                label = if (liked == 1) "בטל לייק" else "לייק",
                tint = if (liked == 1) Accent else null
            ) { vm.like(song.id); onDismiss() }

            OptionRow(
                icon = Icons.Filled.ThumbDown,
                label = if (liked == -1) "בטל דיסלייק" else "דיסלייק — פחות כאלה",
                tint = if (liked == -1) Accent else null
            ) { vm.dislike(song.id); onDismiss() }

            // Near the top, where it is in every other player. Buried under ten
            // other rows it may as well not exist - and it is always offered, even
            // with no playlists yet, since having to leave for the library tab to
            // make the first one is the step that stops playlists being used.
            Text(
                "הוספה לרשימה",
                style = MaterialTheme.typography.labelLarge,
                color = TextSecondary,
                modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp)
            )
            OptionRow(Icons.Filled.Add, "רשימה חדשה", tint = Accent) { newPlaylist = true }
            playlists.forEach { info ->
                OptionRow(Icons.Filled.PlaylistAdd, info.playlist.name) {
                    vm.addToPlaylist(info.playlist.id, song.id)
                    onDismiss()
                }
            }
            Spacer(Modifier.height(6.dp))

            OptionRow(Icons.Filled.Insights, "למה זה הומלץ לי") { showWhy = true }
            OptionRow(Icons.Filled.LocalOffer, "תגיות סגנון לשיר") { showTags = true }
            if (onOpenDetail != null) {
                OptionRow(Icons.Filled.AutoAwesome, "צור מיקס מהשיר הזה") {
                    vm.createMix(song) { onOpenDetail() }
                    onDismiss()
                }
            }
            OptionRow(Icons.Filled.Radio, "התחל רדיו מהשיר") { vm.startRadio(song); onDismiss() }
            OptionRow(Icons.Filled.FormatQuote, "מילות השיר") { showLyrics = true }
            OptionRow(Icons.Filled.SkipNext, "נגן הבא") { vm.playNext(song); onDismiss() }
            OptionRow(Icons.Filled.QueueMusic, "הוסף לתור") { vm.addToQueue(song); onDismiss() }

            if (onOpenArtist != null) {
                OptionRow(Icons.Filled.Person, "עבור לאמן") { onOpenArtist(); onDismiss() }
            }
            if (onOpenAlbum != null) {
                OptionRow(Icons.Filled.Album, "עבור לאלבום") { onOpenAlbum(); onDismiss() }
            }
            if (onRemoveFromPlaylist != null) {
                OptionRow(Icons.Filled.PlaylistRemove, "הסר מהרשימה") {
                    onRemoveFromPlaylist(); onDismiss()
                }
            }
        }
    }

    if (newPlaylist) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newPlaylist = false },
            containerColor = Surface1,
            title = { Text("רשימה חדשה") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    placeholder = { Text("שם הרשימה") }
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        // Creating with the song attached, so the sheet the user
                        // opened on a track actually ends with that track filed.
                        vm.createPlaylist(name.trim(), song)
                        newPlaylist = false
                        onDismiss()
                    }
                ) { Text("צור והוסף", color = Accent) }
            },
            dismissButton = {
                TextButton(onClick = { newPlaylist = false }) {
                    Text("ביטול", color = TextSecondary)
                }
            }
        )
    }

    if (showWhy) {
        WhyDialog(vm = vm, song = song, onDismiss = { showWhy = false })
    }
    if (showLyrics) {
        LyricsEditorDialog(vm = vm, song = song, onDismiss = { showLyrics = false })
    }
    if (showTags) {
        SongTagDialog(
            current = Styles.parse(stats?.styles.orEmpty()),
            onDismiss = { showTags = false },
            onApply = { vm.setSongStyles(song.id, Styles.join(it)) }
        )
    }
}

/** Shows the actual score terms the ranker used for this track. */
@Composable
fun WhyDialog(vm: MainViewModel, song: SongEntity, onDismiss: () -> Unit) {
    val terms = remember(song.id) { vm.explain(song) }
    val total = remember(song.id) { vm.totalScore(song) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        title = { Text("למה \"${song.title}\"") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (terms.isEmpty()) {
                    Text(
                        "הפיד עוד לא נבנה. אחרי רענון יופיע כאן הפירוק המלא.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                } else {
                    Text(
                        "ניקוד כולל: %.2f".format(total),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(8.dp))
                    terms.forEach { term ->
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
                                text = (if (term.value >= 0) "+" else "") + "%.2f".format(term.value),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (term.value >= 0) Accent else TextSecondary
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("סגור", color = Accent) }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SongTagDialog(
    current: List<String>,
    onDismiss: () -> Unit,
    onApply: (List<String>) -> Unit
) {
    var selected by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        title = { Text("תגיות לשיר הזה") },
        text = {
            Column(modifier = Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "תגית על שיר בודד מחליפה את תגיות האמן עבורו בלבד.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Styles.SUGGESTED.forEach { style ->
                        val on = selected.any { it.equals(style, ignoreCase = true) }
                        Chip(label = style, selected = on, onClick = {
                            selected = if (on) selected.filterNot { it.equals(style, ignoreCase = true) }
                            else selected + style
                        })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(selected); onDismiss() }) { Text("שמור", color = Accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("ביטול", color = TextSecondary) }
        }
    )
}

@Composable
private fun OptionRow(
    icon: ImageVector,
    label: String,
    tint: Color? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = tint ?: TextSecondary, modifier = Modifier.size(21.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint ?: MaterialTheme.colorScheme.onBackground)
    }
}
