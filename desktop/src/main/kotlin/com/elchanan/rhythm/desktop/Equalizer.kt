package com.elchanan.rhythm.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import com.elchanan.rhythm.ui.theme.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elchanan.rhythm.desktop.audio.Equalizer
import com.elchanan.rhythm.engine.EqBands
import com.elchanan.rhythm.engine.EqPresets
import com.elchanan.rhythm.engine.EqResponse
import com.elchanan.rhythm.engine.EqSettings
import com.elchanan.rhythm.ui.theme.Accent
import com.elchanan.rhythm.ui.theme.Accent2
import com.elchanan.rhythm.ui.theme.Accent3
import com.elchanan.rhythm.ui.theme.AppBackground
import com.elchanan.rhythm.ui.theme.Surface1
import com.elchanan.rhythm.ui.theme.Surface2
import com.elchanan.rhythm.ui.theme.TextSecondary
import com.elchanan.rhythm.ui.theme.TextTertiary
import kotlin.math.abs
import kotlin.math.roundToInt

/** How far the curve's vertical axis runs, in decibels either way. */
private const val RANGE_DB = 12f

/**
 * Thirty one sliders, and a live picture of what they add up to.
 *
 * The same screen the phone has, drawing the same curve from the same
 * coefficients: [EqResponse] and [EqPresets] are in :engine and both builds
 * ask them the same questions. What the curve shows is the response the
 * audio is actually getting, not the slider positions - neighbouring filters
 * overlap, so three adjacent bands at +6 dB give closer to +9 in the middle,
 * and a picture drawn from the sliders would quietly disagree with the
 * sound.
 */
@Composable
internal fun EqualizerScreen(
    prefs: Prefs,
    equalizer: Equalizer,
    onBack: () -> Unit
) {
    // The screen's copy. The equaliser holds the same value and the audio
    // thread reads it, but a composable cannot observe a plain volatile
    // field, so the two are written together on every change.
    var settings by remember { mutableStateOf(equalizer.settings) }

    fun push(next: EqSettings, save: Boolean) {
        settings = next
        equalizer.setBands(next.bands)
        equalizer.setPreamp(next.preampMb)
        equalizer.enabled = next.enabled
        if (save) {
            prefs.eqBands = next.bands
            prefs.eqPreamp = next.preampMb
            prefs.eqEnabled = next.enabled
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(AppBackground)) {
        DetailTopBar(title = "אקולייזר", onBack = onBack)
        LazyColumn(
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = GUTTER, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("אקולייזר 31 תדרים", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "מחושב בתוך הנגן, אותה תוצאה בכל מכשיר",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Chip(
                        label = if (settings.enabled) "מופעל" else "כבוי",
                        selected = settings.enabled
                    ) { push(settings.copy(enabled = !settings.enabled), save = true) }
                }
            }
            item {
                ResponseCurve(
                    settings = settings,
                    onDraw = { band, mb -> push(settings.withBand(band, mb), save = false) },
                    onDrawEnd = { push(settings, save = true) }
                )
            }
            item {
                FaderStrip(
                    settings = settings,
                    onBand = { band, mb -> push(settings.withBand(band, mb), save = false) },
                    onBandEnd = { push(settings, save = true) }
                )
            }
            item {
                SectionLabel("מוכנים מראש")
                val current = remember(settings.bands) { EqPresets.matching(settings.bands) }
                LazyRow(
                    contentPadding = PaddingValues(horizontal = GUTTER),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(EqPresets.ALL) { preset ->
                        Chip(label = preset.name, selected = current == preset.name) {
                            push(
                                EqSettings.of(true, EqPresets.bands(preset), settings.preampMb),
                                save = true
                            )
                        }
                    }
                }
            }
            item {
                SectionLabel("עוצמה כללית")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = GUTTER)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Surface1)
                        .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatDb(settings.preampMb),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (settings.preampMb == 0) TextSecondary else Accent,
                        modifier = Modifier.width(52.dp)
                    )
                    Slider(
                        value = settings.preampMb.toFloat(),
                        onValueChange = {
                            push(settings.copy(preampMb = it.roundToInt()), save = false)
                        },
                        onValueChangeFinished = { push(settings, save = true) },
                        valueRange = EqBands.PREAMP_MIN_MB.toFloat()..EqBands.PREAMP_MAX_MB.toFloat(),
                        enabled = settings.enabled,
                        colors = SliderDefaults.colors(
                            thumbColor = Accent,
                            activeTrackColor = Accent,
                            inactiveTrackColor = Surface2
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    "מורידים כאן כשהגברה חזקה גורמת לעיוות",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                    modifier = Modifier.padding(start = GUTTER, end = GUTTER, top = 6.dp)
                )
            }
            item {
                Row(modifier = Modifier.padding(horizontal = GUTTER, vertical = 14.dp)) {
                    Chip(label = "אפס הכל", selected = false) {
                        push(
                            settings.copy(bands = List(EqBands.COUNT) { 0 }, preampMb = 0),
                            save = true
                        )
                    }
                }
            }
        }
    }
}

