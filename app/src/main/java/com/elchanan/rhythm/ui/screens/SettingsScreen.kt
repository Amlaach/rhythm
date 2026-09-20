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
import com.elchanan.rhythm.ui.components.Chip
import com.elchanan.rhythm.ui.components.SectionHeader
import com.elchanan.rhythm.ui.components.TuningSlider
import com.elchanan.rhythm.ui.components.rememberMetrics
import com.elchanan.rhythm.ui.theme.Accent2
import com.elchanan.rhythm.ui.theme.Accent
import com.elchanan.rhythm.ui.theme.AppBackground
import com.elchanan.rhythm.ui.theme.Bg
import com.elchanan.rhythm.ui.theme.Surface1
import com.elchanan.rhythm.ui.theme.TextPrimary
import com.elchanan.rhythm.ui.theme.TextSecondary
import com.elchanan.rhythm.ui.theme.TextTertiary

/**
 * The name the picker shows for a document, which is where the playlist gets
 * its own name from. Falls back to the last path segment, which for a
 * content:// uri is an opaque id but is at least never empty.
 */
private fun displayNameOf(context: android.content.Context, uri: android.net.Uri): String {
    val projection = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)
    runCatching {
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val value = cursor.getString(0)
                if (!value.isNullOrBlank()) return value
            }
        }
    }
    return uri.lastPathSegment.orEmpty()
}

