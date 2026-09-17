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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.engine.FeedSection
import com.elchanan.rhythm.engine.Mood
import com.elchanan.rhythm.engine.SectionKind
import com.elchanan.rhythm.ui.MainViewModel
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.text.style.TextOverflow
import com.elchanan.rhythm.ui.AlbumInfo
import com.elchanan.rhythm.ui.components.Artwork
import com.elchanan.rhythm.ui.components.rememberMetrics
import com.elchanan.rhythm.ui.components.Chip
import com.elchanan.rhythm.ui.components.EmptyState
import com.elchanan.rhythm.ui.components.MixCard
import com.elchanan.rhythm.ui.components.RhythmMark
import com.elchanan.rhythm.ui.components.SectionHeader
import com.elchanan.rhythm.ui.components.SongCard
import com.elchanan.rhythm.ui.components.quickPickColumnWidth
import com.elchanan.rhythm.ui.components.formatDuration
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import com.elchanan.rhythm.ui.theme.HeaderWarm
import com.elchanan.rhythm.ui.theme.HeaderMid
import com.elchanan.rhythm.ui.theme.Accent
import com.elchanan.rhythm.ui.theme.Accent2
import com.elchanan.rhythm.ui.theme.AppBackground
import com.elchanan.rhythm.ui.theme.Bg
import com.elchanan.rhythm.ui.theme.Surface1
import com.elchanan.rhythm.ui.theme.Surface2
import com.elchanan.rhythm.ui.theme.TextPrimary
import com.elchanan.rhythm.ui.theme.TextSecondary
import java.util.Calendar

