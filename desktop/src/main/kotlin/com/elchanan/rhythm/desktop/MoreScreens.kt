package com.elchanan.rhythm.desktop

import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.runtime.mutableStateMapOf
import com.elchanan.rhythm.engine.HebrewSpelling
import com.elchanan.rhythm.ui.theme.localized

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import com.elchanan.rhythm.ui.theme.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.elchanan.rhythm.data.TagFixer
import com.elchanan.rhythm.data.db.BookmarkEntity
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.engine.Lyrics
import com.elchanan.rhythm.engine.RecapData
import com.elchanan.rhythm.ui.theme.Accent
import com.elchanan.rhythm.ui.theme.Accent2
import com.elchanan.rhythm.ui.theme.AppBackground
import com.elchanan.rhythm.ui.theme.Bg
import com.elchanan.rhythm.ui.theme.RhythmMark
import com.elchanan.rhythm.ui.theme.Surface1
import com.elchanan.rhythm.ui.theme.Surface2
import com.elchanan.rhythm.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shown once, before the library has been analysed.
 *
 * The first few minutes are the worst the app ever looks: the shelves are
 * built from listening history that does not exist yet, and the analyser is
 * still working through the files. Without a word of explanation that reads
 * as a broken app rather than one that is still filling up, and most people
 * who close it then never open it again.
 */
@Composable
internal fun WelcomeScreen(songCount: Int, onStart: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.0f to Accent.copy(alpha = 0.16f),
                        0.35f to Accent2.copy(alpha = 0.06f),
                        0.7f to Color.Transparent
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.widthIn(max = 460.dp).padding(horizontal = GUTTER),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            RhythmMark(size = 64.dp)
            Spacer(Modifier.height(18.dp))
            Text("ברוך הבא ל־Rhythm", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (songCount > 0) {
                    "נמצאו $songCount שירים"
                } else {
                    "בחר תיקיית מוזיקה כדי להתחיל"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
            Spacer(Modifier.height(26.dp))
            WelcomeStep(
                icon = Icons.Filled.GraphicEq,
                title = "עכשיו מנתח את המוזיקה",
                body = "האפליקציה מאזינה לכל שיר ומודדת קצב, סולם ואנרגיה. " +
                    "זה רץ ברקע וייקח כמה דקות. הכל קורה על המחשב, בלי אינטרנט."
            )
            Spacer(Modifier.height(14.dp))
            WelcomeStep(
                icon = Icons.Filled.Star,
                title = "הפיד ילמד ממך",
                body = "לייקים, דירוגים ומה שאתה מדלג עליו הם מה שבונה את ההמלצות. " +
                    "בהתחלה הפיד דליל — הוא מתמלא ככל שאתה מאזין."
            )
            Spacer(Modifier.height(14.dp))
            WelcomeStep(
                icon = Icons.Filled.Sell,
                title = "שירים שהורדת מהאינטרנט",
                body = "התגיות שלהם לרוב שגויות וכל השירים נראים כמו אמן אחד. " +
                    "בהגדרות יש תיקון אוטומטי שמסדר את זה."
            )
            Spacer(Modifier.height(30.dp))
            Button(
                onClick = onStart,
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                modifier = Modifier.fillMaxWidth()
            ) { Text("יאללה, בוא נתחיל") }
        }
    }
}

@Composable
private fun WelcomeStep(icon: ImageVector, title: String, body: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.Top) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
    }
}

/**
 * What a year of listening looked like.
 *
 * Counted by [com.elchanan.rhythm.engine.Recap] over in :engine, which is the
 * same code the phone counts with - the two builds keep their history in
 * different databases and have to report the same numbers from it.
 */
