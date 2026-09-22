package com.elchanan.rhythm.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.ui.theme.Accent
import com.elchanan.rhythm.ui.theme.Bg
import com.elchanan.rhythm.ui.theme.Surface1
import com.elchanan.rhythm.ui.theme.Surface2
import com.elchanan.rhythm.ui.theme.TextSecondary
import com.elchanan.rhythm.ui.theme.TextTertiary
import com.elchanan.rhythm.ui.theme.gradientFor

/**
 * The pieces every screen is built out of, drawn to match the phone.
 *
 * They live in one file rather than beside the screens that use them because
 * a row on the album screen and a row on a playlist have to be the same row -
 * the moment there are two of them they start drifting, and the difference
 * between two nearly identical lists is exactly the kind of thing that makes
 * an app feel unfinished.
 */

internal val GUTTER = 16.dp
internal val CARD = 156.dp

internal fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val minutes = total / 60
    val seconds = total % 60
    return "%d:%02d".format(minutes, seconds)
}

/**
 * A cover, or the panel that stands in for one.
 *
 * A flat surface rather than a placeholder picture or an empty hole: a row of
 * cards should read as a row of cards whether or not the files happen to
 * carry artwork, and most files in a library of downloads do not.
 */
@Composable
internal fun Art(
    song: SongEntity?,
    size: Dp,
    corner: Dp,
    modifier: Modifier = Modifier,
    /**
     * Show the whole picture rather than filling the square with part of it.
     *
     * True on the player, where the sleeve is the thing being looked at and
     * cropping cuts the artwork someone chose the record for - a wide live
     * shot loses its sides, a tall poster loses its title. False everywhere
     * else, where a cover is an identifier and a cropped one is still
     * recognisable at forty pixels.
     */
    fit: Boolean = false
) {
    val image = rememberArtwork(song)
    val (c1, c2) = gradientFor(song?.artistKey.orEmpty())
    Box(
        modifier = modifier
            .width(size)
            .height(size)
            .clip(RoundedCornerShape(corner))
            .background(Brush.linearGradient(listOf(c1, c2)))
    ) {
        if (image != null) {
            // What fills the corner a fitted cover cannot reach.
            //
            // Covers taken from video thumbnails are 16:9, and fitting one
            // into a square leaves two bands. A band of flat colour beside an
            // album sleeve reads as a frame drawn around it, so the bands get
            // the cover itself instead - scaled past the edges, dimmed and
            // blurred - and the surround becomes the record's own colours.
            //
            // Only when fitting. A cropped cover already reaches every edge.
            if (fit) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alpha = BACKDROP_ALPHA,
                    modifier = Modifier.fillMaxSize().blur(BACKDROP_BLUR)
                )
            }
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = if (fit) ContentScale.Fit else ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/** Enough to read as colour rather than as a second, smaller picture. */
private val BACKDROP_BLUR = 26.dp

/** Dimmed, so the cover in front of it stays the thing being looked at. */
private const val BACKDROP_ALPHA = 0.55f

/**
 * A cover for a whole album, picked from the record's own tracks.
 *
 * The first track that actually carries a picture rather than simply the
 * first track: a record where only the opener was tagged and a record where
 * only the closer was should both show their cover, and a fixed choice shows
 * one of them a blank panel.
 */
@Composable
internal fun AlbumArt(album: AlbumInfo, size: Dp, corner: Dp) {
    val withArt = album.songs.firstOrNull { Artwork.cached(it) != null } ?: album.songs.firstOrNull()
    Art(song = withArt, size = size, corner = corner)
}

/**
 * The one row every list of songs uses.
 *
 * Cover, title, then the artist and the length under it. Like and dislike sit
 * at the end because they are the two things people do to a song without
 * opening anything.
 */
@Composable
internal fun SongRow(
    song: SongEntity,
    isCurrent: Boolean = false,
    liked: Int = 0,
    rating: Int = 0,
    playCount: Int = 0,
    selected: Boolean = false,
    onClick: () -> Unit,
    onMore: (() -> Unit)? = null,
    onLike: (() -> Unit)? = null,
    onDislike: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    /**
     * An extra clause on the second line, for something about this song the
     * row would otherwise hide - a tag the app guessed, most of all. A list of
     * guesses that does not say what was guessed cannot be checked.
     */
    note: String? = null,
    /** Given, the row shows a tick box and this is what pressing it does. */
    leading: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                when {
                    selected -> Accent.copy(alpha = 0.16f)
                    isCurrent -> Accent.copy(alpha = 0.12f)
                    else -> Color.Transparent
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = GUTTER, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leading != null) {
            IconButton(onClick = leading, modifier = Modifier.size(30.dp)) {
                Icon(
                    imageVector = if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                    contentDescription = if (selected) "בטל בחירה" else "בחר",
                    tint = if (selected) Accent else TextTertiary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(6.dp))
        }
        Art(song = song, size = 48.dp, corner = 8.dp)
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
                    Text("$rating", style = MaterialTheme.typography.labelSmall, color = Accent)
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    text = buildString {
                        append(song.artistName.ifEmpty { "ללא אמן" })
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
        // A window is never as narrow as a phone, so both thumbs always fit
        // here - the phone drops dislike below a certain width and this is
        // the one place the two builds deliberately differ.
        if (onDislike != null) {
            IconButton(onClick = onDislike, modifier = Modifier.size(34.dp)) {
                Icon(
                    imageVector = if (liked == -1) Icons.Filled.ThumbDown else Icons.Outlined.ThumbDown,
                    contentDescription = "דיסלייק",
                    tint = if (liked == -1) Accent else TextTertiary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        if (onLike != null) {
            IconButton(onClick = onLike, modifier = Modifier.size(34.dp)) {
                Icon(
                    imageVector = if (liked == 1) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                    contentDescription = "לייק",
                    tint = if (liked == 1) Accent else TextTertiary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        if (onMore != null) {
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

/** Softened rectangle rather than a pill - reads as a surface, not a tag. */
private val ChipShape = RoundedCornerShape(10.dp)

@Composable
internal fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
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

@Composable
internal fun StarRow(rating: Int, onRate: ((Int) -> Unit)? = null, size: Int = 22) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (i in 1..5) {
            val filled = i <= rating
            Icon(
                imageVector = if (filled) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = "דירוג $i",
                tint = if (filled) Accent else TextSecondary,
                modifier = Modifier
                    .size(size.dp)
                    // Pressing the star already there clears the rating: one
                    // set by mistake otherwise has no way back except picking
                    // a different wrong one.
                    .then(
                        if (onRate != null) Modifier.clickable { onRate(if (rating == i) 0 else i) }
                        else Modifier
                    )
            )
        }
    }
}

@Composable
internal fun EmptyState(title: String, body: String, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(72.dp).clip(CircleShape).background(Surface1),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.MusicNote, contentDescription = null, tint = Accent)
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        if (action != null) {
            Spacer(Modifier.height(18.dp))
            action()
        }
    }
}

/** The back arrow and the title, above every screen that was drilled into. */
@Composable
internal fun DetailTopBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "חזור")
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * The A to Z strip down the side of a long list.
 *
 * A library of a few thousand songs is minutes of scrolling and seconds of
 * dragging this. Drag as well as click, because the point is to sweep to
 * roughly the right place and then look, not to hit a three millimetre
 * target; on a mouse the drag is what a scrollbar would have been, except
 * that it is labelled with where you are going rather than how far.
 *
 * Under four buckets it hides itself: an index of three letters is not
 * faster than the list it sits beside.
 */
@Composable
internal fun AlphabetIndex(
    letters: List<String>,
    modifier: Modifier = Modifier,
    onLetter: (String) -> Unit
) {
    if (letters.size < 4) return
    var height by remember { mutableStateOf(0f) }

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
        for (letter in letters) {
            Text(
                text = letter,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                modifier = Modifier.clickable { onLetter(letter) }
            )
        }
    }
}
