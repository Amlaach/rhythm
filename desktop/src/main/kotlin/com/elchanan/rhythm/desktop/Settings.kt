package com.elchanan.rhythm.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.elchanan.rhythm.engine.ActionPlacement
import com.elchanan.rhythm.engine.EngineTuning
import com.elchanan.rhythm.engine.PlayerAction
import com.elchanan.rhythm.engine.ShelfKind
import com.elchanan.rhythm.engine.StyleLearning
import com.elchanan.rhythm.engine.TasteReport
import com.elchanan.rhythm.ui.theme.Accent
import com.elchanan.rhythm.ui.theme.AppBackground
import com.elchanan.rhythm.ui.theme.Bg
import com.elchanan.rhythm.ui.theme.Surface1
import com.elchanan.rhythm.ui.theme.Surface2
import com.elchanan.rhythm.ui.theme.TextPrimary
import com.elchanan.rhythm.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The settings, reached from the home screen exactly as on the phone.
 *
 * There is no settings tab and there should not be: four tabs are the app,
 * and a fifth for something opened twice a year would take a quarter of the
 * bar. The gear is in the top corner of the home screen, where it is on the
 * phone, and the two deeper screens - the player and the algorithm - open
 * from here rather than sitting in the bar.
 */
@Composable
internal fun SettingsScreen(
    prefs: Prefs,
    songs: Int,
    analysed: Int,
    ratedArtists: Int,
    taggedArtists: Int,
    liked: Int,
    played: Int,
    folders: List<String>,
    scanning: Boolean,
    analysing: Boolean,
    onBack: () -> Unit,
    onOpenPlayerSettings: () -> Unit,
    onOpenAlgorithm: () -> Unit,
    onOpenTags: () -> Unit,
    onPickFolder: () -> Unit,
    onRescan: () -> Unit,
    onAnalyze: () -> Unit,
    onResetAnalysis: () -> Unit,
    onResetStats: () -> Unit,
    onPickLyricsFolder: () -> Unit,
    onImportPlaylist: () -> Unit,
    onExportPlaylists: () -> Unit,
    busy: Boolean,
    engineReport: String,
    taste: TasteReport?,
    onEvaluate: () -> Unit,
    onExcludedChanged: () -> Unit,
    onShelvesChanged: () -> Unit
) {
    // Read once into state so a flipped switch moves under the finger. Every
    // one of these writes through to the database as it changes; the state is
    // only here because a composable cannot re-read SQLite to redraw itself.
    var autoAnalyze by remember { mutableStateOf(prefs.autoAnalyze) }
    var hideDuplicates by remember { mutableStateOf(prefs.hideDuplicates) }
    var searchPersonalized by remember { mutableStateOf(prefs.searchPersonalized) }
    var shelvesOpen by remember { mutableStateOf(false) }
    var foldersOpen by remember { mutableStateOf(false) }
    var separationsOpen by remember { mutableStateOf(false) }
    var excluded by remember { mutableStateOf(prefs.excludedFolders) }
    var separations by remember { mutableStateOf(prefs.styleSeparations) }
    var folderTree by remember { mutableStateOf(prefs.folderTree) }
    var stripForeign by remember { mutableStateOf(prefs.tagStripForeign) }
    var writeTags by remember { mutableStateOf(prefs.writeTagsToFiles) }
    var resumeSpoken by remember { mutableStateOf(prefs.resumeSpoken) }
    var skipRecordings by remember { mutableStateOf(prefs.skipRecordings) }
    var pinMoodRow by remember { mutableStateOf(prefs.pinMoodRow) }
    var firstTab by remember { mutableStateOf(prefs.libraryFirstTab) }
    var minDuration by remember { mutableStateOf(prefs.minDurationSec.toFloat()) }
    val lyricsFolder = prefs.lyricsFolder

    Column(modifier = Modifier.fillMaxSize().background(AppBackground)) {
        DetailTopBar(title = "הגדרות", onBack = onBack)
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {

            item {
                SettingSection("הגדרות דף הבית", "אילו מדפים מופיעים, ובאיזה סדר הם נבנים")
                ActionRow(
                    title = "מדפים במסך הבית",
                    subtitle = "כיבוי מדף לא מוחק כלום — הוא פשוט מפסיק להופיע, " +
                        "וחוזר כמו שהיה כשמדליקים אותו בחזרה",
                    action = "ערוך",
                    enabled = true,
                    primary = false,
                    onClick = { shelvesOpen = true }
                )
            }

            item {
                SettingSection("הגדרות הספרייה", "מה נפתח ראשון, וכפילויות")
                Text(
                    "מה נפתח ראשון",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = GUTTER, vertical = 6.dp)
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = GUTTER),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(LIBRARY_TAB_CHOICES) { (name, label) ->
                        Chip(label = label, selected = firstTab == name) {
                            firstTab = name
                            prefs.libraryFirstTab = name
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                SwitchRow(
                    title = "הסתרת כפילויות",
                    subtitle = "אותו שיר שהורד פעמיים תופס מקום אחד ולא שניים",
                    checked = hideDuplicates
                ) {
                    hideDuplicates = it
                    prefs.hideDuplicates = it
                }
                SwitchRow(
                    title = "שורת מצבי רוח בדף הבית",
                    subtitle = "רגוע, קצבי, ריכוז — מסננים על מה שנמדד",
                    checked = pinMoodRow
                ) {
                    pinMoodRow = it
                    prefs.pinMoodRow = it
                }
            }

            item {
                SettingSection("הגדרות החיפוש", "איך תוצאות מסודרות")
                SwitchRow(
                    title = "התאמה אישית בתוצאות",
                    subtitle = "מה שאתה מנגן הרבה עולה למעלה בתוצאות",
                    checked = searchPersonalized
                ) {
                    searchPersonalized = it
                    prefs.searchPersonalized = it
                }
            }

            item {
                LinkRow(
                    title = "הגדרות הנגן והשמע",
                    subtitle = "אקולייזר, רדיו אינסופי, פתיחת הנגן",
                    onClick = onOpenPlayerSettings
                )
                LinkRow(
                    title = "הגדרות האלגוריתם",
                    subtitle = "חמש המשקולות שקובעות מה עולה למעלה",
                    onClick = onOpenAlgorithm
                )
                LinkRow(
                    title = "תיקון תגיות",
                    subtitle = "מסדר שמות של קבצים שהורדו מהאינטרנט",
                    onClick = onOpenTags
                )
            }

            item {
                SettingSection("רשימות השמעה", "m3u ו־pls, כמו בטלפון")
                ActionRow(
                    title = "ייבוא רשימת השמעה",
                    subtitle = "קורא m3u או pls ומתאים אותו לשירים שבספרייה. " +
                        "מה שלא נמצא נספר ונאמר, ולא נעלם בשקט",
                    action = "בחר קובץ",
                    enabled = true,
                    primary = true,
                    onClick = onImportPlaylist
                )
                ActionRow(
                    title = "ייצוא כל הרשימות",
                    subtitle = "כותב קובץ m3u לכל רשימה, כולל האהובים",
                    action = "בחר תיקייה",
                    enabled = true,
                    primary = false,
                    onClick = onExportPlaylists
                )
            }

            item {
                SwitchRow(
                    title = "הסרת טקסט באנגלית",
                    subtitle = "מוריד קרדיטים בסוגריים ושאריות של כותרת מיוטיוב " +
                        "משמות השירים בתיקון התגיות",
                    checked = stripForeign
                ) {
                    stripForeign = it
                    prefs.tagStripForeign = it
                }
                SwitchRow(
                    title = "כתיבת התיקון לקבצים",
                    subtitle = "התיקון נשמר תמיד כאן. זה כותב אותו גם לתוך הקובץ " +
                        "עצמו — שינוי שאי אפשר לבטל בלחיצה",
                    checked = writeTags
                ) {
                    writeTags = it
                    prefs.writeTagsToFiles = it
                }
            }

            item {
                SettingSection("ניתוח אודיו", "מדידת קצב, סולם, אנרגיה וגוון — הכל על המחשב")
                SwitchRow(
                    title = "ניתוח אוטומטי",
                    subtitle = "מודד כל קובץ חדש מיד אחרי סריקה",
                    checked = autoAnalyze
                ) {
                    autoAnalyze = it
                    prefs.autoAnalyze = it
                }
                Text(
                    "$analysed מתוך $songs נותחו",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(horizontal = GUTTER)
                )
                Row(
                    modifier = Modifier.padding(horizontal = GUTTER, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onAnalyze,
                        enabled = !analysing && analysed < songs,
                        colors = ButtonDefaults.buttonColors(containerColor = Accent)
                    ) { Text("נתח עכשיו") }
                    OutlinedButton(onClick = onResetAnalysis, enabled = !analysing) {
                        Text("אפס ניתוח")
                    }
                }
            }

            item {
                SettingSection("הספרייה", null)
                Text(
                    text = if (folders.isEmpty()) {
                        "לא נבחרה תיקייה"
                    } else {
                        folders.joinToString("\n")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(horizontal = GUTTER)
                )
                Row(
                    modifier = Modifier.padding(horizontal = GUTTER, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onPickFolder,
                        enabled = !scanning,
                        colors = ButtonDefaults.buttonColors(containerColor = Accent)
                    ) { Text("בחר תיקייה") }
                    if (folders.isNotEmpty()) {
                        OutlinedButton(onClick = onRescan, enabled = !scanning) {
                            Text("סרוק מחדש")
                        }
                    }
                }
                Text(
                    "אורך מינימלי לשיר: ${minDuration.toInt()} שניות",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = GUTTER)
                )
                Text(
                    "רינגטונים והתראות יושבים באותן תיקיות כמו המוזיקה",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(horizontal = GUTTER)
                )
                Slider(
                    value = minDuration,
                    valueRange = 0f..180f,
                    onValueChange = { minDuration = it },
                    onValueChangeFinished = { prefs.minDurationSec = minDuration.toInt() },
                    modifier = Modifier.padding(horizontal = GUTTER)
                )
                SwitchRow(
                    title = "דלג על הקלטות",
                    subtitle = "מה שנשמע כמו הקלטה של חדר ולא כמו תקליט",
                    checked = skipRecordings
                ) {
                    skipRecordings = it
                    prefs.skipRecordings = it
                }
                SwitchRow(
                    title = "המשך הרצאות מהמקום",
                    subtitle = "הקלטה ארוכה נפתחת במקום שבו הופסקה",
                    checked = resumeSpoken
                ) {
                    resumeSpoken = it
                    prefs.resumeSpoken = it
                }
                SwitchRow(
                    title = "תיקיות בתוך תיקיות",
                    subtitle = "מראה את התיקיות כמו שהן יושבות על הדיסק, " +
                        "ולא כרשימה שטוחה אחת",
                    checked = folderTree
                ) {
                    folderTree = it
                    prefs.folderTree = it
                }
                // Not a switch, because what gets left out is a list of
                // whatever this particular disk happens to have on it.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = GUTTER, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("תיקיות שלא ייסרקו", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = excluded.joinToString(", ").ifBlank { "כרגע נסרק הכל" },
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Button(
                        onClick = { foldersOpen = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Accent)
                    ) { Text("ערוך") }
                }
            }

            item {
                SettingSection("מילות שיר", "נקראות מתגיות הקובץ, ומקבצי LRC אם נבחרה תיקייה")
                Text(
                    text = lyricsFolder.ifEmpty { "לא נבחרה תיקייה" },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(horizontal = GUTTER)
                )
                Row(
                    modifier = Modifier.padding(horizontal = GUTTER, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onPickLyricsFolder,
                        colors = ButtonDefaults.buttonColors(containerColor = Accent)
                    ) { Text("בחר תיקייה") }
                    if (lyricsFolder.isNotEmpty()) {
                        OutlinedButton(onClick = { prefs.lyricsFolder = "" }) { Text("נקה") }
                    }
                }
            }

            item {
                SettingSection(
                    "סגנונות שלא יתערבבו",
                    "מה שלא נשמע טוב אחד אחרי השני, שורה לכל כלל"
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = GUTTER, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = separations.lines()
                            .filter { it.isNotBlank() }
                            .joinToString(" · ")
                            .ifBlank { "אין כרגע הפרדות" },
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = { separationsOpen = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Accent)
                    ) { Text("ערוך") }
                }
            }

            item {
                SettingSection("בדיקת המנוע", "כמה טוב הוא מנחש מה באמת הושמע אחר כך")
                Text(
                    text = engineReport.ifBlank {
                        "בודק את ההמלצות מול ההיסטוריה האמיתית שלך: לוקח מה ששמעת, " +
                            "ושואל אם המנוע היה מציע את השיר הבא."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(horizontal = GUTTER)
                )
                Row(modifier = Modifier.padding(horizontal = GUTTER, vertical = 10.dp)) {
                    Button(
                        onClick = onEvaluate,
                        enabled = !busy,
                        colors = ButtonDefaults.buttonColors(containerColor = Accent)
                    ) { Text("בדוק עכשיו") }
                }
            }

            item {
                SettingSection("על האפליקציה", null)
                Text(
                    "Rhythm — נגן מוזיקה עם מנוע המלצות מקומי.\n" +
                        "הכל קורה על המחשב הזה. שום דבר לא נשלח לשום מקום.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(horizontal = GUTTER, vertical = 6.dp)
                )
            }

            item {
                SettingSection("הסריקה האחרונה", "כמה קבצים נמצאו, ומתי")
                Fact("קבצים שנמצאו", "${prefs.lastScanCount}")
                Fact("שירים בספרייה אחרי סינון", "$songs")
                Fact("נסרק לאחרונה", lastScanLabel(prefs.lastScanAt))
            }

            item {
                SettingSection("מה המנוע יודע עליך", null)
                Fact("שירים בספרייה", "$songs")
                Fact("שירים שנותחו", "$analysed מתוך $songs")
                Fact("אמנים שדורגו", "$ratedArtists")
                Fact("אמנים עם סגנון", "$taggedArtists")
                Fact("שירים עם לייק", "$liked")
                Fact("סך הנגינות", "$played")
                Spacer(Modifier.height(10.dp))
                // Last, and outlined rather than filled. It throws away every
                // rating, like and play count in the database, which is the
                // one thing here that months of listening cannot be got back.
                val r = taste
                if (r != null) {
                    Fact("קשרים סימטריים שנלמדו", "${r.learnedPairs}")
                    Fact("מעברים מכוונים שנלמדו", "${r.learnedTransitions}")
                    Fact(
                        "קצב חציוני בספרייה",
                        if (r.medianBpm > 0) "${r.medianBpm} BPM" else "—"
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "הסגנונות המובילים שלך",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = GUTTER)
                    )
                    Spacer(Modifier.height(4.dp))
                    if (r.topStyles.isEmpty()) {
                        Text(
                            "אחרי שתדרג כמה אמנים ותסמן סגנונות, כאן יופיע פרופיל הטעם",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            modifier = Modifier.padding(horizontal = GUTTER)
                        )
                    } else {
                        for ((style, weight) in r.topStyles) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = GUTTER, vertical = 3.dp)
                            ) {
                                Text(style, style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.weight(1f))
                                Text(
                                    String.format(Locale.ROOT, "%.2f", weight),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onResetStats,
                    modifier = Modifier.padding(horizontal = GUTTER)
                ) { Text("אפס את כל ההיסטוריה", color = Accent) }
            }

        }
    }

    if (foldersOpen) {
        LineListDialog(
            title = "תיקיות שלא ייסרקו",
            hint = "חלק משם הנתיב, שורה לכל תיקייה. כל קובץ שהנתיב שלו " +
                "מכיל את הטקסט הזה לא ייכנס לספרייה.\n\nלמשל:\nWhatsApp\nRecordings\nRingtones",
            initial = excluded.joinToString("\n"),
            confirm = "שמור וסרוק",
            onDismiss = { foldersOpen = false },
            onSave = { text ->
                val list = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
                excluded = list
                prefs.excludedFolders = list
                foldersOpen = false
                // A folder that was excluded is still in the database until
                // something goes and looks again, so the rescan is part of
                // saving rather than something to remember to do afterwards.
                onExcludedChanged()
            }
        )
    }

    if (separationsOpen) {
        LineListDialog(
            title = "סגנונות שלא יתערבבו",
            hint = "שורה לכל כלל, והסגנונות בתוכה מופרדים בפסיק. " +
                "שני סגנונות באותה שורה לא יופיעו יחד באותו מיקס.\n\nלמשל:\n" +
                "חסידי, מזרחי\nקלאסי, רוק",
            initial = separations,
            confirm = "שמור",
            onDismiss = { separationsOpen = false },
            onSave = { text ->
                separations = text
                prefs.styleSeparations = text
                separationsOpen = false
            }
        )
    }

    if (shelvesOpen) {
        ShelfDialog(
            prefs = prefs,
            onChange = onShelvesChanged,
            onDismiss = { shelvesOpen = false }
        )
    }
}

