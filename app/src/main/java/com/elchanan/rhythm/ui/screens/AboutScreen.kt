package com.elchanan.rhythm.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elchanan.rhythm.BuildConfig
import com.elchanan.rhythm.ui.MainViewModel
import com.elchanan.rhythm.ui.components.SectionHeader
import com.elchanan.rhythm.ui.components.rememberMetrics
import com.elchanan.rhythm.ui.theme.Accent
import com.elchanan.rhythm.ui.theme.TextSecondary

/**
 * What the app is, what the last scan found, and what the engine has learned.
 *
 * Read-only except for the one button at the bottom. This is the screen to
 * open when something looks wrong and the question is what the app actually
 * thinks - how many files it saw, how many it kept, how many it held on to
 * because a card was out, and what it has worked out about the listener.
 */
@Composable
fun AboutScreen(vm: MainViewModel, onBack: () -> Unit) {
    val report by vm.report.collectAsStateWithLifecycle()
    val scan by vm.scanReport.collectAsStateWithLifecycle()
    val library by vm.library.collectAsStateWithLifecycle()
    val gutter = rememberMetrics().gutter

    SettingsScaffold(title = "מידע ואבחון", onBack = onBack) {
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
                    // Said out loud, because a card that is out and a
                    // library that has vanished look identical from the
                    // home screen and only one of them is a problem.
                    if (s.onAbsentStorage > 0) {
                        Stat("נשמרו — האחסון מנותק", "${s.onAbsentStorage}")
                        Text(
                            "השירים האלה יושבים על כרטיס זיכרון שלא מחובר כרגע. " +
                                "הם נשארים בספרייה עם הדירוגים וההשמעות שלהם, " +
                                "ויחזרו כמו שהיו ברגע שהכרטיס יחזור.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
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

        item { SectionHeader(title = "מודלים שבתוך האפליקציה") }
        item {
            Text(
                "זיהוי צלילים: YAMNet של Google, ברישיון Apache 2.0.\n" +
                    "זיהוי סגנון ומצב רוח: Discogs-EffNet ומסווגי מצב הרוח של Essentia, " +
                    "מאת MTG, אוניברסיטת פומפאו פברה בברצלונה, ברישיון " +
                    "CC BY-NC-SA 4.0 — לשימוש לא מסחרי בלבד. האפליקציה מופצת בחינם.",
                modifier = Modifier.padding(horizontal = gutter, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    }
}
