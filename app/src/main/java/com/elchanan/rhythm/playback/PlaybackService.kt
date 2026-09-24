package com.elchanan.rhythm.playback

import android.app.PendingIntent
import android.content.Intent
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import com.elchanan.rhythm.engine.AudioAnalyzer
import com.elchanan.rhythm.engine.TrailingSilence
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.elchanan.rhythm.MainActivity
import com.elchanan.rhythm.R
import com.elchanan.rhythm.RhythmApp
import com.elchanan.rhythm.data.MusicRepository
import com.elchanan.rhythm.engine.Listening
import com.elchanan.rhythm.engine.Spoken
import com.elchanan.rhythm.ui.theme.UiStrings
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the ExoPlayer instance and the media session.
 *
 * It also doubles as the app's telemetry point: everything the recommendation
 * engine learns about listening behaviour is measured here, because this is
 * the only component that stays alive when the UI is gone.
 */
/**
 * Accepts media3's unstable API for this class.
 *
 * DefaultRenderersFactory, DefaultAudioSink and the AudioProcessor chain
 * are all unstable. They are what the equaliser is built on, and there is
 * no stable equivalent.
 */
@OptIn(markerClass = [UnstableApi::class])
class PlaybackService : MediaSessionService() {

    companion object {
        const val ACTION_LIKE = "com.elchanan.rhythm.LIKE"

        /** How much of a track's end is looked at for silence. */
        private const val TAIL_MS = 25_000L
        const val ACTION_DISLIKE = "com.elchanan.rhythm.DISLIKE"
        const val ACTION_RADIO = "com.elchanan.rhythm.RADIO"
    }


    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
    private lateinit var graphicEqProcessor: GraphicEqProcessor
    private lateinit var repo: MusicRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // --- listening measurement -------------------------------------------------
    private var trackedId: Long = -1L

    /** The track a saved position was already applied to, so it happens once. */
    private var resumedFor: Long = -1L
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

    /** per track loudness gains; empty until enough of the library is analysed */
    private var gains: Map<Long, Float> = emptyMap()

    /** system equaliser bound to the player's audio session */
    private var eq: EqController? = null

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
        // The player's own volume: taken from the settings, and applied the
        // moment the slider moves. While a fade runs, its next step picks the
        // new level up by itself.
        AppVolume.set(repo.prefs.appVolume)
        AppVolume.onChange = {
            handler.post { if (!fadeRunning) runCatching { player.volume = trackGain() } }
        }

        // The equaliser has to be built before the player, because it goes
        // inside the audio sink rather than being attached to it afterwards.
        graphicEqProcessor = GraphicEqProcessor()

        player = ExoPlayer.Builder(this)
            .setRenderersFactory(equalisingRenderers())
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
        refreshGains()
        // The session id only exists once the audio sink is up, and it changes
        // whenever playback restarts, so the effect is re-attached rather than
        // created once.
        eq = EqController(repo.prefs)
        attachEqualizer()
        EqBridge.graphic = GraphicEqController(repo.prefs, graphicEqProcessor)

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(openAppIntent())
            .setCallback(SessionCallback())
            .build()
        mediaSession?.setCustomLayout(customLayout(likedNow))
        watchLikeState()
        refreshLikeButtons()

        registerVolumeWatcher()

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

    // -------------------------------------------------------------------------
    // pausing when the volume is taken to zero
    // -------------------------------------------------------------------------

    /** Set when this feature paused playback, so only it resumes it. */
    private var pausedByVolume = false

