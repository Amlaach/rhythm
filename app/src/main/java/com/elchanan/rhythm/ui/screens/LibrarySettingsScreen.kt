package com.elchanan.rhythm.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import com.elchanan.rhythm.ui.theme.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elchanan.rhythm.ui.MainViewModel
import com.elchanan.rhythm.ui.components.SectionHeader
import com.elchanan.rhythm.ui.components.TuningSlider
import com.elchanan.rhythm.ui.components.rememberMetrics
import com.elchanan.rhythm.ui.theme.Accent
import com.elchanan.rhythm.ui.theme.Accent2
import com.elchanan.rhythm.ui.theme.Surface1
import com.elchanan.rhythm.ui.theme.TextPrimary
import com.elchanan.rhythm.ui.theme.TextSecondary

/**
 * What gets into the library, and what has been measured about it.
 *
 * The sibling of the display screen: this one is about which files become
 * songs at all - the scan, the filters that trim it, and the analysis pass
 * that measures whatever survived. Reaching for "why is that song not here"
 * or "why is that ringtone here" lands on this screen and nowhere else.
 *
 * The reset for the analysis sits at the bottom of the analysis section
 * rather than in a drawer of its own, because a reset belongs next to the
 * thing it resets - it is only ever wanted by someone already looking at it.
 */
@Composable
fun LibrarySettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    val analysis by vm.analysisProgress.collectAsStateWithLifecycle()
    val gutter = rememberMetrics().gutter

    var minDuration by remember { mutableFloatStateOf(vm.prefs.minDurationSec.toFloat()) }
    var autoAnalyze by remember { mutableStateOf(vm.prefs.autoAnalyze) }
    var skipRecordings by remember { mutableStateOf(vm.prefs.skipRecordings) }
    var resumeSpoken by remember { mutableStateOf(vm.prefs.resumeSpoken) }
    var foldersOpen by remember { mutableStateOf(false) }
    var musicFoldersOpen by remember { mutableStateOf(false) }
    var musicFolders by remember { mutableStateOf(vm.prefs.musicFolders) }

    SettingsScaffold(title = "ספרייה וסריקה", onBack = onBack) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth().clickable { musicFoldersOpen = true }
                    .padding(horizontal = gutter, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("תיקיות המוזיקה", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = musicFolders.joinToString(", ") { folderLabel(it) }
                            .ifBlank { "מאיפה האפליקציה מביאה את המוזיקה. כרגע: כל המכשיר" },
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 2
                    )
                }
                Button(
                    onClick = { musicFoldersOpen = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                ) { Text("בחר") }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth().clickable { foldersOpen = true }
                    .padding(horizontal = gutter, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("תיקיות שלא ייסרקו", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = vm.prefs.excludedFolders.joinToString(", ")
                            .ifBlank { "כרגע נסרק הכל" },
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 2
                    )
                }
                Button(
                    onClick = { foldersOpen = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                ) { Text("ערוך") }
            }
        }

        item {
            TuningSlider(
                label = "אורך מינימלי לשיר: ${minDuration.toInt()} שניות",
                value = minDuration / 180f,
                hint = "קבצים קצרים יותר לא ייכנסו לספרייה (צלצולים, הודעות)",
                onChange = { minDuration = (it * 180f).coerceIn(0f, 180f) },
                onDone = { vm.updateTuning(minDurationSec = minDuration.toInt()) }
            )
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = gutter, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("דלג על הקלטות", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "הקלטות שיחה, הודעות קוליות ותזכורות לא ייכנסו לספרייה. " +
                            "חלק מאפליקציות ההקלטה מסמנות אותן כמוזיקה, ולכן צריך " +
                            "לזהות אותן לפי התיקייה ושם הקובץ",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                Switch(
                    checked = skipRecordings,
                    onCheckedChange = {
                        skipRecordings = it
                        vm.prefs.skipRecordings = it
                        vm.rescan(showMessage = false)
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
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = gutter, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("המשך הרצאות מהמקום", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "הקלטות ארוכות — שיעורים, סיפורים — ימשיכו מהמקום שבו " +
                            "הפסקת, בלי לשאול. שירים רגילים מתחילים מההתחלה",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                Switch(
                    checked = resumeSpoken,
                    onCheckedChange = {
                        resumeSpoken = it
                        vm.prefs.resumeSpoken = it
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
                modifier = Modifier.padding(horizontal = gutter, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = { vm.rescan() },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                ) { Text("סרוק מחדש") }
            }
        }

        item {
            SectionHeader(
                title = "ניתוח אודיו",
                subtitle = "מדידת קצב, סולם, אנרגיה וגוון — הכל על המכשיר"
            )
        }

        item {
            Column(modifier = Modifier.padding(horizontal = gutter, vertical = 6.dp)) {
                Text(
                    text = "נותחו ${analysis.done} מתוך ${analysis.total} שירים",
                    style = MaterialTheme.typography.titleSmall
                )
                if (analysis.running) {
                    Text(
                        text = analysis.currentTitle ?: "מעבד…",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 1
                    )
                } else {
                    Text(
                        text = when {
                            analysis.remaining > 0 && analysis.unreachable >= analysis.remaining ->
                                "${analysis.remaining} שירים לא נגישים כרגע — בכרטיס או כונן שלא מחובר"
                            analysis.remaining > 0 -> "נשארו ${analysis.remaining} שירים לניתוח"
                            else -> "כל הספרייה נותחה"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { analysis.fraction },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = Accent2,
                    trackColor = Surface1
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { if (analysis.running) vm.stopAnalysis() else vm.startAnalysis() },
                        colors = ButtonDefaults.buttonColors(containerColor = Accent)
                    ) { Text(if (analysis.running) "עצור" else "נתח עכשיו") }
                    Button(
                        onClick = { vm.resetAnalysis() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Surface1,
                            contentColor = TextPrimary
                        )
                    ) { Text("אפס ניתוח") }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("ניתוח אוטומטי", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "מנתח קבצים חדשים לבד אחרי כל סריקה",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                Switch(
                    checked = autoAnalyze,
                    onCheckedChange = {
                        autoAnalyze = it
                        vm.updateTuning(autoAnalyze = it)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Accent,
                        checkedTrackColor = Accent.copy(alpha = 0.4f)
                    )
                )
            }
        }

    }

    if (musicFoldersOpen) {
        MusicFoldersDialog(
            initial = musicFolders,
            onDismiss = { musicFoldersOpen = false },
            onApply = {
                musicFolders = it
                vm.prefs.musicFolders = it
                vm.rescan()
            }
        )
    }

    if (foldersOpen) {
        FolderDialog(
            initial = vm.prefs.excludedFolders,
            onDismiss = { foldersOpen = false },
            onApply = {
                vm.updateTuning(excludedFolders = it)
                foldersOpen = false
                vm.rescan()
            }
        )
    }
}
