package com.elchanan.rhythm.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.ui.ArtistInfo
import com.elchanan.rhythm.ui.LibraryState
import com.elchanan.rhythm.ui.MainViewModel
import com.elchanan.rhythm.ui.components.AlphabetIndex
import com.elchanan.rhythm.ui.components.Artwork
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.saveable.rememberSaveable
import com.elchanan.rhythm.engine.Folders
import com.elchanan.rhythm.ui.components.Chip
import com.elchanan.rhythm.ui.theme.TextTertiary
import com.elchanan.rhythm.ui.components.EmptyState
import com.elchanan.rhythm.ui.components.SongRow
import com.elchanan.rhythm.ui.components.StarRow
import com.elchanan.rhythm.ui.components.rememberMetrics
import com.elchanan.rhythm.ui.theme.Accent
import com.elchanan.rhythm.ui.theme.Accent2
import com.elchanan.rhythm.ui.theme.AppBackground
import com.elchanan.rhythm.ui.theme.Bg
import com.elchanan.rhythm.ui.theme.BgElevated
import com.elchanan.rhythm.ui.theme.Color_Error
import com.elchanan.rhythm.ui.theme.Surface1
import com.elchanan.rhythm.ui.theme.TextSecondary
import com.elchanan.rhythm.ui.theme.gradientFor
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The library tabs, in the order they are shown.
 *
 * Named rather than numbered because the display order is a product decision
 * that changes, and a `when` over positions silently means something different
 * the moment the list is reordered. The first entry is what the tab opens on.
 */
private enum class LibraryTab(val label: String) {
    PLAYLISTS("פלייליסטים"),
    FOLDERS("תיקיות"),
    LIKED("אהובים"),
    ARTISTS("אמנים"),
    SONGS("שירים"),
    ALBUMS("אלבומים"),
    SPOKEN("הרצאות")
}

private enum class SongSort(val label: String) {
    TITLE("שם"),
    ARTIST("אמן"),
    ADDED("נוסף לאחרונה"),
    PLAYS("הכי מושמע"),
    RATING("דירוג")
}

/** Hebrew first, then a latin bucket and a catch-all. */
private val INDEX_LETTERS: List<String> =
    ("אבגדהוזחטיכלמנסעפצקרשת".map { it.toString() }) + listOf("A", "#")

/** The last segment of a folder path, which is the part people recognise. */
private fun folderName(path: String): String =
    path.trimEnd('/').substringAfterLast('/').ifEmpty { path }

private fun initialOf(title: String): String {
    val c = title.trim().firstOrNull() ?: return "#"
    return when {
        c in 'א'..'ת' -> when (c) {
            'ך' -> "כ"
            'ם' -> "מ"
            'ן' -> "נ"
            'ף' -> "פ"
            'ץ' -> "צ"
            else -> c.toString()
        }
        c.isLetter() -> "A"
        else -> "#"
    }
}

