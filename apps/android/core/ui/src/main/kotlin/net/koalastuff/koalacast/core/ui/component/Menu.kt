package net.koalastuff.koalacast.core.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.koalastuff.koalacast.core.ui.R
import net.koalastuff.koalacast.core.ui.icon.PhosphorIcons
import net.koalastuff.koalacast.core.ui.theme.KoalaShapes
import net.koalastuff.koalacast.core.ui.theme.KoalaSpacing
import net.koalastuff.koalacast.core.ui.theme.KoalaTheme

/**
 * One entry in an [OverflowMenu]. `destructive` marks the actions a listener cannot
 * undo by tapping again — hiding a show, unsubscribing, deleting a file. Those never
 * sit on a screen as a bare one-tap control; the menu is the whole point.
 */
data class MenuAction(
    val label: String,
    val onClick: () -> Unit,
    val icon: ImageVector? = null,
    val destructive: Boolean = false,
    val enabled: Boolean = true,
    /** Marks the option currently in force, for menus that pick one of a set. */
    val selected: Boolean = false,
)

/**
 * The row-level "…" control. Two taps to reach anything inside, which is exactly
 * what separates a considered action from a mis-tap on a scrolling list.
 */
@Composable
fun OverflowMenu(
    contentDescription: String,
    actions: List<MenuAction>,
    modifier: Modifier = Modifier,
    boxSize: Dp = 34.dp,
    iconSize: Dp = 17.dp,
) {
    MenuButton(
        icon = PhosphorIcons.DotsThreeVertical,
        contentDescription = contentDescription,
        actions = actions,
        modifier = modifier,
        boxSize = boxSize,
        iconSize = iconSize,
    )
}

/**
 * An icon that opens a menu, with an optional label beside it for controls whose
 * current setting is worth showing at rest (a sleep timer counting down, say).
 * Settings that are not being changed right now stay folded away behind it —
 * a panel of options permanently open is clutter, not convenience.
 */
@Composable
fun MenuButton(
    icon: ImageVector,
    contentDescription: String,
    actions: List<MenuAction>,
    modifier: Modifier = Modifier,
    label: String? = null,
    /**
     * Names the menu once it is open. Without it a list reading "5 min / 15 min"
     * asks the reader to work out what the minutes are for — which they only can
     * if they already knew what the icon meant.
     */
    title: String? = contentDescription,
    active: Boolean = false,
    boxSize: Dp = 34.dp,
    iconSize: Dp = 17.dp,
) {
    if (actions.isEmpty()) return
    val colors = KoalaTheme.colors
    var expanded by remember { mutableStateOf(false) }
    val tint = if (active) colors.accentInk else colors.ink4

    Box(modifier = modifier) {
        if (label == null) {
            IconButtonSquare(
                icon = icon,
                contentDescription = contentDescription,
                onClick = { expanded = true },
                tint = tint,
                bordered = false,
                boxSize = boxSize,
                iconSize = iconSize,
            )
        } else {
            Row(
                modifier = Modifier
                    .defaultMinSize(minHeight = KoalaSpacing.minTouchTarget)
                    .clip(KoalaShapes.chip)
                    .clickable(role = Role.Button) { expanded = true }
                    .padding(horizontal = KoalaSpacing.gapSmall),
                horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gapTiny),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PhosphorIcon(
                    icon = icon,
                    contentDescription = contentDescription,
                    tint = tint,
                    size = iconSize,
                )
                MonoText(text = label, color = tint, style = KoalaTheme.type.monoSmall)
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = KoalaShapes.card,
            containerColor = colors.bgPanel,
            border = BorderStroke(1.dp, colors.borderUi),
            shadowElevation = 0.dp,
            tonalElevation = 0.dp,
            modifier = Modifier.widthIn(min = 180.dp),
        ) {
            if (!title.isNullOrBlank()) {
                MonoText(
                    text = title,
                    color = colors.ink4,
                    style = KoalaTheme.type.monoSmall,
                    modifier = Modifier.padding(
                        start = KoalaSpacing.gap,
                        end = KoalaSpacing.gap,
                        top = KoalaSpacing.gapSmall,
                        bottom = KoalaSpacing.gapTiny,
                    ),
                )
                Hairline(modifier = Modifier.padding(horizontal = KoalaSpacing.gapSmall))
            }
            actions.forEach { action ->
                val ink = when {
                    !action.enabled -> colors.ink4
                    action.selected -> colors.accentInk
                    else -> colors.ink2
                }
                DropdownMenuItem(
                    enabled = action.enabled,
                    text = {
                        Text(
                            text = action.label,
                            style = KoalaTheme.type.label,
                            color = ink,
                        )
                    },
                    leadingIcon = action.icon?.let {
                        {
                            PhosphorIcon(
                                icon = it,
                                contentDescription = null,
                                tint = if (action.destructive) colors.ink3 else colors.ink4,
                                size = 17.dp,
                            )
                        }
                    },
                    trailingIcon = if (action.selected) {
                        {
                            PhosphorIcon(
                                icon = PhosphorIcons.Check,
                                contentDescription = null,
                                tint = colors.accentInk,
                                size = 15.dp,
                            )
                        }
                    } else {
                        null
                    },
                    colors = MenuDefaults.itemColors(
                        textColor = ink,
                        leadingIconColor = colors.ink4,
                        disabledTextColor = colors.ink4,
                        disabledLeadingIconColor = colors.ink4,
                    ),
                    onClick = {
                        expanded = false
                        action.onClick()
                    },
                )
            }
        }
    }
}

/**
 * The second gate in front of anything irreversible. Deliberately plain: the
 * question, what it will do, and a way out that is at least as easy to hit.
 */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String = stringResource(R.string.action_cancel),
) {
    val colors = KoalaTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.bgPanel,
        shape = KoalaShapes.card,
        title = {
            Text(text = title, style = KoalaTheme.type.sectionTitle, color = colors.inkStrong)
        },
        text = {
            Text(text = body, style = KoalaTheme.type.bodySmall, color = colors.ink3)
        },
        dismissButton = {
            OutlineButton(text = dismissLabel, onClick = onDismiss)
        },
        confirmButton = {
            AccentButton(text = confirmLabel, onClick = onConfirm)
        },
    )
}

/**
 * A reversal offered where the action happened. Hiding a show is recoverable in
 * Settings, but only for someone who knows to look there — this makes the way back
 * as short as the way in.
 */
@Composable
fun UndoBanner(
    text: String,
    actionLabel: String,
    onAction: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KoalaTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = KoalaSpacing.screenH, vertical = KoalaSpacing.gapSmall)
            .clip(KoalaShapes.card)
            .background(colors.bgSunken)
            .padding(start = KoalaSpacing.gap, end = KoalaSpacing.gapSmall),
        horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(vertical = KoalaSpacing.gapSmall)) {
            MonoText(text = text, color = colors.ink3, style = KoalaTheme.type.monoSmall)
        }
        OutlineButton(text = actionLabel, onClick = onAction)
        IconButtonSquare(
            icon = PhosphorIcons.X,
            contentDescription = stringResource(R.string.action_dismiss),
            onClick = onDismiss,
            tint = colors.ink2,
            bordered = true,
            boxSize = KoalaSpacing.minTouchTarget,
            iconSize = 17.dp,
            shape = KoalaShapes.card,
        )
    }
}
