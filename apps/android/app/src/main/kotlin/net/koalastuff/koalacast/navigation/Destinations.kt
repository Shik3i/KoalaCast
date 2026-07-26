package net.koalastuff.koalacast.navigation

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import net.koalastuff.koalacast.R
import net.koalastuff.koalacast.core.ui.icon.PhosphorIcons

/**
 * String routes rather than type-safe ones: the graph is small, and a route that
 * reads the same in code and in a deep link is easier to keep honest.
 */
object Routes {
    const val ONBOARDING = "onboarding"
    const val DISCOVER = "discover"
    const val SEARCH = "search"
    const val SETTINGS = "settings"

    const val PODCAST = "podcast?podcastId={podcastId}&feedUrl={feedUrl}"
    const val EPISODE = "episode/{episodeId}"

    /**
     * A show can be reached with a KoalaCast id (already ingested) or with only a
     * feed URL (straight off a chart or a search result), so both are optional and
     * the screen resolves whichever it was given.
     */
    fun podcast(feedUrl: String, podcastId: String?): String =
        "podcast?podcastId=${Uri.encode(podcastId.orEmpty())}&feedUrl=${Uri.encode(feedUrl)}"

    fun episode(episodeId: String): String = "episode/${Uri.encode(episodeId)}"
}

/** The bottom bar. Library and Profile join it when they have data to show. */
enum class TopLevelDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    DISCOVER(Routes.DISCOVER, R.string.nav_discover, PhosphorIcons.Compass, PhosphorIcons.CompassFill),
    SEARCH(Routes.SEARCH, R.string.nav_search, PhosphorIcons.MagnifyingGlass, PhosphorIcons.MagnifyingGlassFill),
    SETTINGS(Routes.SETTINGS, R.string.nav_settings, PhosphorIcons.Gear, PhosphorIcons.GearFill),
}
