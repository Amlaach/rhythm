package com.elchanan.rhythm.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elchanan.rhythm.playback.EqBands
import com.elchanan.rhythm.playback.EqBridge
import com.elchanan.rhythm.playback.EqPresets
import com.elchanan.rhythm.playback.EqResponse
import com.elchanan.rhythm.playback.EqSettings
import com.elchanan.rhythm.ui.MainViewModel
import com.elchanan.rhythm.ui.components.Chip
import com.elchanan.rhythm.ui.components.EmptyState
import com.elchanan.rhythm.ui.components.rememberMetrics
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

/**
 * Thirty one sliders, a live picture of what they add up to, and a switch
 * between the app's equaliser and the device's.
 *
 * The curve at the top is not decoration and it is not drawn through the
 * slider positions: it is the response the audio is actually getting, worked
 * out from the same coefficients the player is using. It is there because
 * thirty one numbers are not something anyone can read at a glance, and
 * because it is the only honest way to show that a boost here has
 * consequences either side of it.
 */
@Composable
fun EqualizerScreen(vm: MainViewModel, onBack: () -> Unit) {
    val gutter = rememberMetrics().gutter
    var useGraphic by remember { mutableStateOf(vm.prefs.eqUseGraphic) }

    Column(modifier = Modifier.fillMaxSize().background(AppBackground)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "חזור", tint = TextSecondary)
            }
            Spacer(Modifier.width(4.dp))
            Text("אקולייזר", style = MaterialTheme.typography.titleLarge)
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Chip(
                label = "31 תדרים",
                selected = useGraphic,
                onClick = {
                    useGraphic = true
                    vm.prefs.eqUseGraphic = true
                    // The system effect has to be told as well, or for a
                    // moment both would be filtering the same signal.
                    EqBridge.controller?.apply()
                }
            )
            Chip(
                label = "של המכשיר",
                selected = !useGraphic,
                onClick = {
                    useGraphic = false
                    vm.prefs.eqUseGraphic = false
                    EqBridge.graphic?.setEnabled(false)
                    EqBridge.controller?.apply()
                }
            )
        }

        if (useGraphic) GraphicEqualizer(vm, gutter) else SystemEqualizer(vm, gutter)
    }
}

// --- the app's own thirty one bands -----------------------------------------

@Composable
private fun GraphicEqualizer(vm: MainViewModel, gutter: Dp) {
    val controller = EqBridge.graphic
    if (controller == null) {
        EmptyState(
            title = "הנגן עוד לא עלה",
            body = "האקולייזר הוא חלק מנתיב השמע של הנגן. הפעל שיר פעם אחת וחזור לכאן - " +
                "אחרי זה הוא יישאר זמין גם בלי שמשהו מתנגן."
        )
        return
    }

    // The screen's copy of the settings. The controller holds the same value
    // and the audio thread reads it, but a composable cannot observe a plain
    // volatile field, so the two are written together on every change.
    var settings by remember { mutableStateOf(controller.settings) }

    LazyColumn(
        // Deep enough to scroll the last slider clear of the mini player and
        // the navigation bar, both of which float over this screen.
        contentPadding = PaddingValues(bottom = 150.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 8.dp),
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
                Switch(
                    checked = settings.enabled,
                    onCheckedChange = {
                        settings = settings.copy(enabled = it)
                        controller.setEnabled(it)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Accent,
                        checkedTrackColor = Accent.copy(alpha = 0.4f)
                    )
                )
            }
        }

        item {
            ResponseCurve(
                settings = settings,
                gutter = gutter,
                onDraw = { band, millibels ->
                    settings = settings.withBand(band, millibels)
                    controller.setBand(band, millibels)
                },
                onDrawEnd = { controller.commit() }
            )
        }

        item {
            FaderStrip(
                settings = settings,
                gutter = gutter,
                onBand = { band, millibels ->
                    settings = settings.withBand(band, millibels)
                    controller.setBand(band, millibels)
                },
                onBandEnd = { controller.commit() }
            )
        }

        item {
            SectionLabel("מוכנים מראש", gutter)
            val current = remember(settings.bands) { EqPresets.matching(settings.bands) }
            LazyRow(
                contentPadding = PaddingValues(horizontal = gutter),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(EqPresets.ALL.size) { index ->
                    val preset = EqPresets.ALL[index]
                    Chip(
                        label = preset.name,
                        selected = current == preset.name,
                        onClick = {
                            val bands = EqPresets.bands(preset)
                            settings = EqSettings.of(true, bands, settings.preampMb)
                            controller.setEnabled(true)
                            controller.setBands(bands)
                        }
                    )
                }
            }
        }

        item {
            SectionLabel("עוצמה כללית", gutter)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = gutter)
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
                        val mb = it.roundToInt()
                        settings = settings.copy(preampMb = mb)
                        controller.setPreamp(mb)
                    },
                    onValueChangeFinished = { controller.commit() },
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
                modifier = Modifier.padding(start = gutter, end = gutter, top = 6.dp)
            )
        }

        item {
            Row(modifier = Modifier.padding(horizontal = gutter, vertical = 14.dp)) {
                Chip(
                    label = "אפס הכל",
                    selected = false,
                    onClick = {
                        settings = settings.copy(bands = List(EqBands.COUNT) { 0 }, preampMb = 0)
                        controller.reset()
                    }
                )
            }
        }
    }
}

