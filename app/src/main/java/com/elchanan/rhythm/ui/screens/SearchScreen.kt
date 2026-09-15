package com.elchanan.rhythm.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.engine.Styles
import com.elchanan.rhythm.ui.MainViewModel
import com.elchanan.rhythm.ui.components.Chip
import com.elchanan.rhythm.ui.components.EmptyState
import com.elchanan.rhythm.ui.components.SectionHeader
import com.elchanan.rhythm.ui.components.SongRow
import com.elchanan.rhythm.ui.theme.Accent
import com.elchanan.rhythm.ui.theme.Bg
import com.elchanan.rhythm.ui.theme.Surface1
import com.elchanan.rhythm.ui.theme.TextSecondary
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    vm: MainViewModel,
    onOpenArtist: () -> Unit,
    onOpenDetail: () -> Unit
) {
    val query by vm.searchQuery.collectAsStateWithLifecycle()
    val results by vm.searchResults.collectAsStateWithLifecycle()
    val library by vm.library.collectAsStateWithLifecycle()
    var sheetSong by remember { mutableStateOf<SongEntity?>(null) }

    val topPad = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Column(modifier = Modifier.fillMaxSize().background(Bg)) {
        TextField(
            value = query,
            onValueChange = { vm.onSearchQuery(it) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = topPad)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            placeholder = { Text("חיפוש שיר, אמן או אלבום", color = TextSecondary) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = TextSecondary) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { vm.onSearchQuery("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "נקה", tint = TextSecondary)
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Surface1,
                unfocusedContainerColor = Surface1,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = Accent,
                focusedTextColor = MaterialTheme.colorScheme.onBackground,
                unfocusedTextColor = MaterialTheme.colorScheme.onBackground
            )
        )

        if (query.isBlank()) {
            val styles = library.artists
                .flatMap { Styles.parse(it.styles) }
                .groupingBy { it }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .take(18)

            LazyColumn(contentPadding = PaddingValues(bottom = 40.dp)) {
                item { SectionHeader(title = "עיון מהיר") }
                item {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        QuickBrowse(
                            label = "השירים האהובים",
                            count = library.liked.size
                        ) {
                            vm.openList("אהובים", "כל מה שסימנת בלייק", library.liked, "liked")
                            onOpenDetail()
                        }
                        QuickBrowse(
                            label = "עדיין לא שמעת",
                            count = library.songs.count { (library.stats[it.id]?.playCount ?: 0) == 0 }
                        ) {
                            val list = library.songs.filter { (library.stats[it.id]?.playCount ?: 0) == 0 }
                            vm.openList("עדיין לא שמעת", null, list, "unheard")
                            onOpenDetail()
                        }
                        QuickBrowse(
                            label = "הכי מושמעים",
                            count = library.songs.count { (library.stats[it.id]?.playCount ?: 0) > 0 }
                        ) {
                            val list = library.songs
                                .filter { (library.stats[it.id]?.playCount ?: 0) > 0 }
                                .sortedByDescending { library.stats[it.id]?.playCount ?: 0 }
                            vm.openList("הכי מושמעים", null, list, "top")
                            onOpenDetail()
                        }
                    }
                }
                if (styles.isNotEmpty()) {
                    item { SectionHeader(title = "לפי סגנון", subtitle = "מגיע מהתגיות שהגדרת לאמנים") }
                    item {
                        FlowRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            styles.forEach { entry ->
                                val style = entry.key
                                Chip(label = style, selected = false, onClick = {
                                    val lower = style.lowercase(Locale.ROOT)
                                    val keys = library.artists
                                        .filter { a -> Styles.parse(a.styles).any { it.lowercase(Locale.ROOT) == lower } }
                                        .map { it.key }
                                        .toSet()
                                    val list = library.songs.filter { it.artistKey in keys }
                                    vm.openList("סגנון: $style", "${list.size} שירים", list, "style:$style")
                                    onOpenDetail()
                                })
                            }
                        }
                    }
                }
            }
        } else if (results.isEmpty()) {
            EmptyState(title = "אין תוצאות", body = "אולי כדאי לנסות מילה אחרת.")
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 40.dp)) {
                items(results, key = { it.id }) { song ->
                    SongRow(
                        song = song,
                        liked = library.stats[song.id]?.liked ?: 0,
                        rating = library.stats[song.id]?.rating ?: 0,
                        onClick = { vm.playList(results, results.indexOf(song)) },
                        onMore = { sheetSong = song },
                        onLike = { vm.like(song.id) },
                        onDislike = { vm.dislike(song.id) }
                    )
                }
            }
        }
    }

    sheetSong?.let { song ->
        SongOptionsSheet(
            vm = vm,
            song = song,
            onDismiss = { sheetSong = null },
            onOpenDetail = onOpenDetail,
            onOpenArtist = {
                library.artists.firstOrNull { it.key == song.artistKey }?.let {
                    vm.openArtist(it)
                    onOpenArtist()
                }
            }
        )
    }
}

@Composable
private fun QuickBrowse(label: String, count: Int, onClick: () -> Unit) {
    Chip(label = "$label · $count", selected = false, onClick = onClick)
}
