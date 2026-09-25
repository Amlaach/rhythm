package com.elchanan.rhythm.data

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Process
import com.elchanan.rhythm.engine.Analysis
import com.elchanan.rhythm.data.db.SongEntity
import com.elchanan.rhythm.engine.AudioAnalyzer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

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
        val unreachable: Int = 0,
        /** True when paused waiting for power because analyseOnlyCharging is enabled. */
        val waitingForPower: Boolean = false
    ) {
        val remaining: Int get() = (total - done).coerceAtLeast(0)
        val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
    }

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    private var job: Job? = null

    /**
     * A thread of its own for the pass, at background priority.
     *
     * The pass is hours of decoding and two models on the first run, and it
     * ran on the shared pool at the same priority as everything else, so on a
     * weak phone it competed with the screen for every core and scrolling
     * stuttered while it worked. At background priority the system gives it
     * whatever the interface and the player leave over - the same work, done
     * when there is room for it. TFLite's own threads are started from this
     * one and inherit its priority. What it computes is unchanged.
     */
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            task.run()
        }, "rhythm-analysis").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    /**
     * The fast pass's threads, at normal priority: several songs at once, for
     * a strong phone whose owner would rather it finished tonight than hummed
     * along for days. Made on first use; a phone that never turns it on never
     * starts them.
     */
    private val fastWorker by lazy {
        val songs = AudioAnalyzer.fastSongs()
        Executors.newFixedThreadPool(songs) { task ->
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
                task.run()
            }, "rhythm-analysis-fast").apply { isDaemon = true }
        }.asCoroutineDispatcher() to songs
    }

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
        // Chosen once per pass. The setting's switch restarts a running pass,
        // so a change takes effect at once without two paces mixing in one.
        //
        // Fast or not, each song is measured by the same code with the same
        // models: thread counts do not change what the models compute
        // (checked bit for bit), and songs are independent of each other, so
        // the rows written are the same either way - only sooner.
        val fast = repo.prefs.fastAnalysis
        val (fastDispatcher, parallel) = if (fast) fastWorker else null to 1
        job = scope.launch(fastDispatcher ?: worker) {
            try {
                var total = repo.songCount()
                val done = AtomicInteger(repo.analyzedCount())
                _progress.update { it.copy(running = true, done = done.get(), total = total) }

                // Where the walk has got to, by song id. See the query.
                var after = Long.MIN_VALUE
                val unreachable = AtomicInteger(0)
                while (isActive) {
                    if (repo.prefs.analyseOnlyCharging && !isCharging()) {
                        _progress.update { it.copy(waitingForPower = true, currentTitle = null) }
                        delay(2000)
                        continue
                    } else if (_progress.value.waitingForPower) {
                        _progress.update { it.copy(waitingForPower = false) }
                    }
                    // Batches wide enough to keep every fast worker busy.
                    val batch = repo.songsNeedingAnalysis(after, 12 * parallel)
                    if (batch.isEmpty()) break
                    after = batch.last().id
                    // Asked once per batch rather than once per song: the
                    // answer is a file system check per distinct card, and it
                    // cannot change halfway through twelve songs in a way that
                    // matters.
                    val mounted = Volumes.mountedRoots(batch.map { it.path })
                    // Charging or not, once a batch as well: the charger is
                    // plugged in or out a few times a day, and a batch is
                    // a few minutes at most. The fast pass takes the most
                    // either way.
                    AudioAnalyzer.useThreads(
                        if (fast) AudioAnalyzer.fastThreads() else AudioAnalyzer.threadsFor(isCharging())
                    )
                    if (parallel <= 1) {
                        for (song in batch) {
                            if (!isActive) break
                            if (!measure(song, mounted)) { unreachable.incrementAndGet(); continue }
                            _progress.update { it.copy(done = done.incrementAndGet(), total = total) }
                            // give the rest of the app room to breathe
                            if (!fast) delay(15)
                        }
                    } else {
                        // The fast pass: every song of the batch handed to
                        // the pool, which runs as many at once as it has
                        // threads. Each writes its own row, as before.
                        coroutineScope {
                            for (song in batch) {
                                launch {
                                    if (!isActive) return@launch
                                    if (!measure(song, mounted)) { unreachable.incrementAndGet(); return@launch }
                                    _progress.update { it.copy(done = done.incrementAndGet(), total = total) }
                                }
                            }
                        }
                    }
                    total = repo.songCount()
                }
                _progress.update { it.copy(unreachable = unreachable.get()) }
            } finally {
                // The model holds its weights and a working arena for as long
                // as it is open, and the pass is the only thing that uses it.
                runCatching { AudioAnalyzer.releaseTagger() }
                if (!restarting) _progress.update { it.copy(running = false, currentTitle = null, waitingForPower = false) }
            }
        }
    }

    /** Set while [restart] swaps one pass for the next, so "running" never flickers off. */
    @Volatile
    private var restarting = false

    /**
     * Stops a running pass and starts it again, at whatever pace the setting
     * now says. Nothing when no pass runs.
     *
     * Reads as running throughout. The foreground service that keeps the pass
     * alive stops the moment it sees it not running, and a plain stop and
     * start left it a moment to see exactly that - the pass then carried on
     * with nothing keeping the phone awake for it.
     */
    suspend fun restart() {
        val active = job ?: return
        if (!active.isActive) return
        restarting = true
        try {
            active.cancelAndJoin()
        } finally {
            restarting = false
        }
        job = null
        start()
    }

    /**
     * Measures one song and writes its row. False when the song is on a card
     * that is not in the phone, which is left for a later pass.
     */
    private suspend fun measure(song: SongEntity, mounted: Set<String>): Boolean {
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
        if (root.isNotEmpty() && root !in mounted) return false
        _progress.update { it.copy(currentTitle = song.title) }
        // A song measured before the music model arrived needs
        // only the music model. Running the whole analysis
        // again - YAMNet included, the heaviest part of it -
        // to add one column cost about five seconds a song,
        // hours on a large library, for results it already had.
        val existing = runCatching { repo.feature(song.id) }.getOrNull()
        val feature = runCatching {
            if (existing != null && existing.energy > 0f &&
                existing.soundPrint.isNotEmpty() && existing.musicPrint.isEmpty()
            ) {
                AudioAnalyzer.addMusic(context, song, existing)
            } else {
                AudioAnalyzer.analyze(context, song)
            }
        }.getOrNull()
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
        return true
    }

    /** Whether the phone is plugged in now - charging or already full. Unknown counts as not. */
    private fun isCharging(): Boolean = runCatching {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }.getOrDefault(false)

    fun stop() {
        job?.cancel()
        job = null
        _progress.update { it.copy(running = false, currentTitle = null, waitingForPower = false) }
    }

    /** Stops the writer and waits until its finally block has released it. */
    suspend fun stopAndWait() {
        val active = job
        job = null
        active?.cancelAndJoin()
        _progress.update { it.copy(running = false, currentTitle = null, waitingForPower = false) }
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
