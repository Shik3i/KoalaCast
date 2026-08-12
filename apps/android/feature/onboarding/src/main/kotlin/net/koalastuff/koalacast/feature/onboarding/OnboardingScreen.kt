package net.koalastuff.koalacast.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.koalastuff.koalacast.core.model.DataError
import net.koalastuff.koalacast.core.data.server.ServerUrl
import net.koalastuff.koalacast.core.ui.component.AccentButton
import net.koalastuff.koalacast.core.ui.component.KoalaChip
import net.koalastuff.koalacast.core.ui.component.KoalaTextField
import net.koalastuff.koalacast.core.ui.component.MonoText
import net.koalastuff.koalacast.core.ui.component.OutlineButton
import net.koalastuff.koalacast.core.ui.component.PhosphorIcon
import net.koalastuff.koalacast.core.ui.icon.PhosphorIcons
import net.koalastuff.koalacast.core.ui.theme.KoalaShapes
import net.koalastuff.koalacast.core.ui.theme.KoalaSpacing
import net.koalastuff.koalacast.core.ui.theme.KoalaTheme

/**
 * First run. Server selection is the whole screen rather than a setting buried three
 * levels down, because self-hosting is a first-class path for this project — the
 * official instance is offered as the one-tap default, not assumed.
 */
/**
 * @param onFinished carries whether the listener asked to reach the account screen
 *   straight away, so the choice made here is not silently dropped.
 */
@Composable
fun OnboardingScreen(
    onFinished: (openAccount: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var wantsAccount by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.finished) {
        if (state.finished) onFinished(wantsAccount)
    }

    OnboardingContent(
        state = state,
        onUrlChange = viewModel::onUrlChange,
        onUseOfficial = viewModel::useOfficialInstance,
        onUseEmulator = viewModel::useEmulatorLoopback,
        onConfirm = { withAccount ->
            wantsAccount = withAccount
            viewModel.confirm()
        },
        modifier = modifier,
    )
}

@Composable
internal fun OnboardingContent(
    state: OnboardingUiState,
    onUrlChange: (String) -> Unit,
    onUseOfficial: () -> Unit,
    onUseEmulator: () -> Unit,
    onConfirm: (withAccount: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KoalaTheme.colors

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgPanel)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = KoalaSpacing.screenH, vertical = KoalaSpacing.gapSection),
        verticalArrangement = Arrangement.spacedBy(KoalaSpacing.gapLarge),
    ) {
        MonoText(
            text = stringResource(R.string.onboarding_eyebrow),
            color = colors.accentInk,
            style = KoalaTheme.type.monoBadge,
        )
        Text(
            text = stringResource(R.string.onboarding_title),
            style = KoalaTheme.type.display,
            color = colors.inkStrong,
        )
        Text(
            text = stringResource(R.string.onboarding_dek),
            style = KoalaTheme.type.body,
            color = colors.ink3,
        )

        Spacer(Modifier.size(KoalaSpacing.gapSmall))

        MonoText(
            text = stringResource(R.string.onboarding_server_label),
            color = colors.ink4,
            style = KoalaTheme.type.monoSmall,
        )
        KoalaTextField(
            value = state.url,
            onValueChange = onUrlChange,
            placeholder = stringResource(R.string.onboarding_server_placeholder),
            leadingIcon = PhosphorIcons.HardDrives,
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Go,
            onImeAction = { onConfirm(false) },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall)) {
            QuickChoice(
                label = stringResource(R.string.onboarding_use_official),
                onClick = onUseOfficial,
            )
            if (state.showEmulatorOption) {
                QuickChoice(
                    label = stringResource(R.string.onboarding_use_emulator),
                    onClick = onUseEmulator,
                )
            }
        }

        if (state.cleartext) {
            Notice(
                text = stringResource(R.string.onboarding_cleartext_warning),
                icon = PhosphorIcons.ShieldCheck,
            )
        }

        state.error?.let { error ->
            Notice(
                text = when (error) {
                    is DataError.Network -> stringResource(R.string.onboarding_error_unreachable)
                    is DataError.Http -> stringResource(R.string.onboarding_error_http, error.code)
                    is DataError.Malformed -> if (error.cause == ServerUrl.CLEARTEXT_REJECTED) {
                        stringResource(R.string.onboarding_error_cleartext)
                    } else {
                        stringResource(R.string.onboarding_error_not_koalacast)
                    }
                    DataError.NoServerConfigured -> stringResource(R.string.onboarding_error_empty)
                },
                icon = PhosphorIcons.WarningCircle,
                emphasised = true,
            )
        }

        // Both ways forward are offered here, side by side. An account that only
        // ever appears three taps deep inside a stats screen may as well not
        // exist for someone opening the app for the first time.
        MonoText(
            text = stringResource(R.string.onboarding_account_label),
            color = colors.ink4,
            style = KoalaTheme.type.monoSmall,
        )
        Text(
            text = stringResource(R.string.onboarding_account_body),
            style = KoalaTheme.type.bodySmall,
            color = colors.ink3,
        )
        AccentButton(
            text = if (state.checking) {
                stringResource(R.string.onboarding_checking)
            } else {
                stringResource(R.string.onboarding_account_continue)
            },
            onClick = { onConfirm(true) },
            enabled = !state.checking && state.url.isNotBlank() && !state.cleartextRejected,
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = PhosphorIcons.UserCircle,
        )
        OutlineButton(
            text = stringResource(R.string.onboarding_continue),
            onClick = { onConfirm(false) },
            enabled = !state.checking && state.url.isNotBlank() && !state.cleartextRejected,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.size(KoalaSpacing.gapSmall))

        PrivacyNote()
    }
}

@Composable
private fun QuickChoice(label: String, onClick: () -> Unit) {
    KoalaChip(
        label = label,
        selected = false,
        onClick = onClick,
    )
}

@Composable
private fun Notice(
    text: String,
    icon: ImageVector,
    emphasised: Boolean = false,
) {
    val colors = KoalaTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(KoalaShapes.card)
            .background(colors.bgSunken)
            .padding(KoalaSpacing.gap),
        horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall),
        verticalAlignment = Alignment.Top,
    ) {
        PhosphorIcon(
            icon = icon,
            contentDescription = null,
            tint = if (emphasised) colors.accentInk else colors.ink4,
            size = 17.dp,
        )
        Text(
            text = text,
            style = KoalaTheme.type.bodySmall,
            color = if (emphasised) colors.ink2 else colors.ink3,
        )
    }
}

@Composable
private fun PrivacyNote() {
    val colors = KoalaTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(KoalaSpacing.gapTiny)) {
        Box(
            modifier = Modifier
                .size(width = 28.dp, height = 1.dp)
                .background(colors.borderHair),
        )
        Spacer(Modifier.size(KoalaSpacing.gapSmall))
        MonoText(
            text = stringResource(R.string.onboarding_privacy_heading),
            color = colors.ink4,
            style = KoalaTheme.type.monoSmall,
        )
        Text(
            text = stringResource(R.string.onboarding_privacy_body),
            style = KoalaTheme.type.bodySmall,
            color = colors.ink3,
        )
    }
}
