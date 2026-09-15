package com.elchanan.rhythm.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PostAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elchanan.rhythm.ui.ArtistInfo
import com.elchanan.rhythm.ui.MainViewModel
import com.elchanan.rhythm.engine.Styles
import com.elchanan.rhythm.ui.components.Chip
import com.elchanan.rhythm.ui.components.StarRow
import com.elchanan.rhythm.ui.theme.Accent
import com.elchanan.rhythm.ui.theme.Bg
import com.elchanan.rhythm.ui.theme.BgElevated
import com.elchanan.rhythm.ui.theme.Surface1
import com.elchanan.rhythm.ui.theme.Surface3
import com.elchanan.rhythm.ui.theme.TextSecondary
import com.elchanan.rhythm.ui.theme.gradientFor
import kotlinx.coroutines.launch
import java.util.Locale

private val FILTERS = listOf("הכל", "לא מדורגים", "מדורגים", "בלי סגנון")

@Composable
fun ArtistRatingsScreen(vm: MainViewModel, onOpenArtist: () -> Unit) {
    val library by vm.library.collectAsStateWithLifecycle()
    val report by vm.report.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(0) }
    var bulkOpen by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf(setOf<String>()) }
    var groupOpen by remember { mutableStateOf(false) }

    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val topPad = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    val artists = library.artists
        .filter { a ->
            when (filter) {
                1 -> a.rating == 0
                2 -> a.rating > 0
                3 -> a.styles.isBlank()
                else -> true
            }
        }
        .filter { it.displayName.lowercase(Locale.ROOT).contains(query.lowercase(Locale.ROOT)) }
        .sortedWith(compareByDescending<ArtistInfo> { it.songs.size }.thenBy { it.displayName })

    Column(modifier = Modifier.fillMaxSize().background(Bg)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = topPad)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("דירוג אמנים", style = MaterialTheme.typography.headlineMedium)
                val r = report
                Text(
                    text = if (r == null) "" else "${r.ratedArtists} מתוך ${r.totalArtists} אמנים דורגו",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
            IconButton(onClick = { bulkOpen = true }) {
                Icon(Icons.Filled.PostAdd, contentDescription = "הזנה מרוכזת", tint = Accent)
            }
            IconButton(onClick = {
                scope.launch {
                    clipboard.setText(AnnotatedString(vm.exportArtistsJson()))
                }
            }) {
                Icon(Icons.Filled.ContentCopy, contentDescription = "העתק גיבוי", tint = TextSecondary)
            }
        }

        TextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp),
            placeholder = { Text("סינון לפי שם", color = TextSecondary) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Surface1,
                unfocusedContainerColor = Surface1,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = Accent,
                focusedTextColor = MaterialTheme.colorScheme.onBackground,
                unfocusedTextColor = MaterialTheme.colorScheme.onBackground
            )
        )

        LazyRow(
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(FILTERS.size) { i ->
                Chip(label = FILTERS[i], selected = filter == i, onClick = { filter = i })
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(bottom = 40.dp),
            modifier = Modifier.weight(1f)
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Chip(
                        label = "בחר הכל (${artists.size})",
                        selected = selection.size == artists.size && artists.isNotEmpty(),
                        onClick = {
                            selection = if (selection.size == artists.size) emptySet()
                            else artists.map { it.key }.toSet()
                        }
                    )
                    Chip(
                        label = "לחיצה ארוכה = בחירה",
                        selected = false,
                        onClick = { }
                    )
                }
            }
            items(artists, key = { it.key }) { artist ->
                RatingRow(
                    artist = artist,
                    selected = artist.key in selection,
                    selectionMode = selection.isNotEmpty(),
                    onRate = { vm.rateArtist(artist.key, artist.displayName, it, artist.styles, artist.note) },
                    onOpen = {
                        if (selection.isNotEmpty()) {
                            selection = if (artist.key in selection) selection - artist.key
                            else selection + artist.key
                        } else {
                            vm.openArtist(artist)
                            onOpenArtist()
                        }
                    },
                    onLongPress = {
                        selection = if (artist.key in selection) selection - artist.key
                        else selection + artist.key
                    }
                )
            }
        }

        if (selection.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BgElevated)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { selection = emptySet() }) {
                    Icon(Icons.Filled.Close, contentDescription = "בטל", tint = TextSecondary)
                }
                Text("${selection.size} אמנים נבחרו", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { groupOpen = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                ) { Text("דרג ותייג") }
            }
        }
    }

    if (groupOpen) {
        GroupEditDialog(
            count = selection.size,
            onDismiss = { groupOpen = false },
            onApply = { rating, styles, replace ->
                vm.bulkUpdateArtists(selection.toList(), rating, styles, replace)
                groupOpen = false
                selection = emptySet()
            }
        )
    }

    if (bulkOpen) {
        BulkImportDialog(
            onDismiss = { bulkOpen = false },
            onSubmit = {
                vm.bulkImportArtists(it)
                bulkOpen = false
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RatingRow(
    artist: ArtistInfo,
    selected: Boolean,
    selectionMode: Boolean,
    onRate: (Int) -> Unit,
    onOpen: () -> Unit,
    onLongPress: () -> Unit
) {
    val (c1, c2) = gradientFor(artist.key)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) Accent.copy(alpha = 0.16f) else Color.Transparent)
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectionMode) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = if (selected) Accent else Surface3,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
        }
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(c1, c2))),
            contentAlignment = Alignment.Center
        ) {
            Text(artist.displayName.take(1), color = Color.White, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(artist.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(
                text = if (artist.styles.isBlank()) "${artist.songs.size} שירים · בלי סגנון"
                else artist.styles,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 1
            )
            Spacer(Modifier.height(4.dp))
            StarRow(rating = artist.rating, onRate = onRate, size = 20)
        }
    }
}

