package net.koalastuff.koalacast.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import net.koalastuff.koalacast.core.model.PaletteId
import net.koalastuff.koalacast.core.model.ThemeMode

private val LocalKoalaColors: ProvidableCompositionLocal<KoalaColors> =
    staticCompositionLocalOf { koalaColors(PaletteId.DEFAULT, dark = true) }

private val LocalKoalaTypography: ProvidableCompositionLocal<KoalaTypography> =
    staticCompositionLocalOf { KoalaTypography() }

/**
 * The design system's entry point. It wraps [MaterialTheme] rather than replacing it,
 * because a few Material components (ripples, text selection, the system bar handling)
 * read the M3 scheme — but every colour a screen touches comes from [KoalaTheme].
 *
 * Dark is the default and the one the handoff says to ship: mint reaches its full
 * value only on a dark ground.
 */
@Composable
fun KoalaCastTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    palette: PaletteId = PaletteId.DEFAULT,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = koalaColors(palette, dark)
    val typography = KoalaTypography()

    val materialScheme = if (dark) {
        darkColorScheme(
            primary = colors.accentFill,
            onPrimary = colors.accentOn,
            background = colors.bgPanel,
            onBackground = colors.ink,
            surface = colors.bgPanel,
            onSurface = colors.ink,
            surfaceVariant = colors.bgSunken,
            onSurfaceVariant = colors.ink3,
            outline = colors.borderUi,
            outlineVariant = colors.borderHair,
        )
    } else {
        lightColorScheme(
            primary = colors.accentInk,
            onPrimary = colors.bgPanel,
            background = colors.bgPanel,
            onBackground = colors.ink,
            surface = colors.bgPanel,
            onSurface = colors.ink,
            surfaceVariant = colors.bgSunken,
            onSurfaceVariant = colors.ink3,
            outline = colors.borderUi,
            outlineVariant = colors.borderHair,
        )
    }

    CompositionLocalProvider(
        LocalKoalaColors provides colors,
        LocalKoalaTypography provides typography,
        LocalContentColor provides colors.ink,
    ) {
        MaterialTheme(colorScheme = materialScheme, content = content)
    }
}

@Composable
fun ProvideKoalaAccent(seed: Color?, content: @Composable () -> Unit) {
    val base = LocalKoalaColors.current
    val colors = if (seed == null) {
        base
    } else {
        val fill = if (base.isDark) lerp(seed, Color.White, 0.18f) else seed
        val ink = if (base.isDark) fill else lerp(seed, Color.Black, 0.58f)
        val on = if (fill.luminance() > 0.48f) Color(0xFF101318) else Color.White
        base.copy(
            accentFill = fill,
            accentInk = ink,
            accentOn = on,
            accentWash = fill.copy(alpha = if (base.isDark) 0.16f else 0.12f),
            dataBar = fill,
        )
    }
    CompositionLocalProvider(
        LocalKoalaColors provides colors,
        LocalContentColor provides colors.ink,
        content = content,
    )
}

object KoalaTheme {
    val colors: KoalaColors
        @Composable @ReadOnlyComposable get() = LocalKoalaColors.current

    val type: KoalaTypography
        @Composable @ReadOnlyComposable get() = LocalKoalaTypography.current

    val shapes: KoalaShapes get() = KoalaShapes
    val spacing: KoalaSpacing get() = KoalaSpacing
}
