package com.elchanan.rhythm.data

import android.content.Context
import com.elchanan.rhythm.engine.Analysis
import com.elchanan.rhythm.engine.AudioAnalyzer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
        val currentTitle: String? = null,
        /**
         * Songs the last pass could not reach - on storage that is not
         * attached. Said on the screen, so that a count that will not go down
         * is explained rather than offered as work the button will do.
         */
        val unreachable: Int = 0
    ) {
        val remaining: Int get() = (total - done).coerceAtLeast(0)
        val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
    }

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        // Marked running here rather than inside the coroutine.
        //
        // The foreground service starts this and then watches the progress to
        // know when it may stop. Setting the flag inside the coroutine leaves
        // a window - two database reads wide - in which the pass has been
        // started and does not yet say so, and a watcher that looked during
        // it would conclude there was nothing to wait for and stop the
        // service out from under the work it had just started.
        _progress.update { it.copy(running = true, unreachable = 0) }
        job = scope.launch(Dispatchers.Default) {
            try {
                var total = repo.songCount()
                var done = repo.analyzedCount()
                _progress.update { it.copy(running = true, done = done, total = total) }

                // Where the walk has got to, by song id. See the query.
                var after = Long.MIN_VALUE
                var unreachable = 0
                while (isActive) {
                    val batch = repo.songsNeedingAnalysis(after, 12)
                    if (batch.isEmpty()) break
                    after = batch.last().id
                    // Asked once per batch rather than once per song: the
                    // answer is a file system check per distinct card, and it
                    // cannot change halfway through twelve songs in a way that
                    // matters.
                    val mounted = Volumes.mountedRoots(batch.map { it.path })
                    for (song in batch) {
                        if (!isActive) break
                        // A file on a card that is not in the device is not a
                        // file that failed to decode, and must not be written
                        // off as one. Left with no row at all, so the next
                        // pass picks it up once the card is back - without
                        // this a card pulled out mid-pass left every song on
                        // it permanently marked unanalysable, and nothing
                        // short of wiping the measurements brought them back.
                        //
                        // Only a root that is known and absent counts as out.
                        // A path with no recognisable root - "/sdcard/...",
                        // anything not under /storage - used to count as out
                        // too, for ever, so those songs were never once
                        // analysed. Cards always appear as /storage/XXXX-XXXX;
                        // a path that is not one is the phone's own storage.
                        val root = Volumes.rootOf(song.path)
                        if (root.isNotEmpty() && root !in mounted) {
                            unreachable++
                            continue
                        }
                        _progress.update { it.copy(currentTitle = song.title) }
                        val feature = runCatching { AudioAnalyzer.analyze(context, song) }.getOrNull()
                        if (feature != null) {
                            repo.putFeature(feature)
                        } else if (!repo.markPrintTried(song.id)) {
                            // store a blank row so a file that cannot be decoded
                            // is not retried on every pass - unless it had been
                            // analysed before and was only back for its sound
                            // print, in which case the measurements stay and it
                            // is simply marked as tried
                            repo.putFeature(Analysis.blankFor(song.id))
                        }
                        done++
                        _progress.update { it.copy(done = done, total = total) }
                        // give the rest of the app room to breathe
                        delay(15)
                    }
                    total = repo.songCount()
                }
                _progress.update { it.copy(unreachable = unreachable) }
            } finally {
                // The model holds its weights and a working arena for as long
                // as it is open, and the pass is the only thing that uses it.
                runCatching { AudioAnalyzer.releaseTagger() }
                _progress.update { it.copy(running = false, currentTitle = null) }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _progress.update { it.copy(running = false, currentTitle = null) }
    }

    /** Stops the writer and waits until its finally block has released it. */
    suspend fun stopAndWait() {
        val active = job
        job = null
        active?.cancelAndJoin()
        _progress.update { it.copy(running = false, currentTitle = null) }
    }

    /**
     * Brings the counts up to date while no pass is running.
     *
     * It used to take the current state, wait on two database reads, and
     * write that same state back with the new counts. A pass that started in
     * the meantime - which is exactly what follows a scan, since the scan's
     * completion is what calls this - had its "running" overwritten by the
     * copy taken before it began. The loop carried on analysing; the screen
     * read "not running", offered "analyse" instead of "stop", and the
     * service keeping it alive in the background let go of it.
     *
     * Counts are read first and applied only if nothing started meanwhile.
     */
    suspend fun refreshCounts() {
        if (_progress.value.running) return
        val done = repo.analyzedCount()
        val total = repo.songCount()
        _progress.update { current ->
            if (current.running) current else current.copy(done = done, total = total)
        }
    }

    suspend fun reset() {
        stop()
        repo.clearFeatures()
        refreshCounts()
    }
}
