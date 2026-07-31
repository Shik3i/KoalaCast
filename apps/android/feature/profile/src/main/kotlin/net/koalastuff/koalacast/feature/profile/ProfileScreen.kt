package net.koalastuff.koalacast.feature.profile

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.koalastuff.koalacast.core.ui.component.AccentButton
import net.koalastuff.koalacast.core.ui.component.Hairline
import net.koalastuff.koalacast.core.ui.component.IconButtonSquare
import net.koalastuff.koalacast.core.ui.component.MonoText
import net.koalastuff.koalacast.core.ui.component.OutlineButton
import net.koalastuff.koalacast.core.ui.component.SegmentedControl
import net.koalastuff.koalacast.core.ui.icon.PhosphorIcons
import net.koalastuff.koalacast.core.ui.theme.KoalaShapes
import net.koalastuff.koalacast.core.ui.theme.KoalaSpacing
import net.koalastuff.koalacast.core.ui.theme.KoalaTheme
import net.koalastuff.koalacast.core.ui.util.Format
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun ProfileScreen(
    onOpenPodcast: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenDownloads: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    /** Renders the You/Community switch. The host owns that state. */
    scopeSelector: @Composable () -> Unit = {},
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val export = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) coroutineScope.launch(Dispatchers.IO) { writeExport(context, uri, state) }
    }

    ProfileContent(
        state = state,
        onSetRange = viewModel::setRange,
        onOpenPodcast = onOpenPodcast,
        onOpenSettings = onOpenSettings,
        onOpenAccount = onOpenAccount,
        onOpenDownloads = onOpenDownloads,
        onExport = { export.launch("koalacast-listening-data.json") },
        scopeSelector = scopeSelector,
        modifier = modifier,
        contentPadding = contentPadding,
    )
}

