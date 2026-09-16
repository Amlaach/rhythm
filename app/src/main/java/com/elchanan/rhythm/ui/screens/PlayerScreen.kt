package com.elchanan.rhythm.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import kotlin.math.abs
import com.elchanan.rhythm.playback.QueueMeta
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.ui.MainViewModel
import com.elchanan.rhythm.ui.components.Artwork
import com.elchanan.rhythm.ui.components.LikeButtons
import com.elchanan.rhythm.ui.components.rememberArtworkColors
import com.elchanan.rhythm.ui.components.rememberMetrics
import com.elchanan.rhythm.ui.components.StarRow
import com.elchanan.rhythm.ui.components.formatDuration
import com.elchanan.rhythm.engine.AudioAnalyzer
import com.elchanan.rhythm.playback.SleepTimer
import com.elchanan.rhythm.ui.theme.Accent
import com.elchanan.rhythm.ui.theme.Bg
import com.elchanan.rhythm.ui.theme.Surface1
import com.elchanan.rhythm.ui.theme.TextPrimary
import com.elchanan.rhythm.ui.theme.TextSecondary


@Composable
fun MiniPlayer(
    song: SongEntity,
    isPlaying: Boolean,
    progress: Float,
    liked: Int,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onLike: () -> Unit,
    onExpand: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(song.id) {
                    var drag = 0f
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            // right to left in an RTL layout still means "next"
                            if (drag < -60f) onNext() else if (drag > 60f) onPrevious()
                            drag = 0f
                        }
                    ) { _, amount -> drag += amount }
                }
                .clickable(onClick = onExpand)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Artwork(song.id, song.albumId, song.artistKey, Modifier.size(42.dp), corner = 7)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    song.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    song.artistName,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onLike) {
                Icon(
                    imageVector = if (liked == 1) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                    contentDescription = "לייק",
                    tint = if (liked == 1) Accent else TextSecondary
                )
            }
            IconButton(onClick = onToggle) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = "נגן",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            IconButton(onClick = onNext) {
                Icon(Icons.Filled.SkipNext, contentDescription = "הבא", tint = MaterialTheme.colorScheme.onBackground)
            }
        }
        // Matches the full player: elapsed time grows rightwards regardless of the
        // language, so the two views never disagree about which way the song runs.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = Accent,
                trackColor = Surface1
            )
        }
    }
}

