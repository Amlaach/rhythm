package com.elchanan.rhythm.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.ui.MainViewModel
import com.elchanan.rhythm.ui.theme.Accent
import com.elchanan.rhythm.ui.theme.Surface1
import com.elchanan.rhythm.ui.theme.Text
import com.elchanan.rhythm.ui.theme.TextSecondary

/**
 * A song's name, artist and album, edited in the file itself.
 *
 * One song: all three, filled in with what it is called now. Several: only
 * the artist and the album, left empty - an empty field changes nothing, so
 * giving a whole selection one album touches nothing else. A title shared by
 * a whole selection is never what anyone means, so it is not offered there.
 *
 * Only what actually changed is written: the album Android shows for an
 * untagged file is its folder's name, and writing that back would put a
 * folder name into the file as if it were an album.
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
    var title by remember { mutableStateOf(single?.title.orEmpty()) }
    var artist by remember { mutableStateOf(single?.artistName.orEmpty()) }
    var album by remember { mutableStateOf(single?.albumName.orEmpty()) }

    fun changed(value: String, before: String?): String? =
        value.trim().takeIf { it.isNotEmpty() && it != before }

    val newTitle = if (single != null) changed(title, single.title) else null
    val newArtist = changed(artist, single?.artistName)
    val newAlbum = changed(album, single?.albumName)
    val anything = newTitle != null || newArtist != null || newAlbum != null
    val valid = single == null || (title.isNotBlank() && artist.isNotBlank())

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        title = { Text(if (single != null) "עריכת פרטי השיר" else "עריכת ${songs.size} שירים") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (single != null) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        singleLine = true,
                        label = { Text("שם השיר") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                }
                OutlinedTextField(
                    value = artist,
                    onValueChange = { artist = it },
                    singleLine = true,
                    label = { Text("אמן") },
                    placeholder = { if (single == null) Text("ללא שינוי") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = album,
                    onValueChange = { album = it },
                    singleLine = true,
                    label = { Text("אלבום") },
                    placeholder = { if (single == null) Text("ללא שינוי") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "נשמר באפליקציה ונכתב גם לתוך הקובץ עצמו (MP3), כך שכל נגן יראה אותו. " +
                        "העטיפה ושאר פרטי הקובץ נשמרים כמו שהם.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = anything && valid,
                onClick = {
                    vm.editSongDetails(songs.map { it.id }, newTitle, newArtist, newAlbum)
                    onSaved()
                    onDismiss()
                }
            ) { Text("שמור", color = if (anything && valid) Accent else TextSecondary) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("ביטול", color = TextSecondary) }
        }
    )
}