@Composable
fun LibraryScreen(
    vm: MainViewModel,
    onOpenDetail: () -> Unit,
    onOpenArtist: () -> Unit,
    onOpenAlbums: () -> Unit,
    onOpenRatings: () -> Unit
) {
    val library by vm.library.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val gutter = rememberMetrics().gutter
    // Which tab the library opens on is a preference, so someone who lives in
    // their folders does not land on playlists every single time.
    var tab by remember {
        val wanted = vm.prefs.libraryFirstTab
        mutableStateOf(LibraryTab.entries.indexOfFirst { it.name == wanted }.coerceAtLeast(0))
    }
    var sheetSong by remember { mutableStateOf<SongEntity?>(null) }
    var newPlaylist by remember { mutableStateOf(false) }
    var sort by remember { mutableStateOf(SongSort.TITLE) }
    var sortOpen by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    var selection by remember { mutableStateOf(setOf<Long>()) }
    // Selecting starts with a long press and ends when the last one is
    // deselected, so there is no separate mode to turn on: having anything
    // selected *is* the mode. The tabs that show songs all read this.
    val selectionMode = selection.isNotEmpty()

    val topPad = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    fun sorted(list: List<SongEntity>): List<SongEntity> {
        val filtered = if (filter.isBlank()) list else {
            val q = filter.lowercase(Locale.ROOT)
            list.filter {
                it.titleLower.contains(q) || it.artistName.lowercase(Locale.ROOT).contains(q)
            }
        }
        return when (sort) {
            SongSort.TITLE -> filtered.sortedBy { it.titleLower }
            SongSort.ARTIST -> filtered.sortedWith(
                compareBy({ it.artistName.lowercase(Locale.ROOT) }, { it.titleLower })
            )
            SongSort.ADDED -> filtered.sortedByDescending { it.dateAddedSec }
            SongSort.PLAYS -> filtered.sortedByDescending { library.stats[it.id]?.playCount ?: 0 }
            SongSort.RATING -> filtered.sortedByDescending { library.stats[it.id]?.rating ?: 0 }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(AppBackground)) {
        Text(
            text = "הספרייה שלי",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = topPad).padding(horizontal = gutter, vertical = 10.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = gutter),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(LibraryTab.entries.size) { index ->
                Chip(
                    label = LibraryTab.entries[index].label,
                    selected = tab == index,
                    onClick = {
                        tab = index
                        selection = emptySet()
                    }
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        Box(modifier = Modifier.weight(1f)) {
            when (LibraryTab.entries[tab]) {
                LibraryTab.SONGS -> SongTab(
                    songs = sorted(library.songs),
                    vm = vm,
                    selection = selection,
                    onToggleSelect = { id ->
                        selection = if (id in selection) selection - id else selection + id
                    },
                    onMore = { sheetSong = it },
                    sortLabel = sort.label,
                    onSort = { sortOpen = true },
                    filter = filter,
                    onFilter = { filter = it }
                )

                LibraryTab.LIKED -> {
                    val liked = sorted(library.liked)
                    if (library.liked.isEmpty()) {
                        EmptyState(
                            title = "עוד אין אהובים",
                            body = "לייק על שיר מלמד את האלגוריתם מה לחפש."
                        )
                    } else {
                        SongTab(
                            songs = liked,
                            vm = vm,
                            selection = selection,
                            onToggleSelect = { id ->
                                selection = if (id in selection) selection - id else selection + id
                            },
                            onMore = { sheetSong = it },
                            sortLabel = sort.label,
                            onSort = { sortOpen = true },
                            filter = filter,
                            onFilter = { filter = it }
                        )
                    }
                }

                LibraryTab.ARTISTS -> LazyColumn(contentPadding = PaddingValues(bottom = 40.dp)) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onOpenRatings)
                                .padding(horizontal = gutter, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Favorite, contentDescription = null, tint = Accent)
                            Spacer(Modifier.width(12.dp))
                            Text("דירוג אמנים וסגנונות", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    items(library.artists, key = { it.key }) { artist ->
                        ArtistRow(artist) {
                            vm.openArtist(artist)
                            onOpenArtist()
                        }
                    }
                }

                LibraryTab.ALBUMS -> LazyColumn(contentPadding = PaddingValues(bottom = 40.dp)) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onOpenAlbums)
                                .padding(horizontal = gutter, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("תצוגת רשת", style = MaterialTheme.typography.labelLarge, color = Accent)
                        }
                    }
                    items(library.albums, key = { it.albumId }) { album ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.openList(
                                        album.name,
                                        album.artistName,
                                        album.songs,
                                        "album:${album.albumId}"
                                    )
                                    onOpenDetail()
                                }
                                .padding(horizontal = gutter, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Artwork(-1L, album.albumId, album.name, Modifier.size(52.dp), corner = 8)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(album.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                                Text(
                                    "${album.artistName} · ${album.songs.size} שירים",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                LibraryTab.PLAYLISTS -> LazyColumn(contentPadding = PaddingValues(bottom = 40.dp)) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { newPlaylist = true }
                                .padding(horizontal = gutter, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Surface1),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Filled.Add, contentDescription = null, tint = Accent)
                            }
                            Spacer(Modifier.width(12.dp))
                            Text("רשימה חדשה", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    // Liked songs as an auto playlist. Deliberately derived from the
                    // likes rather than stored as its own list: a real playlist would
                    // drift the moment a like is taken back, and there would then be
                    // two disagreeing answers to "what did I like".
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.openList(
                                        "השירים שאהבתי",
                                        "${library.liked.size} שירים",
                                        library.liked,
                                        "auto:liked"
                                    )
                                    onOpenDetail()
                                }
                                .padding(horizontal = gutter, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Brush.linearGradient(listOf(Accent, Accent2))),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Filled.ThumbUp,
                                    contentDescription = null,
                                    tint = Color.White
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "השירים שאהבתי",
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1
                                )
                                Text(
                                    "מתעדכן לבד · ${library.liked.size} שירים",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                        }
                    }

                    items(playlists, key = { it.playlist.id }) { info ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.openList(
                                        info.playlist.name,
                                        "${info.songs.size} שירים",
                                        info.songs,
                                        "pl:${info.playlist.id}",
                                        playlistId = info.playlist.id
                                    )
                                    onOpenDetail()
                                }
                                .padding(horizontal = gutter, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val (c1, c2) = gradientFor("pl:${info.playlist.id}")
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Brush.linearGradient(listOf(c1, c2))),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Filled.PlaylistPlay, contentDescription = null, tint = Color.White)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(info.playlist.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                                Text(
                                    "${info.songs.size} שירים",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                            IconButton(onClick = { vm.deletePlaylist(info.playlist.id) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "מחק", tint = TextSecondary)
                            }
                        }
                    }
                }

                // Folders. The one view that matches how the files are actually
                // arranged on the device, which is how a lot of people think
                // about their own music - especially when the tags are a mess
                // and the folder name is the only reliable label there is.
                // Talking rather than music: shiurim, stories, recorded
                // lectures. They resume where they were left and they are the
                // one thing in the library nobody wants shuffled.
                LibraryTab.SPOKEN -> {
                    val spoken by vm.spokenWord.collectAsStateWithLifecycle()
                    val positions by vm.positions.collectAsStateWithLifecycle()
                    if (spoken.isEmpty()) {
                        EmptyState(
                            title = "לא נמצאו הרצאות",
                            body = "הזיהוי מחפש הקלטות ארוכות שנשמעות כמו דיבור ולא כמו " +
                                "מוזיקה. אם משהו סווג לא נכון, אפשר לתקן ידנית מתפריט " +
                                "שלוש הנקודות של השיר."
                        )
                    } else {
                        LazyColumn(contentPadding = PaddingValues(bottom = 40.dp)) {
                            items(spoken, key = { it.id }) { song ->
                                val stats = library.stats[song.id]
                                val at = positions[song.id]
                                SongRow(
                                    song = song,
                                    liked = stats?.liked ?: 0,
                                    rating = stats?.rating ?: 0,
                                    playCount = stats?.playCount ?: 0,
                                    selected = song.id in selection,
                                    selectionMode = selectionMode,
                                    onClick = {
                                        if (selectionMode) {
                                            selection = if (song.id in selection) {
                                                selection - song.id
                                            } else {
                                                selection + song.id
                                            }
                                        } else {
                                            vm.playList(spoken, spoken.indexOf(song))
                                        }
                                    },
                                    onLongClick = { selection = selection + song.id },
                                    onMore = { sheetSong = song },
                                    trailing = if (at != null && at.positionMs > 0) {
                                        {
                                            // How far in they are, which is the
                                            // only thing about a part-heard
                                            // recording worth showing here.
                                            Text(
                                                text = formatClock(at.positionMs),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Accent
                                            )
                                        }
                                    } else {
                                        null
                                    }
                                )
                            }
                        }
                    }
                }

                LibraryTab.FOLDERS -> {
                    if (vm.prefs.folderTree) {
                        FolderTreeTab(
                            vm = vm,
                            library = library,
                            gutter = gutter,
                            onMore = { sheetSong = it },
                            onOpenDetail = onOpenDetail
                        )
                    } else {
                        FolderListTab(
                            vm = vm,
                            songs = library.songs,
                            gutter = gutter,
                            onOpenDetail = onOpenDetail
                        )
                    }
                }
            }
        }

        if (selection.isNotEmpty()) {
            SelectionBar(
                vm = vm,
                selection = selection,
                songs = library.songs.filter { it.id in selection },
                onClear = { selection = emptySet() }
            )
        }
    }

    if (sortOpen) {
        AlertDialog(
            onDismissRequest = { sortOpen = false },
            containerColor = Surface1,
            title = { Text("מיון") },
            text = {
                Column {
                    SongSort.entries.forEach { option ->
                        Text(
                            text = option.label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (option == sort) Accent else MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { sort = option; sortOpen = false }
                                .padding(vertical = 11.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { sortOpen = false }) { Text("סגור", color = TextSecondary) }
            }
        )
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
                    label = { Text("שם") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) vm.createPlaylist(name.trim())
                    newPlaylist = false
                }) { Text("צור", color = Accent) }
            },
            dismissButton = {
                TextButton(onClick = { newPlaylist = false }) { Text("ביטול", color = TextSecondary) }
            }
        )
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
            },
            onOpenAlbum = {
                library.albums.firstOrNull { it.albumId == song.albumId }?.let {
                    vm.openList(it.name, it.artistName, it.songs, "album:${it.albumId}")
                    onOpenDetail()
                }
            }
        )
    }
}