/**
 * Draws the response, and lets a finger draw on it.
 *
 * Dragging across sets whichever band is nearest, so a shape can be swept
 * out in one gesture rather than built one slider at a time. With thirty one
 * bands that is the difference between the equaliser being usable and being
 * a chore; the faders underneath are there for the last decibel.
 */
@Composable
private fun ResponseCurve(
    settings: EqSettings,
    onDraw: (Int, Int) -> Unit,
    onDrawEnd: () -> Unit
) {
    // Recomputed only when the settings change rather than on every frame:
    // the solve behind it is a few thousand logarithms.
    val curve = remember(settings) { EqResponse.curve(settings, points = 140) }
    val positions = remember {
        FloatArray(EqBands.COUNT) { EqResponse.fractionOf(EqBands.FREQUENCIES[it].toFloat()) }
    }
    // A frequency axis runs low on the left in every equaliser ever built,
    // and the drawing below is written that way. Left to the app's own
    // direction the labels would flip while the canvas stayed put, so the
    // axis would disagree with the curve drawn against it.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = GUTTER)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(170.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Surface1)
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(settings.enabled) {
                            if (!settings.enabled) return@pointerInput
                            fun paint(position: Offset) {
                                val x = (position.x / size.width).coerceIn(0f, 1f)
                                var nearest = 0
                                var best = Float.MAX_VALUE
                                for (i in positions.indices) {
                                    val d = abs(positions[i] - x)
                                    if (d < best) {
                                        best = d
                                        nearest = i
                                    }
                                }
                                val y = (position.y / size.height).coerceIn(0f, 1f)
                                val db = RANGE_DB - 2f * RANGE_DB * y
                                onDraw(nearest, (db * 100f).roundToInt())
                            }
                            detectDragGestures(
                                onDragStart = { paint(it) },
                                onDrag = { change, _ -> change.consume(); paint(change.position) },
                                onDragEnd = { onDrawEnd() },
                                onDragCancel = { onDrawEnd() }
                            )
                        }
                ) {
                    val w = size.width
                    val h = size.height
                    val mid = h / 2f
                    // A rule every 6 dB, with the zero line brighter: that is
                    // the one that says whether a band is doing anything.
                    for (db in intArrayOf(-12, -6, 0, 6, 12)) {
                        val y = mid - (db / RANGE_DB) * mid
                        drawLine(
                            color = if (db == 0) TextTertiary.copy(alpha = 0.5f)
                            else TextTertiary.copy(alpha = 0.14f),
                            start = Offset(0f, y),
                            end = Offset(w, y),
                            strokeWidth = if (db == 0) 1.4f else 1f
                        )
                    }
                    for (hz in intArrayOf(200, 2000)) {
                        val x = EqResponse.fractionOf(hz.toFloat()) * w
                        drawLine(
                            color = TextTertiary.copy(alpha = 0.14f),
                            start = Offset(x, 0f),
                            end = Offset(x, h),
                            strokeWidth = 1f
                        )
                    }
                    val points = curve.size
                    fun yFor(db: Float) =
                        mid - (db.coerceIn(-RANGE_DB, RANGE_DB) / RANGE_DB) * mid
                    val line = Path()
                    line.moveTo(0f, yFor(curve[0]))
                    for (i in 1 until points) {
                        line.lineTo(w * i / (points - 1).toFloat(), yFor(curve[i]))
                    }
                    // Filled back to the zero line rather than to the bottom,
                    // which is what makes a cut read as a cut instead of as a
                    // smaller boost.
                    val fill = Path()
                    fill.addPath(line)
                    fill.lineTo(w, mid)
                    fill.lineTo(0f, mid)
                    fill.close()
                    drawPath(
                        path = fill,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Accent.copy(alpha = 0.30f),
                                Accent2.copy(alpha = 0.08f),
                                Accent3.copy(alpha = 0.20f)
                            ),
                            startY = 0f,
                            endY = h
                        )
                    )
                    drawPath(
                        path = line,
                        brush = Brush.horizontalGradient(listOf(Accent, Accent2, Accent3)),
                        style = Stroke(width = 2.6f, cap = StrokeCap.Round)
                    )
                    // A dot on each band that has been moved, so it is clear
                    // the curve is made of thirty one things.
                    for (i in 0 until EqBands.COUNT) {
                        val gain = settings.bands[i]
                        if (gain == 0) continue
                        drawCircle(
                            color = Color.White,
                            radius = 2.8f,
                            center = Offset(positions[i] * w, yFor(gain / 100f))
                        )
                    }
                }
                Text(
                    "+12",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    color = TextTertiary,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                )
                Text(
                    "-12",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    color = TextTertiary,
                    modifier = Modifier.align(Alignment.BottomStart).padding(6.dp)
                )
                if (!settings.enabled) {
                    Box(
                        modifier = Modifier.fillMaxSize().background(Surface1.copy(alpha = 0.7f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "כבוי",
                            style = MaterialTheme.typography.labelLarge,
                            color = TextSecondary
                        )
                    }
                }
            }
            // The axis runs 20 Hz to 20 kHz over three decades, so these four
            // marks land at nought, a third, two thirds and the end - which
            // is what SpaceBetween gives without any measuring.
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                for (mark in listOf("20", "200", "2k", "20k")) {
                    Text(
                        mark,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        color = TextTertiary
                    )
                }
            }
        }
    }
}

