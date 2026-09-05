package com.papr.scanner.presentation.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
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
