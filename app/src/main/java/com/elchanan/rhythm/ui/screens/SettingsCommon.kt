package com.elchanan.rhythm.ui.screens

import com.elchanan.rhythm.ui.theme.localized

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.elchanan.rhythm.ui.theme.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.elchanan.rhythm.ui.components.rememberMetrics
import com.elchanan.rhythm.ui.theme.Accent
import com.elchanan.rhythm.ui.theme.AppBackground
import com.elchanan.rhythm.ui.theme.TextSecondary
import com.elchanan.rhythm.ui.theme.TextTertiary

/**
 * The frame every settings screen sits in: a back arrow, a title, a list.
 *
 * Written once because there are now eight of these. Before, the settings
 * were one screen of twenty six rows with three headings between them, and
 * most rows belonged to no heading at all - library settings appeared in
 * three separate places, the tag rows landed after playlist import, and the
 * three "reset" buttons were scattered across the length of it.
 *
 * Each screen is one subject now, and the main screen is the list of
 * subjects. The cost is a tap to reach a single setting; the gain is that
 * there is somewhere obvious to look for it.
 */
@Composable
internal fun SettingsScaffold(
    title: String,
    onBack: () -> Unit,
    content: LazyListScope.() -> Unit
) {
    val topPad = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Column(modifier = Modifier.fillMaxSize().background(AppBackground)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = topPad).padding(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = localized("חזור"))
            }
            Text(title, style = MaterialTheme.typography.headlineSmall)
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 60.dp), content = content)
    }
}

/**
 * One subject on the main settings screen.
 *
 * The subtitle is not decoration: a door labelled only "המנוע" tells nobody
 * whether the thing they are looking for is behind it, and the whole point of
 * a short main screen is that the labels do the navigating.
 */
@Composable
internal fun SettingsDoor(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    val gutter = rememberMetrics().gutter
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = gutter, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
            contentDescription = null,
            tint = TextTertiary,
            modifier = Modifier.size(20.dp)
        )
    }
}
