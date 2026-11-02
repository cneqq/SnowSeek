package dev.snowseek.app.ui.theme

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import dev.snowseek.app.settings.AppSettings
import dev.snowseek.app.settings.ThemeMode

private val Light = lightColorScheme(
    primary = Color(0xFF4163EB), onPrimary = Color.White,
    primaryContainer = Color(0xFFE8EDFF), onPrimaryContainer = Color(0xFF243B93),
    background = Color(0xFFF9FAFE), onBackground = Color(0xFF1D2437),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF1D2437),
    surfaceVariant = Color(0xFFEEF1F8), onSurfaceVariant = Color(0xFF626A80),
    outline = Color(0xFFCBD1E1), outlineVariant = Color(0xFFE3E7F1),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFA9B9FF), onPrimary = Color(0xFF10276E),
    primaryContainer = Color(0xFF28386A), onPrimaryContainer = Color(0xFFDDE4FF),
    background = Color(0xFF10131B), onBackground = Color(0xFFE5E9F5),
    surface = Color(0xFF171B26), onSurface = Color(0xFFE5E9F5),
    surfaceVariant = Color(0xFF242A39), onSurfaceVariant = Color(0xFFA8B0C6),
    outline = Color(0xFF424B63), outlineVariant = Color(0xFF30384B),
)

@Composable
fun SnowSeekTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val dark = when (settings.theme) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val density = LocalDensity.current
    val activity = LocalActivity.current
    SideEffect {
        activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * settings.textScale)) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
    }
}
