package com.elchanan.rhythm.ui.screens

import com.elchanan.rhythm.ui.theme.localized

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import com.elchanan.rhythm.ui.theme.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.engine.Styles
import com.elchanan.rhythm.engine.AlphabetIndexing
import com.elchanan.rhythm.engine.Folders
import com.elchanan.rhythm.ui.ArtistInfo
import com.elchanan.rhythm.ui.LibraryState
import com.elchanan.rhythm.ui.MainViewModel
import com.elchanan.rhythm.ui.components.AlphabetIndex
import com.elchanan.rhythm.ui.components.Artwork
import com.elchanan.rhythm.ui.components.Chip
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
import com.elchanan.rhythm.ui.theme.Surface3
import com.elchanan.rhythm.ui.theme.TextSecondary
import com.elchanan.rhythm.ui.theme.TextTertiary
import com.elchanan.rhythm.ui.theme.gradientFor
import java.util.Locale
import kotlinx.coroutines.launch

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

/** The last segment of a folder path, which is the part people recognise. */
private fun folderName(path: String): String =
    path.trimEnd('/').substringAfterLast('/').ifEmpty { path }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    vm: MainViewModel,
    onOpenDetail: () -> Unit,
    onOpenArtist: () -> Unit,
    onOpenAlbums: () -> Unit,
    // The folders on their own, for the tab of their own on the bottom bar.
    foldersOnly: Boolean = false
) {
    val library by vm.library.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val gutter = rememberMetrics().gutter
    // Which tab the library opens on is a preference, so someone who lives in
    // their folders does not land on playlists every single time.
    var tab by remember {
        val wanted = if (foldersOnly) LibraryTab.FOLDERS.name else vm.prefs.libraryFirstTab
        mutableStateOf(LibraryTab.entries.indexOfFirst { it.name == wanted }.coerceAtLeast(0))
    }
    // A folder asked for from the player. The tree opens it itself; the flat
    // list has no place to stand in, so the folder opens as its song list.
    val folderRequest by vm.folderRequest.collectAsStateWithLifecycle()
    LaunchedEffect(folderRequest) {
        val wanted = folderRequest ?: return@LaunchedEffect
        tab = LibraryTab.FOLDERS.ordinal
        if (!vm.prefs.folderTree) {
            vm.folderRequest.value = null
            val songs = library.songs.filter { it.folder == wanted }
            if (songs.isNotEmpty()) {
                vm.openList(folderName(wanted), wanted, songs, "folder:$wanted")
                onOpenDetail()
            }
        }
    }
    var sheetSong by remember { mutableStateOf<SongEntity?>(null) }
    var newPlaylist by remember { mutableStateOf(false) }
    var sort by remember { mutableStateOf(SongSort.TITLE) }
    var sortOpen by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    // The app's one selection, not this screen's. See MainViewModel.selection.
    val selection by vm.selection.collectAsStateWithLifecycle()
    // Selecting starts with a long press and ends when the last one is
    // deselected, so there is no separate mode to turn on: having anything
    // selected *is* the mode. The tabs that show songs all read this.
    val selectionMode = selection.isNotEmpty()

    /**
     * Long pressing a group takes the whole group.
     *
     * An album, an artist, a folder and a playlist are all, from here, a set
     * of songs - so selecting one selects its songs and everything the
     * selection bar already does applies to it. One selection model with
     * several ways into it, rather than a separate bar per kind of row, each
     * offering a subset of the same actions.
     *
     * A group that is already entirely selected comes back out, so the same
     * gesture undoes itself.
     */
    fun toggleGroup(songs: List<SongEntity>) {
        val ids = songs.map { it.id }
        if (ids.isEmpty()) return
        vm.toggleGroup(ids)
    }

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
            text = if (foldersOnly) "תיקיות" else "הספרייה שלי",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = topPad).padding(horizontal = gutter, vertical = 10.dp)
        )
        if (!foldersOnly) LazyRow(
            contentPadding = PaddingValues(horizontal = gutter),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(LibraryTab.entries.size) { index ->
                Chip(
                    label = LibraryTab.entries[index].label,
                    selected = tab == index,
                    onClick = {
                        tab = index
                        vm.clearSelection()
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
                        vm.toggleSelect(id)
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
                                vm.toggleSelect(id)
                            },
                            onMore = { sheetSong = it },
                            sortLabel = sort.label,
                            onSort = { sortOpen = true },
                            filter = filter,
                            onFilter = { filter = it }
                        )
                    }
                }

                // Just the artists. Rating them, tagging them and merging two
                // spellings of one name all live on the אמנים tab, which is a
                // tap away on the bar at the bottom - a row here that only
                // opened it was a second door onto the same room.
                LibraryTab.ARTISTS -> LazyColumn(contentPadding = PaddingValues(bottom = 40.dp)) {
                    items(library.artists, key = { it.key }) { artist ->
                        ArtistRow(
                            artist = artist,
                            selected = artist.songs.isNotEmpty() &&
                                selection.containsAll(artist.songs.map { it.id }),
                            selectionMode = selectionMode,
                            onLongClick = { toggleGroup(artist.songs) },
                            onClick = {
                                if (selectionMode) {
                                    toggleGroup(artist.songs)
                                } else {
                                    vm.openArtist(artist)
                                    onOpenArtist()
                                }
                            }
                        )
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
                        val picked = album.songs.isNotEmpty() &&
                            selection.containsAll(album.songs.map { it.id })
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        if (selectionMode) {
                                            toggleGroup(album.songs)
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
                                    onLongClick = { toggleGroup(album.songs) }
                                )
                                .background(if (picked) Accent.copy(alpha = 0.16f) else Color.Transparent)
                                .padding(horizontal = gutter, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (selectionMode) {
                                SelectionTick(picked)
                                Spacer(Modifier.width(10.dp))
                            }
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
                        val picked = info.songs.isNotEmpty() &&
                            selection.containsAll(info.songs.map { it.id })
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        if (selectionMode) {
                                            toggleGroup(info.songs)
                                        } else {
                                            vm.openList(
                                                info.playlist.name,
                                                "${info.songs.size} שירים",
                                                info.songs,
                                                "pl:${info.playlist.id}",
                                                playlistId = info.playlist.id
                                            )
                                            onOpenDetail()
                                        }
                                    },
                                    onLongClick = { toggleGroup(info.songs) }
                                )
                                .background(if (picked) Accent.copy(alpha = 0.16f) else Color.Transparent)
                                .padding(horizontal = gutter, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (selectionMode) {
                                SelectionTick(picked)
                                Spacer(Modifier.width(10.dp))
                            }
                            val (c1, c2) = gradientFor("pl:${info.playlist.id}")
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Brush.linearGradient(listOf(c1, c2))),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = null, tint = Color.White)
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
                                Icon(Icons.Filled.Delete, contentDescription = localized("מחק"), tint = TextSecondary)
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
                                            vm.toggleSelect(song.id)
                                        } else {
                                            vm.playList(spoken, spoken.indexOf(song))
                                        }
                                    },
                                    onLongClick = {
                                        vm.noteSelectionScope(spoken.map { it.id })
                                        vm.toggleSelect(song.id)
                                    },
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
                            selection = selection,
                            onToggleGroup = { toggleGroup(it) },
                            onToggleSong = { id ->
                                vm.toggleSelect(id)
                            },
                            onMore = { sheetSong = it }
                        )
                    } else {
                        FolderListTab(
                            vm = vm,
                            songs = library.songs,
                            gutter = gutter,
                            selection = selection,
                            onToggleGroup = { toggleGroup(it) },
                            onOpenDetail = onOpenDetail
                        )
                    }
                }
            }
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
        AlphabetIndexing.present(songs.map { it.title })
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
                            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null, tint = TextSecondary)
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
                    onLongClick = {
                        vm.noteSelectionScope(songs.map { it.id })
                        onToggleSelect(song.id)
                    },
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
            val index = songs.indexOfFirst { AlphabetIndexing.initialOf(it.title) == letter }
            if (index >= 0) {
                scope.launch { listState.scrollToItem(index + 1) }
            }
        }
    }
}

