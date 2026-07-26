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
import net.koalastuff.koalacast.core.model.ThemeMode

private val LocalKoalaColors: ProvidableCompositionLocal<KoalaColors> =
    staticCompositionLocalOf { KoalaDarkColors }

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
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = if (dark) KoalaDarkColors else KoalaLightColors
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

object KoalaTheme {
    val colors: KoalaColors
        @Composable @ReadOnlyComposable get() = LocalKoalaColors.current

    val type: KoalaTypography
        @Composable @ReadOnlyComposable get() = LocalKoalaTypography.current

    val shapes: KoalaShapes get() = KoalaShapes
    val spacing: KoalaSpacing get() = KoalaSpacing
}
