package net.koalastuff.koalacast

import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeGesturesPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.remember
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import net.koalastuff.koalacast.core.model.StartScreen
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
import net.koalastuff.koalacast.navigation.route
import net.koalastuff.koalacast.navigation.StatsScope
import net.koalastuff.koalacast.navigation.TopLevelDestination

/**
 * @param onboardingComplete null while DataStore is still being read; showing either
 *   graph before it is known would flash the wrong screen.
 * @param startScreen the tab a cold start lands on, from Settings.
 */
@Composable
fun KoalaCastApp(
    onboardingComplete: Boolean?,
    startScreen: StartScreen = StartScreen.DEFAULT,
    insecureServerResetPending: Boolean = false,
    onInsecureServerResetAcknowledged: () -> Unit = {},
    requestedEpisodeId: String? = null,
    onEpisodeRequestConsumed: () -> Unit = {},
    navController: NavHostController = rememberNavController(),
) {
    val colors = KoalaTheme.colors

    if (insecureServerResetPending) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.insecure_server_reset_title)) },
            text = { Text(stringResource(R.string.insecure_server_reset_body)) },
            confirmButton = {
                TextButton(onClick = onInsecureServerResetAcknowledged) {
                    Text(stringResource(R.string.insecure_server_reset_confirm))
                }
            },
        )
    }

    if (onboardingComplete == null) {
        Box(modifier = Modifier.fillMaxSize().background(colors.bgApp))
        return
    }

    // Read once. Changing the preference must not tear down the live back stack —
    // it decides where the *next* cold start lands, not where this session goes.
    val homeRoute = remember { startScreen.route() }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    // The tab row stays put on every screen in the graph. It used to appear only
    // on the four top-level routes, so opening a show or Settings took the app's
    // primary navigation away and left Back as the only way out — the listener
    // had to unwind wherever they were before they could go anywhere else.
    val showBottomBar = currentRoute != null && currentRoute != Routes.ONBOARDING
    // Which tab a detail screen belongs to. A podcast opened from Library is
    // still Library; nothing is gained by unhighlighting the row the moment you
    // use it, and an unlit tab row reads as broken.
    var selectedTab by rememberSaveable { mutableStateOf(homeRoute) }
    LaunchedEffect(currentRoute) {
        if (TopLevelDestination.entries.any { it.route == currentRoute }) {
            selectedTab = currentRoute!!
        }
    }
    // The expanded player is a layer over the graph, not a destination: the back
    // stack underneath must survive collapsing it.
    var nowPlayingExpanded by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(onboardingComplete, requestedEpisodeId) {
        val episodeId = requestedEpisodeId?.takeIf(String::isNotBlank) ?: return@LaunchedEffect
        if (!onboardingComplete) return@LaunchedEffect
        navController.navigate(Routes.episode(episodeId)) { launchSingleTop = true }
        onEpisodeRequestConsumed()
    }

    Column(modifier = Modifier.fillMaxSize().background(colors.bgApp)) {
        Box(modifier = Modifier.weight(1f)) {
            NavHost(
                navController = navController,
                startDestination = if (onboardingComplete) homeRoute else Routes.ONBOARDING,
                modifier = Modifier.fillMaxSize(),
            ) {
                composable(Routes.ONBOARDING) {
                    OnboardingScreen(
                        onFinished = { openAccount ->
                            navController.navigate(homeRoute) {
                                popUpTo(Routes.ONBOARDING) { inclusive = true }
                            }
                            if (openAccount) navController.navigate(Routes.ACCOUNT)
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
                        onBack = { navController.popBackStack() },
                        onOpenAccount = { navController.navigate(Routes.ACCOUNT) },
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
                        onOpenSettings = { navController.navigate(Routes.SETTINGS) },
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
                        onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                        contentPadding = statusBarPadding(),
                    )
                }
            }
        }

        if (currentRoute != Routes.ONBOARDING) {
            MiniPlayer(
                onExpand = { nowPlayingExpanded = true },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                modifier = if (showBottomBar) Modifier else Modifier.safeGesturesPadding(),
            )
        }

        if (showBottomBar) {
            KoalaBottomBar(
                currentRoute = selectedTab,
                onSelect = { destination ->
                    selectedTab = destination.route
                    navController.navigate(destination.route) {
                        // Land on the tab itself, every time.
                        //
                        // This used to save and restore each tab's stack, which is
                        // the usual pattern and was harmless while the bar only
                        // existed on the four tab roots. Now that it is on every
                        // screen, a restored stack can end in a detail screen —
                        // pressing "Discover" from Settings reopened whichever
                        // episode was last read, and pressing "Profile" appeared to
                        // do nothing because it restored Settings. A bar that is
                        // always on screen has to be a way out, so it pops to the
                        // root instead of resuming.
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = false
                        }
                        launchSingleTop = true
                        restoreState = false
                    }
                },
            )
        }
    }

    if (nowPlayingExpanded) {
        // Registered here rather than beside the state it reads, and that placement
        // is the whole fix: the dispatcher invokes the most recently added enabled
        // callback, and NavHost adds its own. Declared before the NavHost, this lost
        // every race — back popped the graph underneath while the player stayed up.
        BackHandler { nowPlayingExpanded = false }

        // The player is opaque, so it must also be solid to touch. A background
        // colour only paints; it registers no pointer input, and Compose hit-tests
        // straight past it into the NavHost underneath. Every part of the player
        // that is not itself a control — the artwork, the title, the empty space
        // around the transport — was therefore a window onto whatever screen the
        // listener happened to leave open, and tapping it operated that screen
        // blind. Opening the player from Settings and pressing near the artwork
        // changed the visualiser style behind it.
        //
        // This node covers the whole overlay and simply consumes what reaches it.
        // Hit testing walks siblings in reverse draw order and stops at the first
        // subtree it hits, so the layer below is never reached; the player's own
        // controls are descendants of this node and keep working as before.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial)
                        }
                    }
                },
        ) {
            NowPlayingScreen(
                onCollapse = { nowPlayingExpanded = false },
                onOpenEpisode = { episodeId ->
                    nowPlayingExpanded = false
                    navController.navigate(Routes.episode(episodeId))
                },
                onOpenSettings = {
                    nowPlayingExpanded = false
                    navController.navigate(Routes.SETTINGS)
                },
            )
        }
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
                .padding(vertical = KoalaSpacing.gapSmall)
                .selectableGroup(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TopLevelDestination.entries.forEach { destination ->
                val selected = currentRoute == destination.route
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = KoalaSpacing.minTouchTarget)
                        .selectable(
                            selected = selected,
                            role = Role.Tab,
                            onClick = { onSelect(destination) },
                        )
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