@Composable
private fun FaderStrip(
    settings: EqSettings,
    onBand: (Int, Int) -> Unit,
    onBandEnd: () -> Unit
) {
    val scroll = rememberScrollState()
    // Same reason as the curve: band 0 is 20 Hz and belongs on the left,
    // under the left hand end of the curve it is drawn beneath.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scroll)
                    .padding(horizontal = GUTTER, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                for (band in 0 until EqBands.COUNT) {
                    Fader(
                        millibels = settings.bands[band],
                        label = EqBands.label(EqBands.FREQUENCIES[band]),
                        enabled = settings.enabled,
                        onChange = { onBand(band, it) },
                        onChangeEnd = onBandEnd
                    )
                }
            }
            // The one thing saying the strip goes on.
            //
            // Thirty one bands and a handful of them in view, with nothing at
            // either end to say so: the strip looked like the whole equaliser
            // and the rest of the bands may as well not have existed.
            //
            // The desktop scrollbar rather than the drawn one the phone uses,
            // because here it is also a control - it takes a drag and a click,
            // which is how someone with a mouse expects to cross a long strip.
            HorizontalScrollbar(
                adapter = rememberScrollbarAdapter(scroll),
                modifier = Modifier.fillMaxWidth().padding(start = GUTTER, end = GUTTER, bottom = 6.dp)
            )
        }
    }
}

