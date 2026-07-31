@file:OptIn(ExperimentalLayoutApi::class)

package net.koalastuff.koalacast.feature.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.koalastuff.koalacast.core.model.DataError
import net.koalastuff.koalacast.core.model.DownloadRetention
import net.koalastuff.koalacast.core.model.DownloadStorage
import net.koalastuff.koalacast.core.model.InboxMode
import net.koalastuff.koalacast.core.model.PaletteId
import net.koalastuff.koalacast.core.model.StartScreen
import net.koalastuff.koalacast.core.model.ThemeMode
import net.koalastuff.koalacast.core.ui.component.AccentButton
import net.koalastuff.koalacast.core.ui.component.Hairline
import net.koalastuff.koalacast.core.ui.component.KoalaChip
import net.koalastuff.koalacast.core.ui.component.KoalaTextField
import net.koalastuff.koalacast.core.ui.component.MonoText
import net.koalastuff.koalacast.core.ui.component.OutlineButton
import net.koalastuff.koalacast.core.ui.component.PhosphorIcon
import net.koalastuff.koalacast.core.ui.component.SegmentedControl
import net.koalastuff.koalacast.core.ui.genre.GENRES
import net.koalastuff.koalacast.core.ui.icon.PhosphorIcons
import net.koalastuff.koalacast.core.ui.language.CONTENT_LANGUAGES
import net.koalastuff.koalacast.core.ui.theme.KoalaShapes
import net.koalastuff.koalacast.core.ui.theme.KoalaSpacing
import net.koalastuff.koalacast.core.ui.theme.KoalaTheme
import net.koalastuff.koalacast.core.ui.theme.koalaColors

@Composable
fun SettingsScreen(
    onOpenAccount: () -> Unit,
    onOpenPrivacy: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val storageTreeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            viewModel.setDownloadStorage(DownloadStorage.SAF, uri.toString())
        }
    }

    SettingsContent(
        state = state,
        onServerDraftChange = viewModel::onServerDraftChange,
        onSaveServer = viewModel::saveServer,
        onThemeModeChange = viewModel::setThemeMode,
        onPaletteChange = viewModel::setPalette,
        onToggleLanguage = viewModel::toggleLanguage,
        onCycleGenre = viewModel::cycleGenre,
        onUnhidePodcast = viewModel::unhidePodcast,
        onDefaultInboxModeChange = viewModel::setDefaultInboxMode,
        onStartScreenChange = viewModel::setStartScreen,
        onProxyImagesChange = viewModel::setProxyImages,
        onOpenAccount = onOpenAccount,
        onOpenPrivacy = onOpenPrivacy,
        onDownloadWifiOnlyChange = viewModel::setDownloadWifiOnly,
        onSkipSilenceChange = viewModel::setSkipSilence,
        onVolumeBoostChange = viewModel::setVolumeBoost,
        onAutoDownloadCountChange = viewModel::setAutoDownloadCount,
        onRetentionChange = viewModel::setDownloadRetention,
        onDownloadConcurrencyChange = viewModel::setDownloadConcurrency,
        onDownloadBudgetMbChange = viewModel::setDownloadBudgetMb,
        onDownloadStorageChange = { storage ->
            if (storage == DownloadStorage.SAF) {
                storageTreeLauncher.launch(null)
            } else {
                viewModel.setDownloadStorage(storage)
            }
        },
        modifier = modifier,
        contentPadding = contentPadding,
    )
}

