package com.elchanan.rhythm

import android.app.Application
import com.elchanan.rhythm.data.AnalysisManager
import com.elchanan.rhythm.data.MusicRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class RhythmApp : Application() {

    /** Single instance for the whole process; the playback service uses it too. */
    val repository: MusicRepository by lazy { MusicRepository.create(this) }

    /**
     * Application scoped so a long analysis pass survives navigation and
     * configuration changes instead of restarting on every screen.
     */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val analysis: AnalysisManager by lazy { AnalysisManager(this, repository, appScope) }
}
