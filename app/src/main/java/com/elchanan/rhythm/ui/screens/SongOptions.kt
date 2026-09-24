package com.elchanan.rhythm.ui.screens

import com.elchanan.rhythm.ui.components.DialogBody
import com.elchanan.rhythm.ui.components.fitHeight
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.SentimentSatisfied
import androidx.compose.material.icons.filled.SentimentDissatisfied
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlaylistRemove
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import com.elchanan.rhythm.ui.theme.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.data.db.AudioFeatureEntity
import com.elchanan.rhythm.engine.AudioTags
import com.elchanan.rhythm.engine.Capo
import com.elchanan.rhythm.engine.MusicalMode
import com.elchanan.rhythm.engine.Mood
import com.elchanan.rhythm.engine.MoodMarks
import com.elchanan.rhythm.engine.Styles
import com.elchanan.rhythm.ui.MainViewModel
import com.elchanan.rhythm.ui.components.Artwork
import com.elchanan.rhythm.ui.components.Chip
import com.elchanan.rhythm.ui.components.StarRow
import com.elchanan.rhythm.ui.theme.Accent
import com.elchanan.rhythm.ui.theme.BgElevated
import com.elchanan.rhythm.ui.theme.Color_Error
import com.elchanan.rhythm.ui.theme.Surface1
import com.elchanan.rhythm.ui.theme.TextTertiary
import com.elchanan.rhythm.ui.theme.TextSecondary
import com.elchanan.rhythm.ui.theme.tempoAndKey
import com.elchanan.rhythm.ui.components.rememberMetrics

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongOptionsSheet(
    vm: MainViewModel,
    song: SongEntity,
    onDismiss: () -> Unit,
    onOpenArtist: (() -> Unit)? = null,
    onOpenAlbum: (() -> Unit)? = null,
    onOpenDetail: (() -> Unit)? = null,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    /** True when opened from the player, about the song already playing. */
    forCurrentSong: Boolean = false,
    /** The player's own volume, when the listener put it in this menu rather than on the player. */
    onVolume: (() -> Unit)? = null,
    /** Set by the player so lyrics open its synced panel, not the editor. */
    onShowLyrics: (() -> Unit)? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val gutter = rememberMetrics().gutter
    val library by vm.library.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val features by vm.featuresById.collectAsStateWithLifecycle()
    val stats = library.stats[song.id]
    val rating = stats?.rating ?: 0
    var showWhy by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    var showTags by remember { mutableStateOf(false) }
    var showCapo by remember { mutableStateOf(false) }
    var newPlaylist by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var genreOpen by remember { mutableStateOf(false) }
    var editOpen by remember { mutableStateOf(false) }
    var moodOpen by remember { mutableStateOf(false) }
    val detail by vm.detail.collectAsStateWithLifecycle()
    // Opened from inside a mood's list: the fastest place to say "not this".
    val listMood = detail?.takeIf { d -> d.songs.any { it.id == song.id } }?.let { d ->
        Mood.entries.firstOrNull { d.gradientKey == "mood:${it.name}" }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = BgElevated
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 28.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = gutter, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Artwork(song.id, song.albumId, song.artistKey, Modifier.size(56.dp), corner = 8)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(song.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(
                        song.artistName,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 1
                    )
                    features[song.id]?.let { f ->
                        Text(
                            text = tempoAndKey(f.bpm.toInt(), f.musicalKey, f.mode),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                        // What the tagging model heard, as a hint and nothing
                        // more. These are AudioSet's own words rather than the
                        // styles the user tags with, and no hint is ever
                        // written into the library.
                        val heard = AudioTags.hints(f.tags)
                        if (heard.isNotEmpty()) {
                            Text(
                                text = "רמזים מהצליל: ${heard.joinToString(" · ")}",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextTertiary
                            )
                        }
                    }
                    val plays = stats?.playCount ?: 0
                    if (plays > 0) {
                        Text(
                            text = "$plays השמעות",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextTertiary
                        )
                    }
                }
            }

            // per song rating - the most specific thing the user can say
            Column(modifier = Modifier.padding(horizontal = gutter, vertical = 6.dp)) {
                Text("דירוג השיר", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = if (rating == 0) "דורס את דירוג האמן כשמגדירים אותו"
                    else "השיר מדורג $rating מתוך 5",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(Modifier.height(6.dp))
                StarRow(rating = rating, onRate = { vm.rateSong(song.id, it) }, size = 26)
            }
            Spacer(Modifier.height(6.dp))

            // Like and dislike are deliberately not here. They sit on the row
            // itself and on the player, one tap away in both places; repeating
            // them in the menu only made the menu longer, and a long menu is
            // what stands between someone and simply playing a song.

            // What only this place offers comes first: it is why the menu
            // was opened here rather than anywhere else.
            if (onRemoveFromPlaylist != null) {
                OptionRow(Icons.Filled.PlaylistRemove, "הסר מהרשימה") {
                    onRemoveFromPlaylist(); onDismiss()
                }
            }
            if (listMood != null) {
                OptionRow(Icons.Filled.SentimentDissatisfied, "לא מתאים ל\"${listMood.label}\"") {
                    vm.setMoodMark(song, listMood, false)
                    onDismiss()
                }
            }
            if (onVolume != null) {
                OptionRow(Icons.AutoMirrored.Filled.VolumeUp, "עוצמת הנגן") { onVolume(); onDismiss() }
            }

            // The rest in the order the listener arranged (Home & display),
            // which until they do is the order below: the things people open
            // this menu for first, browsing after, the destructive last.
            val arranged = remember { SongMenu.shown(vm.prefs.songMenu) }
            for (item in arranged) when (item) {
                // Queueing the song that is already playing means nothing, so
                // the player's own menu leaves those two out.
                SongMenuItem.PLAY_NEXT -> if (!forCurrentSong) {
                    OptionRow(Icons.Filled.SkipNext, "נגן הבא") { vm.playNext(song); onDismiss() }
                }
                SongMenuItem.ADD_TO_QUEUE -> if (!forCurrentSong) {
                    OptionRow(Icons.AutoMirrored.Filled.QueueMusic, "הוסף לתור") { vm.addToQueue(song); onDismiss() }
                }
                SongMenuItem.MIX -> if (onOpenDetail != null) {
                    OptionRow(Icons.Filled.AutoAwesome, "צור מיקס מהשיר הזה") {
                        vm.createMix(song) { onOpenDetail() }
                        onDismiss()
                    }
                }
                SongMenuItem.RADIO ->
                    OptionRow(Icons.Filled.Radio, "התחל רדיו מהשיר") { vm.startRadio(song); onDismiss() }
                // Always offered, even with no playlists yet: having to leave
                // for the library tab to make the first one is the step that
                // stops playlists being used at all.
                SongMenuItem.PLAYLISTS -> {
                    Text(
                        "הוספה לרשימה",
                        style = MaterialTheme.typography.labelLarge,
                        color = TextSecondary,
                        modifier = Modifier.padding(start = gutter, top = 14.dp, bottom = 4.dp)
                    )
                    OptionRow(Icons.Filled.Add, "רשימה חדשה", tint = Accent) { newPlaylist = true }
                    playlists.forEach { info ->
                        OptionRow(Icons.AutoMirrored.Filled.PlaylistAdd, info.playlist.name) {
                            vm.addToPlaylist(info.playlist.id, song.id)
                            onDismiss()
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                SongMenuItem.LYRICS -> OptionRow(Icons.Filled.FormatQuote, "מילות השיר") {
                    if (onShowLyrics != null) {
                        onShowLyrics()
                        onDismiss()
                    } else {
                        showLyrics = true
                    }
                }
                SongMenuItem.CAPO -> OptionRow(Icons.Filled.MusicNote, "אקורדים וקאפו") { showCapo = true }
                SongMenuItem.WHY -> OptionRow(Icons.Filled.Insights, "למה זה הומלץ לי") { showWhy = true }
                SongMenuItem.TAGS -> OptionRow(Icons.Filled.LocalOffer, "תגיות סגנון לשיר") { showTags = true }
                SongMenuItem.MOOD -> OptionRow(Icons.Filled.SentimentSatisfied, "מצב רוח") { moodOpen = true }
                SongMenuItem.ARTIST -> if (onOpenArtist != null) {
                    OptionRow(Icons.Filled.Person, "עבור לאמן") { onOpenArtist(); onDismiss() }
                }
                SongMenuItem.ALBUM -> if (onOpenAlbum != null) {
                    OptionRow(Icons.Filled.Album, "עבור לאלבום") { onOpenAlbum(); onDismiss() }
                }
                SongMenuItem.GENRE -> OptionRow(Icons.Filled.LocalOffer, "שנה ז'אנר") { genreOpen = true }
                SongMenuItem.EDIT -> OptionRow(Icons.Filled.Edit, "עריכת תגיות") { editOpen = true }
                SongMenuItem.SHARE -> OptionRow(Icons.Filled.Share, "שתף") {
                    vm.shareSongs(listOf(song))
                    onDismiss()
                }
                // The detector's verdict, and a way to disagree with it. Shown
                // as the opposite of what it currently thinks, so the row says
                // what pressing it will do rather than what is already true.
                SongMenuItem.SPOKEN -> {
                    val markedSpoken = stats?.spoken == 1
                    OptionRow(
                        if (markedSpoken) Icons.Filled.MusicNote else Icons.Filled.RecordVoiceOver,
                        if (markedSpoken) "זה בעצם מוזיקה" else "סמן כהרצאה או שיעור"
                    ) {
                        vm.setSpoken(song.id, !markedSpoken)
                        onDismiss()
                    }
                }
                // Vocal-only: shown as the opposite of the current verdict,
                // like the speech row.
                SongMenuItem.VOCAL -> {
                    val vocalNow = vm.isVocal(song, features[song.id])
                    OptionRow(
                        Icons.Filled.MusicNote,
                        if (vocalNow) "זה לא ווקאלי" else "סמן כווקאלי (לספירה ולשלושת השבועות)"
                    ) {
                        vm.setVocal(song, !vocalNow)
                        onDismiss()
                    }
                }
                // Only worth offering when there is something to clear.
                SongMenuItem.RESET -> if ((stats?.playCount ?: 0) > 0) {
                    OptionRow(Icons.Filled.RestartAlt, "אפס את מספר ההשמעות") {
                        confirmReset = true
                    }
                }
                SongMenuItem.DELETE -> OptionRow(Icons.Filled.Delete, "מחק את הקובץ מהמכשיר", tint = Color_Error) {
                    confirmDelete = true
                }
            }
        }
    }

    if (moodOpen) {
        MoodDialog(
            vm = vm,
            song = song,
            marks = MoodMarks.parse(stats?.moods.orEmpty()),
            onDismiss = { moodOpen = false }
        )
    }

    if (editOpen) {
        SongEditDialog(vm = vm, songs = listOf(song), onDismiss = { editOpen = false; onDismiss() })
    }

    if (genreOpen) {
        GenreDialog(
            initial = stats?.genre.orEmpty().ifBlank { song.genre.orEmpty() },
            count = 1,
            onDismiss = { genreOpen = false },
            onApply = { vm.setGenre(listOf(song.id), it) }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Surface1,
            title = { Text("למחוק את הקובץ?") },
            text = {
                DialogBody {
                    Text(
                        "\"${song.title}\" יימחק מהמכשיר עצמו, לא רק מהאפליקציה. " +
                            "אי אפשר לבטל את זה.",
                        color = TextSecondary
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteSongs(listOf(song))
                        confirmDelete = false
                        onDismiss()
                    }
                ) { Text("מחק", color = Color_Error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text("ביטול", color = TextSecondary)
                }
            }
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            containerColor = Surface1,
            title = { Text("לאפס את ההשמעות?") },
            text = {
                DialogBody {
                    Text(
                        "מספר ההשמעות של \"${song.title}\" יתאפס, והשיר ייעלם מ\"הושמעו לאחרונה\". " +
                            "הלייק, הדירוג והתגיות נשארים.",
                        color = TextSecondary
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.resetPlayCount(song)
                        confirmReset = false
                        onDismiss()
                    }
                ) { Text("אפס", color = Accent) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) {
                    Text("ביטול", color = TextSecondary)
                }
            }
        )
    }

    if (newPlaylist) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newPlaylist = false },
            containerColor = Surface1,
            title = { Text("רשימה חדשה") },
            text = {
                DialogBody {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        placeholder = { Text("שם הרשימה") }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        // Creating with the song attached, so the sheet the user
                        // opened on a track actually ends with that track filed.
                        vm.createPlaylist(name.trim(), song)
                        newPlaylist = false
                        onDismiss()
                    }
                ) { Text("צור והוסף", color = Accent) }
            },
            dismissButton = {
                TextButton(onClick = { newPlaylist = false }) {
                    Text("ביטול", color = TextSecondary)
                }
            }
        )
    }

    if (showWhy) {
        WhyDialog(vm = vm, song = song, onDismiss = { showWhy = false })
    }
    if (showLyrics) {
        LyricsEditorDialog(vm = vm, song = song, onDismiss = { showLyrics = false })
    }
    if (showTags) {
        SongTagDialog(
            current = Styles.parse(stats?.styles.orEmpty()),
            guessed = stats?.stylesAuto == 1,
            onDismiss = { showTags = false },
            onApply = { vm.setSongStyles(song.id, Styles.join(it)) }
        )
    }
    if (showCapo) {
        CapoDialog(feature = features[song.id], onDismiss = { showCapo = false })
    }
}

