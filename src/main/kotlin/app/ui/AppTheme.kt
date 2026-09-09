package app.ui

import androidx.compose.material.Colors
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.ui.graphics.Color

private val LightBackground = Color(0xFFEBECF0)

internal fun appMaterialColors(isDark: Boolean): Colors = when {
    isDark -> darkColors(
        primary = Color(0xFF90CAF9),
        primaryVariant = Color(0xFF42A5F5),
        secondary = Color(0xFF90CAF9),
        background = Color(0xFF292B2E),
        surface = Color(0xFF32353B),
        error = Color(0xFFCF6679),
        onPrimary = Color(0xFF0D47A1),
        onSecondary = Color(0xFF0D47A1),
        onBackground = Color.White,
        onSurface = Color.White,
        onError = Color.Black,
    )
    else -> lightColors(
        primary = Color(0xFF1976D2),
        primaryVariant = Color(0xFF1565C0),
        secondary = Color(0xFF1976D2),
        background = LightBackground,
        surface = Color.White,
        error = Color(0xFFB00020),
        onPrimary = Color.White,
        onSecondary = Color.White,
        onBackground = Color(0xFF1F2328),
        onSurface = Color(0xFF1F2328),
    )
}
