package com.elchanan.rhythm.ui.theme

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
