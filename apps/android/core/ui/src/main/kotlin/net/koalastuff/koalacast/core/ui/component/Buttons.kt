package net.koalastuff.koalacast.core.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.koalastuff.koalacast.core.ui.theme.KoalaShapes
import net.koalastuff.koalacast.core.ui.theme.KoalaSpacing
import net.koalastuff.koalacast.core.ui.theme.KoalaTheme

/**
 * Primary action. Mint ground with dark ink in dark mode; in light mode the ground
 * darkens to `accent-ink` with a light label, because mint as text fails contrast.
 */
@Composable
fun AccentButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
) {
    val colors = KoalaTheme.colors
    val ground = if (colors.isDark) colors.accentFill else colors.accentInk
    val label = if (colors.isDark) colors.accentOn else colors.bgPanel

    Row(
        modifier = modifier
            .clip(KoalaShapes.card)
            .background(if (enabled) ground else colors.track)
            .clickableRow(enabled, onClick)
            .defaultMinSize(minHeight = KoalaSpacing.minTouchTarget)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            PhosphorIcon(
                icon = leadingIcon,
                contentDescription = null,
                tint = if (enabled) label else colors.ink4,
                size = 17.dp,
            )
        }
        Text(
            text = text,
            style = KoalaTheme.type.label,
            color = if (enabled) label else colors.ink4,
        )
    }
}

/** Secondary action: `border-ui` hairline, no fill. */
@Composable
fun OutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
) {
    val colors = KoalaTheme.colors
    val content = if (enabled) colors.ink2 else colors.ink4

    Row(
        modifier = modifier
            .clip(KoalaShapes.card)
            .border(BorderStroke(1.dp, colors.borderUi), KoalaShapes.card)
            .clickableRow(enabled, onClick)
            .defaultMinSize(minHeight = KoalaSpacing.minTouchTarget)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            PhosphorIcon(icon = leadingIcon, contentDescription = null, tint = content, size = 17.dp)
        }
        Text(text = text, style = KoalaTheme.type.label, color = content)
    }
}

/**
 * A bare icon control. The visual box follows the design (28–34px); the touch target
 * is padded out to 48dp because nothing interactive may be smaller on a phone.
 */
@Composable
fun IconButtonSquare(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = KoalaTheme.colors.ink2,
    bordered: Boolean = true,
    boxSize: Dp = 34.dp,
    iconSize: Dp = 17.dp,
    shape: Shape = KoalaShapes.chip,
) {
    val colors = KoalaTheme.colors
    Box(
        modifier = modifier
            .size(KoalaSpacing.minTouchTarget)
            .clickableRow(enabled = true, onClick = onClick, role = Role.Button),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(boxSize)
                .clip(shape)
                .then(
                    if (bordered) Modifier.border(BorderStroke(1.dp, colors.borderUi), shape)
                    else Modifier,
                ),
            contentAlignment = Alignment.Center,
        ) {
            PhosphorIcon(icon = icon, contentDescription = contentDescription, tint = tint, size = iconSize)
        }
    }
}

private fun Modifier.clickableRow(
    enabled: Boolean,
    onClick: () -> Unit,
    role: Role = Role.Button,
): Modifier = this.clickable(enabled = enabled, role = role, onClick = onClick)
