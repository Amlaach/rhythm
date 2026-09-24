package com.elchanan.rhythm.desktop

import androidx.compose.runtime.LaunchedEffect
import com.elchanan.rhythm.data.db.SongEntity
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
import com.elchanan.rhythm.ui.theme.Text
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
import com.elchanan.rhythm.engine.Listening
import com.elchanan.rhythm.engine.StyleLearning
import com.elchanan.rhythm.engine.TasteReport
import com.elchanan.rhythm.ui.theme.Accent
import com.elchanan.rhythm.ui.theme.AppBackground
import com.elchanan.rhythm.ui.theme.Bg
import com.elchanan.rhythm.ui.theme.Surface1
import com.elchanan.rhythm.ui.theme.Surface2
import com.elchanan.rhythm.ui.theme.TextPrimary
import com.elchanan.rhythm.ui.theme.TextSecondary
import com.elchanan.rhythm.ui.theme.UiLanguage
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
/**
 * Which group of settings is on screen.
 *
 * The phone's settings are a short list of doors, each opening onto one
 * subject, and this side was a single column with everything in it - shelves
 * next to audio analysis next to what the engine knows about you. The same
 * seven doors now, and the same words on them.
 *
 * One screen with a page rather than five screens, because the settings share
 * a great deal of state and every one of them would otherwise need the same
 * two dozen parameters threaded into it.
 */
internal enum class SettingsPage { DOORS, HOME, LIBRARY, ENGINE, TAGS, PORTING, ABOUT }

/** The title above each page, and the words on the door that opens it. */
internal fun titleOf(page: SettingsPage): String = when (page) {
    SettingsPage.DOORS -> "הגדרות"
    SettingsPage.HOME -> "דף הבית ותצוגה"
    SettingsPage.LIBRARY -> "ספרייה וסריקה"
    SettingsPage.ENGINE -> "המנוע"
    SettingsPage.TAGS -> "תגיות ומילות שיר"
    SettingsPage.PORTING -> "ייבוא וייצוא"
    SettingsPage.ABOUT -> "מידע ואבחון"
}