@Composable
private fun SongTab(
    songs: List<SongEntity>,
    vm: MainViewModel,
    selection: Set<Long>,
    onToggleSelect: (Long) -> Unit,
    onMore: (SongEntity) -> Unit,
    sortLabel: String,
    onSort: () -> Unit,
    filter: String,
    onFilter: (String) -> Unit
) {
    val library by vm.library.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val selectionMode = selection.isNotEmpty()
    val gutter = rememberMetrics().gutter

    val presentLetters = remember(songs) {
        val set = songs.mapTo(HashSet()) { initialOf(it.title) }
        INDEX_LETTERS.filter { it in set }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = 40.dp, end = 22.dp)
        ) {
            item {
                Column {
                    OutlinedTextField(
                        value = filter,
                        onValueChange = onFilter,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = gutter, vertical = 4.dp),
                        placeholder = { Text("סינון מהיר", color = TextSecondary) },
                        singleLine = true
                    )
                    Row(
                        modifier = Modifier.padding(horizontal = gutter, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { vm.shuffleList(songs) },
                            colors = ButtonDefaults.buttonColors(containerColor = Accent)
                        ) {
                            Icon(Icons.Filled.Shuffle, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("ערבב הכל")
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "${songs.size} שירים",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                        Spacer(Modifier.weight(1f))
                        Row(
                            modifier = Modifier.clickable(onClick = onSort).padding(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Sort, contentDescription = null, tint = TextSecondary)
                            Spacer(Modifier.width(4.dp))
                            Text(sortLabel, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                        }
                    }
                }
            }
            items(songs, key = { it.id }) { song ->
                val stats = library.stats[song.id]
                SongRow(
                    song = song,
                    liked = stats?.liked ?: 0,
                    rating = stats?.rating ?: 0,
                    playCount = stats?.playCount ?: 0,
                    selected = song.id in selection,
                    selectionMode = selectionMode,
                    onClick = {
                        if (selectionMode) onToggleSelect(song.id)
                        else vm.playList(songs, songs.indexOf(song))
                    },
                    onLongClick = { onToggleSelect(song.id) },
                    onMore = { onMore(song) },
                    onLike = { vm.like(song.id) },
                    onDislike = { vm.dislike(song.id) }
                )
            }
        }

        AlphabetIndex(
            letters = presentLetters,
            modifier = Modifier.align(Alignment.CenterEnd)
        ) { letter ->
            val index = songs.indexOfFirst { initialOf(it.title) == letter }
            if (index >= 0) {
                scope.launch { listState.scrollToItem(index + 1) }
            }
        }
    }
}

