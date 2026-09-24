package com.elchanan.rhythm.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.playback.HookFinder
import com.elchanan.rhythm.playback.MediaItems
import com.elchanan.rhythm.ui.MainViewModel
import com.elchanan.rhythm.ui.TASTES_AHEAD
import com.elchanan.rhythm.ui.components.Artwork
import com.elchanan.rhythm.ui.components.EmptyState
import com.elchanan.rhythm.ui.components.rememberArtworkColors
import com.elchanan.rhythm.ui.theme.Accent
import com.elchanan.rhythm.ui.theme.Bg
import com.elchanan.rhythm.ui.theme.Surface1
import com.elchanan.rhythm.ui.theme.Text
import com.elchanan.rhythm.ui.theme.TextPrimary
import com.elchanan.rhythm.ui.theme.TextSecondary
import com.elchanan.rhythm.ui.theme.localized
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Tastes: the library a chorus at a time.
 *
 * One song fills the screen - its cover, its name - and twenty seconds of it
 * play from its chorus. Swipe up for the next. Like it and the engine learns
 * it; play it and it starts from the top in the real player. For finding what
 * is in a big library, so the songs never played come first.
 *
 * Its own player, not the app's: a taste is not a play, and counting twenty
 * seconds as one would teach the engine that every song here was skipped. And
 * swiping on is not a dislike - only the thumb down says that. Whatever was
 * playing is paused on the way in and carries on on the way out.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SamplesScreen(vm: MainViewModel, onLeave: () -> Unit) {
    val context = LocalContext.current
    val songs by vm.samples.collectAsStateWithLifecycle()
    val library by vm.library.collectAsStateWithLifecycle()
    val accuracy by vm.hookAccuracy.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.buildSamples() }

    // The taste player, and the app's player stepped aside while it plays.
    val exo = remember {
        ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                false
            )
            repeatMode = Player.REPEAT_MODE_ONE
        }
    }
    var resumeOnExit by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        resumeOnExit = vm.player.isPlaying
        vm.player.pause()
        onDispose {
            exo.release()
            if (resumeOnExit) vm.player.resume()
        }
    }

    val list = songs
    val topPad = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(modifier = Modifier.fillMaxSize().background(Bg)) {
        when {
            list == null -> Text(
                "מכין טעימות…",
                color = TextSecondary,
                modifier = Modifier.align(Alignment.Center)
            )
            list.isEmpty() -> Box(Modifier.align(Alignment.Center)) {
                EmptyState(
                    title = "אין מה לטעום",
                    body = "טעימות הן לשירים של עד שש דקות שאינם מחרוזות או שיעורים."
                )
            }
            else -> {
                val pager = rememberPagerState(pageCount = { list.size })
                var playing by remember { mutableStateOf(true) }
                var finding by remember { mutableStateOf(false) }
                val progress = remember { mutableFloatStateOf(0f) }
                val fade = remember { Animatable(0f) }
                val scope = rememberCoroutineScope()

                // The page that has settled is the one that plays. Its chorus
                // is found first if it has not been yet, and the next two are
                // looked for meanwhile, so swiping on does not wait.
                LaunchedEffect(pager.settledPage, list) {
                    val song = list[pager.settledPage]
                    exo.stop()
                    progress.floatValue = 0f
                    finding = HookFinder.cached(context, song.id) == null
                    val start = HookFinder.find(context, song)
                    finding = false
                    // The next ten, one at a time on a background thread, so
                    // swiping on lands straight on a chorus.
                    HookFinder.prefetch(
                        context,
                        list.subList(minOf(pager.settledPage + 1, list.size), minOf(pager.settledPage + 1 + TASTES_AHEAD, list.size))
                    )
                    vm.noteTasted(song.id)
                    exo.setMediaItem(
                        MediaItem.Builder()
                            .setUri(MediaItems.songUri(song.id))
                            .setClippingConfiguration(
                                MediaItem.ClippingConfiguration.Builder()
                                    .setStartPositionMs(start)
                                    .setEndPositionMs(minOf(song.durationMs, start + TASTE_MS))
                                    .build()
                            )
                            .build()
                    )
                    exo.prepare()
                    exo.volume = 0f
                    playing = true
                    exo.play()
                    // In softly: a taste that starts at full volume mid-phrase
                    // is a jolt, and a second of fade is enough to avoid it.
                    fade.snapTo(0f)
                    launch { fade.animateTo(1f, tween(900)) { exo.volume = value } }
                    // Where the taste is, for the bar along the bottom.
                    while (true) {
                        delay(200)
                        val length = exo.duration.takeIf { it > 0 } ?: TASTE_MS
                        progress.floatValue = (exo.currentPosition.toFloat() / length).coerceIn(0f, 1f)
                    }
                }
                // A taste counts as heard once it has had a few seconds, for
                // the chorus finder's hit rate.
                LaunchedEffect(pager.settledPage) {
                    delay(5_000)
                    vm.noteTasteHeard()
                }

                VerticalPager(state = pager, modifier = Modifier.fillMaxSize(), key = { list[it].id }) { page ->
                    val song = list[page]
                    TastePage(
                        song = song,
                        liked = library.stats[song.id]?.liked ?: 0,
                        current = page == pager.settledPage,
                        playing = playing,
                        finding = finding && page == pager.settledPage,
                        progress = if (page == pager.settledPage) progress.floatValue else 0f,
                        topPad = topPad,
                        onToggle = {
                            playing = !playing
                            if (playing) exo.play() else exo.pause()
                        },
                        onLike = { vm.like(song.id) },
                        onDislike = {
                            vm.dislike(song.id)
                            // Disliked: on to the next.
                            if (page + 1 < list.size) scope.launch { pager.animateScrollToPage(page + 1) }
                        },
                        onQueue = { vm.addToQueue(song) },
                        onPlayWhole = {
                            resumeOnExit = false
                            vm.playList(listOf(song))
                            onLeave()
                        },
                        onNotTheChorus = { vm.noteNotTheChorus() }
                    )
                }
            }
        }

        // Over the pages: the name of the tab, and how well the chorus is
        // being found. A tab needs no way back; the tabs are right there.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = topPad)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("טעימות", style = MaterialTheme.typography.titleLarge, color = Color.White, modifier = Modifier.weight(1f))
            val (heard, missed) = accuracy
            if (heard >= 10) {
                Text(
                    "פזמון: ${((heard - missed).coerceAtLeast(0) * 100 / heard)}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.padding(end = 10.dp)
                )
            }
        }
    }
}