@Composable
internal fun SettingsScreen(
    page: SettingsPage = SettingsPage.DOORS,
    onOpenPage: (SettingsPage) -> Unit = {},
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
    onPickLyricsFolder: () -> Unit,
    onImportPlaylist: () -> Unit,
    onImportYouTubeMusic: () -> Unit,
    onImportPlayCounts: () -> Unit,
    onExportPlaylists: () -> Unit,
    onExportAnalysis: () -> Unit,
    onExportCatalog: () -> Unit,
    /** The recap and the feed refresh, which were buttons on the home screen. */
    onOpenRecap: () -> Unit = {},
    onRefreshFeed: () -> Unit = {},
    /** The songs hidden from the player, as their tags name them, and the way back. */
    hiddenSongs: List<SongEntity> = emptyList(),
    onUnhide: (List<Long>) -> Unit = {},
    onTitlesFromFiles: (Boolean) -> Unit = {},
    /** Whether the AI models loaded, in a sentence. */
    modelStatus: String,
    busy: Boolean,
    taste: TasteReport?,
    onExcludedChanged: () -> Unit,
    onShelvesChanged: () -> Unit
) {
    // Read once into state so a flipped switch moves under the finger. Every
    // one of these writes through to the database as it changes; the state is
    // only here because a composable cannot re-read SQLite to redraw itself.
    var autoAnalyze by remember { mutableStateOf(prefs.autoAnalyze) }
    var hideDuplicates by remember { mutableStateOf(prefs.hideDuplicates) }
    var shelvesOpen by remember { mutableStateOf(false) }
    var foldersOpen by remember { mutableStateOf(false) }
    var excluded by remember { mutableStateOf(prefs.excludedFolders) }
    var folderTree by remember { mutableStateOf(prefs.folderTree) }
    var stripForeign by remember { mutableStateOf(prefs.tagStripForeign) }
    var writeTags by remember { mutableStateOf(prefs.writeTagsToFiles) }
    var resumeSpoken by remember { mutableStateOf(prefs.resumeSpoken) }
    var skipRecordings by remember { mutableStateOf(prefs.skipRecordings) }
    var titlesFromFiles by remember { mutableStateOf(prefs.titlesFromFiles) }
    var hiddenOpen by remember { mutableStateOf(false) }
    var songMenuOpen by remember { mutableStateOf(false) }
    var pinMoodRow by remember { mutableStateOf(prefs.pinMoodRow) }
    var firstTab by remember { mutableStateOf(prefs.libraryFirstTab) }
    var minDuration by remember { mutableStateOf(prefs.minDurationSec.toFloat()) }
    val lyricsFolder = prefs.lyricsFolder

    Column(modifier = Modifier.fillMaxSize().background(AppBackground)) {
        DetailTopBar(title = titleOf(page), onBack = onBack)
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {

            // The doors. Short on purpose: the point of a list of subjects is
            // that it fits on one screen, so what you are looking for is
            // found by reading seven lines rather than by scrolling past
            // everything you are not looking for.
            if (page == SettingsPage.DOORS) {
                item {
                    LinkRow(
                        if (UiLanguage.english) "Language" else "שפה / Language",
                        if (UiLanguage.english) "English · Click to switch to Hebrew" else "עברית · לחצו כדי לעבור ל־English"
                    ) {
                        val choice = if (UiLanguage.english) "he" else "en"
                        prefs.language = choice
                        UiLanguage.code = choice
                    }
                    LinkRow(
                        "דף הבית ותצוגה",
                        "אילו מדפים מופיעים, מה נפתח ראשון, איך מוצגות התיקיות"
                    ) { onOpenPage(SettingsPage.HOME) }
                    // Once a chart on the home screen, here now, as on the phone.
                    LinkRow(
                        "הסיכום שלך",
                        "מה שמעת, כמה ומתי, והאמנים שחזרת אליהם",
                        onOpenRecap
                    )
                    LinkRow(
                        "ספרייה וסריקה",
                        "אילו קבצים נכנסים לספרייה, וניתוח האודיו"
                    ) { onOpenPage(SettingsPage.LIBRARY) }
                    LinkRow(
                        "נגן ושמע",
                        "אקולייזר, רדיו אינסופי, סידור הכפתורים, עוצמה",
                        onOpenPlayerSettings
                    )
                    LinkRow(
                        "המנוע",
                        "מה עולה למעלה בפיד ובחיפוש, ומה לא יתערבב",
                        onOpenAlgorithm
                    )
                    LinkRow(
                        "תגיות ומילות שיר",
                        "תיקון שמות אמנים, וכתיבה לתוך הקבצים"
                    ) { onOpenPage(SettingsPage.TAGS) }
                    LinkRow(
                        "ייבוא וייצוא",
                        "רשימות השמעה, והעברת הניתוח לטלפון"
                    ) { onOpenPage(SettingsPage.PORTING) }
                    LinkRow(
                        "מידע ואבחון",
                        "גרסה, מה הסריקה מצאה, ומה המנוע יודע עליך"
                    ) { onOpenPage(SettingsPage.ABOUT) }
                }
            }

            if (page == SettingsPage.TAGS) {
                item {
                    LinkRow(
                        "תיקון תגיות",
                        "מסדר שמות של קבצים שהורדו מהאינטרנט",
                        onOpenTags
                    )
                }
            }

            // The phone's page, in its order: shelves, what opens first,
            // folders in folders, duplicates, the mood row.
            if (page == SettingsPage.HOME) item {
                // The refresh that was a button on the home screen.
                ActionRow(
                    title = "רענון ההמלצות",
                    subtitle = "בונה את מסך הבית מחדש",
                    action = "רענן",
                    enabled = true,
                    primary = true,
                    onClick = onRefreshFeed
                )
                ActionRow(
                    title = "מדפים במסך הבית",
                    subtitle = "כיבוי מדף לא מוחק כלום — הוא פשוט מפסיק להופיע, " +
                        "וחוזר כמו שהיה כשמדליקים אותו בחזרה",
                    action = "ערוך",
                    enabled = true,
                    primary = false,
                    onClick = { shelvesOpen = true }
                )
                Text(
                    "מה נפתח ראשון בספרייה",
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
                    title = "תיקיות בתוך תיקיות",
                    subtitle = "מראה את התיקיות כמו שהן יושבות על הדיסק, " +
                        "ולא כרשימה שטוחה אחת",
                    checked = folderTree
                ) {
                    folderTree = it
                    prefs.folderTree = it
                }
                SwitchRow(
                    title = "הסתרת כפילויות",
                    subtitle = "אותו שיר שהורד פעמיים תופס מקום אחד ולא שניים",
                    checked = hideDuplicates
                ) {
                    hideDuplicates = it
                    prefs.hideDuplicates = it
                }
                SwitchRow(
                    title = "פס מצבי הרוח נשאר למעלה",
                    subtitle = "דלוק: הצ'יפים נשארים מתחת לכותרת והדף נגלל מתחתיהם. " +
                        "כבוי: הם נגללים למעלה יחד עם כל השאר",
                    checked = pinMoodRow
                ) {
                    pinMoodRow = it
                    prefs.pinMoodRow = it
                }
                ActionRow(
                    title = "סידור תפריט השיר",
                    subtitle = "מה מופיע בתפריט שלוש הנקודות של כל שיר, ובאיזה סדר",
                    action = "סדר",
                    enabled = true,
                    primary = false
                ) { songMenuOpen = true }
            }

            // The phone's page, in its order. The phone opens with importing
            // what this computer measured; here that is its other half.
            if (page == SettingsPage.PORTING) item {
                ActionRow(
                    title = "ייצוא תוצאות הניתוח לטלפון",
                    subtitle = if (analysed == 0) {
                        "עדיין אין תוצאות לייצא — יש להפעיל קודם את ניתוח הספרייה"
                    } else {
                        "$analysed שירים מוכנים להעברה, כולל המודלים · הטלפון לא יצטרך " +
                            "לנתח אותם שוב · הקובץ אינו כולל את קובצי המוזיקה"
                    },
                    action = "שמור קובץ",
                    enabled = analysed > 0 && !analysing,
                    primary = true,
                    onClick = onExportAnalysis
                )
                ActionRow(
                    title = "ייצוא רשימת הספרייה לבינה מלאכותית",
                    subtitle = "קובץ טקסט עם שם השיר, האמן והאלבום של כל שיר, כדי לבקש " +
                        "מצ'אט לתייג אמנים או לסדר רשימות. בלי קובצי מוזיקה ובלי היסטוריה",
                    action = "שמור רשימה",
                    enabled = songs > 0,
                    primary = false,
                    onClick = onExportCatalog
                )
                ActionRow(
                    title = "ייבוא היסטוריית השמעות",
                    subtitle = "קורא CSV שיוצא מנגן אחר. צריך עמודת שם שיר; " +
                        "עמודת אמן ועמודת מספר השמעות משפרות את ההתאמה. " +
                        "קובץ שיש בו שורה לכל השמעה נספר לבד, " +
                        "וייבוא חוזר של אותו קובץ לא מכפיל את המספרים",
                    action = "בחר קובץ",
                    enabled = true,
                    primary = false,
                    onClick = onImportPlayCounts
                )
                ActionRow(
                    title = "ייבוא רשימת השמעה",
                    subtitle = "קורא m3u או pls ומתאים אותו לשירים שבספרייה. " +
                        "מה שלא נמצא נספר ונאמר, ולא נעלם בשקט",
                    action = "בחר קובץ",
                    enabled = true,
                    primary = false,
                    onClick = onImportPlaylist
                )
                ActionRow(
                    title = "ייבוא מ־YouTube Music",
                    subtitle = "בוחרים את קובץ ה־ZIP מ־Google Takeout (\"YouTube ו־YouTube Music\"), " +
                        "או קובצי CSV של פלייליסטים. כל פלייליסט נוצר כאן, והשירים " +
                        "מזוהים לפי שם ואמן",
                    action = "בחר קבצים",
                    enabled = true,
                    primary = false,
                    onClick = onImportYouTubeMusic
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

            // No section header of its own, which is how these two nearly
            // ended up on every page: the pass that sorted the settings into
            // doors keyed on the headers.
            if (page == SettingsPage.TAGS) item {
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

            if (page == SettingsPage.LIBRARY) item {
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
                    title = "שם הקובץ במקום שם השיר",
                    subtitle = "לשירים שהתגיות שלהם שגויות: כל שיר ייקרא בשם הקובץ שלו, " +
                        "בכל מקום באפליקציה. הקבצים עצמם לא משתנים",
                    checked = titlesFromFiles
                ) {
                    titlesFromFiles = it
                    onTitlesFromFiles(it)
                }
                // Only once something was hidden: an empty list behind a
                // button is a button to nowhere.
                if (hiddenSongs.isNotEmpty()) {
                    ActionRow(
                        title = "שירים מוסתרים (${hiddenSongs.size})",
                        subtitle = "שירים שהסתרת מהנגן. אפשר להחזיר אותם מכאן",
                        action = "הצג",
                        enabled = true,
                        primary = false
                    ) { hiddenOpen = true }
                }
            }

            if (page == SettingsPage.LIBRARY) item {
                SettingSection(
                    "ניתוח אודיו",
                    "מדידת קצב, סולם, אנרגיה וגוון, ומודלי ה-AI של הטלפון — הכל על המחשב. " +
                        modelStatus
                )
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

            if (page == SettingsPage.TAGS) item {
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

            if (page == SettingsPage.ABOUT) item {
                SettingSection("על האפליקציה", null)
                Fact("גרסה", BuildInfo.version)
                BuildInfo.build?.let { Fact("מספר בנייה", it) }
                BuildInfo.commit?.let { Fact("קומיט", it) }
                if (BuildInfo.build == null) {
                    Text(
                        "בנייה מקומית — לא נבנתה דרך GitHub Actions, ולכן אין לה " +
                            "מספר בנייה שאפשר להשוות אליו.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        modifier = Modifier.padding(horizontal = GUTTER, vertical = 4.dp)
                    )
                }
                Text(
                    "Rhythm — נגן מוזיקה עם מנוע המלצות מקומי.\n" +
                        "הכל קורה על המחשב הזה. שום דבר לא נשלח לשום מקום.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(horizontal = GUTTER, vertical = 6.dp)
                )
            }

            if (page == SettingsPage.ABOUT) item {
                SettingSection("הסריקה האחרונה", "כמה קבצים נמצאו, ומתי")
                // A library scanned by a build that did not keep these, or a
                // scan still running, used to read as "0 found" beside a full
                // library. Now it says what is happening, and a count it does
                // not have is never shown as nothing.
                val found = maxOf(prefs.lastScanCount, songs)
                Fact("קבצים שנמצאו", if (scanning) "סורק עכשיו…" else "$found")
                Fact("נכנסו לספרייה", "$songs")
                Fact(
                    "נסרק לאחרונה",
                    when {
                        scanning -> "עכשיו"
                        prefs.lastScanAt <= 0L && songs > 0 -> "לפני העדכון האחרון"
                        else -> lastScanLabel(prefs.lastScanAt)
                    }
                )
                if (!scanning && prefs.lastScanCount > songs) {
                    Text(
                        "ההפרש הוא מה שהמסננים הסירו — אורך מינימלי, תיקיות מוחרגות, " +
                            "הקלטות וכפילויות. כל אחד מהם ניתן לכיבוי או לשינוי בהגדרות.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        modifier = Modifier.padding(horizontal = GUTTER, vertical = 4.dp)
                    )
                }
            }

            if (page == SettingsPage.ABOUT) item {
                SettingSection("מה המנוע יודע עליך", null)
                val r = taste
                if (r == null) {
                    Text("עוד אין נתונים", color = TextSecondary, modifier = Modifier.padding(horizontal = GUTTER))
                } else {
                    Fact("שירים בספרייה", "${r.totalSongs}")
                    Fact("אמנים", "${r.totalArtists} (מדורגים: ${r.ratedArtists})")
                    Fact("שירים עם תגית סגנון", "${r.songsWithStyle}")
                    Fact("שירים מדורגים", "${r.ratedSongs}")
                    Fact("קשרים סימטריים שנלמדו", "${r.learnedPairs}")
                    Fact("מעברים מכוונים שנלמדו", "${r.learnedTransitions}")
                    Fact("שירים שנותחו אקוסטית", "${r.analyzedSongs}")
                    Fact("קצב חציוני בספרייה", if (r.medianBpm > 0) "${r.medianBpm} BPM" else "—")
                    Fact("שירים שסומנו בלייק", "$liked")
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
            }

            if (page == SettingsPage.ABOUT) item {
                SettingSection("מודלים שבתוך האפליקציה", null)
                Text(
                    "זיהוי צלילים: YAMNet של Google, ברישיון Apache 2.0.\n" +
                        "זיהוי סגנון ומצב רוח: Discogs-EffNet ומסווגי מצב הרוח של Essentia, " +
                        "מאת MTG, אוניברסיטת פומפאו פברה בברצלונה, ברישיון " +
                        "CC BY-NC-SA 4.0 — לשימוש לא מסחרי בלבד. אותם מודלים שבטלפון, " +
                        "שהומרו ל-ONNX ורצים ב-ONNX Runtime (Microsoft, רישיון MIT). " +
                        "מקור: essentia.upf.edu/models.html; רישיון: " +
                        "creativecommons.org/licenses/by-nc-sa/4.0/. " +
                        "פרטי הקרדיט והרישיונות מצורפים לתיקיית ההתקנה.\n\n" + modelStatus,
                    modifier = Modifier.padding(horizontal = GUTTER, vertical = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }

        }
    }

    if (songMenuOpen) {
        SongMenuArrangementDialog(
            saved = prefs.songMenu,
            onChange = { prefs.songMenu = it },
            onDismiss = { songMenuOpen = false }
        )
    }

    if (hiddenOpen) {
        // Closed by itself once the last one is back.
        LaunchedEffect(hiddenSongs.isEmpty()) { if (hiddenSongs.isEmpty()) hiddenOpen = false }
        AlertDialog(
            onDismissRequest = { hiddenOpen = false },
            containerColor = Surface1,
            title = { Text("שירים מוסתרים") },
            text = {
                DialogBody {
                    Column {
                        for (song in hiddenSongs) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        song.artistName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                TextButton(onClick = { onUnhide(listOf(song.id)) }) {
                                    Text("החזר", color = Accent)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { onUnhide(hiddenSongs.map { it.id }); hiddenOpen = false }) {
                    Text("החזר הכל", color = Accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { hiddenOpen = false }) { Text("סגור", color = TextSecondary) }
            }
        )
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
    var pauseOnSilence by remember { mutableStateOf(prefs.pauseOnSilence) }
    var arrangementOpen by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(AppBackground)) {
        DetailTopBar(title = "הגדרות הנגן והשמע", onBack = onBack)
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
            // The phone's page, in its order and in its words.
            item {
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
                    title = "לחיצה על שיר פותחת את הנגן",
                    subtitle = "כבוי: השיר מתחיל והנגן נשאר מכווץ למטה. " +
                        "דלוק: מסך הנגן נפתח מיד",
                    checked = openPlayerOnPlay
                ) {
                    openPlayerOnPlay = it
                    prefs.openPlayerOnPlay = it
                }
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
                    title = "הצעה להמשיך מהמיקום האחרון",
                    subtitle = "כששיר נעזב באמצע ופותחים אותו שוב, מוצגת לכמה שניות " +
                        "הצעה לחזור לנקודה — עם הזמן המדויק. נשמר רק לשירים " +
                        "שנעזבו אחרי חצי דקה ולפני הסוף",
                    checked = resumePrompt
                ) {
                    resumePrompt = it
                    prefs.resumePrompt = it
                }
                // The app's own volume slider rather than the system's, which
                // Windows does not report to an app. Same promise: down to
                // zero pauses, back up resumes.
                SwitchRow(
                    title = "עצירה כשהעוצמה באפס",
                    subtitle = "מוריד את העוצמה של הנגן לאפס — ההשמעה נעצרת, ומתחדשת לבד " +
                        "כשמעלים בחזרה. בלי זה השיר ממשיך לרוץ בשקט",
                    checked = pauseOnSilence
                ) {
                    pauseOnSilence = it
                    prefs.pauseOnSilence = it
                }
                SwitchRow(
                    title = "רדיו אינסופי",
                    subtitle = "כשהתור נגמר, ממשיך לבד לפי הטעם שנלמד",
                    checked = autoRadio
                ) {
                    autoRadio = it
                    prefs.autoRadio = it
                }
                ActionRow(
                    title = "אקולייזר",
                    subtitle = "31 תדרים עם עקומת התגובה שהשמע באמת מקבל, " +
                        "מוכנים מראש, ועוצמה כללית",
                    action = "פתח",
                    enabled = true,
                    primary = false,
                    onClick = onOpenEqualizer
                )
                SwitchRow(
                    title = "איזון עוצמה בין שירים",
                    subtitle = "מנמיך את השירים החזקים במיוחד כדי שלא תצטרך לגעת " +
                        "בעוצמה בכל מעבר. דורש שהשירים ינותחו קודם",
                    checked = normalizeVolume
                ) {
                    normalizeVolume = it
                    prefs.normalizeVolume = it
                }
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
    prefs: Prefs,
    checks: AlgorithmChecks,
    tuning: EngineTuning,
    learning: Boolean,
    learningReport: String?,
    busy: Boolean,
    onChange: (EngineTuning) -> Unit,
    onLearn: () -> Unit,
    onClearLearned: () -> Unit,
    /** How many songs carry a tag the app guessed rather than one you typed. */
    guessedCount: Int,
    onShowGuessed: () -> Unit,
    onBack: () -> Unit
) {
    var autoLearn by remember { mutableStateOf(prefs.autoLearn) }
    var searchPersonalized by remember { mutableStateOf(prefs.searchPersonalized) }
    var searchLyrics by remember { mutableStateOf(prefs.searchLyrics) }
    var separations by remember { mutableStateOf(prefs.styleSeparations) }
    var separationsOpen by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }
    // Keyed on the tuning so a reset shows at once rather than on the next visit.
    var minPlay by remember(tuning) { mutableStateOf(prefs.minPlaySeconds.toFloat()) }
    if (confirmReset) {
        ConfirmDialog(
            title = "לאפס את הכוונונים?",
            body = "הפסים יחזרו לברירת המחדל, וכך גם הזמן שנספר כהשמעה. " +
                "הדירוגים, הלייקים וההיסטוריה נשארים.",
            confirm = "אפס",
            danger = false,
            onConfirm = { confirmReset = false; checks.onResetTuning() },
            onDismiss = { confirmReset = false }
        )
    }
    if (confirmForget) {
        ConfirmDialog(
            title = "לאפס את הלמידה?",
            body = "כל הדירוגים, הלייקים, ההשמעות ומה שנלמד מהם יימחקו. " +
                "אי אפשר לבטל את זה.",
            confirm = "אפס",
            danger = true,
            onConfirm = { confirmForget = false; checks.onResetLearning() },
            onDismiss = { confirmForget = false }
        )
    }
    // The phone's screen, in its order and in its words.
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
                        "משקל דירוגי האמנים", tuning.artistWeight, 0f..2f,
                        "כמה הכוכבים שנתת לאמנים משפיעים על הפיד"
                    ) { onChange(tuning.copy(artistWeight = it)) }
                    Knob(
                        "משקל הסגנונות", tuning.styleWeight, 0f..2f,
                        "כמה תגיות הסגנון מכתיבות את הבחירה"
                    ) { onChange(tuning.copy(styleWeight = it)) }
                    Knob(
                        "משקל התאמת הסאונד", tuning.acousticWeight, 0f..2f,
                        "כמה הקצב, האנרגיה והגוון שנמדדו מהקובץ משפיעים"
                    ) { onChange(tuning.copy(acousticWeight = it)) }
                    Knob(
                        "מניעת חזרתיות", tuning.repeatGuard, 0f..2f,
                        "ככל שגבוה יותר, שיר שהתנגן לאחרונה ירד בדירוג. מעל האמצע הוא גם נשאר למטה יותר זמן, עד שבוע בקצה"
                    ) { onChange(tuning.copy(repeatGuard = it)) }
                    // A slider moves under a pointer that was only passing,
                    // and nothing said where it started. Asked first, because
                    // a tuning someone set on purpose is just as easy to lose.
                    TextButton(onClick = { confirmReset = true }) {
                        Text("אפס לברירת המחדל", color = Accent)
                    }
                }
            }
            item {
                ActionRow(
                    title = "למידת סגנונות מהספרייה",
                    subtitle = "לומד איך הסגנונות שהגדרת נשמעים — גם מתגיות האמנים " +
                        "המובנות בספרייה — ומשלים תגיות לשירים שלא תויגו. האפליקציה בודקת " +
                        "על אמנים שלא השתתפו באימון, וסופרת גם תגיות שגויות וחסרות. " +
                        "אם הבדיקה אינה מספקת היא לא משנה כלום " + StyleLearning.requirement(),
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
            }
            item {
                SwitchRow(
                    title = "ווקאלי רק בספירה ובשלושת השבועות",
                    subtitle = "שירים ווקאליים לא מוצעים בשאר השנה. כשהאפשרות מופעלת, בימי ספירת " +
                        "העומר (חוץ מל\"ג בעומר) ובשלושת השבועות מוצעים רק שירים ווקאליים. " +
                        "שיר נחשב ווקאלי לפי השם שלו (ווקאלי, אקפלה), לפי תגית, לפי הצליל, " +
                        "או לפי מה שסימנת בתפריט השיר. " +
                        (checks.season?.let { "כרגע: $it." } ?: "כרגע לא בתקופות האלה."),
                    checked = checks.onlyVocalInSeason
                ) { checks.onOnlyVocal(it) }
                // A set of songs is often titled after the first of them;
                // its length is what gives it away. Off unless chosen.
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = GUTTER, vertical = 10.dp)) {
                    Text("מחרוזת לפי אורך", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "שיר שאורכו לפחות כך נחשב מחרוזת, גם כשבשם שלו לא כתוב \"מחרוזת\". " +
                            "מחרוזות לא מוצעות במיקסים, ברדיו ובהמלצות — הן נשארות בספרייה ובמדף \"נוספו לאחרונה\".",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(EngineTuning.MEDLEY_CHOICES) { minutes ->
                            Chip(
                                label = if (minutes == 0) "רק לפי השם" else "$minutes דקות ומעלה",
                                selected = checks.medleyMinutes == minutes
                            ) { checks.onMedleyMinutes(minutes) }
                        }
                    }
                }
                ActionRow(
                    title = "תעודת ציונים לאלגוריתם",
                    subtitle = "בודק על ההיסטוריה שלך אם האלגוריתם יודע לחזות אילו שירים " +
                        "תאהב לפני ששמעת אותם, ולומד ממנה כמה לסמוך על כל אות — " +
                        "אמן, סגנון, סאונד ומצב רוח. " +
                        (if (checks.usingLearned) "כרגע פעילים משקלים אישיים" else "כרגע פעילים המשקלים הרגילים"),
                    action = if (checks.calibrating) "בודק…" else "בדוק",
                    enabled = !checks.calibrating,
                    primary = true,
                    onClick = checks.onCalibrate
                )
                checks.calibration?.let { report ->
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = GUTTER, vertical = 12.dp)) {
                        Text(report, color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(10.dp))
                        Row {
                            if (checks.canApplyLearned) {
                                Button(
                                    onClick = checks.onApplyLearned,
                                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                                ) { Text("הפעל משקלים אישיים") }
                                Spacer(Modifier.width(10.dp))
                            }
                            if (checks.usingLearned) {
                                OutlinedButton(onClick = checks.onResetLearned) { Text("חזור לרגילים") }
                            }
                        }
                    }
                }
                ActionRow(
                    title = "בדיקת טביעת הצליל",
                    subtitle = "בודק על הספרייה שלך אם השירים שנשמעים דומה באמת מאותו " +
                        "סגנון — פעם לפי מדידת הסאונד הנוכחית ופעם לפי טביעת " +
                        "הצליל החדשה. לא משנה כלום; רק מודד",
                    action = "בדוק",
                    enabled = !busy,
                    primary = true,
                    onClick = checks.onSoundCheck
                )
                checks.soundCheck?.let { report ->
                    Text(
                        report,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = GUTTER, vertical = 12.dp),
                        color = TextPrimary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                ActionRow(
                    title = "בדיקת דיוק המודל המוזיקלי",
                    subtitle = "משווה את למידת הסגנונות והזיהוי של מצבי רוח עם ובלי המודל, " +
                        "על שירים מתויגים ותיקונים ידניים בספרייה שלך. הבדיקה לא משנה תגיות.",
                    action = if (checks.modelChecking) "בודק…" else "בדוק",
                    enabled = !checks.modelChecking,
                    primary = true,
                    onClick = checks.onModelCheck
                )
                checks.modelReport?.let { report ->
                    Text(
                        report,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = GUTTER, vertical = 12.dp),
                        color = TextPrimary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            item {
                Column(modifier = Modifier.padding(horizontal = GUTTER)) {
                    Knob(
                        label = if (minPlay < 1f) {
                            "נספר כהשמעה: מיד"
                        } else {
                            "נספר כהשמעה אחרי ${minPlay.toInt()} שניות"
                        },
                        value = minPlay,
                        range = 0f..Listening.MAX_MINIMUM_SEC.toFloat(),
                        hint = "מי שמדפדף באוזן נוגע בעשרה שירים כדי למצוא אחד. " +
                            "מתחת לזה לא נרשם כלום — לא השמעה ולא דילוג — כדי ששיר " +
                            "שרק הוצץ בו לא ייחשב אהוב ולא ייקבר",
                        onDone = {
                            minPlay = it
                            prefs.minPlaySeconds = it.toInt()
                        }
                    )
                }
                SwitchRow(
                    title = "למידה אוטומטית",
                    subtitle = if (autoLearn) {
                        "לומד בעצמו אחרי כל ניתוח אודיו, בלי ללחוץ. הוא עדיין בודק " +
                            "את עצמו על אמנים שלא אימן עליהם ולא כותב סגנון שאינו " +
                            "מדייק בו — כך שריצה בלי מה לומר לא משנה כלום"
                    } else {
                        "כבויה. הלמידה תרוץ רק כשתלחץ על \"למד\""
                    },
                    checked = autoLearn
                ) {
                    autoLearn = it
                    prefs.autoLearn = it
                }
                // Between learning and clearing: it tagged something, what did
                // it tag, and only then is throwing it away worth offering.
                if (guessedCount > 0) {
                    ActionRow(
                        title = "מה האפליקציה ניחשה",
                        subtitle = "$guessedCount שירים קיבלו תגית מהלמידה. פתח שיר כדי " +
                            "לראות ולתקן — תגית שתקליד בעצמך לא תידרס בריצה הבאה",
                        action = "הצג",
                        enabled = true,
                        primary = false,
                        onClick = onShowGuessed
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
            item {
                SwitchRow(
                    title = "חיפוש גם במילות השיר",
                    subtitle = "מוצא שיר לפי שורה שזכור לך ממנו, גם כשאת השם שכחת. " +
                        "עובד על שירים שיש להם מילים — מקובץ LRC, מתגיות הקובץ, או שהקלדת",
                    checked = searchLyrics
                ) {
                    searchLyrics = it
                    prefs.searchLyrics = it
                }
                SwitchRow(
                    title = "התאמה אישית בתוצאות",
                    subtitle = "מה שאתה מנגן הרבה עולה למעלה בתוצאות",
                    checked = searchPersonalized
                ) {
                    searchPersonalized = it
                    prefs.searchPersonalized = it
                }
                ActionRow(
                    title = "סגנונות שלא יתערבבו",
                    subtitle = separations.lines().filter { it.isNotBlank() }.joinToString(" · ")
                        .ifBlank { "מה שלא נשמע טוב אחד אחרי השני, שורה לכל כלל. כרגע אין הפרדות" },
                    action = "ערוך",
                    enabled = true,
                    primary = true,
                    onClick = { separationsOpen = true }
                )
            }
            item {
                SettingSection("בדיקת המנוע", "כמה טוב הוא מנחש מה באמת הושמע אחר כך")
                Text(
                    "עובר על ההיסטוריה ושואל, לכל מעבר בין שני שירים, באיזה מקום " +
                        "מכל הספרייה המנוע היה מדרג את השיר שבאמת בא אחריו. " +
                        "שינוי באלגוריתם שמשפר — מעלה את המספרים האלה.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(horizontal = GUTTER)
                )
                checks.engineReport?.let { report ->
                    Spacer(Modifier.height(10.dp))
                    Text(
                        report,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary,
                        modifier = Modifier.padding(horizontal = GUTTER)
                    )
                }
                Row(modifier = Modifier.padding(horizontal = GUTTER, vertical = 10.dp)) {
                    Button(
                        onClick = checks.onEvaluate,
                        enabled = !busy,
                        colors = ButtonDefaults.buttonColors(containerColor = Accent)
                    ) { Text("בדוק עכשיו") }
                }
            }
            item {
                // Last, and not a filled button. It throws away every rating,
                // like and play count the engine ever learned from, which is
                // the one thing here that months of listening cannot be got
                // back. It sits under the engine because the engine is what
                // it empties. The phone's place for it.
                Row(modifier = Modifier.padding(horizontal = GUTTER, vertical = 14.dp)) {
                    OutlinedButton(onClick = { confirmForget = true }) { Text("אפס למידה", color = Accent) }
                }
            }
        }
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
                checks.onSeparationsChanged()
            }
        )
    }
}

/** The algorithm screen's checks and the switches beside them; the state lives in Main. */
internal class AlgorithmChecks(
    val onResetTuning: () -> Unit,
    val onlyVocalInSeason: Boolean,
    val onOnlyVocal: (Boolean) -> Unit,
    val medleyMinutes: Int,
    val onMedleyMinutes: (Int) -> Unit,
    /** The Omer or the Three Weeks by name, when today is in one. */
    val season: String?,
    val usingLearned: Boolean,
    val calibrating: Boolean,
    /** The report card in words, with the mood reading's own report under it. */
    val calibration: String?,
    val canApplyLearned: Boolean,
    val onCalibrate: () -> Unit,
    val onApplyLearned: () -> Unit,
    val onResetLearned: () -> Unit,
    val soundCheck: String?,
    val onSoundCheck: () -> Unit,
    val modelChecking: Boolean,
    val modelReport: String?,
    val onModelCheck: () -> Unit,
    /** How often the engine would have guessed what was played next, in words. */
    val engineReport: String?,
    val onEvaluate: () -> Unit,
    /** Forgets every rating, like and play: the phone's "reset learning". */
    val onResetLearning: () -> Unit,
    val onSeparationsChanged: () -> Unit
)

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
 * The player's own volume is the slider the desktop player always shows.
 *
 * Share is Android's own idea. Windows has no equivalent to hand a file to
 * whichever application the user picks from a sheet, and a button that opens
 * a file manager instead is a different feature wearing the same name.
 */
internal val DESKTOP_PLAYER_ACTIONS: List<PlayerAction> =
    PlayerAction.entries.filterNot {
        it == PlayerAction.SPEED || it == PlayerAction.SHARE || it == PlayerAction.VOLUME
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
            DialogBody {
                Column {
                    Text(hint, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 220.dp)
                    )
                }
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
