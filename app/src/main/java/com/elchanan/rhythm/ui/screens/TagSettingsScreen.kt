package com.elchanan.rhythm.ui.screens

import android.content.Intent
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
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elchanan.rhythm.ui.MainViewModel
import com.elchanan.rhythm.ui.components.SectionHeader
import com.elchanan.rhythm.ui.components.rememberMetrics
import com.elchanan.rhythm.ui.theme.Accent
import com.elchanan.rhythm.ui.theme.Surface1
import com.elchanan.rhythm.ui.theme.TextPrimary
import com.elchanan.rhythm.ui.theme.TextSecondary
import com.elchanan.rhythm.ui.theme.TextTertiary

/**
 * Everything that is written about a song rather than measured from it.
 *
 * The tag repair tool, the two switches that decide how far it goes, and
 * where the words come from. They belong together because they are all the
 * same question - what this file says it is - and they were previously three
 * separate places on one long screen, with the two switches landing after
 * playlist import for no reason anyone could have explained.
 */
@Composable
fun TagSettingsScreen(
    vm: MainViewModel,
    onBack: () -> Unit,
    onOpenTagFix: () -> Unit
) {
    val lyricsFolder by vm.lyricsFolder.collectAsStateWithLifecycle()
    val gutter = rememberMetrics().gutter
    val context = LocalContext.current

    var stripForeign by remember { mutableStateOf(vm.prefs.tagStripForeign) }
    var writeTags by remember { mutableStateOf(vm.prefs.writeTagsToFiles) }

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

    SettingsScaffold(title = "תגיות ומילות שיר", onBack = onBack) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenTagFix).padding(horizontal = gutter, vertical = 10.dp),
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
    }
}
