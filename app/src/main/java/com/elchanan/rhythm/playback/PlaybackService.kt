package com.elchanan.rhythm.playback

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.elchanan.rhythm.RhythmApp
import com.elchanan.rhythm.R
import com.elchanan.rhythm.data.MusicRepository
import android.os.Bundle
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Owns the ExoPlayer instance and the media session.
 *
 * It also doubles as the app's telemetry point: everything the recommendation
 * engine learns about listening behaviour is measured here, because this is
 * the only component that stays alive when the UI is gone.
 */
class PlaybackService : MediaSessionService() {

    companion object {
        const val ACTION_LIKE = "com.elchanan.rhythm.LIKE"
        const val ACTION_DISLIKE = "com.elchanan.rhythm.DISLIKE"
        const val ACTION_RADIO = "com.elchanan.rhythm.RADIO"
    }


    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
    private lateinit var repo: MusicRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // --- listening measurement -------------------------------------------------
    private var trackedId: Long = -1L
    private var trackedDurationMs: Long = 0L
    private var accumulatedMs: Long = 0L
    private var resumedAt: Long = 0L
    private var lastActivityAt: Long = 0L
    private val sessionTail = ArrayDeque<Long>()
    private var extending = false

    /** the last song that actually counted as a play, for the directed matrix */
    private var lastCountedId: Long = -1L
    private var lastCountedAt: Long = 0L

    private val handler = Handler(Looper.getMainLooper())

    /** volume ramp between tracks; 0 in preferences means it never runs */
    private val fadeRunnable = object : Runnable {
        override fun run() {
            applyFadeVolume()
            handler.postDelayed(this, 120L)
        }
    }
    private var fadeRunning = false
    private val sleepRunnable = Runnable {
        if (player.isPlaying) player.pause()
        SleepTimer.cancel()
    }