@Composable
fun PlayerScreen(vm: MainViewModel, onCollapse: () -> Unit) {
    val state by vm.player.state.collectAsStateWithLifecycle()
    val library by vm.library.collectAsStateWithLifecycle()
    val features by vm.featuresById.collectAsStateWithLifecycle()
    var showQueue by remember { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    var showLyrics by remember { mutableStateOf(false) }
    var sleepOpen by remember { mutableStateOf(false) }
    var whyOpen by remember { mutableStateOf(false) }

    val song = state.currentSongId?.let { library.songsById[it] } ?: return
    val metrics = rememberMetrics()
    val artColors by rememberArtworkColors(song.id, song.albumId, song.artistKey)
    val songStats = library.stats[song.id]
    val liked = songStats?.liked ?: 0
    val rating = songStats?.rating ?: 0
    val feature = features[song.id]
    val (c1, c2) = artColors

    val topPad = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottomPad = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // Swipe the sheet down to put the player away, the way YouTube Music does.
    // The offset follows the finger while dragging and either carries on past the
    // threshold into a dismiss, or springs back.
    val scope = rememberCoroutineScope()
    val dragY = remember { Animatable(0f) }
    val dismissPx = with(LocalDensity.current) { 120.dp.toPx() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .offset { IntOffset(0, dragY.value.roundToInt()) }
            // The player floats above the browsing UI. Without something to catch
            // them, taps on its empty areas reach the list underneath and play a
            // different song.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { }
            // Solid ground first: the cover tint on top is deliberately translucent,
            // and while the artwork filled the window that never showed. Now that it
            // does not, the browsing UI would read straight through the sheet.
            .background(Bg)
            .background(
                Brush.verticalGradient(
                    listOf(c1.copy(alpha = 0.55f), c2.copy(alpha = 0.25f), Bg, Bg)
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topPad, bottom = bottomPad)
        ) {
            // The drag lives on the header alone rather than the whole sheet, so it
            // can never fight the scrubber, the queue list or the lyrics scroller.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                scope.launch {
                                    if (dragY.value > dismissPx) {
                                        onCollapse()
                                        dragY.snapTo(0f)
                                    } else {
                                        dragY.animateTo(0f)
                                    }
                                }
                            },
                            onDragCancel = { scope.launch { dragY.animateTo(0f) } }
                        ) { change, amount ->
                            change.consume()
                            scope.launch {
                                dragY.snapTo((dragY.value + amount).coerceAtLeast(0f))
                            }
                        }
                    }
            ) {
                // The grab handle: the affordance that says this panel pulls down.
                Box(
                    modifier = Modifier
                        .padding(top = 8.dp, bottom = 4.dp)
                        .align(Alignment.CenterHorizontally)
                        .size(width = 38.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(TextSecondary.copy(alpha = 0.5f))
                )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onCollapse) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "סגור")
                }
                // No "now playing" caption: the cover, the title and the
                // transport directly below already say it.
                Spacer(Modifier.weight(1f))
                IconButton(onClick = {
                    showLyrics = !showLyrics
                    if (showLyrics) showQueue = false
                }) {
                    Icon(
                        Icons.Filled.FormatQuote,
                        contentDescription = "מילות השיר",
                        tint = if (showLyrics) Accent else TextSecondary
                    )
                }
                IconButton(onClick = { whyOpen = true }) {
                    Icon(Icons.Filled.Insights, contentDescription = "למה זה הומלץ", tint = TextSecondary)
                }
                IconButton(onClick = { sleepOpen = true }) {
                    Icon(
                        Icons.Filled.Bedtime,
                        contentDescription = "טיימר שינה",
                        tint = if (SleepTimer.remainingMs() != null) Accent else TextSecondary
                    )
                }
                IconButton(onClick = {
                    showQueue = !showQueue
                    if (showQueue) showLyrics = false
                }) {
                    Icon(
                        Icons.Filled.QueueMusic,
                        contentDescription = "תור",
                        tint = if (showQueue) Accent else TextSecondary
                    )
                }
            }
            }

            if (showQueue) {
                QueueList(vm = vm, modifier = Modifier.weight(1f))
            } else if (showLyrics) {
                LyricsView(
                    vm = vm,
                    song = song,
                    positionMs = state.positionMs,
                    modifier = Modifier.weight(1f)
                )
            } else {
                Column(
                    modifier = Modifier
                        // Without this the column wraps the cover, and a wrapped
                        // column parks at the parent's start edge - the right, in RTL -
                        // so centring it never takes effect.
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 28.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Artwork(
                        songId = song.id,
                        albumId = song.albumId,
                        seed = song.artistKey,
                        // An explicit square. `fillMaxWidth().aspectRatio(1f)` asks
                        // for a box as tall as the window is wide, which in landscape
                        // is far taller than the space it was given - and since a
                        // Column does not clip, it simply drew over the header and
                        // swallowed the close button.
                        modifier = Modifier
                            .size(metrics.artworkMax)
                            .pointerInput(song.id) {
                                var drag = 0f
                                detectHorizontalDragGestures(
                                    onDragEnd = {
                                        // RTL: dragging right goes forward.
                                        if (drag > 70f) vm.player.next()
                                        else if (drag < -70f) vm.player.previous()
                                        drag = 0f
                                    }
                                ) { _, amount -> drag += amount }
                            },
                        corner = 20
                    )
                }
            }

            Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                // With the queue open everything except the transport steps
                // aside, so the list gets nearly the whole sheet. The controls
                // stay: browsing a queue and pausing go together.
                if (!showQueue) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Placed first so RTL puts it on the right, alongside the stars
                    // rather than stranded on the opposite edge from them.
                    LikeButtons(
                        liked = liked,
                        onLike = { vm.like(song.id) },
                        onDislike = { vm.dislike(song.id) }
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            song.title,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            song.artistName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                            maxLines = 1
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Without this the stars sit directly under the artist line and
                    // read as a rating of the artist, which is a different thing the
                    // app also offers.
                    Text(
                        text = "דירוג השיר",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )
                    Spacer(Modifier.width(8.dp))
                    StarRow(rating = rating, onRate = { vm.rateSong(song.id, it) }, size = 20)
                    Spacer(Modifier.width(10.dp))
                    if (feature != null) {
                        Text(
                            // Key only. The modal estimate drives the engine but
                            // reads as jargon on screen - "אהבה רבה" beside a pop
                            // track means nothing to someone just playing music.
                            text = "${feature.bpm.toInt()} BPM · " +
                                AudioAnalyzer.keyLabel(feature.musicalKey, feature.mode),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))

                }

                val duration = if (state.durationMs > 0) state.durationMs else song.durationMs
                val position = scrubbing?.times(duration)?.toLong() ?: state.positionMs
                // Time runs one way whatever the language, so the scrubber and the
                // transport keep the left-to-right reading every media player uses:
                // the head advances rightwards, elapsed sits under its start, and
                // "previous" stays on the left of "next".
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                if (!showQueue) {
                Slider(
                    value = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                    onValueChange = { scrubbing = it },
                    onValueChangeFinished = {
                        scrubbing?.let { vm.player.seekTo((it * duration).toLong()) }
                        scrubbing = null
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = Accent,
                        activeTrackColor = Accent,
                        inactiveTrackColor = Surface1
                    )
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        formatDuration(position),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        formatDuration(duration),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
                }

                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { vm.player.toggleShuffle() }) {
                        Icon(
                            Icons.Filled.Shuffle,
                            contentDescription = "ערבוב",
                            tint = if (state.shuffle) Accent else TextSecondary
                        )
                    }
                    IconButton(onClick = { vm.player.previous() }) {
                        Icon(
                            Icons.Filled.SkipPrevious,
                            contentDescription = "הקודם",
                            // Without an explicit tint these two inherit a colour that
                            // is nearly the background, so they read as missing.
                            tint = TextPrimary,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(66.dp)
                            .clip(CircleShape)
                            .background(Accent)
                            .clickable { vm.player.togglePlayPause() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = "נגן",
                            tint = Color.White,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                    IconButton(onClick = { vm.player.next() }) {
                        Icon(
                            Icons.Filled.SkipNext,
                            contentDescription = "הבא",
                            tint = TextPrimary,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                    IconButton(onClick = { vm.player.cycleRepeat() }) {
                        Icon(
                            if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne
                            else Icons.Filled.Repeat,
                            contentDescription = "חזרה",
                            tint = if (state.repeatMode == Player.REPEAT_MODE_OFF) TextSecondary else Accent
                        )
                    }
                }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (sleepOpen) SleepDialog(vm = vm, onDismiss = { sleepOpen = false })
    if (whyOpen) WhyDialog(vm = vm, song = song, onDismiss = { whyOpen = false })
}

@Composable
private fun QueueHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = TextSecondary,
        modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp)
    )
}

