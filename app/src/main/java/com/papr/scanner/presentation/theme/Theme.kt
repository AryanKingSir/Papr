package com.papr.scanner.presentation.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = PaprBluePrimary,
    onPrimary = PaprBlueOnPrimary,
    primaryContainer = PaprBluePrimaryContainer,
    onPrimaryContainer = PaprBlueOnPrimaryContainer,
    secondary = PaprGreenSecondary,
    onSecondary = PaprGreenOnSecondary,
    secondaryContainer = PaprGreenSecondaryContainer,
    onSecondaryContainer = PaprGreenOnSecondaryContainer,
    surface = PaprSurfaceLight,
    onSurface = PaprOnSurfaceLight,
    background = PaprSurfaceLight,
    onBackground = PaprOnSurfaceLight,
    // The rest of Material 3's roles, from the Papr blue seed (Material's tonal-spot scheme), so
    // cards, sheets and bars don't fall back to the library's default purple tints when Material
    // You is off or unavailable (before Android 12).
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF44464F),
    surfaceTint = Color(0xFF465D91),
    inverseSurface = Color(0xFF2F3036),
    inverseOnSurface = Color(0xFFF1F0F7),
    inversePrimary = Color(0xFFAFC6FF),
    outline = Color(0xFF757780),
    outlineVariant = Color(0xFFC5C6D0),
    surfaceBright = Color(0xFFFAF8FF),
    surfaceDim = Color(0xFFDAD9E0),
    surfaceContainer = Color(0xFFEEEDF4),
    surfaceContainerHigh = Color(0xFFE8E7EF),
    surfaceContainerHighest = Color(0xFFE2E2E9),
    surfaceContainerLow = Color(0xFFF3F3FA),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    tertiary = Color(0xFF725573),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFCD7FB),
    onTertiaryContainer = Color(0xFF593E5A),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
)

private val DarkColors = darkColorScheme(
    primary = PaprBluePrimaryContainer,
    onPrimary = PaprBlueOnPrimaryContainer,
    primaryContainer = PaprBluePrimary,
    onPrimaryContainer = PaprBlueOnPrimary,
    secondary = PaprGreenSecondaryContainer,
    onSecondary = PaprGreenOnSecondaryContainer,
    secondaryContainer = PaprGreenSecondary,
    onSecondaryContainer = PaprGreenOnSecondary,
    surface = PaprSurfaceDark,
    onSurface = PaprOnSurfaceDark,
    background = PaprSurfaceDark,
    onBackground = PaprOnSurfaceDark,
    surfaceVariant = Color(0xFF44464F),
    onSurfaceVariant = Color(0xFFC5C6D0),
    surfaceTint = Color(0xFFAFC6FF),
    inverseSurface = Color(0xFFE2E2E9),
    inverseOnSurface = Color(0xFF2F3036),
    inversePrimary = Color(0xFF465D91),
    outline = Color(0xFF8F9099),
    outlineVariant = Color(0xFF44464F),
    surfaceBright = Color(0xFF38393E),
    surfaceDim = Color(0xFF121318),
    surfaceContainer = Color(0xFF1E1F25),
    surfaceContainerHigh = Color(0xFF282A2F),
    surfaceContainerHighest = Color(0xFF33353A),
    surfaceContainerLow = Color(0xFF1A1B20),
    surfaceContainerLowest = Color(0xFF0C0E13),
    tertiary = Color(0xFFDFBBDE),
    onTertiary = Color(0xFF412743),
    tertiaryContainer = Color(0xFF593E5A),
    onTertiaryContainer = Color(0xFFFCD7FB),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun PaprTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = PaprTypography,
        content = content,
    )
}