/**
 * Which shelves the home screen may build.
 *
 * Turning one off deletes nothing - it stops appearing, and comes back as it
 * was when it is switched on again. Said in the subtitle because a switch in a
 * settings screen that looks like it might throw work away is one people leave
 * alone.
 */
@Composable
private fun ShelfDialog(prefs: Prefs, onChange: () -> Unit, onDismiss: () -> Unit) {
    var shelves by remember { mutableStateOf(prefs.homeShelves) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        title = { Text("מדפים במסך הבית") },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                items(ShelfKind.entries.toList()) { shelf ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(shelf.label, style = MaterialTheme.typography.bodyLarge)
                            if (shelf.about.isNotEmpty()) {
                                Text(
                                    shelf.about,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                        }
                        Switch(
                            checked = shelf.key in shelves,
                            onCheckedChange = { on ->
                                shelves = if (on) shelves + shelf.key else shelves - shelf.key
                                prefs.homeShelves = shelves
                                onChange()
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Bg,
                                checkedTrackColor = Accent,
                                uncheckedThumbColor = TextSecondary,
                                uncheckedTrackColor = Surface2
                            )
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("סגור", color = TextSecondary) }
        }
    )
}

/** "היום", "אתמול", or a date - which is all anyone reads off a scan report. */
private fun lastScanLabel(at: Long): String {
    if (at <= 0L) return "עוד לא נסרק"
    val days = (System.currentTimeMillis() - at) / 86_400_000L
    return when (days) {
        0L -> "היום"
        1L -> "אתמול"
        else -> SimpleDateFormat("d/M/yyyy", Locale.forLanguageTag("he")).format(Date(at))
    }
}

/** The library tabs someone can choose to open on, by their internal name. */
private val LIBRARY_TAB_CHOICES = listOf(
    "PLAYLISTS" to "פלייליסטים",
    "FOLDERS" to "תיקיות",
    "LIKED" to "אהובים",
    "ARTISTS" to "אמנים",
    "SONGS" to "שירים",
    "ALBUMS" to "אלבומים"
)

/**
 * The player and the sound, which on the phone is one screen and here is too.
 *
 * The equaliser opens from here rather than living inside it. Thirty one
 * faders, a response curve and a row of presets are a surface of their own,
 * and the phone gives them a screen of their own for the same reason.
 */
@Composable
internal fun PlayerSettingsScreen(
    prefs: Prefs,
    onBack: () -> Unit,
    onOpenEqualizer: () -> Unit
) {
    var openPlayerOnPlay by remember { mutableStateOf(prefs.openPlayerOnPlay) }
    var autoRadio by remember { mutableStateOf(prefs.autoRadio) }
    var resumePrompt by remember { mutableStateOf(prefs.resumePrompt) }
    var normalizeVolume by remember { mutableStateOf(prefs.normalizeVolume) }
    var tapArtwork by remember { mutableStateOf(prefs.tapArtworkToggles) }
    var arrangementOpen by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(AppBackground)) {
        DetailTopBar(title = "הגדרות הנגן והשמע", onBack = onBack)
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                SettingSection("הנגן", null)
                ActionRow(
                    title = "סידור הכפתורים והתפריט",
                    subtitle = "לאיזו פעולה יהיה כפתור משלה במסך הנגן, לאיזו פריט " +
                        "בתפריט שלוש הנקודות, ואיזו תוסתר",
                    action = "פתח",
                    enabled = true,
                    primary = false,
                    onClick = { arrangementOpen = true }
                )
                SwitchRow(
                    title = "לחיצה על התמונה עוצרת וממשיכה",
                    subtitle = "התמונה הגדולה במסך הנגן היא הדבר הכי קל לפגוע בו " +
                        "בלי להסתכל",
                    checked = tapArtwork
                ) {
                    tapArtwork = it
                    prefs.tapArtworkToggles = it
                }
                SwitchRow(
                    title = "פתיחת הנגן בהשמעה",
                    subtitle = "המסך המלא נפתח ברגע שמשהו מתחיל",
                    checked = openPlayerOnPlay
                ) {
                    openPlayerOnPlay = it
                    prefs.openPlayerOnPlay = it
                }
                SwitchRow(
                    title = "רדיו אינסופי",
                    subtitle = "כשהתור נגמר, המנוע ממשיך עם מה שמתאים",
                    checked = autoRadio
                ) {
                    autoRadio = it
                    prefs.autoRadio = it
                }
                SwitchRow(
                    title = "הצעה להמשיך מהמיקום האחרון",
                    subtitle = "כששיר נעזב באמצע ופותחים אותו שוב, מוצגת לכמה שניות " +
                        "הצעה לחזור לנקודה — עם הזמן המדויק. נשמר רק לשירים " +
                        "שנעזבו אחרי חצי דקה ולפני הסוף",
                    checked = resumePrompt
                ) {
                    resumePrompt = it
                    prefs.resumePrompt = it
                }
                SwitchRow(
                    title = "איזון עוצמה בין שירים",
                    subtitle = "מנמיך את השירים החזקים במיוחד כדי שלא תצטרך לגעת " +
                        "בעוצמה בכל מעבר. דורש שהשירים ינותחו קודם",
                    checked = normalizeVolume
                ) {
                    normalizeVolume = it
                    prefs.normalizeVolume = it
                }
                // No "pause when the volume reaches zero". On the phone that
                // watches the system media stream, which the volume rocker
                // moves; here the operating system owns the mixer and does
                // not tell the app when someone drags it to the bottom. A
                // switch that cannot see what it claims to watch is worse
                // than the absence of one.
            }
            item {
                SettingSection("אקולייזר", "31 תדרים, נשמר בין הפעלות")
                ActionRow(
                    title = "אקולייזר",
                    subtitle = "31 תדרים עם עקומת התגובה שהשמע באמת מקבל, " +
                        "מוכנים מראש, ועוצמה כללית",
                    action = "פתח",
                    enabled = true,
                    primary = false,
                    onClick = onOpenEqualizer
                )
            }
        }
    }
    if (arrangementOpen) {
        PlayerActionsDialog(prefs = prefs, onDismiss = { arrangementOpen = false })
    }
}

