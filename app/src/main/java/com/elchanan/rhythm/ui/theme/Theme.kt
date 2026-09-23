package com.elchanan.rhythm.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

private val RhythmColors = darkColorScheme(
    primary = Accent,
    onPrimary = Bg,
    primaryContainer = Surface2,
    onPrimaryContainer = TextPrimary,
    secondary = Accent2,
    onSecondary = TextPrimary,
    tertiary = Accent3,
    background = Bg,
    onBackground = TextPrimary,
    surface = BgElevated,
    onSurface = TextPrimary,
    surfaceVariant = Surface2,
    onSurfaceVariant = TextSecondary,
    outline = Surface3,
    error = Color_Error,
    onError = TextPrimary
)

@Composable
fun RhythmTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RhythmColors,
        typography = RhythmTypography
    ) {
        // The listener can choose English; Hebrew remains the default.
        //
        // And a content colour, because Compose's default is Color.Black and
        // a Text that names no colour of its own takes it. Material3 normally
        // supplies one from whatever Surface the text sits on - but every
        // screen here paints the ground itself, with a Box and a gradient,
        // precisely so the gradient is not covered by a Surface's flat fill.
        // No Surface means no content colour, which means black letters on a
        // near black background: invisible, and invisible in exactly the
        // places nobody thought to pass a colour.
        //
        // Set here rather than at each screen so it cannot be forgotten by
        // the next one. Anything that wants a different colour - a Button, a
        // Surface, a Text that names one - still overrides it locally.
        CompositionLocalProvider(
            LocalLayoutDirection provides if (UiLanguage.english) LayoutDirection.Ltr else LayoutDirection.Rtl,
            LocalContentColor provides TextPrimary,
            content = content
        )
    }
}