@Composable
internal fun ProfileContent(
    state: ProfileUiState,
    onSetRange: (StatsRange) -> Unit,
    onOpenPodcast: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenDownloads: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    scopeSelector: @Composable () -> Unit = {},
) {
    val colors = KoalaTheme.colors
    val context = LocalContext.current
    val stats = state.stats

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
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(colors.accentFill, KoalaShapes.round),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = state.accountName?.take(2)?.uppercase() ?: "KC",
                    style = KoalaTheme.type.sectionTitle,
                    color = colors.accentOn,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.accountName ?: stringResource(R.string.profile_title),
                    style = KoalaTheme.type.screenTitle,
                    color = colors.inkStrong,
                )
                MonoText(
                    text = profileSummary(state),
                    color = colors.ink4,
                    style = KoalaTheme.type.monoSmall,
                )
            }
            IconButtonSquare(
                icon = PhosphorIcons.Gear,
                contentDescription = stringResource(R.string.profile_settings),
                onClick = onOpenSettings,
            )
        }

        // Signed out, this is the loudest thing on the screen and says what an
        // account is for. Signed in, it collapses back into a plain link.
        if (state.accountName == null) {
            SignInCard(onOpenAccount = onOpenAccount)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall)) {
            OutlineButton(
                text = stringResource(R.string.profile_account),
                onClick = onOpenAccount,
                modifier = Modifier.weight(1f),
            )
            OutlineButton(
                text = stringResource(R.string.profile_downloads),
                onClick = onOpenDownloads,
                modifier = Modifier.weight(1f),
            )
        }

        scopeSelector()

        SegmentedControl(
            options = listOf(
                stringResource(R.string.profile_year),
                stringResource(R.string.profile_90_days),
                stringResource(R.string.profile_all),
            ),
            selectedIndex = StatsRange.entries.indexOf(state.range),
            onSelect = { onSetRange(StatsRange.entries[it]) },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall)) {
            StatCard(
                stringResource(R.string.profile_listened),
                duration(context, stats.totalWallMs),
                Modifier.weight(1f),
            )
            StatCard(
                stringResource(R.string.profile_finished),
                stats.completedCount.toString(),
                Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall)) {
            StatCard(
                stringResource(R.string.profile_streak),
                pluralStringResource(
                    R.plurals.profile_days,
                    stats.longestStreak,
                    stats.longestStreak,
                ),
                Modifier.weight(1f),
            )
            StatCard(
                stringResource(R.string.profile_daily),
                duration(context, state.averagePerActiveDayMs),
                Modifier.weight(1f),
            )
        }

        Hairline()
        SectionTitle(
            stringResource(R.string.profile_activity),
            pluralStringResource(
                R.plurals.profile_active_days,
                stats.activeDays,
                stats.activeDays,
            ),
        )
        Heatmap(stats)

        Hairline()
        SectionTitle(
            stringResource(R.string.profile_most_played),
            stringResource(R.string.profile_by_time),
        )
        if (stats.showTotals.isEmpty()) {
            Text(
                text = stringResource(R.string.profile_no_rankings),
                style = KoalaTheme.type.bodySmall,
                color = colors.ink4,
            )
        } else {
            val maxMs = stats.showTotals.first().listeningMs.coerceAtLeast(1)
            stats.showTotals.forEachIndexed { index, show ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenPodcast(show.id) }
                        .padding(vertical = KoalaSpacing.gapSmall),
                    horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MonoText(
                        text = (index + 1).toString().padStart(2, '0'),
                        color = colors.ink4,
                        style = KoalaTheme.type.monoSmall,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = show.title,
                            style = KoalaTheme.type.listTitle,
                            color = colors.ink2,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Bar(show.listeningMs.toFloat() / maxMs)
                    }
                    MonoText(
                        text = duration(context, show.listeningMs),
                        color = colors.ink3,
                        style = KoalaTheme.type.monoSmall,
                    )
                }
            }
        }

        Breakdown(
            title = stringResource(R.string.profile_weekday),
            labels = weekdayLabels(),
            values = stats.weekdayTotals,
        )
        Breakdown(
            title = stringResource(R.string.profile_hour),
            labels = List(24) { it.toString().padStart(2, '0') },
            values = stats.hourTotals,
            compact = true,
        )

        if (stats.categoryTotals.isNotEmpty()) {
            SectionTitle(stringResource(R.string.profile_categories))
            val maxMs = stats.categoryTotals.maxOf { it.listeningMs }.coerceAtLeast(1)
            stats.categoryTotals.forEach { category ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MonoText(
                        text = category.label,
                        color = colors.ink3,
                        style = KoalaTheme.type.monoSmall,
                        modifier = Modifier.width(100.dp),
                    )
                    Box(modifier = Modifier.weight(1f)) {
                        Bar(category.listeningMs.toFloat() / maxMs)
                    }
                    MonoText(
                        text = if (stats.totalWallMs > 0) {
                            "${category.listeningMs * 100 / stats.totalWallMs}%"
                        } else {
                            "0%"
                        },
                        color = colors.ink4,
                        style = KoalaTheme.type.monoSmall,
                    )
                }
            }
        }

        Hairline()
        SectionTitle(
            stringResource(R.string.profile_time_saved),
            stringResource(R.string.profile_baseline),
        )
        StatCard(
            stringResource(R.string.profile_total_saved),
            duration(context, stats.totalSavedMs),
            Modifier.fillMaxWidth(),
            accent = true,
        )
        SavingRow(stringResource(R.string.profile_speed), stats.speedSavedMs, stats.totalSavedMs)
        SavingRow(stringResource(R.string.profile_silence), stats.silenceSavedMs, stats.totalSavedMs)
        SavingRow(stringResource(R.string.profile_intro), stats.introOutroSkippedMs, stats.totalSavedMs)
        SavingRow(stringResource(R.string.profile_manual), stats.manualSkippedMs, stats.totalSavedMs)
        MonoText(
            text = stringResource(R.string.profile_average_speed, stats.averageSpeed),
            color = colors.ink4,
            style = KoalaTheme.type.monoSmall,
        )

        Hairline()
        SectionTitle(stringResource(R.string.profile_privacy))
        Text(
            text = stringResource(R.string.profile_local_body),
            style = KoalaTheme.type.bodySmall,
            color = colors.ink3,
        )
        OutlineButton(text = stringResource(R.string.profile_export), onClick = onExport)
    }
}

@Composable
private fun SignInCard(onOpenAccount: () -> Unit) {
    val colors = KoalaTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.accentWash, KoalaShapes.card)
            .padding(KoalaSpacing.gap),
        verticalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall),
    ) {
        Text(
            text = stringResource(R.string.profile_sign_in_title),
            style = KoalaTheme.type.sectionTitle,
            color = colors.inkStrong,
        )
        Text(
            text = stringResource(R.string.profile_sign_in_body),
            style = KoalaTheme.type.bodySmall,
            color = colors.ink3,
        )
        AccentButton(
            text = stringResource(R.string.profile_sign_in_action),
            onClick = onOpenAccount,
            leadingIcon = PhosphorIcons.UserCircle,
        )
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier, accent: Boolean = false) {
    val colors = KoalaTheme.colors
    Column(
        modifier = modifier
            .background(if (accent) colors.accentWash else colors.bgSunken, RoundedCornerShape(8.dp))
            .padding(KoalaSpacing.gap),
    ) {
        MonoText(text = label, color = colors.ink4, style = KoalaTheme.type.monoSmall)
        Text(text = value, style = KoalaTheme.type.displaySmall, color = colors.inkStrong)
    }
}

