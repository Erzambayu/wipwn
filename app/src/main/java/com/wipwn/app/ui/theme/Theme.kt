package com.wipwn.app.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val WipwnDarkColorScheme = darkColorScheme(
    primary = GreenAccent,
    onPrimary = Color.Black,
    primaryContainer = GreenDark,
    onPrimaryContainer = GreenAccent,

    secondary = Blue400,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF0D3B66),
    onSecondaryContainer = Blue400,

    tertiary = Orange400,
    onTertiary = Color.Black,

    background = SurfaceDark,
    onBackground = OnSurfaceDark,

    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,

    surfaceContainerLowest = Color(0xFF090C10),
    surfaceContainerLow = SurfaceVariantDark,
    surfaceContainer = SurfaceContainerDark,
    surfaceContainerHigh = SurfaceCardDark,
    surfaceContainerHighest = Color(0xFF2D333B),

    error = RedDark,
    onError = Color.Black,
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),

    outline = Color(0xFF3D444D),
    outlineVariant = Color(0xFF2D333B)
)

@Composable
fun WipwnTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = WipwnDarkColorScheme,
        typography = WipwnTypography,
        content = content
    )
}
