package net.koalastuff.koalacast.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.koalastuff.koalacast.core.ui.theme.KoalaShapes
import net.koalastuff.koalacast.core.ui.theme.KoalaTheme

/**
 * The 4px progress bar from the transport bar, reused wherever a row needs to
 * show how far in the listener is. Decorative: the same number is always
 * available as text next to it, so it carries no semantics of its own.
 */
@Composable
fun ProgressTrack(
    percent: Int,
    modifier: Modifier = Modifier,
    height: Dp = 4.dp,
) {
    val colors = KoalaTheme.colors
    val fraction = (percent / 100f).coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(KoalaShapes.pill)
            .background(colors.track)
            .clearAndSetSemantics { },
    ) {
        if (fraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .clip(KoalaShapes.pill)
                    .background(if (colors.isDark) colors.accentFill else colors.accentInk),
            )
        }
    }
}
