package net.koalastuff.koalacast.feature.globalstats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.koalastuff.koalacast.core.model.GlobalStats
import net.koalastuff.koalacast.core.ui.component.EmptyState
import net.koalastuff.koalacast.core.ui.component.Hairline
import net.koalastuff.koalacast.core.ui.component.IconButtonSquare
import net.koalastuff.koalacast.core.ui.component.MonoText
import net.koalastuff.koalacast.core.ui.component.SegmentedControl
import net.koalastuff.koalacast.core.ui.component.SkeletonRows
import net.koalastuff.koalacast.core.ui.icon.PhosphorIcons
import net.koalastuff.koalacast.core.ui.theme.KoalaShapes
import net.koalastuff.koalacast.core.ui.theme.KoalaSpacing
import net.koalastuff.koalacast.core.ui.theme.KoalaTheme
import net.koalastuff.koalacast.core.ui.util.Format
import java.time.LocalDate

@Composable
fun GlobalStatsScreen(
    onOpenPodcast: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onOpenSettings: () -> Unit = {},
    scopeSelector: @Composable () -> Unit = {},
    viewModel: GlobalStatsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    GlobalStatsContent(
        state = state,
        onSetRange = viewModel::setRange,
        onRetry = viewModel::retry,
        onOpenPodcast = onOpenPodcast,
        onOpenSettings = onOpenSettings,
        scopeSelector = scopeSelector,
        modifier = modifier,
        contentPadding = contentPadding,
    )
}

@Composable
internal fun GlobalStatsContent(
    state: GlobalStatsUiState,
    onSetRange: (GlobalRange) -> Unit,
    onRetry: () -> Unit,
    onOpenPodcast: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onOpenSettings: () -> Unit = {},
    scopeSelector: @Composable () -> Unit = {},
) {
    val colors = KoalaTheme.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgPanel)
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = KoalaSpacing.screenH, vertical = KoalaSpacing.sectionV),
        verticalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSection),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                MonoText(
                    text = "KOALACAST COMMUNITY",
                    color = colors.accentInk,
                    style = KoalaTheme.type.monoStrong,
                )
                Text(
                    text = stringResource(R.string.global_title),
                    style = KoalaTheme.type.screenTitle,
                    color = colors.inkStrong,
                )
            }
            // Settings stays one tap away from either scope, so switching to
            // Community is never a detour on the way to the gear.
            IconButtonSquare(
                icon = PhosphorIcons.Gear,
                contentDescription = stringResource(R.string.global_settings),
                onClick = onOpenSettings,
            )
        }
        Text(
            text = stringResource(R.string.global_subtitle),
            style = KoalaTheme.type.bodySmall,
            color = colors.ink3,
        )

        scopeSelector()

        SegmentedControl(
            options = listOf(
                stringResource(R.string.global_90),
                stringResource(R.string.global_year),
                stringResource(R.string.global_all),
            ),
            selectedIndex = GlobalRange.entries.indexOf(state.range),
            onSelect = { onSetRange(GlobalRange.entries[it]) },
        )

        when {
            state.loading -> SkeletonRows(count = 7)
            state.error != null -> EmptyState(
                title = stringResource(R.string.global_error_title),
                body = stringResource(R.string.global_error_body),
                icon = PhosphorIcons.WarningCircle,
                actionLabel = stringResource(R.string.global_retry),
                onAction = onRetry,
            )
            state.stats != null -> Stats(state.stats, onOpenPodcast)
        }
    }
}

@Composable
private fun Stats(stats: GlobalStats, onOpenPodcast: (String) -> Unit) {
    val context = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall)) {
        Kpi(stringResource(R.string.global_listening), Format.duration(context, stats.totalWallMs), Modifier.weight(1f))
        Kpi(stringResource(R.string.global_participants), stats.participants.toString(), Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall)) {
        Kpi(stringResource(R.string.global_podcasts), stats.podcasts.toString(), Modifier.weight(1f))
        Kpi(stringResource(R.string.global_speed), "%.2f×".format(stats.averageSpeed), Modifier.weight(1f))
    }
    MonoText(
        text = stringResource(
            R.string.global_summary,
            stats.listeningSessions,
            stats.episodes,
            stats.activeDays,
        ),
        color = KoalaTheme.colors.ink4,
        style = KoalaTheme.type.monoSmall,
    )

    Hairline()
    Heading(stringResource(R.string.global_activity))
    CommunityHeatmap(stats)
    BarChart(stringResource(R.string.global_weekday), weekdayLabels(), stats.weekdayTotals)
    BarChart(
        stringResource(R.string.global_hour),
        List(24) { it.toString().padStart(2, '0') },
        stats.hourTotals,
        compact = true,
    )

    if (stats.categoryTotals.isNotEmpty()) {
        Heading(stringResource(R.string.global_categories))
        val max = stats.categoryTotals.maxOf { it.ms }.coerceAtLeast(1)
        stats.categoryTotals.forEach {
            LabeledBar(it.label, it.ms.toFloat() / max, Format.duration(context, it.ms))
        }
    }

    Heading(stringResource(R.string.global_saved))
    LabeledBar(stringResource(R.string.global_saved_speed), part(stats.speedSavedMs, stats.totalSavedMs), Format.duration(context, stats.speedSavedMs))
    LabeledBar(stringResource(R.string.global_saved_silence), part(stats.silenceSavedMs, stats.totalSavedMs), Format.duration(context, stats.silenceSavedMs))
    LabeledBar(stringResource(R.string.global_saved_manual), part(stats.manualSkippedMs, stats.totalSavedMs), Format.duration(context, stats.manualSkippedMs))
    LabeledBar(stringResource(R.string.global_saved_intro), part(stats.introOutroSkippedMs, stats.totalSavedMs), Format.duration(context, stats.introOutroSkippedMs))

    Heading(stringResource(R.string.global_top_podcasts))
    stats.podcastRankings.take(25).forEach {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenPodcast(it.id) }
                .padding(vertical = KoalaSpacing.gapSmall),
            horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MonoText(it.rank.toString().padStart(2, '0'), color = KoalaTheme.colors.ink4, style = KoalaTheme.type.monoSmall)
            Text(
                it.title,
                modifier = Modifier.weight(1f),
                style = KoalaTheme.type.label,
                color = KoalaTheme.colors.ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            MonoText(Format.duration(context, it.ms), color = KoalaTheme.colors.ink3, style = KoalaTheme.type.monoSmall)
        }
    }

    Heading(stringResource(R.string.global_listeners))
    stats.listenerLeaderboard.take(50).forEach {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = KoalaSpacing.gapSmall),
            horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall),
        ) {
            MonoText(it.rank.toString().padStart(2, '0'), color = KoalaTheme.colors.ink4, style = KoalaTheme.type.monoSmall)
            Text(it.username, modifier = Modifier.weight(1f), style = KoalaTheme.type.label, color = KoalaTheme.colors.ink2)
            MonoText(Format.duration(context, it.ms), color = KoalaTheme.colors.ink3, style = KoalaTheme.type.monoSmall)
        }
    }

    Hairline()
    Text(
        text = stringResource(R.string.global_privacy),
        style = KoalaTheme.type.bodySmall,
        color = KoalaTheme.colors.ink3,
    )
}

