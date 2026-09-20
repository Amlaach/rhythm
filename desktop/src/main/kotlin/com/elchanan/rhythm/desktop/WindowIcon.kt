package com.elchanan.rhythm.desktop

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.BasicStroke
import java.awt.Color
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.geom.Line2D
import java.awt.image.BufferedImage

/**
 * The mark in the corner of the window, drawn rather than shipped.
 *
 * The same three leaning bars and the same gradient as [RhythmMark], from the
 * same numbers - a separate PNG beside them is a second copy that drifts the
 * first time either is touched, and nobody notices an icon drifting until it
 * is already wrong everywhere.
 *
 * Drawn with AWT rather than with Compose because a window's icon is settled
 * before there is any composition to draw into: it is an argument to the
 * window, not something inside it.
 *
 * Sixty four pixels, which is what Windows asks for in a title bar and in
 * Alt-Tab. The installed program's own icon is a separate thing and comes
 * from the .ico that jpackage embeds in the exe.
 */
internal object WindowIcon {

    private const val SIZE = 64

    /** The same 24 by 24 frame the drawn mark uses, so the proportions match. */
    private val BARS = listOf(
        floatArrayOf(5.6f, 19.6f, 10.2f, 6.8f),
        floatArrayOf(10.0f, 17.2f, 13.4f, 9.0f),
        floatArrayOf(13.4f, 19.9f, 18.4f, 6.4f)
    )

    val painter: Painter by lazy { BitmapPainter(render().toComposeImageBitmap()) }

    private fun render(): BufferedImage {
        val image = BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

        val unit = SIZE / 24f
        // Accent into Accent2, the two colours the rest of the app is lit
        // with. Written out here because AWT cannot read a Compose colour.
        g.paint = GradientPaint(
            0f, 0f, Color(0xFF, 0x2D, 0x55),
            SIZE.toFloat(), SIZE.toFloat(), Color(0x7A, 0x5C, 0xFF)
        )
        g.stroke = BasicStroke(4.0f * unit, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        for (bar in BARS) {
            g.draw(Line2D.Float(bar[0] * unit, bar[1] * unit, bar[2] * unit, bar[3] * unit))
        }
        g.dispose()
        return image
    }
}
