package com.elchanan.rhythm.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elchanan.rhythm.engine.HebrewSpelling
import com.elchanan.rhythm.ui.MainViewModel
import com.elchanan.rhythm.ui.components.DialogBody
import com.elchanan.rhythm.ui.components.rememberMetrics
import com.elchanan.rhythm.ui.theme.Accent
import com.elchanan.rhythm.ui.theme.Surface1
import com.elchanan.rhythm.ui.theme.Text
import com.elchanan.rhythm.ui.theme.TextPrimary
import com.elchanan.rhythm.ui.theme.TextSecondary
import com.elchanan.rhythm.ui.theme.localized

/**
 * Hebrew spellings for artists and songs written in English letters, offered
 * one by one.
 *
 * Offered, never applied by themselves: some songs really are called
 * something in English, and only the listener knows which. Nothing starts
 * ticked; each offer can be ticked, left, or changed before it is used.
 */
@Composable
fun HebrewNamesScreen(vm: MainViewModel, onBack: () -> Unit) {
    val suggestions by vm.hebrewSuggestions.collectAsStateWithLifecycle()
    val library by vm.library.collectAsStateWithLifecycle()
    LaunchedEffect(library.songs) { vm.buildHebrewSuggestions() }
    val gutter = rememberMetrics().gutter

    // What is ticked, by offer, with the spelling as it will be used - the
    // listener may have changed it.
    val chosen = remember { mutableStateMapOf<String, String>() }
    var editing by remember { mutableStateOf<HebrewSpelling.Suggestion?>(null) }
    fun keyOf(s: HebrewSpelling.Suggestion) = "${s.field}:${s.latin}:${s.songIds.first()}"

    val all = suggestions.orEmpty()
    val artists = all.filter { it.field == HebrewSpelling.Field.ARTIST }
    val titles = all.filter { it.field == HebrewSpelling.Field.TITLE }

    SettingsScaffold(title = "שמות בעברית", onBack = onBack) {
        item {
            Column(modifier = Modifier.padding(horizontal = gutter, vertical = 8.dp)) {
                Text(
                    "שמות של אמנים ושירים שכתובים באותיות אנגליות, ואיך הם נכתבים בעברית. " +
                        "זו הצעה בלבד: יש שירים ששמם האמיתי באנגלית, אז שום דבר לא מסומן מראש. " +
                        "סמן את מה שנכון, ואפשר ללחוץ על הצעה כדי לתקן את האיות לפני שמחילים.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            vm.applyHebrewNames(all.mapNotNull { s -> chosen[keyOf(s)]?.let { s.copy(hebrew = it) } })
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
                        }) {
                            Text(if (chosen.size == all.size) "בטל הכל" else "בחר הכל", color = TextSecondary)
                        }
                    }
                }
            }
        }
        when {
            suggestions == null -> item {
                Text("מחפש…", color = TextSecondary, modifier = Modifier.padding(horizontal = gutter, vertical = 16.dp))
            }
            all.isEmpty() -> item {
                Text(
                    "אין כרגע שמות באנגלית שנמצא להם איות בעברית.",
                    color = TextSecondary,
                    modifier = Modifier.padding(horizontal = gutter, vertical = 16.dp)
                )
            }
        }
        if (artists.isNotEmpty()) {
            item { SectionTitle("אמנים (${artists.size})", gutter) }
            items(artists, key = { "a:" + keyOf(it) }) { s ->
                SpellingRow(s, chosen[keyOf(s)], gutter,
                    detail = "${s.songIds.size} שירים",
                    onToggle = { if (chosen.containsKey(keyOf(s))) chosen.remove(keyOf(s)) else chosen[keyOf(s)] = s.hebrew },
                    onEdit = { editing = s })
            }
        }
        if (titles.isNotEmpty()) {
            item { SectionTitle("שירים (${titles.size})", gutter) }
            items(titles, key = { "t:" + keyOf(it) }) { s ->
                val artist = library.songsById[s.songIds.first()]?.artistName.orEmpty()
                SpellingRow(s, chosen[keyOf(s)], gutter,
                    detail = artist,
                    onToggle = { if (chosen.containsKey(keyOf(s))) chosen.remove(keyOf(s)) else chosen[keyOf(s)] = s.hebrew },
                    onEdit = { editing = s })
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
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        label = { Text("האיות בעברית") }
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = {
                    chosen[keyOf(s)] = text.trim()
                    editing = null
                }) { Text("סמן", color = Accent) }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text("ביטול", color = TextSecondary) }
            }
        )
    }
}

@Composable
private fun SectionTitle(text: String, gutter: androidx.compose.ui.unit.Dp) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = gutter, end = gutter, top = 14.dp, bottom = 6.dp)
    )
}

/** One offer: the English spelling, the Hebrew one, and a tick. */
@Composable
private fun SpellingRow(
    s: HebrewSpelling.Suggestion,
    chosenAs: String?,
    gutter: androidx.compose.ui.unit.Dp,
    detail: String,
    onToggle: () -> Unit,
    onEdit: () -> Unit
) {
    val ticked = chosenAs != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = gutter, vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (ticked) Accent.copy(alpha = 0.14f) else Surface1)
            .clickable(onClick = onEdit)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                chosenAs ?: s.hebrew,
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                s.latin,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (detail.isNotBlank()) {
                    Text(detail, style = MaterialTheme.typography.labelSmall, color = TextSecondary, maxLines = 1)
                }
                // Found written this way elsewhere in the library: the surer kind.
                if (s.fromLibrary) {
                    Text("· כך כתוב בספרייה", style = MaterialTheme.typography.labelSmall, color = Accent, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            if (ticked) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
            contentDescription = localized(if (ticked) "מסומן" else "לא מסומן"),
            tint = if (ticked) Accent else TextSecondary,
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(50))
                .clickable(onClick = onToggle)
                .padding(3.dp)
        )
    }
}
