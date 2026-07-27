@file:OptIn(ExperimentalLayoutApi::class)

package net.koalastuff.koalacast.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.koalastuff.koalacast.core.model.DataError
import net.koalastuff.koalacast.core.model.ThemeMode
import net.koalastuff.koalacast.core.ui.component.AccentButton
import net.koalastuff.koalacast.core.ui.component.Hairline
import net.koalastuff.koalacast.core.ui.component.KoalaChip
import net.koalastuff.koalacast.core.ui.component.KoalaTextField
import net.koalastuff.koalacast.core.ui.component.MonoText
import net.koalastuff.koalacast.core.ui.component.SegmentedControl
import net.koalastuff.koalacast.core.ui.genre.GENRES
import net.koalastuff.koalacast.core.ui.icon.PhosphorIcons
import net.koalastuff.koalacast.core.ui.language.CONTENT_LANGUAGES
import net.koalastuff.koalacast.core.ui.theme.KoalaSpacing
import net.koalastuff.koalacast.core.ui.theme.KoalaTheme
import net.koalastuff.koalacast.core.ui.R as CoreR

@Composable
fun SettingsScreen(
    onOpenPrivacy: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    SettingsContent(
        state = state,
        onServerDraftChange = viewModel::onServerDraftChange,
        onSaveServer = viewModel::saveServer,
        onThemeModeChange = viewModel::setThemeMode,
        onToggleLanguage = viewModel::toggleLanguage,
        onSelectCategory = viewModel::setCategory,
        onProxyImagesChange = viewModel::setProxyImages,
        onOpenPrivacy = onOpenPrivacy,
        onDownloadWifiOnlyChange = viewModel::setDownloadWifiOnly,
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
    onToggleLanguage: (String) -> Unit,
    onSelectCategory: (String) -> Unit,
    onProxyImagesChange: (Boolean) -> Unit,
    onOpenPrivacy: () -> Unit,
    onDownloadWifiOnlyChange: (Boolean) -> Unit,
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

        Section(title = stringResource(R.string.settings_category_title)) {
            Text(
                text = stringResource(R.string.settings_category_note),
                style = KoalaTheme.type.bodySmall,
                color = colors.ink3,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                KoalaChip(
                    label = stringResource(CoreR.string.genre_all),
                    selected = prefs?.category.isNullOrBlank(),
                    onClick = { onSelectCategory("") },
                )
                GENRES.forEach { genre ->
                    KoalaChip(
                        label = stringResource(genre.labelRes),
                        selected = prefs?.category == genre.wireName,
                        onClick = { onSelectCategory(genre.wireName) },
                    )
                }
            }
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
