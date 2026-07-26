package net.koalastuff.koalacast.core.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * The spotlight's accent wash — the web's
 * `radial-gradient(85% 150% at 0% 0%, rgba(127,208,170,.13), transparent 62%)`.
 * Static: the optional drift animation is decorative and would have to be gated on
 * reduced motion, so it is not worth its cost on a phone.
 */
fun Modifier.spotlightGlow(accent: Color): Modifier = drawBehind {
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(accent.copy(alpha = 0.13f), Color.Transparent),
            center = Offset.Zero,
            radius = size.maxDimension * 1.15f,
        ),
    )
}
