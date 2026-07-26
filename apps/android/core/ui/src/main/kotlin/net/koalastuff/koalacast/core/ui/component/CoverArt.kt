package net.koalastuff.koalacast.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import net.koalastuff.koalacast.core.data.server.ArtworkUrls
import net.koalastuff.koalacast.core.ui.theme.KoalaShapes
import net.koalastuff.koalacast.core.ui.theme.KoalaTheme
import kotlin.math.sqrt

/**
 * Provided once by the app so every cover in the tree gets the listener's artwork
 * routing (proxy on or off) without each screen having to thread it through.
 * `null` — in previews and tests — means "load the URL as given".
 */
val LocalArtworkUrls = staticCompositionLocalOf<ArtworkUrls?> { null }

/**
 * The 135° stripe placeholder from the handoff, used as loading *and* missing state
 * so a feed without artwork looks deliberate rather than broken.
 */
@Composable
fun coverPlaceholderBrush(): Brush {
    val colors = KoalaTheme.colors
    val period = with(LocalDensity.current) { 24.dp.toPx() }
    // 135° in CSS points down-right; one full dark+light period spans 24dp along it.
    val leg = period / sqrt(2f)
    return Brush.linearGradient(
        colorStops = arrayOf(
            0f to colors.tileStripeDark,
            0.5f to colors.tileStripeDark,
            0.5f to colors.tileStripeLight,
            1f to colors.tileStripeLight,
        ),
        start = Offset.Zero,
        end = Offset(leg, leg),
        tileMode = TileMode.Repeated,
    )
}

/**
 * @param sizeHint the width the cover is drawn at. Passed to the image proxy so the
 *   server downscales instead of shipping a 3000px original to a 56dp tile.
 */
@Composable
fun CoverArt(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    shape: Shape = KoalaShapes.cover,
    sizeHint: Dp? = null,
) {
    val artworkUrls = LocalArtworkUrls.current
    val widthPx = sizeHint?.let { with(LocalDensity.current) { it.roundToPx() } }
    val model = artworkUrls?.forArtwork(url, widthPx) ?: url?.takeIf { it.isNotBlank() }

    Box(modifier = modifier.clip(shape).background(coverPlaceholderBrush())) {
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}