/**
 * Capo positions for the song's key, and the chords that key contains.
 *
 * The detected key is a starting point, not a verdict - it can be wrong, and a
 * guitarist will hear that within one bar. So it is labelled as detected, and
 * changing it is a single tap rather than something buried in a setting.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CapoDialog(feature: AudioFeatureEntity?, onDismiss: () -> Unit) {
    val detectedKey = feature?.musicalKey ?: -1
    val detectedMode = MusicalMode.byOrdinalOrNull(feature?.scaleMode ?: -1)
    // tonicIsMajor, not brightFamily: this decides which chord gets fingered.
    val detectedBright = detectedMode?.tonicIsMajor ?: (feature?.mode == 1)

    var key by remember(detectedKey) { mutableStateOf(detectedKey) }
    var bright by remember(detectedBright) { mutableStateOf(detectedBright) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        title = { Text("אקורדים וקאפו") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = fitHeight(420.dp))
                    .verticalScroll(rememberScrollState())
            ) {
                if (key !in 0..11) {
                    Text(
                        "השיר עדיין לא נותח, אז אין סולם להתבסס עליו. " +
                            "אפשר לבחור סולם ידנית:",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                } else {
                    Text(
                        "הסולם שזוהה: ${Capo.keyName(key, bright)}" +
                            (detectedMode?.takeIf { it.ordinal > 1 }?.let { " · ${it.label}" } ?: ""),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        "זיהוי אוטומטי מתוך הצליל — אם זה נשמע לא נכון, שנה למטה.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }

                Spacer(Modifier.height(12.dp))
                // Note names are Latin, and a bare "G#" dropped into a Hebrew
                // paragraph comes out as "#G" - the sharp jumps to the wrong
                // side. Laying the whole row out left to right fixes the
                // spelling and puts the chromatic scale in rising order too.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        for (pc in 0..11) {
                            Chip(
                                label = Capo.NAMES[pc],
                                selected = pc == key,
                                onClick = { key = pc }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(label = "מז'ורי", selected = bright, onClick = { bright = true })
                    Chip(label = "מינורי", selected = !bright, onClick = { bright = false })
                }

                if (key in 0..11) {
                    Spacer(Modifier.height(16.dp))
                    Text("קאפו", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "בסריג המסומן, נגן את הצורות של הסולם שמימין",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(Modifier.height(6.dp))
                    Capo.options(key, bright).forEach { option ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (option.fret == 0) "בלי קאפו" else "סריג ${option.fret}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (option.open) Accent else TextSecondary
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = Capo.keyName(option.playKey, bright) +
                                    if (option.open) "  ✓" else "",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    textDirection = TextDirection.Ltr
                                ),
                                color = if (option.open) Accent else TextSecondary
                            )
                        }
                    }
                    Text(
                        "✓ = אקורדים פתוחים, בלי בָּארֶה",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )

                    val chords = detectedMode?.let { Capo.scaleChords(key, it) }
                        ?: Capo.scaleChords(
                            key,
                            if (bright) MusicalMode.MAJOR else MusicalMode.MINOR
                        )
                    if (chords.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        Text("האקורדים של הסולם", style = MaterialTheme.typography.titleSmall)
                        // Said plainly, because it is the difference between a
                        // shortlist and a transcription: nothing here listened to
                        // the recording.
                        Text(
                            "אלה האקורדים שקיימים בסולם — לא האקורדים שהשיר מנגן. " +
                                "האפליקציה לא מזהה אקורדים מתוך ההקלטה.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            chords.joinToString("   "),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                textDirection = TextDirection.Ltr
                            ),
                            color = Accent
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("סגור", color = Accent) }
        }
    )
}

/** Shows the actual score terms the ranker used for this track. */
@Composable
fun WhyDialog(vm: MainViewModel, song: SongEntity, onDismiss: () -> Unit) {
    val terms = remember(song.id) { vm.explain(song) }
    val total = remember(song.id) { vm.totalScore(song) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        title = { Text("למה \"${song.title}\"") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (terms.isEmpty()) {
                    Text(
                        "הפיד עוד לא נבנה. אחרי רענון יופיע כאן הפירוק המלא.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                } else {
                    Text(
                        "ניקוד כולל: %.2f".format(total),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(8.dp))
                    terms.forEach { term ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(term.label, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    term.detail,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                            }
                            Text(
                                text = (if (term.value >= 0) "+" else "") + "%.2f".format(term.value),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (term.value >= 0) Accent else TextSecondary
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("סגור", color = Accent) }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SongTagDialog(
    current: List<String>,
    guessed: Boolean,
    onDismiss: () -> Unit,
    onApply: (List<String>) -> Unit
) {
    var selected by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        title = { Text("תגיות לשיר הזה") },
        text = {
            Column(modifier = Modifier.heightIn(max = fitHeight(340.dp)).verticalScroll(rememberScrollState())) {
                Text(
                    "תגית על שיר בודד מחליפה את תגיות האמן עבורו בלבד.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                if (guessed) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "התגיות האלה נוחשו על ידי האפליקציה ולא נבחרו על ידך. " +
                            "שינוי כאן הופך אותן לשלך, והלמידה כבר לא תדרוס אותן.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Accent
                    )
                }
                Spacer(Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Styles.SUGGESTED.forEach { style ->
                        val on = selected.any { it.equals(style, ignoreCase = true) }
                        Chip(label = style, selected = on, onClick = {
                            selected = if (on) selected.filterNot { it.equals(style, ignoreCase = true) }
                            else selected + style
                        })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(selected); onDismiss() }) { Text("שמור", color = Accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("ביטול", color = TextSecondary) }
        }
    )
}

/**
 * Sets a genre on one song, an album, or everything that is selected.
 *
 * The suggestions are the app's own style words, because a genre on a
 * downloaded file is usually blank or the name of the site it came from, and
 * a list of twenty-odd familiar words is faster than typing and keeps the
 * spelling consistent - which is what makes the engine able to group by it at
 * all. Free text stays allowed for everything the list does not cover.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GenreDialog(
    initial: String,
    count: Int,
    onDismiss: () -> Unit,
    onApply: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        title = { Text(if (count == 1) "ז'אנר" else "ז'אנר ל-$count שירים") },
        text = {
            DialogBody {
                Column {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        placeholder = { Text("למשל: חסידי") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Styles.SUGGESTED.take(14).forEach { style ->
                            Chip(
                                label = style,
                                selected = text.equals(style, ignoreCase = true),
                                onClick = { text = style }
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "השארה ריקה מנקה את הז'אנר וחוזרת למה שכתוב בקובץ.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(text.trim()); onDismiss() }) {
                Text("שמור", color = Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("ביטול", color = TextSecondary) }
        }
    )
}

@Composable
private fun OptionRow(
    icon: ImageVector,
    label: String,
    tint: Color? = null,
    onClick: () -> Unit
) {
    val gutter = rememberMetrics().gutter
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = gutter, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = tint ?: TextSecondary, modifier = Modifier.size(21.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint ?: MaterialTheme.colorScheme.onBackground)
    }
}

/**
 * The song's moods: what the audio reading says, and the user's own answer
 * for each, which always wins and is what the reading learns from.
 */
@Composable
private fun MoodDialog(
    vm: MainViewModel,
    song: SongEntity,
    marks: Map<Mood, Boolean>,
    onDismiss: () -> Unit
) {
    val reading by produceState<Map<Mood, Boolean>?>(null, song.id) {
        value = vm.moodReading(song.id)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        title = { Text("מצב הרוח של השיר") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "מה שתסמן גובר על הזיהוי האוטומטי, והאפליקציה לומדת ממנו " +
                        "לזהות נכון שירים שנשמעים דומה. לחיצה שנייה מחזירה לאוטומטי.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(Modifier.height(10.dp))
                for (mood in Mood.entries) {
                    val said = marks[mood]
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(mood.label, style = MaterialTheme.typography.titleSmall)
                            val read = reading
                            val auto = read?.get(mood)
                            // What the reading says, yes or no, kept in sight
                            // after a mark too: it is how one follows whether
                            // the reading is learning, and "זוהה" alone did not
                            // say what had been found.
                            Text(
                                when {
                                    read == null -> if (said != null) "סימנת בעצמך" else "…"
                                    read.isEmpty() -> if (said != null) "סימנת בעצמך" else "השיר עוד לא נותח"
                                    said != null && auto == true -> "סימנת בעצמך · הזיהוי האוטומטי: כן"
                                    said != null -> "סימנת בעצמך · הזיהוי האוטומטי: לא"
                                    auto == true -> "הזיהוי האוטומטי: כן"
                                    else -> "הזיהוי האוטומטי: לא"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (auto == true) Accent else TextTertiary
                            )
                        }
                        Chip("כן", selected = said == true) {
                            vm.setMoodMark(song, mood, if (said == true) null else true)
                        }
                        Spacer(Modifier.width(6.dp))
                        Chip("לא", selected = said == false) {
                            vm.setMoodMark(song, mood, if (said == false) null else false)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("סגור", color = Accent) }
        }
    )
}
