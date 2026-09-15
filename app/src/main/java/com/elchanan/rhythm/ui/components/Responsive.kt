package com.elchanan.rhythm.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * One place that decides how wide things are allowed to get.
 *
 * The same screens have to work on a 5" phone held upright, on a tablet, and on
 * a landscape window three times as wide as it is tall. Hardcoded card widths
 * handle exactly one of those: on a wide screen a "full width" cover grows to
 * absurd size, and a fixed two-column grid leaves half the screen empty.
 * Everything below is derived from the current window instead.
 */
data class Metrics(
    /** Page margin: roomier as the window grows. */
    val gutter: Dp,
    /** Width of a square cover card inside a horizontal shelf. */
    val cardWidth: Dp,
    /** Width of the wider gradient mix tiles. */
    val mixCardWidth: Dp,
    /** Smallest a grid cell may be before the grid drops a column. */
    val gridCellMin: Dp,
    /** Ceiling for the big square cover on the player screen. */
    val artworkMax: Dp,
    /** Reading width for list content, so text does not span a whole tablet. */
    val contentMax: Dp,
    val isCompact: Boolean
)

@Composable
fun rememberMetrics(): Metrics {
    val config = LocalConfiguration.current
    val width = config.screenWidthDp
    val height = config.screenHeightDp
    return remember(width, height) {
        val gutter = when {
            width < 600 -> 16
            width < 840 -> 24
            else -> 32
        }
        // Aim for roughly two and a half cards in view, so the shelf visibly
        // continues past the edge and invites a scroll.
        val card = ((width - gutter * 2) / 2.4f).coerceIn(140f, 210f)
        val mix = ((width - gutter * 2) / 2.1f).coerceIn(165f, 260f)

        // In landscape the limit is the height, not the width: the cover has to
        // leave room for the header, the title, the scrubber and the transport row,
        // which together want a little over half the window.
        val artwork = minOf(width - gutter * 2, (height * 0.42f).toInt())
            .coerceIn(160, 400)

        Metrics(
            gutter = gutter.dp,
            cardWidth = card.dp,
            mixCardWidth = mix.dp,
            gridCellMin = if (width < 600) 150.dp else 180.dp,
            artworkMax = artwork.dp,
            contentMax = 1040.dp,
            isCompact = width < 600
        )
    }
}

/**
 * Quick-pick shelves stack four rows into a column and scroll sideways. On a
 * phone one column should very nearly fill the screen, with the next one just
 * peeking out; on a tablet it should stop growing rather than stretch a title
 * across half a metre of glass.
 */
@Composable
fun quickPickColumnWidth(): Dp {
    val width = LocalConfiguration.current.screenWidthDp
    return remember(width) { (width * 0.88f).coerceIn(280f, 420f).dp }
}
