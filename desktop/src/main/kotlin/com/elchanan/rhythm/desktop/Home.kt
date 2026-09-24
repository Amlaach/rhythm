package com.elchanan.rhythm.desktop

import com.elchanan.rhythm.ui.theme.localized

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.elchanan.rhythm.ui.theme.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.data.db.SongStatsEntity
import com.elchanan.rhythm.engine.FeedSection
import com.elchanan.rhythm.engine.Mix
import com.elchanan.rhythm.engine.Mood
import com.elchanan.rhythm.engine.SectionKind
import com.elchanan.rhythm.engine.ShelfKind
import com.elchanan.rhythm.ui.theme.Accent
import com.elchanan.rhythm.ui.theme.AppBackground
import com.elchanan.rhythm.ui.theme.HeaderMid
import com.elchanan.rhythm.ui.theme.collageSongs
import com.elchanan.rhythm.ui.theme.isFolderNamedAlbum
import com.elchanan.rhythm.ui.theme.HeaderWarm
import com.elchanan.rhythm.ui.theme.RhythmMark
import com.elchanan.rhythm.ui.theme.Surface2
import com.elchanan.rhythm.ui.theme.TextPrimary
import com.elchanan.rhythm.ui.theme.TextSecondary
import com.elchanan.rhythm.ui.theme.gradientFor
import java.util.Calendar

/**
 * The home screen, built the way the phone builds it.
 *
 * What used to be here was a row of four large buttons - pick a folder, scan,
 * analyse, shuffle - which the phone has nowhere, and which made the first
 * thing anyone saw a control panel rather than their music. Those belong in
 * settings, and the two of them that are ever urgent say so here as a banner,
 * the way the phone says it.
 *
 * The order is the phone's: a wash of colour behind the top bar and the mood
 * chips, then the greeting, then at most one nudge, then the shelves the
 * engine built, then the albums.
 */
