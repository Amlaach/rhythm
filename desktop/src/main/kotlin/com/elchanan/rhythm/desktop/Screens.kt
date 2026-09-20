package com.elchanan.rhythm.desktop

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.automirrored.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.data.db.SongStatsEntity
import com.elchanan.rhythm.engine.Styles
import com.elchanan.rhythm.ui.theme.Accent
import com.elchanan.rhythm.ui.theme.Accent2
import com.elchanan.rhythm.ui.theme.AppBackground
import com.elchanan.rhythm.ui.theme.Surface1
import com.elchanan.rhythm.ui.theme.TextSecondary
import com.elchanan.rhythm.ui.theme.gradientFor

/**
 * A list the user drilled into: a playlist, an album, a folder, the likes.
 *
 * Carried as a value rather than looked up from a route, because the thing on
 * screen is a list of songs and every one of those four produces one. A route
 * would need a way to name each source and a way to rebuild it, and the only
 * difference between them that the screen cares about is whether a song can be
 * taken off it - which is what [playlistId] says.
 */
data class DetailList(
    val title: String,
    val subtitle: String?,
    val songs: List<SongEntity>,
    val gradientKey: String,
    val playlistId: Long? = null
)

/** How a list of songs can be ordered, in the order the phone offers them. */
private enum class SongSort(val label: String) {
    TITLE("שם"),
    ARTIST("אמן"),
    ADDED("נוסף לאחרונה"),
    PLAYS("הכי מושמע"),
    RATING("דירוג")
}

/** The library tabs, in the order the phone shows them. */
private enum class LibraryTab(val label: String) {
    PLAYLISTS("פלייליסטים"),
    FOLDERS("תיקיות"),
    LIKED("אהובים"),
    ARTISTS("אמנים"),
    SONGS("שירים"),
    ALBUMS("אלבומים"),
    SPOKEN("הרצאות")
}

/**
 * A plain list of songs, used wherever there is nothing above it.
 *
 * Search results and the songs tab are the same list of the same rows; only
 * what fills them differs.
 */
@Composable
internal fun SongList(
    songs: List<SongEntity>,
    stats: Map<Long, SongStatsEntity>,
    current: Long?,
    empty: String,
    onPlay: (Int) -> Unit,
    onLike: (SongEntity) -> Unit,
    onDislike: (SongEntity) -> Unit,
    onMore: (SongEntity) -> Unit,
    selection: Set<Long> = emptySet(),
    onToggleSelect: ((Long) -> Unit)? = null
) {
    if (songs.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(empty, style = MaterialTheme.typography.bodyLarge, color = TextSecondary)
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
            val selecting = selection.isNotEmpty()
            SongRow(
                song = song,
                isCurrent = song.id == current,
                liked = stats[song.id]?.liked ?: 0,
                rating = stats[song.id]?.rating ?: 0,
                playCount = stats[song.id]?.playCount ?: 0,
                selected = song.id in selection,
                // Once anything is ticked, a tap ticks rather than plays. A
                // list that plays a song while you are selecting twenty of
                // them is a list you have to start over.
                onClick = {
                    if (selecting && onToggleSelect != null) onToggleSelect(song.id)
                    else onPlay(index)
                },
                onMore = { onMore(song) },
                onLike = { onLike(song) },
                onDislike = { onDislike(song) },
                leading = if (onToggleSelect == null) {
                    null
                } else {
                    { onToggleSelect(song.id) }
                }
            )
        }
    }
}

/**
 * The library tab: six ways into the same songs.
 *
 * Which one someone uses says a lot about how their library is arranged.
 * Folders are here because a lot of people think about their music that way -
 * especially when the tags are a mess and the folder name is the only
 * reliable label there is.
 */