@Composable
fun HomeScreen(
    vm: MainViewModel,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onOpenDetail: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenRatings: () -> Unit = {},
    onOpenRecap: () -> Unit = {}
) {
    val library by vm.library.collectAsStateWithLifecycle()
    val feed by vm.feed.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val analysis by vm.analysisProgress.collectAsStateWithLifecycle()

    val tagTipVisible by vm.tagTipVisible.collectAsStateWithLifecycle()
    val ratingTipVisible by vm.ratingTipVisible.collectAsStateWithLifecycle()

    val statusPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    var sheetSong by remember { mutableStateOf<SongEntity?>(null) }
    val pinMoods = vm.prefs.pinMoodRow

    // Tapping home while already home scrolls the feed back to the top.
    val feedState = rememberLazyListState()
    val homeTop by vm.homeTopSignal.collectAsStateWithLifecycle()
    LaunchedEffect(homeTop) {
        if (homeTop > 0) feedState.animateScrollToItem(0)
    }

    Column(modifier = Modifier.fillMaxSize().background(AppBackground)) {
        // A wash of colour behind the header and the category chips, fading out
        // into the page. It sits under both rather than belonging to either, so
        // the two keep their own spacing and the gradient ends where it likes
        // instead of at a component boundary.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            HeaderWarm,
                            HeaderMid,
                            Color.Transparent
                        )
                    )
                )
        ) {
            HomeTopBar(
                padding = statusPadding,
                onRefresh = { vm.refreshFeed(reshuffle = true) },
                onRecap = onOpenRecap,
                onSettings = onOpenSettings
            )
            if (busy) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = Accent
                )
            }

            // Pinned under the header by default, so the categories stay one
            // tap away wherever you are in the list. Switched off, they go
            // into the feed below and scroll away with it.
            if (hasPermission && library.songs.isNotEmpty() && pinMoods) {
                MoodChipRow(onPick = { mood -> vm.openMood(mood) { onOpenDetail() } })
            }
            Spacer(Modifier.height(6.dp))
        }

        when {
            !hasPermission -> EmptyState(
                title = "צריך גישה לשירים",
                body = "האפליקציה קוראת רק את קבצי המוזיקה שכבר נמצאים במכשיר. אין אינטרנט, אין העלאות.",
                action = {
                    Button(
                        onClick = onRequestPermission,
                        colors = ButtonDefaults.buttonColors(containerColor = Accent)
                    ) { Text("אישור גישה") }
                }
            )

            library.songs.isEmpty() -> EmptyState(
                title = "לא נמצאו שירים",
                body = "אפשר לסרוק שוב אחרי שמעתיקים קבצים למכשיר.",
                action = {
                    Button(
                        onClick = { vm.rescan() },
                        colors = ButtonDefaults.buttonColors(containerColor = Accent)
                    ) { Text("סרוק עכשיו") }
                }
            )

            else -> LazyColumn(
                state = feedState,
                contentPadding = PaddingValues(bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (!pinMoods) {
                    item {
                        MoodChipRow(onPick = { mood -> vm.openMood(mood) { onOpenDetail() } })
                    }
                }

                item { GreetingCard(library.songs.size, library.artists.count { it.rating > 0 }) }

                // One-off pointer to the tag repair tool. Shown ahead of the other
                // nudges because a library filed under a single artist makes every
                // one of them meaningless.
                if (tagTipVisible && library.artists.size <= 2 && library.songs.size >= 8) {
                    item {
                        Banner(
                            icon = Icons.Filled.Sell,
                            title = "כל השירים רשומים על אמן אחד",
                            body = "התגיות בקבצים שהורדו מהאינטרנט לרוב שגויות. " +
                                "בהגדרות יש תיקון אוטומטי שמפריד את שם האמן משם השיר",
                            action = "להגדרות",
                            onClick = { vm.dismissTagTip(); onOpenSettings() },
                            onDismiss = { vm.dismissTagTip() }
                        )
                    }
                }

                // one nudge at a time, and only while it is still relevant
                item {
                    val unrated = library.artists.count { it.rating == 0 }
                    when {
                        analysis.running -> Banner(
                            icon = Icons.Filled.GraphicEq,
                            title = "מנתח את הספרייה",
                            body = "${analysis.done} מתוך ${analysis.total} · ${analysis.currentTitle.orEmpty()}",
                            action = "עצור",
                            onClick = { vm.stopAnalysis() }
                        )

                        analysis.remaining > 0 && analysis.total > 0 -> Banner(
                            icon = Icons.Filled.GraphicEq,
                            title = "${analysis.remaining} שירים עוד לא נותחו",
                            body = "ניתוח הקצב והגוון משפר את הרדיו ואת המיקסים",
                            action = "נתח",
                            onClick = { vm.startAnalysis() }
                        )

                        ratingTipVisible && unrated > 0 &&
                            library.artists.count { it.rating > 0 } < 12 -> Banner(
                            icon = Icons.Filled.Star,
                            title = "$unrated אמנים עוד לא מדורגים",
                            body = "כמה דירוגים משנים את הפיד יותר מכל דבר אחר",
                            action = "דרג",
                            onClick = onOpenRatings,
                            onDismiss = { vm.dismissRatingTip() }
                        )

                        else -> Unit
                    }
                }

                items(feed, key = { it.id }) { section ->
                    FeedSectionView(
                        section = section,
                        vm = vm,
                        onOpenDetail = onOpenDetail,
                        onMore = { sheetSong = it }
                    )
                }

                // Only worth a shelf once there is something to browse. A library
                // of singles collapses into a single folder-named album, and one
                // lone tile reads as a bug rather than a section.
                if (library.albums.size >= 3) {
                    item {
                        AlbumShelf(
                            albums = library.albums,
                            onOpen = { album ->
                                vm.openList(
                                    album.name,
                                    album.artistName,
                                    album.songs,
                                    "album:${album.albumId}"
                                )
                                onOpenDetail()
                            }
                        )
                    }
                }

                if (feed.isEmpty()) {
                    item {
                        EmptyState(
                            title = "בונה את הפיד",
                            body = "רגע אחד, מחשב דירוגים.",
                            action = {
                                Button(
                                    onClick = { vm.refreshFeed(true) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                                ) { Text("רענון") }
                            }
                        )
                    }
                }
            }
        }
    }

    sheetSong?.let { song ->
        SongOptionsSheet(
            vm = vm,
            song = song,
            onDismiss = { sheetSong = null },
            onOpenDetail = onOpenDetail
        )
    }
}

