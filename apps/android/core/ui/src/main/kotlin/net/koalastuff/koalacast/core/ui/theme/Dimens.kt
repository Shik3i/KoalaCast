package net.koalastuff.koalacast.core.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.dp

/**
 * Radii, spacing and hit targets from the handoff. No shadows anywhere: depth comes
 * from surface value plus hairlines, on Android exactly as on the web.
 */
@Immutable
object KoalaShapes {
    val chip = RoundedCornerShape(4.dp)
    val card = RoundedCornerShape(6.dp)
    val cover = RoundedCornerShape(6.dp)
    val statCard = RoundedCornerShape(8.dp)
    val frame = RoundedCornerShape(10.dp)
    val pill = RoundedCornerShape(20.dp)
    val round = RoundedCornerShape(percent = 50)
}

@Immutable
object KoalaSpacing {
    val hairline = 1.dp

    /** Horizontal screen padding. The handoff's 20–24px, at the tighter end on phones. */
    val screenH = 20.dp
    val sectionV = 20.dp

    val gapTiny = 4.dp
    val gapSmall = 8.dp
    val gap = 12.dp
    val gapLarge = 16.dp
    val gapSection = 24.dp

    /** Nothing interactive goes below this on touch. */
    val minTouchTarget = 48.dp
}

/**
 * The two sizes a square icon control comes in.
 *
 * There used to be six — 24/15, 26/14, 28/16, 28/17, 30/15, 30/16 — chosen a call
 * site at a time, so the clear button in the search field, the queue reorder
 * arrows and the episode row's queue button were all "the small icon button" at
 * three different sizes. Anything that does not fit one of these two is drift,
 * and the right fix is to pick the nearer one rather than to add a third.
 */
object KoalaIconButton {
    /** List-row actions and the transport's secondary controls. */
    val rowBox = 30.dp
    val rowIcon = 16.dp

    /** Dense inline actions: queue reordering, a text field's clear button. */
    val compactBox = 26.dp
    val compactIcon = 14.dp

    /**
     * The glyph slot in an icon-above-a-word action, and the icon inside it.
     *
     * The slot exists because one of those actions — Download — draws a progress
     * ring around its glyph and so needs room a bare icon does not. Every action
     * in the row reserves it whether it uses it or not; without that, the one
     * control with a ring stood 10dp taller and pushed its own label out of line
     * with its neighbours'.
     */
    val labelledBox = 30.dp
    val labelledIcon = 20.dp
}
