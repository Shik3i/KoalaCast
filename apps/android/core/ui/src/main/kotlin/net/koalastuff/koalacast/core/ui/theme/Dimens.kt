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
