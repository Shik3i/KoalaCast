package net.koalastuff.koalacast.navigation

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import net.koalastuff.koalacast.R
import net.koalastuff.koalacast.core.model.StartScreen
import net.koalastuff.koalacast.core.ui.icon.PhosphorIcons

/**
 * String routes rather than type-safe ones: the graph is small, and a route that
 * reads the same in code and in a deep link is easier to keep honest.
 */
object Routes {
    const val ONBOARDING = "onboarding"
    const val DISCOVER = "discover"
    const val SEARCH = "search"
    const val INBOX = "inbox"
    const val LIBRARY = "library"
    const val PROFILE = "profile"
    const val ACCOUNT = "account"
    const val SETTINGS = "settings"
    const val PRIVACY = "privacy"
    const val DOWNLOADS = "downloads"

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

/** The stored start-screen choice, resolved against the graph's actual routes. */
fun StartScreen.route(): String = when (this) {
    StartScreen.DISCOVER -> Routes.DISCOVER
    StartScreen.INBOX -> Routes.INBOX
    StartScreen.LIBRARY -> Routes.LIBRARY
}

/**
 * The bottom bar. Four and no more: at the narrowest supported width a fifth
 * label has nowhere to go and starts truncating. Community listening figures
 * are therefore a scope *within* Profile rather than a destination beside it —
 * the two screens render the same dashboard, only aggregated differently.
 */
enum class TopLevelDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    DISCOVER(Routes.DISCOVER, R.string.nav_discover, PhosphorIcons.Compass, PhosphorIcons.CompassFill),
    INBOX(Routes.INBOX, R.string.nav_inbox, PhosphorIcons.Tray, PhosphorIcons.TrayFill),
    LIBRARY(Routes.LIBRARY, R.string.nav_library, PhosphorIcons.Books, PhosphorIcons.BooksFill),
    PROFILE(Routes.PROFILE, R.string.nav_profile, PhosphorIcons.UserCircle, PhosphorIcons.UserCircleFill),
}
