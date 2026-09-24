package com.elchanan.rhythm.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.elchanan.rhythm.ui.MainViewModel
import com.elchanan.rhythm.ui.components.Chip
import com.elchanan.rhythm.ui.components.rememberMetrics
import com.elchanan.rhythm.ui.theme.BgElevated
import com.elchanan.rhythm.ui.theme.Text
import com.elchanan.rhythm.ui.theme.TextSecondary

/**
 * The rows of a song's three-dot menu that the listener can arrange, in the
 * menu's own order. Rows that belong to one place only - remove from this
 * playlist, not right for this mood, the player's volume - are not here:
 * they show where they apply, first, because they are why the menu was opened
 * there.
 */
enum class SongMenuItem(val key: String, val label: String) {
    PLAY_NEXT("next", "נגן הבא"),
    ADD_TO_QUEUE("queue", "הוסף לתור"),
    MIX("mix", "צור מיקס מהשיר הזה"),
    RADIO("radio", "התחל רדיו מהשיר"),
    PLAYLISTS("playlists", "הוספה לרשימה"),
    LYRICS("lyrics", "מילות השיר"),
    CAPO("capo", "אקורדים וקאפו"),
    WHY("why", "למה זה הומלץ לי"),
    TAGS("tags", "תגיות סגנון לשיר"),
    MOOD("mood", "מצב רוח"),
    ARTIST("artist", "עבור לאמן"),
    ARTIST_RATING("artist_rating", "דירוג האמן"),
    ALBUM("album", "עבור לאלבום"),
    GENRE("genre", "שנה ז'אנר"),
    EDIT("edit", "עריכת תגיות"),
    SHARE("share", "שתף"),
    SPOKEN("spoken", "הרצאה או מוזיקה"),
    VOCAL("vocal", "ווקאלי"),
    RESET("reset", "אפס את מספר ההשמעות"),
    HIDE("hide", "הסתר מהנגן"),
    DELETE("delete", "מחק את הקובץ מהמכשיר")
}

/** Where a row sits: at the head of the menu, in its usual place, or not at all. */
enum class SongMenuPlacement(val label: String) {
    TOP("למעלה"),
    MENU("במקום הרגיל"),
    HIDDEN("מוסתר")
}

object SongMenu {
    fun placementOf(saved: Map<String, String>, item: SongMenuItem): SongMenuPlacement =
        SongMenuPlacement.entries.firstOrNull { it.name == saved[item.key] } ?: SongMenuPlacement.MENU

    /** The rows to draw: those moved up first, then the rest, each in the menu's own order. */
    fun shown(saved: Map<String, String>): List<SongMenuItem> {
        val top = SongMenuItem.entries.filter { placementOf(saved, it) == SongMenuPlacement.TOP }
        val rest = SongMenuItem.entries.filter { placementOf(saved, it) == SongMenuPlacement.MENU }
        return top + rest
    }
}

/**
 * The song menu's arrangement, as the player's buttons have theirs: each row
 * moved to the top, left where it is, or hidden. Written straight through.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SongMenuSheet(vm: MainViewModel, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val gutter = rememberMetrics().gutter
    var saved by remember { mutableStateOf(vm.prefs.songMenu) }

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
            Column(modifier = Modifier.padding(horizontal = gutter, vertical = 4.dp)) {
                Text("סידור תפריט השיר", style = MaterialTheme.typography.titleMedium)
                Text(
                    "מה מופיע בתפריט שלוש הנקודות של כל שיר, ובאיזה סדר: " +
                        "למעלה, במקום הרגיל, או מוסתר לגמרי.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }

            for (item in SongMenuItem.entries) {
                val current = SongMenu.placementOf(saved, item)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = gutter, vertical = 6.dp)
                ) {
                    Text(item.label, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SongMenuPlacement.entries.forEach { placement ->
                            Chip(
                                label = placement.label,
                                selected = current == placement,
                                onClick = {
                                    saved = saved + (item.key to placement.name)
                                    vm.prefs.songMenu = saved
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
