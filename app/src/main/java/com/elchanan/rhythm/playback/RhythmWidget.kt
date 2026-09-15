package com.elchanan.rhythm.playback

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.KeyEvent
import android.widget.RemoteViews
import androidx.media3.common.Player
import com.elchanan.rhythm.MainActivity
import com.elchanan.rhythm.R

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
            val albumId = item?.mediaMetadata?.artworkUri?.lastPathSegment?.toLongOrNull() ?: -1L
            val views = buildViews(context, title, artist, player.isPlaying, albumId)
            for (id in ids) manager.updateAppWidget(id, views)
        }

        private fun buildViews(
            context: Context,
            title: String?,
            artist: String?,
            playing: Boolean,
            albumId: Long
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_player)
            views.setTextViewText(R.id.widget_title, title ?: context.getString(R.string.app_name))
            views.setTextViewText(R.id.widget_artist, artist.orEmpty())
            views.setImageViewResource(
                R.id.widget_play,
                if (playing) R.drawable.ic_pause else R.drawable.ic_play
            )
            if (albumId > 0) {
                views.setImageViewUri(R.id.widget_art, MediaItems.artworkUri(albumId))
            } else {
                views.setImageViewResource(R.id.widget_art, R.drawable.ic_note)
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
