package com.elchanan.rhythm.ui.screens

import com.elchanan.rhythm.ui.components.DialogBody
import com.elchanan.rhythm.ui.components.fitHeight
import com.elchanan.rhythm.ui.theme.localized

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import com.elchanan.rhythm.ui.theme.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elchanan.rhythm.engine.Styles
import com.elchanan.rhythm.ui.ArtistInfo
import com.elchanan.rhythm.ui.MainViewModel
import com.elchanan.rhythm.ui.components.Artwork
import com.elchanan.rhythm.ui.components.Chip
import com.elchanan.rhythm.ui.components.StarRow
import com.elchanan.rhythm.ui.components.rememberMetrics
import com.elchanan.rhythm.ui.theme.Accent
import com.elchanan.rhythm.ui.theme.AppBackground
import com.elchanan.rhythm.ui.theme.Bg
import com.elchanan.rhythm.ui.theme.BgElevated
import com.elchanan.rhythm.ui.theme.CaptionedIconButton
import com.elchanan.rhythm.ui.theme.Surface1
import com.elchanan.rhythm.ui.theme.Surface3
import com.elchanan.rhythm.ui.theme.TextPrimary
import com.elchanan.rhythm.ui.theme.TextSecondary
import com.elchanan.rhythm.ui.theme.TextTertiary
import com.elchanan.rhythm.ui.theme.gradientFor
import java.util.Locale
import kotlinx.coroutines.launch

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
    var mergeOpen by remember { mutableStateOf(false) }

    val merging by vm.mergingArtist.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val topPad = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val gutter = rememberMetrics().gutter

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

    Column(modifier = Modifier.fillMaxSize().background(AppBackground)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = topPad)
                .padding(horizontal = gutter, vertical = 8.dp),
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
            // Named, not bare glyphs: a red note and two squares said
            // nothing about typing in many ratings at once or copying them.
            // One word each on a small phone, where the full names would push
            // the screen's own title onto two lines.
            val narrow = rememberMetrics().isNarrow
            CaptionedIconButton(Icons.Filled.PostAdd, if (narrow) "הזנה" else "הזנה מרוכזת", { bulkOpen = true }, tint = Accent)
            CaptionedIconButton(Icons.Filled.ContentCopy, if (narrow) "גיבוי" else "העתק גיבוי", {
                scope.launch {
                    clipboard.setText(AnnotatedString(vm.exportArtistsJson()))
                }
                vm.toast("הגיבוי הועתק")
            })
        }

        TextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = gutter, vertical = 4.dp),
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
            contentPadding = PaddingValues(horizontal = gutter, vertical = 8.dp),
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
            // What the app spotted by itself, above what you can do by hand.
            // The whole list is searched, not the filtered view: a pair is
            // still a pair when one of the two is hidden by the filter.
            item { ArtistMergeSuggestions(vm, library.artists, gutter) }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = gutter, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Chip(
                        label = "בחר הכל (${artists.size})",
                        selected = selection.size == artists.size && artists.isNotEmpty(),
                        onClick = {
                            selection = if (selection.size == artists.size) emptySet()
                            else artists.map { it.key }.toSet()
                        }
                    )
                    // A hint, and drawn as one. It used to be a chip like the
                    // button beside it, and pressing it did nothing.
                    Text(
                        "לחיצה ארוכה על אמן בוחרת כמה יחד",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary,
                        modifier = Modifier.weight(1f)
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
                    Icon(Icons.Filled.Close, contentDescription = localized("בטל"), tint = TextSecondary)
                }
                Text("${selection.size} אמנים נבחרו", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.weight(1f))
                // Exactly two, because merging is a question about a pair:
                // which of these two names stays. Three at once would need
                // an answer per pair and is a worse thing to get wrong.
                if (selection.size == 2) {
                    TextButton(onClick = { mergeOpen = true }) {
                        Text("אחד", color = Accent)
                    }
                }
                Button(
                    onClick = { groupOpen = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                ) { Text("דרג ותייג") }
            }
        }
    }

    if (mergeOpen && selection.size == 2) {
        val picked = library.artists.filter { it.key in selection }
        if (picked.size == 2) {
            MergeArtistsDialog(
                first = picked[0],
                second = picked[1],
                busy = merging,
                onDismiss = { mergeOpen = false },
                onMerge = { source, target ->
                    vm.mergeArtists(source, target)
                    mergeOpen = false
                    selection = emptySet()
                }
            )
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
    val metrics = rememberMetrics()
    val gutter = metrics.gutter
    // The stars beside the name, on one line, wherever there is room for
    // them: stacked under it, every artist took a tall card of mostly empty
    // space and a library of sixty was a long scroll. A narrow phone keeps
    // them underneath, where the name still has the width to be read.
    val inline = !metrics.isCompact
    val top = artist.songs.firstOrNull()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = gutter, vertical = 3.dp)
            .clip(RoundedCornerShape(14.dp))
            // A card rather than a bare row: an artist list of plain text on a
            // dark ground had nothing to hold it together, which is why this
            // screen looked emptier than the rest of the app.
            .background(if (selected) Accent.copy(alpha = 0.18f) else Surface1)
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
            .padding(horizontal = 12.dp, vertical = if (inline) 8.dp else 10.dp),
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
        // A real cover beats an initial in a circle. Artwork already falls back
        // to the deterministic gradient when a track carries no picture.
        Artwork(
            songId = top?.id ?: -1L,
            albumId = top?.albumId ?: -1L,
            seed = artist.key,
            modifier = Modifier.size(48.dp),
            corner = 24
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                artist.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildString {
                    append("${artist.songs.size} שירים")
                    if (artist.styles.isNotBlank()) append(" · ${artist.styles}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!inline) {
                Spacer(Modifier.height(6.dp))
                StarRow(rating = artist.rating, onRate = onRate, size = 20)
            }
        }
        if (inline) {
            Spacer(Modifier.width(8.dp))
            StarRow(rating = artist.rating, onRate = onRate, size = 22)
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
            DialogBody {
                Column(modifier = Modifier.heightIn(max = fitHeight(400.dp))) {
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
            DialogBody {
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

/**
 * Merging two spellings of one name, chosen by hand.
 *
 * The app finds the pairs that are a single letter apart on its own, but a
 * great deal of what is actually the same artist is further apart than that -
 * two letters, a transposition, a nickname, a name spelled in Hebrew on one
 * file and in English on another. Nothing can suggest those; someone who
 * knows the music has to say so.
 *
 * Which name survives is the question this asks, because it cannot be
 * guessed. Neither "the one with more songs" nor "the first alphabetically"
 * is right often enough to decide silently: the correct spelling is
 * frequently the one on two files, not the one on forty.
 */
@Composable
private fun MergeArtistsDialog(
    first: ArtistInfo,
    second: ArtistInfo,
    busy: Boolean,
    onDismiss: () -> Unit,
    onMerge: (ArtistInfo, ArtistInfo) -> Unit
) {
    var keepFirst by remember { mutableStateOf(first.songs.size >= second.songs.size) }
    val target = if (keepFirst) first else second
    val source = if (keepFirst) second else first

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        title = { Text("איחוד אמנים") },
        text = {
            DialogBody {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "איזה שם יישאר? כל השירים של השני יעברו אליו.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    Spacer(Modifier.height(10.dp))
                    MergeChoice(first, keepFirst) { keepFirst = true }
                    MergeChoice(second, !keepFirst) { keepFirst = false }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "«${source.displayName}» יוחלף ב־«${target.displayName}» על " +
                            "${source.songs.size} שירים. הדירוג והסגנונות של " +
                            "${target.displayName} נשארים כפי שהם.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(Modifier.height(6.dp))
                    // Said plainly rather than left to be discovered: this is a
                    // rename inside the app, and the files keep the tags they
                    // came with.
                    Text(
                        "השינוי נשמר באפליקציה. קובצי המוזיקה עצמם לא משתנים, " +
                            "ואפשר לבטל את זה מ\"ניקוי התגיות שנוחשו\" בהגדרות.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = { onMerge(source, target) }) {
                Text("אחד", color = Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("ביטול", color = TextSecondary) }
        }
    )
}

@Composable
private fun MergeChoice(artist: ArtistInfo, chosen: Boolean, onPick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onPick)
            .background(if (chosen) Accent.copy(alpha = 0.16f) else Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (chosen) Icons.Filled.RadioButtonChecked
            else Icons.Filled.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (chosen) Accent else TextSecondary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(artist.displayName, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${artist.songs.size} שירים" +
                    if (artist.rating > 0) " · ${artist.rating} כוכבים" else "",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    }
}
