package dev.snowseek.app.settings

import android.content.Context
import dev.snowseek.core.ChatInstruction
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.snowSeekSettings by preferencesDataStore(name = "snowseek_settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK, CHARCOAL, BLACK }

data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val language: String = "ru",
    val textScale: Float = 1f,
    val animations: Boolean = true,
    val welcomeComplete: Boolean = false,
    val customInstructions: List<ChatInstruction> = emptyList(),
)

class SettingsStore(context: Context) {
    private val store = context.applicationContext.snowSeekSettings

    // Read failures are deliberately propagated so the UI can show an actionable error.
    val settings: Flow<AppSettings> = store.data.map { values ->
        AppSettings(
            theme = ThemeMode.entries.firstOrNull { it.name == values[THEME] } ?: ThemeMode.SYSTEM,
            language = validLanguage(values[LANGUAGE] ?: "ru"),
            textScale = validScale(values[TEXT_SCALE] ?: 1f),
            animations = values[ANIMATIONS] ?: true,
            welcomeComplete = values[WELCOME] ?: false,
            customInstructions = values[INSTRUCTIONS]?.let { Json.decodeFromString<List<ChatInstruction>>(it) }
                .orEmpty().filter { it.isValid() && it.id.startsWith("custom.") }.distinctBy { it.id },
        )
    }

    suspend fun updateTheme(value: ThemeMode) { store.edit { it[THEME] = value.name } }
    suspend fun updateLanguage(value: String) { store.edit { it[LANGUAGE] = validLanguage(value) } }
    suspend fun updateTextScale(value: Float) { store.edit { it[TEXT_SCALE] = validScale(value) } }
    suspend fun updateAnimations(value: Boolean) { store.edit { it[ANIMATIONS] = value } }
    suspend fun completeWelcome() { store.edit { it[WELCOME] = true } }

    suspend fun saveInstruction(instruction: ChatInstruction) {
        require(instruction.isValid() && instruction.id.startsWith("custom."))
        store.edit { values ->
            val previous = values[INSTRUCTIONS]?.let { Json.decodeFromString<List<ChatInstruction>>(it) }.orEmpty()
            require(previous.size < 100 || previous.any { it.id == instruction.id }) { "Instruction limit reached" }
            values[INSTRUCTIONS] = Json.encodeToString(previous.filterNot { it.id == instruction.id } + instruction)
        }
    }
    suspend fun deleteInstruction(id: String) {
        store.edit { values ->
            val previous = values[INSTRUCTIONS]?.let { Json.decodeFromString<List<ChatInstruction>>(it) }.orEmpty()
            values[INSTRUCTIONS] = Json.encodeToString(previous.filterNot { it.id == id })
        }
    }

    private companion object {
        val INSTRUCTIONS = stringPreferencesKey("custom_instructions_v1")
        val THEME = stringPreferencesKey("theme")
        val LANGUAGE = stringPreferencesKey("language")
        val TEXT_SCALE = floatPreferencesKey("text_scale")
        val ANIMATIONS = booleanPreferencesKey("animations")
        val WELCOME = booleanPreferencesKey("welcome_complete")
        fun validLanguage(value: String) = if (value == "en") "en" else "ru"
        fun validScale(value: Float) = if (value.isFinite()) value.coerceIn(0.7f, 1.5f) else 1f
    }
}