@Composable
internal fun SettingsContent(
    state: SettingsUiState,
    onServerDraftChange: (String) -> Unit,
    onSaveServer: () -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    onPaletteChange: (PaletteId) -> Unit,
    onToggleLanguage: (String) -> Unit,
    onCycleGenre: (String) -> Unit,
    onUnhidePodcast: (String) -> Unit,
    onDefaultInboxModeChange: (InboxMode) -> Unit,
    onStartScreenChange: (StartScreen) -> Unit,
    onProxyImagesChange: (Boolean) -> Unit,
    onOpenAccount: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onDownloadWifiOnlyChange: (Boolean) -> Unit,
    onSkipSilenceChange: (Boolean) -> Unit,
    onVolumeBoostChange: (Boolean) -> Unit,
    onAutoDownloadCountChange: (Int) -> Unit,
    onRetentionChange: (DownloadRetention) -> Unit,
    onDownloadConcurrencyChange: (Int) -> Unit,
    onDownloadBudgetMbChange: (Int) -> Unit,
    onDownloadStorageChange: (DownloadStorage) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val colors = KoalaTheme.colors
    val prefs = state.preferences

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgPanel)
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = KoalaSpacing.screenH, vertical = KoalaSpacing.sectionV),
        verticalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSection),
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            style = KoalaTheme.type.screenTitle,
            color = colors.inkStrong,
        )

        // First section on the screen, and the only one that reads as an invitation
        // rather than a switch: an account is what turns one device's library into
        // a library, and nobody finds it if it hides under a stats dashboard.
        Section(title = stringResource(R.string.settings_account_title)) {
            Text(
                text = if (state.accountName != null) {
                    stringResource(R.string.settings_account_signed_in, state.accountName)
                } else {
                    stringResource(R.string.settings_account_signed_out_note)
                },
                style = KoalaTheme.type.bodySmall,
                color = colors.ink3,
            )
            if (state.accountName != null) {
                OutlineButton(
                    text = stringResource(R.string.settings_account_manage),
                    onClick = onOpenAccount,
                    leadingIcon = PhosphorIcons.UserCircle,
                )
            } else {
                AccentButton(
                    text = stringResource(R.string.settings_account_sign_in),
                    onClick = onOpenAccount,
                    leadingIcon = PhosphorIcons.UserCircle,
                )
            }
        }

        Hairline()

        Section(title = stringResource(R.string.settings_server_title)) {
            KoalaTextField(
                value = state.serverDraft,
                onValueChange = onServerDraftChange,
                placeholder = stringResource(R.string.settings_server_placeholder),
                leadingIcon = PhosphorIcons.HardDrives,
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
                onImeAction = onSaveServer,
            )
            Text(
                text = stringResource(R.string.settings_server_note),
                style = KoalaTheme.type.bodySmall,
                color = colors.ink3,
            )
            if (state.cleartext) {
                Text(
                    text = stringResource(R.string.settings_server_cleartext),
                    style = KoalaTheme.type.bodySmall,
                    color = colors.ink2,
                )
            }
            state.serverError?.let { error ->
                Text(
                    text = when (error) {
                        is DataError.Http -> stringResource(R.string.settings_server_error_http, error.code)
                        is DataError.Malformed -> stringResource(R.string.settings_server_error_shape)
                        else -> stringResource(R.string.settings_server_error_unreachable)
                    },
                    style = KoalaTheme.type.bodySmall,
                    color = colors.ink2,
                )
            }
            if (state.serverSaved) {
                MonoText(
                    text = stringResource(R.string.settings_server_saved),
                    color = colors.accentInk,
                    style = KoalaTheme.type.monoSmall,
                )
            }
            AccentButton(
                text = if (state.checkingServer) {
                    stringResource(R.string.settings_server_checking)
                } else {
                    stringResource(R.string.settings_server_save)
                },
                onClick = onSaveServer,
                enabled = !state.checkingServer && state.serverDraft.isNotBlank(),
            )
        }

        Hairline()

        Section(title = stringResource(R.string.settings_appearance_title)) {
            val modes = listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK)
            SegmentedControl(
                options = listOf(
                    stringResource(R.string.settings_theme_system),
                    stringResource(R.string.settings_theme_light),
                    stringResource(R.string.settings_theme_dark),
                ),
                selectedIndex = modes.indexOf(prefs?.themeMode ?: ThemeMode.SYSTEM),
                onSelect = { onThemeModeChange(modes[it]) },
            )
            Text(
                text = stringResource(R.string.settings_theme_note),
                style = KoalaTheme.type.bodySmall,
                color = colors.ink3,
            )
        }

        Hairline()

        Section(title = stringResource(R.string.settings_start_screen_title)) {
            Text(
                text = stringResource(R.string.settings_start_screen_note),
                style = KoalaTheme.type.bodySmall,
                color = colors.ink3,
            )
            val screens = StartScreen.entries
            SegmentedControl(
                options = listOf(
                    stringResource(R.string.settings_start_screen_discover),
                    stringResource(R.string.settings_start_screen_inbox),
                    stringResource(R.string.settings_start_screen_library),
                ),
                selectedIndex = screens.indexOf(prefs?.startScreen ?: StartScreen.DEFAULT),
                onSelect = { onStartScreenChange(screens[it]) },
            )
        }

        Hairline()

        Section(title = stringResource(R.string.settings_palette_title)) {
            Text(
                text = stringResource(R.string.settings_palette_note),
                style = KoalaTheme.type.bodySmall,
                color = colors.ink3,
            )
            PalettePicker(
                selected = prefs?.palette ?: PaletteId.DEFAULT,
                onSelect = onPaletteChange,
            )
        }

        Hairline()

        Section(title = stringResource(R.string.settings_languages_title)) {
            Text(
                text = stringResource(R.string.settings_languages_note),
                style = KoalaTheme.type.bodySmall,
                color = colors.ink3,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                CONTENT_LANGUAGES.forEach { language ->
                    KoalaChip(
                        label = language.name,
                        selected = prefs?.languages?.contains(language.code) == true,
                        onClick = { onToggleLanguage(language.code) },
                    )
                }
            }
        }

        Hairline()

        Section(title = stringResource(R.string.settings_interests_title)) {
            Text(
                text = stringResource(R.string.settings_interests_note),
                style = KoalaTheme.type.bodySmall,
                color = colors.ink3,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                GENRES.forEach { genre ->
                    GenrePreferenceChip(
                        label = stringResource(genre.labelRes),
                        state = when (genre.wireName) {
                            in (prefs?.interests ?: emptySet()) -> GenrePreferenceState.PREFERRED
                            in (prefs?.hiddenGenres ?: emptySet()) -> GenrePreferenceState.HIDDEN
                            else -> GenrePreferenceState.NEUTRAL
                        },
                        onClick = { onCycleGenre(genre.wireName) },
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MonoText(
                    text = "♥ ${stringResource(R.string.settings_interests_preferred)}",
                    color = colors.accentInk,
                    style = KoalaTheme.type.monoSmall,
                )
                MonoText(
                    text = "⊘ ${stringResource(R.string.settings_interests_hidden)}",
                    color = colors.ink3,
                    style = KoalaTheme.type.monoSmall,
                )
            }
            if (!prefs?.hiddenPodcasts.isNullOrEmpty()) {
                Text(
                    text = stringResource(R.string.settings_hidden_podcasts_title),
                    style = KoalaTheme.type.label,
                    color = colors.ink2,
                )
                prefs.hiddenPodcasts
                    .sortedBy { it.title.lowercase() }
                    .forEach { podcast ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gap),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = podcast.title,
                                style = KoalaTheme.type.bodySmall,
                                color = colors.ink2,
                                modifier = Modifier.weight(1f),
                            )
                            OutlineButton(
                                text = stringResource(R.string.settings_hidden_podcasts_show),
                                onClick = { onUnhidePodcast(podcast.key) },
                            )
                        }
                    }
            }
        }

        Hairline()

        Section(title = stringResource(R.string.settings_new_podcasts_title)) {
            Text(
                text = stringResource(R.string.settings_new_podcasts_note),
                style = KoalaTheme.type.bodySmall,
                color = colors.ink3,
            )
            val inboxModes = listOf(InboxMode.ALL, InboxMode.LATEST)
            SegmentedControl(
                options = listOf(
                    stringResource(R.string.settings_new_podcasts_all),
                    stringResource(R.string.settings_new_podcasts_latest),
                ),
                selectedIndex = inboxModes.indexOf(
                    prefs?.defaultInboxMode ?: InboxMode.ALL,
                ),
                onSelect = { onDefaultInboxModeChange(inboxModes[it]) },
            )
        }

        Hairline()

        Section(title = stringResource(R.string.settings_audio_title)) {
            SwitchRow(
                title = stringResource(R.string.settings_skip_silence),
                note = stringResource(R.string.settings_skip_silence_note),
                checked = prefs?.skipSilence ?: false,
                onCheckedChange = onSkipSilenceChange,
            )
            SwitchRow(
                title = stringResource(R.string.settings_volume_boost),
                note = stringResource(R.string.settings_volume_boost_note),
                checked = prefs?.volumeBoost ?: false,
                onCheckedChange = onVolumeBoostChange,
            )
        }

        Hairline()

        Section(title = stringResource(R.string.settings_downloads_title)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onDownloadWifiOnlyChange(prefs?.downloadWifiOnly != true)
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_download_wifi),
                        style = KoalaTheme.type.label,
                        color = colors.ink2,
                    )
                    Text(
                        text = stringResource(R.string.settings_download_wifi_note),
                        style = KoalaTheme.type.bodySmall,
                        color = colors.ink3,
                    )
                }
                Switch(
                    checked = prefs?.downloadWifiOnly ?: true,
                    onCheckedChange = onDownloadWifiOnlyChange,
                )
            }
            Text(
                text = stringResource(R.string.settings_download_concurrency),
                style = KoalaTheme.type.label,
                color = colors.ink2,
            )
            val concurrency = listOf(1, 2, 3, 4)
            SegmentedControl(
                options = concurrency.map(Int::toString),
                selectedIndex = concurrency.indexOf(prefs?.downloadConcurrency ?: 2),
                onSelect = { onDownloadConcurrencyChange(concurrency[it]) },
            )
            Text(
                text = stringResource(R.string.settings_download_budget),
                style = KoalaTheme.type.label,
                color = colors.ink2,
            )
            val budgets = listOf(512, 1_024, 2_048, 5_120, 0)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall)) {
                budgets.forEach { budgetMb ->
                    KoalaChip(
                        label = if (budgetMb == 0) {
                            stringResource(R.string.settings_download_budget_unlimited)
                        } else {
                            stringResource(R.string.settings_download_budget_mb, budgetMb)
                        },
                        selected = prefs?.downloadBudgetBytes == budgetMb.toLong() * 1024 * 1024,
                        onClick = { onDownloadBudgetMbChange(budgetMb) },
                    )
                }
            }
            Text(
                text = stringResource(R.string.settings_download_storage),
                style = KoalaTheme.type.label,
                color = colors.ink2,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall)) {
                DownloadStorage.entries.forEach { storage ->
                    KoalaChip(
                        label = stringResource(storage.labelRes()),
                        selected = prefs?.downloadStorage == storage,
                        onClick = { onDownloadStorageChange(storage) },
                    )
                }
            }
            if (prefs?.downloadStorage == DownloadStorage.SAF && prefs.downloadTreeUri.isNotBlank()) {
                MonoText(
                    text = prefs.downloadTreeUri,
                    color = colors.ink4,
                    style = KoalaTheme.type.monoSmall,
                )
            }
        }

        Hairline()

        Section(title = stringResource(R.string.settings_auto_download_title)) {
            Text(
                text = stringResource(R.string.settings_auto_download_note),
                style = KoalaTheme.type.bodySmall,
                color = colors.ink3,
            )
            Text(
                text = stringResource(R.string.settings_auto_download_count),
                style = KoalaTheme.type.label,
                color = colors.ink2,
            )
            val counts = listOf(1, 3, 5, 10)
            SegmentedControl(
                options = counts.map { it.toString() },
                selectedIndex = counts.indexOf(prefs?.autoDownloadCount ?: 3).coerceAtLeast(0),
                onSelect = { onAutoDownloadCountChange(counts[it]) },
            )

            Text(
                text = stringResource(R.string.settings_retention_title),
                style = KoalaTheme.type.label,
                color = colors.ink2,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall)) {
                DownloadRetention.entries.forEach { rule ->
                    KoalaChip(
                        label = stringResource(rule.labelRes()),
                        selected = (prefs?.downloadRetention ?: DownloadRetention.DEFAULT) == rule,
                        onClick = { onRetentionChange(rule) },
                    )
                }
            }
            Text(
                text = stringResource(R.string.settings_retention_note),
                style = KoalaTheme.type.bodySmall,
                color = colors.ink4,
            )
        }

        Hairline()

        Section(title = stringResource(R.string.settings_privacy_title)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onProxyImagesChange(prefs?.proxyImages != true) },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_proxy_images),
                        style = KoalaTheme.type.label,
                        color = colors.ink2,
                    )
                    Text(
                        text = stringResource(R.string.settings_proxy_images_note),
                        style = KoalaTheme.type.bodySmall,
                        color = colors.ink3,
                    )
                }
                Switch(
                    checked = prefs?.proxyImages ?: true,
                    onCheckedChange = onProxyImagesChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = colors.accentOn,
                        checkedTrackColor = if (colors.isDark) colors.accentFill else colors.accentInk,
                        uncheckedThumbColor = colors.ink4,
                        uncheckedTrackColor = colors.bgSunken,
                        uncheckedBorderColor = colors.borderUi,
                    ),
                )
            }
            Text(
                text = stringResource(R.string.settings_audio_never_proxied),
                style = KoalaTheme.type.bodySmall,
                color = colors.ink4,
            )
            AccentButton(
                text = stringResource(R.string.settings_privacy_policy),
                onClick = onOpenPrivacy,
                leadingIcon = PhosphorIcons.ShieldCheck,
            )
        }
    }
}

