package dev.snowseek.app.ui.theme

import androidx.annotation.FontRes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import dev.snowseek.app.R
import dev.snowseek.app.settings.FontMode

private val standardFont = variableFamily(R.font.noto_sans)
private val appleLikeFont = variableFamily(R.font.inter)
private val anthropicLikeFont = variableFamily(R.font.source_serif)

internal fun FontMode.fontFamily(): FontFamily = when (this) {
    FontMode.SYSTEM -> FontFamily.Default
    FontMode.STANDARD -> standardFont
    FontMode.APPLE_LIKE -> appleLikeFont
    FontMode.ANTHROPIC_LIKE -> anthropicLikeFont
}

@OptIn(ExperimentalTextApi::class)
private fun variableFamily(@FontRes id: Int): FontFamily = FontFamily(
    listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map { weight ->
        Font(id, weight = weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))
    },
)

internal fun snowSeekTypography(mode: FontMode): Typography {
    val base = Typography()
    if (mode == FontMode.SYSTEM) return base
    val family = mode.fontFamily()
    return base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = family),
        displayMedium = base.displayMedium.copy(fontFamily = family),
        displaySmall = base.displaySmall.copy(fontFamily = family),
        headlineLarge = base.headlineLarge.copy(fontFamily = family),
        headlineMedium = base.headlineMedium.copy(fontFamily = family),
        headlineSmall = base.headlineSmall.copy(fontFamily = family),
        titleLarge = base.titleLarge.copy(fontFamily = family),
        titleMedium = base.titleMedium.copy(fontFamily = family),
        titleSmall = base.titleSmall.copy(fontFamily = family),
        bodyLarge = base.bodyLarge.copy(fontFamily = family),
        bodyMedium = base.bodyMedium.copy(fontFamily = family),
        bodySmall = base.bodySmall.copy(fontFamily = family),
        labelLarge = base.labelLarge.copy(fontFamily = family),
        labelMedium = base.labelMedium.copy(fontFamily = family),
        labelSmall = base.labelSmall.copy(fontFamily = family),
    )
}
