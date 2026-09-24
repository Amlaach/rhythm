package com.elchanan.rhythm.playback

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.view.KeyEvent
import android.widget.RemoteViews
import androidx.media3.common.Player
import com.elchanan.rhythm.MainActivity
import com.elchanan.rhythm.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Home screen widget. Buttons are delivered to the playback service as plain
 * media button intents, which [PlaybackService] already understands through
 * MediaSession - so the widget needs no binder connection of its own.
 */
class RhythmWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (id in appWidgetIds) {
            appWidgetManager.updateAppWidget(id, buildViews(context, null, null, false, -1L))
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_PREV -> sendKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            ACTION_PLAY -> sendKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            ACTION_NEXT -> sendKey(context, KeyEvent.KEYCODE_MEDIA_NEXT)
            else -> super.onReceive(context, intent)
        }
    }

    private fun sendKey(context: Context, keyCode: Int) {
        val intent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
            component = ComponentName(context, PlaybackService::class.java)
            putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        }
        runCatching { context.startService(intent) }
    }

    companion object {
        const val ACTION_PREV = "com.elchanan.rhythm.widget.PREV"
        const val ACTION_PLAY = "com.elchanan.rhythm.widget.PLAY"
        const val ACTION_NEXT = "com.elchanan.rhythm.widget.NEXT"

        /** Called by the service whenever the current item or state changes. */
        fun refresh(context: Context, player: Player) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, RhythmWidget::class.java))
            if (ids.isEmpty()) return
            val item = player.currentMediaItem
            val title = item?.mediaMetadata?.title?.toString()
            val artist = item?.mediaMetadata?.artist?.toString()
            val songId = item?.mediaId?.toLongOrNull() ?: -1L
            val views = buildViews(context, title, artist, player.isPlaying, songId)
            for (id in ids) manager.updateAppWidget(id, views)

            // The cover is read off the track itself, which means decoding, so it
            // arrives in a second pass rather than holding up the text and the
            // transport. Album art alone showed one picture for a whole library
            // of singles, exactly as it did in the app before this was fixed.
            if (songId > 0 && artCache[songId] == null) {
                scope.launch {
                    val art = runCatching { loadArt(context, songId) }.getOrNull() ?: return@launch
                    artCache[songId] = art
                    if (artCache.size > 24) {
                        artCache.keys.firstOrNull { it != songId }?.let { artCache.remove(it) }
                    }
                    val refreshed = buildViews(context, title, artist, player.isPlaying, songId)
                    for (id in ids) manager.updateAppWidget(id, refreshed)
                }
            }
        }

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Small, because RemoteViews bitmaps cross a process boundary. */
        private const val ART_PX = 192
        private val artCache = java.util.concurrent.ConcurrentHashMap<Long, Bitmap>()

        private fun loadArt(context: Context, songId: Long): Bitmap? {
            val retriever = MediaMetadataRetriever()
            val bytes = try {
                retriever.setDataSource(context, MediaItems.songUri(songId))
                retriever.embeddedPicture
            } catch (e: Exception) {
                null
            } finally {
                runCatching { retriever.release() }
            } ?: return null

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / sample > ART_PX * 2) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
            // A video thumbnail's padding off first, so the square below is
            // the sleeve and not a slice of it with the bars' edges in.
            val full = com.elchanan.rhythm.ui.components.trimPadding(decoded)
            // Square crop from the centre: these covers are video thumbnails and
            // letterboxing them inside the widget wastes most of the tile.
            val side = minOf(full.width, full.height)
            val cropped = Bitmap.createBitmap(
                full,
                (full.width - side) / 2,
                (full.height - side) / 2,
                side,
                side
            )
            val scaled = Bitmap.createScaledBitmap(cropped, ART_PX, ART_PX, true)
            if (cropped != full) full.recycle()
            return scaled
        }

        private fun buildViews(
            context: Context,
            title: String?,
            artist: String?,
            playing: Boolean,
            songId: Long
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_player)
            views.setTextViewText(R.id.widget_title, title ?: context.getString(R.string.app_name))
            views.setTextViewText(R.id.widget_artist, artist.orEmpty())
            views.setImageViewResource(
                R.id.widget_play,
                if (playing) R.drawable.ic_pause else R.drawable.ic_play
            )
            val art = artCache[songId]
            when {
                art != null -> views.setImageViewBitmap(R.id.widget_art, art)
                songId > 0 -> views.setImageViewResource(R.id.widget_art, R.drawable.ic_note)
                else -> views.setImageViewResource(R.id.widget_art, R.drawable.ic_note)
            }

            views.setOnClickPendingIntent(R.id.widget_prev, command(context, ACTION_PREV, 1))
            views.setOnClickPendingIntent(R.id.widget_play, command(context, ACTION_PLAY, 2))
            views.setOnClickPendingIntent(R.id.widget_next, command(context, ACTION_NEXT, 3))

            val open = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            views.setOnClickPendingIntent(
                R.id.widget_title,
                PendingIntent.getActivity(context, 0, open, flags())
            )
            return views
        }

        private fun command(context: Context, action: String, requestCode: Int): PendingIntent {
            val intent = Intent(context, RhythmWidget::class.java).setAction(action)
            return PendingIntent.getBroadcast(context, requestCode, intent, flags())
        }

        private fun flags(): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
    }
}
