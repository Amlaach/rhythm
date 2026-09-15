package com.elchanan.rhythm.ui.components

import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import com.elchanan.rhythm.ui.theme.gradientFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pulls two dominant colours out of the album art so the player can be tinted
 * by the cover the way YouTube Music and Spotify do. Falls back to the
 * deterministic gradient when a track has no embedded artwork.
 */
@Composable
fun rememberArtworkColors(songId: Long, albumId: Long, seed: String): State<Pair<Color, Color>> {
    val context = LocalContext.current
    val fallback = gradientFor(seed)
    val state = remember(songId, albumId, seed) { mutableStateOf(fallback) }

    LaunchedEffect(songId, albumId, seed) {
        state.value = fallback
        val extracted = withContext(Dispatchers.IO) {
            runCatching {
                val request = ImageRequest.Builder(context)
                    .data(SongArt(songId, albumId))
                    .size(192)
                    .allowHardware(false)
                    .build()
                val drawable = context.imageLoader.execute(request).drawable
                val bitmap = (drawable as? BitmapDrawable)?.bitmap ?: return@runCatching null
                val palette = Palette.from(bitmap).maximumColorCount(16).generate()
                val primary = palette.vibrantSwatch
                    ?: palette.lightVibrantSwatch
                    ?: palette.mutedSwatch
                    ?: palette.dominantSwatch
                val secondary = palette.darkVibrantSwatch
                    ?: palette.darkMutedSwatch
                    ?: palette.dominantSwatch
                if (primary == null || secondary == null) null
                else Color(primary.rgb) to Color(secondary.rgb)
            }.getOrNull()
        }
        if (extracted != null) state.value = extracted
    }

    return state
}
