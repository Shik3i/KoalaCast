@file:OptIn(ExperimentalTextApi::class)

package net.koalastuff.koalacast.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import net.koalastuff.koalacast.core.ui.R

/**
 * Two families, one voice. Nunito carries display and headings, Nunito Sans
 * carries body, controls and metadata — a superfamily, so the two agree on
 * proportions and the interface reads as one thing rather than four.
 *
 * Bundled rather than fetched, so the app makes no third-party request at
 * launch. Both are SIL Open Font License; copies live in `core/ui/licenses`.
 *
 * This replaces a stack (Archivo Condensed, Bricolage, Outfit, IBM Plex Mono)
 * that read as squeezed and mechanical: the display face was compressed to
 * 62.5% width, and every piece of metadata was uppercase mono on wide tracking.
 * Nothing here is condensed, and metadata is sentence case.
 */

/** Display: spotlight headline and show title. */
val NunitoDisplay = FontFamily(
    Font(
        resId = R.font.nunito_variable,
        weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700)),
    ),
    Font(
        resId = R.font.nunito_variable,
        weight = FontWeight.ExtraBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(800)),
    ),
)

/** UI headings: screen titles, card titles, list titles, KPI numbers. */
val NunitoHeading = NunitoDisplay

/** Body, controls and metadata. */
val NunitoText = FontFamily(
    Font(
        resId = R.font.nunito_sans_variable,
        weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400)),
    ),
    Font(
        resId = R.font.nunito_sans_variable,
        weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500)),
    ),
    Font(
        resId = R.font.nunito_sans_variable,
        weight = FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600)),
    ),
    Font(
        resId = R.font.nunito_sans_variable,
        weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700)),
    ),
)

/**
 * Counts, time codes and durations still have to line up in a column, which is
 * what the old mono face was really for. Tabular figures give the same
 * alignment without the typewriter voice.
 */
private const val TABULAR = "tnum"

private val TightLineHeight = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

/**
 * The scale the design actually uses: 46/40/30/26/22/17/15/14/13/12/11/10/9.
 * Metadata is uppercase mono and factual; prose is sentence case and short.
 */
@Immutable
data class KoalaTypography(
    /** Spotlight headline. Desktop 46px; 30px on a phone, per the mobile spec. */
    val display: TextStyle = TextStyle(
        fontFamily = NunitoDisplay,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 28.5.sp, // .95
        letterSpacing = 0.sp,
        lineHeightStyle = TightLineHeight,
        textAlign = TextAlign.Start,
    ),
    /** Show title on the podcast screen. */
    val displaySmall: TextStyle = TextStyle(
        fontFamily = NunitoDisplay,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 24.7.sp,
        lineHeightStyle = TightLineHeight,
    ),
    /** Screen titles. */
    val screenTitle: TextStyle = TextStyle(
        fontFamily = NunitoHeading,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 22.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.03).em,
    ),
    /** Section headings and card titles. */
    val sectionTitle: TextStyle = TextStyle(
        fontFamily = NunitoHeading,
        fontWeight = FontWeight.Bold,
        fontSize = 17.sp,
        lineHeight = 21.sp,
        letterSpacing = (-0.02).em,
    ),
    /** List item titles. */
    val listTitle: TextStyle = TextStyle(
        fontFamily = NunitoHeading,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = (-0.02).em,
    ),
    /** Prose. */
    val body: TextStyle = TextStyle(
        fontFamily = NunitoText,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    val bodySmall: TextStyle = TextStyle(
        fontFamily = NunitoText,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    ),
    /** Nav labels and buttons. */
    val label: TextStyle = TextStyle(
        fontFamily = NunitoText,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 16.sp,
    ),
    /** Metadata: uppercase, tracked, tabular. */
    val mono: TextStyle = TextStyle(
        fontFamily = NunitoText,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.01.em,
        fontFeatureSettings = TABULAR,
    ),
    val monoSmall: TextStyle = TextStyle(
        fontFamily = NunitoText,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 13.sp,
        letterSpacing = 0.01.em,
        fontFeatureSettings = TABULAR,
    ),
    /** Badges — `COVER STORY`, `NEW`. */
    val monoBadge: TextStyle = TextStyle(
        fontFamily = NunitoText,
        fontWeight = FontWeight.Bold,
        fontSize = 10.sp,
        lineHeight = 12.sp,
        letterSpacing = 0.06.em,
    ),
    /** KPI numbers and time codes. */
    val monoStrong: TextStyle = TextStyle(
        fontFamily = NunitoText,
        fontFeatureSettings = TABULAR,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.06.em,
    ),
)
