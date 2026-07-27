package net.koalastuff.koalacast.feature.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import net.koalastuff.koalacast.core.model.DownloadState
import net.koalastuff.koalacast.core.model.EpisodeDownload
import net.koalastuff.koalacast.core.ui.R as CoreR
import net.koalastuff.koalacast.core.ui.component.IconButtonSquare
import net.koalastuff.koalacast.core.ui.component.MonoText
import net.koalastuff.koalacast.core.ui.component.OutlineButton
import net.koalastuff.koalacast.core.ui.component.RowSeparator
import net.koalastuff.koalacast.core.ui.icon.PhosphorIcons
import net.koalastuff.koalacast.core.ui.theme.KoalaSpacing
import net.koalastuff.koalacast.core.ui.theme.KoalaTheme

@Composable
fun DownloadsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val colors = KoalaTheme.colors
    LazyColumn(
        modifier = modifier.fillMaxSize().background(colors.bgPanel),
        contentPadding = contentPadding,
    ) {
        item {
            Column {
                Row(
                    modifier = Modifier.padding(KoalaSpacing.gapSmall),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButtonSquare(
                        icon = PhosphorIcons.CaretLeft,
                        contentDescription = stringResource(CoreR.string.action_back),
                        onClick = onBack,
                        bordered = false,
                    )
                    Text(
                        stringResource(R.string.downloads_title),
                        style = KoalaTheme.type.screenTitle,
                        color = colors.inkStrong,
                    )
                }
                if (items.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = KoalaSpacing.screenH),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MonoText(
                            stringResource(
                                R.string.downloads_storage,
                                formatBytes(items.sumOf { it.bytesDownloaded }),
                            ),
                            color = colors.ink4,
                            style = KoalaTheme.type.monoSmall,
                        )
                        OutlineButton(
                            text = stringResource(R.string.downloads_remove_all),
                            onClick = viewModel::removeAll,
                        )
                    }
                }
            }
        }
        if (items.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.downloads_empty),
                    modifier = Modifier.padding(KoalaSpacing.screenH),
                    style = KoalaTheme.type.bodySmall,
                    color = colors.ink4,
                )
            }
        }
        items(items, key = { it.episodeId }) { item ->
            DownloadRow(
                item = item,
                onPrimary = { viewModel.primary(item) },
                onRemove = { viewModel.remove(item) },
            )
            RowSeparator(modifier = Modifier.padding(horizontal = KoalaSpacing.screenH))
        }
    }
}

@Composable
private fun DownloadRow(
    item: EpisodeDownload,
    onPrimary: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = KoalaTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = KoalaSpacing.screenH, vertical = KoalaSpacing.gap),
        verticalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall),
    ) {
        Text(
            item.track.title,
            style = KoalaTheme.type.listTitle,
            color = colors.ink2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        MonoText(
            "${item.track.podcastTitle} · ${stateLabel(item.state)} · " +
                formatBytes(item.bytesDownloaded),
            color = colors.ink4,
            style = KoalaTheme.type.monoSmall,
        )
        if (item.state == DownloadState.DOWNLOADING || item.state == DownloadState.QUEUED) {
            LinearProgressIndicator(
                progress = { item.progressPercent / 100f },
                modifier = Modifier.fillMaxWidth(),
                color = colors.accentFill,
                trackColor = colors.track,
            )
        }
        item.error?.let {
            Text(it, style = KoalaTheme.type.bodySmall, color = colors.ink3)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall)) {
            OutlineButton(
                text = stringResource(
                    when (item.state) {
                        DownloadState.DONE -> R.string.downloads_play
                        DownloadState.DOWNLOADING, DownloadState.QUEUED -> R.string.downloads_pause
                        DownloadState.PAUSED, DownloadState.FAILED -> R.string.downloads_retry
                    },
                ),
                onClick = onPrimary,
            )
            OutlineButton(text = stringResource(R.string.downloads_remove), onClick = onRemove)
        }
    }
}

@Composable
private fun stateLabel(state: DownloadState) = stringResource(
    when (state) {
        DownloadState.QUEUED -> R.string.downloads_queued
        DownloadState.DOWNLOADING -> R.string.downloads_downloading
        DownloadState.PAUSED -> R.string.downloads_paused
        DownloadState.DONE -> R.string.downloads_done
        DownloadState.FAILED -> R.string.downloads_failed
    },
)

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> String.format(Locale.US, "%.1f GB", bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1_024 -> String.format(Locale.US, "%.1f KB", bytes / 1_024.0)
    else -> "$bytes B"
}
