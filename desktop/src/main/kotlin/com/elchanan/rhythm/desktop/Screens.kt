package com.elchanan.rhythm.desktop

import com.elchanan.rhythm.ui.theme.Surface3
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import com.elchanan.rhythm.ui.theme.CaptionedIconButton
import com.elchanan.rhythm.ui.theme.localized

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistAddCheck
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistRemove
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import com.elchanan.rhythm.ui.theme.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.elchanan.rhythm.data.db.AudioFeatureEntity
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.data.db.SongStatsEntity
import com.elchanan.rhythm.engine.AlphabetIndexing
import com.elchanan.rhythm.engine.AudioTags
import com.elchanan.rhythm.engine.Capo
import com.elchanan.rhythm.data.ArtistMerge
import com.elchanan.rhythm.data.ArtistShelf
import com.elchanan.rhythm.data.ArtistShelves
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.elchanan.rhythm.engine.Folders
import com.elchanan.rhythm.engine.Mood
import com.elchanan.rhythm.engine.MoodMarks
import com.elchanan.rhythm.engine.MusicalMode
import com.elchanan.rhythm.engine.ScoreTerm
import com.elchanan.rhythm.engine.Styles
import com.elchanan.rhythm.ui.theme.Accent
import com.elchanan.rhythm.ui.theme.Accent2
import com.elchanan.rhythm.ui.theme.AppBackground
import com.elchanan.rhythm.ui.theme.Color_Error
import com.elchanan.rhythm.ui.theme.Surface1
import com.elchanan.rhythm.ui.theme.Surface2
import com.elchanan.rhythm.ui.theme.TextSecondary
import com.elchanan.rhythm.ui.theme.TextTertiary
import com.elchanan.rhythm.ui.theme.gradientFor
import java.util.Locale
import kotlinx.coroutines.launch

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
    onToggleSelect: ((Long) -> Unit)? = null,
    // Hoisted so the alphabet index can scroll the list it sits beside.
    // Callers that have nothing to say about scrolling leave it alone.
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(bottom = 24.dp)
) {
    if (songs.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(empty, style = MaterialTheme.typography.bodyLarge, color = TextSecondary)
        }
        return
    }
    LazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding
    ) {
        itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
            val selecting = selection.isNotEmpty()
            val own = stats[song.id]
            SongRow(
                song = song,
                isCurrent = song.id == current,
                liked = own?.liked ?: 0,
                rating = own?.rating ?: 0,
                playCount = own?.playCount ?: 0,
                selected = song.id in selection,
                // Wherever a guessed song turns up, it says so. The point of
                // showing the guesses is that they can be checked, and a list
                // of titles with nothing beside them cannot be.
                note = if (own?.stylesAuto == 1 && own.styles.isNotBlank()) {
                    "תויג אוטומטית: ${own.styles}"
                } else {
                    null
                },
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
    folderTree: Boolean,
    onShuffle: (List<SongEntity>) -> Unit,
    onBulkRate: (List<Long>, Int) -> Unit,
    onBulkLike: (List<Long>) -> Unit,
    onBulkQueue: (List<SongEntity>) -> Unit,
    onBulkAddTo: (Long, List<Long>) -> Unit,
    onBulkGenre: (List<Long>, String) -> Unit,
    onTagFolder: (List<Long>, List<String>, Boolean) -> Unit,
    onBulkDelete: (List<SongEntity>) -> Unit
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
    var filter by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    // What is ticked. Empty means nobody is selecting anything, which is also
    // what makes the selection bar appear and disappear on its own.
    var selection by remember { mutableStateOf(emptySet<Long>()) }
    var addingSelection by remember { mutableStateOf(false) }

    // Title or artist, case folded, substring. Not the search engine: this is
    // a filter on a list that is already on screen, and anything cleverer
    // would make the rows move around for reasons that are not visible.
    fun matching(list: List<SongEntity>, text: String): List<SongEntity> {
        if (text.isBlank()) return list
        val q = text.lowercase(Locale.ROOT)
        return list.filter {
            it.titleLower.contains(q) || it.artistName.lowercase(Locale.ROOT).contains(q)
        }
    }

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
            val chosen = remember(selection, library.songs) {
                val byId = library.songs.associateBy { it.id }
                selection.mapNotNull { byId[it] }
            }
            SelectionBar(
                count = selection.size,
                onClear = { selection = emptySet() },
                onPlay = {
                    if (chosen.isNotEmpty()) onPlay(chosen, 0)
                    selection = emptySet()
                },
                onRate = { onBulkRate(selection.toList(), it) },
                onLike = {
                    onBulkLike(selection.toList())
                    selection = emptySet()
                },
                onQueue = {
                    onBulkQueue(chosen)
                    selection = emptySet()
                },
                onAddTo = { addingSelection = true },
                onGenre = {
                    onBulkGenre(selection.toList(), it)
                    selection = emptySet()
                },
                onDelete = {
                    onBulkDelete(chosen)
                    selection = emptySet()
                }
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            when (LibraryTab.entries[tab]) {
                LibraryTab.SONGS -> {
                    val ordered = remember(library.songs, sort, stats, filter) {
                        sorted(matching(library.songs, filter))
                    }
                    val listState = rememberLazyListState()
                    val letters = remember(ordered) {
                        AlphabetIndexing.present(ordered.map { it.title })
                    }
                    Column {
                        // Typing beats scrolling and beats the sort chips too,
                        // once a library is past a few hundred songs. It
                        // filters rather than searches: this is the list you
                        // are already looking at, narrowed.
                        OutlinedTextField(
                            value = filter,
                            onValueChange = { filter = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = GUTTER, vertical = 4.dp),
                            placeholder = { Text("סינון מהיר", color = TextSecondary) },
                            singleLine = true,
                            trailingIcon = if (filter.isEmpty()) {
                                null
                            } else {
                                {
                                    IconButton(onClick = { filter = "" }) {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = localized("נקה"),
                                            tint = TextSecondary
                                        )
                                    }
                                }
                            }
                        )
                        Row(
                            modifier = Modifier.padding(horizontal = GUTTER, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = {
                                    if (ordered.isNotEmpty()) onShuffle(ordered)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Accent)
                            ) {
                                Icon(Icons.Filled.Shuffle, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("ערבב הכל")
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "${ordered.size} שירים",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
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
                        Box(modifier = Modifier.fillMaxSize()) {
                            SongList(
                                songs = ordered,
                                stats = stats,
                                current = current,
                                empty = if (filter.isBlank()) {
                                    "אין שירים"
                                } else {
                                    "שום שיר לא תואם"
                                },
                                onPlay = { index -> onPlay(ordered, index) },
                                onLike = onLike,
                                onDislike = onDislike,
                                onMore = onMore,
                                selection = selection,
                                onToggleSelect = { id ->
                                    selection =
                                        if (id in selection) selection - id else selection + id
                                },
                                state = listState,
                                // Room down the side for the index, so the
                                // last rows are not hidden behind it.
                                contentPadding = PaddingValues(bottom = 24.dp, end = 22.dp)
                            )
                            // Only useful while the list is in title order -
                            // under any other sort the letters would not be
                            // in order down the list, and jumping to one
                            // would land somewhere arbitrary.
                            if (sort == SongSort.TITLE) {
                                AlphabetIndex(
                                    letters = letters,
                                    modifier = Modifier.align(Alignment.CenterEnd)
                                ) { letter ->
                                    val index = ordered.indexOfFirst {
                                        AlphabetIndexing.initialOf(it.title) == letter
                                    }
                                    if (index >= 0) {
                                        scope.launch { listState.scrollToItem(index) }
                                    }
                                }
                            }
                        }
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

                LibraryTab.FOLDERS -> if (!folderTree) {
                    FolderList(
                        folders = library.folders,
                        onOpenList = onOpenList
                    )
                } else FolderTree(
                    songs = library.songs,
                    stats = stats,
                    current = current,
                    onTagFolder = onTagFolder,
                    onRateFolder = onBulkRate,
                    onPlay = onPlay,
                    onShuffle = onShuffle,
                    onLike = onLike,
                    onDislike = onDislike,
                    onMore = onMore
                )

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
                                    contentDescription = localized("מחק"),
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
            DialogBody {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("שם הרשימה") },
                    singleLine = true
                )
            }
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
    onPlay: () -> Unit,
    onRate: (Int) -> Unit,
    onLike: () -> Unit,
    onQueue: () -> Unit,
    onAddTo: () -> Unit,
    onGenre: (String) -> Unit,
    onDelete: () -> Unit
) {
    var rateOpen by remember { mutableStateOf(false) }
    var genreOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = GUTTER)
            .clip(RoundedCornerShape(12.dp))
            .background(Surface1)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onClear) {
            Icon(Icons.Filled.Close, contentDescription = localized("בטל"), tint = TextSecondary)
        }
        Text(
            "$count נבחרו",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(end = 6.dp)
        )
        Spacer(Modifier.weight(1f))
        // Labelled, because eight icons in a row is a puzzle. The phone
        // labels them for the same reason and a mouse does not make an
        // unlabelled icon any more legible than a finger does.
        BarAction(Icons.Filled.PlayArrow, "נגן", onPlay)
        BarAction(Icons.AutoMirrored.Filled.QueueMusic, "לתור", onQueue)
        BarAction(Icons.Filled.ThumbUp, "לייק", onLike)
        BarAction(Icons.Filled.Star, "דרג") { rateOpen = true }
        BarAction(Icons.AutoMirrored.Filled.PlaylistAdd, "לרשימה", onAddTo)
        BarAction(Icons.Filled.LocalOffer, "ז'אנר") { genreOpen = true }
        BarAction(Icons.Filled.Delete, "מחק") { deleteOpen = true }
    }

    if (rateOpen) {
        var rating by remember { mutableStateOf(5) }
        AlertDialog(
            onDismissRequest = { rateOpen = false },
            containerColor = Surface1,
            title = { Text("דירוג $count שירים") },
            text = {
                DialogBody {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        StarRow(
                            rating = rating,
                            onRate = { rating = if (it == 0) 1 else it },
                            size = 32
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onRate(rating)
                    rateOpen = false
                    onClear()
                }) { Text("שמור", color = Accent) }
            },
            dismissButton = {
                TextButton(onClick = { rateOpen = false }) {
                    Text("ביטול", color = TextSecondary)
                }
            }
        )
    }

    if (genreOpen) {
        GenreDialog(
            initial = "",
            onDismiss = { genreOpen = false },
            onApply = { onGenre(it); genreOpen = false }
        )
    }

    if (deleteOpen) {
        ConfirmDialog(
            title = "למחוק $count קבצים?",
            body = "הקבצים יימחקו מהדיסק עצמו, לא רק מהאפליקציה. " +
                "אי אפשר לבטל את זה.",
            confirm = "מחק",
            danger = true,
            onDismiss = { deleteOpen = false },
            onConfirm = { onDelete(); deleteOpen = false }
        )
    }
}

/**
 * One button on the selection bar: an icon with its name under it.
 */
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

/**
 * Artists whose names are one letter apart - a typo, or two spellings of one
 * name - offered for merging, never merged on their own: the listener picks
 * which spelling stays. The phone's ArtistMergeSuggestions, with the same
 * rule from :engine.
 */
@Composable
private fun ArtistMergeSuggestions(
    artists: List<ArtistInfo>,
    busy: Boolean,
    onMerge: (source: ArtistInfo, target: ArtistInfo) -> Unit
) {
    val pairs by produceState<List<Pair<ArtistInfo, ArtistInfo>>>(emptyList(), artists) {
        value = withContext(Dispatchers.Default) {
            // As the phone: one letter apart, other word order or a title,
            // full and short spelling, or Hebrew and English.
            ArtistMerge.suggestions(artists) { it.displayName }
        }
    }
    var show by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Pair<ArtistInfo, ArtistInfo>?>(null) }
    var keepFirst by remember { mutableStateOf(true) }
    // A card with a button, as on the phone: a line of text read as the app
    // remarking on something rather than offering to do it.
    if (pairs.isNotEmpty()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = GUTTER, vertical = 6.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Surface1)
                .clickable(enabled = !busy) { show = true }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.People, contentDescription = null, tint = Accent)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(if (pairs.size == 1) "אמן אחד שנראה כפול" else "${pairs.size} אמנים שנראים כפולים")
                Text("אותו אמן בשני איותים. אפשר לאחד אותם לאמן אחד", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = { show = true }, enabled = !busy) { Text("תקן") }
        }
    }
    if (show && selected == null) {
        AlertDialog(
            onDismissRequest = { show = false },
            containerColor = Surface1,
            title = { Text("ייתכן שזה אותו אמן") },
            text = {
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(pairs) { pair ->
                        // Each pair with a button that says what it does. A
                        // bare pair of names read as information, not as
                        // something to act on. The name that stays starts on
                        // the one with more songs, which is usually the one
                        // the tags agree on; the next step can switch it.
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(pair.first.displayName, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    pair.second.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextSecondary
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(if (busy) Surface2 else Accent)
                                    .clickable(enabled = !busy) {
                                        selected = pair
                                        keepFirst = pair.first.songs.size >= pair.second.songs.size
                                    }
                                    .padding(horizontal = 16.dp, vertical = 7.dp)
                            ) {
                                Text("תקן", style = MaterialTheme.typography.labelLarge, color = Color.White)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { show = false }) { Text("סגור", color = TextSecondary) } }
        )
    }
    selected?.let { pair ->
        val target = if (keepFirst) pair.first else pair.second
        val source = if (keepFirst) pair.second else pair.first
        AlertDialog(
            onDismissRequest = { selected = null },
            containerColor = Surface1,
            title = { Text("לתקן לאמן אחד?") },
            text = {
                DialogBody {
                    Column(Modifier.fillMaxWidth()) {
                        Text("בחר את השם שיישאר. שירי שני האמנים יוצגו יחד באפליקציה. קובצי המוזיקה לא ישתנו.")
                        TextButton(onClick = { keepFirst = true }) {
                            Text("${if (keepFirst) "✓ " else ""}${pair.first.displayName} (${pair.first.songs.size} שירים)")
                        }
                        TextButton(onClick = { keepFirst = false }) {
                            Text("${if (!keepFirst) "✓ " else ""}${pair.second.displayName} (${pair.second.songs.size} שירים)")
                        }
                        Text("הדירוג והסגנונות של ${target.displayName} יישארו כפי שהם.", color = TextSecondary)
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    onMerge(source, target)
                    selected = null
                    show = false
                }) { Text("תקן", color = Accent) }
            },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("ביטול", color = TextSecondary) } }
        )
    }
}

