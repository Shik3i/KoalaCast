package net.koalastuff.koalacast.feature.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.koalastuff.koalacast.core.model.Subscription
import net.koalastuff.koalacast.core.ui.component.CoverArt
import net.koalastuff.koalacast.core.ui.component.EmptyState
import net.koalastuff.koalacast.core.ui.icon.PhosphorIcons
import net.koalastuff.koalacast.core.ui.theme.KoalaShapes
import net.koalastuff.koalacast.core.ui.theme.KoalaSpacing
import net.koalastuff.koalacast.core.ui.theme.KoalaTheme

/**
 * Covers only, three across, as the design specifies — the artwork carries the
 * recognition and captions would just be noise. A long press offers the one
 * destructive action, behind a confirmation.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SubscriptionGrid(
    subscriptions: List<Subscription>,
    onOpen: (feedUrl: String, podcastId: String?) -> Unit,
    onUnsubscribe: (String) -> Unit,
    onOpenDiscover: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (subscriptions.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.library_empty_subscriptions_title),
            body = stringResource(R.string.library_empty_subscriptions_body),
            modifier = modifier,
            icon = PhosphorIcons.Books,
            actionLabel = stringResource(R.string.library_empty_action_discover),
            onAction = onOpenDiscover,
        )
        return
    }

    var pendingUnsubscribe by remember { mutableStateOf<Subscription?>(null) }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = modifier,
        contentPadding = PaddingValues(KoalaSpacing.screenH),
        horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gap),
        verticalArrangement = Arrangement.spacedBy(KoalaSpacing.gapLarge),
    ) {
        items(items = subscriptions, key = { it.podcastId }) { subscription ->
            Column(verticalArrangement = Arrangement.spacedBy(KoalaSpacing.gapTiny)) {
                CoverArt(
                    url = subscription.artworkUrl,
                    contentDescription = subscription.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(KoalaShapes.cover)
                        .combinedClickable(
                            onClick = { onOpen(subscription.feedUrl, subscription.podcastId) },
                            onLongClick = { pendingUnsubscribe = subscription },
                        ),
                    sizeHint = 160.dp,
                )
                Text(
                    text = subscription.title,
                    style = KoalaTheme.type.listTitle,
                    color = KoalaTheme.colors.ink2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    pendingUnsubscribe?.let { subscription ->
        AlertDialog(
            onDismissRequest = { pendingUnsubscribe = null },
            containerColor = KoalaTheme.colors.bgSunken,
            title = {
                Text(
                    text = stringResource(R.string.library_unsubscribe_title, subscription.title),
                    style = KoalaTheme.type.sectionTitle,
                    color = KoalaTheme.colors.inkStrong,
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.library_unsubscribe_body),
                    style = KoalaTheme.type.bodySmall,
                    color = KoalaTheme.colors.ink3,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onUnsubscribe(subscription.podcastId)
                    pendingUnsubscribe = null
                }) {
                    Text(
                        text = stringResource(R.string.library_unsubscribe_confirm),
                        color = KoalaTheme.colors.accentInk,
                        style = KoalaTheme.type.label,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingUnsubscribe = null }) {
                    Text(
                        text = stringResource(R.string.library_cancel),
                        color = KoalaTheme.colors.ink3,
                        style = KoalaTheme.type.label,
                    )
                }
            },
        )
    }
}
