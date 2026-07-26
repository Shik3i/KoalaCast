package net.koalastuff.koalacast.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * The "Quiet Edition" (direction 4b) palette, ported verbatim from
 * `design_handoff_koalacast_4b/README.md`. Values were contrast-tested against
 * WCAG 2.1 AA — do not introduce a colour without measuring it the same way.
 *
 * The one rule that makes light mode work: mint is a *fill*, never text.
 * [accentFill] paints grounds (with [accentOn] on top), [accentInk] paints every
 * green glyph and label. In dark mode both are mint; in light mode [accentInk]
 * darkens to #12523A because mint as text measures ~1.8:1.
 */
@Immutable
data class KoalaColors(
    val bgApp: Color,
    val bgPanel: Color,
    val bgRail: Color,
    val bgSunken: Color,
    val bgTransport: Color,
    val ink: Color,
    val inkStrong: Color,
    val ink2: Color,
    val ink3: Color,
    /** Dimmest ink permitted anywhere. Nothing may go below this. */
    val ink4: Color,
    val accentFill: Color,
    val accentOn: Color,
    val accentInk: Color,
    val accentWash: Color,
    /** Inputs, chips, icon buttons — informational, held at 3:1. */
    val borderUi: Color,
    /** Section dividers. Decorative by spec, deliberately low contrast. */
    val borderHair: Color,
    /** Row separators. Decorative. */
    val borderRow: Color,
    val dataBar: Color,
    val track: Color,
    val heatmap: List<Color>,
    val tileStripeDark: Color,
    val tileStripeLight: Color,
    val isDark: Boolean,
)

val KoalaDarkColors = KoalaColors(
    bgApp = Color(0xFF06100C),
    bgPanel = Color(0xFF0A0F0C),
    bgRail = Color(0xFF080D0A),
    bgSunken = Color(0xFF0D1410),
    bgTransport = Color(0xFF050A07),
    ink = Color(0xFFEAF6F0),
    inkStrong = Color(0xFFF4FBF7),
    ink2 = Color(0xFFDCEBE4),
    ink3 = Color(0xFFA9C8BA),
    ink4 = Color(0xFF8FB4A3),
    accentFill = Color(0xFF7FD0AA),
    accentOn = Color(0xFF06100C),
    accentInk = Color(0xFF7FD0AA),
    accentWash = Color(0x1F7FD0AA), // rgba(127,208,170,.12)
    borderUi = Color(0xFF4A6558),
    borderHair = Color(0xFF1E2B23),
    borderRow = Color(0xFF16211B),
    dataBar = Color(0xFF5A7A68),
    track = Color(0xFF2C3D33),
    heatmap = listOf(
        Color(0xFF16211B),
        Color(0xFF2F5A45),
        Color(0xFF47876A),
        Color(0xFF5FB08C),
        Color(0xFF7FD0AA),
    ),
    tileStripeDark = Color(0xFF141D18),
    tileStripeLight = Color(0xFF1F2E26),
    isDark = true,
)

/**
 * Light theme. The handoff pins the tokens that carry the design; the few it leaves
 * open (app ground, sunken surface, strong/secondary ink, accent wash) are derived
 * here from the pinned ones and are marked as such.
 */
val KoalaLightColors = KoalaColors(
    bgApp = Color(0xFFEEF3EF), // derived: one step under bg-rail
    bgPanel = Color(0xFFFFFFFF),
    bgRail = Color(0xFFF5F8F5),
    bgSunken = Color(0xFFF0F4F1), // derived: matches bg-transport
    bgTransport = Color(0xFFF0F4F1),
    ink = Color(0xFF0C1A13),
    inkStrong = Color(0xFF06100C), // derived: one step over ink
    ink2 = Color(0xFF17281F), // derived: between ink and ink-3
    ink3 = Color(0xFF3C5145),
    ink4 = Color(0xFF4A6355),
    accentFill = Color(0xFF7FD0AA),
    accentOn = Color(0xFF06100C),
    accentInk = Color(0xFF12523A),
    accentWash = Color(0xFFDFF0E7), // the handoff's "active rail item"
    borderUi = Color(0xFF7D9186),
    borderHair = Color(0xFFD7E0D9),
    borderRow = Color(0xFFE6ECE7),
    dataBar = Color(0xFF6F9C84),
    track = Color(0xFFCFE2D7),
    heatmap = listOf(
        Color(0xFFE6ECE7),
        Color(0xFFBBDCC9),
        Color(0xFF8FC4A9),
        Color(0xFF5FA684),
        Color(0xFF12523A),
    ),
    tileStripeDark = Color(0xFFE9EFE9),
    tileStripeLight = Color(0xFFDBE5DD),
    isDark = false,
)
