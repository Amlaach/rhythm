package com.elchanan.rhythm.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elchanan.rhythm.data.AnalysisTransfer
import com.elchanan.rhythm.data.LibraryCatalogExport
import com.elchanan.rhythm.ui.MainViewModel
import com.elchanan.rhythm.ui.components.rememberMetrics
import com.elchanan.rhythm.ui.theme.Accent
import com.elchanan.rhythm.ui.theme.TextSecondary

/**
 * Moving playlists in and out.
 *
 * Two rows, and a screen of their own rather than a pair of buttons lost in
 * the middle of a longer list. They are the only things here that touch
 * files outside the app, which is reason enough to keep them apart from the
 * settings that only change how it behaves.
 */
@Composable
fun TransferScreen(vm: MainViewModel, onBack: () -> Unit) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    val gutter = rememberMetrics().gutter
    val context = LocalContext.current

    val analysisLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) vm.importAnalysis(uri)
    }

    val catalogLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(LibraryCatalogExport.MIME)
    ) { uri ->
        if (uri != null) vm.exportLibraryCatalog(uri)
    }

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

    // Same reasoning as the playlist picker: a CSV exported by another player
    // arrives as text/csv, text/plain, text/comma-separated-values or
    // application/octet-stream depending on who wrote it.
    val playCountLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) vm.importPlayCounts(uri)
    }

    // A folder rather than a file: there is more than one list to write, and
    // asking where to put each of thirty of them would be absurd.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) vm.exportAllPlaylists(uri)
    }

    SettingsScaffold(title = "ייבוא וייצוא", onBack = onBack) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("ייבוא ניתוח מהמחשב", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "מייבא קובץ ${AnalysisTransfer.EXTENSION} שיצרה Rhythm ב־Windows. " +
                            "רק התאמות ודאיות נשמרות; שירים שלא נמצאו נשארים לניתוח בטלפון",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                Button(
                    onClick = { analysisLauncher.launch(arrayOf("*/*")) },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                ) { Text("בחר קובץ") }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("ייצוא רשימת הספרייה לבינה מלאכותית", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "כותב את כל האמנים, האלבומים והשירים בקובץ טקסט קריא — " +
                            "בלי נתיבי קבצים, דירוגים או היסטוריית האזנה",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                Button(
                    onClick = { catalogLauncher.launch(LibraryCatalogExport.FILE_NAME) },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                ) { Text("שמור רשימה") }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("ייבוא היסטוריית השמעות", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "קורא קובץ CSV שיוצא מנגן אחר. צריך עמודת שם שיר; " +
                            "עמודת אמן ועמודת מספר השמעות משפרות את ההתאמה. " +
                            "קובץ שיש בו שורה לכל השמעה נספר לבד. " +
                            "ייבוא חוזר של אותו קובץ לא מכפיל את המספרים",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                Button(
                    onClick = { playCountLauncher.launch(arrayOf("*/*")) },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                ) { Text("בחר קובץ") }
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

    }
}