@Composable
private fun SectionTitle(title: String, detail: String = "") {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(text = title, style = KoalaTheme.type.sectionTitle, color = KoalaTheme.colors.inkStrong)
        if (detail.isNotBlank()) {
            MonoText(text = detail, color = KoalaTheme.colors.ink4, style = KoalaTheme.type.monoSmall)
        }
    }
}

@Composable
private fun Heatmap(stats: ListeningAnalytics) {
    val colors = KoalaTheme.colors
    val levels = listOf(
        colors.bgSunken,
        colors.accentWash,
        colors.accentFill.copy(alpha = .45f),
        colors.accentFill.copy(alpha = .72f),
        colors.accentFill,
    )
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        heatmapDays(stats).chunked(7).forEach { week ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                week.forEach { (_, level) ->
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(levels[level], RoundedCornerShape(2.dp)),
                    )
                }
            }
        }
    }
}

@Composable
private fun Breakdown(
    title: String,
    labels: List<String>,
    values: List<Long>,
    compact: Boolean = false,
) {
    SectionTitle(title)
    val max = values.maxOrNull()?.coerceAtLeast(1) ?: 1
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 7.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        values.forEachIndexed { index, value ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .width(if (compact) 10.dp else 32.dp)
                        .height(72.dp)
                        .background(KoalaTheme.colors.bgSunken),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height((72 * value.toFloat() / max).coerceAtLeast(2f).dp)
                            .background(KoalaTheme.colors.accentFill),
                    )
                }
                MonoText(
                    text = labels[index],
                    color = KoalaTheme.colors.ink4,
                    style = KoalaTheme.type.monoSmall,
                )
            }
        }
    }
}

@Composable
private fun SavingRow(label: String, value: Long, total: Long) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = KoalaSpacing.gapSmall),
        horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = KoalaTheme.type.label, color = KoalaTheme.colors.ink2)
            Bar(if (total > 0) value.toFloat() / total else 0f)
        }
        MonoText(
            text = duration(context, value),
            color = KoalaTheme.colors.ink3,
            style = KoalaTheme.type.monoSmall,
        )
    }
}

@Composable
private fun Bar(fraction: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(KoalaTheme.colors.track, RoundedCornerShape(4.dp)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(4.dp)
                .background(KoalaTheme.colors.accentFill, RoundedCornerShape(4.dp)),
        )
    }
}

@Composable
private fun profileSummary(state: ProfileUiState): String {
    val since = state.firstListeningAtMs?.let {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(
            Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()),
        )
    } ?: stringResource(R.string.profile_tracking_starts)
    return listOf(
        since,
        pluralStringResource(
            R.plurals.profile_subscriptions,
            state.subscriptionCount,
            state.subscriptionCount,
        ),
        pluralStringResource(
            R.plurals.profile_shows_heard,
            state.touchedShows,
            state.touchedShows,
        ),
    ).joinToString(" · ")
}

private fun weekdayLabels(): List<String> {
    val formatter = DateTimeFormatter.ofPattern("EEE")
    return (1..7).map { LocalDate.of(2026, 7, 19).plusDays(it.toLong()).format(formatter) }
}

private fun duration(context: Context, ms: Long): String = Format.duration(context, ms)

private fun writeExport(context: Context, uri: Uri, state: ProfileUiState) {
    val sessions = JSONArray()
    state.sessions.forEach { item ->
        sessions.put(
            JSONObject()
                .put("id", item.id)
                .put("episode_id", item.episodeId)
                .put("podcast_id", item.podcastId)
                .put("title", item.title)
                .put("podcast_title", item.podcastTitle)
                .put("categories", JSONArray(item.categories))
                .put("started_at", item.startedAtMs)
                .put("ended_at", item.endedAtMs)
                .put("wall_clock_ms", item.wallClockMs)
                .put("audio_listened_ms", item.audioListenedMs)
                .put("speed_saved_ms", item.speedSavedMs)
                .put("silence_saved_ms", item.silenceSavedMs)
                .put("manual_skipped_ms", item.manualSkippedMs)
                .put("intro_outro_skipped_ms", item.introOutroSkippedMs)
                .put("speed_weighted_ms", item.speedWeightedMs),
        )
    }
    val history = JSONArray()
    state.history.forEach { item ->
        history.put(
            JSONObject()
                .put("episode_id", item.episodeId)
                .put("podcast_id", item.podcastId)
                .put("position_ms", item.positionMs)
                .put("completed", item.completed)
                .put("progress_percent", item.progressPercent)
                .put("last_played_at", item.lastPlayedAtMs),
        )
    }
    val root = JSONObject()
        .put("exportedAt", Instant.now().toString())
        .put("listeningSessions", sessions)
        .put("playbackStates", history)
    context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
        it.write(root.toString(2))
    }
}
