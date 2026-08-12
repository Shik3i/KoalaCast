package net.koalastuff.koalacast.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import net.koalastuff.koalacast.MainActivity
import net.koalastuff.koalacast.R
import net.koalastuff.koalacast.core.player.PlaybackService
import java.util.UUID

class KoalaCastWidgetProvider : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        // An AppWidgetProvider has to be exported for the system's own
        // APPWIDGET_UPDATE, which means any installed app can also send it our two
        // private actions. Both therefore carry the token that only this app's own
        // storage holds. A missing token means no widget has ever been rendered,
        // and then there is nothing to refresh either.
        if (intent.getStringExtra(EXTRA_TOGGLE_TOKEN) != storedToggleToken(context)) return
        when (intent.action) {
            ACTION_TOGGLE -> togglePlayback(context)
            ACTION_STATE_CHANGED -> updateAll(context)
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    private fun updateAppWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_koalacast)
        val state = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val title = state.getString(KEY_TITLE, null)
        val podcast = state.getString(KEY_PODCAST, null)
        val playing = state.getBoolean(KEY_PLAYING, false)
        views.setTextViewText(R.id.widget_track_title, title ?: context.getString(R.string.app_name))
        views.setTextViewText(
            R.id.widget_podcast_title,
            podcast ?: context.getString(R.string.widget_ready),
        )
        views.setImageViewResource(
            R.id.widget_play_pause,
            if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
        )

        // Tapping widget opens main app
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.widget_container, pendingIntent)
        views.setOnClickPendingIntent(
            R.id.widget_play_pause,
            PendingIntent.getBroadcast(
                context,
                1,
                Intent(context, KoalaCastWidgetProvider::class.java)
                    .setAction(ACTION_TOGGLE)
                    .putExtra(EXTRA_TOGGLE_TOKEN, toggleToken(context)),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }

    private fun togglePlayback(context: Context) {
        val future = MediaController.Builder(
            context,
            SessionToken(context, ComponentName(context, PlaybackService::class.java)),
        ).buildAsync()
        future.addListener(
            {
                runCatching {
                    future.get().let { controller ->
                        if (controller.isPlaying) controller.pause() else controller.play()
                        controller.release()
                    }
                }
            },
            MoreExecutors.directExecutor(),
        )
    }

    private fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        onUpdate(
            context,
            manager,
            manager.getAppWidgetIds(ComponentName(context, KoalaCastWidgetProvider::class.java)),
        )
    }

    private fun toggleToken(context: Context): String {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        preferences.getString(KEY_TOGGLE_TOKEN, null)?.let { return it }
        val token = UUID.randomUUID().toString()
        // apply() updates this process' in-memory SharedPreferences immediately;
        // the service is not assigned to a separate Android process.
        preferences.edit().putString(KEY_TOGGLE_TOKEN, token).apply()
        return token
    }

    /** The stored token, or null when no widget has been placed yet. */
    private fun storedToggleToken(context: Context): String? =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(KEY_TOGGLE_TOKEN, null)

    companion object {
        const val ACTION_STATE_CHANGED = "net.koalastuff.koalacast.WIDGET_STATE_CHANGED"
        private const val ACTION_TOGGLE = "net.koalastuff.koalacast.WIDGET_TOGGLE"
        const val EXTRA_TOGGLE_TOKEN = "toggle_token"
        const val PREFERENCES = "playback_widget"
        const val KEY_TITLE = "title"
        const val KEY_PODCAST = "podcast"
        const val KEY_PLAYING = "playing"
        private const val KEY_TOGGLE_TOKEN = "toggle_token"
    }
}