@Composable
private fun AlbumShelf(albums: List<AlbumInfo>, onOpen: (AlbumInfo) -> Unit) {
    val metrics = rememberMetrics()
    val width = metrics.cardWidth
    // Matching the header's margin so the first cover lines up with its title.
    val gutter = metrics.gutter
    Column {
        SectionHeader(title = "אלבומים בשבילך", subtitle = "מתוך הספרייה שלך")
        LazyRow(contentPadding = PaddingValues(horizontal = gutter)) {
            items(albums.take(20), key = { it.albumId }) { album ->
                Column(
                    modifier = Modifier
                        .width(width)
                        .clickable { onOpen(album) }
                        .padding(horizontal = 4.dp, vertical = 4.dp)
                ) {
                    Artwork(
                        songId = album.songs.firstOrNull()?.id ?: -1L,
                        albumId = album.albumId,
                        seed = album.name,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                        corner = 12
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = album.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${album.songs.size} שירים",
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
 * The category strip under the header.
 *
 * Every chip is a predicate over the measured audio features, so the row works
 * off the same analysis the recommender uses. Picking one before the library has
 * been analysed is not an error - [MainViewModel.openMood] answers with a message
 * explaining there is not enough measured audio yet, which is a far better
 * introduction to the feature than hiding it until some threshold is crossed.
 */
@Composable
private fun MoodChipRow(onPick: (Mood) -> Unit) {
    val gutter = rememberMetrics().gutter
    LazyRow(
        contentPadding = PaddingValues(horizontal = gutter, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(Mood.entries.size) { index ->
            val mood = Mood.entries[index]
            Chip(
                label = mood.label,
                selected = false,
                onClick = { onPick(mood) }
            )
        }
    }
}

@Composable
private fun HomeTopBar(
    padding: androidx.compose.ui.unit.Dp,
    onRefresh: () -> Unit,
    onRecap: () -> Unit,
    onSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = padding)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RhythmMark(size = 30.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            text = "Rhythm",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onRefresh) {
            Icon(Icons.Filled.Autorenew, contentDescription = "רענון", tint = TextSecondary)
        }
        IconButton(onClick = onRecap) {
            Icon(Icons.Filled.BarChart, contentDescription = "הסיכום שלך", tint = TextSecondary)
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Filled.Settings, contentDescription = "הגדרות", tint = TextSecondary)
        }
    }
}

@Composable
private fun Banner(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    action: String,
    onClick: () -> Unit,
    onDismiss: (() -> Unit)? = null
) {
    // On a narrow phone the margins and the two-line clamp together cut the
    // explanation off mid-sentence, which is exactly the text that has to be
    // read for the nudge to mean anything.
    val metrics = rememberMetrics()
    val compact = metrics.isCompact
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = metrics.gutter, vertical = 4.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            // Surface2, not Surface1: against the lifted background the darker
            // card had all but disappeared, taking its text with it.
            .background(Surface2)
            .clickable(onClick = onClick)
            .padding(
                start = 8.dp,
                end = if (compact) 10.dp else 14.dp,
                top = 12.dp,
                bottom = 12.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = Accent)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary
            )
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = if (compact) 3 else 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(if (compact) 4.dp else 8.dp))
        // The action and the dismiss sit outside the weighted column so a long
        // body can never squeeze either of them out of the row.
        Text(
            action,
            style = MaterialTheme.typography.labelLarge,
            color = Accent,
            maxLines = 1
        )
        // A nudge with no way out stops being a nudge. The choice is remembered,
        // so a suggestion that has been turned down stays turned down.
        if (onDismiss != null) {
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onDismiss, modifier = Modifier.size(30.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "סגור",
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun GreetingCard(songCount: Int, ratedArtists: Int) {
    val gutter = rememberMetrics().gutter
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val greeting = when (hour) {
        in 5..11 -> "בוקר טוב"
        in 12..16 -> "צהריים טובים"
        in 17..21 -> "ערב טוב"
        else -> "לילה טוב"
    }
    Column(modifier = Modifier.padding(horizontal = gutter, vertical = 10.dp)) {
        Text(
            text = greeting,
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "$songCount שירים במכשיר · $ratedArtists אמנים מדורגים",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
    }
}

@Composable
private fun FeedSectionView(
    section: FeedSection,
    vm: MainViewModel,
    onOpenDetail: () -> Unit,
    onMore: (SongEntity) -> Unit
) {
    val library by vm.library.collectAsStateWithLifecycle()
    val gutter = rememberMetrics().gutter

    when (section.kind) {
        SectionKind.QUICK_PICKS -> {
            SectionHeader(
                title = section.title,
                subtitle = section.subtitle,
                actionLabel = "נגן הכל",
                onAction = { vm.playList(section.songs) }
            )
            // Covers with the title written across them, which is how these are
            // actually recognised: someone returning to a track knows its sleeve
            // long before they read its name.
            QuickPickTiles(
                songs = section.songs.take(9),
                gutter = gutter,
                onPlay = { song ->
                    val index = section.songs.indexOf(song)
                    vm.playList(section.songs, if (index >= 0) index else 0)
                },
                onMore = onMore
            )
        }

        SectionKind.MIX_ROW -> {
            SectionHeader(title = section.title, subtitle = section.subtitle)
            LazyRow(contentPadding = PaddingValues(horizontal = gutter)) {
                items(section.mixes, key = { it.id }) { mix ->
                    MixCard(
                        id = mix.id,
                        title = mix.title,
                        subtitle = mix.subtitle,
                        count = mix.songs.size,
                        onClick = {
                            vm.openMix(mix)
                            onOpenDetail()
                        },
                        onPlay = { vm.playList(mix.songs) },
                        covers = mix.songs.take(4).map { it.id to it.albumId }
                    )
                }
            }
        }

        SectionKind.SONG_ROW -> {
            SectionHeader(
                title = section.title,
                subtitle = section.subtitle,
                actionLabel = "הכל",
                onAction = {
                    vm.openList(section.title, section.subtitle, section.songs, section.id)
                    onOpenDetail()
                }
            )
            LazyRow(contentPadding = PaddingValues(horizontal = gutter)) {
                items(section.songs, key = { it.id }) { song ->
                    SongCard(
                        song = song,
                        onClick = {
                            val index = section.songs.indexOf(song)
                            vm.playList(section.songs, if (index >= 0) index else 0)
                        },
                        onMore = { onMore(song) }
                    )
                }
            }
        }
    }
}

/**
 * The quick picks, as covers with the title written across them.
 *
 * Two arrangements of the same tile, because which one is right depends on the
 * shape of the window rather than on a preference. A phone is tall and narrow,
 * so three columns fill it and nine tiles are in view at once. A wide screen
 * has room lengthways and little to spare downwards, so the same tiles run
 * sideways in one strip - a three column grid there would either leave most of
 * the width empty or push everything below it off the screen.
 */
@Composable
private fun QuickPickTiles(
    songs: List<SongEntity>,
    gutter: Dp,
    onPlay: (SongEntity) -> Unit,
    onMore: (SongEntity) -> Unit
) {
    if (songs.isEmpty()) return
    val metrics = rememberMetrics()

    if (metrics.isCompact) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = gutter),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            songs.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { song ->
                        QuickPickTile(
                            song = song,
                            modifier = Modifier.weight(1f),
                            onPlay = { onPlay(song) },
                            onMore = { onMore(song) }
                        )
                    }
                    // Keeps a short last row aligned with the ones above rather
                    // than letting two tiles stretch across the whole width.
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    } else {
        LazyRow(
            contentPadding = PaddingValues(horizontal = gutter),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(songs, key = { it.id }) { song ->
                QuickPickTile(
                    song = song,
                    modifier = Modifier.width(metrics.cardWidth),
                    onPlay = { onPlay(song) },
                    onMore = { onMore(song) }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickPickTile(
    song: SongEntity,
    modifier: Modifier = Modifier,
    onPlay: () -> Unit,
    onMore: () -> Unit
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onPlay, onLongClick = onMore)
    ) {
        Artwork(
            songId = song.id,
            albumId = song.albumId,
            seed = song.artistKey,
            modifier = Modifier.fillMaxSize(),
            corner = 0
        )
        // Without this the title lands on whatever the sleeve happens to be and
        // is unreadable on about half of them. The scrim darkens only the strip
        // the words sit on, leaving the artwork above it alone.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.45f)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f))
                    )
                )
        )
        Text(
            text = song.title,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 8.dp, vertical = 7.dp)
        )
    }
}
