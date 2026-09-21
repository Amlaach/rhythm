package com.elchanan.rhythm.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The app's mark: three leaning bars in the accent gradient.
 *
 * Drawn rather than shipped as an image so it stays sharp at any size and
 * picks up the same two colours the rest of the app uses. The proportions
 * match the launcher icon, so the thing in the corner of the screen and the
 * thing on the home screen read as one identity.
 *
 * It lives beside the colours rather than with the other components because
 * that is the source directory the desktop build compiles too, and this is
 * the one drawing both builds put on screen. There were two copies of it,
 * identical to the line, which is how a mark ends up subtly different in two
 * places after somebody adjusts one of them.
 */
@Composable
fun RhythmMark(modifier: Modifier = Modifier, size: Dp = 30.dp) {
    val brush = Brush.linearGradient(listOf(Accent, Accent2))
    Canvas(modifier = modifier.size(size)) {
        val unit = this.size.minDimension / 24f
        val stroke = 4.0f * unit
        // start and end of each bar, in a 24x24 frame
        val bars = listOf(
            Triple(5.6f, 19.6f, 10.2f) to 6.8f,
            Triple(10.0f, 17.2f, 13.4f) to 9.0f,
            Triple(13.4f, 19.9f, 18.4f) to 6.4f
        )
        for ((from, endY) in bars) {
            val (x0, y0, x1) = from
            drawLine(
                brush = brush,
                start = Offset(x0 * unit, y0 * unit),
                end = Offset(x1 * unit, endY * unit),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
        }
    }
}
