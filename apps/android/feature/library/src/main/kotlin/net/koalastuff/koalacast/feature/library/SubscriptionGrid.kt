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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import net.koalastuff.koalacast.core.ui.component.AccentButton
import net.koalastuff.koalacast.core.ui.component.EmptyState
import net.koalastuff.koalacast.core.ui.component.OutlineButton
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
    onSetFolder: (String, String) -> Unit,
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

    var pendingSubscription by remember { mutableStateOf<Subscription?>(null) }
    var folderDraft by remember { mutableStateOf("") }
    var selectedFolder by remember { mutableStateOf("") }
    val folders = subscriptions.map { it.folder }.filter { it.isNotBlank() }.distinct().sorted()
    val visibleSubscriptions = if (selectedFolder.isBlank()) {
        subscriptions
    } else {
        subscriptions.filter { it.folder == selectedFolder }
    }

    Column(modifier = modifier) {
        if (folders.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = KoalaSpacing.screenH),
                horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall),
            ) {
                item {
                    FilterChip(
                        selected = selectedFolder.isBlank(),
                        onClick = { selectedFolder = "" },
                        label = { Text(stringResource(R.string.library_all_folders)) },
                    )
                }
                items(folders.size) { index ->
                    val folder = folders[index]
                    FilterChip(
                        selected = selectedFolder == folder,
                        onClick = { selectedFolder = folder },
                        label = { Text(folder) },
                    )
                }
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(KoalaSpacing.screenH),
            horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gap),
            verticalArrangement = Arrangement.spacedBy(KoalaSpacing.gapLarge),
        ) {
            items(items = visibleSubscriptions, key = { it.podcastId }) { subscription ->
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
                                onLongClick = {
                                    pendingSubscription = subscription
                                    folderDraft = subscription.folder
                                },
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
    }

    pendingSubscription?.let { subscription ->
        AlertDialog(
            onDismissRequest = { pendingSubscription = null },
            containerColor = KoalaTheme.colors.bgSunken,
            title = {
                Text(
                    text = stringResource(R.string.library_manage_title, subscription.title),
                    style = KoalaTheme.type.sectionTitle,
                    color = KoalaTheme.colors.inkStrong,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(KoalaSpacing.gap)) {
                    OutlinedTextField(
                        value = folderDraft,
                        onValueChange = { folderDraft = it },
                        label = { Text(stringResource(R.string.library_folder_name)) },
                        supportingText = { Text(stringResource(R.string.library_folder_hint)) },
                        singleLine = true,
                    )
                    OutlineButton(
                        text = stringResource(R.string.library_unsubscribe_confirm),
                        onClick = {
                            onUnsubscribe(subscription.podcastId)
                            pendingSubscription = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                AccentButton(
                    text = stringResource(R.string.library_folder_save),
                    onClick = {
                        onSetFolder(subscription.podcastId, folderDraft)
                        pendingSubscription = null
                    },
                )
            },
            dismissButton = {
                OutlineButton(
                    text = stringResource(R.string.library_cancel),
                    onClick = { pendingSubscription = null },
                )
            },
        )
    }
}
