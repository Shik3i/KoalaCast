package net.koalastuff.koalacast.core.ui.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.koalastuff.koalacast.core.ui.icon.PhosphorIcons
import net.koalastuff.koalacast.core.ui.theme.KoalaShapes
import net.koalastuff.koalacast.core.ui.theme.KoalaSpacing
import net.koalastuff.koalacast.core.ui.theme.KoalaTheme
import net.koalastuff.koalacast.core.ui.theme.reduceMotion

/**
 * Skeletons, not spinners — the definition of done says so. The pulse is skipped when
 * the system asks for reduced motion, so the block simply sits there at rest.
 */
@Composable
fun Skeleton(
    modifier: Modifier = Modifier,
    height: Dp = 14.dp,
) {
    val alpha = if (reduceMotion()) {
        0.6f
    } else {
        val transition = rememberInfiniteTransition(label = "skeleton")
        transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 0.75f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "skeleton-alpha",
        ).value
    }

    Box(
        modifier = modifier
            .height(height)
            .clip(KoalaShapes.chip)
            .alpha(alpha)
            .background(KoalaTheme.colors.bgSunken)
            .clearAndSetSemantics { },
    )
}

/** A list of skeleton rows shaped like the real rows they stand in for. */
@Composable
fun SkeletonRows(
    modifier: Modifier = Modifier,
    count: Int = 6,
    coverSize: Dp = 56.dp,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(KoalaSpacing.gapLarge),
    ) {
        repeat(count) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(KoalaSpacing.gap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Skeleton(modifier = Modifier.size(coverSize), height = coverSize)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(KoalaSpacing.gapSmall),
                ) {
                    Skeleton(modifier = Modifier.fillMaxWidth(0.7f))
                    Skeleton(modifier = Modifier.fillMaxWidth(0.4f), height = 11.dp)
                }
            }
        }
    }
}

/**
 * Empty states get an icon, a sentence and — where there is one — a way out. Never a
 * bare line of grey text.
 */
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = PhosphorIcons.Compass,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = KoalaSpacing.screenH, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(KoalaSpacing.gap),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(KoalaShapes.round)
                .background(KoalaTheme.colors.bgSunken),
            contentAlignment = Alignment.Center,
        ) {
            PhosphorIcon(
                icon = icon,
                contentDescription = null,
                tint = KoalaTheme.colors.accentInk,
                size = 24.dp,
            )
        }
        Text(
            text = title,
            style = KoalaTheme.type.sectionTitle,
            color = KoalaTheme.colors.inkStrong,
            textAlign = TextAlign.Center,
        )
        Text(
            text = body,
            style = KoalaTheme.type.bodySmall,
            color = KoalaTheme.colors.ink3,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            OutlineButton(text = actionLabel, onClick = onAction)
        }
    }
}

/**
 * Errors say what failed and offer the retry, in that order. The wording comes from
 * the caller because only the screen knows what the listener was trying to do.
 */
@Composable
fun ErrorState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    retryLabel: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    EmptyState(
        title = title,
        body = body,
        modifier = modifier,
        icon = PhosphorIcons.WarningCircle,
        actionLabel = retryLabel,
        onAction = onRetry,
    )
}

/** Decorative separator. Deliberately low contrast; never carries meaning alone. */
@Composable
fun Hairline(
    modifier: Modifier = Modifier,
    thickness: Dp = 1.dp,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(thickness)
            .background(KoalaTheme.colors.borderHair),
    )
}

/** Row separator inside lists. */
@Composable
fun RowSeparator(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(KoalaTheme.colors.borderRow),
    )
}

/** A fixed-width mono value, e.g. a running time in a list row. */
@Composable
fun MonoValue(text: String, width: Dp, modifier: Modifier = Modifier) {
    Box(modifier = modifier.width(width), contentAlignment = Alignment.CenterEnd) {
        MonoText(text = text, style = KoalaTheme.type.monoStrong, color = KoalaTheme.colors.ink3)
    }
}
