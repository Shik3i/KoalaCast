package net.koalastuff.koalacast

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import net.koalastuff.koalacast.core.ui.component.MonoText
import net.koalastuff.koalacast.core.ui.component.SegmentedControl
import net.koalastuff.koalacast.core.ui.component.PhosphorIcon
import net.koalastuff.koalacast.core.ui.theme.KoalaSpacing
import net.koalastuff.koalacast.core.ui.theme.KoalaTheme
import net.koalastuff.koalacast.feature.discover.DiscoverScreen
import net.koalastuff.koalacast.feature.downloads.DownloadsScreen
import net.koalastuff.koalacast.feature.episode.EpisodeScreen
import net.koalastuff.koalacast.feature.episode.EpisodeViewModel
import net.koalastuff.koalacast.feature.library.LibraryScreen
import net.koalastuff.koalacast.feature.inbox.InboxScreen
import net.koalastuff.koalacast.feature.onboarding.OnboardingScreen
import net.koalastuff.koalacast.feature.player.MiniPlayer
import net.koalastuff.koalacast.feature.player.NowPlayingScreen
import net.koalastuff.koalacast.feature.profile.ProfileScreen
import net.koalastuff.koalacast.feature.account.AccountScreen
import net.koalastuff.koalacast.feature.globalstats.GlobalStatsScreen
import net.koalastuff.koalacast.feature.podcast.PodcastScreen
import net.koalastuff.koalacast.feature.podcast.PodcastViewModel
import net.koalastuff.koalacast.feature.search.SearchScreen
import net.koalastuff.koalacast.feature.settings.SettingsScreen
import net.koalastuff.koalacast.feature.settings.PrivacyScreen
import net.koalastuff.koalacast.navigation.Routes
import net.koalastuff.koalacast.navigation.StatsScope
import net.koalastuff.koalacast.navigation.TopLevelDestination

/**
 * @param onboardingComplete null while DataStore is still being read; showing either
 *   graph before it is known would flash the wrong screen.
 */