@Composable
private fun ArtistRow(
    artist: ArtistInfo,
    selected: Boolean = false,
    selectionMode: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onLongClick == null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    // Long press to start selecting, exactly as on the phone.
                    // Once something is ticked an ordinary click ticks too,
                    // because reaching for a checkbox forty times is the
                    // thing selection mode exists to avoid.
                    Modifier.pointerInput(artist.key) {
                        detectTapGestures(
                            onTap = { onClick() },
                            onLongPress = { onLongClick() }
                        )
                    }
                }
            )
            .background(if (selected) Surface2 else Color.Transparent)
            .padding(horizontal = GUTTER, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectionMode) {
            Icon(
                imageVector = if (selected) {
                    Icons.Filled.CheckCircle
                } else {
                    Icons.Outlined.Circle
                },
                contentDescription = null,
                tint = if (selected) Accent else TextSecondary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
        }
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
internal fun ArtistsPane(
    artists: List<ArtistInfo>,
    onOpen: (ArtistInfo) -> Unit,
    onBulkUpdate: (List<String>, Int?, List<String>?, Boolean) -> Unit,
    onBulkImport: (String) -> Unit,
    merging: Boolean = false,
    onMerge: (source: ArtistInfo, target: ArtistInfo) -> Unit = { _, _ -> }
) {
    if (artists.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("סרוק תיקייה כדי להתחיל", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }
    var filter by remember { mutableStateOf("") }
    var selection by remember { mutableStateOf(emptySet<String>()) }
    var groupOpen by remember { mutableStateOf(false) }
    var importOpen by remember { mutableStateOf(false) }

    val ordered = remember(artists, filter) {
        val q = filter.trim().lowercase(Locale.ROOT)
        artists
            .filter { q.isEmpty() || it.displayName.lowercase(Locale.ROOT).contains(q) }
            .sortedWith(compareBy<ArtistInfo> { it.rating > 0 }.thenByDescending { it.songs.size })
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.padding(horizontal = GUTTER, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("דירוג אמנים וסגנונות", style = MaterialTheme.typography.headlineMedium)
                Text(
                    text = "${artists.count { it.rating > 0 }} מתוך ${artists.size} דורגו · " +
                        "לחיצה ארוכה על אמן בוחרת כמה יחד",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
            // The way in for a list written somewhere else. Rating a few
            // hundred artists one at a time is the job this avoids.
            CaptionedIconButton(
                Icons.AutoMirrored.Filled.PlaylistAddCheck,
                "הזנה מרוכזת",
                { importOpen = true },
                tint = Accent
            )
        }
        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = GUTTER, vertical = 2.dp),
            placeholder = { Text("סינון לפי שם", color = TextSecondary) },
            singleLine = true
        )
        ArtistMergeSuggestions(artists, merging, onMerge)
        Spacer(Modifier.height(6.dp))

        if (selection.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = GUTTER)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Surface1)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { selection = emptySet() }) {
                    Icon(Icons.Filled.Close, contentDescription = localized("בטל"), tint = TextSecondary)
                }
                Text(
                    "${selection.size} אמנים נבחרו",
                    style = MaterialTheme.typography.labelLarge
                )
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { groupOpen = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                ) { Text("דרג ותייג") }
            }
            Spacer(Modifier.height(6.dp))
        }

        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            // Select-all is over the filtered list, not the library: filter
            // to a word and take all of those is the fast way to tag a
            // family of artists, and taking everything is the same gesture
            // with the filter empty.
            if (ordered.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.padding(horizontal = GUTTER, vertical = 4.dp)
                    ) {
                        val all = selection.size == ordered.size
                        Chip(
                            label = if (all) "בטל הכל" else "בחר הכל (${ordered.size})",
                            selected = all,
                            onClick = {
                                selection = if (all) {
                                    emptySet()
                                } else {
                                    ordered.mapTo(HashSet()) { it.key }
                                }
                            }
                        )
                    }
                }
            }
            items(ordered, key = { it.key }) { artist ->
                ArtistRow(
                    artist = artist,
                    selected = artist.key in selection,
                    selectionMode = selection.isNotEmpty(),
                    onLongClick = {
                        selection = if (artist.key in selection) {
                            selection - artist.key
                        } else {
                            selection + artist.key
                        }
                    },
                    onClick = {
                        if (selection.isNotEmpty()) {
                            selection = if (artist.key in selection) {
                                selection - artist.key
                            } else {
                                selection + artist.key
                            }
                        } else {
                            onOpen(artist)
                        }
                    }
                )
            }
            if (ordered.isEmpty()) {
                item {
                    EmptyState(title = "אין תוצאות", body = "שום אמן לא תואם את הסינון.")
                }
            }
        }
    }

    if (groupOpen) {
        GroupEditDialog(
            count = selection.size,
            onDismiss = { groupOpen = false },
            onApply = { rating, styles, replace ->
                onBulkUpdate(selection.toList(), rating, styles, replace)
                groupOpen = false
                selection = emptySet()
            }
        )
    }

    if (importOpen) {
        BulkImportDialog(
            onDismiss = { importOpen = false },
            onSubmit = {
                onBulkImport(it)
                importOpen = false
            }
        )
    }
}

