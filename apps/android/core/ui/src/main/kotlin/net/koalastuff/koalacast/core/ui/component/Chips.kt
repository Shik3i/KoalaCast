package net.koalastuff.koalacast.core.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import net.koalastuff.koalacast.core.ui.theme.KoalaShapes
import net.koalastuff.koalacast.core.ui.theme.KoalaSpacing
import net.koalastuff.koalacast.core.ui.theme.KoalaTheme

/**
 * Filter chip. Selected chips use the accent as a *ground*, never as text, which is
 * what keeps the light theme legible.
 */
@Composable
fun KoalaChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KoalaTheme.colors
    val ground = when {
        !selected -> colors.bgSunken
        colors.isDark -> colors.accentFill
        else -> colors.accentInk
    }
    val ink = when {
        !selected -> colors.ink3
        colors.isDark -> colors.accentOn
        else -> colors.bgPanel
    }

    Box(
        modifier = modifier
            .defaultMinSize(minHeight = KoalaSpacing.minTouchTarget)
            .padding(vertical = 5.dp)
            .clip(KoalaShapes.chip)
            .background(ground)
            .then(
                if (selected) Modifier
                else Modifier.border(BorderStroke(1.dp, colors.borderUi), KoalaShapes.chip),
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        MonoText(text = label, color = ink, style = KoalaTheme.type.monoSmall)
    }
}

/**
 * The `I HAVE 25 | 40 | 60` control shape: one bordered track, the chosen segment
 * filled. Generic because Discover, Search sort and the Show tabs all use it.
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KoalaTheme.colors
    Row(
        modifier = modifier
            .clip(KoalaShapes.chip)
            .border(BorderStroke(1.dp, colors.borderUi), KoalaShapes.chip)
            .selectableGroup(),
        horizontalArrangement = Arrangement.Start,
    ) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            val ground = when {
                !selected -> colors.bgSunken
                colors.isDark -> colors.accentFill
                else -> colors.accentInk
            }
            val ink = when {
                !selected -> colors.ink3
                colors.isDark -> colors.accentOn
                else -> colors.bgPanel
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(ground)
                    .selectable(
                        selected = selected,
                        role = Role.RadioButton,
                        onClick = { onSelect(index) },
                    )
                    .defaultMinSize(minHeight = KoalaSpacing.minTouchTarget)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                MonoText(
                    text = option,
                    color = ink,
                    style = KoalaTheme.type.monoSmall.copy(textAlign = TextAlign.Center),
                    maxLines = 2,
                )
            }
        }
    }
}

/** `COVER STORY`, `NEW`, `EP 214` — accent ground, dark ink, 10px tracked mono. */
@Composable
fun AccentBadge(
    label: String,
    modifier: Modifier = Modifier,
) {
    val colors = KoalaTheme.colors
    Box(
        modifier = modifier
            .clip(KoalaShapes.chip)
            .background(if (colors.isDark) colors.accentFill else colors.accentInk)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(
            text = label.uppercase(),
            style = KoalaTheme.type.monoBadge,
            color = if (colors.isDark) colors.accentOn else colors.bgPanel,
        )
    }
}