@Composable
fun KoalaCastApp(
    onboardingComplete: Boolean?,
    navController: NavHostController = rememberNavController(),
) {
    val colors = KoalaTheme.colors

    if (onboardingComplete == null) {
        Box(modifier = Modifier.fillMaxSize().background(colors.bgApp))
        return
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = TopLevelDestination.entries.any { it.route == currentRoute }
    // The expanded player is a layer over the graph, not a destination: the back
    // stack underneath must survive collapsing it.
    var nowPlayingExpanded by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = nowPlayingExpanded) { nowPlayingExpanded = false }

    Column(modifier = Modifier.fillMaxSize().background(colors.bgApp)) {
        Box(modifier = Modifier.weight(1f)) {
            NavHost(
                navController = navController,
                startDestination = if (onboardingComplete) Routes.DISCOVER else Routes.ONBOARDING,
                modifier = Modifier.fillMaxSize(),
            ) {
                composable(Routes.ONBOARDING) {
                    OnboardingScreen(
                        onFinished = {
                            navController.navigate(Routes.DISCOVER) {
                                popUpTo(Routes.ONBOARDING) { inclusive = true }
                            }
                        },
                    )
                }

                composable(Routes.DISCOVER) {
                    DiscoverScreen(
                        onOpenSearch = { navController.navigate(Routes.SEARCH) },
                        onOpenPodcast = { feedUrl, id ->
                            navController.navigate(Routes.podcast(feedUrl, id))
                        },
                        onOpenEpisode = { navController.navigate(Routes.episode(it)) },
                        contentPadding = statusBarPadding(),
                    )
                }

                composable(Routes.SEARCH) {
                    SearchScreen(
                        onBack = { navController.popBackStack() },
                        onOpenPodcast = { feedUrl, id ->
                            navController.navigate(Routes.podcast(feedUrl, id))
                        },
                        contentPadding = statusBarPadding(),
                    )
                }

                composable(Routes.LIBRARY) {
                    LibraryScreen(
                        onOpenPodcast = { feedUrl, id ->
                            navController.navigate(Routes.podcast(feedUrl, id))
                        },
                        onOpenEpisode = { navController.navigate(Routes.episode(it)) },
                        onOpenDiscover = {
                            navController.navigate(Routes.DISCOVER) {
                                popUpTo(Routes.DISCOVER) { inclusive = true }
                            }
                        },
                        contentPadding = statusBarPadding(),
                    )
                }

                composable(Routes.INBOX) {
                    InboxScreen(
                        onOpenEpisode = { navController.navigate(Routes.episode(it)) },
                        onOpenDiscover = { navController.navigate(Routes.SEARCH) },
                        contentPadding = statusBarPadding(),
                    )
                }

                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        onOpenPrivacy = { navController.navigate(Routes.PRIVACY) },
                        contentPadding = statusBarPadding(),
                    )
                }

                composable(Routes.PRIVACY) {
                    PrivacyScreen(
                        onBack = { navController.popBackStack() },
                        contentPadding = statusBarPadding(),
                    )
                }

                composable(Routes.DOWNLOADS) {
                    DownloadsScreen(
                        onBack = { navController.popBackStack() },
                        contentPadding = statusBarPadding(),
                    )
                }

                composable(Routes.PROFILE) {
                    // Personal and community figures are the same dashboard over a
                    // different population, so they share a destination and differ
                    // by scope rather than costing a fifth tab.
                    var scope by rememberSaveable { mutableStateOf(StatsScope.YOU) }
                    val selector: @Composable () -> Unit = {
                        SegmentedControl(
                            options = listOf(
                                stringResource(R.string.profile_scope_me),
                                stringResource(R.string.profile_scope_community),
                            ),
                            selectedIndex = StatsScope.entries.indexOf(scope),
                            onSelect = { scope = StatsScope.entries[it] },
                        )
                    }
                    val openPodcast: (String) -> Unit = { podcastId ->
                        navController.navigate(Routes.podcast("", podcastId))
                    }
                    val openSettings = { navController.navigate(Routes.SETTINGS) }

                    when (scope) {
                        StatsScope.YOU -> ProfileScreen(
                            onOpenPodcast = openPodcast,
                            onOpenSettings = openSettings,
                            onOpenAccount = { navController.navigate(Routes.ACCOUNT) },
                            onOpenDownloads = { navController.navigate(Routes.DOWNLOADS) },
                            contentPadding = statusBarPadding(),
                            scopeSelector = selector,
                        )
                        StatsScope.COMMUNITY -> GlobalStatsScreen(
                            onOpenPodcast = openPodcast,
                            onOpenSettings = openSettings,
                            contentPadding = statusBarPadding(),
                            scopeSelector = selector,
                        )
                    }
                }

                composable(Routes.ACCOUNT) {
                    AccountScreen(
                        onBack = { navController.popBackStack() },
                        contentPadding = statusBarPadding(),
                    )
                }

                composable(
                    route = Routes.PODCAST,
                    arguments = listOf(
                        navArgument(PodcastViewModel.ARG_PODCAST_ID) {
                            type = NavType.StringType
                            defaultValue = ""
                        },
                        navArgument(PodcastViewModel.ARG_FEED_URL) {
                            type = NavType.StringType
                            defaultValue = ""
                        },
                    ),
                ) {
                    PodcastScreen(
                        onBack = { navController.popBackStack() },
                        onOpenEpisode = { navController.navigate(Routes.episode(it)) },
                        contentPadding = statusBarPadding(),
                    )
                }

                composable(
                    route = Routes.EPISODE,
                    arguments = listOf(
                        navArgument(EpisodeViewModel.ARG_EPISODE_ID) { type = NavType.StringType },
                    ),
                ) {
                    EpisodeScreen(
                        onBack = { navController.popBackStack() },
                        contentPadding = statusBarPadding(),
                    )
                }
            }
        }

        if (currentRoute != Routes.ONBOARDING) {
            MiniPlayer(onExpand = { nowPlayingExpanded = true })
        }

        if (showBottomBar) {
            KoalaBottomBar(
                currentRoute = currentRoute,
                onSelect = { destination ->
                    navController.navigate(destination.route) {
                        popUpTo(Routes.DISCOVER) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
            )
        }
    }

    if (nowPlayingExpanded) {
        NowPlayingScreen(
            onCollapse = { nowPlayingExpanded = false },
            onOpenEpisode = { episodeId ->
                nowPlayingExpanded = false
                navController.navigate(Routes.episode(episodeId))
            },
        )
    }
}

@Composable
private fun statusBarPadding(): PaddingValues =
    WindowInsets.statusBars.asPaddingValues()

/**
 * The transport bar's place in the design is taken by the mini player once playback
 * exists; until then the tab row sits alone on `bg-transport` with the same 1px
 * `border-ui` top edge the mock specifies.
 */
@Composable
private fun KoalaBottomBar(
    currentRoute: String?,
    onSelect: (TopLevelDestination) -> Unit,
) {
    val colors = KoalaTheme.colors

    Column(modifier = Modifier.fillMaxWidth().background(colors.bgTransport)) {
        // border-ui, not a decorative hairline: the transport's top edge is an
        // informational boundary and the handoff holds it at 3:1.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.borderUi),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(vertical = KoalaSpacing.gapSmall),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TopLevelDestination.entries.forEach { destination ->
                val selected = currentRoute == destination.route
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(role = Role.Tab) { onSelect(destination) }
                        .padding(vertical = KoalaSpacing.gapSmall),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(KoalaSpacing.gapTiny),
                ) {
                    PhosphorIcon(
                        icon = if (selected) destination.selectedIcon else destination.icon,
                        contentDescription = null,
                        tint = if (selected) colors.accentInk else colors.ink4,
                        size = 20.dp,
                    )
                    MonoText(
                        text = stringResource(destination.labelRes),
                        color = if (selected) colors.accentInk else colors.ink4,
                        style = KoalaTheme.type.monoSmall,
                    )
                }
            }
        }
    }
}
