package com.elchanan.rhythm.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import com.elchanan.rhythm.ui.theme.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.ui.theme.Accent
import com.elchanan.rhythm.ui.theme.Accent2
import com.elchanan.rhythm.ui.theme.Bg
import com.elchanan.rhythm.ui.theme.RhythmMark
import com.elchanan.rhythm.ui.theme.Surface1
import com.elchanan.rhythm.ui.theme.Surface2
import com.elchanan.rhythm.ui.theme.Surface3
import com.elchanan.rhythm.ui.theme.TextSecondary
import com.elchanan.rhythm.ui.theme.TextTertiary
import com.elchanan.rhythm.ui.theme.gradientFor
import java.util.concurrent.TimeUnit

fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val minutes = TimeUnit.MILLISECONDS.toMinutes(ms)
    val seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
    return "%d:%02d".format(minutes, seconds)
}

/**
 * Album art with a deterministic colour gradient behind it, so a library with
 * no embedded artwork still looks alive instead of grey.
 */
@Composable
fun Artwork(
    songId: Long,
    albumId: Long,
    seed: String,
    modifier: Modifier = Modifier,
    corner: Int = 10,
    /**
     * How the cover fills its box.
     *
     * Crop for the small tiles, where the sleeve is an identifier and a
     * cropped one is still recognisable at forty pixels. Fit for the big
     * cover on the player, where the sleeve is the thing being looked at
     * and cropping it cuts the artwork someone chose this album for - a
     * wide live shot loses its sides, a tall poster loses its title.
     */
    contentScale: ContentScale = ContentScale.Crop
) {
    val (c1, c2) = gradientFor(seed)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(corner.dp))
            .background(Brush.linearGradient(listOf(c1, c2)))
    ) {
        // What a song with no cover of its own falls back to: the app's own
        // mark, over the tint its artist always gets. A generic note icon said
        // nothing; this at least makes an untagged library look like it belongs
        // to something. The real cover, when there is one, paints straight over
        // it.
        RhythmMark(
            modifier = Modifier.align(Alignment.Center),
            size = 26.dp
        )
        // What fills the corner a fitted cover cannot reach.
        //
        // Fitting a 16:9 cover into a square leaves two bands, and a band of
        // flat colour behind an album sleeve reads as a frame around it -
        // which is not what a cover is supposed to look like. So the bands are
        // filled with the cover itself, scaled up past the edges and blurred,
        // and the surround becomes the album's own colours rather than a
        // border drawn around it.
        //
        // Only when fitting. A cropped cover already reaches every edge, and
        // drawing it twice for corners that do not exist is work for nothing.
        //
        // Drawn above the mark and below the cover, so an album with no art at
        // all is untouched: nothing decodes, nothing paints, and the gradient
        // and the mark stay exactly as they were. Coil serves the second
        // request from memory, so the file is decoded once.
        if (contentScale != ContentScale.Crop) {
            AsyncImage(
                model = SongArt(songId, albumId),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                // Blur is honoured from Android 12 and ignored below it, where
                // this stays a scaled up still of the same artwork - softer
                // than a hard edge either way, and never a frame.
                alpha = BACKDROP_ALPHA,
                modifier = Modifier.fillMaxSize().blur(BACKDROP_BLUR)
            )
        }
        AsyncImage(
            model = SongArt(songId, albumId),
            contentDescription = null,
            // Covers that came from video thumbnails are 16:9. On a small
            // tile, fitting one into a square leaves the artwork itself small,
            // so those crop; the caller decides, because on the player the
            // whole picture matters more than a filled square.
            contentScale = contentScale,
            modifier = Modifier.fillMaxSize()
        )
    }
}

/** Enough to read as colour rather than as a second, smaller picture. */
private val BACKDROP_BLUR = 26.dp

/** Dimmed, so the cover in front of it stays the thing being looked at. */
private const val BACKDROP_ALPHA = 0.55f

