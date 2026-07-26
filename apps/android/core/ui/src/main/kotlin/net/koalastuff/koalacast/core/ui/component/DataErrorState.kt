package net.koalastuff.koalacast.core.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import net.koalastuff.koalacast.core.model.DataError
import net.koalastuff.koalacast.core.ui.R

/**
 * One place that turns a [DataError] into words. Screens pass the error straight
 * through, so the phrasing — and its translation — never drifts between them.
 */
@Composable
fun DataErrorState(
    error: DataError,
    serverUrl: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    val (title, body) = when (error) {
        is DataError.Network -> stringResource(R.string.error_offline_title) to
            stringResource(R.string.error_offline_body, serverUrl)

        is DataError.Http -> stringResource(R.string.error_server_title) to
            stringResource(R.string.error_server_body, error.code)

        is DataError.Malformed -> stringResource(R.string.error_unreadable_title) to
            stringResource(R.string.error_unreadable_body)

        DataError.NoServerConfigured -> stringResource(R.string.error_no_server_title) to
            stringResource(R.string.error_no_server_body)
    }

    ErrorState(
        title = title,
        body = body,
        modifier = modifier,
        retryLabel = if (onRetry != null) stringResource(R.string.action_retry) else null,
        onRetry = onRetry,
    )
}
