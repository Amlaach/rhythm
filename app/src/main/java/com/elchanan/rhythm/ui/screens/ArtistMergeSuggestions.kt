package com.elchanan.rhythm.ui.screens

import androidx.compose.foundation.layout.Column
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

@Composable
fun ArtistMergeSuggestions(vm: MainViewModel, artists: List<ArtistInfo>) {
    val pairs by produceState<List<Pair<ArtistInfo, ArtistInfo>>>(emptyList(), artists) {
        value = withContext(Dispatchers.Default) {
            buildList {
                for (i in artists.indices) for (j in i + 1 until artists.size) {
                    if (ArtistMerge.oneLetterApart(artists[i].key, artists[j].key)) {
                        add(artists[i] to artists[j])
                    }
                }
            }
        }
    }
    val busy by vm.mergingArtist.collectAsStateWithLifecycle()
    var show by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Pair<ArtistInfo, ArtistInfo>?>(null) }
    var keepFirst by remember { mutableStateOf(true) }
    if (pairs.isNotEmpty()) {
        TextButton(onClick = { show = true }, enabled = !busy) {
            Text("נמצאו ${pairs.size} זוגות אמנים עם שמות דומים — בדוק איחוד")
        }
    }
    if (show && selected == null) {
        AlertDialog(
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