@Composable
fun SectionHeader(
    title: String,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = rememberMetrics().gutter, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (actionLabel != null && onAction != null) {
            Text(
                text = actionLabel,
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    song: SongEntity,
    isCurrent: Boolean = false,
    liked: Int = 0,
    rating: Int = 0,
    playCount: Int = 0,
    selected: Boolean = false,
    selectionMode: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onMore: (() -> Unit)? = null,
    onLike: (() -> Unit)? = null,
    onDislike: (() -> Unit)? = null,
    /**
     * An extra clause on the second line, for something about this song that
     * the row would otherwise hide - a tag the app guessed, most of all. A
     * list of guesses that does not say what was guessed cannot be checked.
     */
    note: String? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) Accent.copy(alpha = 0.16f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = rememberMetrics().gutter, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectionMode) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = if (selected) Accent else Surface3,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(10.dp))
        }
        Artwork(
            songId = song.id,
            albumId = song.albumId,
            seed = song.artistKey,
            modifier = Modifier.size(48.dp),
            corner = 8
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                color = if (isCurrent) Accent else MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (rating > 0) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = null,
                        tint = Accent,
                        modifier = Modifier.size(11.dp)
                    )
                    Text(
                        text = "$rating",
                        style = MaterialTheme.typography.labelSmall,
                        color = Accent
                    )
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    text = buildString {
                        append(song.artistName)
                        append(" • ")
                        append(formatDuration(song.durationMs))
                        if (playCount > 0) {
                            append(" • ")
                            append(playCount)
                            append(" השמעות")
                        }
                        if (!note.isNullOrBlank()) {
                            append(" • ")
                            append(note)
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        trailing?.invoke()
        // On a narrow phone three icons eat most of the row and the title is left
        // with a few characters and an ellipsis. Dislike is the rarer of the two
        // and steps aside there; like stays, because it is the one thing people
        // do to a song without opening anything, and it is no longer duplicated
        // in the overflow menu.
        val roomForThumbs = !rememberMetrics().isCompact
        if (onDislike != null && !selectionMode && roomForThumbs) {
            IconButton(onClick = onDislike, modifier = Modifier.size(34.dp)) {
                Icon(
                    imageVector = if (liked == -1) Icons.Filled.ThumbDown else Icons.Outlined.ThumbDown,
                    contentDescription = "דיסלייק",
                    tint = if (liked == -1) Accent else TextTertiary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        if (onLike != null && !selectionMode) {
            IconButton(onClick = onLike, modifier = Modifier.size(34.dp)) {
                Icon(
                    imageVector = if (liked == 1) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                    contentDescription = "לייק",
                    tint = if (liked == 1) Accent else TextTertiary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        if (onMore != null && !selectionMode) {
            IconButton(onClick = onMore, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "עוד",
                    tint = TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/** The square card used inside horizontal shelves. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongCard(
    song: SongEntity,
    width: Dp = rememberMetrics().cardWidth,
    selected: Boolean = false,
    selectionMode: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
    onMore: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .width(width)
            .then(
                if (onLongClick == null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                }
            )
            .padding(horizontal = 4.dp, vertical = 4.dp)
    ) {
        Box {
            Artwork(
                songId = song.id,
                albumId = song.albumId,
                seed = song.artistKey,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                corner = 12
            )
            // Over the cover: a card has no margin to put a tick in, and one
            // that pushed the artwork would make the whole shelf jump the
            // moment anything was selected.
            if (selectionMode) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(Surface1.copy(alpha = 0.85f))
                        .padding(3.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = if (selected) Accent else Surface3,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = song.artistName,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // Offset up and in, so the menu sits against the title rather than
            // pushing the card wider than the cover above it.
            if (onMore != null) {
                IconButton(
                    onClick = onMore,
                    modifier = Modifier.size(28.dp).offset(y = (-2).dp)
                ) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "עוד",
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/** The big colourful gradient tile that represents a generated mix. */
@Composable
fun MixCard(
    id: String,
    title: String,
    subtitle: String,
    count: Int,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    covers: List<Pair<Long, Long>> = emptyList()
) {
    val (c1, c2) = gradientFor(id)
    Column(
        modifier = Modifier
            .width(rememberMetrics().mixCardWidth)
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(14.dp))
                .background(Brush.linearGradient(listOf(c1, c2)))
        ) {
            // The covers of what is actually inside, so a mix is recognisable at
            // a glance rather than being one more coloured rectangle. The
            // gradient stays underneath for mixes with too few covers to fill
            // the grid, and the text sits on a scrim so it stays readable over
            // whatever artwork lands behind it.
            Column(modifier = Modifier.fillMaxSize()) {
                // The collage identifies the mix; the strip under it names it.
                // Laying the title over the artwork looked good on one cover and
                // became unreadable on the next, and the kind of mix is the part
                // a listener actually needs to read.
                Box(modifier = Modifier.fillMaxWidth().weight(0.62f)) {
                    if (covers.size >= 4) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            for (row in 0 until 2) {
                                Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                                    for (column in 0 until 2) {
                                        val (songId, albumId) = covers[row * 2 + column]
                                        AsyncImage(
                                            model = SongArt(songId, albumId),
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.weight(1f).fillMaxHeight()
                                        )
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
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "$count שירים",
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
                    .background(Color.Black.copy(alpha = 0.35f))
                    .clickable(onClick = onPlay),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "נגן", tint = Color.White)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun StarRow(
    rating: Int,
    onRate: ((Int) -> Unit)? = null,
    size: Int = 22
) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (i in 1..5) {
            val filled = i <= rating
            val scale by animateFloatAsState(if (filled) 1f else 0.9f, label = "star$i")
            Icon(
                imageVector = if (filled) Icons.Filled.Star else Icons.Outlined.StarBorder,
                contentDescription = "דירוג $i",
                tint = if (filled) Accent else TextSecondary,
                modifier = Modifier
                    .size((size * scale).dp)
                    .then(
                        if (onRate != null) Modifier.clickable { onRate(if (rating == i) 0 else i) }
                        else Modifier
                    )
            )
        }
    }
}

@Composable
fun Chip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(ChipShape)
            .background(if (selected) Accent else Surface2)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Bg else MaterialTheme.colorScheme.onBackground,
            maxLines = 1
        )
    }
}

/** Softened rectangle rather than a pill - reads as a surface, not a tag. */
private val ChipShape = RoundedCornerShape(10.dp)

@Composable
fun LikeButtons(
    liked: Int,
    onLike: () -> Unit,
    onDislike: () -> Unit,
    size: Int = 24
) {
    // Like is declared first so RTL places it on the right, with dislike to its
    // left - the order asked for, and the one the rest of the UI reads in.
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onLike) {
            Icon(
                imageVector = if (liked == 1) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                contentDescription = "לייק",
                tint = if (liked == 1) Accent else TextSecondary,
                modifier = Modifier.size(size.dp)
            )
        }
        IconButton(onClick = onDislike) {
            Icon(
                imageVector = if (liked == -1) Icons.Filled.ThumbDown else Icons.Outlined.ThumbDown,
                contentDescription = "דיסלייק",
                tint = if (liked == -1) Accent else TextSecondary,
                // The two glyphs are not vertical mirrors of each other: the
                // thumb sits high in one and low in the other, so centring them
                // in equal boxes leaves them visibly out of line. A small nudge
                // lines up what the eye actually reads as the middle.
                modifier = Modifier
                    .offset(y = 3.dp)
                    .size(size.dp)
            )
        }
    }
}

@Composable
fun EmptyState(title: String, body: String, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(Surface1),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.MusicNote, contentDescription = null, tint = Accent)
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(6.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
        if (action != null) {
            Spacer(Modifier.height(18.dp))
            action()
        }
    }
}

/**
 * The letter strip down the edge of a long list. Dragging over it jumps the
 * list, which matters once a library passes a few hundred songs.
 */
@Composable
fun AlphabetIndex(
    letters: List<String>,
    modifier: Modifier = Modifier,
    onLetter: (String) -> Unit
) {
    if (letters.size < 4) return
    var height by androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0f) }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(22.dp)
            .onSizeChanged { height = it.height.toFloat() }
            .pointerInput(letters) {
                detectVerticalDragGestures { change, _ ->
                    if (height <= 0f) return@detectVerticalDragGestures
                    val fraction = (change.position.y / height).coerceIn(0f, 0.999f)
                    onLetter(letters[(fraction * letters.size).toInt()])
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        letters.forEach { letter ->
            Text(
                text = letter,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                modifier = Modifier.clickable { onLetter(letter) }
            )
        }
    }
}

/**
 * One named value on a scale of nothing to everything, with the explanation of
 * what it does underneath.
 *
 * The value is held by the caller while the finger moves and committed when it
 * lifts, so tuning does not re-rank a library on every pixel of the drag.
 */
@Composable
fun TuningSlider(
    label: String,
    value: Float,
    hint: String,
    onChange: (Float) -> Unit,
    onDone: () -> Unit
) {
    val gutter = rememberMetrics().gutter
    Column(modifier = Modifier.padding(horizontal = gutter, vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        Slider(
            value = value.coerceIn(0f, 1f),
            onValueChange = onChange,
            onValueChangeFinished = onDone,
            colors = SliderDefaults.colors(
                thumbColor = Accent,
                activeTrackColor = Accent,
                inactiveTrackColor = Surface1
            )
        )
    }
}

val ScreenPadding = PaddingValues(bottom = 150.dp)