/**
 * The same nine palettes the web client offers, in the same order. Each row shows
 * the palette in *its own* colours rather than the current theme's, so the choice
 * is made by looking rather than by reading the name.
 */
@Composable
private fun PalettePicker(
    selected: PaletteId,
    onSelect: (PaletteId) -> Unit,
) {
    val current = KoalaTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall)) {
        PaletteId.entries.forEach { palette ->
            val preview = koalaColors(palette, dark = current.isDark)
            val isSelected = palette == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(KoalaShapes.card)
                    .background(preview.bgPanel)
                    .border(
                        width = if (isSelected) 2.dp else 1.dp,
                        // The row's own accent, not the current theme's: only a
                        // palette's own accent is guaranteed to contrast against
                        // the panel colour it is being drawn on.
                        color = if (isSelected) preview.accentInk else preview.borderHair,
                        shape = KoalaShapes.card,
                    )
                    .clickable { onSelect(palette) }
                    .padding(KoalaSpacing.gap),
                horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    listOf(preview.bgApp, preview.bgSunken, preview.accentFill, preview.ink)
                        .forEach { swatch ->
                            Box(
                                modifier = Modifier
                                    .size(18.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(swatch)
                                    .border(1.dp, preview.borderHair, RoundedCornerShape(3.dp)),
                            )
                        }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(palette.labelRes()),
                        style = KoalaTheme.type.listTitle,
                        color = preview.inkStrong,
                    )
                    Text(
                        text = stringResource(palette.descriptionRes()),
                        style = KoalaTheme.type.bodySmall,
                        color = preview.ink3,
                    )
                }
                if (isSelected) {
                    PhosphorIcon(
                        icon = PhosphorIcons.CheckCircleFill,
                        contentDescription = null,
                        tint = preview.accentInk,
                        size = 18.dp,
                    )
                }
            }
        }
    }
}