@Composable
internal fun SelectionBar(vm: MainViewModel) {
    val selection by vm.selection.collectAsStateWithLifecycle()
    if (selection.isEmpty()) return
    val songs = remember(selection) { vm.selectedSongs() }
    val onClear: () -> Unit = { vm.clearSelection() }
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    var rateOpen by remember { mutableStateOf(false) }
    var playlistOpen by remember { mutableStateOf(false) }
    var genreOpen by remember { mutableStateOf(false) }
    var editOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    var styleOpen by remember { mutableStateOf(false) }
    val scope by vm.selectionScope.collectAsStateWithLifecycle()
    val ids = selection.toList()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BgElevated)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onClear) {
            Icon(Icons.Filled.Close, contentDescription = localized("בטל"), tint = TextSecondary)
        }
        // The count takes whatever room is left and gives it up first. It was
        // a fixed width, and the moment it reached three digits - a few
        // folders' worth - it pushed "more" off the end of a small screen,
        // taking every action behind it along.
        Text(
            "${selection.size} נבחרו",
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(end = 6.dp)
        )
        // Four on the bar and the rest behind the dots. Eight labelled icons
        // do not fit a phone: on a narrow screen they were squeezing each
        // other off the end, and which four survived depended on the device.
        BarAction(Icons.Filled.PlayArrow, "נגן") { vm.playList(songs); onClear() }
        BarAction(Icons.AutoMirrored.Filled.PlaylistPlay, "הבא") { vm.bulkPlayNext(songs); onClear() }
        BarAction(Icons.AutoMirrored.Filled.QueueMusic, "לתור") { vm.bulkQueue(songs); onClear() }
        Box {
            BarAction(Icons.Filled.MoreVert, "עוד") { moreOpen = true }
            DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                if (scope.isNotEmpty() && !selection.containsAll(scope)) {
                    DropdownMenuItem(
                        text = { Text("בחר הכל (${scope.size})") },
                        leadingIcon = { Icon(Icons.Filled.CheckCircle, contentDescription = null) },
                        onClick = { moreOpen = false; vm.selectAll() }
                    )
                }
                DropdownMenuItem(
                    text = { Text("סגנון") },
                    leadingIcon = { Icon(Icons.Filled.LocalOffer, contentDescription = null) },
                    onClick = { moreOpen = false; styleOpen = true }
                )
                DropdownMenuItem(
                    text = { Text("לייק") },
                    leadingIcon = { Icon(Icons.Filled.ThumbUp, contentDescription = null) },
                    onClick = { moreOpen = false; vm.bulkLikeSongs(ids, 1); onClear() }
                )
                DropdownMenuItem(
                    text = { Text("הוסף לרשימה") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null) },
                    onClick = { moreOpen = false; playlistOpen = true }
                )
                DropdownMenuItem(
                    text = { Text("דרג") },
                    leadingIcon = { Icon(Icons.Filled.Star, contentDescription = null) },
                    onClick = { moreOpen = false; rateOpen = true }
                )
                DropdownMenuItem(
                    text = { Text("עריכת תגיות") },
                    leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                    onClick = { moreOpen = false; editOpen = true }
                )
                DropdownMenuItem(
                    text = { Text("ז'אנר") },
                    leadingIcon = { Icon(Icons.Filled.LocalOffer, contentDescription = null) },
                    onClick = { moreOpen = false; genreOpen = true }
                )
                DropdownMenuItem(
                    text = { Text("שתף") },
                    leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                    onClick = { moreOpen = false; vm.shareSongs(songs); onClear() }
                )
                DropdownMenuItem(
                    text = { Text("מחק", color = Color_Error) },
                    leadingIcon = {
                        Icon(Icons.Filled.Delete, contentDescription = null, tint = Color_Error)
                    },
                    onClick = { moreOpen = false; deleteOpen = true }
                )
            }
        }
    }

    if (styleOpen) {
        BulkStyleDialog(
            title = "תגיות ל-${selection.size} שירים",
            subtitle = "תגית על שיר מחליפה את תגיות האמן עבורו",
            count = selection.size,
            onDismiss = { styleOpen = false },
            onApply = { styles, replace -> vm.tagFolder(songs, styles, replace); onClear() }
        )
    }

    if (editOpen) {
        SongEditDialog(vm = vm, songs = songs, onDismiss = { editOpen = false }, onSaved = onClear)
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
        Icon(icon, contentDescription = localized(label), tint = Accent, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArtistRow(
    artist: ArtistInfo,
    selected: Boolean = false,
    selectionMode: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val (c1, c2) = gradientFor(artist.key)
    val gutter = rememberMetrics().gutter
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onLongClick == null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                }
            )
            .background(if (selected) Accent.copy(alpha = 0.16f) else Color.Transparent)
            .padding(horizontal = gutter, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectionMode) {
            SelectionTick(selected)
            Spacer(Modifier.width(10.dp))
        }
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
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderTreeTab(
    vm: MainViewModel,
    library: LibraryState,
    gutter: Dp,
    selection: Set<Long>,
    onToggleGroup: (List<SongEntity>) -> Unit,
    onToggleSong: (Long) -> Unit,
    onMore: (SongEntity) -> Unit
) {
    val selectionMode = selection.isNotEmpty()
    val root = remember(library.songs) { Folders.build(library.songs) }
    var path by rememberSaveable { mutableStateOf(root.path) }
    val folderRequest by vm.folderRequest.collectAsStateWithLifecycle()
    LaunchedEffect(folderRequest, root) {
        val wanted = folderRequest ?: return@LaunchedEffect
        if (root.total == 0) return@LaunchedEffect
        vm.folderRequest.value = null
        Folders.find(root, Folders.pathOf(wanted))?.let { path = it.path }
    }
    // A rescan can remove the folder being looked at, and a path that no
    // longer exists would otherwise show an empty screen with no way out.
    val here = remember(root, path) { Folders.find(root, path) ?: root }
    val trail = remember(root, here) { Folders.trail(root, here.path) }
    // Which folder the style dialog is about, or null while it is closed.
    var tagging by remember { mutableStateOf<Folders.Node?>(null) }
    // Which folder the rating dialog is about, likewise.
    var rating by remember { mutableStateOf<Folders.Node?>(null) }

    rating?.let { node ->
        val inside = remember(node) { Folders.allSongs(node) }
        FolderRatingDialog(
            folderName = node.name,
            songs = inside,
            stats = library.stats,
            onDismiss = { rating = null },
            onApply = { ids, stars ->
                vm.bulkRateSongs(ids, stars)
                rating = null
            }
        )
    }

    tagging?.let { node ->
        val inside = remember(node) { Folders.allSongs(node) }
        FolderStyleDialog(
            folderName = node.name,
            count = inside.size,
            onDismiss = { tagging = null },
            onApply = { styles, replace -> vm.tagFolder(inside, styles, replace) }
        )
    }

    BackHandler(enabled = here.path != root.path) {
        path = trail.getOrNull(trail.size - 2)?.path ?: root.path
    }

    if (root.total == 0) {
        NoFolders(vm, gutter)
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (here.path == root.path) MainFolderRow(vm, gutter)
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
                    // Sideways rather than wrapped, like the player's row: the
                    // actions stay one line however narrow the phone.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = gutter, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Chip(label = "נגן הכל", selected = false, onClick = {
                            val all = Folders.allSongs(here)
                            if (all.isNotEmpty()) vm.playList(all, 0, here.name)
                        })
                        Chip(label = "ערבב", selected = false, onClick = {
                            vm.shuffleList(Folders.allSongs(here))
                        })
                        Chip(label = "תייג סגנון", selected = false, onClick = { tagging = here })
                        Chip(label = "דרג", selected = false, onClick = { rating = here })
                    }
                }
            }

            items(here.children, key = { it.path }) { child ->
                // Everything under it, not only what sits directly in it:
                // long pressing a folder of folders means the lot, which is
                // the same thing "play all" on it means.
                val inside = remember(child) { Folders.allSongs(child) }
                val picked = inside.isNotEmpty() && selection.containsAll(inside.map { it.id })
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = {
                                if (selectionMode) onToggleGroup(inside) else path = child.path
                            },
                            onLongClick = {
                                vm.noteSelectionScope(Folders.allSongs(here).map { it.id })
                                onToggleGroup(inside)
                            }
                        )
                        .background(if (picked) Accent.copy(alpha = 0.16f) else Color.Transparent)
                        .padding(horizontal = gutter, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (selectionMode) {
                        SelectionTick(picked)
                        Spacer(Modifier.width(10.dp))
                    }
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
                    if (!selectionMode) {
                        IconButton(onClick = { rating = child }) {
                            Icon(
                                Icons.Filled.Star,
                                contentDescription = localized("דרג את כל השירים בתיקייה"),
                                tint = TextTertiary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        IconButton(onClick = { tagging = child }) {
                            Icon(
                                Icons.Filled.LocalOffer,
                                contentDescription = localized("תייג סגנון לתיקייה"),
                                tint = TextTertiary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
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
                    selected = song.id in selection,
                    selectionMode = selectionMode,
                    onClick = {
                        if (selectionMode) {
                            onToggleSong(song.id)
                        } else {
                            vm.playList(here.songs, here.songs.indexOf(song), here.name)
                        }
                    },
                    onLongClick = {
                        vm.noteSelectionScope(Folders.allSongs(here).map { it.id })
                        onToggleSong(song.id)
                    },
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

/**
 * One rating for every song in a folder, subfolders included.
 *
 * Whether songs already rated one by one keep their own is asked, and
 * keeping them is the default: a rating given to one song on purpose says
 * more than one given to a folder in passing.
 */
@Composable
private fun FolderRatingDialog(
    folderName: String,
    songs: List<SongEntity>,
    stats: Map<Long, com.elchanan.rhythm.data.db.SongStatsEntity>,
    onDismiss: () -> Unit,
    onApply: (List<Long>, Int) -> Unit
) {
    var stars by remember { mutableStateOf(0) }
    var keepRated by remember { mutableStateOf(true) }
    val rated = songs.count { (stats[it.id]?.rating ?: 0) > 0 }
    val target = if (keepRated) songs.filter { (stats[it.id]?.rating ?: 0) == 0 } else songs
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        title = { Text("דירוג לתיקייה") },
        text = {
            Column {
                Text(
                    "\"$folderName\" · ${songs.size} שירים, כולל תת־תיקיות",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(Modifier.height(14.dp))
                StarRow(rating = stars, onRate = { stars = it }, size = 34)
                if (rated > 0) {
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip(label = "רק שירים בלי דירוג", selected = keepRated, onClick = { keepRated = true })
                        Chip(label = "כל השירים", selected = !keepRated, onClick = { keepRated = false })
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (keepRated) "$rated שירים שכבר דירגת אחד אחד ישמרו את הדירוג שלהם."
                        else "גם $rated השירים שכבר דירגת יקבלו את הדירוג הזה.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = stars > 0 && target.isNotEmpty(),
                onClick = { onApply(target.map { it.id }, stars) }
            ) { Text("דרג ${target.size} שירים", color = Accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("ביטול", color = TextSecondary) }
        }
    )
}

/**
 * Puts one set of style tags on every song in a folder.
 *
 * The shortcut that was missing. A downloaded library arrives as folders and
 * the folder is usually the answer for everything inside it, so tagging song
 * by song was most of the manual work the app asked for - and the reason a
 * library in daily use for months still had too few labels for the learner to
 * fit anything.
 *
 * Adding is the default and replacing is the opt-in, because the destructive
 * one is the one that has to be chosen on purpose. Either way a tag the app
 * guessed is overwritten without asking: it was never the user's answer, and
 * this is.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FolderStyleDialog(
    folderName: String,
    count: Int,
    onDismiss: () -> Unit,
    onApply: (List<String>, Boolean) -> Unit
) = BulkStyleDialog(
    title = "תגיות לתיקייה",
    subtitle = "\"$folderName\" · $count שירים, כולל תת־תיקיות",
    count = count,
    onDismiss = onDismiss,
    onApply = onApply
)

/**
 * One set of style tags onto many songs: a folder, or whatever is selected.
 * What a bulk tag may overwrite is BulkTagging's decision, not this dialog's.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BulkStyleDialog(
    title: String,
    subtitle: String,
    count: Int,
    onDismiss: () -> Unit,
    onApply: (List<String>, Boolean) -> Unit
) {
    var selected by remember { mutableStateOf(emptyList<String>()) }
    var replace by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    subtitle,
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
                            selected = if (on) {
                                selected.filterNot { it.equals(style, ignoreCase = true) }
                            } else {
                                selected + style
                            }
                        })
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Chip(
                        label = "הוסף לקיים",
                        selected = !replace,
                        onClick = { replace = false }
                    )
                    Chip(
                        label = "החלף מה שיש",
                        selected = replace,
                        onClick = { replace = true }
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (replace) {
                        "תגיות שסימנת בעצמך על שירים בתיקייה יימחקו ויוחלפו."
                    } else {
                        "תגיות שסימנת בעצמך יישארו. ניחושים של האפליקציה יוחלפו בכל מקרה."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (replace) Accent else TextSecondary
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected.isNotEmpty(),
                onClick = { onApply(selected, replace); onDismiss() }
            ) {
                Text("תייג $count שירים", color = if (selected.isEmpty()) TextTertiary else Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("ביטול", color = TextSecondary) }
        }
    )
}

/**
 * The main folder - where the music comes from - said above the folders, and
 * changed from here as well as from the settings. In both folder views, and
 * when nothing was found: a folder chosen by mistake would otherwise leave the
 * tab empty with no way back from it.
 */
@Composable
private fun MainFolderRow(vm: MainViewModel, gutter: Dp) {
    var musicFolders by remember { mutableStateOf(vm.prefs.musicFolders) }
    var musicFoldersOpen by remember { mutableStateOf(false) }
    if (musicFoldersOpen) {
        MusicFoldersDialog(
            initial = musicFolders,
            onDismiss = { musicFoldersOpen = false },
            onApply = {
                musicFolders = it
                vm.prefs.musicFolders = it
                vm.rescan()
            }
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { musicFoldersOpen = true }
            .padding(horizontal = gutter, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            localized("תיקייה ראשית:").orEmpty() + " " + musicFolders.joinToString(", ") { folderLabel(it) }
                .ifBlank { localized("כל המכשיר").orEmpty() },
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text("שינוי", style = MaterialTheme.typography.labelLarge, color = Accent)
    }
}

@Composable
private fun NoFolders(vm: MainViewModel, gutter: Dp) {
    Column(modifier = Modifier.fillMaxSize()) {
        MainFolderRow(vm, gutter)
        EmptyState(
            title = "לא נמצאו תיקיות",
            body = "התיקיות מופיעות אחרי שהאפליקציה סורקת את המכשיר."
        )
    }
}

/** The older flat list, for anyone who preferred it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderListTab(
    vm: MainViewModel,
    songs: List<SongEntity>,
    gutter: Dp,
    selection: Set<Long>,
    onToggleGroup: (List<SongEntity>) -> Unit,
    onOpenDetail: () -> Unit
) {
    val selectionMode = selection.isNotEmpty()
    val folders = remember(songs) {
        songs.groupBy { it.folder }
            .map { (path, list) -> path to list }
            .sortedBy { it.first.lowercase(Locale.ROOT) }
    }
    if (folders.isEmpty()) {
        NoFolders(vm, gutter)
        return
    }
    Column(modifier = Modifier.fillMaxSize()) {
        MainFolderRow(vm, gutter)
        LazyColumn(contentPadding = PaddingValues(bottom = 40.dp)) {
            items(folders, key = { it.first }) { (path, list) ->
                val picked = list.isNotEmpty() && selection.containsAll(list.map { it.id })
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = {
                                if (selectionMode) {
                                    onToggleGroup(list)
                                } else {
                                    vm.openList(folderName(path), path, list, "folder:$path")
                                    onOpenDetail()
                                }
                            },
                            onLongClick = { onToggleGroup(list) }
                        )
                        .background(if (picked) Accent.copy(alpha = 0.16f) else Color.Transparent)
                        .padding(horizontal = gutter, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (selectionMode) {
                        SelectionTick(picked)
                        Spacer(Modifier.width(10.dp))
                    }
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
}

/**
 * The tick that appears on the left of a row once something is selected.
 *
 * Only while selecting: a checkbox on every row all the time turns a library
 * into a form, and the gesture that starts selection is a long press, which
 * needs no affordance because nothing else in the app uses it.
 */
@Composable
internal fun SelectionTick(selected: Boolean) {
    // The same mark, tint and size a selected song row draws, because a
    // selected album and a selected song are the same state and two ways of
    // drawing it would read as two different things.
    Icon(
        imageVector = Icons.Filled.CheckCircle,
        contentDescription = null,
        tint = if (selected) Accent else Surface3,
        modifier = Modifier.size(22.dp)
    )
}