/**
 * One vertical slider.
 *
 * Hand written rather than a rotated [Slider], for two reasons. A rotated
 * slider keeps the layout of a horizontal one, so thirty one of them do not
 * fit side by side; and its touch target stays the rotated rectangle, which
 * here would overlap its neighbours.
 */
@Composable
private fun Fader(
    millibels: Int,
    label: String,
    enabled: Boolean,
    onChange: (Int) -> Unit,
    onChangeEnd: () -> Unit
) {
    val span = (EqBands.MAX_MB - EqBands.MIN_MB).toFloat()
    val tint = when {
        !enabled -> TextTertiary
        millibels == 0 -> TextSecondary
        millibels > 0 -> Accent
        else -> Accent3
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(33.dp)
    ) {
        Text(
            text = if (millibels == 0) "" else formatDb(millibels),
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            color = tint,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.height(14.dp)
        )
        Box(
            modifier = Modifier
                .width(31.dp)
                .height(172.dp)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectVerticalDragGestures(
                        onDragStart = { onChange(valueAt(it.y, size.height, span)) },
                        onVerticalDrag = { change, _ ->
                            change.consume()
                            onChange(valueAt(change.position.y, size.height, span))
                        },
                        onDragEnd = { onChangeEnd() },
                        onDragCancel = { onChangeEnd() }
                    )
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(
                        // Back to flat in one gesture. It is the thing people
                        // try first on a slider they have pushed too far.
                        onDoubleTap = { onChange(0); onChangeEnd() },
                        onTap = {
                            onChange(valueAt(it.y, size.height, span))
                            onChangeEnd()
                        }
                    )
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val top = 11f
                val bottom = size.height - 11f
                val mid = (top + bottom) / 2f
                val thumbY = top + (bottom - top) * ((EqBands.MAX_MB - millibels) / span)
                drawLine(
                    color = TextTertiary.copy(alpha = 0.26f),
                    start = Offset(cx, top),
                    end = Offset(cx, bottom),
                    strokeWidth = 3f,
                    cap = StrokeCap.Round
                )
                // Filled from the middle, not from the floor: on an equaliser
                // the centre is the resting position, and a bar growing up
                // from the bottom would make -12 dB look untouched.
                if (enabled && millibels != 0) {
                    drawLine(
                        color = if (millibels > 0) Accent else Accent3,
                        start = Offset(cx, mid),
                        end = Offset(cx, thumbY),
                        strokeWidth = 3f,
                        cap = StrokeCap.Round
                    )
                    drawCircle(
                        color = (if (millibels > 0) Accent else Accent3).copy(alpha = 0.3f),
                        radius = 11f,
                        center = Offset(cx, thumbY)
                    )
                }
                drawCircle(
                    color = if (enabled) Color.White else TextTertiary,
                    radius = 6.5f,
                    center = Offset(cx, thumbY)
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            color = TextTertiary,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 5.dp)
        )
    }
}

/** Where a touch that far down the track lands, in millibels. */
private fun valueAt(y: Float, height: Int, span: Float): Int {
    val t = (y / height.toFloat()).coerceIn(0f, 1f)
    return (EqBands.MAX_MB - span * t).roundToInt()
}

/** Millibels as the decibels a person reads, signed. */
internal fun formatDb(millibels: Int): String {
    val db = millibels / 100f
    val rounded = (db * 10).roundToInt() / 10f
    return if (rounded > 0) "+$rounded dB" else "$rounded dB"
}

@Composable
private fun SectionLabel(text: String, gutter: Dp = GUTTER) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = TextSecondary,
        modifier = Modifier.padding(start = gutter, end = gutter, top = 14.dp, bottom = 6.dp)
    )
}
