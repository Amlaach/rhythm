package com.elchanan.rhythm.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.engine.Styles
import com.elchanan.rhythm.ui.MainViewModel
import com.elchanan.rhythm.ui.components.Artwork
import com.elchanan.rhythm.ui.components.Chip
import com.elchanan.rhythm.ui.components.EmptyState
import com.elchanan.rhythm.ui.components.SongRow
import com.elchanan.rhythm.ui.components.StarRow
import com.elchanan.rhythm.ui.components.rememberMetrics
import com.elchanan.rhythm.ui.theme.Accent
import com.elchanan.rhythm.ui.theme.Bg
import com.elchanan.rhythm.ui.theme.TextSecondary
import com.elchanan.rhythm.ui.theme.gradientFor

@Composable
private fun DetailTopBar(title: String, onBack: () -> Unit) {
    val topPad = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topPad)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "חזור")
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun DetailListScreen(vm: MainViewModel, onBack: () -> Unit) {
    val detail by vm.detail.collectAsStateWithLifecycle()
    val library by vm.library.collectAsStateWithLifecycle()
    var sheetSong by remember { mutableStateOf<SongEntity?>(null) }

    val data = detail
    Column(modifier = Modifier.fillMaxSize().background(Bg)) {
        DetailTopBar(title = data?.title.orEmpty(), onBack = onBack)
        if (data == null) {
            EmptyState(title = "אין מה להציג", body = "אפשר לחזור אחורה ולבחור רשימה.")
            return@Column
        }
        val songs = data.songs
        LazyColumn(contentPadding = PaddingValues(bottom = 40.dp)) {
            item {
                val (c1, c2) = gradientFor(data.gradientKey)
                Column(modifier = Modifier.padding(16.dp)) {
                    Box(
                        modifier = Modifier
                            .size(150.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Brush.linearGradient(listOf(c1, c2))),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = data.title,
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White,
                            modifier = Modifier.padding(12.dp),
                            maxLines = 4
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(data.title, style = MaterialTheme.typography.headlineSmall)
                    if (data.subtitle != null) {
                        Text(
                            data.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { vm.playList(songs) },
                            colors = ButtonDefaults.buttonColors(containerColor = Accent)
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("נגן")
                        }
                        OutlinedButton(onClick = { vm.shuffleList(songs) }) {
                            Icon(Icons.Filled.Shuffle, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("ערבב")
                        }
                    }
                }
            }
            items(songs, key = { it.id }) { song ->
                SongRow(
                    song = song,
                    liked = library.stats[song.id]?.liked ?: 0,
                    rating = library.stats[song.id]?.rating ?: 0,
                    onClick = { vm.playList(songs, songs.indexOf(song)) },
                    onMore = { sheetSong = song },
                    onLike = { vm.like(song.id) },
                    onDislike = { vm.dislike(song.id) }
                )
            }
        }
    }

    sheetSong?.let { song ->
        SongOptionsSheet(
            vm = vm,
            song = song,
            onDismiss = { sheetSong = null },
            onOpenDetail = { },
            onRemoveFromPlaylist = data?.playlistId?.let { id ->
                { vm.removeFromPlaylist(id, song.id) }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ArtistDetailScreen(vm: MainViewModel, onBack: () -> Unit, onOpenDetail: () -> Unit) {
    val artist by vm.artistDetail.collectAsStateWithLifecycle()
    val library by vm.library.collectAsStateWithLifecycle()
    var sheetSong by remember { mutableStateOf<SongEntity?>(null) }

    val info = artist
    Column(modifier = Modifier.fillMaxSize().background(Bg)) {
        DetailTopBar(title = info?.displayName.orEmpty(), onBack = onBack)
        if (info == null) {
            EmptyState(title = "לא נבחר אמן", body = "אפשר לבחור אמן מהספרייה.")
            return@Column
        }
        val live = library.artists.firstOrNull { it.key == info.key } ?: info
        val selectedStyles = Styles.parse(live.styles)

        LazyColumn(contentPadding = PaddingValues(bottom = 40.dp)) {
            item {
                val (c1, c2) = gradientFor(live.key)
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(c1, c2))),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            live.displayName.take(2),
                            style = MaterialTheme.typography.displaySmall,
                            color = Color.White
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(live.displayName, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "${live.songs.size} שירים",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(Modifier.height(12.dp))
                    StarRow(
                        rating = live.rating,
                        onRate = { vm.rateArtist(live.key, live.displayName, it, live.styles, live.note) },
                        size = 30
                    )
                    Text(
                        text = ratingHint(live.rating),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { vm.playList(live.songs) },
                            colors = ButtonDefaults.buttonColors(containerColor = Accent)
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("נגן")
                        }
                        OutlinedButton(onClick = {
                            live.songs.firstOrNull()?.let { vm.startRadio(it) }
                        }) {
                            Icon(Icons.Filled.Radio, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("רדיו")
                        }
                    }
                }
            }

            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text("סגנונות", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "התגיות האלה הן מה שהופך את ההמלצות למדויקות",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(Modifier.height(10.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Styles.SUGGESTED.forEach { style ->
                            val selected = selectedStyles.any { it.equals(style, ignoreCase = true) }
                            Chip(label = style, selected = selected, onClick = {
                                val next = if (selected) {
                                    selectedStyles.filterNot { it.equals(style, ignoreCase = true) }
                                } else {
                                    selectedStyles + style
                                }
                                vm.rateArtist(
                                    live.key,
                                    live.displayName,
                                    live.rating,
                                    Styles.join(next),
                                    live.note
                                )
                            })
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("השירים", style = MaterialTheme.typography.titleMedium)
                }
            }

            items(live.songs, key = { it.id }) { song ->
                SongRow(
                    song = song,
                    liked = library.stats[song.id]?.liked ?: 0,
                    rating = library.stats[song.id]?.rating ?: 0,
                    onClick = { vm.playList(live.songs, live.songs.indexOf(song)) },
                    onMore = { sheetSong = song },
                    onLike = { vm.like(song.id) },
                    onDislike = { vm.dislike(song.id) }
                )
            }
        }
    }

    sheetSong?.let { song ->
        SongOptionsSheet(
            vm = vm,
            song = song,
            onDismiss = { sheetSong = null },
            onOpenDetail = onOpenDetail,
            onOpenAlbum = {
                library.albums.firstOrNull { it.albumId == song.albumId }?.let {
                    vm.openList(it.name, it.artistName, it.songs, "album:${it.albumId}")
                    onOpenDetail()
                }
            }
        )
    }
}

private fun ratingHint(rating: Int): String = when (rating) {
    5 -> "מהאהובים ביותר — יופיע הרבה"
    4 -> "אמן מועדף"
    3 -> "ניטרלי"
    2 -> "פחות מעניין"
    1 -> "כמעט לא יופיע"
    else -> "עוד לא דורג"
}

@Composable
fun AlbumsScreen(vm: MainViewModel, onBack: () -> Unit, onOpenDetail: () -> Unit) {
    val library by vm.library.collectAsStateWithLifecycle()
    Column(modifier = Modifier.fillMaxSize().background(Bg)) {
        DetailTopBar(title = "אלבומים", onBack = onBack)
        LazyVerticalGrid(
            // Two columns on a phone, more as the window widens, without ever
            // squeezing a cover below a legible size.
            columns = GridCells.Adaptive(minSize = rememberMetrics().gridCellMin),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(library.albums, key = { it.albumId }) { album ->
                Column(
                    modifier = Modifier.clickable {
                        vm.openList(album.name, album.artistName, album.songs, "album:${album.albumId}")
                        onOpenDetail()
                    }
                ) {
                    Artwork(
                        -1L,
                        album.albumId,
                        album.name,
                        Modifier.fillMaxWidth().aspectRatio(1f),
                        corner = 12
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(album.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text(
                        album.artistName,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
