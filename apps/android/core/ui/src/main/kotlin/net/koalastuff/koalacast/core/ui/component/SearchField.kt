package net.koalastuff.koalacast.core.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.material3.Text
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import net.koalastuff.koalacast.core.ui.icon.PhosphorIcons
import net.koalastuff.koalacast.core.ui.theme.KoalaShapes
import net.koalastuff.koalacast.core.ui.theme.KoalaSpacing
import net.koalastuff.koalacast.core.ui.theme.KoalaTheme

/**
 * One text input for the whole app: `border-ui` hairline, 5–6px radius, sunken ground,
 * leading glyph, optional trailing action. Material's TextField brings a filled
 * container and a floating label that the design does not have, so this is built on
 * BasicTextField instead of fought with.
 */
@Composable
fun KoalaTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = PhosphorIcons.MagnifyingGlass,
    trailingContent: (@Composable () -> Unit)? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Search,
    onImeAction: (() -> Unit)? = null,
    singleLine: Boolean = true,
) {
    val colors = KoalaTheme.colors
    val selectionColors = TextSelectionColors(
        handleColor = colors.accentFill,
        backgroundColor = colors.accentWash,
    )

    CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .clip(KoalaShapes.card)
                .background(colors.bgSunken)
                .border(BorderStroke(1.dp, colors.borderUi), KoalaShapes.card)
                .defaultMinSize(minHeight = KoalaSpacing.minTouchTarget)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingIcon != null) {
                PhosphorIcon(
                    icon = leadingIcon,
                    contentDescription = null,
                    tint = colors.ink4,
                    size = 15.dp,
                )
            }
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = KoalaTheme.type.bodySmall,
                        color = colors.ink4,
                        maxLines = 1,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = KoalaTheme.type.bodySmall.copy(color = colors.ink),
                    cursorBrush = SolidColor(colors.accentFill),
                    singleLine = singleLine,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = keyboardType,
                        imeAction = imeAction,
                        autoCorrectEnabled = false,
                    ),
                    keyboardActions = KeyboardActions(
                        onAny = { onImeAction?.invoke() },
                    ),
                )
            }
            trailingContent?.invoke()
        }
    }
}
