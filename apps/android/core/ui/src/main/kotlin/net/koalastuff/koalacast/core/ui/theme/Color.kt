package net.koalastuff.koalacast.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * The colour tokens one palette provides. The values themselves live in
 * [Palettes.kt], generated from the web client's stylesheet so that the two
 * clients ship the same nine palettes rather than two drifting copies.
 *
 * The one rule that makes light mode work: the accent is a *fill*, never text.
 * [accentFill] paints grounds (with [accentOn] on top), [accentInk] paints every
 * accented glyph and label. In dark mode the two are usually the same colour; in
 * light mode [accentInk] darkens sharply, because a pastel accent as text
 * measures around 1.8:1 against white.
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
