package dev.snowseek.app.settings

import android.content.Context
import dev.snowseek.core.ChatInstruction
import dev.snowseek.core.AppliedInstructionStore
import kotlinx.coroutines.flow.first
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
enum class FontMode { SYSTEM, STANDARD, APPLE_LIKE, ANTHROPIC_LIKE }
enum class WallpaperKind { NONE, SNOW, WAVES, PHOTO }

data class WallpaperSettings(
    val kind: WallpaperKind = WallpaperKind.NONE,
    val photoId: String? = null,
    val dimming: Float = .35f,
    val blur: Float = 0f,
) {
    fun normalized() = copy(
        photoId = photoId?.takeIf { it.matches(Regex("[a-f0-9-]{36}")) },
        dimming = if (dimming.isFinite()) dimming.coerceIn(0f, 1f) else .35f,
        blur = if (blur.isFinite()) blur.coerceIn(0f, 24f) else 0f,
    )
}
enum class PhotoCacheLimit(val bytes: Long) {
    MB_100(100L * 1024 * 1024),
    MB_500(500L * 1024 * 1024),
    GB_1(1024L * 1024 * 1024),
    GB_2(2L * 1024 * 1024 * 1024),
    UNLIMITED(Long.MAX_VALUE),
}

data class GlassSettings(
    val enabled: Boolean = false,
    val blur: Float = 6f,
    val refraction: Float = .55f,
    val transparency: Float = .35f,
) {
    internal fun normalized() = copy(
        blur = blur.finiteIn(0f..24f, 6f),
        refraction = refraction.finiteIn(0f..1f, .55f),
        transparency = transparency.finiteIn(0f..1f, .35f),
    )

    private fun Float.finiteIn(range: ClosedFloatingPointRange<Float>, fallback: Float) =
        if (isFinite()) coerceIn(range) else fallback
}

data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val font: FontMode = FontMode.SYSTEM,
    val language: String = "ru",
    val textScale: Float = 1f,
    val animations: Boolean = true,
    val hideInstructions: Boolean = false,
    val welcomeComplete: Boolean = false,
    val customInstructions: List<ChatInstruction> = emptyList(),
    val glass: GlassSettings = GlassSettings(),
    val photoCacheLimit: PhotoCacheLimit = PhotoCacheLimit.MB_500,
    val deepThink: Boolean = false,
    val search: Boolean = false,
    val wallpaper: WallpaperSettings = WallpaperSettings(),
)

class SettingsStore(context: Context) : AppliedInstructionStore {
    private val store = context.applicationContext.snowSeekSettings

    // Read failures are deliberately propagated so the UI can show an actionable error.
    val settings: Flow<AppSettings> = store.data.map { values ->
        AppSettings(
            theme = ThemeMode.entries.firstOrNull { it.name == values[THEME] } ?: ThemeMode.SYSTEM,
            font = FontMode.entries.firstOrNull { it.name == values[FONT] } ?: FontMode.SYSTEM,
            language = validLanguage(values[LANGUAGE] ?: "ru"),
            textScale = validScale(values[TEXT_SCALE] ?: 1f),
            animations = values[ANIMATIONS] ?: true,
            deepThink = values[DEEP_THINK] ?: false,
            search = values[SEARCH] ?: false,
            wallpaper = WallpaperSettings(
                kind = WallpaperKind.entries.firstOrNull { it.name == values[WALLPAPER_KIND] } ?: WallpaperKind.NONE,
                photoId = values[WALLPAPER_PHOTO],
                dimming = values[WALLPAPER_DIMMING] ?: .35f,
                blur = values[WALLPAPER_BLUR] ?: 0f,
            ).normalized(),
            hideInstructions = values[HIDE_INSTRUCTIONS] ?: false,
            photoCacheLimit = PhotoCacheLimit.entries.firstOrNull { it.name == values[PHOTO_CACHE_LIMIT] }
                ?: PhotoCacheLimit.MB_500,
            welcomeComplete = values[WELCOME] ?: false,
            customInstructions = values[INSTRUCTIONS]?.let { Json.decodeFromString<List<ChatInstruction>>(it) }
                .orEmpty().filter { it.isValid() && it.id.startsWith("custom.") }.distinctBy { it.id },
            glass = GlassSettings(
                enabled = values[GLASS_ENABLED] ?: false,
                blur = values[GLASS_BLUR] ?: 6f,
                refraction = values[GLASS_REFRACTION] ?: .55f,
                transparency = values[GLASS_TRANSPARENCY] ?: .35f,
            ).normalized(),
        )
    }

