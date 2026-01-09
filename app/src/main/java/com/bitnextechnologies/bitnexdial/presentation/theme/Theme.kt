package com.bitnextechnologies.bitnexdial.presentation.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// BitNex Brand Colors
val BitNexTeal = Color(0xFF3AB0B0)       // Primary teal from logo
val BitNexTealDark = Color(0xFF2D8A8A)   // Darker variant
val BitNexTealLight = Color(0xFF4ECDC4)  // Lighter variant
val BitNexGray = Color(0xFF4A4A4A)       // Gray from logo
val BitNexGreen = Color(0xFF22C55E)
val BitNexRed = Color(0xFFEF4444)
val BitNexOrange = Color(0xFFF97316)

// Legacy aliases for compatibility
val BitNexBlue = BitNexTeal
val BitNexBlueDark = BitNexTealDark
val BitNexBlueLight = BitNexTealLight

// Light Theme Colors
private val LightColorScheme = lightColorScheme(
    primary = BitNexTeal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD1F5F5),
    onPrimaryContainer = Color(0xFF002020),
    secondary = Color(0xFF565E71),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDAE2F9),
    onSecondaryContainer = Color(0xFF131C2B),
    tertiary = BitNexGreen,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFBBF7D0),
    onTertiaryContainer = Color(0xFF002106),
    error = BitNexRed,
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF1A1C1E),
    surface = Color.White,
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF44474F),
    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC4C6D0)
)

// Dark Theme Colors
private val DarkColorScheme = darkColorScheme(
    primary = BitNexTealLight,
    onPrimary = Color(0xFF002F2F),
    primaryContainer = BitNexTealDark,
    onPrimaryContainer = Color(0xFFD1F5F5),
    secondary = Color(0xFFBEC6DC),
    onSecondary = Color(0xFF283141),
    secondaryContainer = Color(0xFF3E4759),
    onSecondaryContainer = Color(0xFFDAE2F9),
    tertiary = Color(0xFF86EFAC),
    onTertiary = Color(0xFF00390F),
    tertiaryContainer = Color(0xFF005319),
    onTertiaryContainer = Color(0xFFBBF7D0),
    error = Color(0xFFFCA5A5),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFEE2E2),
    background = Color(0xFF121212),
    onBackground = Color(0xFFE3E2E6),
    surface = Color(0xFF1A1A1A),
    onSurface = Color(0xFFE3E2E6),
    surfaceVariant = Color(0xFF44474F),
    onSurfaceVariant = Color(0xFFC4C6D0),
    outline = Color(0xFF8E9099),
    outlineVariant = Color(0xFF44474F)
)

@Composable
fun BitNexDialTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    useDarkTheme: Boolean? = null, // Force dark theme if set, otherwise use darkTheme
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val isDark = useDarkTheme ?: darkTheme
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        isDark -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.surface.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !isDark
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
