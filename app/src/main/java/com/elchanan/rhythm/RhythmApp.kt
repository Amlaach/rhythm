package com.elchanan.rhythm

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.elchanan.rhythm.data.AnalysisManager
import com.elchanan.rhythm.data.MusicRepository
import com.elchanan.rhythm.ui.components.SongArtFetcher
import com.elchanan.rhythm.ui.components.SongArtKeyer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class RhythmApp : Application(), ImageLoaderFactory {

    /** Single instance for the whole process; the playback service uses it too. */
    val repository: MusicRepository by lazy { MusicRepository.create(this) }

    /**
     * Application scoped so a long analysis pass survives navigation and
     * configuration changes instead of restarting on every screen.
     */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val analysis: AnalysisManager by lazy { AnalysisManager(this, repository, appScope) }

    /** Teaches Coil to read a track's own embedded cover instead of the album thumbnail. */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components {
                add(SongArtKeyer())
                add(SongArtFetcher.Factory())
            }
            .build()
}
