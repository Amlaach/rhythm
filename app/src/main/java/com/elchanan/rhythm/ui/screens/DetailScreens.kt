package com.elchanan.rhythm.ui.screens

import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import com.elchanan.rhythm.ui.theme.Surface3
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.ColumnScope
import com.elchanan.rhythm.ui.components.DialogBody
import com.elchanan.rhythm.ui.theme.localized

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import com.elchanan.rhythm.ui.theme.Text
import androidx.compose.material3.TextButton
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
import com.elchanan.rhythm.data.ArtistShelf
import com.elchanan.rhythm.data.ArtistShelves
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.engine.Styles
import com.elchanan.rhythm.ui.AlbumInfo
import com.elchanan.rhythm.ui.MainViewModel
import com.elchanan.rhythm.ui.components.Artwork
import com.elchanan.rhythm.ui.components.Chip
import com.elchanan.rhythm.ui.components.EmptyState
import com.elchanan.rhythm.ui.components.SongRow
import com.elchanan.rhythm.ui.components.StarRow
import com.elchanan.rhythm.ui.components.rememberMetrics
import com.elchanan.rhythm.ui.theme.Accent
import com.elchanan.rhythm.ui.theme.AppBackground
import com.elchanan.rhythm.ui.theme.Bg
import com.elchanan.rhythm.ui.theme.Surface1
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
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = localized("חזור"))
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
    val selection by vm.selection.collectAsStateWithLifecycle()
    var sheetSong by remember { mutableStateOf<SongEntity?>(null) }

    val data = detail
    Column(modifier = Modifier.fillMaxSize().background(AppBackground)) {
        DetailTopBar(title = data?.title.orEmpty(), onBack = onBack)
        if (data == null) {
            EmptyState(title = "אין מה להציג", body = "אפשר לחזור אחורה ולבחור רשימה.")
            return@Column
        }
        if (data.loading) {
            EmptyState(title = "טוען…", body = data.subtitle.orEmpty())
            return@Column
        }
        val songs = data.songs
        if (songs.isEmpty()) {
            EmptyState(title = data.title, body = data.subtitle ?: "אין כאן שירים")
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 40.dp)) {
            item {
                val (c1, c2) = gradientFor(data.gradientKey)
                AdaptiveHeader(
                    stackedSize = 150.dp,
                    centred = false,
                    cover = { side ->
                        Box(
                            modifier = Modifier
                                .size(side)
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
                    }
                ) {
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
                val own = library.stats[song.id]
                SongRow(
                    song = song,
                    liked = own?.liked ?: 0,
                    rating = own?.rating ?: 0,
                    // Wherever a guessed song turns up, it says so. The point
                    // of showing the guesses is that they can be checked, and
                    // a list of titles with nothing beside them cannot be.
                    note = if (own?.stylesAuto == 1 && own.styles.isNotBlank()) {
                        "תויג אוטומטית: ${own.styles}"
                    } else {
                        null
                    },
                    selected = song.id in selection,
                    selectionMode = selection.isNotEmpty(),
                    onClick = {
                        if (selection.isNotEmpty()) vm.toggleSelect(song.id)
                        else vm.playList(songs, songs.indexOf(song))
                    },
                    onLongClick = {
                        vm.noteSelectionScope(songs.map { it.id })
                        vm.toggleSelect(song.id)
                    },
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
    val selection by vm.selection.collectAsStateWithLifecycle()
    var sheetSong by remember { mutableStateOf<SongEntity?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    val gutter = rememberMetrics().gutter

    // The listener's choice, kept: an artist page is usually opened to find
    // an album, so the albums come first unless they said otherwise.
    var groupByAlbum by remember { mutableStateOf(vm.prefs.artistByAlbum) }
    val info = artist
    Column(modifier = Modifier.fillMaxSize().background(AppBackground)) {
        DetailTopBar(title = info?.displayName.orEmpty(), onBack = onBack)
        if (info == null) {
            EmptyState(title = "לא נבחר אמן", body = "אפשר לבחור אמן מהספרייה.")
            return@Column
        }
        val live = library.artists.firstOrNull { it.key == info.key } ?: info
        val selectedStyles = Styles.parse(live.styles)
        val shelves = remember(live.songs) { ArtistShelves.of(live.songs, looseName = "שירים בודדים") }
        // Closed to begin with, unless the artist has one album and nothing
        // else, where closed would be one more tap for nothing.
        var openAlbums by remember(live.key) {
            mutableStateOf(shelves.singleOrNull()?.albumId?.let { setOf(it) } ?: emptySet())
        }
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        val displayedSongs = if (groupByAlbum) shelves.flatMap { it.songs } else live.songs
        val songRow: @Composable (SongEntity) -> Unit = { song ->
            SongRow(
                song = song,
                liked = library.stats[song.id]?.liked ?: 0,
                rating = library.stats[song.id]?.rating ?: 0,
                selected = song.id in selection,
                selectionMode = selection.isNotEmpty(),
                onClick = {
                    if (selection.isNotEmpty()) vm.toggleSelect(song.id)
                    else vm.playList(displayedSongs, displayedSongs.indexOf(song))
                },
                onLongClick = {
                    vm.noteSelectionScope(displayedSongs.map { it.id })
                    vm.toggleSelect(song.id)
                },
                onMore = { sheetSong = song },
                onLike = { vm.like(song.id) },
                onDislike = { vm.dislike(song.id) }
            )
        }

        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 40.dp)) {
            item {
                val (c1, c2) = gradientFor(live.key)
                AdaptiveHeader(
                    stackedSize = 120.dp,
                    centred = true,
                    cover = { side ->
                        Box(
                            modifier = Modifier
                                .size(side)
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
                    }
                ) {
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
                            onClick = { vm.playList(displayedSongs) },
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
                Column(modifier = Modifier.padding(horizontal = gutter)) {
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
                    val plays = live.songs.sumOf { library.stats[it.id]?.playCount ?: 0 }
                    if (plays > 0) {
                        Spacer(Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("השמעות", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "$plays בסך הכל",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                            Chip(
                                label = "אפס",
                                selected = false,
                                onClick = { confirmReset = true }
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("השירים", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("לפי אלבומים", selected = groupByAlbum, onClick = {
                            groupByAlbum = true; vm.prefs.artistByAlbum = true
                        })
                        Chip("כל השירים", selected = !groupByAlbum, onClick = {
                            groupByAlbum = false; vm.prefs.artistByAlbum = false
                        })
                    }
                }
            }

            if (groupByAlbum) {
                // One heading would only repeat the page's own title.
                val headed = !(shelves.size == 1 && shelves[0].albumId == null)
                shelves.forEach { shelf ->
                    val albumId = shelf.albumId
                    // An album is closed until it is opened, and its songs
                    // then sit inside it: indented, with a line down their
                    // side, and a button at the end that closes it again -
                    // so a long album does not have to be scrolled back up
                    // to put away. Loose songs are not an album and are
                    // always shown as they are.
                    val open = albumId == null || albumId in openAlbums
                    if (headed) {
                        item(key = "shelf:${albumId ?: "loose"}") {
                            ShelfHeading(
                                shelf = shelf,
                                expanded = if (albumId == null) null else open,
                                onToggle = albumId?.let { id ->
                                    { openAlbums = if (id in openAlbums) openAlbums - id else openAlbums + id }
                                },
                                onOpen = albumId?.let { id ->
                                    {
                                        library.albums.firstOrNull { it.albumId == id }?.let {
                                            vm.openList(it.name, it.artistName, it.songs, "album:${it.albumId}")
                                            onOpenDetail()
                                        }
                                    }
                                },
                                onPlay = { vm.playList(shelf.songs) }
                            )
                        }
                    }
                    if (open) {
                        if (albumId == null || !headed) {
                            items(shelf.songs, key = { it.id }) { song -> songRow(song) }
                        } else {
                            items(shelf.songs, key = { it.id }) { song -> InsideAlbum { songRow(song) } }
                            item(key = "close:$albumId") {
                                CloseAlbumRow(onClose = {
                                    // Back to the album's own row, which may be
                                    // far above by now; it keeps its place in
                                    // the list, since only what follows it
                                    // changes.
                                    val here = listState.layoutInfo.visibleItemsInfo
                                        .firstOrNull { it.key == "close:$albumId" }?.index
                                    openAlbums = openAlbums - albumId
                                    if (here != null) {
                                        scope.launch { listState.scrollToItem(maxOf(0, here - shelf.songs.size - 1)) }
                                    }
                                })
                            }
                        }
                    }
                }
            } else {
                items(live.songs, key = { it.id }) { song -> songRow(song) }
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

    if (confirmReset && info != null) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            containerColor = Surface1,
            title = { Text("לאפס את ההשמעות?") },
            text = {
                DialogBody {
                    Text(
                        "כל ההשמעות של ${info.displayName} יתאפסו, והשירים ייעלמו מ\"הושמעו לאחרונה\". " +
                            "הדירוג, הסגנונות והלייקים נשארים.",
                        color = TextSecondary
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.resetArtistPlayCounts(info.key, info.displayName)
                        confirmReset = false
                    }
                ) { Text("אפס", color = Accent) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) {
                    Text("ביטול", color = TextSecondary)
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShelfHeading(
    shelf: ArtistShelf,
    /** Whether the album is open, or null for the loose songs, which do not fold. */
    expanded: Boolean?,
    onToggle: (() -> Unit)?,
    onOpen: (() -> Unit)?,
    onPlay: () -> Unit
) {
    val gutter = rememberMetrics().gutter
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .then(
                // A tap opens and closes the album where it is; a long press
                // goes to the album's own page, as the tap used to.
                if (onToggle != null) {
                    Modifier.combinedClickable(onClick = onToggle, onLongClick = onOpen)
                } else {
                    Modifier
                }
            )
            .padding(start = gutter, end = gutter - 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Read once: the shelf comes from :engine, where the compiler cannot
        // assume the property stays what it was checked to be.
        val albumId = shelf.albumId
        if (albumId != null) {
            Artwork(shelf.songs.first().id, albumId, shelf.name, Modifier.size(52.dp), corner = 10)
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                shelf.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row {
                if (shelf.year > 0) {
                    Text("${shelf.year} · ", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
                Text("${shelf.songs.size} שירים", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        }
        IconButton(onClick = onPlay) {
            Icon(Icons.Filled.PlayArrow, contentDescription = localized("נגן"), tint = Accent)
        }
        if (onOpen != null) {
            IconButton(onClick = onOpen) {
                Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = localized("לעמוד האלבום"), tint = TextSecondary)
            }
        }
        if (expanded != null) {
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = localized(if (expanded) "סגור אלבום" else "פתח אלבום"),
                tint = TextSecondary
            )
        }
    }
}

/** A song inside an open album: set in, with a line down its side that ties it to the album above. */
@Composable
private fun InsideAlbum(content: @Composable () -> Unit) {
    val gutter = rememberMetrics().gutter
    val line = Surface3
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = gutter + 20.dp)
            // Drawn rather than laid out, so the line is as tall as the row
            // without measuring the row twice. At the start edge - the
            // right, in Hebrew.
            .drawBehind {
                val x = if (layoutDirection == LayoutDirection.Rtl) size.width - 1.dp.toPx() else 1.dp.toPx()
                drawLine(line, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
            }
    ) { content() }
}

/** The end of an open album, with the way to close it where the reading stopped. */
@Composable
private fun CloseAlbumRow(onClose: () -> Unit) {
    val gutter = rememberMetrics().gutter
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = gutter + 20.dp, top = 2.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Surface1)
                .clickable(onClick = onClose)
                .padding(start = 10.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("סגור אלבום", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
        }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumsScreen(vm: MainViewModel, onBack: () -> Unit, onOpenDetail: () -> Unit) {
    val library by vm.library.collectAsStateWithLifecycle()
    // The same gesture as the list view of the same albums, and the same bar
    // underneath it - which is now the app's one bar, drawn in RhythmRoot.
    // A cover in a grid is still a row of songs.
    val selection by vm.selection.collectAsStateWithLifecycle()
    val selectionMode = selection.isNotEmpty()

    fun toggle(album: AlbumInfo) {
        vm.toggleGroup(album.songs.map { it.id })
    }

    Column(modifier = Modifier.fillMaxSize().background(AppBackground)) {
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
                val picked = album.songs.isNotEmpty() &&
                    selection.containsAll(album.songs.map { it.id })
                Column(
                    modifier = Modifier.combinedClickable(
                        onClick = {
                            if (selectionMode) {
                                toggle(album)
                            } else {
                                vm.openList(
                                    album.name,
                                    album.artistName,
                                    album.songs,
                                    "album:${album.albumId}"
                                )
                                onOpenDetail()
                            }
                        },
                        onLongClick = { toggle(album) }
                    )
                ) {
                    Box {
                        Artwork(
                            -1L,
                            album.albumId,
                            album.name,
                            Modifier.fillMaxWidth().aspectRatio(1f),
                            corner = 12
                        )
                        // Over the cover rather than beside it: in a grid
                        // there is no margin to put it in, and a tick that
                        // shifted the artwork would make the whole grid jump
                        // the moment anything was selected.
                        if (selectionMode) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(6.dp)
                                    .clip(CircleShape)
                                    .background(Surface1.copy(alpha = 0.85f))
                                    .padding(3.dp)
                            ) {
                                SelectionTick(picked)
                            }
                        }
                    }
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

/**
 * The top of a list page: its cover and what it is, with the buttons.
 *
 * Stacked on a phone held upright. Side by side wherever there is width to
 * spare or little height: stacked, on a phone on its side, the header alone
 * filled the screen and the list began below the fold.
 */
@Composable
private fun AdaptiveHeader(
    stackedSize: Dp,
    centred: Boolean,
    cover: @Composable (Dp) -> Unit,
    info: @Composable ColumnScope.() -> Unit
) {
    val metrics = rememberMetrics()
    if (metrics.isShort || !metrics.isCompact) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            cover(if (metrics.isShort) minOf(stackedSize, 112.dp) else stackedSize)
            Spacer(Modifier.width(18.dp))
            Column(modifier = Modifier.weight(1f), content = info)
        }
    } else {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = if (centred) Alignment.CenterHorizontally else Alignment.Start
        ) {
            cover(stackedSize)
            Spacer(Modifier.height(12.dp))
            info()
        }
    }
}
