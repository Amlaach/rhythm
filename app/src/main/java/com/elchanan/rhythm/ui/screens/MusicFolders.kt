package com.elchanan.rhythm.ui.screens

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.elchanan.rhythm.ui.theme.Accent
import com.elchanan.rhythm.ui.theme.Surface1
import com.elchanan.rhythm.ui.theme.Text
import com.elchanan.rhythm.ui.theme.TextSecondary
import com.elchanan.rhythm.ui.theme.localized

/** Where the internal storage is mounted; what the system picker calls "primary". */
private const val INTERNAL = "/storage/emulated/0"

/**
 * The folder a system folder-picker answer points at, as a path, or null for
 * a place that is not a folder on the storage (Downloads' own view, a cloud
 * provider). Paths because that is what the library is scanned by.
 */
internal fun folderPathOf(tree: Uri): String? {
    if (tree.authority != "com.android.externalstorage.documents") return null
    val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return null
    val volume = id.substringBefore(':')
    val inside = id.substringAfter(':', "").trim('/')
    val base = if (volume.equals("primary", ignoreCase = true)) INTERNAL else "/storage/$volume"
    return if (inside.isEmpty()) base else "$base/$inside"
}

/** A folder as a person reads it: the storage it is on, then its own path. */
internal fun folderLabel(path: String): String = when {
    path == INTERNAL -> localized("אחסון פנימי").orEmpty()
    path.startsWith("$INTERNAL/") -> path.removePrefix("$INTERNAL/")
    path.startsWith("/storage/") ->
        localized("כרטיס זיכרון").orEmpty() + "/" + path.removePrefix("/storage/").substringAfter('/', "")
    else -> path
}.trimEnd('/')

/**
 * Which folders the music comes from. None chosen is the whole device, which
 * is what the app has always done; choosing some makes the library only what
 * is in them (and their subfolders). A song left outside is hidden, not
 * forgotten: its plays, ratings and analysis are there again the moment its
 * folder is added back.
 */
@Composable
internal fun MusicFoldersDialog(
    initial: List<String>,
    onDismiss: () -> Unit,
    onApply: (List<String>) -> Unit
) {
    var folders by remember { mutableStateOf(initial) }
    var refused by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val path = folderPathOf(uri)
        refused = path == null
        if (path != null && path !in folders) {
            // A folder inside one already chosen adds nothing; one around
            // chosen folders takes their place.
            if (folders.none { path.startsWith("$it/") }) {
                folders = folders.filterNot { it.startsWith("$path/") } + path
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        title = { Text("תיקיות המוזיקה") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "מאילו תיקיות האפליקציה מביאה את המוזיקה, כולל כל התיקיות שבתוכן. " +
                        "בלי תיקייה נבחרת נסרק כל המכשיר. שיר שנשאר בחוץ רק מוסתר — " +
                        "ההשמעות, הדירוגים והניתוח שלו חוזרים איתו.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(Modifier.height(10.dp))
                if (folders.isEmpty()) {
                    Text("כל המכשיר", style = MaterialTheme.typography.bodyLarge)
                }
                for (folder in folders) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Folder, contentDescription = null, tint = Accent, modifier = Modifier.size(20.dp))
                        Text(
                            folderLabel(folder),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                        )
                        IconButton(onClick = { folders = folders - folder }) {
                            Icon(Icons.Filled.Close, contentDescription = localized("הסר"), tint = TextSecondary)
                        }
                    }
                }
                TextButton(onClick = { picker.launch(null) }) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = Accent)
                    Text("הוסף תיקייה", color = Accent)
                }
                if (refused) {
                    Text(
                        "אפשר לבחור רק תיקייה מהאחסון של הטלפון או מכרטיס הזיכרון.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(folders); onDismiss() }) { Text("שמור וסרוק", color = Accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("ביטול", color = TextSecondary) }
        }
    )
}
