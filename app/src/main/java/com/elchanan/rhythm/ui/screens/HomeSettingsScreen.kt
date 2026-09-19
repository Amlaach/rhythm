package com.elchanan.rhythm.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.elchanan.rhythm.engine.ShelfKind
import com.elchanan.rhythm.ui.MainViewModel
import com.elchanan.rhythm.ui.components.rememberMetrics
import com.elchanan.rhythm.ui.theme.Accent
import com.elchanan.rhythm.ui.theme.AppBackground
import com.elchanan.rhythm.ui.theme.BgElevated
import com.elchanan.rhythm.ui.theme.Surface1
import com.elchanan.rhythm.ui.theme.TextSecondary

/**
 * Everything the home screen is told to do, on a screen of its own.
 *
 * Kept out of the settings list rather than folded into it: which shelves
 * appear decides what is seen first on every launch, and that is worth a page
 * of its own rather than one more row to scroll past. The shelves themselves
 * are a tap further in, in [ShelfSheet].
 */
@Composable
fun HomeSettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val gutter = rememberMetrics().gutter
    var shelvesOpen by remember { mutableStateOf(false) }
    var pinMoods by remember { mutableStateOf(vm.prefs.pinMoodRow) }

    Column(modifier = Modifier.fillMaxSize().background(AppBackground)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "חזור",
                    tint = TextSecondary
                )
            }
            Spacer(Modifier.width(4.dp))
            Text("הגדרות דף הבית", style = MaterialTheme.typography.titleLarge)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = gutter, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "פס מצבי הרוח נשאר למעלה",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    "דלוק: הצ'יפים נשארים מתחת לכותרת והדף נגלל מתחתיהם. " +
                        "כבוי: הם נגללים למעלה יחד עם כל השאר",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
            Switch(
                checked = pinMoods,
                onCheckedChange = {
                    pinMoods = it
                    vm.prefs.pinMoodRow = it
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Accent,
                    checkedTrackColor = Accent.copy(alpha = 0.4f)
                )
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = gutter, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("מדפים במסך הבית", style = MaterialTheme.typography.titleSmall)
                Text(
                    "אילו מדפים יופיעו במסך הבית",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
            Button(
                onClick = { shelvesOpen = true },
                colors = ButtonDefaults.buttonColors(containerColor = Surface1)
            ) { Text("ערוך") }
        }
    }

    if (shelvesOpen) {
        ShelfSheet(vm = vm, onDismiss = { shelvesOpen = false })
    }
}

/**
 * The shelves, one switch each.
 *
 * A sheet over the page rather than a screen of its own, because the list is
 * longer than a phone's height once every shelf carries its explanation: here
 * it scrolls away under the finger while the row that opened it stays put.
 *
 * Every tap is written straight through. There is nothing to confirm - a
 * switch that moved without the home screen following it would be a lie about
 * what the app is doing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShelfSheet(vm: MainViewModel, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val gutter = rememberMetrics().gutter
    var shelves by remember { mutableStateOf(vm.prefs.homeShelves) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = BgElevated
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Scrolls inside the sheet, so the last shelves stay reachable
                // on a short screen without the list being cut off.
                .verticalScroll(rememberScrollState())
                .padding(bottom = 28.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = gutter, vertical = 4.dp)) {
                Text(
                    "מדפים במסך הבית",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "כיבוי מדף לא מוחק כלום — הוא פשוט מפסיק להופיע, וחוזר כמו " +
                        "שהיה כשמדליקים אותו בחזרה.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }

            for (shelf in ShelfKind.entries) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = gutter, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(shelf.label, style = MaterialTheme.typography.bodyLarge)
                        if (shelf.about.isNotEmpty()) {
                            Text(
                                shelf.about,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }
                    Switch(
                        checked = shelf.key in shelves,
                        onCheckedChange = { on ->
                            shelves = if (on) shelves + shelf.key else shelves - shelf.key
                            vm.setHomeShelves(shelves)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Accent,
                            checkedTrackColor = Accent.copy(alpha = 0.4f)
                        )
                    )
                }
            }
        }
    }
}
