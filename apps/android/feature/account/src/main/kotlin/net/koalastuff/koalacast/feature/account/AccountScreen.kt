package net.koalastuff.koalacast.feature.account

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.koalastuff.koalacast.core.model.AccountSession
import net.koalastuff.koalacast.core.model.DataError
import net.koalastuff.koalacast.core.model.SyncStatus
import net.koalastuff.koalacast.core.ui.component.AccentButton
import net.koalastuff.koalacast.core.ui.component.ConfirmDialog
import net.koalastuff.koalacast.core.ui.component.Hairline
import net.koalastuff.koalacast.core.ui.component.IconButtonSquare
import net.koalastuff.koalacast.core.ui.component.KoalaTextField
import net.koalastuff.koalacast.core.ui.component.MonoText
import net.koalastuff.koalacast.core.ui.component.OutlineButton
import net.koalastuff.koalacast.core.ui.component.SegmentedControl
import net.koalastuff.koalacast.core.ui.icon.PhosphorIcons
import net.koalastuff.koalacast.core.ui.theme.KoalaShapes
import net.koalastuff.koalacast.core.ui.theme.KoalaSpacing
import net.koalastuff.koalacast.core.ui.theme.KoalaTheme
import java.text.DateFormat
import java.util.Date

@Composable
fun AccountScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    viewModel: AccountViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            viewModel.beginOpmlImport()
            coroutineScope.launch {
                runCatching {
                    withContext(Dispatchers.IO) { readLimitedText(context, it) }
                }
                    .onSuccess(viewModel::importOpml)
                    .onFailure(viewModel::importFailed)
            }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/xml"),
    ) { uri ->
        val xml = state.opmlExport
        if (uri != null && xml != null) {
            coroutineScope.launch {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                        it.write(xml)
                    }
                }
                viewModel.consumeOpmlExport()
            }
        } else {
            viewModel.consumeOpmlExport()
        }
    }
    LaunchedEffect(state.opmlExport) {
        if (state.opmlExport != null) exportLauncher.launch("koalacast_subscriptions.opml")
    }

    AccountContent(
        state = state,
        onBack = onBack,
        onUsername = viewModel::setUsername,
        onPassword = viewModel::setPassword,
        onRecoveryCode = viewModel::setRecoveryCode,
        onNewPassword = viewModel::setNewPassword,
        onRegister = viewModel::register,
        onLogin = viewModel::login,
        onRecover = viewModel::recover,
        onRecoverySaved = viewModel::confirmRecoveryCodeSaved,
        onLogout = viewModel::logout,
        onSync = viewModel::syncNow,
        onRevoke = viewModel::revokeSession,
        onGlobalStats = viewModel::setGlobalStats,
        onImport = {
            importLauncher.launch(
                arrayOf("text/xml", "application/xml", "text/x-opml", "application/opml+xml", "text/plain", "*/*"),
            )
        },
        onExport = viewModel::prepareOpmlExport,
        modifier = modifier,
        contentPadding = contentPadding,
    )
}

