package com.miunlock.app.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

val MiOrange = Color(0xFFFF6900)
val MiOrangeSoft = Color(0xFFFFE2CC)
val PaperDark = Color(0xFF211A16)

/**
 * Единственная схема приложения — тёплая тёмная. Выбора тем нет:
 * фон тёмно-коричневый, карточки на тон светлее, акцент оранжевый.
 */
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
fun MiUnlockTheme(content: @Composable () -> Unit) {
    val colors = DarkColors
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