/**
 * Rating and tagging a group of artists in one go.
 *
 * Both halves are optional and both default to leaving things alone: no stars
 * means the ratings are not touched, no style chips means the tags are not
 * touched. Someone who opens this to tag forty artists should not silently
 * re-rate them all at zero on the way out.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupEditDialog(
    count: Int,
    onDismiss: () -> Unit,
    onApply: (Int?, List<String>?, Boolean) -> Unit
) {
    var rating by remember { mutableStateOf(0) }
    var styles by remember { mutableStateOf(listOf<String>()) }
    var replace by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        title = { Text("$count אמנים") },
        text = {
            Column(modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                Text("דירוג", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                StarRow(rating = rating, onRate = { rating = it }, size = 28)
                Text(
                    text = if (rating == 0) {
                        "בלי כוכבים — הדירוג הקיים לא ישתנה"
                    } else {
                        "כל האמנים שנבחרו יקבלו $rating כוכבים"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(Modifier.height(14.dp))
                Text("סגנונות", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (style in Styles.SUGGESTED) {
                        val on = styles.any { it.equals(style, ignoreCase = true) }
                        Chip(label = style, selected = on, onClick = {
                            styles = if (on) {
                                styles.filterNot { it.equals(style, ignoreCase = true) }
                            } else {
                                styles + style
                            }
                        })
                    }
                }
                Spacer(Modifier.height(10.dp))
                Chip(
                    label = if (replace) "מחליף את התגיות הקיימות" else "מוסיף לתגיות הקיימות",
                    selected = replace,
                    onClick = { replace = !replace }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onApply(
                    if (rating > 0) rating else null,
                    if (styles.isEmpty()) null else styles,
                    replace
                )
            }) { Text("החל", color = Accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("ביטול", color = TextSecondary) }
        }
    )
}

/** Artists typed one per line, for a list that was written somewhere else. */
@Composable
private fun BulkImportDialog(onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        title = { Text("הזנה מרוכזת של אמנים") },
        text = {
            DialogBody {
                Column {
                    Text(
                        "שורה לכל אמן, בפורמט:\nשם | דירוג 1-5 | סגנונות מופרדים בפסיק\n\n" +
                            "למשל:\nאברהם פריד | 5 | חסידי, מרגש\nיונתן רזאל | 4 | רגוע, שירי נשמה",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 140.dp, max = 260.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(text) }) { Text("שמור", color = Accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("ביטול", color = TextSecondary) }
        }
    )
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
                                    contentDescription = localized("הסר מהרשימה"),
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
    feature: AudioFeatureEntity?,
    playlists: List<PlaylistInfo>,
    scoreTerms: List<ScoreTerm>,
    totalScore: Double,
    inPlaylist: Long?,
    onDismiss: () -> Unit,
    onRate: (Int) -> Unit,
    onRadio: () -> Unit,
    onMix: () -> Unit,
    onOpenArtist: () -> Unit,
    onOpenAlbum: () -> Unit,
    onAddTo: (Long) -> Unit,
    onCreateWith: (String) -> Unit,
    onRemoveFromPlaylist: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onLyrics: () -> Unit,
    onBookmarks: () -> Unit,
    onStyles: (String) -> Unit,
    onGenre: (String) -> Unit,
    onSpoken: (Boolean) -> Unit,
    onResetPlays: () -> Unit,
    onDelete: () -> Unit,
    /** Whether the song counts as vocal-only now, by the phone's rule. */
    vocalNow: Boolean = false,
    onVocal: (Boolean) -> Unit = {},
    /** What the audio reading says about each mood, the listener's own marks on this song left out. */
    moodReading: suspend () -> Map<Mood, Boolean> = { emptyMap() },
    onMoodMark: (Mood, Boolean?) -> Unit = { _, _ -> }
) {
    var picking by remember { mutableStateOf(false) }
    var moodOpen by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var whyOpen by remember { mutableStateOf(false) }
    var capoOpen by remember { mutableStateOf(false) }
    var tagsOpen by remember { mutableStateOf(false) }
    var genreOpen by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    if (whyOpen) {
        WhyDialog(
            title = song.title,
            terms = scoreTerms,
            total = totalScore,
            onDismiss = { whyOpen = false }
        )
        return
    }
    if (capoOpen) {
        CapoDialog(feature = feature, onDismiss = { capoOpen = false })
        return
    }
    if (moodOpen) {
        MoodDialog(
            marks = MoodMarks.parse(stat?.moods.orEmpty()),
            reading = moodReading,
            onMark = onMoodMark,
            onDismiss = { moodOpen = false }
        )
        return
    }
    if (tagsOpen) {
        SongTagDialog(
            current = Styles.parse(stat?.styles.orEmpty()),
            guessed = stat?.stylesAuto == 1,
            onDismiss = { tagsOpen = false },
            onApply = { onStyles(Styles.join(it)); onDismiss() }
        )
        return
    }
    if (genreOpen) {
        GenreDialog(
            initial = stat?.genre.orEmpty().ifBlank { song.genre.orEmpty() },
            onDismiss = { genreOpen = false },
            onApply = { onGenre(it); onDismiss() }
        )
        return
    }
    if (confirmReset) {
        ConfirmDialog(
            title = "לאפס את ההשמעות?",
            body = "מספר ההשמעות של \"${song.title}\" יתאפס, והשיר ייעלם מ\"הושמעו " +
                "לאחרונה\". הלייק, הדירוג והתגיות נשארים.",
            confirm = "אפס",
            danger = false,
            onConfirm = { onResetPlays(); onDismiss() },
            onDismiss = { confirmReset = false }
        )
        return
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "למחוק את הקובץ?",
            body = "\"${song.title}\" יימחק מהמחשב עצמו, לא רק מהאפליקציה. " +
                "אי אפשר לבטל את זה.",
            confirm = "מחק",
            danger = true,
            onConfirm = { onDelete(); onDismiss() },
            onDismiss = { confirmDelete = false }
        )
        return
    }

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
                // What the tagging model heard, as a hint and nothing more.
                // These are AudioSet's own words rather than the styles the
                // user tags with, and no hint is ever written into the library.
                val heard = AudioTags.hints(feature?.tags.orEmpty())
                if (heard.isNotEmpty()) {
                    Text(
                        "רמזים מהצליל: ${heard.joinToString(" · ")}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary
                    )
                }
            }
        },
        text = {
            // Sixteen rows do not fit a laptop screen, and a dialog does not
            // scroll on its own: everything past "צור מיקס" was simply off the
            // bottom with no way to reach it. Capped and scrollable, the same
            // way the playlist picker above already is.
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
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
                OptionRow(Icons.Filled.AutoAwesome, "צור מיקס מהשיר הזה") {
                    onMix()
                    onDismiss()
                }
                OptionRow(Icons.Filled.Radio, "רדיו מהשיר הזה") {
                    onRadio()
                    onDismiss()
                }
                // The ones the phone keeps in this menu rather than on the
                // player's header, so the header stays at three icons.
                OptionRow(Icons.Filled.FormatQuote, "מילות השיר") {
                    onLyrics()
                    onDismiss()
                }
                OptionRow(Icons.Filled.MusicNote, "אקורדים וקאפו") { capoOpen = true }
                OptionRow(Icons.Filled.Insights, "למה זה הומלץ לי") { whyOpen = true }
                OptionRow(Icons.Filled.LocalOffer, "תגיות סגנון לשיר") { tagsOpen = true }
                OptionRow(Icons.Filled.Mood, "מצב הרוח של השיר") { moodOpen = true }
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
                if (inPlaylist != null) {
                    OptionRow(Icons.Filled.PlaylistRemove, "הסר מהרשימה") {
                        onRemoveFromPlaylist()
                        onDismiss()
                    }
                }
                OptionRow(Icons.Filled.LocalOffer, "שנה ז'אנר") { genreOpen = true }
                // The detector's verdict, and a way to disagree with it.
                // Shown as the opposite of what it currently thinks, so the
                // row says what pressing it will do rather than what is
                // already true.
                val markedSpoken = stat?.spoken == 1
                OptionRow(
                    if (markedSpoken) Icons.Filled.MusicNote else Icons.Filled.RecordVoiceOver,
                    if (markedSpoken) "זה בעצם מוזיקה" else "סמן כהרצאה או שיעור"
                ) {
                    onSpoken(!markedSpoken)
                    onDismiss()
                }
                // Vocal-only: shown as the opposite of the current verdict,
                // like the speech row above. The phone's row.
                OptionRow(
                    Icons.Filled.MusicNote,
                    if (vocalNow) "זה לא ווקאלי" else "סמן כווקאלי (לספירה ולשלושת השבועות)"
                ) {
                    onVocal(!vocalNow)
                    onDismiss()
                }
                // Only worth offering when there is something to clear.
                if ((stat?.playCount ?: 0) > 0) {
                    OptionRow(Icons.Filled.RestartAlt, "אפס את מספר ההשמעות") {
                        confirmReset = true
                    }
                }
                OptionRow(Icons.Filled.Delete, "מחק את הקובץ מהמחשב", tint = Color_Error) {
                    confirmDelete = true
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("סגור", color = TextSecondary) }
        }
    )
}