@Composable
internal fun AccountContent(
    state: AccountUiState,
    onBack: () -> Unit,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    onRecoveryCode: (String) -> Unit,
    onNewPassword: (String) -> Unit,
    onRegister: () -> Unit,
    onLogin: () -> Unit,
    onRecover: () -> Unit,
    onRecoverySaved: () -> Unit,
    onLogout: () -> Unit,
    onSync: () -> Unit,
    onRevoke: (String) -> Unit,
    onGlobalStats: (Boolean) -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(KoalaTheme.colors.bgPanel)
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = KoalaSpacing.screenH, vertical = KoalaSpacing.sectionV),
        verticalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSection),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButtonSquare(
                icon = PhosphorIcons.CaretLeft,
                contentDescription = stringResource(R.string.account_back),
                onClick = onBack,
                bordered = false,
            )
            Text(
                text = stringResource(R.string.account_title),
                style = KoalaTheme.type.screenTitle,
                color = KoalaTheme.colors.inkStrong,
            )
        }

        state.error?.takeUnless { it.isOpmlError() }?.let {
            Text(
                text = errorMessage(it),
                style = KoalaTheme.type.bodySmall,
                color = KoalaTheme.colors.ink2,
            )
        }
        state.notice?.let {
            MonoText(
                text = noticeMessage(it),
                color = KoalaTheme.colors.accentInk,
                style = KoalaTheme.type.monoStrong,
            )
        }

        if (state.recoveryCodeDisplay != null) {
            RecoveryCodeContent(
                code = state.recoveryCodeDisplay,
                onConfirmed = onRecoverySaved,
            )
        } else if (state.account == null) {
            SignedOutContent(
                state = state,
                onUsername = onUsername,
                onPassword = onPassword,
                onRecoveryCode = onRecoveryCode,
                onNewPassword = onNewPassword,
                onRegister = onRegister,
                onLogin = onLogin,
                onRecover = onRecover,
            )
        } else {
            SignedInContent(
                state = state,
                onLogout = onLogout,
                onSync = onSync,
                onRevoke = onRevoke,
                onGlobalStats = onGlobalStats,
            )
        }

        if (state.recoveryCodeDisplay == null) {
            Hairline()
            Section(stringResource(R.string.account_opml)) {
            Text(
                text = stringResource(R.string.account_opml_body),
                style = KoalaTheme.type.bodySmall,
                color = KoalaTheme.colors.ink3,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall)) {
                OutlineButton(
                    text = stringResource(R.string.account_opml_import),
                    onClick = onImport,
                    enabled = !state.busy,
                )
                OutlineButton(
                    text = stringResource(R.string.account_opml_export),
                    onClick = onExport,
                    enabled = !state.busy,
                )
            }
            if (state.opmlImporting) {
                MonoText(
                    text = stringResource(R.string.account_opml_importing),
                    color = KoalaTheme.colors.accentInk,
                    style = KoalaTheme.type.monoSmall,
                )
            }
            state.error?.takeIf { it.isOpmlError() }?.let {
                Text(
                    text = errorMessage(it),
                    style = KoalaTheme.type.bodySmall,
                    color = KoalaTheme.colors.ink2,
                )
            }
            state.opmlReport?.let { report ->
                MonoText(
                    text = listOf(
                        pluralStringResource(
                            R.plurals.account_opml_imported,
                            report.imported,
                            report.imported,
                        ),
                        pluralStringResource(
                            R.plurals.account_opml_skipped,
                            report.skipped,
                            report.skipped,
                        ),
                        pluralStringResource(
                            R.plurals.account_opml_found,
                            report.totalFound,
                            report.totalFound,
                        ),
                    ).joinToString(" · "),
                    color = KoalaTheme.colors.accentInk,
                    style = KoalaTheme.type.monoSmall,
                )
                report.failures.take(5).forEach {
                    Text(text = it, style = KoalaTheme.type.bodySmall, color = KoalaTheme.colors.ink4)
                }
            }
            }
        }
    }
}

@Composable
private fun SignedOutContent(
    state: AccountUiState,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    onRecoveryCode: (String) -> Unit,
    onNewPassword: (String) -> Unit,
    onRegister: () -> Unit,
    onLogin: () -> Unit,
    onRecover: () -> Unit,
) {
    var mode by remember { mutableIntStateOf(0) }
    SegmentedControl(
        options = listOf(
            stringResource(R.string.account_login),
            stringResource(R.string.account_register),
            stringResource(R.string.account_recover),
        ),
        selectedIndex = mode,
        onSelect = { mode = it },
    )
    MonoText(
        text = stringResource(R.string.account_username),
        color = KoalaTheme.colors.ink4,
        style = KoalaTheme.type.monoSmall,
    )
    KoalaTextField(
        value = state.username,
        onValueChange = onUsername,
        placeholder = stringResource(R.string.account_username),
        leadingIcon = PhosphorIcons.UserCircle,
        imeAction = ImeAction.Next,
    )
    if (mode == 2) {
        MonoText(
            text = stringResource(R.string.account_recovery_code),
            color = KoalaTheme.colors.ink4,
            style = KoalaTheme.type.monoSmall,
        )
        KoalaTextField(
            value = state.recoveryCodeInput,
            onValueChange = onRecoveryCode,
            placeholder = stringResource(R.string.account_recovery_code),
            leadingIcon = PhosphorIcons.ShieldCheck,
            imeAction = ImeAction.Next,
        )
        MonoText(
            text = stringResource(R.string.account_new_password),
            color = KoalaTheme.colors.ink4,
            style = KoalaTheme.type.monoSmall,
        )
        KoalaTextField(
            value = state.newPassword,
            onValueChange = onNewPassword,
            placeholder = stringResource(R.string.account_new_password),
            leadingIcon = PhosphorIcons.ShieldCheck,
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
            visualTransformation = PasswordVisualTransformation(),
            onImeAction = onRecover,
        )
        AccentButton(
            text = stringResource(R.string.account_reset_password),
            onClick = onRecover,
            enabled = !state.busy && state.username.isNotBlank() &&
                state.recoveryCodeInput.isNotBlank() && state.newPassword.length >= 8,
        )
    } else {
        MonoText(
            text = stringResource(R.string.account_password),
            color = KoalaTheme.colors.ink4,
            style = KoalaTheme.type.monoSmall,
        )
        KoalaTextField(
            value = state.password,
            onValueChange = onPassword,
            placeholder = stringResource(R.string.account_password),
            leadingIcon = PhosphorIcons.ShieldCheck,
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
            visualTransformation = PasswordVisualTransformation(),
            onImeAction = if (mode == 0) onLogin else onRegister,
        )
        AccentButton(
            text = stringResource(
                if (mode == 0) R.string.account_sign_in else R.string.account_create,
            ),
            onClick = if (mode == 0) onLogin else onRegister,
            enabled = !state.busy && state.username.length >= 3 && state.password.length >= 8,
        )
    }
}

