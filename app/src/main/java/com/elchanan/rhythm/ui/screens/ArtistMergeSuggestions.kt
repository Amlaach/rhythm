package com.elchanan.rhythm.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.elchanan.rhythm.ui.theme.Accent
import com.elchanan.rhythm.ui.theme.Surface1
import com.elchanan.rhythm.ui.theme.TextSecondary
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import com.elchanan.rhythm.ui.theme.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elchanan.rhythm.data.ArtistMerge
import com.elchanan.rhythm.ui.ArtistInfo
import com.elchanan.rhythm.ui.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Artists that look like one artist twice, offered for joining.
 *
 * A card with a button, at the top of the artists list: it was a line of
 * text on the ratings screen, which read as the app remarking on something
 * rather than offering to do it, in a place nobody looks for duplicates.
 */
@Composable
fun ArtistMergeSuggestions(vm: MainViewModel, artists: List<ArtistInfo>, gutter: Dp = 16.dp) {
    val pairs by produceState<List<Pair<ArtistInfo, ArtistInfo>>>(emptyList(), artists) {
        value = withContext(Dispatchers.Default) {
            // One letter apart, the same words in another order or with a
            // title, full and short spelling, or Hebrew and English.
            ArtistMerge.suggestions(artists) { it.displayName }
        }
    }
    val busy by vm.mergingArtist.collectAsStateWithLifecycle()
    var show by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Pair<ArtistInfo, ArtistInfo>?>(null) }
    var keepFirst by remember { mutableStateOf(true) }
    if (pairs.isNotEmpty()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = gutter, vertical = 8.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Surface1)
                .clickable(enabled = !busy) { show = true }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.People, contentDescription = null, tint = Accent, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (pairs.size == 1) "אמן אחד שנראה כפול" else "${pairs.size} אמנים שנראים כפולים",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    "אותו אמן בשני איותים. אפשר לאחד אותם לאמן אחד",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
            Spacer(Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Accent)
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            ) {
                Text("בדיקה", style = MaterialTheme.typography.labelLarge, color = Color.White)
            }
        }
    }
    if (show && selected == null) {
        AlertDialog(
            containerColor = Surface1,
            onDismissRequest = { show = false },
            title = { Text("ייתכן שזה אותו אמן") },
            text = {
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(pairs) { pair ->
                        TextButton(onClick = { selected = pair; keepFirst = true }, enabled = !busy) {
                            Text("${pair.first.displayName} / ${pair.second.displayName}")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { show = false }) { Text("סגור") } }
        )
    }
    selected?.let { pair ->
        val target = if (keepFirst) pair.first else pair.second
        val source = if (keepFirst) pair.second else pair.first
        AlertDialog(
            containerColor = Surface1,
            onDismissRequest = { selected = null },
            title = { Text("לאחד את האמנים?") },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    Text("בחר את השם שיישאר. שירי שני האמנים יוצגו יחד באפליקציה. קובצי המוזיקה לא ישתנו.")
                    TextButton(onClick = { keepFirst = true }) {
                        Text("${if (keepFirst) "✓ " else ""}${pair.first.displayName} (${pair.first.songs.size} שירים)")
                    }
                    TextButton(onClick = { keepFirst = false }) {
                        Text("${if (!keepFirst) "✓ " else ""}${pair.second.displayName} (${pair.second.songs.size} שירים)")
                    }
                    Text("הדירוג והסגנונות של ${target.displayName} יישארו כפי שהם.")
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && artists.any { it.key == source.key } &&
                    artists.any { it.key == target.key }, onClick = {
                    vm.mergeArtists(source, target)
                    selected = null
                    show = false
                }) { Text("אחד") }
            },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("ביטול") } }
        )
    }
}
