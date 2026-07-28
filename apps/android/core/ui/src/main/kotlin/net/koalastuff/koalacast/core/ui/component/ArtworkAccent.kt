package net.koalastuff.koalacast.core.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size
import coil3.toBitmap
import net.koalastuff.koalacast.core.ui.theme.ProvideKoalaAccent

/**
 * Derives the show screen's accent from its cover. Coil supplies the same memory
 * and disk cache as CoverArt, so this does not create a second network fetch.
 */
@Composable
fun ArtworkAccent(
    artworkUrl: String?,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val source = LocalArtworkUrls.current?.forArtwork(artworkUrl, 128)
        ?: artworkUrl?.takeIf(String::isNotBlank)
    var accent by remember(source) { mutableStateOf<Color?>(null) }

    LaunchedEffect(source) {
        accent = null
        if (source == null) return@LaunchedEffect
        val result = SingletonImageLoader.get(context).execute(
            ImageRequest.Builder(context)
                .data(source)
                .size(Size(128, 128))
                .allowHardware(false)
                .build(),
        )
        if (result is SuccessResult) {
            val palette = Palette.from(result.image.toBitmap()).generate()
            val color = palette.vibrantSwatch?.rgb
                ?: palette.mutedSwatch?.rgb
                ?: palette.dominantSwatch?.rgb
            accent = color?.let(::Color)
        }
    }

    ProvideKoalaAccent(accent, content)
}