// --- the curve ---------------------------------------------------------------

/**
 * Draws the response, and lets a finger draw on it.
 *
 * Dragging across sets whichever band is nearest the finger, so a shape can be
 * swept out in one gesture rather than built one slider at a time. With
 * thirty one bands that is the difference between the equaliser being usable
 * and being a chore; the sliders underneath are there for the last decibel.
 */
@Composable
private fun ResponseCurve(
    settings: EqSettings,
    gutter: Dp,
    onDraw: (Int, Int) -> Unit,
    onDrawEnd: () -> Unit
) {
    // Recomputed only when the settings change rather than on every frame:
    // the solve behind it is a few thousand logarithms.
    val curve = remember(settings) { EqResponse.curve(settings, points = 140) }
    // Where each band sits across the width, 0 to 1. Fixed, so it is worked
    // out once instead of inside the drawing and the gesture.
    val positions = remember {
        FloatArray(EqBands.COUNT) {
            EqResponse.fractionOf(EqBands.FREQUENCIES[it].toFloat())
        }
    }

    // A frequency axis runs low on the left and high on the right, in every
    // equaliser ever built, and the drawing below is written that way. Left to
    // the app's own direction the labels and the sliders would both be flipped
    // by the layout while the canvas stayed put, so the axis would disagree
    // with the curve drawn against it.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = gutter)) {
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

                // A rule every 6 dB, with the zero line brighter than the
                // rest: that is the one that says whether a band is doing
                // anything at all.
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
                fun yFor(db: Float) = mid - (db.coerceIn(-RANGE_DB, RANGE_DB) / RANGE_DB) * mid

                val line = Path()
                line.moveTo(0f, yFor(curve[0]))
                for (i in 1 until points) {
                    line.lineTo(w * i / (points - 1).toFloat(), yFor(curve[i]))
                }

                // The same path closed back to the zero line and filled.
                // Filling to zero rather than to the bottom is what makes a
                // cut read as a cut instead of as a smaller boost.
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
                    brush = Brush.horizontalGradient(listOf(Accent, AccentGlow, Accent3)),
                    style = Stroke(width = 2.6f, cap = StrokeCap.Round)
                )

                // A dot on each band that has been moved, so it is clear the
                // curve is made of thirty one things and which ones are set.
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
        // marks land at exactly nought, a third, two thirds and the end -
        // which is what SpaceBetween gives without any measuring.
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

// --- the sliders -------------------------------------------------------------

@Composable
private fun FaderStrip(
    settings: EqSettings,
    gutter: Dp,
    onBand: (Int, Int) -> Unit,
    onBandEnd: () -> Unit
) {
    val scroll = rememberScrollState()
    // Same reason as the curve: band 0 is 20 Hz and belongs on the left, next
    // to the left hand end of the curve it is drawn under.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(horizontal = gutter, vertical = 10.dp),
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
    }
}

