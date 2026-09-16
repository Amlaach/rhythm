package com.elchanan.rhythm.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elchanan.rhythm.ui.MainViewModel
import com.elchanan.rhythm.ui.components.SectionHeader
import com.elchanan.rhythm.ui.theme.Accent2
import com.elchanan.rhythm.ui.theme.Accent
import com.elchanan.rhythm.ui.theme.Bg
import com.elchanan.rhythm.ui.theme.Surface1
import com.elchanan.rhythm.ui.theme.TextSecondary

@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val report by vm.report.collectAsStateWithLifecycle()
    val library by vm.library.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()

    var discovery by remember { mutableFloatStateOf(vm.prefs.discovery) }
    var artistWeight by remember { mutableFloatStateOf(vm.prefs.artistWeight) }
    var styleWeight by remember { mutableFloatStateOf(vm.prefs.styleWeight) }
    var repeatGuard by remember { mutableFloatStateOf(vm.prefs.repeatGuard) }
    var autoRadio by remember { mutableStateOf(vm.prefs.autoRadio) }
    var minDuration by remember { mutableFloatStateOf(vm.prefs.minDurationSec.toFloat()) }
    var acousticWeight by remember { mutableFloatStateOf(vm.prefs.acousticWeight) }
    var autoAnalyze by remember { mutableStateOf(vm.prefs.autoAnalyze) }
    var foldersOpen by remember { mutableStateOf(false) }
    var crossfade by remember { mutableFloatStateOf(vm.prefs.crossfadeMs.toFloat()) }
    var skipSilence by remember { mutableStateOf(vm.prefs.skipSilence) }
    var normalizeVolume by remember { mutableStateOf(vm.prefs.normalizeVolume) }
    val analysis by vm.analysisProgress.collectAsStateWithLifecycle()
    val lyricsFolder by vm.lyricsFolder.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            vm.setLyricsFolder(uri.toString())
        }
    }

    val topPad = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Column(modifier = Modifier.fillMaxSize().background(Bg)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = topPad).padding(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "חזור")
            }
            Text("הגדרות", style = MaterialTheme.typography.headlineSmall)
        }

        LazyColumn(contentPadding = PaddingValues(bottom = 60.dp)) {
            item {
                SectionHeader(title = "האלגוריתם", subtitle = "הכל מקומי, שום דבר לא יוצא מהמכשיר")
            }
            item {
                TuningSlider(
                    label = "גילוי מול מוכר",
                    value = discovery,
                    hint = "ככל שגבוה יותר, יופיעו יותר שירים שלא שמעת",
                    onChange = { discovery = it },
                    onDone = { vm.updateTuning(discovery = discovery) }
                )
            }
            item {
                TuningSlider(
                    label = "משקל דירוגי האמנים",
                    value = artistWeight / 2f,
                    hint = "כמה הכוכבים שנתת לאמנים משפיעים על הפיד",
                    onChange = { artistWeight = it * 2f },
                    onDone = { vm.updateTuning(artistWeight = artistWeight) }
                )
            }
            item {
                TuningSlider(
                    label = "משקל הסגנונות",
                    value = styleWeight / 2f,
                    hint = "כמה תגיות הסגנון מכתיבות את הבחירה",
                    onChange = { styleWeight = it * 2f },
                    onDone = { vm.updateTuning(styleWeight = styleWeight) }
                )
            }
            item {
                TuningSlider(
                    label = "משקל התאמת הסאונד",
                    value = acousticWeight / 2f,
                    hint = "כמה הקצב, האנרגיה והגוון שנמדדו מהקובץ משפיעים",
                    onChange = { acousticWeight = it * 2f },
                    onDone = { vm.updateTuning(acousticWeight = acousticWeight) }
                )
            }
            item {
                TuningSlider(
                    label = "מניעת חזרתיות",
                    value = repeatGuard / 2f,
                    hint = "ככל שגבוה יותר, שיר שהתנגן לאחרונה ירד בדירוג",
                    onChange = { repeatGuard = it * 2f },
                    onDone = { vm.updateTuning(repeatGuard = repeatGuard) }
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
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
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
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

            item {
                SectionHeader(
                    title = "ניתוח אודיו",
                    subtitle = "מדידת קצב, סולם, אנרגיה וגוון — הכל על המכשיר"
                )
            }
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
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
                            text = if (analysis.remaining > 0)
                                "נשארו ${analysis.remaining} שירים לניתוח"
                            else "כל הספרייה נותחה",
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
                            colors = ButtonDefaults.buttonColors(containerColor = Surface1)
                        ) { Text("אפס ניתוח") }
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
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

            item { SectionHeader(title = "הספרייה") }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
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
                        colors = ButtonDefaults.buttonColors(containerColor = Surface1)
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
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { vm.rescan() },
                        enabled = !busy,
                        colors = ButtonDefaults.buttonColors(containerColor = Accent)
                    ) { Text("סרוק מחדש") }
                    Button(
                        onClick = { vm.resetLearning() },
                        colors = ButtonDefaults.buttonColors(containerColor = Surface1)
                    ) { Text("אפס למידה") }
                }
            }

            item {
                SectionHeader(
                    title = "מילות שיר",
                    subtitle = "נקראות מתגיות הקובץ, ומקבצי LRC אם נבחרה תיקייה"
                )
            }
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(
                        text = if (lyricsFolder == null) "לא נבחרה תיקיית מילים"
                        else "תיקייה נבחרה",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = "מילים שמוטמעות בתוך קובץ השמע נקראות תמיד. קבצי .lrc או .txt " +
                            "שיושבים ליד השירים דורשים הרשאה חד פעמית לתיקייה.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { folderLauncher.launch(null) },
                            colors = ButtonDefaults.buttonColors(containerColor = Accent)
                        ) { Text("בחר תיקייה") }
                        if (lyricsFolder != null) {
                            Button(
                                onClick = { vm.setLyricsFolder(null) },
                                colors = ButtonDefaults.buttonColors(containerColor = Surface1)
                            ) { Text("נקה") }
                        }
                    }
                }
            }

            item { SectionHeader(title = "מה המנוע יודע עליך") }
            item {
                val r = report
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    if (r == null) {
                        Text("עוד אין נתונים", color = TextSecondary)
                    } else {
                        Stat("שירים בספרייה", "${r.totalSongs}")
                        Stat("אמנים", "${r.totalArtists} (מדורגים: ${r.ratedArtists})")
                        Stat("שירים עם תגית סגנון", "${r.songsWithStyle}")
                        Stat("שירים מדורגים", "${r.ratedSongs}")
                        Stat("קשרים סימטריים שנלמדו", "${r.learnedPairs}")
                        Stat("מעברים מכוונים שנלמדו", "${r.learnedTransitions}")
                        Stat("שירים שנותחו אקוסטית", "${r.analyzedSongs}")
                        Stat("קצב חציוני בספרייה", if (r.medianBpm > 0) "${r.medianBpm} BPM" else "—")
                        Stat("שירים שסומנו בלייק", "${library.liked.size}")
                        Spacer(Modifier.height(10.dp))
                        Text("הסגנונות המובילים שלך", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        if (r.topStyles.isEmpty()) {
                            Text(
                                "אחרי שתדרג כמה אמנים ותסמן סגנונות, כאן יופיע פרופיל הטעם",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        } else {
                            r.topStyles.forEach { (style, weight) ->
                                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                    Text(style, style = MaterialTheme.typography.bodyMedium)
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        "%.2f".format(weight),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (foldersOpen) {
        FolderDialog(
            initial = vm.prefs.excludedFolders,
            onDismiss = { foldersOpen = false },
            onApply = {
                vm.updateTuning(excludedFolders = it)
                vm.rescan()
            }
        )
    }
}

@Composable
private fun FolderDialog(
    initial: List<String>,
    onDismiss: () -> Unit,
    onApply: (List<String>) -> Unit
) {
    var text by remember { mutableStateOf(initial.joinToString("\n")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        title = { Text("תיקיות שלא ייסרקו") },
        text = {
            Column {
                Text(
                    "חלק משם הנתיב, שורה לכל תיקייה. כל קובץ שהנתיב שלו מכיל את הטקסט הזה לא ייכנס לספרייה.\n\nלמשל:\nWhatsApp\nRecordings\nRingtones",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 220.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onApply(text.lines().map { it.trim() }.filter { it.isNotEmpty() })
                onDismiss()
            }) { Text("שמור וסרוק", color = Accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("ביטול", color = TextSecondary) }
        }
    )
}

@Composable
private fun Stat(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Spacer(Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun TuningSlider(
    label: String,
    value: Float,
    hint: String,
    onChange: (Float) -> Unit,
    onDone: () -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        Slider(
            value = value.coerceIn(0f, 1f),
            onValueChange = onChange,
            onValueChangeFinished = onDone,
            colors = SliderDefaults.colors(
                thumbColor = Accent,
                activeTrackColor = Accent,
                inactiveTrackColor = Surface1
            )
        )
    }
}
