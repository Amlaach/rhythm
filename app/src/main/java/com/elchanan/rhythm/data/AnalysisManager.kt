package com.elchanan.rhythm.data

import android.content.Context
import com.elchanan.rhythm.engine.Analysis
import com.elchanan.rhythm.engine.AudioAnalyzer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Runs [AudioAnalyzer] over the library, one file at a time, in the
 * background. Decoding is the slow part - roughly half a second to a second
 * per track - so the pass is resumable and reports progress instead of
 * blocking anything.
 */
class AnalysisManager(
    private val context: Context,
    private val repo: MusicRepository,
    private val scope: CoroutineScope
) {

    data class Progress(
        val running: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        val currentTitle: String? = null
    ) {
        val remaining: Int get() = (total - done).coerceAtLeast(0)
        val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
    }

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.Default) {
            try {
                var total = repo.songCount()
                var done = repo.analyzedCount()
                _progress.value = Progress(running = true, done = done, total = total)

                while (isActive) {
                    val batch = repo.songsNeedingAnalysis(12)
                    if (batch.isEmpty()) break
                    for (song in batch) {
                        if (!isActive) break
                        _progress.value = _progress.value.copy(currentTitle = song.title)
                        val feature = runCatching { AudioAnalyzer.analyze(context, song) }.getOrNull()
                        if (feature != null) {
                            repo.putFeature(feature)
                        } else {
                            // store a blank row so a file that cannot be decoded
                            // is not retried on every pass
                            repo.putFeature(Analysis.blankFor(song.id))
                        }
                        done++
                        _progress.value = _progress.value.copy(done = done, total = total)
                        // give the rest of the app room to breathe
                        delay(15)
                    }
                    total = repo.songCount()
                }
            } finally {
                // The model holds its weights and a working arena for as long
                // as it is open, and the pass is the only thing that uses it.
                runCatching { AudioAnalyzer.releaseTagger() }
                _progress.value = _progress.value.copy(running = false, currentTitle = null)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _progress.value = _progress.value.copy(running = false, currentTitle = null)
    }

    suspend fun refreshCounts() {
        if (_progress.value.running) return
        _progress.value = _progress.value.copy(
            done = repo.analyzedCount(),
            total = repo.songCount()
        )
    }

    suspend fun reset() {
        stop()
        repo.clearFeatures()
        refreshCounts()
    }
}