/**
 * One vertical slider.
 *
 * Hand written rather than a rotated [Slider], for two reasons. A rotated
 * slider keeps the layout of a horizontal one, so thirty one of them do not
 * fit side by side; and its touch target stays the rotated rectangle, which
 * here would overlap its neighbours.
 *
 * The gesture is vertical only. These sit in a strip that scrolls sideways,
 * and claiming every direction would mean the strip could not be scrolled by
 * dragging across the sliders - which is most of its surface.
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
                // from the bottom would make -12 dB look like an untouched
                // slider rather than a deep cut.
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

// --- the device's own effect --------------------------------------------------

@Composable
private fun SystemEqualizer(vm: MainViewModel, gutter: Dp) {
    val controller = EqBridge.controller
    var enabled by remember { mutableStateOf(vm.prefs.eqEnabled) }
    var preset by remember { mutableStateOf(vm.prefs.eqPreset) }
    var levels by remember { mutableStateOf(controller?.levels() ?: emptyList()) }

    if (controller == null || controller.bandCount == 0) {
        EmptyState(
            title = "אין כרגע ערוץ שמע",
            body = "האקולייזר של המכשיר מתחבר לערוץ השמע, וזה קיים רק בזמן השמעה. " +
                "הפעל שיר וחזור לכאן, או עבור לאקולייזר של 31 התדרים שעובד תמיד."
        )
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "${controller.bandCount} תדרים",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        "המנוע של המכשיר עצמו. מספר התדרים נקבע על ידי היצרן",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        controller.setEnabled(it)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Accent,
                        checkedTrackColor = Accent.copy(alpha = 0.4f)
                    )
                )
            }
        }

        if (controller.presetNames.isNotEmpty()) {
            item {
                SectionLabel("מוכנים מראש", gutter)
                LazyRow(
                    contentPadding = PaddingValues(horizontal = gutter),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(controller.presetNames.size + 1) { index ->
                        val isManual = index == controller.presetNames.size
                        Chip(
                            label = if (isManual) "ידני" else controller.presetNames[index],
                            selected = if (isManual) preset < 0 else preset == index,
                            onClick = {
                                val chosen = if (isManual) -1 else index
                                preset = chosen
                                controller.usePreset(chosen)
                                levels = controller.levels()
                            }
                        )
                    }
                }
            }
        }

        item { SectionLabel("תדרים", gutter) }

        items(controller.bandCount) { band ->
            val hz = controller.bandFrequencies.getOrNull(band) ?: 0
            val value = levels.getOrNull(band)?.toFloat() ?: 0f
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = gutter, vertical = 2.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Surface1)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (hz >= 1000) "${hz / 1000} kHz" else "$hz Hz",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = formatDb(value.roundToInt()),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
                Slider(
                    value = value,
                    onValueChange = { new ->
                        levels = levels.toMutableList().also {
                            while (it.size < controller.bandCount) it.add(0)
                            it[band] = new.toInt()
                        }
                        preset = -1
                    },
                    onValueChangeFinished = { controller.setBand(band, levels[band]) },
                    valueRange = controller.minLevel.toFloat()..controller.maxLevel.toFloat(),
                    enabled = enabled,
                    colors = SliderDefaults.colors(
                        thumbColor = Accent,
                        activeTrackColor = Accent,
                        inactiveTrackColor = Surface2
                    )
                )
            }
        }

        item { Spacer(Modifier.height(10.dp)) }
    }
}

// --- small shared pieces ------------------------------------------------------

@Composable
private fun SectionLabel(text: String, gutter: Dp) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = TextSecondary,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = gutter, top = 14.dp, bottom = 6.dp)
    )
}

/** Millibels as the decibels people expect to read, sign and all. */
private fun formatDb(millibels: Int): String =
    if (millibels == 0) "0.0" else "%+.1f".format(millibels / 100f)

/** How far the curve reaches, top and bottom, in decibels. */
private const val RANGE_DB = 12f

/** A lighter accent for the middle of the curve, so it has some depth. */
private val AccentGlow = Color(0xFFFF7BA0)
