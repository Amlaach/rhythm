package com.elchanan.rhythm.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.elchanan.rhythm.playback.EqBridge
import com.elchanan.rhythm.ui.MainViewModel
import com.elchanan.rhythm.ui.components.Chip
import com.elchanan.rhythm.ui.components.EmptyState
import com.elchanan.rhythm.ui.components.rememberMetrics
import com.elchanan.rhythm.ui.theme.Accent
import com.elchanan.rhythm.ui.theme.AppBackground
import com.elchanan.rhythm.ui.theme.Surface1
import com.elchanan.rhythm.ui.theme.TextSecondary

/**
 * The device's own equaliser, exposed as presets and per band sliders.
 *
 * It needs a live audio session to attach to, which only exists while something
 * is playing. Rather than show dead controls, the screen says so.
 */
@Composable
fun EqualizerScreen(vm: MainViewModel, onBack: () -> Unit) {
    val controller = EqBridge.controller
    val gutter = rememberMetrics().gutter
    var enabled by remember { mutableStateOf(vm.prefs.eqEnabled) }
    var preset by remember { mutableStateOf(vm.prefs.eqPreset) }
    var levels by remember {
        mutableStateOf(controller?.levels() ?: emptyList())
    }

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

        if (controller == null || controller.bandCount == 0) {
            EmptyState(
                title = "אין כרגע ערוץ שמע",
                body = "האקולייזר מתחבר לערוץ השמע של הנגן, וזה קיים רק בזמן השמעה. " +
                    "הפעל שיר וחזור לכאן."
            )
            return@Column
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
                        Text("אקולייזר", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "משתמש במנוע של המערכת, כך שהוא פועל גם דרך אוזניות ורמקול חיצוני",
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
                    Text(
                        "מוכנים מראש",
                        style = MaterialTheme.typography.labelLarge,
                        color = TextSecondary,
                        modifier = Modifier.padding(start = gutter, top = 8.dp, bottom = 4.dp)
                    )
                }
                item {
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

            item {
                Text(
                    "תדרים",
                    style = MaterialTheme.typography.labelLarge,
                    color = TextSecondary,
                    modifier = Modifier.padding(start = gutter, top = 14.dp, bottom = 4.dp)
                )
            }

            items(controller.bandCount) { band ->
                val hz = controller.bandFrequencies.getOrNull(band) ?: 0
                val value = levels.getOrNull(band)?.toFloat() ?: 0f
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = gutter, vertical = 2.dp)
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
                            text = "%+.1f dB".format(value / 100f),
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
                        onValueChangeFinished = {
                            controller.setBand(band, levels[band])
                        },
                        valueRange = controller.minLevel.toFloat()..controller.maxLevel.toFloat(),
                        enabled = enabled,
                        colors = SliderDefaults.colors(
                            thumbColor = Accent,
                            activeTrackColor = Accent,
                            inactiveTrackColor = Surface1
                        )
                    )
                }
            }

            item { Spacer(Modifier.height(10.dp)) }
        }
    }
}
