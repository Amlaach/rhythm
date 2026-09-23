package com.elchanan.rhythm.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.elchanan.rhythm.engine.Listening
import com.elchanan.rhythm.engine.MoodMarks
import com.elchanan.rhythm.engine.SignalCalibration
import com.elchanan.rhythm.engine.StyleLearning
import com.elchanan.rhythm.ui.MainViewModel
import com.elchanan.rhythm.ui.components.SectionHeader
import com.elchanan.rhythm.ui.components.TuningSlider
import com.elchanan.rhythm.ui.components.rememberMetrics
import com.elchanan.rhythm.ui.theme.Accent
import com.elchanan.rhythm.ui.theme.AppBackground
import com.elchanan.rhythm.ui.theme.Surface1
import com.elchanan.rhythm.ui.theme.TextPrimary
import com.elchanan.rhythm.ui.theme.TextSecondary
import com.elchanan.rhythm.ui.theme.TextTertiary

/**
 * How the algorithm weighs what it knows, on a screen of its own.
 *
 * Kept out of the settings list rather than folded into it: these five values
 * are what decides the feed, and they are the part of the app someone comes
 * back to after living with it for a while. Learning the styles sits here too -
 * it is the other half of the same question, and the two are easier to read
 * side by side than scattered through the settings.
 */