/** Rate and tag many artists in one action - the fastest way to teach the engine. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupEditDialog(
    count: Int,
    onDismiss: () -> Unit,
    onApply: (Int?, List<String>?, Boolean) -> Unit
) {
    var rating by remember { mutableStateOf(0) }
    var styles by remember { mutableStateOf(listOf<String>()) }
    var replace by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        title = { Text("$count אמנים") },
        text = {
            Column(modifier = Modifier.heightIn(max = 400.dp)) {
                Text("דירוג", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                StarRow(rating = rating, onRate = { rating = it }, size = 28)
                Text(
                    text = if (rating == 0) "בלי כוכבים — הדירוג הקיים לא ישתנה"
                    else "כל האמנים שנבחרו יקבלו $rating כוכבים",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(Modifier.height(14.dp))
                Text("סגנונות", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Styles.SUGGESTED.forEach { style ->
                        val on = styles.any { it.equals(style, ignoreCase = true) }
                        Chip(label = style, selected = on, onClick = {
                            styles = if (on) styles.filterNot { it.equals(style, ignoreCase = true) }
                            else styles + style
                        })
                    }
                }
                Spacer(Modifier.height(10.dp))
                Chip(
                    label = if (replace) "מחליף את התגיות הקיימות" else "מוסיף לתגיות הקיימות",
                    selected = replace,
                    onClick = { replace = !replace }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onApply(
                    if (rating > 0) rating else null,
                    if (styles.isEmpty()) null else styles,
                    replace
                )
            }) { Text("החל", color = Accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("ביטול", color = TextSecondary) }
        }
    )
}

@Composable
private fun BulkImportDialog(onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        title = { Text("הזנה מרוכזת של אמנים") },
        text = {
            Column {
                Text(
                    "שורה לכל אמן, בפורמט:\nשם | דירוג 1-5 | סגנונות מופרדים בפסיק\n\nלמשל:\nאברהם פריד | 5 | חסידי, מרגש\nיונתן רזאל | 4 | רגוע, שירי נשמה",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 140.dp, max = 260.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(text) }) { Text("שמור", color = Accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("ביטול", color = TextSecondary) }
        }
    )
}