/**
 * Where every action sits: its own button on the player, an item in the
 * three dot menu, or nowhere at all.
 *
 * Every choice is written straight through. There is nothing to confirm - a
 * placement that moved without the player following it would be a lie about
 * what the app is doing.
 */
@Composable
private fun PlayerActionsDialog(prefs: Prefs, onDismiss: () -> Unit) {
    var actions by remember { mutableStateOf(prefs.playerActions) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        title = { Text("סידור הכפתורים והתפריט") },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 460.dp)) {
                item {
                    Text(
                        "לכל פעולה אפשר לבחור: כפתור משלה במסך הנגן, פריט בתפריט " +
                            "השלוש נקודות, או מוסתרת לגמרי.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(Modifier.height(10.dp))
                }
                items(DESKTOP_PLAYER_ACTIONS) { action ->
                    val current = PlayerAction.placementOf(actions, action)
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
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
                            for (choice in ActionPlacement.entries) {
                                Chip(label = choice.label, selected = current == choice) {
                                    actions = actions + (action.key to choice.name)
                                    prefs.playerActions = actions
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("סגור", color = Accent) } }
    )
}

/**
 * The five weights, with what each one actually does written under it.
 *
 * Every slider rebuilds the shelves when it is let go. That is the point: a
 * weight that does not visibly change the home screen is a weight nobody can
 * tell they have changed, and these are exactly the settings people move once
 * and never look at again if they see nothing happen.
 */
@Composable
internal fun AlgorithmSettingsScreen(
    tuning: EngineTuning,
    learning: Boolean,
    learningReport: String?,
    busy: Boolean,
    onChange: (EngineTuning) -> Unit,
    onLearn: () -> Unit,
    onClearLearned: () -> Unit,
    onBack: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().background(AppBackground)) {
        DetailTopBar(title = "הגדרות האלגוריתם", onBack = onBack)
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Column(modifier = Modifier.padding(horizontal = GUTTER)) {
                    Knob(
                        "גילוי מול מוכר", tuning.discovery, 0f..1f,
                        "ככל שגבוה יותר, יופיעו יותר שירים שלא שמעת"
                    ) { onChange(tuning.copy(discovery = it)) }
                    Knob(
                        "משקל דירוג האמן", tuning.artistWeight, 0f..2f,
                        "כמה הדירוג שנתת לאמן משפיע על השירים שלו"
                    ) { onChange(tuning.copy(artistWeight = it)) }
                    Knob(
                        "משקל הסגנון", tuning.styleWeight, 0f..2f,
                        "כמה התאמת הסגנון מושכת שיר למעלה"
                    ) { onChange(tuning.copy(styleWeight = it)) }
                    Knob(
                        "מניעת חזרתיות", tuning.repeatGuard, 0f..2f,
                        "ככל שגבוה יותר, שיר שהתנגן לאחרונה ירד בדירוג"
                    ) { onChange(tuning.copy(repeatGuard = it)) }
                    Knob(
                        "משקל הדמיון האקוסטי", tuning.acousticWeight, 0f..2f,
                        "כמה הצליל עצמו קובע, לעומת מה שכתוב על השיר"
                    ) { onChange(tuning.copy(acousticWeight = it)) }
                }
            }
            item {
                SettingSection("למידת סגנונות", null)
                ActionRow(
                    title = "למידת סגנונות מהספרייה",
                    subtitle = "לומד איך הסגנונות שהגדרת נשמעים — מהשירים של האמנים " +
                        "שתייגת — ומשלים תגיות לשירים שלא תויגו. האפליקציה בודקת " +
                        "על אמנים שלא השתתפו באימון, וסופרת גם תגיות שגויות וחסרות. " +
                        "אם הבדיקה אינה מספקת, לא משתנות תגיות " + StyleLearning.requirement(),
                    action = if (learning) "לומד…" else "למד",
                    enabled = !busy && !learning,
                    primary = true,
                    onClick = onLearn
                )
                learningReport?.let { report ->
                    Text(
                        report,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = GUTTER, vertical = 12.dp),
                        color = TextPrimary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                ActionRow(
                    title = "ניקוי התגיות שנוחשו",
                    subtitle = "מוחק רק תגיות שהאפליקציה הוסיפה בעצמה. התגיות שהקלדת " +
                        "נשארות. שימושי אחרי שתייגת עוד אמנים — הלמידה תהיה טובה " +
                        "יותר, והניחושים הישנים לא יחסמו אותה",
                    action = "נקה",
                    enabled = !busy,
                    primary = false,
                    onClick = onClearLearned
                )
            }
        }
    }
}