@Composable
private fun RecoveryCodeContent(
    code: String,
    onConfirmed: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(KoalaTheme.colors.accentWash, KoalaShapes.card)
            .padding(KoalaSpacing.gap),
        verticalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall),
    ) {
        Text(
            text = stringResource(R.string.account_save_recovery),
            style = KoalaTheme.type.label,
            color = KoalaTheme.colors.inkStrong,
        )
        SelectionContainer {
            MonoText(text = code, color = KoalaTheme.colors.accentInk, style = KoalaTheme.type.monoStrong)
        }
        OutlineButton(
            text = stringResource(R.string.account_copy),
            onClick = {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText("KoalaCast recovery code", code))
            },
        )
        AccentButton(
            text = stringResource(R.string.account_recovery_saved),
            onClick = onConfirmed,
        )
    }
}

@Composable
private fun SignedInContent(
    state: AccountUiState,
    onLogout: () -> Unit,
    onSync: () -> Unit,
    onRevoke: (String) -> Unit,
    onGlobalStats: (Boolean) -> Unit,
) {
    val account = checkNotNull(state.account)
    // Signing out clears this device's copy of the account's data, and anything
    // not yet pushed goes with it. Never one tap away.
    var confirmSignOut by remember { mutableStateOf(false) }

    if (confirmSignOut) {
        ConfirmDialog(
            title = stringResource(R.string.account_sign_out_title),
            body = stringResource(R.string.account_sign_out_body, account.username),
            confirmLabel = stringResource(R.string.account_sign_out),
            onConfirm = {
                confirmSignOut = false
                onLogout()
            },
            onDismiss = { confirmSignOut = false },
        )
    }

    Section(account.username) {
        MonoText(
            text = account.userId,
            color = KoalaTheme.colors.ink4,
            style = KoalaTheme.type.monoSmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall)) {
            AccentButton(
                text = syncLabel(state.syncStatus),
                onClick = onSync,
                enabled = state.syncStatus != SyncStatus.SYNCING,
            )
            OutlineButton(
                text = stringResource(R.string.account_sign_out),
                onClick = { confirmSignOut = true },
                enabled = !state.busy,
            )
        }
        // Verbatim and untranslated on purpose: this is the line someone pastes
        // into a bug report. A red status dot with no reason is what made "my
        // listening never reaches the server" impossible to act on.
        state.syncError?.let { reason ->
            MonoText(
                text = stringResource(R.string.account_sync_error, reason),
                color = KoalaTheme.colors.ink4,
                style = KoalaTheme.type.monoSmall,
            )
        }
    }

    Hairline()
    Section(stringResource(R.string.account_statistics)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onGlobalStats(!state.globalStatsOptIn) },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.account_global_stats),
                    style = KoalaTheme.type.label,
                    color = KoalaTheme.colors.ink2,
                )
                Text(
                    text = stringResource(R.string.account_global_stats_body),
                    style = KoalaTheme.type.bodySmall,
                    color = KoalaTheme.colors.ink3,
                )
            }
            Switch(
                checked = state.globalStatsOptIn,
                onCheckedChange = onGlobalStats,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = KoalaTheme.colors.accentOn,
                    checkedTrackColor = KoalaTheme.colors.accentInk,
                ),
            )
        }
    }

    Hairline()
    Section(stringResource(R.string.account_sessions)) {
        if (state.sessions.isEmpty()) {
            Text(
                text = stringResource(R.string.account_no_sessions),
                style = KoalaTheme.type.bodySmall,
                color = KoalaTheme.colors.ink4,
            )
        }
        state.sessions.forEach { session -> SessionRow(session, onRevoke) }
    }
}

