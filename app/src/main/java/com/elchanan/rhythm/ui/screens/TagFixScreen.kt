package com.elchanan.rhythm.ui.screens

import android.Manifest
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.elchanan.rhythm.data.db.SongEntity
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elchanan.rhythm.ui.MainViewModel
import com.elchanan.rhythm.ui.components.EmptyState
import com.elchanan.rhythm.ui.components.rememberMetrics
import com.elchanan.rhythm.ui.theme.Accent
import com.elchanan.rhythm.ui.theme.AppBackground
import com.elchanan.rhythm.ui.theme.Bg
import com.elchanan.rhythm.ui.theme.Surface1
import com.elchanan.rhythm.ui.theme.TextSecondary

/**
 * Repairs the tags a download tool wrote, with the whole change shown before
 * anything is applied.
 *
 * The preview matters more than it looks: the split is a guess at a naming
 * convention, and a library where the guess is wrong would otherwise end up
 * worse than it started, with no way to tell what happened.
 */
@Composable
fun TagFixScreen(vm: MainViewModel, onBack: () -> Unit) {
    val proposals by vm.tagProposals.collectAsStateWithLifecycle()
    val library by vm.library.collectAsStateWithLifecycle()

    // Keyed on the songs themselves, not their count: a correction changes the
    // titles without changing how many there are, and keying on the size left
    // the preview showing proposals that had already been applied.
    LaunchedEffect(library.songs) { vm.buildTagProposals() }

    // Writing into the files needs the system's own permission dialog from
    // Android 11 on, and only an activity can show it.
    val writesToFiles = vm.prefs.writeTagsToFiles
    val permissionRequest by vm.writePermissionRequest.collectAsStateWithLifecycle()
    val writeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        vm.onWritePermissionResult(result.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(permissionRequest) {
        permissionRequest?.let {
            writeLauncher.launch(IntentSenderRequest.Builder(it).build())
        }
    }

    // Below Android 11 there is no per file dialog, just the old storage
    // permission, which the app has never had a reason to ask for until now.
    val legacyRequest by vm.legacyPermissionRequest.collectAsStateWithLifecycle()
    val legacyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> vm.onWritePermissionResult(granted) }
    LaunchedEffect(legacyRequest) {
        if (legacyRequest) {
            legacyLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    val changed = proposals.filter { it.changed }
    val gutter = rememberMetrics().gutter
    var filter by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<SongEntity?>(null) }

    // Hand editing, for the files the pattern cannot help with: a title with no
    // separator, a guest artist the split got wrong, a name spelled two ways.
    editing?.let { song ->
        var title by remember(song.id) { mutableStateOf(song.title) }
        var artist by remember(song.id) { mutableStateOf(song.artistName) }
        AlertDialog(
            onDismissRequest = { editing = null },
            containerColor = Surface1,
            title = { Text("עריכת תגיות") },
            text = {
                Column {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        singleLine = true,
                        label = { Text("שם השיר") }
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = artist,
                        onValueChange = { artist = it },
                        singleLine = true,
                        label = { Text("שם האמן") }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = title.isNotBlank() && artist.isNotBlank(),
                    onClick = {
                        vm.saveTagOverride(song.id, title, artist)
                        editing = null
                    }
                ) { Text("שמור", color = Accent) }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) {
                    Text("ביטול", color = TextSecondary)
                }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(AppBackground)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "חזור", tint = TextSecondary)
            }
            Spacer(Modifier.width(4.dp))
            Text("תיקון תגיות", style = MaterialTheme.typography.titleLarge)
        }

        LazyColumn(
            contentPadding = PaddingValues(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Column(modifier = Modifier.padding(horizontal = gutter, vertical = 8.dp)) {
                    Text(
                        "לכל קובץ יש תווית פנימית עם שם השיר ושם האמן. בקבצים שהורדו מהאינטרנט " +
                            "התווית לרוב שגויה — שם האמן דחוס בתוך שם השיר, ובשדה האמן יושב שם " +
                            "הערוץ. בגלל זה כל השירים נראים כמו אמן אחד.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (writesToFiles) {
                            "לפי ההגדרות, התיקון ייכתב גם לתוך קבצי ה־MP3 עצמם. " +
                                "העטיפה ושאר התגיות נשמרות, אבל שינוי בקובץ אי אפשר לבטל " +
                                "מתוך האפליקציה. כדי לתקן רק כאן, כבה את ההגדרה."
                        } else {
                            "התיקון נשמר באפליקציה בלבד ולא נוגע בקבצים עצמם, אז אין שום סיכון " +
                                "שמשהו ייהרס. אפשר לבטל בכל רגע."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (writesToFiles) Accent else TextSecondary
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Button(
                            onClick = { vm.applyTagFix(proposals) },
                            enabled = changed.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = Accent)
                        ) { Text("החל על ${changed.size} שירים") }
                        Spacer(Modifier.width(10.dp))
                        TextButton(onClick = { vm.resetTagFix() }) {
                            Text("שחזר מקור", color = TextSecondary)
                        }
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    singleLine = true,
                    placeholder = { Text("חפש שיר לעריכה") },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = gutter)
                )
            }

            val shown = library.songs.filter {
                filter.isBlank() ||
                    it.title.contains(filter, true) ||
                    it.artistName.contains(filter, true)
            }
            val proposalById = changed.associateBy { it.songId }

            items(shown, key = { it.id }) { song ->
                val proposal = proposalById[song.id]
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = gutter)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Surface1)
                        .clickable { editing = song }
                        .padding(12.dp)
                ) {
                    Text(
                        song.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        song.artistName,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 1
                    )
                    if (proposal != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "מוצע: ${proposal.newTitle} · ${proposal.newArtist}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Accent,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