@Composable
private fun ActionRow(
    title: String,
    subtitle: String,
    action: String,
    enabled: Boolean,
    primary: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = GUTTER, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
        Spacer(Modifier.width(12.dp))
        if (primary) {
            Button(
                onClick = onClick,
                enabled = enabled,
                colors = ButtonDefaults.buttonColors(containerColor = Accent)
            ) { Text(action) }
        } else {
            OutlinedButton(onClick = onClick, enabled = enabled) { Text(action) }
        }
    }
}

@Composable
private fun SettingSection(title: String, subtitle: String?) {
    Column(modifier = Modifier.padding(start = GUTTER, end = GUTTER, top = 22.dp, bottom = 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Accent)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
    }
}

@Composable
private fun LinkRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = GUTTER, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Surface1)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
        Text("פתח", style = MaterialTheme.typography.labelLarge, color = Accent)
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = GUTTER, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Bg,
                checkedTrackColor = Accent,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = Surface2
            )
        )
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = GUTTER, vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
    }
}

@Composable
private fun Knob(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    hint: String,
    onDone: (Float) -> Unit
) {
    // The slider follows the pointer locally and the engine is only rebuilt
    // when it is let go. Rebuilding on every pixel would score the whole
    // library a hundred times for one drag.
    var live by remember(value) { mutableStateOf(value) }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        Slider(
            value = live,
            valueRange = range,
            onValueChange = { live = it },
            onValueChangeFinished = { onDone(live) }
        )
    }
}