@Composable
fun AlgorithmSettingsScreen(
    vm: MainViewModel,
    onBack: () -> Unit,
    onOpenDetail: () -> Unit = {}
) {
    // Every row on this screen shares the page margin, so a narrow phone gets
    // its content back instead of spending it on empty edges.
    val gutter = rememberMetrics().gutter
    val busy by vm.busy.collectAsStateWithLifecycle()
    val learning by vm.learning.collectAsStateWithLifecycle()
    val learningReport by vm.learningReport.collectAsStateWithLifecycle()
    val soundCheck by vm.soundCheck.collectAsStateWithLifecycle()
    val musicModelReport by vm.musicModelReport.collectAsStateWithLifecycle()
    val musicModelChecking by vm.musicModelChecking.collectAsStateWithLifecycle()
    val calibration by vm.calibration.collectAsStateWithLifecycle()
    val calibrating by vm.calibrating.collectAsStateWithLifecycle()
    val moodReport by vm.moodReport.collectAsStateWithLifecycle()
    var usingLearned by remember { mutableStateOf(vm.usingLearnedWeights) }
    var onlyVocal by remember { mutableStateOf(vm.prefs.onlyVocalInSeason) }

    var discovery by remember { mutableFloatStateOf(vm.prefs.discovery) }
    var artistWeight by remember { mutableFloatStateOf(vm.prefs.artistWeight) }
    var styleWeight by remember { mutableFloatStateOf(vm.prefs.styleWeight) }
    var acousticWeight by remember { mutableFloatStateOf(vm.prefs.acousticWeight) }
    var repeatGuard by remember { mutableFloatStateOf(vm.prefs.repeatGuard) }

    val evaluation by vm.sequenceReport.collectAsStateWithLifecycle()
    var searchPersonal by remember { mutableStateOf(vm.prefs.searchPersonalized) }
    var searchLyrics by remember { mutableStateOf(vm.prefs.searchLyrics) }
    var searchOpen by remember { mutableStateOf(false) }
    var autoLearn by remember { mutableStateOf(vm.prefs.autoLearn) }
    var minPlay by remember { mutableFloatStateOf(vm.prefs.minPlaySeconds.toFloat()) }
    var separations by remember { mutableStateOf(vm.prefs.styleSeparations) }
    var separationsOpen by remember { mutableStateOf(false) }

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
            Text("הגדרות האלגוריתם", style = MaterialTheme.typography.titleLarge)
        }

        LazyColumn(contentPadding = PaddingValues(bottom = 60.dp)) {
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
                    modifier = Modifier.fillMaxWidth().clickable(enabled = !busy && !learning) { vm.learnStyles() }.padding(horizontal = gutter, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("למידת סגנונות מהספרייה", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "לומד איך הסגנונות שהגדרת נשמעים — גם מתגיות האמנים " +
                                "המובנות בספרייה — ומשלים תגיות לשירים שלא תויגו. האפליקציה " +
                                "בודקת על אמנים שלא השתתפו באימון, וסופרת גם תגיות שגויות וחסרות. אם הבדיקה אינה מספקת היא " +
                                "לא משנה כלום " + StyleLearning.requirement(),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Button(
                        onClick = { vm.learnStyles() },
                        enabled = !busy && !learning,
                        colors = ButtonDefaults.buttonColors(containerColor = Accent)
                    ) { Text(if (learning) "לומד…" else "למד") }
                }
            }

            learningReport?.let { report ->
                item {
                    Text(
                        report,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clickable { onlyVocal = !onlyVocal; vm.setOnlyVocalInSeason(onlyVocal) }
                        .padding(horizontal = gutter, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("ווקאלי רק בספירה ובשלושת השבועות", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "שירים ווקאליים לא מוצעים בשאר השנה. כשהאפשרות מופעלת, בימי ספירת " +
                                "העומר (חוץ מל\"ג בעומר) ובשלושת השבועות מוצעים רק שירים ווקאליים. " +
                                "שיר נחשב ווקאלי לפי השם שלו (ווקאלי, אקפלה), לפי תגית, לפי הצליל, " +
                                "או לפי מה שסימנת בתפריט השיר. " +
                                (vm.season?.let { "כרגע: ${it.label}." } ?: "כרגע לא בתקופות האלה."),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Switch(
                        checked = onlyVocal,
                        onCheckedChange = {
                            onlyVocal = it
                            vm.setOnlyVocalInSeason(it)
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
                    modifier = Modifier.fillMaxWidth()
                        .clickable(enabled = !calibrating) { vm.runCalibration() }
                        .padding(horizontal = gutter, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("תעודת ציונים לאלגוריתם", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "בודק על ההיסטוריה שלך אם האלגוריתם יודע לחזות אילו שירים " +
                                "תאהב לפני ששמעת אותם, ולומד ממנה כמה לסמוך על כל אות — " +
                                "אמן, סגנון, סאונד ומצב רוח. " +
                                (if (usingLearned) "כרגע פעילים משקלים אישיים" else "כרגע פעילים המשקלים הרגילים"),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Button(
                        onClick = { vm.runCalibration() },
                        enabled = !calibrating,
                        colors = ButtonDefaults.buttonColors(containerColor = Accent)
                    ) { Text(if (calibrating) "בודק…" else "בדוק") }
                }
            }

            calibration?.let { report ->
                item {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 12.dp)) {
                        Text(
                            SignalCalibration.describe(report),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary
                        )
                        moodReport?.let { moods ->
                            Spacer(Modifier.height(10.dp))
                            Text(
                                MoodMarks.describe(moods),
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Row {
                            if (report.accepted && report.weights != null) {
                                Button(
                                    onClick = {
                                        vm.applyLearnedWeights()
                                        usingLearned = true
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                                ) { Text("הפעל משקלים אישיים") }
                                Spacer(Modifier.width(10.dp))
                            }
                            if (usingLearned) {
                                Button(
                                    onClick = {
                                        vm.resetLearnedWeights()
                                        usingLearned = false
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Surface1,
                                        contentColor = TextPrimary
                                    )
                                ) { Text("חזור לרגילים") }
                            }
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable(enabled = !busy) { vm.runSoundCheck() }.padding(horizontal = gutter, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("בדיקת טביעת הצליל", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "בודק על הספרייה שלך אם השירים שנשמעים דומה באמת מאותו " +
                                "סגנון — פעם לפי מדידת הסאונד הנוכחית ופעם לפי טביעת " +
                                "הצליל החדשה. לא משנה כלום; רק מודד",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Button(
                        onClick = { vm.runSoundCheck() },
                        enabled = !busy,
                        colors = ButtonDefaults.buttonColors(containerColor = Accent)
                    ) { Text("בדוק") }
                }
            }

            soundCheck?.let { report ->
                item {
                    Text(
                        report,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clickable(enabled = !musicModelChecking) { vm.runMusicModelEvaluation() }
                        .padding(horizontal = gutter, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("בדיקת דיוק המודל המוזיקלי", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "משווה את למידת הסגנונות והזיהוי של מצבי רוח עם ובלי המודל, " +
                                "על שירים מתויגים ותיקונים ידניים בספרייה שלך. הבדיקה לא משנה תגיות.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Button(
                        onClick = { vm.runMusicModelEvaluation() },
                        enabled = !musicModelChecking,
                        colors = ButtonDefaults.buttonColors(containerColor = Accent)
                    ) { Text(if (musicModelChecking) "בודק…" else "בדוק") }
                }
            }

            musicModelReport?.let { report ->
                item {
                    Text(
                        report,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary
                    )
                }
            }

            item {
                TuningSlider(
                    label = if (minPlay < 1f) {
                        "נספר כהשמעה: מיד"
                    } else {
                        "נספר כהשמעה אחרי ${minPlay.toInt()} שניות"
                    },
                    value = minPlay / Listening.MAX_MINIMUM_SEC,
                    hint = "מי שמדפדף באוזן נוגע בעשרה שירים כדי למצוא אחד. " +
                        "מתחת לזה לא נרשם כלום — לא השמעה ולא דילוג — כדי ששיר " +
                        "שרק הוצץ בו לא ייחשב אהוב ולא ייקבר",
                    onChange = {
                        minPlay = (it * Listening.MAX_MINIMUM_SEC)
                            .coerceIn(0f, Listening.MAX_MINIMUM_SEC.toFloat())
                    },
                    onDone = { vm.prefs.minPlaySeconds = minPlay.toInt() }
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("למידה אוטומטית", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "לומד בעצמו אחרי כל ניתוח אודיו, בלי ללחוץ. הוא עדיין " +
                                "בודק את עצמו על אמנים שלא אימן עליהם ולא כותב סגנון " +
                                "שאינו מדייק בו — כך שריצה בלי מה לומר לא משנה כלום",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Switch(
                        checked = autoLearn,
                        onCheckedChange = {
                            autoLearn = it
                            vm.prefs.autoLearn = it
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Accent,
                            checkedTrackColor = Accent.copy(alpha = 0.4f)
                        )
                    )
                }
            }

            // Between learning and clearing, because that is the order the
            // questions arrive in: it tagged something, what did it tag, and
            // only then is throwing it away a sensible thing to offer.
            val guessed = vm.guessedSongs()
            if (guessed.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { vm.openGuessed(); onOpenDetail() }
                            .padding(horizontal = gutter, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "מה האפליקציה ניחשה",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                "${guessed.size} שירים קיבלו תגית מהלמידה. פתח שיר כדי " +
                                    "לראות ולתקן — תגית שתקליד בעצמך לא תידרס בריצה הבאה",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                        Button(
                            onClick = { vm.openGuessed(); onOpenDetail() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Surface1,
                                contentColor = TextPrimary
                            )
                        ) { Text("הצג") }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("ניקוי התגיות שנוחשו", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "מוחק רק תגיות שהאפליקציה הוסיפה בעצמה. התגיות שהקלדת " +
                                "נשארות. שימושי אחרי שתייגת עוד אמנים — הלמידה " +
                                "תהיה טובה יותר, והניחושים הישנים לא יחסמו אותה",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Button(
                        onClick = { vm.clearLearnedStyles() },
                        enabled = !busy,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Surface1,
                            contentColor = TextPrimary
                        )
                    ) { Text("נקה") }
                }
            }

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
                        .fillMaxWidth().clickable { separationsOpen = true }
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


            item {
                // Last, and not a filled button. It throws away every rating,
                // like and play count the engine ever learned from, which is
                // the one thing here that months of listening cannot be got
                // back. It sits under the engine because the engine is what
                // it empties.
                Row(modifier = Modifier.padding(horizontal = gutter, vertical = 14.dp)) {
                    Button(
                        onClick = { vm.resetLearning() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Surface1,
                            contentColor = TextPrimary
                        )
                    ) { Text("אפס למידה") }
                }
            }
        }
    }

    if (separationsOpen) {
        SeparationDialog(
            initial = separations,
            onDismiss = { separationsOpen = false },
            onApply = {
                separations = it
                vm.setStyleSeparations(it)
                separationsOpen = false
            }
        )
    }
}
