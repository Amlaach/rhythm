package com.elchanan.rhythm.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
    val isCompact: Boolean,
    /**
     * A window wider than it is tall, with room for two columns: a tablet, or
     * a phone on its side. The player puts the cover beside the controls
     * there rather than above them.
     */
    val twoPane: Boolean = false,
    /** The cover's size when it has a pane of its own. */
    val paneArtwork: Dp = 0.dp,
    /** A window with little height to spare, where headers step aside sooner. */
    val isShort: Boolean = false,
    /** A small phone, around 320-380dp, where every label has to be short. */
    val isNarrow: Boolean = false
)

@Composable
fun rememberMetrics(): Metrics {
    val config = LocalConfiguration.current
    // The window in the dp the app is drawing in: compact mode draws with a
    // smaller dp, so the same window holds more of them.
    val scale = if (com.elchanan.rhythm.ui.Display.compact) 1f / com.elchanan.rhythm.ui.Display.COMPACT_SIZE else 1f
    val width = (config.screenWidthDp * scale).toInt()
    val height = (config.screenHeightDp * scale).toInt()
    return remember(width, height) {
        val gutter = when {
            // Small and older phones report around 320-360dp. At that width the
            // roomier margin is most of the difference between two cards fitting
            // and one, so it tightens rather than scaling down the content.
            width < 380 -> 12
            width < 600 -> 16
            width < 840 -> 24
            else -> 32
        }
        // Aim for roughly two and a half cards in view, so the shelf visibly
        // continues past the edge and invites a scroll.
        // The lower bounds come down on a narrow screen: clamping a card to
        // 140dp on a 320dp phone leaves no room for the next one to peek, and a
        // shelf you cannot tell scrolls is a shelf nobody scrolls.
        val minCard = if (width < 380) 118f else 140f
        val minMix = if (width < 380) 140f else 165f
        // And no taller than a short window can show beside the rest of the
        // page: sized by the width alone, a shelf on a landscape tablet or a
        // phone on its side was a wall of covers filling the whole height,
        // one shelf per screen. There more cards sit side by side instead.
        val card = ((width - gutter * 2) / 2.4f).coerceIn(minCard, 210f)
            .coerceAtMost(maxOf(112f, height * 0.32f))
        val mix = ((width - gutter * 2) / 2.1f).coerceIn(minMix, 260f)
            .coerceAtMost(maxOf(132f, height * 0.38f))

        // In landscape the limit is the height, not the width: the cover has to
        // leave room for the header, the title, the scrubber and the transport row,
        // which together want a little over half the window.
        //
        // The ceiling is a preference and the floor was a bug. coerceIn with a
        // floor of 160 pushed the cover back up past what the window could
        // hold on a short screen - a small phone in landscape, or a freeform
        // window - and a Column does not clip, so it drew over the rows below
        // it and came out looking cut off. Whatever the shape of the window,
        // the cover never asks for more than the window has: the floor is
        // applied first, and then the real limit wins over it.
        val artwork = minOf(width - gutter * 2, (height * 0.42f).toInt())
            .coerceAtLeast(96)
            .coerceAtMost(minOf(width - gutter * 2, (height * 0.46f).toInt()))
            .coerceAtMost(400)

        val twoPane = width >= 600 && width > height * 1.15f
        // Its own half of the window, less the player's header above it and
        // some air around it.
        val paneArtwork = minOf(height - 150, (width * 0.46f).toInt() - 56).coerceIn(120, 520)

        Metrics(
            gutter = gutter.dp,
            cardWidth = card.dp,
            mixCardWidth = mix.dp,
            gridCellMin = when {
                width < 380 -> 128.dp
                width < 600 -> 150.dp
                else -> 180.dp
            },
            artworkMax = artwork.dp,
            contentMax = 1040.dp,
            isCompact = width < 600,
            twoPane = twoPane,
            paneArtwork = paneArtwork.dp,
            isShort = height < 520,
            isNarrow = width < 380
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

/**
 * How tall a dialog's content may be: what it would like, but never more than
 * about half of the window. A dialog sized for a phone held upright ran off a
 * phone on its side, and whatever did not fit - often the part being asked
 * about - was simply cut away.
 */
@Composable
fun fitHeight(preferred: Dp): Dp {
    val height = LocalConfiguration.current.screenHeightDp
    return minOf(preferred, (height * 0.55f).dp).coerceAtLeast(140.dp)
}

/**
 * A dialog's content, scrolling when the window is too short for it rather
 * than cut off. Every dialog's text goes through here, so none of them has to
 * guess the height of the screen it will be shown on.
 */
@Composable
fun DialogBody(preferred: Dp = 480.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .heightIn(max = fitHeight(preferred))
            .verticalScroll(rememberScrollState()),
        content = content
    )
}
