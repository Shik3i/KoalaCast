package net.koalastuff.koalacast.feature.inbox

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import net.koalastuff.koalacast.core.model.InboxMode
import net.koalastuff.koalacast.core.model.Subscription
import net.koalastuff.koalacast.core.ui.theme.KoalaCastTheme
import org.junit.Rule
import org.junit.Test

class InboxContentTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun setContent(onToggleSettings: () -> Unit = {}) {
        compose.setContent {
            KoalaCastTheme {
                InboxContent(
                    state = InboxUiState(
                        loading = false,
                        subscriptions = listOf(
                            Subscription(
                                podcastId = "show",
                                feedUrl = "https://example.com/feed.xml",
                                title = "Example show",
                                artworkUrl = "",
                                addedAtMs = 0,
                                inboxMode = InboxMode.ALL,
                            ),
                        ),
                    ),
                    onRefresh = {},
                    onToggleSettings = onToggleSettings,
                    onSetDownloadedOnly = {},
                    onSetPodcastFilter = {},
                    onSetDateRange = {},
                    onSetMood = {},
                    onSetHideSpecials = {},
                    onSetSessionMinutes = {},
                    onQueueSession = {},
                    onSetMode = { _, _ -> },
                    onTogglePriority = {},
                    onOpenEpisode = {},
                    onOpenDiscover = {},
                    onPlay = {},
                    onQueue = {},
                    onDownload = {},
                    onTogglePlayed = {},
                    onMarkAllPlayed = {},
                    onMarkOlder = { _, _ -> },
                )
            }
        }
    }

    /**
     * The toolbar has to fit without scrolling sideways, so both of its controls
     * must be on screen at once rather than one of them living past the edge.
     */
    @Test
    fun toolbarShowsFiltersAndOverflowWithoutScrolling() {
        setContent()

        compose.onNodeWithText(context.getString(R.string.inbox_filters)).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.inbox_more_options))
            .assertIsDisplayed()
    }

    /** The filter panel is collapsed until asked for, then shows every dimension. */
    @Test
    fun filterControlsAppearOnceFiltersAreExpanded() {
        setContent()

        compose.onNodeWithText(context.getString(R.string.inbox_filters)).performClick()

        compose.onNodeWithText(context.getString(R.string.inbox_downloaded)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inbox_all_shows)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inbox_date_all)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inbox_mood_all)).assertIsDisplayed()
    }

    /**
     * Per-show settings moved behind the overflow, so the entry has to survive
     * there — a menu nothing can reach is the same as a deleted feature.
     */
    @Test
    fun showSettingsIsReachableFromTheOverflowMenu() {
        var toggled = false
        setContent(onToggleSettings = { toggled = true })

        compose.onNodeWithContentDescription(context.getString(R.string.inbox_more_options))
            .performClick()
        compose.onNodeWithText(context.getString(R.string.inbox_settings)).performClick()

        assert(toggled) { "Show settings did not fire from the overflow menu" }
    }
}