@Composable
internal fun HomeScreen(
    feed: List<FeedSection>,
    songCount: Int,
    ratedArtists: Int,
    albums: List<AlbumInfo>,
    stats: Map<Long, SongStatsEntity>,
    moods: List<Mood>,
    /** The phone's rule: on, the chips stay under the header; off, they scroll away with the page. */
    moodsPinned: Boolean = true,
    scanning: Boolean,
    analysing: Boolean,
    unanalysed: Int,
    status: String,
    hasFolders: Boolean,
    singleArtist: Boolean,
    tagTipVisible: Boolean,
    ratingTipVisible: Boolean,
    onMood: (Mood) -> Unit,
    onRefresh: () -> Unit,
    onRecap: () -> Unit,
    onSettings: () -> Unit,
    onPickFolder: () -> Unit,
    onRescan: () -> Unit,
    onAnalyze: () -> Unit,
    onRateArtists: () -> Unit,
    onStopAnalysis: () -> Unit,
    onDismissTagTip: () -> Unit,
    onDismissRatingTip: () -> Unit,
    onPlay: (List<SongEntity>, Int) -> Unit,
    onOpenList: (DetailList) -> Unit,
    onMore: (SongEntity) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().background(AppBackground)) {
        // A wash of colour behind the header and the chips, fading into the
        // page. It sits under both rather than belonging to either, so the two
        // keep their own spacing and the gradient ends where it likes instead
        // of at a component boundary.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(listOf(HeaderWarm, HeaderMid, Color.Transparent))
                )
        ) {
            HomeTopBar(
                onRefresh = onRefresh,
                onRecap = onRecap,
                onSettings = onSettings
            )
            if (scanning || analysing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Accent)
            }
            if (songCount > 0 && moods.isNotEmpty() && moodsPinned) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = GUTTER, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(moods) { mood ->
                        Chip(label = mood.label, selected = false) { onMood(mood) }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        if (songCount == 0) {
            EmptyState(
                title = if (hasFolders) "לא נמצאו שירים" else "לא נבחרה תיקייה",
                body = if (hasFolders) {
                    "אפשר לסרוק שוב אחרי שמעתיקים קבצים לתיקייה."
                } else {
                    "בחר את התיקייה שבה המוזיקה שלך יושבת. " +
                        "הכל נקרא מהמחשב הזה, בלי אינטרנט ובלי העלאות."
                },
                action = {
                    Button(
                        onClick = if (hasFolders) onRescan else onPickFolder,
                        enabled = !scanning,
                        colors = ButtonDefaults.buttonColors(containerColor = Accent)
                    ) { Text(if (hasFolders) "סרוק עכשיו" else "בחר תיקייה") }
                }
            )
            if (status.isNotBlank()) {
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(horizontal = GUTTER)
                )
            }
            return@Column
        }

        LazyColumn(
            contentPadding = PaddingValues(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (!moodsPinned && moods.isNotEmpty()) {
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = GUTTER, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(moods) { mood ->
                            Chip(label = mood.label, selected = false) { onMood(mood) }
                        }
                    }
                }
            }
            item { GreetingCard(songCount, ratedArtists) }

            // A one off pointer to the tag repair tool, ahead of the rest
            // because a library filed under a single artist makes every other
            // suggestion meaningless until that is fixed.
            if (tagTipVisible && singleArtist) {
                item {
                    Banner(
                        icon = Icons.Filled.Sell,
                        title = "כל השירים רשומים על אמן אחד",
                        body = "התגיות בקבצים שהורדו מהאינטרנט לרוב שגויות. " +
                            "בהגדרות יש תיקון אוטומטי שמפריד את שם האמן משם השיר",
                        action = "להגדרות",
                        onClick = { onDismissTagTip(); onSettings() },
                        onDismiss = onDismissTagTip
                    )
                }
            }

            // One nudge at a time, and only while it is still relevant.
            item {
                when {
                    analysing -> Banner(
                        icon = Icons.Filled.GraphicEq,
                        title = "מנתח את הספרייה",
                        body = status.ifBlank { "מודד קצב, סולם, אנרגיה וגוון" },
                        action = "עצור",
                        onClick = onStopAnalysis
                    )

                    unanalysed > 0 -> Banner(
                        icon = Icons.Filled.GraphicEq,
                        title = "$unanalysed שירים עוד לא נותחו",
                        body = "ניתוח הקצב והגוון משפר את הרדיו ואת המיקסים",
                        action = "נתח",
                        onClick = onAnalyze
                    )

                    ratingTipVisible && ratedArtists < 12 -> Banner(
                        icon = Icons.Filled.Star,
                        title = "כמה אמנים עוד לא מדורגים",
                        body = "כמה דירוגים משנים את הפיד יותר מכל דבר אחר",
                        action = "דרג",
                        onClick = onRateArtists,
                        onDismiss = onDismissRatingTip
                    )

                    else -> Unit
                }
            }

            items(feed, key = { it.id }) { section ->
                FeedSectionView(
                    section = section,
                    stats = stats,
                    onPlay = onPlay,
                    onOpenList = onOpenList,
                    onMore = onMore
                )
            }

            // Only worth a shelf once there is something to browse. A library
            // of singles collapses into one folder-named album, and a lone
            // tile reads as a bug rather than a section.
            // Folders filed as albums - "Download", "Music" - stay off it.
            val shelfAlbums = albums.filterNot { isFolderNamedAlbum(it.name, it.songs) }
            if (shelfAlbums.size >= 3) {
                item {
                    Column {
                        SectionHeader("אלבומים בשבילך", "מתוך הספרייה שלך")
                        LazyRow(contentPadding = PaddingValues(horizontal = GUTTER)) {
                            items(shelfAlbums.take(20), key = { it.albumId }) { album ->
                                Column(
                                    modifier = Modifier
                                        .width(CARD)
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
                                        .padding(horizontal = 4.dp, vertical = 4.dp)
                                ) {
                                    AlbumArt(album, size = CARD - 8.dp, corner = 12.dp)
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        album.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "${album.songs.size} שירים",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (feed.isEmpty()) {
                item {
                    EmptyState(
                        title = "בונה את הפיד",
                        body = "רגע אחד, מחשב דירוגים.",
                        action = {
                            Button(
                                onClick = onRefresh,
                                colors = ButtonDefaults.buttonColors(containerColor = Accent)
                            ) { Text("רענון") }
                        }
                    )
                }
            }
        }
    }
}

/**
 * The mark, the name, and the things reached from the home screen.
 *
 * The gear is here and not in the navigation bar because that is where the
 * phone puts it, and because four tabs are the app.
 */
@Composable
private fun HomeTopBar(
    onRefresh: () -> Unit,
    onRecap: () -> Unit,
    onSettings: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RhythmMark(size = 30.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            text = "Rhythm",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onRefresh) {
            Icon(Icons.Filled.Autorenew, contentDescription = localized("רענון"), tint = TextSecondary)
        }
        IconButton(onClick = onRecap) {
            Icon(Icons.Filled.BarChart, contentDescription = localized("הסיכום שלך"), tint = TextSecondary)
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Filled.Settings, contentDescription = localized("הגדרות"), tint = TextSecondary)
        }
    }
}

@Composable
private fun GreetingCard(songCount: Int, ratedArtists: Int) {
    val greeting = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..11 -> "בוקר טוב"
        in 12..16 -> "צהריים טובים"
        in 17..21 -> "ערב טוב"
        else -> "לילה טוב"
    }
    Column(modifier = Modifier.padding(horizontal = GUTTER, vertical = 10.dp)) {
        Text(greeting, style = MaterialTheme.typography.displaySmall)
        Text(
            "$songCount שירים · $ratedArtists אמנים מדורגים",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
    }
}

/**
 * A nudge, in a card, with one thing to do about it.
 *
 * Deliberately not a dialog and not a toast: it is information about the
 * library rather than something that happened, so it waits where it is until
 * it stops being true.
 */
@Composable
private fun Banner(
    icon: ImageVector,
    title: String,
    body: String,
    action: String,
    onClick: () -> Unit,
    onDismiss: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = GUTTER, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            // Surface2, not Surface1: against the lifted background the darker
            // card had all but disappeared, taking its text with it.
            .background(Surface2)
            .clickable(enabled = action.isNotEmpty(), onClick = onClick)
            .padding(start = 8.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = Accent)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = TextPrimary)
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        // The action sits outside the weighted column so a long body can never
        // squeeze it out of the row.
        if (action.isNotEmpty()) {
            Text(
                action,
                style = MaterialTheme.typography.labelLarge,
                color = Accent,
                maxLines = 1
            )
        }
        if (onDismiss != null) {
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onDismiss, modifier = Modifier.size(30.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = localized("סגור"),
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/**
 * A shelf heading: the name in full size, what it is under it in grey, and
 * where it exists, the one thing to do with the whole shelf.
 *
 * The subtitle is where a shelf says why it exists - "מתוך הספרייה שלך",
 * "כי שמעת" - and a row of covers with no explanation is a row of covers
 * nobody trusts.
 */
@Composable
internal fun SectionHeader(
    title: String,
    subtitle: String?,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = GUTTER, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (actionLabel != null && onAction != null) {
            Text(
                actionLabel,
                style = MaterialTheme.typography.labelLarge,
                color = Accent,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onAction)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun FeedSectionView(
    section: FeedSection,
    stats: Map<Long, SongStatsEntity>,
    onPlay: (List<SongEntity>, Int) -> Unit,
    onOpenList: (DetailList) -> Unit,
    onMore: (SongEntity) -> Unit
) {
    when (section.kind) {
        SectionKind.QUICK_PICKS -> {
            SectionHeader(
                title = section.title,
                subtitle = section.subtitle,
                actionLabel = "נגן הכל",
                onAction = { onPlay(section.songs, 0) }
            )
            // Two shelves share this kind and each gets the shape that does
            // its own job, exactly as on the phone. Speed dial is the handful
            // you keep returning to, and a returning song is known by its
            // sleeve - so, tiles. Quick picks are the ranker's wider choice,
            // where the title and the artist carry the identification and the
            // whole ranked list should stay scrollable - so, columns of four.
            if (ShelfKind.of(section.id) == ShelfKind.SPEED_DIAL) {
                QuickPickTiles(
                    songs = section.songs.take(9),
                    onPlay = { song ->
                        onPlay(section.songs, section.songs.indexOf(song))
                    },
                    onMore = onMore
                )
            } else {
                // A gap between the columns, so each row's menu is not read
                // as belonging to the next column's song.
                LazyRow(
                    contentPadding = PaddingValues(horizontal = GUTTER),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(section.songs.chunked(4)) { column ->
                        Column(modifier = Modifier.width(360.dp)) {
                            for (song in column) {
                                QuickPickRow(
                                    song = song,
                                    liked = stats[song.id]?.liked ?: 0,
                                    onClick = {
                                        onPlay(section.songs, section.songs.indexOf(song))
                                    },
                                    onMore = { onMore(song) }
                                )
                            }
                        }
                    }
                }
            }
        }

        SectionKind.MIX_ROW -> {
            SectionHeader(section.title, section.subtitle)
            LazyRow(contentPadding = PaddingValues(horizontal = GUTTER)) {
                items(section.mixes, key = { it.id }) { mix ->
                    MixCard(
                        mix = mix,
                        onOpen = {
                            onOpenList(
                                DetailList(
                                    title = mix.title,
                                    subtitle = mix.subtitle,
                                    songs = mix.songs,
                                    gradientKey = mix.id
                                )
                            )
                        },
                        onPlay = { onPlay(mix.songs, 0) }
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
                    onOpenList(
                        DetailList(
                            title = section.title,
                            subtitle = section.subtitle,
                            songs = section.songs,
                            gradientKey = section.id
                        )
                    )
                }
            )
            LazyRow(contentPadding = PaddingValues(horizontal = GUTTER)) {
                items(section.songs, key = { it.id }) { song ->
                    SongCard(
                        song = song,
                        onClick = { onPlay(section.songs, section.songs.indexOf(song)) },
                        onMore = { onMore(song) }
                    )
                }
            }
        }
    }
}

/**
 * One track in a quick-pick column.
 *
 * The title and the artist carry the identification, so every row reads as
 * text and the whole ranked list stays scrollable rather than cut at nine. A
 * liked track carries a small dot - enough to spot at a glance, and quieter
 * than repeating the thumb here when it is already on every other row.
 */
@Composable
private fun QuickPickRow(
    song: SongEntity,
    liked: Int,
    onClick: () -> Unit,
    onMore: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Art(song = song, size = 52.dp, corner = 8.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                song.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${song.artistName.ifEmpty { "ללא אמן" }} · ${formatDuration(song.durationMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (liked == 1) {
            Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(Accent))
            Spacer(Modifier.width(6.dp))
        }
        IconButton(onClick = onMore, modifier = Modifier.size(34.dp)) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = localized("עוד"),
                tint = TextSecondary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/** The square card used inside horizontal shelves. */
@Composable
private fun SongCard(song: SongEntity, onClick: () -> Unit, onMore: () -> Unit) {
    Column(
        modifier = Modifier
            .width(CARD)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 4.dp)
    ) {
        Art(song = song, size = CARD - 8.dp, corner = 12.dp)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    song.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    song.artistName.ifEmpty { "ללא אמן" },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // Offset up and in, so the menu sits against the title rather than
            // pushing the card wider than the cover above it.
            IconButton(
                onClick = onMore,
                modifier = Modifier.size(28.dp).offset(y = (-2).dp)
            ) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = localized("עוד"),
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/**
 * A mix, shown as what is inside it over the colour its id picks.
 *
 * The collage identifies the mix; the strip under it names it. Laying the
 * title over the artwork looked good on one cover and became unreadable on
 * the next, and the kind of mix is the part a listener actually needs to read.
 */
@Composable
private fun MixCard(mix: Mix, onOpen: () -> Unit, onPlay: () -> Unit) {
    val (c1, c2) = gradientFor(mix.id)
    val covers = collageSongs(mix.songs)
    Column(
        modifier = Modifier
            .width(MIX_CARD)
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .clickable(onClick = onOpen)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(14.dp))
                .background(Brush.linearGradient(listOf(c1, c2)))
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.fillMaxWidth().weight(0.62f)) {
                    if (covers.size >= 4) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            for (row in 0 until 2) {
                                Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                                    for (column in 0 until 2) {
                                        val cover = rememberArtwork(covers[row * 2 + column])
                                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                            if (cover != null) {
                                                Image(
                                                    bitmap = cover,
                                                    contentDescription = null,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.38f)
                        // Graphite, not a dark wash over the mix's colour: over the
                        // gradient it came out a heavy purple or green block
                        // under every collage. The covers bring the colour.
                        .background(Surface2)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        mix.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${mix.songs.size} שירים",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.85f),
                        maxLines = 1
                    )
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp)
                    .size(38.dp)
                    .clip(CircleShape)
                    // In the accent, like the big play button: plainly the
                    // thing to press, not a shadow on the picture.
                    .background(Accent)
                    .clickable(onClick = onPlay),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = localized("נגן"), tint = Color.White)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            mix.subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** A mix card is wider than a song card, as it is on the phone. */
private val MIX_CARD: Dp = 176.dp

/**
 * The speed dial, as covers with the title written across them.
 *
 * These are the songs you keep returning to, and a returning song is known
 * by its sleeve long before its name is read. A window is wide, so the nine
 * sit in a row of three columns rather than the phone's stacked grid - same
 * tiles, same nine, laid out for the shape of the screen they are on.
 */
@Composable
private fun QuickPickTiles(
    songs: List<SongEntity>,
    onPlay: (SongEntity) -> Unit,
    onMore: (SongEntity) -> Unit
) {
    if (songs.isEmpty()) return
    LazyRow(
        contentPadding = PaddingValues(horizontal = GUTTER),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(songs.chunked(3)) { column ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (song in column) {
                    QuickPickTile(
                        song = song,
                        onPlay = { onPlay(song) },
                        onMore = { onMore(song) }
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickPickTile(
    song: SongEntity,
    onPlay: () -> Unit,
    onMore: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(CARD)
            .clip(RoundedCornerShape(10.dp))
            .pointerInput(song.id) {
                detectTapGestures(onTap = { onPlay() }, onLongPress = { onMore() })
            }
    ) {
        Art(song = song, size = CARD, corner = 0.dp)
        // Without this the title lands on whatever the sleeve happens to be
        // and is unreadable on about half of them. The scrim darkens only the
        // strip the words sit on, leaving the artwork above it alone.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(CARD * 0.45f)
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
