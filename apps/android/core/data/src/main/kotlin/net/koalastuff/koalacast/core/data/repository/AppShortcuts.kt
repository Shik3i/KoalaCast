package net.koalastuff.koalacast.core.data.repository

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import net.koalastuff.koalacast.core.data.R
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The long-press menu on the launcher icon. Shows the last few things the
 * listener had running, because "get me back into that episode" is the only
 * shortcut worth the space — everything else is one tap away once the app opens.
 *
 * Deliberately not a live subscription: shortcuts are a launcher-side cache, and
 * rewriting them on every position tick would be wasteful. Refreshed when the app
 * starts, which is often enough for something you reach *before* opening the app.
 */
@Singleton
class AppShortcuts @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val progress: ProgressRepository,
) {

    suspend fun refresh() {
        val recent = progress.inProgress.first().take(MAX_SHORTCUTS)
        if (recent.isEmpty()) {
            ShortcutManagerCompat.removeAllDynamicShortcuts(context)
            return
        }

        val shortcuts = recent.mapNotNull { state ->
            val track = state.track ?: return@mapNotNull null
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setClassName(context, MAIN_ACTIVITY)
                putExtra(EXTRA_EPISODE_ID, track.episodeId)
                // A shortcut launches cold, so it must be able to stand alone.
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            ShortcutInfoCompat.Builder(context, "episode-${track.episodeId}")
                .setShortLabel(track.title.take(SHORT_LABEL_MAX))
                .setLongLabel(track.podcastTitle.ifBlank { track.title })
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_play))
                .setIntent(intent)
                .build()
        }

        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }
    }

    private companion object {
        const val MAX_SHORTCUTS = 3
        const val SHORT_LABEL_MAX = 24
        const val MAIN_ACTIVITY = "net.koalastuff.koalacast.MainActivity"
        const val EXTRA_EPISODE_ID = "episodeId"
    }
}