    suspend fun updateTheme(value: ThemeMode) { store.edit { it[THEME] = value.name } }
    suspend fun updateFont(value: FontMode) { store.edit { it[FONT] = value.name } }
    suspend fun updateLanguage(value: String) { store.edit { it[LANGUAGE] = validLanguage(value) } }
    suspend fun updateTextScale(value: Float) { store.edit { it[TEXT_SCALE] = validScale(value) } }
    suspend fun updateAnimations(value: Boolean) { store.edit { it[ANIMATIONS] = value } }
    suspend fun toggleDeepThink() { store.edit { it[DEEP_THINK] = !(it[DEEP_THINK] ?: false) } }
    suspend fun toggleSearch() { store.edit { it[SEARCH] = !(it[SEARCH] ?: false) } }
    suspend fun updateWallpaper(value: WallpaperSettings) {
        val wallpaper = value.normalized()
        store.edit {
            it[WALLPAPER_KIND] = wallpaper.kind.name
            if (wallpaper.photoId == null) it.remove(WALLPAPER_PHOTO) else it[WALLPAPER_PHOTO] = wallpaper.photoId
            it[WALLPAPER_DIMMING] = wallpaper.dimming
            it[WALLPAPER_BLUR] = wallpaper.blur
        }
    }
    suspend fun updateHideInstructions(value: Boolean) { store.edit { it[HIDE_INSTRUCTIONS] = value } }
    suspend fun updatePhotoCacheLimit(value: PhotoCacheLimit) { store.edit { it[PHOTO_CACHE_LIMIT] = value.name } }
    suspend fun updateGlass(value: GlassSettings) {
        val glass = value.normalized()
        store.edit {
            it[GLASS_ENABLED] = glass.enabled
            it[GLASS_BLUR] = glass.blur
            it[GLASS_REFRACTION] = glass.refraction
            it[GLASS_TRANSPARENCY] = glass.transparency
            it.remove(LEGACY_GLASS_COLORS)
        }
    }
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

    override suspend fun save(key: String, instruction: ChatInstruction) {
        store.edit { it[stringPreferencesKey("applied_instruction_" + key)] = Json.encodeToString(instruction) }
    }

    override suspend fun load(key: String): ChatInstruction? =
        store.data.first()[stringPreferencesKey("applied_instruction_" + key)]?.let { Json.decodeFromString<ChatInstruction>(it) }

    private companion object {
        val INSTRUCTIONS = stringPreferencesKey("custom_instructions_v1")
        val THEME = stringPreferencesKey("theme")
        val FONT = stringPreferencesKey("font")
        val LANGUAGE = stringPreferencesKey("language")
        val TEXT_SCALE = floatPreferencesKey("text_scale")
        val ANIMATIONS = booleanPreferencesKey("animations")
        val DEEP_THINK = booleanPreferencesKey("deep_think")
        val SEARCH = booleanPreferencesKey("search")
        val WALLPAPER_KIND = stringPreferencesKey("wallpaper_kind")
        val WALLPAPER_PHOTO = stringPreferencesKey("wallpaper_photo")
        val WALLPAPER_DIMMING = floatPreferencesKey("wallpaper_dimming")
        val WALLPAPER_BLUR = floatPreferencesKey("wallpaper_blur")
        val HIDE_INSTRUCTIONS = booleanPreferencesKey("hide_instructions")
        val PHOTO_CACHE_LIMIT = stringPreferencesKey("photo_cache_limit")
        val WELCOME = booleanPreferencesKey("welcome_complete")
        val GLASS_ENABLED = booleanPreferencesKey("composer_glass_enabled")
        val GLASS_BLUR = floatPreferencesKey("composer_glass_blur")
        val GLASS_REFRACTION = floatPreferencesKey("composer_glass_refraction")
        val GLASS_TRANSPARENCY = floatPreferencesKey("composer_glass_transparency")
        val LEGACY_GLASS_COLORS = booleanPreferencesKey("composer_glass_colors")
        fun validLanguage(value: String) = if (value == "en") "en" else "ru"
        fun validScale(value: Float) = if (value.isFinite()) value.coerceIn(0.7f, 1.5f) else 1f
    }
}