    override fun onCreate() {
        super.onCreate()
        repo = (application as RhythmApp).repository

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()

        player.addListener(listener)

        player.skipSilenceEnabled = repo.prefs.skipSilence

        mediaSession = MediaSession.Builder(this, player)
            .setCallback(SessionCallback())
            .build()
        mediaSession?.setCustomLayout(customLayout())

        scope.launch {
            SleepTimer.deadlineElapsed.collectLatest { deadline ->
                handler.removeCallbacks(sleepRunnable)
                if (deadline != null) {
                    val delay = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                    handler.postDelayed(sleepRunnable, delay)
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = mediaSession?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            stopSelf()
        }
    }

    // -------------------------------------------------------------------------
    // notification buttons
    // -------------------------------------------------------------------------

    private fun customLayout(): List<CommandButton> = listOf(
        CommandButton.Builder()
            .setDisplayName("לייק")
            .setIconResId(R.drawable.ic_thumb_up)
            .setSessionCommand(SessionCommand(ACTION_LIKE, Bundle.EMPTY))
            .setEnabled(true)
            .build(),
        CommandButton.Builder()
            .setDisplayName("דיסלייק")
            .setIconResId(R.drawable.ic_thumb_down)
            .setSessionCommand(SessionCommand(ACTION_DISLIKE, Bundle.EMPTY))
            .setEnabled(true)
            .build(),
        CommandButton.Builder()
            .setDisplayName("רדיו")
            .setIconResId(R.drawable.ic_radio)
            .setSessionCommand(SessionCommand(ACTION_RADIO, Bundle.EMPTY))
            .setEnabled(true)
            .build()
    )

    override fun onDestroy() {
        finalizeCurrent(manual = false)
        persistQueue()
        handler.removeCallbacks(sleepRunnable)
        stopFadeLoop()
        player.removeListener(listener)
        mediaSession?.release()
        player.release()
        mediaSession = null
        scope.cancel()
        super.onDestroy()
    }

    // -------------------------------------------------------------------------
    // Media items crossing the session binder lose their local uri, so they are
    // rebuilt here from the request metadata.
    // -------------------------------------------------------------------------
    private inner class SessionCallback : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val available = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                .add(SessionCommand(ACTION_LIKE, Bundle.EMPTY))
                .add(SessionCommand(ACTION_DISLIKE, Bundle.EMPTY))
                .add(SessionCommand(ACTION_RADIO, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(available)
                .setCustomLayout(customLayout())
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            val id = player.currentMediaItem?.mediaId?.toLongOrNull()
            if (id != null) {
                scope.launch {
                    when (customCommand.customAction) {
                        ACTION_LIKE -> repo.setLike(id, 1)
                        ACTION_DISLIKE -> {
                            repo.setLike(id, -1)
                            player.seekToNextMediaItem()
                        }
                        ACTION_RADIO -> startRadioFromCurrent(id)
                    }
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolved = mediaItems.map { item ->
                val uri = item.requestMetadata.mediaUri
                    ?: item.mediaId.toLongOrNull()?.let { MediaItems.songUri(it) }
                if (uri == null) item else item.buildUpon().setUri(uri).build()
            }.toMutableList()
            return Futures.immediateFuture(resolved)
        }
    }

    /** "Start radio" straight from the notification, without opening the app. */
    private suspend fun startRadioFromCurrent(songId: Long) {
        val song = repo.songById(songId) ?: return
        val engine = runCatching { repo.buildRecommender() }.getOrNull() ?: return
        val list = engine.radio(song, 40)
        if (list.isEmpty()) return
        QueueMeta.reset()
        QueueMeta.markAuto(list.drop(1).map { it.id })
        player.setMediaItems(list.map { MediaItems.toMediaItem(it) }, 0, 0L)
        player.prepare()
        player.play()
    }

    private val listener = object : Player.Listener {

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val manual = reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK ||
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
            finalizeCurrent(manual)
            if (SleepTimer.consumeStopAfterTrack()) {
                player.pause()
            }
            startTracking(mediaItem)
            persistQueue()
            maybeExtendQueue()
            RhythmWidget.refresh(this@PlaybackService, player)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                resumedAt = System.currentTimeMillis()
                startFadeLoop()
            } else {
                absorb()
                persistQueue()
                stopFadeLoop()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY && trackedDurationMs <= 0L) {
                val d = player.duration
                if (d > 0L) trackedDurationMs = d
            }
            if (playbackState == Player.STATE_ENDED) {
                finalizeCurrent(manual = false)
            }
        }
    }

    // -------------------------------------------------------------------------
    // volume ramp between tracks
    // -------------------------------------------------------------------------

    private fun startFadeLoop() {
        if (fadeRunning) return
        if (repo.prefs.crossfadeMs <= 0) return
        fadeRunning = true
        handler.post(fadeRunnable)
    }

    private fun stopFadeLoop() {
        if (!fadeRunning) return
        fadeRunning = false
        handler.removeCallbacks(fadeRunnable)
        player.volume = 1f
    }

    /**
     * ExoPlayer has no true overlapping crossfade, so this ramps the single
     * output down at the end of a track and back up at the start of the next.
     * The gap itself stays gapless; only the loudness curve changes.
     */
    private fun applyFadeVolume() {
        val fade = repo.prefs.crossfadeMs
        if (fade <= 0) {
            player.volume = 1f
            return
        }
        val duration = player.duration
        val position = player.currentPosition
        var volume = 1f
        if (duration > 0 && player.hasNextMediaItem()) {
            val remaining = duration - position
            if (remaining in 0..fade) volume = minOf(volume, remaining.toFloat() / fade)
        }
        if (position in 0..fade) {
            volume = minOf(volume, position.toFloat() / fade)
        }
        player.volume = volume.coerceIn(0.02f, 1f)
    }

    private fun absorb() {
        if (resumedAt > 0L) {
            accumulatedMs += System.currentTimeMillis() - resumedAt
            resumedAt = 0L
        }
    }

    private fun startTracking(item: MediaItem?) {
        trackedId = item?.mediaId?.toLongOrNull() ?: -1L
        accumulatedMs = 0L
        resumedAt = if (player.isPlaying) System.currentTimeMillis() else 0L
        trackedDurationMs = player.duration.let { if (it > 0) it else 0L }
    }

    private fun finalizeCurrent(manual: Boolean) {
        val id = trackedId
        if (id <= 0L) return
        absorb()
        val listened = accumulatedMs
        trackedId = -1L
        accumulatedMs = 0L
        if (listened < 3_000L) {
            trackedDurationMs = 0L
            return
        }

        val duration = trackedDurationMs
        val ratio = if (duration > 0) listened.toDouble() / duration else 0.0
        val now = System.currentTimeMillis()

        // a gap of more than 40 minutes means this is a new listening session
        if (lastActivityAt > 0 && now - lastActivityAt > 40 * 60_000L) sessionTail.clear()
        lastActivityAt = now

        trackedDurationMs = 0L
        val counted = ratio >= 0.5 || listened >= 90_000L ||
            (duration <= 0L && listened >= 60_000L)
        // the directed edge is written before lastCountedId moves on
        val previous = lastCountedId
        val previousFresh = previous > 0L && now - lastCountedAt < 15 * 60_000L

        scope.launch {
            if (counted) {
                val tail = sessionTail.toList()
                repo.recordPlay(id, listened, ratio >= 0.9, tail)
                if (previousFresh && previous != id) {
                    repo.recordTransition(previous, id, skipped = false)
                }
                sessionTail.addFirst(id)
                while (sessionTail.size > 6) sessionTail.removeLast()
            } else if (manual) {
                repo.recordSkip(id, listened)
                if (previousFresh && previous != id) {
                    repo.recordTransition(previous, id, skipped = true)
                }
            }
        }
        if (counted) {
            lastCountedId = id
            lastCountedAt = now
        }
    }

    /**
     * Saves the queue so the next launch can restore it. Cheap enough to call
     * on every transition: it writes a comma separated list of ids.
     */
    private fun persistQueue() {
        runCatching {
            val ids = ArrayList<Long>(player.mediaItemCount)
            for (i in 0 until player.mediaItemCount) {
                player.getMediaItemAt(i).mediaId.toLongOrNull()?.let { ids.add(it) }
            }
            repo.prefs.savedQueue = ids
            repo.prefs.savedQueueIndex = player.currentMediaItemIndex.coerceAtLeast(0)
            repo.prefs.savedQueuePosition = player.currentPosition.coerceAtLeast(0L)
        }
    }

    /** Endless radio: top the queue up before it runs dry. */
    private fun maybeExtendQueue() {
        if (extending) return
        if (!repo.prefs.autoRadio) return
        val count = player.mediaItemCount
        if (count == 0) return
        if (player.currentMediaItemIndex < count - 3) return
        extending = true
        scope.launch {
            try {
                val existing = HashSet<Long>()
                for (i in 0 until player.mediaItemCount) {
                    player.getMediaItemAt(i).mediaId.toLongOrNull()?.let { existing.add(it) }
                }
                val recent = ArrayList<Long>()
                var i = player.currentMediaItemIndex
                while (i >= 0 && recent.size < 5) {
                    player.getMediaItemAt(i).mediaId.toLongOrNull()?.let { recent.add(it) }
                    i--
                }
                val engine = repo.buildRecommender()
                val more = engine.continuation(recent, existing, 15)
                if (more.isNotEmpty()) {
                    QueueMeta.markAuto(more.map { it.id })
                    player.addMediaItems(more.map { MediaItems.toMediaItem(it) })
                }
            } catch (_: Throwable) {
                // an empty library or a mid-scan database simply means no extension
            } finally {
                extending = false
            }
        }
    }
}
