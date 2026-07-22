package com.miunlock.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

val MiOrange = Color(0xFFFF6900)
val MiOrangeDark = Color(0xFFE85D00)
val MiOrangeSoft = Color(0xFFFFE2CC)
val Paper = Color(0xFFFFF8F0)
val PaperDark = Color(0xFF211A16)
val Ink = Color(0xFF2B2118)
val InkSoft = Color(0xFF6D5B4E)
val Success = Color(0xFF239B56)
val Error = Color(0xFFD84315)

private val LightColors = lightColorScheme(
    primary = MiOrange,
    onPrimary = Color.White,
    primaryContainer = MiOrangeSoft,
    onPrimaryContainer = Ink,
    secondary = Color(0xFF8D5B36),
    background = Paper,
    onBackground = Ink,
    surface = Color(0xFFFFFCF8),
    onSurface = Ink,
    outline = Color(0xFF9A806D),
    error = Error,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF8A3D),
    onPrimary = Color(0xFF421700),
    primaryContainer = Color(0xFF633000),
    onPrimaryContainer = Color(0xFFFFDCC4),
    secondary = Color(0xFFFFB780),
    background = PaperDark,
    onBackground = Color(0xFFF2E3D8),
    surface = Color(0xFF2B211B),
    onSurface = Color(0xFFF2E3D8),
    outline = Color(0xFFB99A84),
    error = Color(0xFFFFB4A3),
)

@Composable
fun MiUnlockTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        val window = (view.context as Activity).window
        window.statusBarColor = colors.primary.toArgbCompat()
        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
    }
    MaterialTheme(colorScheme = colors, typography = Typography, content = content)
}

private fun Color.toArgbCompat(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt()
)
