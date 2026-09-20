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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.elchanan.rhythm.ui.ActionPlacement
import com.elchanan.rhythm.ui.MainViewModel
import com.elchanan.rhythm.ui.PlayerAction
import com.elchanan.rhythm.ui.components.Chip
import com.elchanan.rhythm.ui.components.rememberMetrics
import com.elchanan.rhythm.ui.theme.Accent
import com.elchanan.rhythm.ui.theme.AppBackground
import com.elchanan.rhythm.ui.theme.TextSecondary

/**
 * How the player behaves, on a screen of its own.
 *
 * Kept out of the settings list rather than folded into it: which controls
 * exist, where each one sits, and how playback answers to the volume and to a
 * finished queue are all one subject, and a row that hid them on every visit
 * only made them harder to find. The equalizer stays a page further in, since
 * it is a surface of its own.
 */
@Composable
fun PlayerSettingsScreen(
    vm: MainViewModel,
    onBack: () -> Unit,
    onOpenEqualizer: () -> Unit = {}
) {
    // Every row on this screen shares the page margin, so a narrow phone gets
    // its content back instead of spending it on empty edges.
    val gutter = rememberMetrics().gutter
    var actions by remember { mutableStateOf(vm.prefs.playerActions) }
    var openOnPlay by remember { mutableStateOf(vm.prefs.openPlayerOnPlay) }
    var tapArtwork by remember { mutableStateOf(vm.prefs.tapArtworkToggles) }
    var resumePrompt by remember { mutableStateOf(vm.prefs.resumePrompt) }
    var pauseSilent by remember { mutableStateOf(vm.prefs.pauseOnSilence) }
    var autoRadio by remember { mutableStateOf(vm.prefs.autoRadio) }
    var normalizeVolume by remember { mutableStateOf(vm.prefs.normalizeVolume) }

    Column(modifier = Modifier.fillMaxSize().background(AppBackground)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "חזור",
                    tint = TextSecondary
                )
            }
            Spacer(Modifier.width(4.dp))
            Text("הגדרות הנגן והשמע", style = MaterialTheme.typography.titleLarge)
        }

        LazyColumn(contentPadding = PaddingValues(bottom = 60.dp)) {
            item {
                Text(
                    "לכל פעולה אפשר לבחור: כפתור משלה במסך הנגן, פריט בתפריט " +
                        "השלוש נקודות, או מוסתרת לגמרי.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(horizontal = gutter, vertical = 4.dp)
                )
            }
            items(PlayerAction.entries.toList()) { action ->
                val current = PlayerAction.placementOf(actions, action)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = gutter, vertical = 6.dp)
                ) {
                    Text(action.label, style = MaterialTheme.typography.bodyLarge)
                    if (action.about.isNotEmpty()) {
                        Text(
                            action.about,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionPlacement.entries.forEach { placement ->
                            Chip(
                                label = placement.label,
                                selected = current == placement,
                                onClick = {
                                    actions = actions + (action.key to placement.name)
                                    vm.prefs.playerActions = actions
                                }
                            )
                        }
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("לחיצה על שיר פותחת את הנגן", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "כבוי: השיר מתחיל והנגן נשאר מכווץ למטה, כמו היום. " +
                                "דלוק: מסך הנגן נפתח מיד",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Switch(
                        checked = openOnPlay,
                        onCheckedChange = {
                            openOnPlay = it
                            vm.prefs.openPlayerOnPlay = it
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Accent,
                            checkedTrackColor = Accent.copy(alpha = 0.4f)
                        )
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "לחיצה על התמונה עוצרת וממשיכה",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            "התמונה הגדולה במסך הנגן היא הדבר הכי קל לפגוע בו בלי " +
                                "להסתכל",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Switch(
                        checked = tapArtwork,
                        onCheckedChange = {
                            tapArtwork = it
                            vm.prefs.tapArtworkToggles = it
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Accent,
                            checkedTrackColor = Accent.copy(alpha = 0.4f)
                        )
                    )
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("הצעה להמשיך מהמיקום האחרון", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "כששיר נעזב באמצע ופותחים אותו שוב, מוצגת לכמה שניות " +
                                "הצעה לחזור לנקודה — עם הזמן המדויק. נשמר רק לשירים " +
                                "שנעזבו אחרי חצי דקה ולפני הסוף",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Switch(
                        checked = resumePrompt,
                        onCheckedChange = {
                            resumePrompt = it
                            vm.prefs.resumePrompt = it
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Accent,
                            checkedTrackColor = Accent.copy(alpha = 0.4f)
                        )
                    )
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("עצירה כשהעוצמה באפס", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "מוריד את העוצמה לאפס — ההשמעה נעצרת, ומתחדשת לבד " +
                                "כשמעלים בחזרה. בלי זה השיר ממשיך לרוץ בשקט",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Switch(
                        checked = pauseSilent,
                        onCheckedChange = {
                            pauseSilent = it
                            vm.prefs.pauseOnSilence = it
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Accent,
                            checkedTrackColor = Accent.copy(alpha = 0.4f)
                        )
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("רדיו אינסופי", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "כשהתור נגמר, ממשיך לבד לפי הטעם שנלמד",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Switch(
                        checked = autoRadio,
                        onCheckedChange = {
                            autoRadio = it
                            vm.updateTuning(autoRadio = it)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Accent,
                            checkedTrackColor = Accent.copy(alpha = 0.4f)
                        )
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("אקולייזר", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "כוונון תדרים לפי המנוע של המערכת, עם המוכנים מראש של המכשיר",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Button(
                        onClick = onOpenEqualizer,
                        colors = ButtonDefaults.buttonColors(containerColor = Accent)
                    ) { Text("פתח") }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("איזון עוצמה בין שירים", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "מנמיך את השירים החזקים במיוחד כדי שלא תצטרך לגעת בעוצמה בכל מעבר. " +
                                "דורש שהשירים ינותחו קודם",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Switch(
                        checked = normalizeVolume,
                        onCheckedChange = {
                            normalizeVolume = it
                            vm.prefs.normalizeVolume = it
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Accent,
                            checkedTrackColor = Accent.copy(alpha = 0.4f)
                        )
                    )
                }
            }
        }
    }
}
