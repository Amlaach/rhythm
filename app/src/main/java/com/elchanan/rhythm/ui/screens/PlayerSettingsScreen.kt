package com.elchanan.rhythm.ui.screens

import com.elchanan.rhythm.ui.theme.localized

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import com.elchanan.rhythm.ui.theme.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.elchanan.rhythm.engine.ActionPlacement
import com.elchanan.rhythm.ui.MainViewModel
import com.elchanan.rhythm.data.ArtworkTap
import com.elchanan.rhythm.engine.PlayerAction
import com.elchanan.rhythm.ui.components.Chip
import com.elchanan.rhythm.ui.components.rememberMetrics
import com.elchanan.rhythm.ui.theme.Accent
import com.elchanan.rhythm.ui.theme.AppBackground
import com.elchanan.rhythm.ui.theme.BgElevated
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
    var arrangementOpen by remember { mutableStateOf(false) }
    var openOnPlay by remember { mutableStateOf(vm.prefs.openPlayerOnPlay) }
    var artworkTap by remember { mutableStateOf(vm.prefs.artworkTap) }
    var resumePrompt by remember { mutableStateOf(vm.prefs.resumePrompt) }
    var pauseSilent by remember { mutableStateOf(vm.prefs.pauseOnSilence) }
    var autoRadio by remember { mutableStateOf(vm.prefs.autoRadio) }
    var trimSilence by remember { mutableStateOf(vm.prefs.trimRadioSilence) }
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
                    contentDescription = localized("חזור"),
                    tint = TextSecondary
                )
            }
            Spacer(Modifier.width(4.dp))
            Text("הגדרות הנגן והשמע", style = MaterialTheme.typography.titleLarge)
        }

        LazyColumn(contentPadding = PaddingValues(bottom = 60.dp)) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth().clickable { arrangementOpen = true }
                        .padding(horizontal = gutter, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("סידור הכפתורים והתפריט", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "לאיזו פעולה יהיה כפתור משלה, לאיזו פריט בתפריט השלוש " +
                                "נקודות, ואיזו תוסתר",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Button(
                        onClick = { arrangementOpen = true },
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
                // One choice, not two switches: a tap cannot both pause the
                // song and open the picture.
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 10.dp)) {
                    Text("לחיצה על התמונה בנגן", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "התמונה הגדולה במסך הנגן היא הדבר הכי קל לפגוע בו בלי להסתכל. " +
                            "עצירה והמשך מציגה לרגע סימן באמצע התמונה, כמו ביוטיוב",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip(label = "עצירה והמשך", selected = artworkTap == ArtworkTap.TOGGLE) {
                            artworkTap = ArtworkTap.TOGGLE; vm.prefs.artworkTap = artworkTap
                        }
                        Chip(label = "הגדלת התמונה", selected = artworkTap == ArtworkTap.ZOOM) {
                            artworkTap = ArtworkTap.ZOOM; vm.prefs.artworkTap = artworkTap
                        }
                        Chip(label = "כלום", selected = artworkTap == ArtworkTap.NONE) {
                            artworkTap = ArtworkTap.NONE; vm.prefs.artworkTap = artworkTap
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
                SettingSwitch(
                    title = "דילוג על שקט בסוף שיר ברדיו",
                    subtitle = "כשהרדיו בחר את השיר ונשאר בסופו שקט ארוך, עובר לבא מיד " +
                        "כשהצליל נגמר. שירים שבחרת בעצמך מתנגנים עד הסוף",
                    checked = trimSilence
                ) {
                    trimSilence = it
                    vm.prefs.trimRadioSilence = it
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenEqualizer).padding(horizontal = gutter, vertical = 10.dp),
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

    if (arrangementOpen) {
        PlayerActionsSheet(vm = vm, onDismiss = { arrangementOpen = false })
    }
}

/**
 * Where every action sits: its own button on the player, an item in the three
 * dot menu, or nowhere at all.
 *
 * A sheet over the page rather than the list itself, for the same reason the
 * shelves are: sixteen actions with three choices each stand taller than a
 * phone, and the page above it is about how the player behaves rather than
 * about this one table. Every tap is written straight through - a placement
 * that moved without the player following it would be a lie about what the
 * app is doing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerActionsSheet(vm: MainViewModel, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val gutter = rememberMetrics().gutter
    var actions by remember { mutableStateOf(vm.prefs.playerActions) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = BgElevated
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Scrolls inside the sheet, so the last actions stay reachable
                // on a short screen without the list being cut off.
                .verticalScroll(rememberScrollState())
                .padding(bottom = 28.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = gutter, vertical = 4.dp)) {
                Text(
                    "סידור הכפתורים והתפריט",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "לכל פעולה אפשר לבחור: כפתור משלה במסך הנגן, פריט בתפריט " +
                        "השלוש נקודות, או מוסתרת לגמרי.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }

            for (action in PlayerAction.entries) {
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
        }
    }
}
