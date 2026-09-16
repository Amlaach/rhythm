package com.elchanan.rhythm.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Not quite black. A flat near-black ground makes every surface above it read
 * as a hole rather than a layer. A few points of blue and violet keep the
 * screen feeling lit without ever becoming a colour of its own.
 */
val Bg = Color(0xFF0A0910)
val BgElevated = Color(0xFF101014)

/**
 * The app's ground, with a warm wash under the status bar.
 *
 * Used instead of a flat fill on every full screen surface. A single colour
 * behind everything reads as an absence - the eye has nothing to place the
 * content against - and the small amount of violet at the top gives each screen
 * a top edge without ever competing with artwork or text.
 *
 * It has to be applied by the screens themselves: anything painted over it,
 * including a Scaffold's own container colour, hides it completely.
 */
val AppBackground: Brush = Brush.verticalGradient(
    colorStops = arrayOf(
        0.00f to Color(0xFF241528),
        0.10f to Color(0xFF17101F),
        0.28f to Color(0xFF0E0B15),
        0.55f to Color(0xFF0A0910)
    )
)
val Surface1 = Color(0xFF17171D)
val Surface2 = Color(0xFF23232B)
val Surface3 = Color(0xFF2E2E38)

val Accent = Color(0xFFFF2D55)
val AccentSoft = Color(0xFFFF6B8A)
val Accent2 = Color(0xFF7A5CFF)
val Accent3 = Color(0xFF00D2C6)

val TextPrimary = Color(0xFFF4F4F7)
val TextSecondary = Color(0xFFA6A6B4)
val TextTertiary = Color(0xFF6E6E7C)
val Color_Error = Color(0xFFFF5252)

/**
 * The palette the mix cards are painted with. YouTube Music leans on saturated
 * two tone gradients; each mix picks one deterministically from its id so the
 * home screen stays visually stable between refreshes.
 */
val MixGradients: List<Pair<Color, Color>> = listOf(
    Color(0xFFFF2D55) to Color(0xFF7A1FA2),
    Color(0xFF7A5CFF) to Color(0xFF1E3AE0),
    Color(0xFF00D2C6) to Color(0xFF0A6E8A),
    Color(0xFFFFB020) to Color(0xFFE0492E),
    Color(0xFFFF6BC1) to Color(0xFF6E2BD6),
    Color(0xFF4ADE80) to Color(0xFF0F766E),
    Color(0xFF60A5FA) to Color(0xFF1E1B8A),
    Color(0xFFF97316) to Color(0xFF7C2D12),
    Color(0xFFA78BFA) to Color(0xFF4C1D95),
    Color(0xFFFF4D6D) to Color(0xFF2B1055)
)

fun gradientFor(key: String): Pair<Color, Color> {
    val h = key.fold(7) { acc, c -> acc * 31 + c.code }
    val idx = ((h % MixGradients.size) + MixGradients.size) % MixGradients.size
    return MixGradients[idx]
}