@Composable
internal fun LibraryPane(
    library: LibraryModel,
    stats: Map<Long, SongStatsEntity>,
    current: Long?,
    onPlay: (List<SongEntity>, Int) -> Unit,
    onLike: (SongEntity) -> Unit,
    onDislike: (SongEntity) -> Unit,
    onMore: (SongEntity) -> Unit,
    onOpenList: (DetailList) -> Unit,
    onOpenArtist: (ArtistInfo) -> Unit,
    onOpenAlbums: () -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onDeletePlaylist: (Long) -> Unit,
    spoken: List<SongEntity>,
    resumePoints: Map<Long, Long>,
    firstTab: String,
    onBulkRate: (List<Long>, Int) -> Unit,
    onBulkLike: (List<Long>) -> Unit,
    onBulkQueue: (List<SongEntity>) -> Unit,
    onBulkAddTo: (Long, List<Long>) -> Unit
) {
    // Opens on whichever tab the settings name, and only reads that setting
    // once - changing it later should not yank the screen out from under
    // someone who is standing on a different tab.
    var tab by remember {
        mutableStateOf(
            LibraryTab.entries.indexOfFirst { it.name == firstTab }.coerceAtLeast(0)
        )
    }
    var newList by remember { mutableStateOf(false) }
    var sort by remember { mutableStateOf(SongSort.TITLE) }
    // What is ticked. Empty means nobody is selecting anything, which is also
    // what makes the selection bar appear and disappear on its own.
    var selection by remember { mutableStateOf(emptySet<Long>()) }
    var addingSelection by remember { mutableStateOf(false) }

    fun sorted(list: List<SongEntity>): List<SongEntity> = when (sort) {
        SongSort.TITLE -> list.sortedBy { it.titleLower }
        SongSort.ARTIST -> list.sortedWith(compareBy({ it.artistKey }, { it.titleLower }))
        SongSort.ADDED -> list.sortedByDescending { it.dateAddedSec }
        SongSort.PLAYS -> list.sortedByDescending { stats[it.id]?.playCount ?: 0 }
        SongSort.RATING -> list.sortedByDescending { stats[it.id]?.rating ?: 0 }
    }

    if (library.songs.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("סרוק תיקייה כדי להתחיל", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "הספרייה שלי",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(horizontal = GUTTER, vertical = 10.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = GUTTER),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(LibraryTab.entries.size) { index ->
                Chip(
                    label = LibraryTab.entries[index].label,
                    selected = tab == index,
                    onClick = { tab = index }
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        // Only while something is ticked. A permanent action bar is a strip of
        // buttons that do nothing most of the time.
        if (selection.isNotEmpty()) {
            SelectionBar(
                count = selection.size,
                onClear = { selection = emptySet() },
                onRate = { onBulkRate(selection.toList(), it) },
                onLike = {
                    onBulkLike(selection.toList())
                    selection = emptySet()
                },
                onQueue = {
                    val byId = library.songs.associateBy { it.id }
                    onBulkQueue(selection.mapNotNull { byId[it] })
                    selection = emptySet()
                },
                onAddTo = { addingSelection = true }
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            when (LibraryTab.entries[tab]) {
                LibraryTab.SONGS -> {
                    val ordered = remember(library.songs, sort, stats) { sorted(library.songs) }
                    Column {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = GUTTER),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(SongSort.entries.toList()) { option ->
                                Chip(
                                    label = option.label,
                                    selected = sort == option,
                                    onClick = { sort = option }
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        SongList(
                            songs = ordered,
                            stats = stats,
                            current = current,
                            empty = "אין שירים",
                            onPlay = { index -> onPlay(ordered, index) },
                            onLike = onLike,
                            onDislike = onDislike,
                            onMore = onMore,
                            selection = selection,
                            onToggleSelect = { id ->
                                selection =
                                    if (id in selection) selection - id else selection + id
                            }
                        )
                    }
                }

                LibraryTab.LIKED -> {
                    val liked = library.liked(stats)
                    if (liked.isEmpty()) {
                        EmptyState(
                            title = "עוד אין אהובים",
                            body = "לייק על שיר מלמד את האלגוריתם מה לחפש."
                        )
                    } else {
                        SongList(
                            songs = liked,
                            stats = stats,
                            current = current,
                            empty = "",
                            onPlay = { index -> onPlay(liked, index) },
                            onLike = onLike,
                            onDislike = onDislike,
                            onMore = onMore,
                            selection = selection,
                            onToggleSelect = { id ->
                                selection = if (id in selection) selection - id else selection + id
                            }
                        )
                    }
                }

                LibraryTab.ARTISTS -> LazyColumn(
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(library.artists, key = { it.key }) { artist ->
                        ArtistRow(artist) { onOpenArtist(artist) }
                    }
                }

                LibraryTab.ALBUMS -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onOpenAlbums)
                                .padding(horizontal = GUTTER, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "תצוגת רשת",
                                style = MaterialTheme.typography.labelLarge,
                                color = Accent
                            )
                        }
                    }
                    items(library.albums, key = { it.albumId }) { album ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onOpenList(
                                        DetailList(
                                            title = album.name,
                                            subtitle = album.artistName,
                                            songs = album.songs,
                                            gradientKey = "album:${album.albumId}"
                                        )
                                    )
                                }
                                .padding(horizontal = GUTTER, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AlbumArt(album, size = 52.dp, corner = 8.dp)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    album.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "${album.artistName} · ${album.songs.size} שירים",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                LibraryTab.FOLDERS -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(library.folders, key = { it.path }) { folder ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onOpenList(
                                        DetailList(
                                            title = folder.name,
                                            subtitle = folder.path,
                                            songs = folder.songs,
                                            gradientKey = "folder:${folder.path}"
                                        )
                                    )
                                }
                                .padding(horizontal = GUTTER, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val (c1, c2) = gradientFor(folder.path)
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Brush.linearGradient(listOf(c1, c2))),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Filled.Folder,
                                    contentDescription = null,
                                    tint = Color.White
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    folder.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "${folder.songs.size} שירים",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                        }
                    }
                }

                // Talking rather than music: shiurim, stories, recorded
                // lectures. They resume where they were left and they are the
                // one thing in the library nobody wants shuffled.
                LibraryTab.SPOKEN -> if (spoken.isEmpty()) {
                    EmptyState(
                        title = "לא נמצאו הרצאות",
                        body = "הזיהוי מחפש הקלטות ארוכות שנשמעות כמו דיבור ולא כמו " +
                            "מוזיקה. צריך שהקבצים ינותחו קודם."
                    )
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                        itemsIndexed(spoken, key = { _, song -> song.id }) { index, song ->
                            val at = resumePoints[song.id]
                            SongRow(
                                song = song,
                                isCurrent = song.id == current,
                                liked = stats[song.id]?.liked ?: 0,
                                rating = stats[song.id]?.rating ?: 0,
                                onClick = { onPlay(spoken, index) },
                                onMore = { onMore(song) },
                                trailing = if (at == null) {
                                    null
                                } else {
                                    {
                                        // Where it was left, said outright. A
                                        // recording that silently starts forty
                                        // minutes in looks broken.
                                        Text(
                                            "נעצר ב־${formatDuration(at)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Accent
                                        )
                                    }
                                }
                            )
                        }
                    }
                }

                LibraryTab.PLAYLISTS -> LazyColumn(
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { newList = true }
                                .padding(horizontal = GUTTER, vertical = 12.dp),
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
                    // Liked songs as an auto playlist. Derived from the likes
                    // rather than stored as its own list: a real playlist would
                    // drift the moment a like is taken back, and there would
                    // then be two disagreeing answers to "what did I like".
                    item {
                        val liked = library.liked(stats)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onOpenList(
                                        DetailList(
                                            title = "השירים שאהבתי",
                                            subtitle = "${liked.size} שירים",
                                            songs = liked,
                                            gradientKey = "auto:liked"
                                        )
                                    )
                                }
                                .padding(horizontal = GUTTER, vertical = 7.dp),
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
                                    "מתעדכן לבד · ${liked.size} שירים",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                        }
                    }
                    items(library.playlists, key = { it.playlist.id }) { info ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onOpenList(
                                        DetailList(
                                            title = info.playlist.name,
                                            subtitle = "${info.songs.size} שירים",
                                            songs = info.songs,
                                            gradientKey = "pl:${info.playlist.id}",
                                            playlistId = info.playlist.id
                                        )
                                    )
                                }
                                .padding(horizontal = GUTTER, vertical = 7.dp),
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
                                Icon(
                                    Icons.AutoMirrored.Filled.PlaylistPlay,
                                    contentDescription = null,
                                    tint = Color.White
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    info.playlist.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "${info.songs.size} שירים",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                            IconButton(onClick = { onDeletePlaylist(info.playlist.id) }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "מחק",
                                    tint = TextSecondary
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (addingSelection) {
        AlertDialog(
            onDismissRequest = { addingSelection = false },
            containerColor = Surface1,
            title = { Text("הוספת ${selection.size} שירים לרשימה") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(library.playlists, key = { it.playlist.id }) { info ->
                        OptionRow(
                            icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                            label = info.playlist.name,
                            hint = "${info.songs.size} שירים"
                        ) {
                            onBulkAddTo(info.playlist.id, selection.toList())
                            selection = emptySet()
                            addingSelection = false
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { addingSelection = false }) {
                    Text("סגור", color = TextSecondary)
                }
            }
        )
    }

    if (newList) {
        NamePlaylistDialog(
            onDismiss = { newList = false },
            onConfirm = {
                onCreatePlaylist(it)
                newList = false
            }
        )
    }
}

/**
 * Asks for a name, and refuses to make a list without one.
 *
 * An unnamed list is one nobody can tell apart from the next unnamed list, so
 * the button is simply off until there is something to call it.
 */
@Composable
private fun NamePlaylistDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        title = { Text("רשימה חדשה") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("שם הרשימה") },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank()
            ) { Text("צור", color = if (name.isBlank()) TextSecondary else Accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("ביטול", color = TextSecondary) }
        }
    )
}

/**
 * What can be done to everything ticked at once.
 *
 * Rating in bulk is the one that matters. The engine learns from ratings, and
 * rating a library one song at a time is the reason most people never rate
 * anything - twenty at a time is a minute's work that changes every shelf.
 */
@Composable
private fun SelectionBar(
    count: Int,
    onClear: () -> Unit,
    onRate: (Int) -> Unit,
    onLike: () -> Unit,
    onQueue: () -> Unit,
    onAddTo: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = GUTTER)
            .clip(RoundedCornerShape(12.dp))
            .background(Surface1)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$count נבחרו",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            StarRow(rating = 0, onRate = onRate, size = 20)
            Spacer(Modifier.width(10.dp))
            IconButton(onClick = onLike, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.ThumbUp,
                    contentDescription = "לייק לכולם",
                    tint = Accent,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = onQueue, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.PlaylistAddCheck,
                    contentDescription = "הוסף לתור",
                    tint = Accent,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = onAddTo, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.PlaylistAdd,
                    contentDescription = "הוסף לרשימה",
                    tint = Accent,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = onClear, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "בטל בחירה",
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun ArtistRow(artist: ArtistInfo, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = GUTTER, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val (c1, c2) = gradientFor(artist.key)
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(c1, c2))),
            contentAlignment = Alignment.Center
        ) {
            Text(
                artist.displayName.take(2),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                artist.displayName.ifEmpty { "ללא שם" },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${artist.songs.size} שירים",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
        if (artist.rating > 0) StarRow(rating = artist.rating, size = 14)
    }
}

