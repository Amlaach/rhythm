package com.elchanan.rhythm.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Not quite black. A flat near-black ground makes every surface above it read
 * as a hole rather than a layer. Neutral graphite, with no hue of its own: the
 * colour on a screen belongs to the covers and the accent, not to the ground.
 */
val Bg = Color(0xFF0B0B0E)
val BgElevated = Color(0xFF101014)

/**
 * The app's ground, a little lighter under the status bar.
 *
 * Used instead of a flat fill on every full screen surface. A single colour
 * behind everything reads as an absence - the eye has nothing to place the
 * content against - and a slightly lighter graphite at the top gives each screen
 * a top edge without ever competing with artwork or text. It used to be violet;
 * the owner found it read as a purple glow, and pure black was not wanted either.
 *
 * It has to be applied by the screens themselves: anything painted over it,
 * including a Scaffold's own container colour, hides it completely.
 */
val AppBackground: Brush = Brush.verticalGradient(
    colorStops = arrayOf(
        0.00f to Color(0xFF17171B),
        0.12f to Color(0xFF131316),
        0.30f to Color(0xFF0E0E11),
        0.55f to Color(0xFF0B0B0E)
    )
)
/**
 * The wash across the top of the home screen: soft graphite light fading out
 * into the page.
 *
 * No hue. It was violet, and a coloured band like this reads as a glow that
 * tints every cover under it; grey light only says where the top of the page
 * is, the way a lit surface would, and leaves the colour to the artwork.
 */
val HeaderWarm = Color(0xFF26262C)
val HeaderMid = Color(0xFF18181D)

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
