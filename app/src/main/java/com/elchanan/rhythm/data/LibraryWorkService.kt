package com.elchanan.rhythm.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.elchanan.rhythm.MainActivity
import com.elchanan.rhythm.R
import com.elchanan.rhythm.RhythmApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps a scan or an analysis pass alive while the screen is off.
 *
 * Both are the sort of work that takes minutes on a large library - a scan
 * walks every audio file the device knows about, and analysis decodes each one
 * - and both used to run in an ordinary coroutine on the application scope.
 * That is enough to survive navigation, and not nearly enough to survive the
 * screen going off: Android freezes a cached process, so the pass stopped
 * seconds after the screen did and the person came back to a library half
 * scanned. Someone with ten thousand files could not finish a scan at all
 * without sitting and watching it.
 *
 * A foreground service is the only thing that says "this is work the user
 * asked for and is waiting on" in a way the system honours. The notification
 * is the price of that and is also the right thing to show: work that takes
 * minutes should say so, and be stoppable.
 *
 * The service holds no work of its own. It starts what already exists and
 * waits for it, so there is one implementation of scanning and one of
 * analysis, not two.
 */
class LibraryWorkService : Service() {

    private var scope: CoroutineScope? = null
    private var work: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val scan = intent?.getBooleanExtra(EXTRA_SCAN, false) ?: false
        val analyze = intent?.getBooleanExtra(EXTRA_ANALYZE, false) ?: false
        if (!scan && !analyze) {
            stopSelf()
            return START_NOT_STICKY
        }

        createChannel()
        startForegroundCompat(notification(getString(R.string.work_preparing), 0, 0))

        // Already busy: the extras of the second request are folded into the
        // first rather than starting a competing pass, because two scans over
        // one library is the same answer twice at twice the cost.
        if (work?.isActive == true) return START_NOT_STICKY

        val app = applicationContext as RhythmApp
        val holder = CoroutineScope(SupervisorJob())
        scope = holder

        // The service keeps the process alive; the wake lock keeps the CPU
        // from idling between the file reads, which on some devices is the
        // difference between a pass that takes four minutes and one that
        // takes forty. Timed, so a bug here can never hold the CPU awake for
        // longer than the longest plausible pass.
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rhythm:library")
            .also { lock ->
                lock.setReferenceCounted(false)
                runCatching { lock.acquire(WAKE_LOCK_MS) }
            }

        work = holder.launch {
            try {
                if (scan) {
                    notify(getString(R.string.work_scanning), 0, 0)
                    runCatching { app.repository.rescan() }
                    app.analysis.refreshCounts()
                }
                if (analyze || (scan && app.repository.prefs.autoAnalyze)) {
                    app.analysis.start()
                    // Follow the pass rather than duplicating it: the manager
                    // owns the loop, and this only has to keep the service up
                    // and the notification honest until it finishes.
                    while (isActive) {
                        val progress = app.analysis.progress.value
                        if (!progress.running && progress.remaining == 0) break
                        notify(
                            getString(R.string.work_analyzing),
                            progress.done,
                            progress.total
                        )
                        if (!progress.running) break
                        delay(700)
                    }
                }
            } finally {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        work?.cancel()
        scope?.cancel()
        scope = null
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                getString(R.string.work_channel),
                // Low: this is a progress bar, not news. It belongs in the
                // shade without a sound or a heads up.
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
        )
    }

    private fun notification(text: String, done: Int, total: Int): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .apply {
                if (total > 0) setProgress(total, done.coerceIn(0, total), false)
                else setProgress(0, 0, true)
            }
            .build()
    }

    private fun notify(text: String, done: Int, total: Int) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(NOTIFICATION_ID, notification(text, done, total)) }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL = "library_work"
        private const val NOTIFICATION_ID = 4711
        private const val EXTRA_SCAN = "scan"
        private const val EXTRA_ANALYZE = "analyze"

        /** Longer than any plausible pass, and short enough to be a backstop. */
        private const val WAKE_LOCK_MS = 60L * 60L * 1000L

        /**
         * Starts the work, or runs it in the caller's process if the system
         * will not allow a foreground service right now.
         *
         * Android will not let an app in the background start one, and there
         * is no way to ask politely. The fallback is what the app did before
         * this service existed: it works, it just does not survive the screen
         * going off - which is better than the work not happening at all.
         *
         * @return true when the service took it.
         */
        fun start(context: Context, scan: Boolean, analyze: Boolean): Boolean {
            val intent = Intent(context, LibraryWorkService::class.java)
                .putExtra(EXTRA_SCAN, scan)
                .putExtra(EXTRA_ANALYZE, analyze)
            return runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                true
            }.getOrDefault(false)
        }
    }
}
