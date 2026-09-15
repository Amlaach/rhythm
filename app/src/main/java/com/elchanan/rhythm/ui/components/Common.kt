package com.elchanan.rhythm.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.CheckCircle
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.playback.MediaItems
import com.elchanan.rhythm.ui.theme.Accent
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
    albumId: Long,
    seed: String,
    modifier: Modifier = Modifier,
    corner: Int = 10
) {
    val (c1, c2) = gradientFor(seed)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(corner.dp))
            .background(Brush.linearGradient(listOf(c1, c2)))
    ) {
        Icon(
            imageVector = Icons.Filled.MusicNote,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.45f),
            modifier = Modifier
                .align(Alignment.Center)
                .size(26.dp)
        )
        AsyncImage(
            model = MediaItems.artworkUri(albumId),
            contentDescription = null,
            modifier = Modifier.fillMaxSize()
        )
    }
}

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
            .padding(horizontal = 16.dp, vertical = 6.dp),
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
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) Accent.copy(alpha = 0.16f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 7.dp),
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
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        trailing?.invoke()
        if (onDislike != null && !selectionMode) {
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
@Composable
fun SongCard(
    song: SongEntity,
    width: Int = 148,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .width(width.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 4.dp)
    ) {
        Artwork(
            albumId = song.albumId,
            seed = song.artistKey,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            corner = 12
        )
        Spacer(Modifier.height(8.dp))
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
        if (onLongClick != null) Spacer(Modifier.height(0.dp))
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
    onPlay: () -> Unit
) {
    val (c1, c2) = gradientFor(id)
    Column(
        modifier = Modifier
            .width(168.dp)
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
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 3,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
            )
            Text(
                text = "$count שירים",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
            )
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
            .clip(CircleShape)
            .background(if (selected) Accent.copy(alpha = 0.22f) else Surface2)
            .border(
                width = 1.dp,
                color = if (selected) Accent else Color.Transparent,
                shape = CircleShape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) Accent else MaterialTheme.colorScheme.onBackground
        )
    }
}

@Composable
fun LikeButtons(
    liked: Int,
    onLike: () -> Unit,
    onDislike: () -> Unit,
    size: Int = 24
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onDislike) {
            Icon(
                imageVector = if (liked == -1) Icons.Filled.ThumbDown else Icons.Outlined.ThumbDown,
                contentDescription = "דיסלייק",
                tint = if (liked == -1) Accent else TextSecondary,
                modifier = Modifier.size(size.dp)
            )
        }
        IconButton(onClick = onLike) {
            Icon(
                imageVector = if (liked == 1) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                contentDescription = "לייק",
                tint = if (liked == 1) Accent else TextSecondary,
                modifier = Modifier.size(size.dp)
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

val ScreenPadding = PaddingValues(bottom = 150.dp)
