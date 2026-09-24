package com.elchanan.rhythm.ui.screens

import com.elchanan.rhythm.ui.components.fitHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.elchanan.rhythm.data.TagEdit
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.ui.MainViewModel
import com.elchanan.rhythm.ui.theme.Accent
import com.elchanan.rhythm.ui.theme.Surface1
import com.elchanan.rhythm.ui.theme.Text
import com.elchanan.rhythm.ui.theme.TextSecondary

/**
 * A song's tags - name, artist, album, album artist, year, track number and
 * genre - edited in the file itself.
 *
 * One song: every field, filled in with what the file says now; emptying a
 * field clears it in the file. Several: the fields a set of songs can share,
 * left empty - an empty field changes nothing, so giving a whole selection
 * one album touches nothing else. A name or a track number shared by a whole
 * selection is never what anyone means, so they are not offered there.
 *
 * Only what actually changed is written, and nothing else in the file is
 * touched - see [com.elchanan.rhythm.data.Id3Tags].
 */
@Composable
internal fun SongEditDialog(
    vm: MainViewModel,
    songs: List<SongEntity>,
    onDismiss: () -> Unit,
    onSaved: () -> Unit = {}
) {
    if (songs.isEmpty()) return
    val single = songs.singleOrNull()
    // Every field belongs to these songs: if the dialog is ever handed others,
    // it starts again rather than carry one song's name onto another.
    val ids = remember(songs) { songs.map { it.id } }

    // Where each field starts: for one song, the file's own tags (the
    // library fills an empty album with the folder's name, and the file is
    // what is being edited); the library's name and artist until those are
    // read, and in their place if the file cannot be.
    var original by remember(ids) {
        mutableStateOf(
            if (single != null) TagEdit(single.title, single.artistName, "", "", "", "", "") else TagEdit()
        )
    }
    var title by remember(ids) { mutableStateOf(original.title.orEmpty()) }
    var artist by remember(ids) { mutableStateOf(original.artist.orEmpty()) }
    var album by remember(ids) { mutableStateOf("") }
    var albumArtist by remember(ids) { mutableStateOf("") }
    var year by remember(ids) { mutableStateOf("") }
    var track by remember(ids) { mutableStateOf("") }
    var genre by remember(ids) { mutableStateOf("") }
    var loaded by remember(ids) { mutableStateOf(single == null) }

    if (single != null) {
        LaunchedEffect(single.id) {
            val file = vm.readFileTags(single.id)
            val start = TagEdit(
                title = single.title,
                artist = single.artistName,
                album = file?.album ?: single.albumName,
                albumArtist = file?.albumArtist.orEmpty(),
                year = file?.year ?: single.year.takeIf { it > 0 }?.toString().orEmpty(),
                track = file?.track ?: (single.trackNumber % 1000).takeIf { it > 0 }?.toString().orEmpty(),
                genre = file?.genre ?: single.genre.orEmpty()
            )
            original = start
            album = start.album.orEmpty()
            albumArtist = start.albumArtist.orEmpty()
            year = start.year.orEmpty()
            track = start.track.orEmpty()
            genre = start.genre.orEmpty()
            loaded = true
        }
    }

    // One song: a field that differs from where it started, emptied ones
    // included. Several: a field that was filled in.
    fun changed(value: String, before: String?): String? =
        if (single != null) value.trim().takeIf { it != before.orEmpty() }
        else value.trim().takeIf { it.isNotEmpty() }

    val edit = TagEdit(
        title = if (single != null) changed(title, original.title) else null,
        artist = changed(artist, original.artist),
        album = changed(album, original.album),
        albumArtist = changed(albumArtist, original.albumArtist),
        year = changed(year, original.year),
        track = if (single != null) changed(track, original.track) else null,
        genre = changed(genre, original.genre)
    )
    val valid = single == null || (title.isNotBlank() && artist.isNotBlank())
    val ready = loaded && !edit.isEmpty && valid

    @Composable
    fun Field(label: String, value: String, number: Boolean = false, onChange: (String) -> Unit) {
        OutlinedTextField(
            value = value,
            onValueChange = { v -> onChange(if (number) v.filter { it.isDigit() }.take(4) else v) },
            singleLine = true,
            label = { Text(label) },
            placeholder = { if (single == null) Text("ללא שינוי") },
            keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        title = { Text(if (single != null) "עריכת תגיות" else "עריכת תגיות ל־${songs.size} שירים") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = fitHeight(460.dp))
                    .verticalScroll(rememberScrollState())
            ) {
                if (single != null) Field("שם השיר", title) { title = it }
                Field("אמן", artist) { artist = it }
                Field("אלבום", album) { album = it }
                Field("אמן האלבום", albumArtist) { albumArtist = it }
                Row {
                    Column(modifier = Modifier.weight(1f)) {
                        Field("שנה", year, number = true) { year = it }
                    }
                    if (single != null) {
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Field("מספר רצועה", track, number = true) { track = it }
                        }
                    }
                }
                Field("ז'אנר", genre) { genre = it }
                Text(
                    "נכתב לתוך הקובץ עצמו (MP3), כך שכל נגן יראה אותו. רק מה ששינית משתנה — " +
                        "העטיפה ושאר התגיות נשארים כמו שהם.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = ready,
                onClick = {
                    vm.editSongTags(songs.map { it.id }, edit)
                    onSaved()
                    onDismiss()
                }
            ) { Text("שמור", color = if (ready) Accent else TextSecondary) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("ביטול", color = TextSecondary) }
        }
    )
}