    private val volumeWatcher = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            if (!repo.prefs.pauseOnSilence) return
            val audio = getSystemService(AUDIO_SERVICE) as? AudioManager ?: return
            val level = runCatching { audio.getStreamVolume(AudioManager.STREAM_MUSIC) }
                .getOrDefault(1)
            if (level == 0) {
                if (player.isPlaying) {
                    pausedByVolume = true
                    player.pause()
                }
            } else if (pausedByVolume) {
                // Only resume what this actually stopped. Someone who pressed
                // pause and then changed the volume did not ask for the song to
                // start again.
                pausedByVolume = false
                player.play()
            }
        }
    }

    private fun registerVolumeWatcher() {
        runCatching {
            contentResolver.registerContentObserver(
                Settings.System.CONTENT_URI,
                true,
                volumeWatcher
            )
        }
    }

    /**
     * What the notification opens when it is tapped.
     *
     * Without a session activity the notification has no content intent at all,
     * so tapping the body of it does nothing - which is the first gesture
     * anybody tries. The activity is singleTask, so this brings the running
     * instance forward instead of stacking a second copy of the player on top
     * of itself.
     */
    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        // Immutable pending intents only exist from Android 6.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = mediaSession?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            stopSelf()
        }
    }

    // -------------------------------------------------------------------------
    // notification buttons
    // -------------------------------------------------------------------------

    /**
     * What the current track has been marked, so the notification can show it.
     *
     * Held here because the buttons are rebuilt from it, and reading it from
     * the database at that moment would mean a suspending call inside a
     * callback that has to answer immediately.
     */
    @Volatile
    private var likedNow: Int = 0

    /**
     * Which song the buttons are currently drawn for.
     *
     * A field rather than a read of the player because the thing that decides
     * what the thumbs look like is a database row, and watching a row means a
     * flow to combine it with.
     */
    private val shownSongId = MutableStateFlow<Long?>(null)

    /**
     * The three buttons, drawn for the state the track is actually in.
     *
     * They were fixed icons set once at startup, so pressing like recorded the
     * like and then changed nothing on screen. The press looked ignored, and
     * the only way to find out whether it had registered was to open the app.
     * A filled thumb against an outlined one is the whole of the feedback, and
     * it is the reason the buttons are rebuilt on every change.
     */
    private fun customLayout(liked: Int): List<CommandButton> = listOf(
        CommandButton.Builder()
            .setDisplayName(UiStrings.translate(if (liked == 1) "בטל לייק" else "לייק", repo.prefs.language))
            .setIconResId(
                if (liked == 1) R.drawable.ic_thumb_up else R.drawable.ic_thumb_up_outline
            )
            .setSessionCommand(SessionCommand(ACTION_LIKE, Bundle.EMPTY))
            .setEnabled(true)
            .build(),
        CommandButton.Builder()
            .setDisplayName(UiStrings.translate(if (liked == -1) "בטל דיסלייק" else "דיסלייק", repo.prefs.language))
            .setIconResId(
                if (liked == -1) R.drawable.ic_thumb_down else R.drawable.ic_thumb_down_outline
            )
            .setSessionCommand(SessionCommand(ACTION_DISLIKE, Bundle.EMPTY))
            .setEnabled(true)
            .build(),
        CommandButton.Builder()
            .setDisplayName(UiStrings.translate("רדיו", repo.prefs.language))
            .setIconResId(R.drawable.ic_radio)
            .setSessionCommand(SessionCommand(ACTION_RADIO, Bundle.EMPTY))
            .setEnabled(true)
            .build()
    )

    /**
     * Points the buttons at whatever is playing now.
     *
     * It no longer reads the mark itself. The mark is a database row, and the
     * row changes from two places - the notification's own thumbs and the
     * player screen inside the app - so reading it once at the moment of a
     * press only ever caught one of them. Liking a song in the app left the
     * notification showing an outline until the track changed.
     */
    private fun refreshLikeButtons() {
        shownSongId.value = player.currentMediaItem?.mediaId?.toLongOrNull()
    }

    /**
     * Draws the buttons for [liked] and puts the result on screen.
     *
     * setCustomLayout on its own tells the connected controllers, which is
     * what the app itself listens to. The notification in the shade is drawn
     * from a posted notification and is only rebuilt when one is posted, so
     * without the second call the command ran, the like was recorded, and the
     * thumb on screen stayed exactly as it was - which is what a press that
     * looks ignored actually is.
     */
    private fun applyLikeButtons(liked: Int) {
        likedNow = liked
        val session = mediaSession ?: return
        runCatching { session.setCustomLayout(customLayout(liked)) }
        runCatching { onUpdateNotification(session, player.isPlaying) }
    }

    /**
     * Keeps the thumbs in step with the row they describe.
     *
     * Combined rather than read on demand: the stats table is written by the
     * notification, by the player screen and by playback itself, and the only
     * way for one set of buttons to be right for all three is to watch the
     * row. distinctUntilChanged keeps that cheap - play counts land in the
     * same table constantly and almost none of them change a thumb.
     */
    private fun watchLikeState() {
        scope.launch {
            combine(shownSongId, repo.stats) { id, rows ->
                if (id == null) 0 else rows.firstOrNull { it.songId == id }?.liked ?: 0
            }
                .distinctUntilChanged()
                .collect { applyLikeButtons(it) }
        }
    }

    override fun onDestroy() {
        AppVolume.onChange = null
        handler.removeCallbacks(silenceRunnable)
        runCatching { contentResolver.unregisterContentObserver(volumeWatcher) }
        finalizeCurrent(manual = false)
        persistQueue()
        handler.removeCallbacks(sleepRunnable)
        stopFadeLoop()
        player.removeListener(listener)
        mediaSession?.release()
        player.release()
        mediaSession = null
        EqBridge.controller = null
        EqBridge.graphic = null
        eq?.release()
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
                .setCustomLayout(customLayout(likedNow))
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
                            // The skip redraws them on its own through the
                            // transition, so this only has to move on.
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

    /**
     * Which albums lend their picture, when the app's screens have not said.
     *
     * The screens work it out when the library loads. Radio from the
     * notification and the queue's refill run here with no screen open, and
     * until then every folder of singles lent one song's cover to the lock
     * screen and the notification for all the others.
     */
    private suspend fun noteLibrary() {
        if (MediaItems.noted) return
        runCatching { MediaItems.noteLibrary(repo.allSongsForExport()) }
    }

    /** "Start radio" straight from the notification, without opening the app. */
    private suspend fun startRadioFromCurrent(songId: Long) {
        val song = repo.songById(songId) ?: return
        val engine = runCatching { repo.buildRecommender() }.getOrNull() ?: return
        val list = withContext(Dispatchers.Default) { engine.radio(song, 40) }
        if (list.isEmpty()) return
        QueueMeta.reset()
        QueueMeta.markAuto(list.drop(1).map { it.id })
        noteLibrary()
        player.setMediaItems(list.map { MediaItems.toMediaItem(it) }, 0, 0L)
        player.prepare()
        player.play()
    }

    private val listener = object : Player.Listener {

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // A move past the silence at the end of a track is a seek to the
            // player, and the track played out all the same.
            val manual = (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK && !movingPastSilence) ||
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
            movingPastSilence = false
            // Remembered before the track is torn down, while the position it
            // was left at is still readable.
            rememberPosition()
            finalizeCurrent(manual)
            if (SleepTimer.consumeStopAfterTrack()) {
                player.pause()
            }
            startTracking(mediaItem)
            findTrailingSilence(mediaItem)
            applyTrackGain()
            attachEqualizer()
            persistQueue()
            maybeExtendQueue()
            refreshLikeButtons()
            RhythmWidget.refresh(this@PlaybackService, player)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                resumedAt = System.currentTimeMillis()
                startFadeLoop()
                handler.removeCallbacks(silenceRunnable)
                handler.post(silenceRunnable)
            } else {
                handler.removeCallbacks(silenceRunnable)
                absorb()
                persistQueue()
                rememberPosition()
                stopFadeLoop()
            }
            // The widget draws its play button from this exact flag, and it was
            // only ever redrawn when the track changed - so pausing left it
            // showing a pause icon over a stopped player until the next song.
            RhythmWidget.refresh(this@PlaybackService, player)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                if (trackedDurationMs <= 0L) {
                    val d = player.duration
                    if (d > 0L) trackedDurationMs = d
                }
                // Here rather than on the transition: the duration is not
                // known until the player is ready, and without it there is no
                // way to tell a long recording from a song.
                resumeIfLong()
            }
            if (playbackState == Player.STATE_ENDED) {
                finalizeCurrent(manual = false)
            }
        }
    }

    // -------------------------------------------------------------------------
    // silence at the end of a track, in the radio
    // -------------------------------------------------------------------------

    /** Where to move on from the track [moveOnFor], or -1 for its natural end. */
    private var moveOnAtMs = -1L
    private var moveOnFor = -1L

    /** Set just before moving past the silence, so the move counts as the track ending. */
    private var movingPastSilence = false

    /** What each track's end was found to be, so a song heard twice is decoded once. */
    private val silenceFound = object : LinkedHashMap<Long, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Long>?) = size > 200
    }

    /**
     * Looks for dead air at the end of a track the radio chose - a song the
     * listener queued is theirs, silence and all - by decoding its last
     * stretch off the main thread. See [TrailingSilence].
     */
    private fun findTrailingSilence(item: MediaItem?) {
        moveOnAtMs = -1L
        moveOnFor = -1L
        val id = item?.mediaId?.toLongOrNull() ?: return
        if (!repo.prefs.trimRadioSilence || !QueueMeta.isAuto(id)) return
        silenceFound[id]?.let { at ->
            moveOnFor = id
            moveOnAtMs = at
            return
        }
        scope.launch {
            val at = withContext(Dispatchers.IO) {
                // Behind the player: decoding the end of a track is work for
                // a spare moment, and on a slow phone it must never take the
                // time the song that is playing needs.
                val tid = android.os.Process.myTid()
                val before = runCatching { android.os.Process.getThreadPriority(tid) }.getOrDefault(0)
                runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
                try { runCatching {
                    val song = repo.songById(id) ?: return@runCatching -1L
                    val duration = song.durationMs
                    if (duration < TAIL_MS * 2) return@runCatching -1L
                    val startMs = duration - TAIL_MS
                    val (samples, rate) = AudioAnalyzer.decodeMono(
                        this@PlaybackService, MediaItems.songUri(id), startMs * 1000L, (TAIL_MS / 1000L).toInt()
                    ) ?: return@runCatching -1L
                    TrailingSilence.moveOnAt(samples, rate, startMs, duration) ?: -1L
                }.getOrDefault(-1L) } finally {
                    runCatching { android.os.Process.setThreadPriority(before) }
                }
            }
            silenceFound[id] = at
            if (player.currentMediaItem?.mediaId?.toLongOrNull() == id) {
                moveOnFor = id
                moveOnAtMs = at
            }
        }
    }

    private val silenceRunnable = object : Runnable {
        override fun run() {
            val id = player.currentMediaItem?.mediaId?.toLongOrNull()
            if (id != null && id == moveOnFor && moveOnAtMs > 0 &&
                player.currentPosition >= moveOnAtMs && player.hasNextMediaItem()
            ) {
                // Heard to where the sound stopped: that is the end of it.
                trackedDurationMs = moveOnAtMs
                moveOnFor = -1L
                movingPastSilence = true
                player.seekToNextMediaItem()
            }
            if (player.isPlaying) handler.postDelayed(this, 400)
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
        player.volume = trackGain()
    }

    /**
     * ExoPlayer has no true overlapping crossfade, so this ramps the single
     * output down at the end of a track and back up at the start of the next.
     * The gap itself stays gapless; only the loudness curve changes.
     */
    private fun applyFadeVolume() {
        val fade = repo.prefs.crossfadeMs
        if (fade <= 0) {
            player.volume = trackGain()
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
        // The floor keeps a fade from reaching true silence; the listener's own
        // volume at zero is meant to.
        player.volume = (volume * trackGain()).coerceIn(if (AppVolume.gain > 0f) 0.02f else 0f, 1f)
    }

    /** The level for whatever is playing: the loudness correction, at the listener's own volume. */
    private fun trackGain(): Float = levelling() * AppVolume.gain

    /** The loudness correction alone, or 1 when off or unknown. */
    private fun levelling(): Float {
        if (!repo.prefs.normalizeVolume) return 1f
        val id = player.currentMediaItem?.mediaId?.toLongOrNull() ?: return 1f
        return gains[id] ?: 1f
    }

    /**
     * The ramp loop only runs when crossfade is enabled, so with it off nothing
     * would ever apply a gain. Setting it on each transition covers that case.
     */
    private fun applyTrackGain() {
        if (repo.prefs.crossfadeMs <= 0) player.volume = trackGain()
    }

    private fun attachEqualizer() {
        runCatching { eq?.attach(player.audioSessionId) }
        EqBridge.controller = eq
    }

    /**
     * A renderers factory whose audio sink has the equaliser in it.
     *
     * This is the only hook ExoPlayer offers for touching decoded audio: the
     * sink is built here, and the processors it is given run over every buffer
     * on its way out. Passing the two flags straight back through keeps
     * whatever the default would have decided about float output and playback
     * speed - the point is to add a processor, not to take over the sink.
     *
     * Offload is deliberately never enabled. It hands the compressed stream to
     * the DSP and saves a little battery, and it would skip the equaliser
     * entirely, which is not a trade worth making silently.
     */
    private fun equalisingRenderers(): RenderersFactory =
        object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                // Explicitly typed: Kotlin arrays are invariant, so an
                // Array<GraphicEqProcessor> is not an Array<AudioProcessor>.
                .setAudioProcessors(arrayOf<AudioProcessor>(graphicEqProcessor))
                .build()
        }

    private fun refreshGains() {
        scope.launch {
            gains = runCatching { repo.loudnessGains() }.getOrDefault(emptyMap())
            applyTrackGain()
        }
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
        val minimum = repo.prefs.minPlayMs
        if (listened < minimum) {
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
        // One copy of the rule, in :engine, because the recommender divides
        // skips by attempts and two builds that disagree about what an attempt
        // is will rank the same library differently. Written out here and
        // again on the desktop until now, and the two had already drifted on
        // the case of a file with no length in its tags.
        val counted = Listening.countsAsPlay(listened, duration, endedOnItsOwn = !manual, minimumMs = minimum)
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
    /**
     * Notes where the current song was left, if it is worth coming back to.
     *
     * Bounded at both ends on purpose. The first seconds are not a place worth
     * returning to, and something abandoned near the end was effectively
     * finished - offering to resume either would be noise where the point is to
     * rescue a long track someone actually got lost in.
     */
    private fun rememberPosition() {
        val id = player.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        val position = player.currentPosition
        val duration = player.duration

        // Kept for everything, not only when the prompt is on, because the
        // long recordings resume by themselves and the shelf of what is part
        // heard is built from these rows.
        if (duration > 0L) {
            scope.launch { repo.savePosition(id, position, duration) }
        }

        if (!repo.prefs.resumePrompt) return
        val points = repo.prefs.resumePoints.toMutableMap()
        if (position > 30_000L && (duration <= 0L || position < duration - 30_000L)) {
            points[id] = position
        } else {
            points.remove(id)
        }
        repo.prefs.resumePoints = points
    }

    /**
     * Picks a long recording up where it was left.
     *
     * Only for things past [Spoken.SHORT_MINUTES]. A song does not need it -
     * starting a song again costs nothing, and silently jumping into the
     * middle of one would be wrong. An hour of speech is the opposite: finding
     * the place again by dragging a bar is the whole problem this solves.
     *
     * Length rather than the speech detector, deliberately. The detector needs
     * the file to have been analysed, and this has to work on the first play -
     * which is exactly when the file has not been analysed yet.
     */
    private fun resumeIfLong() {
        val id = player.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        if (id == resumedFor) return
        resumedFor = id
        if (!repo.prefs.resumeSpoken) return
        val duration = player.duration
        if (duration <= Spoken.SHORT_MINUTES * 60_000L) return
        // Already somewhere other than the start: the user seeked, or the
        // queue was restored with its own position.
        if (player.currentPosition > 5_000L) return
        scope.launch {
            val saved = repo.position(id) ?: return@launch
            if (saved.positionMs <= 0L) return@launch
            runCatching { player.seekTo(saved.positionMs) }
        }
    }

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
                val timeline = player.currentTimeline
                val seedId = player.currentMediaItem?.mediaId
                val engine = repo.buildRecommender()
                val more = withContext(Dispatchers.Default) { engine.continuation(recent, existing, 15) }
                // The user may replace/reorder the queue while ranking runs.
                if (player.currentTimeline != timeline || player.currentMediaItem?.mediaId != seedId) return@launch
                if (more.isNotEmpty()) {
                    QueueMeta.markAuto(more.map { it.id })
                    noteLibrary()
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
