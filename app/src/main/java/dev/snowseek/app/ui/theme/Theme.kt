package dev.snowseek.app.ui.theme

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
private val Charcoal = darkColorScheme(
    primary = Color(0xFFB9C8FF), onPrimary = Color(0xFF152452),
    primaryContainer = Color(0xFF363C4B), onPrimaryContainer = Color(0xFFE8EBF3),
    background = Color(0xFF191919), onBackground = Color(0xFFEAEAEA),
    surface = Color(0xFF232323), onSurface = Color(0xFFEAEAEA),
    surfaceVariant = Color(0xFF303030), onSurfaceVariant = Color(0xFFBDBDBD),
    outline = Color(0xFF676767), outlineVariant = Color(0xFF414141),
)
private val Black = Charcoal.copy(
    background = Color.Black, surface = Color(0xFF090909),
    surfaceVariant = Color(0xFF1A1A1A), outlineVariant = Color(0xFF303030),
)

@Composable
fun SnowSeekTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val dark = when (settings.theme) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.CHARCOAL, ThemeMode.BLACK -> true
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
    val colors = when (settings.theme) {
        ThemeMode.SYSTEM -> if (dark) Black else Light
        ThemeMode.LIGHT -> Light
        ThemeMode.DARK -> Dark
        ThemeMode.CHARCOAL -> Charcoal
        ThemeMode.BLACK -> Black
    }
    val scale = settings.textScale.takeIf { it.isFinite() }?.coerceIn(.7f, 1.5f) ?: 1f
    val typography = remember(settings.font) { snowSeekTypography(settings.font) }
    CompositionLocalProvider(LocalDensity provides Density(density.density * scale, density.fontScale)) {
        MaterialTheme(colorScheme = colors, typography = typography, shapes = Shapes(
            extraSmall = RoundedCornerShape(10.dp), small = RoundedCornerShape(16.dp),
            medium = RoundedCornerShape(24.dp), large = RoundedCornerShape(28.dp),
            extraLarge = RoundedCornerShape(32.dp),
        ), content = content)
    }
}
