package com.elchanan.rhythm.ui.screens

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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elchanan.rhythm.ui.MainViewModel
import com.elchanan.rhythm.ui.components.EmptyState
import com.elchanan.rhythm.ui.theme.Accent
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
fun TagFixScreen(vm: MainViewModel, onBack: () -> Unit) {
    val proposals by vm.tagProposals.collectAsStateWithLifecycle()
    val library by vm.library.collectAsStateWithLifecycle()

    LaunchedEffect(library.songs.size) { vm.buildTagProposals() }

    val changed = proposals.filter { it.changed }

    Column(modifier = Modifier.fillMaxSize().background(Bg)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "חזור", tint = TextSecondary)
            }
            Spacer(Modifier.width(4.dp))
            Text("תיקון תגיות", style = MaterialTheme.typography.titleLarge)
        }

        LazyColumn(
            contentPadding = PaddingValues(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        "לכל קובץ יש תווית פנימית עם שם השיר ושם האמן. בקבצים שהורדו מהאינטרנט " +
                            "התווית לרוב שגויה — שם האמן דחוס בתוך שם השיר, ובשדה האמן יושב שם " +
                            "הערוץ. בגלל זה כל השירים נראים כמו אמן אחד.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "התיקון נשמר באפליקציה בלבד ולא נוגע בקבצים עצמם, אז אין שום סיכון " +
                            "שמשהו ייהרס. אפשר לבטל בכל רגע.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Button(
                            onClick = { vm.applyTagFix(proposals) },
                            enabled = changed.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = Accent)
                        ) { Text("החל על ${changed.size} שירים") }
                        Spacer(Modifier.width(10.dp))
                        TextButton(onClick = { vm.resetTagFix() }) {
                            Text("שחזר מקור", color = TextSecondary)
                        }
                    }
                }
            }

            if (changed.isEmpty()) {
                item {
                    EmptyState(
                        title = "אין מה לתקן",
                        body = "שמות השירים והאמנים כבר נראים תקינים, או שהתיקון כבר הוחל."
                    )
                }
            }

            items(changed, key = { it.songId }) { p ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Surface1)
                        .padding(12.dp)
                ) {
                    Text(
                        p.oldTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        p.newTitle,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        p.newArtist,
                        style = MaterialTheme.typography.bodySmall,
                        color = Accent,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
