package net.koalastuff.koalacast.feature.inbox

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import net.koalastuff.koalacast.core.model.InboxMode
import net.koalastuff.koalacast.core.model.Subscription
import net.koalastuff.koalacast.core.ui.theme.KoalaCastTheme
import org.junit.Rule
import org.junit.Test

class InboxContentTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun filterControlsAreVisibleForSubscriptions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
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
                    onToggleSettings = {},
                    onSetUnplayedOnly = {},
                    onSetDownloadedOnly = {},
                    onSetPodcastFilter = {},
                    onSetDateRange = {},
                    onSetMood = {},
                    onSetSessionMinutes = {},
                    onQueueSession = {},
                    onSetMode = { _, _ -> },
                    onOpenEpisode = {},
                    onOpenDiscover = {},
                    onPlay = {},
                    onQueue = {},
                    onTogglePlayed = {},
                    onMarkAllPlayed = {},
                    onMarkOlder = { _, _ -> },
                )
            }
        }

        compose.onNodeWithText(context.getString(R.string.inbox_downloaded)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inbox_all_shows)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inbox_date_all)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.inbox_mood_all)).assertIsDisplayed()
    }
}