@Composable
private fun SleepDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val armed = SleepTimer.remainingMs()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        title = { Text("טיימר שינה") },
        text = {
            Column {
                if (armed != null) {
                    Text(
                        "נשארו ${(armed / 60000).toInt()} דקות",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Accent
                    )
                    Spacer(Modifier.height(8.dp))
                }
                listOf(15, 30, 45, 60, 90).forEach { minutes ->
                    Text(
                        text = "בעוד $minutes דקות",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { vm.sleepIn(minutes); onDismiss() }
                            .padding(vertical = 10.dp)
                    )
                }
                Text(
                    text = "בסוף השיר הנוכחי",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { vm.sleepAfterTrack(); onDismiss() }
                        .padding(vertical = 10.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.cancelSleep(); onDismiss() }) { Text("בטל טיימר", color = Accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("סגור", color = TextSecondary) }
        }
    )
}

@Composable
private fun QueueList(vm: MainViewModel, modifier: Modifier = Modifier) {
    val state by vm.player.state.collectAsStateWithLifecycle()
    val library by vm.library.collectAsStateWithLifecycle()
    val autoIds by vm.autoAddedIds.collectAsStateWithLifecycle()
    val songs = state.queueIds.mapNotNull { library.songsById[it] }

    // the first entry after the current one that the radio appended by itself
    val autoStart = remember(songs, autoIds, state.queueIndex) {
        val from = (state.queueIndex + 1).coerceAtMost(songs.size)
        (from until songs.size).firstOrNull { songs[it].id in autoIds } ?: -1
    }

    val source by QueueMeta.source.collectAsStateWithLifecycle()
    var sheetSong by remember { mutableStateOf<SongEntity?>(null) }
    val scope = rememberCoroutineScope()

    // Open on what is playing rather than at the top. In a sixty track queue the
    // start of the list is the least useful place to land.
    val listState = rememberLazyListState()
    LaunchedEffect(state.queueIndex, songs.size) {
        if (state.queueIndex in songs.indices) {
            // +1 for the header row that sits above the entries
            listState.scrollToItem((state.queueIndex + 1).coerceAtMost(songs.size))
        }
    }

    // Where a drag currently sits, as a queue index, while the finger is down.
    var dragFrom by remember { mutableStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val rowHeight = with(LocalDensity.current) { 54.dp.toPx() }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxHeight(),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        item {
            Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp)) {
                Text(
                    text = "התור",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = source?.let { "מתוך $it" } ?: "${songs.size} שירים",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }

        items(songs.size) { index ->
            if (index == state.queueIndex + 1 && index != autoStart) {
                QueueHeader("הבא בתור")
            }
            if (index == autoStart) {
                QueueHeader("המשך אוטומטי")
            }
            val song = songs[index]

            // Swipe aside to drop an entry. Written by hand rather than with the
            // Material box: the version in this toolchain is still experimental
            // and its names move between releases, and this is a handful of
            // lines against a stable API.
            val swipe = remember(song.id) { Animatable(0f) }
            val swipeLimit = with(LocalDensity.current) { 110.dp.toPx() }

            val dragging = dragFrom == index

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // The row follows the finger while it is being reordered.
                    // Without this the list looked frozen until the drop, and the
                    // track then jumped somewhere with no sense of cause.
                    .offset { IntOffset(0, if (dragging) dragOffset.roundToInt() else 0) }
                    .zIndex(if (dragging) 1f else 0f)
                    .background(if (dragging) Surface1 else Color.Transparent)
            ) {
                Box(
                    modifier = Modifier
                        .offset { IntOffset(swipe.value.roundToInt(), 0) }
                        .pointerInput(song.id, index) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    scope.launch {
                                        if (abs(swipe.value) > swipeLimit) {
                                            vm.player.removeAt(index)
                                            swipe.snapTo(0f)
                                        } else {
                                            swipe.animateTo(0f)
                                        }
                                    }
                                },
                                onDragCancel = { scope.launch { swipe.animateTo(0f) } }
                            ) { change, amount ->
                                change.consume()
                                scope.launch { swipe.snapTo(swipe.value + amount) }
                            }
                        }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Bg)
                            .clickable { vm.player.jumpTo(index) }
                            .padding(horizontal = 20.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // The handle, not the whole row: dragging anywhere would
                        // fight the list's own scrolling.
                        Icon(
                            imageVector = Icons.Filled.DragHandle,
                            contentDescription = "גרור לסידור",
                            tint = TextSecondary,
                            modifier = Modifier
                                .size(24.dp)
                                .pointerInput(index, songs.size) {
                                    detectDragGestures(
                                        onDragStart = {
                                            dragFrom = index
                                            dragOffset = 0f
                                        },
                                        onDragEnd = {
                                            val steps = (dragOffset / rowHeight).roundToInt()
                                            val target = (dragFrom + steps)
                                                .coerceIn(0, songs.lastIndex)
                                            if (dragFrom >= 0 && target != dragFrom) {
                                                vm.player.moveItem(dragFrom, target)
                                            }
                                            dragFrom = -1
                                            dragOffset = 0f
                                        },
                                        onDragCancel = {
                                            dragFrom = -1
                                            dragOffset = 0f
                                        }
                                    ) { change, amount ->
                                        change.consume()
                                        dragOffset += amount.y
                                    }
                                }
                        )
                        Spacer(Modifier.width(10.dp))
                        Artwork(song.id, song.albumId, song.artistKey, Modifier.size(40.dp), corner = 6)
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                song.title,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (index == state.queueIndex) Accent
                                else MaterialTheme.colorScheme.onBackground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                song.artistName,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                maxLines = 1
                            )
                        }
                        IconButton(onClick = { sheetSong = song }) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = "אפשרויות",
                                tint = TextSecondary
                            )
                        }
                    }
                }

                // The delete band grows from whichever edge the row is heading
                // for, so the gesture points at its own consequence instead of
                // revealing a colour behind the track it just left.
                if (swipe.value != 0f) {
                    val travel = abs(swipe.value)
                    val width = with(LocalDensity.current) { travel.toDp() }
                    val armed = travel > swipeLimit
                    Row(
                        modifier = Modifier
                            .align(
                                if (swipe.value < 0f) Alignment.CenterStart
                                else Alignment.CenterEnd
                            )
                            .fillMaxHeight()
                            .width(width)
                            .background(
                                Accent.copy(alpha = if (armed) 0.85f else 0.35f)
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        if (width > 34.dp) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = "הסר מהתור",
                                tint = Color.White
                            )
                        }
                    }
                }
            }
        }
    }

    sheetSong?.let { song ->
        SongOptionsSheet(
            vm = vm,
            song = song,
            onDismiss = { sheetSong = null }
        )
    }
}
