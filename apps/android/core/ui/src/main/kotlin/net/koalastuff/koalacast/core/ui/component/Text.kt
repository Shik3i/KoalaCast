package net.koalastuff.koalacast.core.ui.component

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import net.koalastuff.koalacast.core.ui.theme.KoalaTheme

/**
 * Metadata. Sentence case: it used to be uppercased here, which together with a
 * mono face and wide tracking is what made every second line read as machine
 * output. The name stays [MonoText] because dozens of call sites use it and it
 * still means "the small, factual line" — it is simply no longer monospaced.
 */
@Composable
fun MonoText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = KoalaTheme.colors.ink4,
    style: TextStyle = KoalaTheme.type.mono,
    maxLines: Int = 1,
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        style = style,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Prose. Sentence case, short, `ink3` unless a screen says otherwise. */
@Composable
fun BodyText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = KoalaTheme.colors.ink3,
    style: TextStyle = KoalaTheme.type.body,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        style = style,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}