/**
 * One song, filling the screen, in the shape the screen has: stacked on a
 * phone held upright, the cover beside the rest on a wide, short one - a
 * tablet, a phone on its side - where stacked, the buttons ran off the
 * bottom.
 */
@Composable
private fun TastePage(
    song: SongEntity,
    liked: Int,
    current: Boolean,
    playing: Boolean,
    finding: Boolean,
    progress: Float,
    topPad: androidx.compose.ui.unit.Dp,
    onToggle: () -> Unit,
    onLike: () -> Unit,
    onDislike: () -> Unit,
    onQueue: () -> Unit,
    onPlayWhole: () -> Unit,
    onNotTheChorus: () -> Unit
) {
    val (c1, c2) = rememberArtworkColors(song.id, song.albumId, song.artistKey).value
    var marked by remember(song.id) { mutableStateOf(false) }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(c1.copy(alpha = 0.55f), c2.copy(alpha = 0.35f), Bg)))
    ) {
        val top = topPad + 56.dp
        val height = maxHeight - top - 12.dp
        val wide = maxWidth >= 560.dp && maxWidth > height * 1.2f
        val narrow = maxWidth < 360.dp

        val cover: @Composable (androidx.compose.ui.unit.Dp) -> Unit = { side ->
            Box(contentAlignment = Alignment.Center) {
                Artwork(
                    songId = song.id,
                    albumId = song.albumId,
                    seed = song.artistKey,
                    contentScale = ContentScale.Fit,
                    corner = 18,
                    modifier = Modifier
                        .size(side)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onToggle
                        )
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
            Column(horizontalAlignment = align) {
                val textAlign = if (align == Alignment.CenterHorizontally) TextAlign.Center else TextAlign.Start
                Text(
                    song.title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = textAlign,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    song.artistName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = textAlign,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    color = Accent,
                    trackColor = Surface1,
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape)
                )
                Text(
                    if (finding) "מחפש את הפזמון…" else " ",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    textAlign = textAlign,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
                Spacer(Modifier.height(10.dp))
                val button = if (narrow) 44.dp else 48.dp
                Row(
                    horizontalArrangement = Arrangement.spacedBy(if (narrow) 8.dp else 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RoundAction(if (liked == 1) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp, "לייק", liked == 1, button, onLike)
                    RoundAction(if (liked == -1) Icons.Filled.ThumbDown else Icons.Outlined.ThumbDown, "דיסלייק", liked == -1, button, onDislike)
                    RoundAction(Icons.AutoMirrored.Filled.PlaylistAdd, "הוסף לתור", false, button, onQueue)
                    // The one that leaves: the whole song, from the top, in
                    // the app's own player.
                    Box(
                        modifier = Modifier
                            .height(button)
                            .clip(CircleShape)
                            .background(Accent)
                            .clickable(onClick = onPlayWhole)
                            .padding(horizontal = if (narrow) 12.dp else 18.dp),
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
                // Tells the chorus finder how it is doing; nothing else changes.
                TextButton(enabled = !marked, onClick = { marked = true; onNotTheChorus() }) {
                    Text(
                        if (marked) "נרשם, תודה" else "זה לא הפזמון",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )
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
            // The cover as large as the rest leaves room for: the width on a
            // phone held upright, less on a short one.
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
private fun RoundAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    active: Boolean,
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (active) Accent.copy(alpha = 0.22f) else Surface1)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = localized(description), tint = if (active) Accent else TextPrimary, modifier = Modifier.size(22.dp))
    }
}

/** How long a taste plays before it goes round again. */
private const val TASTE_MS = 25_000L
