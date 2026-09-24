package com.elchanan.rhythm.ui.screens

import com.elchanan.rhythm.ui.components.DialogBody
import com.elchanan.rhythm.ui.components.fitHeight
import com.elchanan.rhythm.ui.theme.localized

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import com.elchanan.rhythm.ui.theme.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elchanan.rhythm.data.db.BookmarkEntity
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.ui.MainViewModel
import com.elchanan.rhythm.ui.components.EmptyState
import com.elchanan.rhythm.ui.components.rememberMetrics
import com.elchanan.rhythm.ui.theme.Accent
import com.elchanan.rhythm.ui.theme.BgElevated
import com.elchanan.rhythm.ui.theme.Surface1
import com.elchanan.rhythm.ui.theme.TextSecondary
import com.elchanan.rhythm.ui.theme.TextTertiary

/**
 * The places the listener marked in the track that is playing.
 *
 * Worth having for the same reason as resuming: in an hour of speech, "the bit
 * about the third question" is somewhere around forty minutes in, and without
 * a mark the only way back is to drag the bar until it sounds right.
 *
 * Marks are made from here rather than from a long press on the progress bar,
 * because the bar is the one control people grab without looking and it should
 * keep doing exactly what it does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksSheet(
    vm: MainViewModel,
    song: SongEntity,
    currentPositionMs: Long,
    onSeek: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val gutter = rememberMetrics().gutter
    val bookmarks by vm.bookmarksFor(song.id).collectAsStateWithLifecycle(emptyList())
    var naming by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = BgElevated
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 28.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("סימניות", style = MaterialTheme.typography.titleMedium)
                    Text(
                        song.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 1
                    )
                }
                TextButton(onClick = { naming = true }) {
                    Icon(
                        Icons.Filled.BookmarkAdd,
                        contentDescription = null,
                        tint = Accent,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("סמן ${formatClock(currentPositionMs)}", color = Accent)
                }
            }

            if (bookmarks.isEmpty()) {
                EmptyState(
                    title = "עוד אין סימניות",
                    body = "לחיצה על \"סמן\" שומרת את המקום שבו אתה נמצא עכשיו, " +
                        "ואפשר לחזור אליו בלחיצה אחת."
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = fitHeight(320.dp))) {
                    items(bookmarks, key = { it.id }) { mark ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSeek(mark.positionMs)
                                    onDismiss()
                                }
                                .padding(horizontal = gutter, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Bookmark,
                                contentDescription = null,
                                tint = Accent,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = mark.label.ifBlank { "סימנייה" },
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1
                                )
                                Text(
                                    text = formatClock(mark.positionMs),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextTertiary
                                )
                            }
                            IconButton(onClick = { vm.deleteBookmark(mark.id) }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = localized("מחק סימנייה"),
                                    tint = TextTertiary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (naming) {
        var label by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { naming = false },
            containerColor = Surface1,
            title = { Text("סימנייה ב-${formatClock(currentPositionMs)}") },
            text = {
                DialogBody {
                    Column {
                        Text(
                            "אפשר לתת שם, ואפשר להשאיר ריק.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = label,
                            onValueChange = { label = it },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.addBookmark(song.id, currentPositionMs, label)
                    naming = false
                }) { Text("שמור", color = Accent) }
            },
            dismissButton = {
                TextButton(onClick = { naming = false }) {
                    Text("ביטול", color = TextSecondary)
                }
            }
        )
    }
}

/**
 * Milliseconds as a clock.
 *
 * Shows hours only when there are any: a shiur runs past an hour and needs
 * them, and a song showing "0:04:12" looks broken.
 */
fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
