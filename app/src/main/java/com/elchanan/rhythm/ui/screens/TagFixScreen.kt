package com.elchanan.rhythm.ui.screens

import androidx.compose.foundation.layout.Box
import com.elchanan.rhythm.ui.components.DialogBody
import com.elchanan.rhythm.ui.theme.localized

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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import com.elchanan.rhythm.ui.theme.Text
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
fun TagFixScreen(vm: MainViewModel, onBack: () -> Unit, onOpenHebrewNames: () -> Unit = {}) {
    val proposals by vm.tagProposals.collectAsStateWithLifecycle()
    val library by vm.library.collectAsStateWithLifecycle()

    // Keyed on the songs themselves, not their count: a correction changes the
    // titles without changing how many there are, and keying on the size left
    // the preview showing proposals that had already been applied.
    LaunchedEffect(library.songs) { vm.buildTagProposals() }

    // The permission dialogs for writing into the files are shown by
    // RhythmRoot, for every screen that edits a file - not only this one.
    val writesToFiles = vm.prefs.writeTagsToFiles

    val changed = proposals.filter { it.changed }
    // The uncertain ones are shown and marked, and applied only when asked.
    val uncertain = changed.count { !it.certain }
    var includeUncertain by remember { mutableStateOf(false) }
    val applying = changed.count { it.certain || includeUncertain }
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
                DialogBody {
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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = localized("חזור"), tint = TextSecondary)
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
                    if (uncertain > 0) {
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { includeUncertain = !includeUncertain },
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("כולל הצעות לא בטוחות ($uncertain)", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "הצעות שנשענות על ניחוש, למשל שם שיר באנגלית שהותאם לשם בעברית. " +
                                        "כדאי לעבור עליהן ברשימה - הן מסומנות \"לא בטוח\"",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                            Switch(
                                checked = includeUncertain,
                                onCheckedChange = { includeUncertain = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Accent,
                                    checkedTrackColor = Accent.copy(alpha = 0.4f)
                                )
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Button(
                            onClick = { vm.applyTagFix(proposals, includeUncertain) },
                            enabled = applying > 0,
                            colors = ButtonDefaults.buttonColors(containerColor = Accent)
                        ) { Text("החל על $applying שירים") }
                        Spacer(Modifier.width(10.dp))
                        TextButton(onClick = { vm.resetTagFix() }) {
                            Text("שחזר מקור", color = TextSecondary)
                        }
                    }
                }
            }

            // Names written in English letters, offered in Hebrew - a screen of
            // its own, because every one of them is the listener's call.
            item { HebrewNamesDoor(gutter, onOpenHebrewNames) }

            item {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    singleLine = true,
                    placeholder = { Text("חפש שיר לעריכה") },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = gutter)
                )
            }

            fun matches(title: String, artist: String) =
                filter.isBlank() || title.contains(filter, true) || artist.contains(filter, true)

            // The suggestions first and on their own: they are what the screen
            // was opened for, and mixed into the whole library in its usual
            // order they had to be scrolled for, one here and one there.
            val suggested = changed.filter { matches(it.oldTitle, it.oldArtist) || matches(it.newTitle, it.newArtist) }
            if (suggested.isNotEmpty()) {
                item {
                    Text(
                        "הצעות לתיקון (${suggested.size})",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = gutter, vertical = 4.dp)
                    )
                }
                items(suggested, key = { "p${it.songId}" }) { proposal ->
                    val song = library.songsById[proposal.songId]
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = gutter)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Surface1)
                            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp)
                    ) {
                        Text(
                            proposal.oldTitle,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(proposal.oldArtist, style = MaterialTheme.typography.bodySmall, color = TextSecondary, maxLines = 1)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (proposal.certain) "מוצע: ${proposal.newTitle} · ${proposal.newArtist}"
                            else "לא בטוח · מוצע: ${proposal.newTitle} · ${proposal.newArtist}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (proposal.certain) Accent else TextSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        // The album too, where a site's name was all it was.
                        if (proposal.albumChanged) {
                            Text(
                                "אלבום: ${proposal.oldAlbum} ← ${proposal.newAlbum}",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        // This one only - uncertain or not, since it was picked
                        // by hand - or edited first.
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            if (song != null) {
                                TextButton(onClick = { editing = song }) { Text("ערוך", color = TextSecondary) }
                            }
                            TextButton(onClick = { vm.applyTagFix(listOf(proposal), includeUncertain = true) }) {
                                Text("החל", color = Accent)
                            }
                        }
                    }
                }
                item {
                    Text(
                        "כל השירים",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = gutter, vertical = 4.dp)
                    )
                }
            }

            // Every other song, for fixing by hand what no pattern can.
            val proposed = changed.mapTo(HashSet()) { it.songId }
            val shown = library.songs.filter { it.id !in proposed && matches(it.title, it.artistName) }

            items(shown, key = { it.id }) { song ->
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
                }
            }
        }
    }
}

/** The way to the Hebrew spellings, from the tag fixer and the tag settings. */
@Composable
internal fun HebrewNamesDoor(gutter: androidx.compose.ui.unit.Dp, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = gutter)
            .clip(RoundedCornerShape(14.dp))
            .background(Surface1)
            .clickable(onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("איות שמות", style = MaterialTheme.typography.titleSmall)
            Text(
                "הצעות לאיות שמות של אמנים ושירים בעברית או באנגלית",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Accent)
                .padding(horizontal = 14.dp, vertical = 7.dp)
        ) {
            Text("הצג", style = MaterialTheme.typography.labelLarge, color = androidx.compose.ui.graphics.Color.White)
        }
    }
}
