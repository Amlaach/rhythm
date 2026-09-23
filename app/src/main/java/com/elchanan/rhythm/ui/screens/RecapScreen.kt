package com.elchanan.rhythm.ui.screens

import com.elchanan.rhythm.ui.theme.localized

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.elchanan.rhythm.ui.theme.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elchanan.rhythm.ui.MainViewModel
import com.elchanan.rhythm.ui.components.Artwork
import com.elchanan.rhythm.ui.components.EmptyState
import com.elchanan.rhythm.ui.components.SectionHeader
import com.elchanan.rhythm.ui.components.rememberMetrics
import com.elchanan.rhythm.ui.theme.Accent
import com.elchanan.rhythm.ui.theme.Accent2
import com.elchanan.rhythm.ui.theme.AppBackground
import com.elchanan.rhythm.ui.theme.Bg
import com.elchanan.rhythm.ui.theme.Surface1
import com.elchanan.rhythm.ui.theme.TextSecondary
import com.elchanan.rhythm.ui.theme.gradientFor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The offline equivalent of Wrapped / Recap: everything here is computed from
 * the local play history table, so it works from the first week of use rather
 * than once a year.
 */
@Composable
fun RecapScreen(vm: MainViewModel, onBack: () -> Unit, onOpenDetail: () -> Unit) {
    val recap by vm.recap.collectAsStateWithLifecycle()
    val library by vm.library.collectAsStateWithLifecycle()
    val topPad = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val gutter = rememberMetrics().gutter

    LaunchedEffect(Unit) { vm.loadRecap() }

    Column(modifier = Modifier.fillMaxSize().background(AppBackground)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = topPad).padding(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = localized("חזור"))
            }
            Text("הסיכום שלך", style = MaterialTheme.typography.headlineSmall)
        }

        val data = recap
        if (data == null || data.totalPlays == 0) {
            EmptyState(
                title = "עוד אין מספיק היסטוריה",
                body = "אחרי כמה ימי האזנה יופיעו כאן המספרים."
            )
            return@Column
        }

        LazyColumn(contentPadding = PaddingValues(bottom = 40.dp)) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    BigStat("דקות האזנה", "${data.totalMinutes}", Modifier.weight(1f))
                    BigStat("השמעות", "${data.totalPlays}", Modifier.weight(1f))
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    BigStat("שירים שונים", "${data.distinctSongs}", Modifier.weight(1f))
                    BigStat("אמנים שונים", "${data.distinctArtists}", Modifier.weight(1f))
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    BigStat("רצף נוכחי", "${data.currentStreakDays} ימים", Modifier.weight(1f))
                    BigStat("הרצף הארוך", "${data.longestStreakDays} ימים", Modifier.weight(1f))
                }
            }

            item {
                SectionHeader(
                    title = "מתי אתה שומע",
                    subtitle = "היום הכי עמוס: ${data.busiestDay}"
                )
            }
            item { HourChart(data.byHour) }

            if (data.firstPlayAt > 0) {
                item {
                    val formatter = SimpleDateFormat("d MMMM yyyy", Locale("he"))
                    Text(
                        text = "ההשמעה הראשונה שנרשמה: ${formatter.format(Date(data.firstPlayAt))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        modifier = Modifier.padding(horizontal = gutter, vertical = 8.dp)
                    )
                }
            }

            item { SectionHeader(title = "האמנים המובילים") }
            items(data.topArtists.size) { index ->
                val (name, count) = data.topArtists[index]
                RankRow(
                    rank = index + 1,
                    title = name,
                    subtitle = "$count השמעות",
                    seed = name,
                    onClick = {
                        library.artists.firstOrNull { it.displayName == name }?.let { artist ->
                            vm.openList(
                                artist.displayName,
                                "${artist.songs.size} שירים",
                                artist.songs,
                                "artist:${artist.key}"
                            )
                            onOpenDetail()
                        }
                    }
                )
            }

            item {
                SectionHeader(
                    title = "השירים המובילים",
                    actionLabel = "נגן הכל",
                    onAction = { vm.playList(data.topSongs.map { it.first }) }
                )
            }
            items(data.topSongs.size) { index ->
                val (song, count) = data.topSongs[index]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { vm.playList(data.topSongs.map { it.first }, index) }
                        .padding(horizontal = gutter, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextSecondary,
                        modifier = Modifier.width(26.dp),
                        textAlign = TextAlign.Center
                    )
                    Artwork(song.id, song.albumId, song.artistKey, Modifier.size(44.dp), corner = 8)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(song.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                        Text(
                            "${song.artistName} · $count השמעות",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BigStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Surface1)
            .padding(16.dp)
    ) {
        Text(value, style = MaterialTheme.typography.headlineMedium, color = Accent)
        Text(label, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
    }
}

/** 24 bars, one per hour, scaled to the busiest hour. */
@Composable
private fun HourChart(byHour: List<Int>) {
    val max = (byHour.maxOrNull() ?: 0).coerceAtLeast(1)
    val gutter = rememberMetrics().gutter
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(110.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            byHour.forEachIndexed { hour, count ->
                val fraction = count.toFloat() / max
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height((6 + 100 * fraction).dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(
                            Brush.verticalGradient(
                                listOf(if (count > 0) Accent else Surface1, Accent2.copy(alpha = 0.5f))
                            )
                        )
                )
                if (hour == byHour.lastIndex) Spacer(Modifier.width(0.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            listOf("00", "06", "12", "18", "23").forEach { label ->
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun RankRow(
    rank: Int,
    title: String,
    subtitle: String,
    seed: String,
    onClick: () -> Unit
) {
    val (c1, c2) = gradientFor(seed)
    val gutter = rememberMetrics().gutter
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = gutter, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "$rank",
            style = MaterialTheme.typography.titleMedium,
            color = TextSecondary,
            modifier = Modifier.width(26.dp),
            textAlign = TextAlign.Center
        )
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(c1, c2))),
            contentAlignment = Alignment.Center
        ) {
            Text(title.take(1), color = Color.White, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
    }
}