/**
 * The song's moods: what the audio reading says, and the listener's own answer
 * for each, which always wins and is what the reading learns from. The
 * phone's MoodDialog.
 */
@Composable
private fun MoodDialog(
    marks: Map<Mood, Boolean>,
    reading: suspend () -> Map<Mood, Boolean>,
    onMark: (Mood, Boolean?) -> Unit,
    onDismiss: () -> Unit
) {
    val auto by produceState<Map<Mood, Boolean>?>(null) { value = reading() }
    // Held here as well, so a chip answers the tap at once rather than when
    // the store has written it and the stats have come back.
    var said by remember { mutableStateOf(marks) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        title = { Text("מצב הרוח של השיר") },
        text = {
            Column(modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "מה שתסמן גובר על הזיהוי האוטומטי, והאפליקציה לומדת ממנו " +
                        "לזהות נכון שירים שנשמעים דומה. לחיצה שנייה מחזירה לאוטומטי.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(Modifier.height(10.dp))
                for (mood in Mood.entries) {
                    val mine = said[mood]
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(mood.label, style = MaterialTheme.typography.titleSmall)
                            val reading = auto
                            Text(
                                when {
                                    mine != null -> "סימנת בעצמך"
                                    reading == null -> "…"
                                    reading.isEmpty() -> "השיר עוד לא נותח"
                                    reading[mood] == true -> "זוהה אוטומטית"
                                    else -> "לא זוהה"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = TextTertiary
                            )
                        }
                        Chip("כן", selected = mine == true) {
                            val next = if (mine == true) null else true
                            said = if (next == null) said - mood else said + (mood to next)
                            onMark(mood, next)
                        }
                        Spacer(Modifier.width(6.dp))
                        Chip("לא", selected = mine == false) {
                            val next = if (mine == false) null else false
                            said = if (next == null) said - mood else said + (mood to next)
                            onMark(mood, next)
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

@Composable
private fun OptionRow(
    icon: ImageVector,
    label: String,
    hint: String? = null,
    enabled: Boolean = true,
    tint: Color = Accent,
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
            tint = if (enabled) tint else TextSecondary
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
    onMore: (SongEntity) -> Unit,
    onOpenAlbum: (Long) -> Unit
) {
    val selected = Styles.parse(artist.styles)
    // As on the phone: the albums first, the one long list a chip away.
    var byAlbum by remember { mutableStateOf(true) }
    val shelves = remember(artist.songs) { ArtistShelves.of(artist.songs, looseName = "שירים בודדים") }
    // Closed to begin with, unless the artist has one album and nothing else.
    var openAlbums by remember(artist.key) {
        mutableStateOf(shelves.singleOrNull()?.albumId?.let { setOf(it) } ?: emptySet())
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val shown = if (byAlbum) shelves.flatMap { it.songs } else artist.songs
    Column(modifier = Modifier.fillMaxSize().background(AppBackground)) {
        DetailTopBar(title = artist.displayName, onBack = onBack)
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
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
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("לפי אלבומים", selected = byAlbum, onClick = { byAlbum = true })
                        Chip("כל השירים", selected = !byAlbum, onClick = { byAlbum = false })
                    }
                }
            }

            val row: @Composable (SongEntity) -> Unit = { song ->
                SongRow(
                    song = song,
                    isCurrent = song.id == current,
                    liked = stats[song.id]?.liked ?: 0,
                    rating = stats[song.id]?.rating ?: 0,
                    onClick = { onPlay(shown, shown.indexOf(song)) },
                    onMore = { onMore(song) },
                    onLike = { onLike(song) },
                    onDislike = { onDislike(song) }
                )
            }
            if (byAlbum) {
                // One heading would only repeat the page's own title.
                val headed = !(shelves.size == 1 && shelves[0].albumId == null)
                shelves.forEach { shelf ->
                    val albumId = shelf.albumId
                    // An album is closed until it is opened; its songs then sit
                    // inside it, with a button at the end that closes it again
                    // so a long album need not be scrolled back up to put away.
                    val open = albumId == null || albumId in openAlbums
                    if (headed) {
                        item(key = "shelf:${albumId ?: "loose"}") {
                            ShelfHeading(
                                shelf = shelf,
                                expanded = if (albumId == null) null else open,
                                onToggle = albumId?.let { id ->
                                    { openAlbums = if (id in openAlbums) openAlbums - id else openAlbums + id }
                                },
                                onOpen = albumId?.let { id -> { onOpenAlbum(id) } },
                                onPlay = { onPlay(shelf.songs, 0) }
                            )
                        }
                    }
                    if (open) {
                        if (albumId == null || !headed) {
                            items(shelf.songs, key = { it.id }) { song -> row(song) }
                        } else {
                            items(shelf.songs, key = { it.id }) { song -> InsideAlbum { row(song) } }
                            item(key = "close:$albumId") {
                                CloseAlbumRow(onClose = {
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
                items(artist.songs, key = { it.id }) { song -> row(song) }
            }
        }
    }
}

@Composable
private fun ShelfHeading(
    shelf: ArtistShelf,
    /** Whether the album is open, or null for the loose songs, which do not fold. */
    expanded: Boolean?,
    onToggle: (() -> Unit)?,
    onOpen: (() -> Unit)?,
    onPlay: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            // A click opens and closes the album where it is; the album's own
            // page is the arrow button beside it.
            .then(if (onToggle != null) Modifier.clickable(onClick = onToggle) else Modifier)
            .padding(start = GUTTER, end = GUTTER - 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (shelf.albumId != null) {
            Art(song = shelf.songs.first(), size = 52.dp, corner = 10.dp)
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
    val line = Surface3
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = GUTTER + 20.dp)
            .drawBehind {
                val x = if (layoutDirection == LayoutDirection.Rtl) size.width - 1.dp.toPx() else 1.dp.toPx()
                drawLine(line, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
            }
    ) { content() }
}

/** The end of an open album, with the way to close it where the reading stopped. */
@Composable
private fun CloseAlbumRow(onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = GUTTER + 20.dp, top = 2.dp, bottom = 8.dp),
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

/**
 * Capo positions for the song's key, and the chords that key contains.
 *
 * The detected key is a starting point, not a verdict - it can be wrong, and
 * a guitarist will hear that within one bar. So it is labelled as detected,
 * and changing it is a single click rather than something buried in a
 * setting. All of the arithmetic is [Capo] in :engine, the same the phone
 * asks.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CapoDialog(feature: AudioFeatureEntity?, onDismiss: () -> Unit) {
    val detectedKey = feature?.musicalKey ?: -1
    val detectedMode = MusicalMode.byOrdinalOrNull(feature?.scaleMode ?: -1)
    // tonicIsMajor, not brightFamily: this decides which chord gets fingered.
    val detectedBright = detectedMode?.tonicIsMajor ?: (feature?.mode == 1)
    var key by remember(detectedKey) { mutableStateOf(detectedKey) }
    var bright by remember(detectedBright) { mutableStateOf(detectedBright) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        title = { Text("אקורדים וקאפו") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())
            ) {
                if (key !in 0..11) {
                    Text(
                        "השיר עדיין לא נותח, אז אין סולם להתבסס עליו. " +
                            "אפשר לבחור סולם ידנית:",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                } else {
                    Text(
                        "הסולם שזוהה: ${Capo.keyName(key, bright)}" +
                            (detectedMode?.takeIf { it.ordinal > 1 }?.let { " · ${it.label}" }
                                ?: ""),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        "זיהוי אוטומטי מתוך הצליל — אם זה נשמע לא נכון, שנה למטה.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                Spacer(Modifier.height(12.dp))
                // Note names are Latin, and a bare "G#" dropped into a Hebrew
                // paragraph comes out as "#G" - the sharp jumps to the wrong
                // side. Laying the row out left to right fixes the spelling
                // and puts the chromatic scale in rising order too.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        for (pc in 0..11) {
                            Chip(label = Capo.NAMES[pc], selected = pc == key) { key = pc }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(label = "מז'ורי", selected = bright) { bright = true }
                    Chip(label = "מינורי", selected = !bright) { bright = false }
                }
                if (key in 0..11) {
                    Spacer(Modifier.height(16.dp))
                    Text("קאפו", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "בסריג המסומן, נגן את הצורות של הסולם שמימין",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(Modifier.height(6.dp))
                    for (option in Capo.options(key, bright)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (option.fret == 0) "בלי קאפו" else "סריג ${option.fret}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (option.open) Accent else TextSecondary
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = Capo.keyName(option.playKey, bright) +
                                    if (option.open) "  ✓" else "",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    textDirection = TextDirection.Ltr
                                ),
                                color = if (option.open) Accent else TextSecondary
                            )
                        }
                    }
                    Text(
                        "✓ = אקורדים פתוחים, בלי בָּארֶה",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    val chords = detectedMode?.let { Capo.scaleChords(key, it) }
                        ?: Capo.scaleChords(
                            key,
                            if (bright) MusicalMode.MAJOR else MusicalMode.MINOR
                        )
                    if (chords.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        Text("האקורדים של הסולם", style = MaterialTheme.typography.titleSmall)
                        // Said plainly, because it is the difference between a
                        // shortlist and a transcription: nothing here listened
                        // to the recording.
                        Text(
                            "אלה האקורדים שקיימים בסולם — לא האקורדים שהשיר מנגן. " +
                                "האפליקציה לא מזהה אקורדים מתוך ההקלטה.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            chords.joinToString("   "),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                textDirection = TextDirection.Ltr
                            ),
                            color = Accent
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("סגור", color = Accent) } }
    )
}

/** Style words on one song, which override the artist's for it alone. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SongTagDialog(
    current: List<String>,
    guessed: Boolean,
    onDismiss: () -> Unit,
    onApply: (List<String>) -> Unit
) {
    var selected by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        title = { Text("תגיות לשיר הזה") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())
            ) {
                Text(
                    "תגית על שיר בודד מחליפה את תגיות האמן עבורו בלבד.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                if (guessed) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "התגיות האלה נוחשו על ידי האפליקציה ולא נבחרו על ידך. " +
                            "שינוי כאן הופך אותן לשלך, והלמידה כבר לא תדרוס אותן.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Accent
                    )
                }
                Spacer(Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (style in Styles.SUGGESTED) {
                        val on = selected.any { it.equals(style, ignoreCase = true) }
                        Chip(label = style, selected = on) {
                            selected = if (on) {
                                selected.filterNot { it.equals(style, ignoreCase = true) }
                            } else {
                                selected + style
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(selected) }) { Text("שמור", color = Accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("ביטול", color = TextSecondary) }
        }
    )
}

/**
 * Sets a genre on a song.
 *
 * The suggestions are the app's own style words, because a genre on a
 * downloaded file is usually blank or the name of the site it came from, and
 * a list of familiar words is faster than typing and keeps the spelling
 * consistent - which is what lets the engine group by it at all. Free text
 * stays allowed for everything the list does not cover.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GenreDialog(initial: String, onDismiss: () -> Unit, onApply: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        title = { Text("ז'אנר") },
        text = {
            DialogBody {
                Column {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        label = { Text("למשל: חסידי") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (style in Styles.SUGGESTED) {
                            Chip(label = style, selected = text == style) { text = style }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(text.trim()) }) { Text("שמור", color = Accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("ביטול", color = TextSecondary) }
        }
    )
}

/** Asks before something that cannot be taken back. */
@Composable
internal fun ConfirmDialog(
    title: String,
    body: String,
    confirm: String,
    danger: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        title = { Text(title) },
        text = {
            DialogBody {     Text(body, color = TextSecondary)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirm, color = if (danger) Color_Error else Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("ביטול", color = TextSecondary) }
        }
    )
}

/**
 * The folders, as folders: one level at a time, with a way back to any level
 * above.
 *
 * This is the one view that matches how the files are actually filed, which
 * for a library built by hand over years is often the only arrangement its
 * owner trusts. A flat list of every folder that happens to hold an audio
 * file loses exactly what makes that arrangement useful - the nesting - so
 * the tree walks.
 *
 * Playing a folder means everything under it, not just the songs sitting
 * directly in it: someone who clicks "play all" on a folder of folders is
 * asking for the lot.
 */
@Composable
private fun FolderTree(
    songs: List<SongEntity>,
    stats: Map<Long, SongStatsEntity>,
    current: Long?,
    onTagFolder: (List<Long>, List<String>, Boolean) -> Unit,
    onRateFolder: (List<Long>, Int) -> Unit,
    onPlay: (List<SongEntity>, Int) -> Unit,
    onShuffle: (List<SongEntity>) -> Unit,
    onLike: (SongEntity) -> Unit,
    onDislike: (SongEntity) -> Unit,
    onMore: (SongEntity) -> Unit
) {
    val root = remember(songs) { Folders.build(songs) }
    var path by remember(root.path) { mutableStateOf(root.path) }
    // A rescan can remove the folder being looked at, and a path that no
    // longer exists would otherwise show an empty screen with no way out.
    val here = remember(root, path) { Folders.find(root, path) ?: root }
    val trail = remember(root, here) { Folders.trail(root, here.path) }
    // Which folder the style dialog is about, or null while it is closed.
    var tagging by remember { mutableStateOf<Folders.Node?>(null) }
    // Which folder the rating dialog is about, likewise.
    var rating by remember { mutableStateOf<Folders.Node?>(null) }

    tagging?.let { node ->
        val inside = remember(node) { Folders.allSongs(node) }
        FolderStyleDialog(
            folderName = node.name,
            count = inside.size,
            onDismiss = { tagging = null },
            onApply = { styles, replace -> onTagFolder(inside.map { it.id }, styles, replace) }
        )
    }
    rating?.let { node ->
        val inside = remember(node) { Folders.allSongs(node) }
        FolderRatingDialog(
            folderName = node.name,
            songs = inside,
            stats = stats,
            onDismiss = { rating = null },
            onApply = { ids, stars ->
                onRateFolder(ids, stars)
                rating = null
            }
        )
    }

    if (root.total == 0) {
        EmptyState(
            title = "לא נמצאו תיקיות",
            body = "התיקיות מופיעות אחרי שהאפליקציה סורקת את המחשב."
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Where we are, and a way back to any level above without clicking
        // back once per folder.
        if (trail.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = GUTTER, vertical = 6.dp),
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
            if (here.total > 0 && here.path != root.path) {
                item {
                    Row(
                        modifier = Modifier.padding(horizontal = GUTTER, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Chip(label = "נגן הכל", selected = false, onClick = {
                            val all = Folders.allSongs(here)
                            if (all.isNotEmpty()) onPlay(all, 0)
                        })
                        Chip(label = "ערבב", selected = false, onClick = {
                            onShuffle(Folders.allSongs(here))
                        })
                        Chip(label = "תייג סגנון", selected = false, onClick = { tagging = here })
                        Chip(label = "דרג", selected = false, onClick = { rating = here })
                    }
                }
            }

            items(here.children, key = { it.path }) { child ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { path = child.path }
                        .padding(horizontal = GUTTER, vertical = 7.dp),
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
                    Icon(
                        Icons.Filled.ChevronLeft,
                        contentDescription = null,
                        tint = TextTertiary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            itemsIndexed(here.songs, key = { _, song -> song.id }) { index, song ->
                SongRow(
                    song = song,
                    isCurrent = song.id == current,
                    liked = stats[song.id]?.liked ?: 0,
                    rating = stats[song.id]?.rating ?: 0,
                    onClick = { onPlay(here.songs, index) },
                    onLike = { onLike(song) },
                    onDislike = { onDislike(song) },
                    onMore = { onMore(song) }
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
 * One rating for every song in a folder, subfolders included. Whether songs
 * already rated one by one keep their own is asked, and keeping them is the
 * default. The phone's dialog.
 */
@Composable
private fun FolderRatingDialog(
    folderName: String,
    songs: List<SongEntity>,
    stats: Map<Long, SongStatsEntity>,
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
            DialogBody {
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
 * The same dialog the phone shows, for the same reason: a downloaded library
 * arrives as folders and the folder is usually the answer for everything
 * inside it, so tagging song by song was most of the manual work the app
 * asked for.
 *
 * Adding is the default and replacing is the opt-in, because the destructive
 * one is the one that has to be chosen on purpose. A tag the app guessed is
 * overwritten either way: it was never the user's answer, and this is.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FolderStyleDialog(
    folderName: String,
    count: Int,
    onDismiss: () -> Unit,
    onApply: (List<String>, Boolean) -> Unit
) {
    var selected by remember { mutableStateOf(emptyList<String>()) }
    var replace by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        title = { Text("תגיות לתיקייה") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "\"$folderName\" · $count שירים, כולל תת־תיקיות",
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(label = "הוסף לקיים", selected = !replace, onClick = { replace = false })
                    Chip(label = "החלף מה שיש", selected = replace, onClick = { replace = true })
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
 * The older flat list of folders, for anyone who preferred it.
 *
 * Every folder that holds a file, at one level, sorted. On a library filed
 * two or three deep this is genuinely quicker than walking the tree, and the
 * setting exists because which of the two is quicker depends entirely on how
 * the person filed their music.
 */
@Composable
private fun FolderList(
    folders: List<FolderInfo>,
    onOpenList: (DetailList) -> Unit
) {
    if (folders.isEmpty()) {
        EmptyState(
            title = "לא נמצאו תיקיות",
            body = "התיקיות מופיעות אחרי שהאפליקציה סורקת את המחשב."
        )
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        items(folders, key = { it.path }) { folder ->
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
                    Icon(Icons.Filled.Folder, contentDescription = null, tint = Color.White)
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
}
