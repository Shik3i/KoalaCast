package net.koalastuff.koalacast.core.ui.component

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import net.koalastuff.koalacast.core.ui.theme.KoalaTheme

/**
 * Metadata is uppercase mono by decision, not by accident, so the uppercasing lives
 * here instead of in the dozens of call sites that would each have to remember.
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
        text = text.uppercase(),
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