@StringRes
private fun DownloadRetention.labelRes(): Int = when (this) {
    DownloadRetention.KEEP -> R.string.settings_retention_keep
    DownloadRetention.WHEN_FINISHED -> R.string.settings_retention_finished
    DownloadRetention.AFTER_7_DAYS -> R.string.settings_retention_7
    DownloadRetention.AFTER_14_DAYS -> R.string.settings_retention_14
    DownloadRetention.AFTER_30_DAYS -> R.string.settings_retention_30
}

@StringRes
private fun DownloadStorage.labelRes(): Int = when (this) {
    DownloadStorage.INTERNAL -> R.string.settings_download_storage_internal
    DownloadStorage.EXTERNAL -> R.string.settings_download_storage_external
    DownloadStorage.SAF -> R.string.settings_download_storage_folder
}

@StringRes
private fun PaletteId.labelRes(): Int = when (this) {
    PaletteId.EUCALYPTUS -> R.string.settings_palette_eucalyptus
    PaletteId.FJORD -> R.string.settings_palette_fjord
    PaletteId.EMBER -> R.string.settings_palette_ember
    PaletteId.LAVENDER -> R.string.settings_palette_lavender
    PaletteId.AURORA -> R.string.settings_palette_aurora
    PaletteId.SANDSTONE -> R.string.settings_palette_sandstone
    PaletteId.OBSIDIAN -> R.string.settings_palette_obsidian
    PaletteId.PAPER -> R.string.settings_palette_paper
    PaletteId.ULTRAVIOLET -> R.string.settings_palette_ultraviolet
}