@Composable
internal fun RecapScreen(recap: RecapData?, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().background(AppBackground)) {
        DetailTopBar(title = "הסיכום שלך", onBack = onBack)
        if (recap == null || recap.totalPlays == 0) {
            EmptyState(
                title = "עוד אין מה לסכם",
                body = "אחרי כמה שעות של האזנה יהיה כאן סיכום."
            )
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Row(
                    modifier = Modifier.padding(horizontal = GUTTER, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    BigNumber("${recap.totalMinutes}", "דקות")
                    BigNumber("${recap.totalPlays}", "השמעות")
                    BigNumber("${recap.distinctSongs}", "שירים")
                    BigNumber("${recap.distinctArtists}", "אמנים")
                }
            }
            item {
                Column(modifier = Modifier.padding(horizontal = GUTTER)) {
                    Text("רצף", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "הרצף הנוכחי ${recap.currentStreakDays} ימים · " +
                            "הארוך ביותר ${recap.longestStreakDays} ימים",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    if (recap.busiestDay.isNotEmpty()) {
                        Text(
                            "היום הכי עמוס: יום ${recap.busiestDay}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    if (recap.firstPlayAt > 0) {
                        Text(
                            "מאזין מאז ${dayOf(recap.firstPlayAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }
            item { HourChart(recap.byHour) }
            item {
                Text(
                    "האמנים שלך",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = GUTTER, vertical = 8.dp)
                )
            }
            itemsIndexed(recap.topArtists) { index, (name, plays) ->
                RankRow(index + 1, name, "$plays השמעות")
            }
            item {
                Text(
                    "השירים שלך",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = GUTTER, vertical = 8.dp)
                )
            }
            itemsIndexed(recap.topSongs) { index, (song, plays) ->
                RankRow(index + 1, song.title, "${song.artistName} · $plays השמעות")
            }
        }
    }
}

private fun dayOf(millis: Long): String =
    SimpleDateFormat("d/M/yyyy", Locale.forLanguageTag("he")).format(Date(millis))

@Composable
private fun BigNumber(value: String, label: String) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Surface1)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(value, style = MaterialTheme.typography.headlineSmall, color = Accent)
        Text(label, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
    }
}

/**
 * Plays by hour of the day, as twenty four bars.
 *
 * Scaled to the busiest hour rather than to a fixed height, because the shape
 * is the whole point - whether someone listens in the morning or at night -
 * and an absolute scale flattens that to nothing on a small history.
 */
@Composable
private fun HourChart(byHour: List<Int>) {
    val peak = (byHour.maxOrNull() ?: 0).coerceAtLeast(1)
    Column(modifier = Modifier.padding(horizontal = GUTTER, vertical = 12.dp)) {
        Text("מתי אתה מאזין", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().height(90.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            for (hour in byHour.indices) {
                val share = byHour[hour].toFloat() / peak
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.Bottom,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            // A floor of a few pixels, so an hour with one
                            // play is visibly different from an hour with none.
                            .height((6f + share * 60f).dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (share > 0f) Accent else Surface2)
                    )
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text("00:00", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            Spacer(Modifier.weight(1f))
            Text("12:00", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            Spacer(Modifier.weight(1f))
            Text("23:00", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        }
    }
}

@Composable
private fun RankRow(rank: Int, title: String, subtitle: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = GUTTER, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "$rank",
            style = MaterialTheme.typography.titleMedium,
            color = if (rank <= 3) Accent else TextSecondary,
            modifier = Modifier.width(28.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Repairs the tags a download tool wrote, with the whole change shown before
 * anything is applied.
 *
 * The preview matters more than it looks: the split is a guess at a naming
 * convention, and a library where the guess is wrong would otherwise end up
 * worse than it started with no way to tell what happened.
 */
@Composable
internal fun TagFixScreen(
    proposals: List<TagFixer.Proposal>,
    stripForeign: Boolean,
    writeToFiles: Boolean,
    onStripForeign: (Boolean) -> Unit,
    onWriteToFiles: (Boolean) -> Unit,
    onApply: (List<TagFixer.Proposal>, Boolean) -> Unit,
    onEdit: (Long, String, String) -> Unit,
    onBack: () -> Unit,
    onOpenHebrewNames: () -> Unit = {}
) {
    var filter by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<TagFixer.Proposal?>(null) }

    val changed = proposals.filter { it.changed }
    // The uncertain ones are shown and marked, and applied only when asked.
    // The phone's rule.
    val uncertain = changed.count { !it.certain }
    var includeUncertain by remember { mutableStateOf(false) }
    val applying = changed.count { it.certain || includeUncertain }
    val shown = if (filter.isBlank()) {
        changed
    } else {
        changed.filter {
            it.oldTitle.contains(filter, true) || it.oldArtist.contains(filter, true) ||
                it.newTitle.contains(filter, true) || it.newArtist.contains(filter, true)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(AppBackground)) {
        DetailTopBar(title = "תיקון תגיות", onBack = onBack)
        Column(modifier = Modifier.padding(horizontal = GUTTER)) {
            Text(
                text = if (changed.isEmpty()) {
                    "אין מה לתקן — התגיות נראות בסדר"
                } else {
                    "$applying שירים ישתנו"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(
                    label = "הסרת טקסט באנגלית",
                    selected = stripForeign,
                    onClick = { onStripForeign(!stripForeign) }
                )
                Chip(
                    label = "כתיבה לקבצים",
                    selected = writeToFiles,
                    onClick = { onWriteToFiles(!writeToFiles) }
                )
            }
            if (uncertain > 0) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { includeUncertain = !includeUncertain },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("כולל הצעות לא בטוחות ($uncertain)", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "כדאי לעבור עליהן ברשימה - הן מסומנות \"לא בטוח\"",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Switch(
                        checked = includeUncertain,
                        onCheckedChange = { includeUncertain = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = Accent, checkedTrackColor = Accent.copy(alpha = 0.4f))
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                label = { Text("סינון") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { onApply(proposals, includeUncertain) },
                enabled = applying > 0,
                colors = ButtonDefaults.buttonColors(containerColor = Accent)
            ) { Text("החל על $applying שירים") }
            Spacer(Modifier.height(10.dp))
            // Names written in English letters, offered in Hebrew - a screen
            // of its own, because every one of them is the listener's call.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Surface1)
                    .clickable(onClick = onOpenHebrewNames)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("שמות בעברית", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "הצעות לאיות בעברית לאמנים ושירים שכתובים באותיות אנגליות",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                Button(onClick = onOpenHebrewNames, colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                    Text("הצג")
                }
            }
        }

        LazyColumn(contentPadding = PaddingValues(top = 10.dp, bottom = 32.dp)) {
            items(shown, key = { it.songId }) { proposal ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { editing = proposal }
                        .padding(horizontal = GUTTER, vertical = 7.dp)
                ) {
                    // Before over after, both in full. A diff that only shows
                    // the new value asks someone to approve a change they
                    // cannot see.
                    Text(
                        "${proposal.oldArtist} — ${proposal.oldTitle}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        (if (proposal.certain) "" else "לא בטוח · ") + "${proposal.newArtist} — ${proposal.newTitle}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (proposal.certain) Accent else TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (proposal.albumChanged) {
                        Text(
                            "אלבום: ${proposal.oldAlbum} ← ${proposal.newAlbum}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }

    // Hand editing, for the files the pattern cannot help with: a title with
    // no separator, a guest artist the split got wrong, a name spelled two
    // ways.
    editing?.let { proposal ->
        var title by remember(proposal.songId) { mutableStateOf(proposal.newTitle) }
        var artist by remember(proposal.songId) { mutableStateOf(proposal.newArtist) }
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
                        onEdit(proposal.songId, title, artist)
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
}

/**
 * What is playing and what follows it.
 *
 * The phone reaches this from inside the player; a window has room to show it
 * as a screen of its own. Either way it is the one place the queue is a list
 * of songs rather than an implication.
 */
@Composable
internal fun QueueScreen(
    queue: List<SongEntity>,
    index: Int,
    onBack: () -> Unit,
    onPlay: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onClear: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().background(AppBackground)) {
        DetailTopBar(title = "התור", onBack = onBack)
        if (queue.isEmpty()) {
            EmptyState(title = "התור ריק", body = "כל דבר שתנגן ייכנס לכאן.")
            return@Column
        }
        Row(
            modifier = Modifier.padding(horizontal = GUTTER, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${queue.size} שירים · ${queue.size - index - 1} אחרי הנוכחי",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(onClick = onClear) { Text("נקה", color = TextSecondary) }
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            itemsIndexed(queue, key = { position, song -> "$position:${song.id}" }) { position, song ->
                SongRow(
                    song = song,
                    isCurrent = position == index,
                    onClick = { onPlay(position) },
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (position < index) {
                                Text(
                                    "הושמע",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                            }
                            IconButton(
                                onClick = { onRemove(position) },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    // A cross, because this removes the
                                    // track. It was a drag handle, which is
                                    // an offer to reorder - something this
                                    // queue does not do, and the wrong thing
                                    // to promise on a button that deletes.
                                    Icons.Filled.Close,
                                    contentDescription = localized("הסר מהתור"),
                                    tint = TextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                )
            }
        }
    }
}

/**
 * The places the listener marked in the track that is playing.
 *
 * Worth having for the same reason as resuming: in an hour of speech, "the
 * bit about the third question" is somewhere around forty minutes in, and
 * without a mark the only way back is to drag the bar until it sounds right.
 */
@Composable
internal fun BookmarksDialog(
    song: SongEntity,
    bookmarks: List<BookmarkEntity>,
    positionMs: Long,
    onAdd: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
    onSeek: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    var naming by remember { mutableStateOf(false) }
    var label by remember { mutableStateOf("") }

    if (naming) {
        AlertDialog(
            onDismissRequest = { naming = false },
            containerColor = Surface1,
            title = { Text("סימנייה ב־${formatDuration(positionMs)}") },
            text = {
                DialogBody {
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        singleLine = true,
                        label = { Text("על מה מדובר כאן") }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onAdd(positionMs, label.trim())
                    label = ""
                    naming = false
                }) { Text("שמור", color = Accent) }
            },
            dismissButton = {
                TextButton(onClick = { naming = false }) {
                    Text("ביטול", color = TextSecondary)
                }
            }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        title = { Text("סימניות") },
        text = {
            Column {
                Text(
                    song.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { naming = true }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.BookmarkAdd, contentDescription = null, tint = Accent)
                    Spacer(Modifier.width(12.dp))
                    Text("סמן את ${formatDuration(positionMs)}")
                }
                if (bookmarks.isEmpty()) {
                    Text(
                        "עוד אין סימניות בשיר הזה",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                        items(bookmarks, key = { it.id }) { mark ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onSeek(mark.positionMs)
                                        onDismiss()
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Filled.Bookmark,
                                    contentDescription = null,
                                    tint = TextSecondary
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        mark.label.ifBlank { "סימנייה" },
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        formatDuration(mark.positionMs),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary
                                    )
                                }
                                IconButton(onClick = { onDelete(mark.id) }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = localized("מחק"),
                                        tint = TextSecondary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("סגור", color = TextSecondary) }
        }
    )
}

/**
 * Stop playing, later.
 *
 * The one option that is not a number of minutes is the one most people
 * actually want: finish this track and stop. A timer that cuts off mid-song
 * is a timer people turn off.
 */
@Composable
internal fun SleepDialog(
    armedMs: Long?,
    afterTrack: Boolean,
    onMinutes: (Int) -> Unit,
    onAfterTrack: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        title = { Text("טיימר שינה") },
        text = {
            DialogBody {
                Column {
                    val state = when {
                        afterTrack -> "ייעצר בסוף השיר הנוכחי"
                        armedMs != null -> "ייעצר בעוד ${formatDuration(armedMs)}"
                        else -> "לא פעיל"
                    }
                    Text(state, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    Spacer(Modifier.height(10.dp))
                    for (minutes in listOf(15, 30, 45, 60, 90)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onMinutes(minutes)
                                    onDismiss()
                                }
                                .padding(vertical = 10.dp)
                        ) { Text("בעוד $minutes דקות") }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onAfterTrack()
                                onDismiss()
                            }
                            .padding(vertical = 10.dp)
                    ) { Text("בסוף השיר הנוכחי") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onCancel()
                onDismiss()
            }) { Text("בטל טיימר", color = Accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("סגור", color = TextSecondary) }
        }
    )
}

/**
 * The words, followed along if the file carries timestamps.
 *
 * Two quite different things wearing one screen. A plain lyric sheet is text
 * to read; an LRC is a position in a song, and the line being sung has to be
 * obvious without anyone hunting for it - which is what the colour and the
 * weight are for. Everything else is deliberately quiet.
 *
 * Nothing is fetched. If the words are not in the file or beside it, there
 * are none, and saying so plainly is better than a spinner that never ends.
 */
@Composable
internal fun LyricsScreen(
    song: SongEntity,
    words: Words?,
    positionMs: Long,
    onBack: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().background(AppBackground)) {
        DetailTopBar(title = song.title, onBack = onBack)
        if (words == null) {
            EmptyState(
                title = "אין מילים לשיר הזה",
                body = "המילים נקראות מתוך הקובץ, או מקובץ LRC לידו. " +
                    "בהגדרות אפשר לבחור תיקייה שבה הם נשמרים."
            )
            return@Column
        }
        val timed = remember(words.lrc) { Lyrics.parseLrc(words.lrc) }
        if (timed.isEmpty()) {
            LazyColumn(contentPadding = PaddingValues(GUTTER)) {
                item {
                    Text(words.plain, style = MaterialTheme.typography.bodyLarge)
                }
            }
            return@Column
        }
        // The last line whose timestamp has passed. A binary search would be
        // quicker and would also be the wrong shape: this runs once a second
        // over a few hundred lines, and the linear scan is what makes the
        // "no line yet" case before the first timestamp fall out for free.
        val active = timed.indexOfLast { it.timeMs <= positionMs }
        val listState = rememberLazyListState()
        LaunchedEffect(active) {
            if (active >= 0) {
                // Kept a third of the way down rather than at the top, so the
                // lines about to be sung are visible too.
                listState.animateScrollToItem(maxOf(0, active - 2))
            }
        }
        LazyColumn(state = listState, contentPadding = PaddingValues(GUTTER)) {
            itemsIndexed(timed) { index, line ->
                Text(
                    text = line.text,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (index == active) Accent else TextSecondary,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                )
            }
        }
    }
}

/**
 * Hebrew spellings for artists and songs written in English letters, offered
 * one by one - the phone's screen of the same name. Nothing starts ticked:
 * some songs really are called something in English.
 */
@Composable
internal fun HebrewNamesScreen(
    suggestions: List<HebrewSpelling.Suggestion>?,
    artistOf: (Long) -> String,
    onApply: (List<HebrewSpelling.Suggestion>) -> Unit,
    onBack: () -> Unit
) {
    val chosen = remember { mutableStateMapOf<String, String>() }
    var editing by remember { mutableStateOf<HebrewSpelling.Suggestion?>(null) }
    fun keyOf(s: HebrewSpelling.Suggestion) = "${s.field}:${s.latin}:${s.songIds.first()}"
    val all = suggestions.orEmpty()

    Column(modifier = Modifier.fillMaxSize().background(AppBackground)) {
        DetailTopBar(title = "שמות בעברית", onBack = onBack)
        Column(modifier = Modifier.padding(horizontal = GUTTER)) {
            Text(
                "שמות של אמנים ושירים שכתובים באותיות אנגליות, ואיך הם נכתבים בעברית. " +
                    "זו הצעה בלבד: יש שירים ששמם האמיתי באנגלית, אז שום דבר לא מסומן מראש. " +
                    "לחיצה על הצעה מאפשרת לתקן את האיות.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        onApply(all.mapNotNull { s -> chosen[keyOf(s)]?.let { s.copy(hebrew = it) } })
                        chosen.clear()
                    },
                    enabled = chosen.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                ) { Text("החל על ${chosen.size}") }
                Spacer(Modifier.width(10.dp))
                if (all.isNotEmpty()) {
                    TextButton(onClick = {
                        if (chosen.size == all.size) chosen.clear()
                        else all.forEach { chosen[keyOf(it)] = chosen[keyOf(it)] ?: it.hebrew }
                    }) { Text(if (chosen.size == all.size) "בטל הכל" else "בחר הכל", color = TextSecondary) }
                }
            }
        }
        LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp)) {
            if (suggestions == null) {
                item { Text("מחפש…", color = TextSecondary, modifier = Modifier.padding(GUTTER)) }
            } else if (all.isEmpty()) {
                item {
                    Text(
                        "אין כרגע שמות באנגלית שנמצא להם איות בעברית.",
                        color = TextSecondary,
                        modifier = Modifier.padding(GUTTER)
                    )
                }
            }
            for (field in HebrewSpelling.Field.entries) {
                val group = all.filter { it.field == field }
                if (group.isEmpty()) continue
                item(key = "title:$field") {
                    Text(
                        (if (field == HebrewSpelling.Field.ARTIST) "אמנים" else "שירים") + " (${group.size})",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = GUTTER, end = GUTTER, top = 12.dp, bottom = 4.dp)
                    )
                }
                items(group, key = { keyOf(it) }) { s ->
                    val ticked = chosen[keyOf(s)]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = GUTTER, vertical = 3.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (ticked != null) Accent.copy(alpha = 0.14f) else Surface1)
                            .clickable { editing = s }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(ticked ?: s.hebrew, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(s.latin, style = MaterialTheme.typography.bodySmall, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val detail = if (field == HebrewSpelling.Field.ARTIST) "${s.songIds.size} שירים" else artistOf(s.songIds.first())
                            Text(
                                detail + if (s.fromLibrary) " · כך כתוב בספרייה" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (s.fromLibrary) Accent else TextSecondary,
                                maxLines = 1
                            )
                        }
                        IconButton(onClick = {
                            if (ticked != null) chosen.remove(keyOf(s)) else chosen[keyOf(s)] = s.hebrew
                        }) {
                            Icon(
                                if (ticked != null) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                                contentDescription = localized(if (ticked != null) "מסומן" else "לא מסומן"),
                                tint = if (ticked != null) Accent else TextSecondary
                            )
                        }
                    }
                }
            }
        }
    }

    editing?.let { s ->
        var text by remember(s) { mutableStateOf(chosen[keyOf(s)] ?: s.hebrew) }
        AlertDialog(
            onDismissRequest = { editing = null },
            containerColor = Surface1,
            title = { Text(s.latin) },
            text = {
                DialogBody {
                    OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, label = { Text("האיות בעברית") })
                }
            },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = {
                    chosen[keyOf(s)] = text.trim()
                    editing = null
                }) { Text("סמן", color = Accent) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("ביטול", color = TextSecondary) } }
        )
    }
}
