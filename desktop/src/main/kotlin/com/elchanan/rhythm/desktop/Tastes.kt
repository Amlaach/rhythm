package com.elchanan.rhythm.desktop

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.desktop.audio.AudioPlayer
import com.elchanan.rhythm.desktop.audio.Hooks
import com.elchanan.rhythm.ui.theme.Accent
import com.elchanan.rhythm.ui.theme.Bg
import com.elchanan.rhythm.ui.theme.Surface1
import com.elchanan.rhythm.ui.theme.Text
import com.elchanan.rhythm.ui.theme.TextPrimary
import com.elchanan.rhythm.ui.theme.TextSecondary
import com.elchanan.rhythm.ui.theme.gradientFor
import com.elchanan.rhythm.ui.theme.localized
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** How long a taste is, and how long it fades in. The phone's. */
private const val CLIP_MS = 25_000L
private const val FADE_MS = 900

/** How many choruses are found ahead of the one being tasted. */
internal const val TASTES_AHEAD = 10

/**
 * The phone's tastes: one song at a time, from its chorus, a scroll (or an
 * arrow key) to the next. In a player of their own, so a taste is never
 * counted as a play and the queue is left as it was; the main player is
 * paused on the way in and carries on on the way out, unless the whole song
 * was asked for.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TastesPane(
    songs: List<SongEntity>?,
    likedOf: (Long) -> Int,
    volume: Float,
    accuracy: Pair<Int, Int>,
    mainPlaying: () -> Boolean,
    onPauseMain: () -> Unit,
    onResumeMain: () -> Unit,
    onLike: (SongEntity) -> Unit,
    onDislike: (SongEntity) -> Unit,
    onQueue: (SongEntity) -> Unit,
    onPlayWhole: (SongEntity) -> Unit,
    onTasted: (SongEntity) -> Unit,
    onHeard: () -> Unit,
    onNotTheChorus: () -> Unit
) {
    val taste = remember { AudioPlayer() }
    var resumeOnExit by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        if (mainPlaying()) {
            onPauseMain()
            resumeOnExit = true
        }
        onDispose {
            taste.stop()
            if (resumeOnExit) onResumeMain()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Bg)) {
        when {
            songs == null -> Text(
                "מכין טעימות…",
                color = TextSecondary,
                modifier = Modifier.align(Alignment.Center)
            )
            songs.isEmpty() -> Column(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("אין מה לטעום", style = MaterialTheme.typography.titleMedium)
                Text(
                    "טעימות הן לשירים של עד שש דקות שאינם מחרוזות או שיעורים.",
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )
            }
            else -> {
                val pager = rememberPagerState { songs.size }
                val scope = rememberCoroutineScope()
                var playing by remember { mutableStateOf(true) }
                var finding by remember { mutableStateOf(false) }
                val progress = remember { mutableFloatStateOf(0f) }
                val fade = remember { Animatable(0f) }
                var fadeJob by remember { mutableStateOf<Job?>(null) }
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

                LaunchedEffect(volume) {
                    taste.setVolume(fade.value * volume)
                }

                // A taste counts as heard once it has had a few seconds, for
                // the chorus finder's hit rate - same as on the phone.
                LaunchedEffect(pager.settledPage) {
                    delay(5_000)
                    onHeard()
                }

                // The page that has settled is the one that plays: its chorus
                // found (or remembered), then 25 seconds from there, over and
                // over, fading in each time it starts.
                LaunchedEffect(pager.settledPage) {
                    val song = songs.getOrNull(pager.settledPage) ?: return@LaunchedEffect
                    taste.stop()
                    playing = true
                    progress.floatValue = 0f
                    finding = Hooks.cached(song.id) == null
                    val start = Hooks.find(song)
                    finding = false
                    Hooks.prefetch(songs.drop(pager.settledPage + 1).take(TASTES_AHEAD))
                    onTasted(song)
                    taste.setVolume(0f)
                    taste.play(File(song.path), song.durationMs, start)
                    fade.snapTo(0f)
                    fadeJob?.cancel()
                    fadeJob = launch { fade.animateTo(1f, tween(FADE_MS)) { taste.setVolume(value * volume) } }
                    while (true) {
                        delay(200)
                        val state = taste.state.value
                        val into = (state.positionMs - start).coerceAtLeast(0L)
                        progress.floatValue = (into.toFloat() / CLIP_MS).coerceIn(0f, 1f)
                        // Round again from the chorus when the taste is over,
                        // or when the file ran out before it was - then the
                        // player has let the file go and is started again.
                        val ended = !state.playing && playing && state.positionMs >= song.durationMs - 1_000
                        if (into >= CLIP_MS || ended) {
                            taste.setVolume(0f)
                            progress.floatValue = 0f
                            if (ended) {
                                taste.play(File(song.path), song.durationMs, start)
                            } else {
                                taste.seekTo(start)
                            }
                            fade.snapTo(0f)
                            fadeJob?.cancel()
                            fadeJob = launch { fade.animateTo(1f, tween(FADE_MS)) { taste.setVolume(value * volume) } }
                        }
                    }
                }

                fun toggle() {
                    playing = !playing
                    if (playing) taste.resume() else taste.pause()
                }

                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRequester(focus)
                        .focusable()
                        .onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (e.key) {
                                Key.DirectionDown, Key.PageDown -> {
                                    scope.launch { pager.animateScrollToPage((pager.currentPage + 1).coerceAtMost(songs.lastIndex)) }
                                    true
                                }
                                Key.DirectionUp, Key.PageUp -> {
                                    scope.launch { pager.animateScrollToPage((pager.currentPage - 1).coerceAtLeast(0)) }
                                    true
                                }
                                Key.Spacebar -> { toggle(); true }
                                else -> false
                            }
                        }
                ) {
                    VerticalPager(state = pager, modifier = Modifier.weight(1f).fillMaxSize(), key = { songs[it].id }) { page ->
                        val song = songs[page]
                        TastePage(
                            song = song,
                            liked = likedOf(song.id),
                            current = page == pager.settledPage,
                            playing = playing,
                            finding = finding && page == pager.settledPage,
                            progress = if (page == pager.settledPage) progress.floatValue else 0f,
                            onToggle = ::toggle,
                            onLike = { onLike(song) },
                            onDislike = {
                                onDislike(song)
                                scope.launch { pager.animateScrollToPage((page + 1).coerceAtMost(songs.lastIndex)) }
                            },
                            onQueue = { onQueue(song) },
                            onPlayWhole = {
                                resumeOnExit = false
                                taste.stop()
                                onPlayWhole(song)
                            },
                            onNotTheChorus = onNotTheChorus
                        )
                    }
                    // A mouse has no swipe: up and down, beside the pages.
                    Column(
                        modifier = Modifier.padding(horizontal = 8.dp).align(Alignment.CenterVertically),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(onClick = { scope.launch { pager.animateScrollToPage((pager.currentPage - 1).coerceAtLeast(0)) } }) {
                            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = localized("הקודם"), tint = TextSecondary)
                        }
                        IconButton(onClick = { scope.launch { pager.animateScrollToPage((pager.currentPage + 1).coerceAtMost(songs.lastIndex)) } }) {
                            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = localized("הבא"), tint = TextSecondary)
                        }
                    }
                }
            }
        }

        // Over the pages: the name of the tab, and how well the chorus is found.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("טעימות", style = MaterialTheme.typography.titleLarge, color = TextPrimary, modifier = Modifier.weight(1f))
            val (heard, missed) = accuracy
            if (heard >= 10) {
                Text(
                    "פזמון: ${((heard - missed).coerceAtLeast(0) * 100 / heard)}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
            }
        }
    }
}

/** One song, in the shape the window has: the cover beside the rest when wide, above it when not. */
@Composable
private fun TastePage(
    song: SongEntity,
    liked: Int,
    current: Boolean,
    playing: Boolean,
    finding: Boolean,
    progress: Float,
    onToggle: () -> Unit,
    onLike: () -> Unit,
    onDislike: () -> Unit,
    onQueue: () -> Unit,
    onPlayWhole: () -> Unit,
    onNotTheChorus: () -> Unit
) {
    var marked by remember(song.id) { mutableStateOf(false) }
    val (c1, c2) = gradientFor(song.artistKey)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(c1.copy(alpha = 0.55f), c2.copy(alpha = 0.35f), Bg)))
    ) {
        val top = 60.dp
        val height = maxHeight - top - 12.dp
        val wide = maxWidth >= 640.dp && maxWidth > height * 1.2f

        val cover: @Composable (Dp) -> Unit = { side ->
            Box(contentAlignment = Alignment.Center) {
                Art(
                    song = song,
                    size = side,
                    corner = 18.dp,
                    fit = true,
                    modifier = Modifier.clickable(onClick = onToggle)
                )
                if (current && !playing) {
                    Box(
                        modifier = Modifier.size(72.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(44.dp))
                    }
                }
            }
        }
        val details: @Composable (Alignment.Horizontal) -> Unit = { align ->
            val textAlign = if (align == Alignment.CenterHorizontally) TextAlign.Center else TextAlign.Start
            Column(horizontalAlignment = align) {
                Text(song.title, style = MaterialTheme.typography.headlineSmall, color = TextPrimary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = textAlign, modifier = Modifier.fillMaxWidth())
                Text(song.artistName, style = MaterialTheme.typography.titleMedium, color = TextSecondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = textAlign, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    color = Accent,
                    trackColor = Surface1,
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape)
                )
                Text(if (finding) "מחפש את הפזמון…" else " ", style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary, textAlign = textAlign, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoundAction(if (liked == 1) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp, "לייק", liked == 1, onLike)
                    RoundAction(if (liked == -1) Icons.Filled.ThumbDown else Icons.Outlined.ThumbDown, "דיסלייק", liked == -1, onDislike)
                    RoundAction(Icons.AutoMirrored.Filled.PlaylistAdd, "הוסף לתור", false, onQueue)
                    Box(
                        modifier = Modifier.height(48.dp).clip(CircleShape).background(Accent)
                            .clickable(onClick = onPlayWhole).padding(horizontal = 18.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("השיר המלא", style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 1)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                TextButton(enabled = !marked, onClick = { marked = true; onNotTheChorus() }) {
                    Text(if (marked) "נרשם, תודה" else "זה לא הפזמון", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                }
            }
        }

        if (wide) {
            val side = minOf(height * 0.9f, maxWidth * 0.42f)
            Row(
                modifier = Modifier.fillMaxSize().padding(top = top, bottom = 12.dp, start = 24.dp, end = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                cover(side)
                Spacer(Modifier.width(28.dp))
                Box(modifier = Modifier.widthIn(max = 440.dp)) { details(Alignment.Start) }
            }
        } else {
            val side = minOf(maxWidth * 0.82f, height - 250.dp).coerceAtLeast(120.dp)
            Column(
                modifier = Modifier.fillMaxSize().padding(top = top, bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                cover(side)
                Spacer(Modifier.height(18.dp))
                Box(modifier = Modifier.widthIn(max = maxOf(side + 40.dp, 320.dp)).padding(horizontal = 16.dp)) {
                    details(Alignment.CenterHorizontally)
                }
            }
        }
    }
}

@Composable
private fun RoundAction(icon: ImageVector, description: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(if (active) Accent.copy(alpha = 0.22f) else Surface1)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = localized(description), tint = if (active) Accent else TextPrimary)
    }
}