@Composable
private fun SessionRow(session: AccountSession, onRevoke: (String) -> Unit) {
    val formatted = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        .format(Date(session.lastUsedAtMs))
    val label = session.deviceName.ifBlank { session.deviceType.ifBlank { session.kind } }
    var confirmRevoke by remember { mutableStateOf(false) }

    if (confirmRevoke) {
        ConfirmDialog(
            title = stringResource(R.string.account_revoke_title),
            body = stringResource(R.string.account_revoke_body, label),
            confirmLabel = stringResource(R.string.account_revoke),
            onConfirm = {
                confirmRevoke = false
                onRevoke(session.id)
            },
            onDismiss = { confirmRevoke = false },
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = KoalaSpacing.gapSmall),
        horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = KoalaTheme.type.label,
                color = KoalaTheme.colors.ink2,
            )
            MonoText(
                text = listOf(session.truncatedIp, formatted)
                    .filter(String::isNotBlank)
                    .joinToString(" · "),
                color = KoalaTheme.colors.ink4,
                style = KoalaTheme.type.monoSmall,
            )
        }
        if (session.isCurrent) {
            MonoText(
                text = stringResource(R.string.account_current),
                color = KoalaTheme.colors.accentInk,
                style = KoalaTheme.type.monoSmall,
            )
        } else {
            OutlineButton(
                text = stringResource(R.string.account_revoke),
                onClick = { confirmRevoke = true },
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(KoalaSpacing.gap)) {
        Text(text = title, style = KoalaTheme.type.sectionTitle, color = KoalaTheme.colors.inkStrong)
        content()
    }
}

@Composable
private fun errorMessage(error: DataError): String = when (error) {
    is DataError.Http -> stringResource(R.string.account_error_http, error.code)
    is DataError.Network -> stringResource(R.string.account_error_network)
    is DataError.Malformed -> stringResource(
        if (error.cause.startsWith("OPML:")) {
            R.string.account_error_opml
        } else {
            R.string.account_error_data
        },
    )
    DataError.NoServerConfigured -> stringResource(R.string.account_error_server)
}

private fun DataError.isOpmlError(): Boolean =
    this is DataError.Malformed && cause.startsWith("OPML:")

@Composable
private fun noticeMessage(notice: String): String = stringResource(
    when (notice) {
        "account_created" -> R.string.account_notice_created
        "signed_in" -> R.string.account_notice_signed_in
        "signed_out" -> R.string.account_notice_signed_out
        "password_changed" -> R.string.account_notice_password
        "session_revoked" -> R.string.account_notice_revoked
        else -> R.string.account_notice_done
    },
)

@Composable
private fun syncLabel(status: SyncStatus): String = stringResource(
    when (status) {
        SyncStatus.OFF -> R.string.account_sync_off
        SyncStatus.IDLE -> R.string.account_sync_now
        SyncStatus.SYNCING -> R.string.account_syncing
        SyncStatus.ERROR -> R.string.account_sync_retry
    },
)

private fun readLimitedText(context: Context, uri: Uri): String {
    val stream = context.contentResolver.openInputStream(uri)
        ?: throw IllegalArgumentException("cannot open OPML")
    return stream.bufferedReader().use { reader ->
        val buffer = CharArray(8_192)
        val text = StringBuilder()
        while (true) {
            val read = reader.read(buffer)
            if (read < 0) break
            text.append(buffer, 0, read)
            require(text.length <= MAX_OPML_CHARS) { "OPML exceeds 5 MB" }
        }
        text.toString()
    }
}

private const val MAX_OPML_CHARS = 5 * 1024 * 1024
