package net.koalastuff.koalacast.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.media3.cast.MediaRouteButton as Media3RouteButton
import androidx.media3.common.util.UnstableApi
import net.koalastuff.koalacast.core.ui.theme.KoalaSpacing
import net.koalastuff.koalacast.core.ui.theme.KoalaTheme

/**
 * The cast target picker.
 *
 * Media3 owns discovery, system output switching and the fallback Cast dialog.
 * Keeping the official button also keeps its disabled/connected semantics and
 * accessibility behaviour in sync with the player implementation.
 */
@OptIn(UnstableApi::class)
@Composable
fun CastButton(
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(LocalContentColor provides KoalaTheme.colors.ink3) {
        Media3RouteButton(modifier = modifier.size(KoalaSpacing.minTouchTarget))
    }
}