/**
 * The artists tab: everything that can be rated, unrated first.
 *
 * Unrated first because the point of this screen is to work through the ones
 * that have not been done yet, and an alphabetical list gives no sense of how
 * far that has got.
 */
@Composable
internal fun ArtistsPane(artists: List<ArtistInfo>, onOpen: (ArtistInfo) -> Unit) {
    if (artists.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("סרוק תיקייה כדי להתחיל", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }
    val ordered = artists.sortedWith(
        compareBy<ArtistInfo> { it.rating > 0 }.thenByDescending { it.songs.size }
    )
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "דירוג אמנים וסגנונות",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(horizontal = GUTTER, vertical = 10.dp)
        )
        Text(
            text = "${artists.count { it.rating > 0 }} מתוך ${artists.size} דורגו",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.padding(horizontal = GUTTER)
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(ordered, key = { it.key }) { artist ->
                ArtistRow(artist) { onOpen(artist) }
            }
        }
    }
}

/**
 * A list, with its cover and its two buttons above it.
 *
 * The same screen serves a playlist, an album, a folder and the likes,
 * because all four are a title and a list of songs. The only thing that
 * changes is whether a song can be taken off.
 */
@Composable
internal fun DetailListScreen(
    data: DetailList,
    stats: Map<Long, SongStatsEntity>,
    current: Long?,
    onBack: () -> Unit,
    onPlay: (List<SongEntity>, Int) -> Unit,
    onShuffle: (List<SongEntity>) -> Unit,
    onLike: (SongEntity) -> Unit,
    onDislike: (SongEntity) -> Unit,
    onMore: (SongEntity) -> Unit,
    onRemove: ((SongEntity) -> Unit)? = null
) {
    Column(modifier = Modifier.fillMaxSize().background(AppBackground)) {
        DetailTopBar(title = data.title, onBack = onBack)
        if (data.songs.isEmpty()) {
            EmptyState(title = "הרשימה ריקה", body = "אפשר להוסיף שירים מכל מקום באפליקציה.")
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                val (c1, c2) = gradientFor(data.gradientKey)
                Column(modifier = Modifier.padding(GUTTER)) {
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
                            onClick = { onPlay(data.songs, 0) },
                            colors = ButtonDefaults.buttonColors(containerColor = Accent)
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("נגן")
                        }
                        OutlinedButton(onClick = { onShuffle(data.songs) }) {
                            Icon(Icons.Filled.Shuffle, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("ערבב")
                        }
                    }
                }
            }
            itemsIndexed(data.songs, key = { _, song -> song.id }) { index, song ->
                SongRow(
                    song = song,
                    isCurrent = song.id == current,
                    liked = stats[song.id]?.liked ?: 0,
                    rating = stats[song.id]?.rating ?: 0,
                    onClick = { onPlay(data.songs, index) },
                    onMore = { onMore(song) },
                    onLike = { onLike(song) },
                    onDislike = { onDislike(song) },
                    trailing = if (onRemove == null) {
                        null
                    } else {
                        {
                            IconButton(
                                onClick = { onRemove.invoke(song) },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "הסר מהרשימה",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                )
            }
        }
    }
}

/**
 * What else can be done with one song.
 *
 * The phone puts these in a sheet that slides up from the bottom; a window
 * has no bottom edge to slide from, so it is a dialog. The actions are the
 * same ones and in the same order, because they are the same decisions.
 *
 * Adding to a list is first. It is the only one of these that cannot be
 * reached any other way.
 */
@Composable
internal fun SongOptionsDialog(
    song: SongEntity,
    stat: SongStatsEntity?,
    playlists: List<PlaylistInfo>,
    onDismiss: () -> Unit,
    onRate: (Int) -> Unit,
    onRadio: () -> Unit,
    onOpenArtist: () -> Unit,
    onOpenAlbum: () -> Unit,
    onAddTo: (Long) -> Unit,
    onCreateWith: (String) -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onLyrics: () -> Unit,
    onBookmarks: () -> Unit
) {
    var picking by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }

    if (naming) {
        NamePlaylistDialog(
            onDismiss = { naming = false },
            onConfirm = {
                onCreateWith(it)
                naming = false
                onDismiss()
            }
        )
        return
    }

    if (picking) {
        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = Surface1,
            title = { Text("הוספה לרשימה") },
            text = {
                // Scrolls rather than grows: someone with forty lists should
                // still be able to see the buttons at the bottom of the box.
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    item {
                        OptionRow(Icons.Filled.Add, "רשימה חדשה") { naming = true }
                    }
                    items(playlists, key = { it.playlist.id }) { info ->
                        val already = info.songs.any { it.id == song.id }
                        OptionRow(
                            icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                            label = info.playlist.name,
                            hint = if (already) "כבר ברשימה" else "${info.songs.size} שירים",
                            enabled = !already
                        ) {
                            onAddTo(info.playlist.id)
                            onDismiss()
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("סגור", color = TextSecondary) }
            }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        title = {
            Column {
                Text(song.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    song.artistName.ifEmpty { "ללא אמן" },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        },
        text = {
            Column {
                Text("דירוג", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                Spacer(Modifier.height(6.dp))
                StarRow(rating = stat?.rating ?: 0, onRate = onRate, size = 26)
                Spacer(Modifier.height(14.dp))
                OptionRow(Icons.AutoMirrored.Filled.PlaylistAdd, "הוספה לרשימה") { picking = true }
                OptionRow(Icons.Filled.SkipNext, "נגן אחרי הנוכחי") {
                    onPlayNext()
                    onDismiss()
                }
                OptionRow(Icons.AutoMirrored.Filled.PlaylistAddCheck, "הוסף לסוף התור") {
                    onAddToQueue()
                    onDismiss()
                }
                OptionRow(Icons.Filled.Radio, "רדיו מהשיר הזה") {
                    onRadio()
                    onDismiss()
                }
                // The two the phone keeps in this menu rather than on the
                // player's header, so the header stays at three icons.
                OptionRow(Icons.Filled.FormatQuote, "מילות השיר") {
                    onLyrics()
                    onDismiss()
                }
                OptionRow(Icons.Filled.Bookmark, "סימניות") {
                    onBookmarks()
                    onDismiss()
                }
                OptionRow(Icons.Filled.Person, "עבור לאמן") {
                    onOpenArtist()
                    onDismiss()
                }
                OptionRow(Icons.Filled.Album, "עבור לאלבום") {
                    onOpenAlbum()
                    onDismiss()
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("סגור", color = TextSecondary) }
        }
    )
}

@Composable
private fun OptionRow(
    icon: ImageVector,
    label: String,
    hint: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (enabled) Accent else TextSecondary
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onBackground else TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (hint != null) {
                Text(hint, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        }
    }
}

/**
 * An artist's page: the rating, the style words, then everything they sing on.
 *
 * The style words are here rather than buried in a settings screen because
 * they are what the learner trains on - every song by a tagged artist becomes
 * a labelled example, which is how a few minutes here turns into a few
 * hundred of them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ArtistDetailScreen(
    artist: ArtistInfo,
    stats: Map<Long, SongStatsEntity>,
    current: Long?,
    onBack: () -> Unit,
    onPlay: (List<SongEntity>, Int) -> Unit,
    onRadio: (SongEntity) -> Unit,
    onRate: (Int) -> Unit,
    onTag: (String) -> Unit,
    onLike: (SongEntity) -> Unit,
    onDislike: (SongEntity) -> Unit,
    onMore: (SongEntity) -> Unit
) {
    val selected = Styles.parse(artist.styles)
    Column(modifier = Modifier.fillMaxSize().background(AppBackground)) {
        DetailTopBar(title = artist.displayName, onBack = onBack)
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                val (c1, c2) = gradientFor(artist.key)
                Column(
                    modifier = Modifier.fillMaxWidth().padding(GUTTER),
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
                            artist.displayName.take(2),
                            style = MaterialTheme.typography.displaySmall,
                            color = Color.White
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(artist.displayName, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "${artist.songs.size} שירים",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(Modifier.height(12.dp))
                    StarRow(rating = artist.rating, onRate = onRate, size = 30)
                    Text(
                        text = ratingHint(artist.rating),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { onPlay(artist.songs, 0) },
                            colors = ButtonDefaults.buttonColors(containerColor = Accent)
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("נגן")
                        }
                        OutlinedButton(
                            onClick = { artist.songs.firstOrNull()?.let(onRadio) }
                        ) {
                            Icon(Icons.Filled.Radio, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("רדיו")
                        }
                    }
                }
            }

            item {
                Column(modifier = Modifier.padding(horizontal = GUTTER)) {
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
                            Chip(
                                label = style,
                                selected = selected.any { it.equals(style, ignoreCase = true) },
                                onClick = { onTag(style) }
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("השירים", style = MaterialTheme.typography.titleMedium)
                }
            }

            itemsIndexed(artist.songs, key = { _, song -> song.id }) { index, song ->
                SongRow(
                    song = song,
                    isCurrent = song.id == current,
                    liked = stats[song.id]?.liked ?: 0,
                    rating = stats[song.id]?.rating ?: 0,
                    onClick = { onPlay(artist.songs, index) },
                    onMore = { onMore(song) },
                    onLike = { onLike(song) },
                    onDislike = { onDislike(song) }
                )
            }
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

/** Every album at once, as covers. The one screen that is about the artwork. */
@Composable
internal fun AlbumsScreen(
    albums: List<AlbumInfo>,
    onBack: () -> Unit,
    onOpen: (AlbumInfo) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().background(AppBackground)) {
        DetailTopBar(title = "אלבומים", onBack = onBack)
        LazyVerticalGrid(
            // As many covers as the window is wide enough for, without ever
            // squeezing one below a legible size.
            columns = GridCells.Adaptive(minSize = CARD),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(albums, key = { it.albumId }) { album ->
                Column(modifier = Modifier.clickable { onOpen(album) }) {
                    AlbumArt(album, size = CARD, corner = 12.dp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        album.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        album.artistName,
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
