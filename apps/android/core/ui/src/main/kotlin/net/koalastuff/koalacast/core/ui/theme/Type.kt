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
 * Three families, three jobs — bundled rather than loaded from a font provider so the
 * app makes no third-party request at launch. All four are SIL Open Font License;
 * copies live in `core/ui/licenses`.
 */

/** Display: spotlight headline and show title only. Condensed (wdth 62.5), uppercase. */
val ArchivoCondensed = FontFamily(
    Font(
        resId = R.font.archivo_variable,
        weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(
            FontVariation.width(62.5f),
            FontVariation.weight(700),
        ),
    ),
)

/** Display at 75% width — the 12px section labels in button-style headers. */
val ArchivoSemiCondensed = FontFamily(
    Font(
        resId = R.font.archivo_variable,
        weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(
            FontVariation.width(75f),
            FontVariation.weight(700),
        ),
    ),
)

/** UI headings: screen titles, card titles, list titles, KPI numbers. */
val Bricolage = FontFamily(
    Font(
        resId = R.font.bricolage_grotesque_variable,
        weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700)),
    ),
    Font(
        resId = R.font.bricolage_grotesque_variable,
        weight = FontWeight.ExtraBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(800)),
    ),
)

/** Body and controls. */
val Outfit = FontFamily(
    Font(
        resId = R.font.outfit_variable,
        weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400)),
    ),
    Font(
        resId = R.font.outfit_variable,
        weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500)),
    ),
    Font(
        resId = R.font.outfit_variable,
        weight = FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600)),
    ),
    Font(
        resId = R.font.outfit_variable,
        weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700)),
    ),
)

/** Every time code, count, keyboard hint and piece of metadata. */
val PlexMono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_mono_bold, FontWeight.Bold),
)

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
        fontFamily = ArchivoCondensed,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 28.5.sp, // .95
        letterSpacing = 0.sp,
        lineHeightStyle = TightLineHeight,
        textAlign = TextAlign.Start,
    ),
    /** Show title on the podcast screen. */
    val displaySmall: TextStyle = TextStyle(
        fontFamily = ArchivoCondensed,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 24.7.sp,
        lineHeightStyle = TightLineHeight,
    ),
    /** Screen titles. */
    val screenTitle: TextStyle = TextStyle(
        fontFamily = Bricolage,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 22.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.03).em,
    ),
    /** Section headings and card titles. */
    val sectionTitle: TextStyle = TextStyle(
        fontFamily = Bricolage,
        fontWeight = FontWeight.Bold,
        fontSize = 17.sp,
        lineHeight = 21.sp,
        letterSpacing = (-0.02).em,
    ),
    /** List item titles. */
    val listTitle: TextStyle = TextStyle(
        fontFamily = Bricolage,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = (-0.02).em,
    ),
    /** Prose. */
    val body: TextStyle = TextStyle(
        fontFamily = Outfit,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    val bodySmall: TextStyle = TextStyle(
        fontFamily = Outfit,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    ),
    /** Nav labels and buttons. */
    val label: TextStyle = TextStyle(
        fontFamily = Outfit,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 16.sp,
    ),
    /** Metadata: uppercase, tracked, tabular. */
    val mono: TextStyle = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.08.em,
    ),
    val monoSmall: TextStyle = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 13.sp,
        letterSpacing = 0.1.em,
    ),
    /** Badges — `COVER STORY`, `NEW`. */
    val monoBadge: TextStyle = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.Bold,
        fontSize = 10.sp,
        lineHeight = 12.sp,
        letterSpacing = 0.14.em,
    ),
    /** KPI numbers and time codes. */
    val monoStrong: TextStyle = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.06.em,
    ),
)
