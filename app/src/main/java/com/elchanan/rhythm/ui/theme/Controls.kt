package com.elchanan.rhythm.ui.theme

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/*
 * Controls that say what they do.
 *
 * Bare icons were the main reason the app felt hard to steer: a row of a
 * waveform, sparkles and a radio, or four glyphs across the top of the home
 * screen, left people guessing, and a control you have to guess at is not one
 * you feel in charge of. These put the word beside the picture.
 *
 * Kept with the colours because this is the directory both builds compile, so
 * the phone and the desktop draw the same thing.
 */

/**
 * An action on the thing in front of you: an icon and its word, in a pill.
 * The same shape as the chips across the home screen, so it reads as
 * something to press rather than as a status light.
 */
@Composable
fun ActionPill(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (active) Accent.copy(alpha = 0.16f) else Surface1)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (active) Accent else TextSecondary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (active) Accent else TextPrimary,
            maxLines = 1
        )
    }
}

/**
 * A toolbar button with its name under it, for the few places - the top of
 * the home screen - where a row of icons has no other way to explain itself.
 */
@Composable
fun CaptionedIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = TextSecondary
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .widthIn(min = 52.dp)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * A bubble just under the control it is placed beside, with an arrow at that
 * control, and a button that closes it. For telling someone once that
 * something has moved: put it in a Box with the control it points at.
 */
@Composable
fun PointingHint(title: String, body: String, onDone: () -> Unit) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val position = androidx.compose.runtime.remember {
        BelowAnchor(with(density) { 4.dp.roundToPx() }, with(density) { 12.dp.roundToPx() })
    }
    androidx.compose.ui.window.Popup(popupPositionProvider = position, onDismissRequest = onDone) {
        Column {
            // Placed by absolute offset: in Hebrew a plain offset would mirror
            // the arrow away from the control it points at.
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .absoluteOffset { androidx.compose.ui.unit.IntOffset(position.arrowX - with(density) { 8.dp.roundToPx() }, 0) }
                    .size(16.dp, 8.dp)
                    .drawBehind {
                        val path = androidx.compose.ui.graphics.Path().apply {
                            moveTo(size.width / 2, 0f)
                            lineTo(size.width, size.height)
                            lineTo(0f, size.height)
                            close()
                        }
                        drawPath(path, Accent)
                    }
            )
            Row(
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Accent)
                    .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f, fill = false)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, color = Color.White)
                    Text(body, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.9f))
                }
                Spacer(Modifier.width(4.dp))
                androidx.compose.material3.TextButton(onClick = onDone) {
                    Text("הבנתי", style = MaterialTheme.typography.labelLarge, color = Color.White)
                }
            }
        }
    }
}

/**
 * Just under the control it points at, kept a margin inside the window, with
 * where the control's middle falls inside the bubble for the arrow to use.
 */
private class BelowAnchor(private val gap: Int, private val margin: Int) :
    androidx.compose.ui.window.PopupPositionProvider {
    var arrowX by androidx.compose.runtime.mutableIntStateOf(0)

    override fun calculatePosition(
        anchorBounds: androidx.compose.ui.unit.IntRect,
        windowSize: androidx.compose.ui.unit.IntSize,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        popupContentSize: androidx.compose.ui.unit.IntSize
    ): androidx.compose.ui.unit.IntOffset {
        val centre = anchorBounds.left + anchorBounds.width / 2
        val maxX = (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin)
        val x = (centre - popupContentSize.width / 2).coerceIn(margin, maxX)
        arrowX = centre - x
        return androidx.compose.ui.unit.IntOffset(x, anchorBounds.bottom + gap)
    }
}
