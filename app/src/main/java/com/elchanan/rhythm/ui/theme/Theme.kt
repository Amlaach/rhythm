package com.elchanan.rhythm.ui.theme

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
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(
            colorScheme = RhythmColors,
            typography = RhythmTypography,
            content = content
        )
    }
}