/**
 * The actions this build can actually carry out.
 *
 * Two of the phone's are missing rather than hidden, and the difference
 * matters: a hidden action is one the user can switch back on, and these two
 * would do nothing if they did.
 *
 * Speed needs the decoder to resample while keeping the pitch. ExoPlayer
 * does that on the phone; javax.sound hands over raw PCM and a line to pour
 * it into, so the same thing here means a time stretch written by hand -
 * real work, and not work this screen should pretend is already done.
 *
 * Share is Android's own idea. Windows has no equivalent to hand a file to
 * whichever application the user picks from a sheet, and a button that opens
 * a file manager instead is a different feature wearing the same name.
 */
internal val DESKTOP_PLAYER_ACTIONS: List<PlayerAction> =
    PlayerAction.entries.filterNot {
        it == PlayerAction.SPEED || it == PlayerAction.SHARE
    }

/**
 * A dialog holding a list written one item per line.
 *
 * Used for the two settings that are lists of free text rather than choices:
 * the folders to leave out of a scan, and the pairs of styles that should
 * never share a mix. A multi-line box rather than an add-one-at-a-time list,
 * because these are edited rarely and in bulk - usually pasted in once and
 * then left alone for months - and a text box can be read, corrected and
 * reordered in one go where a list of rows cannot.
 */
@Composable
private fun LineListDialog(
    title: String,
    hint: String,
    initial: String,
    confirm: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        title = { Text(title) },
        text = {
            Column {
                Text(hint, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 220.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }) { Text(confirm, color = Accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("ביטול", color = TextSecondary) }
        }
    )
}
