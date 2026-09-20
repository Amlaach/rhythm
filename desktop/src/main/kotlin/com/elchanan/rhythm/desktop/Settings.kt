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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.elchanan.rhythm.desktop.audio.Equalizer
import com.elchanan.rhythm.engine.EngineTuning
import com.elchanan.rhythm.ui.theme.Accent
import com.elchanan.rhythm.ui.theme.AppBackground
import com.elchanan.rhythm.ui.theme.Bg
import com.elchanan.rhythm.ui.theme.Surface1
import com.elchanan.rhythm.ui.theme.Surface2
import com.elchanan.rhythm.ui.theme.TextSecondary

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
    onPickLyricsFolder: () -> Unit
) {
    // Read once into state so a flipped switch moves under the finger. Every
    // one of these writes through to the database as it changes; the state is
    // only here because a composable cannot re-read SQLite to redraw itself.
    var autoAnalyze by remember { mutableStateOf(prefs.autoAnalyze) }
    var hideDuplicates by remember { mutableStateOf(prefs.hideDuplicates) }
    var searchPersonalized by remember { mutableStateOf(prefs.searchPersonalized) }
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
                SettingSection("מסכים", null)
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
                OutlinedButton(
                    onClick = onResetStats,
                    modifier = Modifier.padding(horizontal = GUTTER)
                ) { Text("אפס את כל ההיסטוריה", color = Accent) }
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
        }
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
 * The equaliser is in it rather than behind its own entry, because "make the
 * bass louder" and "open the player when I press play" are the same kind of
 * decision and nobody goes looking for them in two places.
 */
@Composable
internal fun PlayerSettingsScreen(
    prefs: Prefs,
    equalizer: Equalizer,
    onBack: () -> Unit
) {
    var openPlayerOnPlay by remember { mutableStateOf(prefs.openPlayerOnPlay) }
    var autoRadio by remember { mutableStateOf(prefs.autoRadio) }

    Column(modifier = Modifier.fillMaxSize().background(AppBackground)) {
        DetailTopBar(title = "הגדרות הנגן והשמע", onBack = onBack)
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                SettingSection("הנגן", null)
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
            }
            item {
                SettingSection("אקולייזר", "שש רצועות, נשמר בין הפעלות")
                EqualizerPanel(prefs = prefs, equalizer = equalizer)
            }
        }
    }
}

@Composable
private fun EqualizerPanel(prefs: Prefs, equalizer: Equalizer) {
    // The sliders read from the filter and write to it directly. There is no
    // copy of these six numbers anywhere else, which is what stops a slider
    // and the sound it is meant to change from disagreeing. They are written
    // to the database as well, but only on the way past.
    var version by remember { mutableStateOf(0) }
    var on by remember { mutableStateOf(equalizer.enabled) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = GUTTER)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    on = !on
                    equalizer.enabled = on
                    prefs.eqEnabled = on
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (on) Accent else Surface2
                )
            ) { Text(if (on) "מופעל" else "כבוי") }
            OutlinedButton(
                onClick = {
                    equalizer.reset()
                    prefs.eqBands = List(Prefs.BAND_COUNT) { 0 }
                    version++
                },
                modifier = Modifier.padding(start = 8.dp)
            ) { Text("אפס") }
        }
        Spacer(Modifier.height(8.dp))
        for (band in Equalizer.FREQUENCIES.indices) {
            val hz = Equalizer.FREQUENCIES[band].toInt()
            val label = if (hz >= 1000) "${hz / 1000}kHz" else "${hz}Hz"
            var live by remember(version, band) { mutableStateOf(equalizer.gain(band)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.width(56.dp)
                )
                Slider(
                    value = live,
                    valueRange = -Equalizer.MAX_DB..Equalizer.MAX_DB,
                    onValueChange = {
                        live = it
                        equalizer.setGain(band, it)
                    },
                    // Saved when the slider is let go, not while it is moving:
                    // a drag across the width of the window is a few hundred
                    // values, and every one of them would be a write.
                    onValueChangeFinished = {
                        prefs.eqBands = Equalizer.FREQUENCIES.indices.map {
                            equalizer.gain(it).toInt()
                        }
                    },
                    enabled = on,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                )
                Text(
                    "${live.toInt()} dB",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.width(52.dp)
                )
            }
        }
    }
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
    onChange: (EngineTuning) -> Unit,
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