@StringRes
private fun PaletteId.descriptionRes(): Int = when (this) {
    PaletteId.EUCALYPTUS -> R.string.settings_palette_eucalyptus_desc
    PaletteId.FJORD -> R.string.settings_palette_fjord_desc
    PaletteId.EMBER -> R.string.settings_palette_ember_desc
    PaletteId.LAVENDER -> R.string.settings_palette_lavender_desc
    PaletteId.AURORA -> R.string.settings_palette_aurora_desc
    PaletteId.SANDSTONE -> R.string.settings_palette_sandstone_desc
    PaletteId.OBSIDIAN -> R.string.settings_palette_obsidian_desc
    PaletteId.PAPER -> R.string.settings_palette_paper_desc
    PaletteId.ULTRAVIOLET -> R.string.settings_palette_ultraviolet_desc
}

private enum class GenrePreferenceState { NEUTRAL, PREFERRED, HIDDEN }

@Composable
private fun GenrePreferenceChip(
    label: String,
    state: GenrePreferenceState,
    onClick: () -> Unit,
) {
    val colors = KoalaTheme.colors
    val ground = when (state) {
        GenrePreferenceState.NEUTRAL -> colors.bgSunken
        GenrePreferenceState.PREFERRED -> if (colors.isDark) colors.accentFill else colors.accentInk
        GenrePreferenceState.HIDDEN -> colors.bgTransport
    }
    val ink = when (state) {
        GenrePreferenceState.NEUTRAL -> colors.ink3
        GenrePreferenceState.PREFERRED -> if (colors.isDark) colors.accentOn else colors.bgPanel
        GenrePreferenceState.HIDDEN -> colors.ink2
    }
    val prefix = when (state) {
        GenrePreferenceState.NEUTRAL -> ""
        GenrePreferenceState.PREFERRED -> "♥ "
        GenrePreferenceState.HIDDEN -> "⊘ "
    }

    Box(
        modifier = Modifier
            .defaultMinSize(minHeight = KoalaSpacing.minTouchTarget)
            .padding(vertical = 5.dp)
            .clip(KoalaShapes.chip)
            .background(ground)
            .border(
                BorderStroke(
                    1.dp,
                    if (state == GenrePreferenceState.HIDDEN) colors.ink4 else colors.borderUi,
                ),
                KoalaShapes.chip,
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        MonoText(
            text = prefix + label,
            color = ink,
            style = KoalaTheme.type.monoSmall,
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    note: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = KoalaTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = KoalaTheme.type.label, color = colors.ink2)
            Text(text = note, style = KoalaTheme.type.bodySmall, color = colors.ink3)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(KoalaSpacing.gap)) {
        MonoText(
            text = title,
            color = KoalaTheme.colors.ink4,
            style = KoalaTheme.type.monoSmall,
        )
        content()
    }
}
