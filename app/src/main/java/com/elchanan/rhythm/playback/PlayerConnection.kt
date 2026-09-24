package com.elchanan.rhythm.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.elchanan.rhythm.data.db.SongEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PlayerUiState(
    val connected: Boolean = false,
    val currentSongId: Long? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedMs: Long = 0L,
    val queueIds: List<Long> = emptyList(),
    val queueIndex: Int = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF
)

/**
 * Thin, UI facing wrapper around the MediaController.
 * Nothing in the UI layer ever touches ExoPlayer directly.
 */
class PlayerConnection(
    private val context: Context,
    private val scope: CoroutineScope
) {

    private var controller: MediaController? = null

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) =
            sync(refreshQueue = events.contains(Player.EVENT_TIMELINE_CHANGED))
    }

    fun connect() {
        if (controller != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            try {
                val c = future.get()
                controller = c
                c.addListener(listener)
                sync()
                startTicker()
            } catch (_: Throwable) {
                // service could not start; the UI stays in the disconnected state
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun release() {
        controller?.removeListener(listener)
        controller?.release()
        controller = null
        _state.value = PlayerUiState()
    }

    private fun startTicker() {
        scope.launch {
            while (controller != null) {
                val c = controller
                if (c != null && c.isPlaying) {
                    _state.value = _state.value.copy(
                        positionMs = c.currentPosition.coerceAtLeast(0L),
                        bufferedMs = c.bufferedPosition.coerceAtLeast(0L)
                    )
                }
                delay(250)
            }
        }
    }

    private fun sync(refreshQueue: Boolean = true) {
        val c = controller ?: return
        // Play/pause, seek and buffer events do not change the queue.
        val ids = if (refreshQueue) {
            List(c.mediaItemCount) { i -> c.getMediaItemAt(i).mediaId.toLongOrNull() ?: -1L }
        } else _state.value.queueIds
        _state.value = PlayerUiState(
            connected = true,
            currentSongId = c.currentMediaItem?.mediaId?.toLongOrNull(),
            isPlaying = c.isPlaying,
            positionMs = c.currentPosition.coerceAtLeast(0L),
            durationMs = c.duration.let { if (it > 0) it else 0L },
            bufferedMs = c.bufferedPosition.coerceAtLeast(0L),
            queueIds = ids,
            queueIndex = c.currentMediaItemIndex.coerceAtLeast(0),
            shuffle = c.shuffleModeEnabled,
            repeatMode = c.repeatMode
        )
    }

    // -----------------------------------------------------------------------

    private fun items(songs: List<SongEntity>): List<MediaItem> = songs.map { MediaItems.toMediaItem(it) }

    fun play(songs: List<SongEntity>, startIndex: Int = 0) {
        val c = controller ?: return
        if (songs.isEmpty()) return
        c.setMediaItems(items(songs), startIndex.coerceIn(0, songs.lastIndex), 0L)
        c.prepare()
        c.play()
    }

    /**
     * A new queue that begins with the song already playing: that song goes
     * on from where it is, and the rest of the queue is replaced behind it.
     *
     * Radio and mix from the player start from the song on screen, and
     * handing the whole list to [play] started that song again from the
     * beginning - the reported "it jumps back". Returns false when the list
     * does not begin with the current song, for the caller to play it anew.
     */
    fun continueFromCurrent(songs: List<SongEntity>): Boolean {
        val c = controller ?: return false
        val current = c.currentMediaItem ?: return false
        if (songs.isEmpty() || current.mediaId != songs.first().id.toString()) return false
        val index = c.currentMediaItemIndex
        if (index + 1 < c.mediaItemCount) c.removeMediaItems(index + 1, c.mediaItemCount)
        if (index > 0) c.removeMediaItems(0, index)
        c.addMediaItems(items(songs.drop(1)))
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        c.play()
        return true
    }

    /** Puts a saved queue back without starting playback. */
    fun restore(songs: List<SongEntity>, index: Int, positionMs: Long) {
        val c = controller ?: return
        if (songs.isEmpty() || c.mediaItemCount > 0) return
        c.setMediaItems(items(songs), index.coerceIn(0, songs.lastIndex), positionMs.coerceAtLeast(0L))
        c.prepare()
    }

    fun playShuffled(songs: List<SongEntity>) {
        if (songs.isEmpty()) return
        val shuffled = songs.shuffled()
        play(shuffled, 0)
        controller?.shuffleModeEnabled = false
    }

    fun playNext(song: SongEntity) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) {
            play(listOf(song))
        } else {
            c.addMediaItem(c.currentMediaItemIndex + 1, MediaItems.toMediaItem(song))
        }
    }

    /** Several songs straight after the current one, in the order given. */
    fun playNext(songs: List<SongEntity>) {
        if (songs.isEmpty()) return
        val c = controller ?: return
        if (c.mediaItemCount == 0) {
            play(songs)
        } else {
            c.addMediaItems(c.currentMediaItemIndex + 1, items(songs))
        }
    }

    fun addToQueue(songs: List<SongEntity>) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) {
            play(songs)
        } else {
            c.addMediaItems(items(songs))
        }
    }

    /** Whether the player is playing, for the screens that step it aside and back. */
    val isPlaying: Boolean get() = controller?.isPlaying == true

    fun pause() {
        controller?.pause()
    }

    fun resume() {
        val c = controller ?: return
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        c.play()
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    fun next() = controller?.seekToNextMediaItem() ?: Unit
    fun previous() {
        val c = controller ?: return
        if (c.currentPosition > 5_000L) c.seekTo(0L) else c.seekToPreviousMediaItem()
    }

    fun seekTo(ms: Long) {
        controller?.seekTo(ms.coerceAtLeast(0L))
        _state.value = _state.value.copy(positionMs = ms)
    }

    /**
     * Playback speed. ExoPlayer leaves the pitch alone when only the speed
     * changes, so a slowed track sounds slower rather than lower - which is the
     * whole point for anyone using this to learn a tune.
     */
    fun speed(): Float = controller?.playbackParameters?.speed ?: 1f

    fun setSpeed(value: Float) {
        controller?.setPlaybackSpeed(value.coerceIn(0.25f, 3.0f))
    }

    /** Relative jump, for the optional skip-forward and skip-back buttons. */
    fun nudge(ms: Long) {
        val c = controller ?: return
        val target = (c.currentPosition + ms).coerceIn(0L, c.duration.coerceAtLeast(0L))
        c.seekTo(target)
        _state.value = _state.value.copy(positionMs = target)
    }

    fun jumpTo(index: Int) {
        val c = controller ?: return
        if (index in 0 until c.mediaItemCount) {
            c.seekTo(index, 0L)
            c.play()
        }
    }

    fun removeAt(index: Int) {
        val c = controller ?: return
        if (index in 0 until c.mediaItemCount) c.removeMediaItem(index)
    }

    /** Reorders the queue in place; whatever is playing keeps playing. */
    fun moveItem(from: Int, to: Int) {
        val c = controller ?: return
        val count = c.mediaItemCount
        if (from == to || from !in 0 until count || to !in 0 until count) return
        c.moveMediaItem(from, to)
    }

    fun toggleShuffle() {
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
    }

    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun stop() {
        val c = controller ?: return
        c.stop()
        c.clearMediaItems()
    }
}
