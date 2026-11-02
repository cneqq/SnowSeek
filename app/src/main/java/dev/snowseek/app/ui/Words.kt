package dev.snowseek.app.ui

import androidx.compose.runtime.staticCompositionLocalOf

class Words(val language: String) {
    fun text(ru: String, en: String) = if (language == "en") en else ru
}
val LocalWords = staticCompositionLocalOf { Words("ru") }
val LocalDemoMode = staticCompositionLocalOf { true }