@Composable
private fun Kpi(label: String, value: String, modifier: Modifier) {
    Column(
        modifier = modifier.background(KoalaTheme.colors.bgSunken, KoalaShapes.card).padding(KoalaSpacing.gap),
    ) {
        MonoText(label, color = KoalaTheme.colors.ink4, style = KoalaTheme.type.monoSmall)
        Text(value, style = KoalaTheme.type.displaySmall, color = KoalaTheme.colors.inkStrong)
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = KoalaTheme.type.sectionTitle, color = KoalaTheme.colors.inkStrong)
}

@Composable
private fun CommunityHeatmap(stats: GlobalStats) {
    val values = stats.dayTotals.associate { it.date to it.ms }
    val max = values.values.maxOrNull()?.coerceAtLeast(1) ?: 1
    val today = LocalDate.now(java.time.ZoneOffset.UTC)
    val levels = listOf(
        KoalaTheme.colors.bgSunken,
        KoalaTheme.colors.accentWash,
        KoalaTheme.colors.accentFill.copy(alpha = .45f),
        KoalaTheme.colors.accentFill.copy(alpha = .72f),
        KoalaTheme.colors.accentFill,
    )
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        (181L downTo 0L).map { today.minusDays(it) }.chunked(7).forEach { week ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                week.forEach { date ->
                    val value = values[date.toString()] ?: 0
                    val ratio = value.toFloat() / max
                    val level = when {
                        value == 0L -> 0
                        ratio < .15f -> 1
                        ratio < .35f -> 2
                        ratio < .65f -> 3
                        else -> 4
                    }
                    Box(Modifier.size(10.dp).background(levels[level], RoundedCornerShape(2.dp)))
                }
            }
        }
    }
}

@Composable
private fun BarChart(title: String, labels: List<String>, values: List<Long>, compact: Boolean = false) {
    Heading(title)
    val max = values.maxOrNull()?.coerceAtLeast(1) ?: 1
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 7.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        values.forEachIndexed { index, value ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.width(if (compact) 10.dp else 32.dp).height(72.dp)
                        .background(KoalaTheme.colors.bgSunken),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(
                        Modifier.fillMaxWidth()
                            .height((72 * value.toFloat() / max).coerceAtLeast(2f).dp)
                            .background(KoalaTheme.colors.accentFill),
                    )
                }
                MonoText(labels.getOrElse(index) { "" }, color = KoalaTheme.colors.ink4, style = KoalaTheme.type.monoSmall)
            }
        }
    }
}

@Composable
private fun LabeledBar(label: String, fraction: Float, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = KoalaSpacing.gapSmall),
        horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MonoText(label, modifier = Modifier.width(100.dp), color = KoalaTheme.colors.ink3, style = KoalaTheme.type.monoSmall)
        Box(
            modifier = Modifier.weight(1f).height(5.dp)
                .background(KoalaTheme.colors.track, RoundedCornerShape(5.dp)),
        ) {
            Box(
                Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(5.dp)
                    .background(KoalaTheme.colors.accentFill, RoundedCornerShape(5.dp)),
            )
        }
        MonoText(value, color = KoalaTheme.colors.ink4, style = KoalaTheme.type.monoSmall)
    }
}

private fun part(value: Long, total: Long) = if (total > 0) value.toFloat() / total else 0f

private fun weekdayLabels(): List<String> {
    val formatter = java.time.format.DateTimeFormatter.ofPattern("EEE")
    val sunday = LocalDate.of(2026, 7, 26)
    return List(7) { sunday.plusDays(it.toLong()).format(formatter) }
}