@Composable
private fun SelectionBar(
    vm: MainViewModel,
    selection: Set<Long>,
    songs: List<SongEntity>,
    onClear: () -> Unit
) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    var rateOpen by remember { mutableStateOf(false) }
    var playlistOpen by remember { mutableStateOf(false) }
    var genreOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }
    val ids = selection.toList()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BgElevated)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onClear) {
            Icon(Icons.Filled.Close, contentDescription = "בטל", tint = TextSecondary)
        }
        Text(
            "${selection.size} נבחרו",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(end = 6.dp)
        )
        Spacer(Modifier.weight(1f))
        BarAction(Icons.Filled.PlayArrow, "נגן") { vm.playList(songs); onClear() }
        BarAction(Icons.Filled.QueueMusic, "לתור") { vm.bulkQueue(songs); onClear() }
        BarAction(Icons.Filled.ThumbUp, "לייק") { vm.bulkLikeSongs(ids, 1); onClear() }
        BarAction(Icons.Filled.Star, "דרג") { rateOpen = true }
        BarAction(Icons.Filled.PlaylistAdd, "לרשימה") { playlistOpen = true }
        BarAction(Icons.Filled.LocalOffer, "ז'אנר") { genreOpen = true }
        BarAction(Icons.Filled.Share, "שתף") { vm.shareSongs(songs); onClear() }
        BarAction(Icons.Filled.Delete, "מחק") { deleteOpen = true }
    }

    if (genreOpen) {
        GenreDialog(
            initial = "",
            count = selection.size,
            onDismiss = { genreOpen = false },
            onApply = { vm.setGenre(ids, it); onClear() }
        )
    }

    if (deleteOpen) {
        AlertDialog(
            onDismissRequest = { deleteOpen = false },
            containerColor = Surface1,
            title = { Text("למחוק ${selection.size} קבצים?") },
            text = {
                Text(
                    "הקבצים יימחקו מהמכשיר עצמו, לא רק מהאפליקציה. אי אפשר לבטל את זה.",
                    color = TextSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteSongs(songs)
                    deleteOpen = false
                    onClear()
                }) { Text("מחק", color = Color_Error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteOpen = false }) {
                    Text("ביטול", color = TextSecondary)
                }
            }
        )
    }

    if (rateOpen) {
        var rating by remember { mutableStateOf(5) }
        AlertDialog(
            onDismissRequest = { rateOpen = false },
            containerColor = Surface1,
            title = { Text("דירוג ${selection.size} שירים") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    StarRow(rating = rating, onRate = { rating = if (it == 0) 1 else it }, size = 32)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.bulkRateSongs(ids, rating)
                    rateOpen = false
                    onClear()
                }) { Text("שמור", color = Accent) }
            },
            dismissButton = {
                TextButton(onClick = { rateOpen = false }) { Text("ביטול", color = TextSecondary) }
            }
        )
    }

    if (playlistOpen) {
        AlertDialog(
            onDismissRequest = { playlistOpen = false },
            containerColor = Surface1,
            title = { Text("הוספה לרשימה") },
            text = {
                Column {
                    if (playlists.isEmpty()) {
                        Text("אין עדיין רשימות", color = TextSecondary)
                    }
                    playlists.forEach { info ->
                        Text(
                            text = info.playlist.name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.bulkAddToPlaylist(info.playlist.id, ids)
                                    playlistOpen = false
                                    onClear()
                                }
                                .padding(vertical = 11.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { playlistOpen = false }) { Text("סגור", color = TextSecondary) }
            }
        )
    }
}

@Composable
private fun BarAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = label, tint = Accent, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
    }
}