@Composable
fun SettingsScreen(
    vm: MainViewModel,
    onBack: () -> Unit,
    onOpenTagFix: () -> Unit = {},
    onOpenHomeSettings: () -> Unit = {},
    onOpenPlayerSettings: () -> Unit = {},
    onOpenAlgorithmSettings: () -> Unit = {}
) {
    val report by vm.report.collectAsStateWithLifecycle()
    val scan by vm.scanReport.collectAsStateWithLifecycle()
    val evaluation by vm.sequenceReport.collectAsStateWithLifecycle()
    val library by vm.library.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    // Every row on this screen shares the page margin, so a narrow phone gets
    // its content back instead of spending it on empty edges.
    val gutter = rememberMetrics().gutter

    var minDuration by remember { mutableFloatStateOf(vm.prefs.minDurationSec.toFloat()) }
    var autoAnalyze by remember { mutableStateOf(vm.prefs.autoAnalyze) }
    var foldersOpen by remember { mutableStateOf(false) }
    var crossfade by remember { mutableFloatStateOf(vm.prefs.crossfadeMs.toFloat()) }
    var skipSilence by remember { mutableStateOf(vm.prefs.skipSilence) }
    var libraryOpen by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var searchPersonal by remember { mutableStateOf(vm.prefs.searchPersonalized) }
    var searchLyrics by remember { mutableStateOf(vm.prefs.searchLyrics) }
    var firstTab by remember { mutableStateOf(vm.prefs.libraryFirstTab) }
    var hideDupes by remember { mutableStateOf(vm.prefs.hideDuplicates) }
    var stripForeign by remember { mutableStateOf(vm.prefs.tagStripForeign) }
    var writeTags by remember { mutableStateOf(vm.prefs.writeTagsToFiles) }
    var separations by remember { mutableStateOf(vm.prefs.styleSeparations) }
    var separationsOpen by remember { mutableStateOf(false) }
    var folderTree by remember { mutableStateOf(vm.prefs.folderTree) }
    var skipRecordings by remember { mutableStateOf(vm.prefs.skipRecordings) }
    var resumeSpoken by remember { mutableStateOf(vm.prefs.resumeSpoken) }
    val analysis by vm.analysisProgress.collectAsStateWithLifecycle()
    val lyricsFolder by vm.lyricsFolder.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Any mime type, because an .m3u exported by another player is served as
    // audio/x-mpegurl, text/plain or application/octet-stream depending on who
    // wrote it - filtering on type is how these files become unpickable.
    val playlistLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            vm.importPlaylist(uri, displayNameOf(context, uri))
        }
    }
    // A folder rather than a file: there is more than one list to write, and
    // asking where to put each of thirty of them would be absurd.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) vm.exportAllPlaylists(uri)
    }

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

    Column(modifier = Modifier.fillMaxSize().background(AppBackground)) {
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
            // ---------------------------------------------------------------
            // האלגוריתם
            // ---------------------------------------------------------------
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = gutter, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("הגדרות האלגוריתם", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "גילוי, משקל דירוגים וסגנונות, התאמת סאונד. הכל מקומי — " +
                                "שום דבר לא יוצא מהמכשיר",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Button(
                        onClick = onOpenAlgorithmSettings,
                        colors = ButtonDefaults.buttonColors(containerColor = Accent)
                    ) { Text("פתח") }
                }
            }

            // ---------------------------------------------------------------
            // דף הבית
            // ---------------------------------------------------------------
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = gutter, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("הגדרות דף הבית", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "אילו מדפים יופיעו, ופס מצבי הרוח",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Button(
                        onClick = onOpenHomeSettings,
                        colors = ButtonDefaults.buttonColors(containerColor = Accent)
                    ) { Text("פתח") }
                }
            }

            // ---------------------------------------------------------------
            // הספרייה
            // ---------------------------------------------------------------
            item {
                SectionToggleRow(
                    title = "הגדרות הספרייה",
                    subtitle = "מה נפתח ראשון, וכפילויות",
                    open = libraryOpen,
                    onToggle = { libraryOpen = !libraryOpen }
                )
            }
            if (libraryOpen) {
                item {
                    Column(modifier = Modifier.padding(horizontal = gutter, vertical = 6.dp)) {
                        Text("מה נפתח ראשון", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Chip(
                                label = "פלייליסטים",
                                selected = firstTab == "PLAYLISTS",
                                onClick = { firstTab = "PLAYLISTS"; vm.prefs.libraryFirstTab = firstTab }
                            )
                            Chip(
                                label = "תיקיות",
                                selected = firstTab == "FOLDERS",
                                onClick = { firstTab = "FOLDERS"; vm.prefs.libraryFirstTab = firstTab }
                            )
                        }
                    }
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("הסתרת כפילויות", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "כשאותה הקלטה קיימת פעמיים במכשיר, מוצג רק עותק אחד. " +
                                    "גרסאות שונות של אותו שיר — לייב, קאבר — נחשבות " +
                                    "שירים נפרדים ולא נעלמות",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                        Switch(
                            checked = hideDupes,
                            onCheckedChange = {
                                hideDupes = it
                                vm.setHideDuplicates(it)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Accent,
                                checkedTrackColor = Accent.copy(alpha = 0.4f)
                            )
                        )
                    }
                }
            }

            // ---------------------------------------------------------------
            // החיפוש
            // ---------------------------------------------------------------
            item {
                SectionToggleRow(
                    title = "הגדרות החיפוש",
                    subtitle = "איך תוצאות מסודרות",
                    open = searchOpen,
                    onToggle = { searchOpen = !searchOpen }
                )
            }
            if (searchOpen) {
                item {
                    Text(
                        "החיפוש מתעלם מאותיות סופיות, מגרשיים ומניקוד — \"מוהרן\" " +
                            "מוצא את \"מוהר״ן\" — ומוחל על שגיאת כתיב אחת במילים ארוכות.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        modifier = Modifier.padding(horizontal = gutter, vertical = 4.dp)
                    )
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("חיפוש גם במילות השיר", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "מוצא שיר לפי שורה שזכור לך ממנו, גם כשאת השם שכחת. " +
                                    "עובד על שירים שיש להם מילים — מקובץ LRC או מתגיות הקובץ",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                        Switch(
                            checked = searchLyrics,
                            onCheckedChange = {
                                searchLyrics = it
                                vm.prefs.searchLyrics = it
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
                            Text("התאמה אישית בתוצאות", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "כששתי תוצאות מתאימות באותה מידה לטקסט, זו שקרובה " +
                                    "לטעם שלך תופיע ראשונה. לא משנה סדר של התאמה מדויקת",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                        Switch(
                            checked = searchPersonal,
                            onCheckedChange = {
                                searchPersonal = it
                                vm.prefs.searchPersonalized = it
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Accent,
                                checkedTrackColor = Accent.copy(alpha = 0.4f)
                            )
                        )
                    }
                }
            }

            // ---------------------------------------------------------------
            // הנגן והשמע
            // ---------------------------------------------------------------
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = gutter, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("הגדרות הנגן והשמע", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "אילו כפתורים יופיעו בנגן, רדיו, אקולייזר ואיזון עוצמה",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Button(
                        onClick = onOpenPlayerSettings,
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
                        Text("תיקון תגיות", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "מפריד את שם האמן משם השיר ומנקה שמות ערוץ. בלי זה כל השירים " +
                                "שהורדו מהאינטרנט נראים כמו אמן אחד",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Button(
                        onClick = onOpenTagFix,
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
                        Text("ייבוא רשימת השמעה", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "קורא קובץ M3U, M3U8 או PLS שיוצא מנגן אחר — למשל " +
                                "\"ייצא כקובץ M3U\" במיוזיקולט. השירים מזוהים לפי שם הקובץ",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Button(
                        onClick = { playlistLauncher.launch(arrayOf("*/*")) },
                        colors = ButtonDefaults.buttonColors(containerColor = Accent)
                    ) { Text("בחר קובץ") }
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
                        Text("ייצוא כל הרשימות", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "כותב לתיקייה שתבחר קובץ M3U8 לכל פלייליסט, לכל מיקס " +
                                "ולכל מצב רוח. המיקסים ומצבי הרוח נבנים כאן ולא קיימים " +
                                "בשום מקום אחר",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Button(
                        onClick = { exportLauncher.launch(null) },
                        enabled = !busy,
                        colors = ButtonDefaults.buttonColors(containerColor = Accent)
                    ) { Text("בחר תיקייה") }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("הסרת טקסט באנגלית", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "מוריד משם השיר את מה שנוסף באנגלית — קרדיטים של מפיקים, " +
                                "שם אנגלי בסוגריים ושאריות מכותרת הסרטון. " +
                                "סימון של הופעה חיה נשמר",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Switch(
                        checked = stripForeign,
                        onCheckedChange = {
                            stripForeign = it
                            vm.setTagStripForeign(it)
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
                        Text("כתיבת התיקון לקבצים", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "כבוי: התיקונים נשמרים באפליקציה בלבד והקבצים במחשב לא משתנים. " +
                                "בהפעלה, כל תיקון ייכתב גם לתוך קובץ ה־MP3 עצמו, כך שגם נגנים " +
                                "אחרים יראו אותו. העטיפה והשאר נשמרים",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Switch(
                        checked = writeTags,
                        onCheckedChange = {
                            writeTags = it
                            vm.prefs.writeTagsToFiles = it
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

            item { SectionHeader(title = "הספרייה") }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = gutter, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("תיקיות בתוך תיקיות", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "מציג את התיקיות כמו שהן מסודרות במכשיר — אחת בתוך השנייה — " +
                                "במקום רשימה אחת ארוכה של כל התיקיות",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Switch(
                        checked = folderTree,
                        onCheckedChange = {
                            folderTree = it
                            vm.prefs.folderTree = it
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
                        Text("סגנונות שלא יתערבבו", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = separations.split('\n')
                                .filter { it.isNotBlank() }
                                .joinToString(" · ") { it.trim() }
                                .ifBlank { "הכל יכול להתערבב" },
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            maxLines = 2
                        )
                    }
                    Button(
                        onClick = { separationsOpen = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Accent)
                    ) { Text("ערוך") }
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
                    Button(
                        onClick = { vm.resetLearning() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Surface1,
                            contentColor = TextPrimary
                        )
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
                Column(modifier = Modifier.padding(horizontal = gutter, vertical = 6.dp)) {
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
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Surface1,
                                    contentColor = TextPrimary
                                )
                            ) { Text("נקה") }
                        }
                    }
                }
            }

            // Which build this actually is. Without it there is no way to tell
            // an install from a month ago from one made five minutes ago, and
            // no way to answer "does your copy have the fix in it" - which is
            // the first question any report needs settled.
            // The engine's own mark. Every change to the ranking is otherwise
            // an argument, and this is the only thing in the app that can
            // settle one.
            item {
                SectionHeader(
                    title = "בדיקת המנוע",
                    subtitle = "כמה טוב הוא מנחש מה באמת הושמע אחר כך"
                )
            }
            item {
                Column(modifier = Modifier.padding(horizontal = gutter)) {
                    Text(
                        "עובר על ההיסטוריה ושואל, לכל מעבר בין שני שירים, באיזה מקום " +
                            "מכל הספרייה המנוע היה מדרג את השיר שבאמת בא אחריו. " +
                            "שינוי באלגוריתם שמשפר — מעלה את המספרים האלה.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    val e = evaluation
                    if (e != null) {
                        Spacer(Modifier.height(10.dp))
                        Stat("מעברים שנבדקו", "${e.pairs}")
                        Stat(
                            "בעשירייה הראשונה",
                            "${(e.recallAt10 * 100).toInt()}% " +
                                "(אקראי: ${(e.randomRecallAt10 * 100).toInt()}%)"
                        )
                        Stat(
                            "בחמישים הראשונים",
                            "${(e.recallAt50 * 100).toInt()}% " +
                                "(אקראי: ${(e.randomRecallAt50 * 100).toInt()}%)"
                        )
                        Stat("דירוג חציוני", "${e.medianRank} מתוך ${e.librarySize}")
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "המספרים אופטימיים: הסטטיסטיקה שהמנוע מדרג לפיה כוללת כבר " +
                                "את ההשמעות שהוא מנסה לנחש. הם מוטים באותו אופן בכל " +
                                "ריצה, ולכן ההשוואה בין שתי ריצות תקפה גם אם אף אחת " +
                                "מהן אינה הערכה נקייה.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextTertiary
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { vm.evaluateEngine() },
                        enabled = !busy,
                        colors = ButtonDefaults.buttonColors(containerColor = Accent)
                    ) { Text("בדוק עכשיו") }
                }
            }

            item { SectionHeader(title = "על האפליקציה") }
            item {
                Column(modifier = Modifier.padding(horizontal = gutter)) {
                    Stat("גרסה", BuildConfig.VERSION_NAME)
                    Stat("מספר בנייה", "${BuildConfig.VERSION_CODE}")
                    if (BuildConfig.GIT_SHA.isNotBlank()) {
                        Stat("קומיט", BuildConfig.GIT_SHA)
                    }
                    if (BuildConfig.VERSION_NAME.endsWith("-dev")) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "בנייה מקומית — לא נבנתה דרך GitHub Actions, ולכן אין לה " +
                                "מספר בנייה שאפשר להשוות אליו.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }

            // Where the files went. A library that comes up short is otherwise
            // a mystery from the inside - the filters are invisible and each
            // one of them can be the whole explanation.
            item {
                SectionHeader(
                    title = "הסריקה האחרונה",
                    subtitle = "כמה קבצים נמצאו במכשיר, וכמה סוננו ולמה"
                )
            }
            item {
                val s = scan
                Column(modifier = Modifier.padding(horizontal = gutter)) {
                    if (s == null) {
                        Text(
                            "עוד לא רצה סריקה במחזור הנוכחי של האפליקציה.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    } else {
                        Stat("נמצאו במכשיר", "${s.onDevice}")
                        Stat("נכנסו לספרייה", "${s.kept}")
                        if (s.tooShort > 0) {
                            Stat("סוננו כקצרים מדי", "${s.tooShort}")
                        }
                        if (s.inExcludedFolder > 0) {
                            Stat("בתיקייה מוחרגת", "${s.inExcludedFolder}")
                        }
                        if (s.looksLikeRecording > 0) {
                            Stat("זוהו כהקלטות", "${s.looksLikeRecording}")
                        }
                        if (s.onDevice == 0) {
                            Text(
                                "המכשיר לא החזיר אף קובץ. בדרך כלל זה אומר שהמערכת עוד " +
                                    "לא סיימה לאנדקס את הקבצים, או שיש קובץ .nomedia " +
                                    "בתיקייה שלהם.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        } else if (s.kept < s.onDevice) {
                            Text(
                                "ההפרש הוא בדיוק מה שהמסננים למעלה הסירו. כל אחד מהם " +
                                    "ניתן לכיבוי או לשינוי בהגדרות.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }

            item { SectionHeader(title = "מה המנוע יודע עליך") }
            item {
                val r = report
                Column(modifier = Modifier.padding(horizontal = gutter)) {
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

    if (separationsOpen) {
        SeparationDialog(
            initial = separations,
            onDismiss = { separationsOpen = false },
            onApply = {
                separations = it
                vm.setStyleSeparations(it)
            }
        )
    }
}

/**
 * Which styles are never to share a mix.
 *
 * Free text rather than a grid of checkboxes: the styles are free text
 * themselves, the rules are a handful of lines, and a matrix of every style
 * against every other would be a hundred and fifty boxes to answer a question
 * most people have one opinion about.
 */
@Composable
private fun SeparationDialog(
    initial: String,
    onDismiss: () -> Unit,
    onApply: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        title = { Text("סגנונות שלא יתערבבו") },
        text = {
            Column {
                Text(
                    "שורה לכל כלל, והסגנונות בתוך השורה מופרדים בפסיק. " +
                        "סגנונות שנמצאים באותה שורה לא יופיעו יחד באותו מיקס, רדיו או המשך תור.\n\n" +
                        "למשל:\nחסידי, ישראלי\nילדים, חזנות\n\n" +
                        "הכלל חל על התגיות שאתה נתת — לשיר עצמו או לאמן שלו. " +
                        "שירים בלי תגיות לא מושפעים.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 200.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onApply(text.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n"))
                onDismiss()
            }) { Text("שמור", color = Accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("ביטול", color = TextSecondary) }
        }
    )
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

/** The header that folds a whole area of settings away behind one tap. */
@Composable
private fun SectionToggleRow(
    title: String,
    subtitle: String,
    open: Boolean,
    onToggle: () -> Unit
) {
    val gutter = rememberMetrics().gutter
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = gutter, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
        Button(
            onClick = onToggle,
            colors = ButtonDefaults.buttonColors(containerColor = Accent)
        ) { Text(if (open) "סגור" else "פתח") }
    }
}
