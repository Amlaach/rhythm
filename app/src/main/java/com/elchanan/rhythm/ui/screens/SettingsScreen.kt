package com.elchanan.rhythm.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.elchanan.rhythm.ui.MainViewModel
import com.elchanan.rhythm.ui.components.rememberMetrics
import com.elchanan.rhythm.ui.theme.Accent
import com.elchanan.rhythm.ui.theme.Surface1
import com.elchanan.rhythm.ui.theme.TextSecondary

/**
 * The name the picker shows for a document, which is where the playlist gets
 * its own name from. Falls back to the last path segment, which for a
 * content:// uri is an opaque id but is at least never empty.
 */
internal fun displayNameOf(context: android.content.Context, uri: android.net.Uri): String {
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
    onBack: () -> Unit,
    onOpenHomeSettings: () -> Unit = {},
    onOpenPlayerSettings: () -> Unit = {},
    onOpenAlgorithmSettings: () -> Unit = {},
    onOpenLibrarySettings: () -> Unit = {},
    onOpenTagSettings: () -> Unit = {},
    onOpenTransfer: () -> Unit = {},
    onOpenAbout: () -> Unit = {}
) {
    SettingsScaffold(title = "הגדרות", onBack = onBack) {
        item {
            SettingsDoor(
                icon = Icons.Filled.Home,
                title = "דף הבית ותצוגה",
                subtitle = "אילו מדפים מופיעים, מה נפתח ראשון, איך מוצגות התיקיות",
                onClick = onOpenHomeSettings
            )
        }
        item {
            SettingsDoor(
                icon = Icons.Filled.LibraryMusic,
                title = "ספרייה וסריקה",
                subtitle = "אילו קבצים נכנסים לספרייה, וניתוח האודיו",
                onClick = onOpenLibrarySettings
            )
        }
        item {
            SettingsDoor(
                icon = Icons.Filled.PlayArrow,
                title = "נגן ושמע",
                subtitle = "אקולייזר, רדיו אינסופי, סידור הכפתורים, עוצמה",
                onClick = onOpenPlayerSettings
            )
        }
        item {
            SettingsDoor(
                icon = Icons.Filled.AutoAwesome,
                title = "המנוע",
                subtitle = "מה עולה למעלה בפיד ובחיפוש, ומה לא יתערבב",
                onClick = onOpenAlgorithmSettings
            )
        }
        item {
            SettingsDoor(
                icon = Icons.Filled.Sell,
                title = "תגיות ומילות שיר",
                subtitle = "תיקון שמות אמנים, וכתיבה לתוך הקבצים",
                onClick = onOpenTagSettings
            )
        }
        item {
            SettingsDoor(
                icon = Icons.Filled.SwapHoriz,
                title = "ייבוא וייצוא",
                subtitle = "רשימות השמעה, פנימה והחוצה",
                onClick = onOpenTransfer
            )
        }
        item {
            SettingsDoor(
                icon = Icons.Filled.Info,
                title = "מידע ואבחון",
                subtitle = "גרסה, מה הסריקה מצאה, ומה המנוע יודע עליך",
                onClick = onOpenAbout
            )
        }
    }
}

@Composable
internal fun SeparationDialog(
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
internal fun FolderDialog(
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
internal fun Stat(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Spacer(Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** The header that folds a whole area of settings away behind one tap. */
@Composable
internal fun SectionToggleRow(
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