@Composable
private fun ArtistRow(artist: ArtistInfo, onClick: () -> Unit) {
    val (c1, c2) = gradientFor(artist.key)
    val gutter = rememberMetrics().gutter
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = gutter, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(c1, c2))),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = artist.displayName.take(1),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(artist.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(
                text = if (artist.styles.isBlank()) "${artist.songs.size} שירים"
                else "${artist.songs.size} שירים · ${artist.styles}",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 1
            )
        }
        StarRow(rating = artist.rating, size = 14)
    }
}

// --- folders ----------------------------------------------------------------

/**
 * The folders as they sit on the device, one inside another.
 *
 * Navigated in place rather than by pushing screens: the tab keeps its own
 * position, so switching away and back lands where it was left, and the system
 * back button climbs a level instead of leaving the library.
 */
@Composable
private fun FolderTreeTab(
    vm: MainViewModel,
    library: LibraryState,
    gutter: Dp,
    onMore: (SongEntity) -> Unit,
    onOpenDetail: () -> Unit
) {
    val root = remember(library.songs) { Folders.build(library.songs) }
    var path by rememberSaveable { mutableStateOf(root.path) }
    // A rescan can remove the folder being looked at, and a path that no
    // longer exists would otherwise show an empty screen with no way out.
    val here = remember(root, path) { Folders.find(root, path) ?: root }
    val trail = remember(root, here) { Folders.trail(root, here.path) }

    BackHandler(enabled = here.path != root.path) {
        path = trail.getOrNull(trail.size - 2)?.path ?: root.path
    }

    if (root.total == 0) {
        EmptyState(
            title = "לא נמצאו תיקיות",
            body = "התיקיות מופיעות אחרי שהאפליקציה סורקת את המכשיר."
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Where we are, and a way back to any level above without tapping back
        // once per folder.
        if (trail.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = gutter, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                trail.forEachIndexed { index, node ->
                    if (index > 0) {
                        Text(
                            " › ",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextTertiary
                        )
                    }
                    Text(
                        text = if (index == 0) "הכל" else node.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (node.path == here.path) Accent else TextSecondary,
                        maxLines = 1,
                        modifier = Modifier
                            .clickable(enabled = node.path != here.path) { path = node.path }
                            .padding(vertical = 4.dp)
                    )
                }
            }
        }

        LazyColumn(contentPadding = PaddingValues(bottom = 40.dp)) {
            // Playing a folder means everything under it, which is what
            // someone tapping a folder of folders is asking for.
            if (here.total > 0 && here.path != root.path) {
                item {
                    Row(
                        modifier = Modifier.padding(horizontal = gutter, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Chip(label = "נגן הכל", selected = false, onClick = {
                            val all = Folders.allSongs(here)
                            if (all.isNotEmpty()) vm.playList(all, 0, here.name)
                        })
                        Chip(label = "ערבב", selected = false, onClick = {
                            vm.shuffleList(Folders.allSongs(here))
                        })
                    }
                }
            }

            items(here.children, key = { it.path }) { child ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { path = child.path }
                        .padding(horizontal = gutter, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val (c1, c2) = gradientFor(child.path)
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Brush.linearGradient(listOf(c1, c2))),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.Folder, contentDescription = null, tint = Color.White)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            child.name,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = buildString {
                                append(child.total)
                                append(" שירים")
                                if (child.children.isNotEmpty()) {
                                    append(" · ")
                                    append(child.children.size)
                                    append(" תיקיות")
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            maxLines = 1
                        )
                    }
                    Icon(
                        Icons.Filled.ChevronLeft,
                        contentDescription = null,
                        tint = TextTertiary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            items(here.songs, key = { it.id }) { song ->
                val stats = library.stats[song.id]
                SongRow(
                    song = song,
                    liked = stats?.liked ?: 0,
                    rating = stats?.rating ?: 0,
                    playCount = stats?.playCount ?: 0,
                    onClick = { vm.playList(here.songs, here.songs.indexOf(song), here.name) },
                    onMore = { onMore(song) },
                    onLike = { vm.like(song.id) },
                    onDislike = { vm.dislike(song.id) }
                )
            }

            if (here.children.isEmpty() && here.songs.isEmpty()) {
                item {
                    EmptyState(title = "התיקייה ריקה", body = "אין כאן שירים שנסרקו.")
                }
            }
        }
    }
}

/** The older flat list, for anyone who preferred it. */
@Composable
private fun FolderListTab(
    vm: MainViewModel,
    songs: List<SongEntity>,
    gutter: Dp,
    onOpenDetail: () -> Unit
) {
    val folders = remember(songs) {
        songs.groupBy { it.folder }
            .map { (path, list) -> path to list }
            .sortedBy { it.first.lowercase(Locale.ROOT) }
    }
    if (folders.isEmpty()) {
        EmptyState(
            title = "לא נמצאו תיקיות",
            body = "התיקיות מופיעות אחרי שהאפליקציה סורקת את המכשיר."
        )
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 40.dp)) {
        items(folders, key = { it.first }) { (path, list) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        vm.openList(folderName(path), path, list, "folder:$path")
                        onOpenDetail()
                    }
                    .padding(horizontal = gutter, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val (c1, c2) = gradientFor(path)
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Brush.linearGradient(listOf(c1, c2))),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Folder, contentDescription = null, tint = Color.White)
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        folderName(path),
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1
                    )
                    Text(
                        "${list.size} שירים · $path",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
