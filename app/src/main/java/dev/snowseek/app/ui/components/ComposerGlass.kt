package dev.snowseek.app.ui.components

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.OpticalSizeValue
import dev.chrisbanes.haze.glass.hazeGlass
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import dev.snowseek.app.settings.GlassSettings
import dev.snowseek.app.ui.LocalWords
import kotlin.math.roundToInt

/** Measures the panel first so the last message and jump button clear it as its height changes. */
@Composable
internal fun ChatComposerLayout(
    modifier: Modifier,
    overlay: Boolean,
    composer: @Composable () -> Unit,
    content: @Composable (composerInset: Dp) -> Unit,
) {
    SubcomposeLayout(modifier) { constraints ->
        val panel = subcompose("composer", composer).single().measure(constraints.copy(minHeight = 0))
        val remaining = (constraints.maxHeight - panel.height).coerceAtLeast(0)
        val contentHeight = if (overlay) constraints.maxHeight else remaining
        val inset = if (overlay) panel.height.toDp() else 0.dp
        val chat = subcompose("chat") { content(inset) }.single()
            .measure(Constraints.fixed(constraints.maxWidth, contentHeight))
        layout(constraints.maxWidth, constraints.maxHeight) {
            chat.placeRelative(0, 0)
            panel.placeRelative(0, remaining)
        }
    }
}

private val composerShape = RoundedCornerShape(26.dp)

@Composable
internal fun ComposerSurface(
    settings: GlassSettings,
    backdrop: HazeState?,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val glass = settings.enabled && backdrop != null
    val style = remember(settings, colors.surface, colors.background) {
        GlassStyle.regular.then {
            shape(composerShape)
            backgroundColor(colors.background)
            tint(colors.surface.copy(alpha = 1f - settings.transparency))
            optics {
                copy(
                    blurRadius = OpticalSizeValue.Fixed(settings.blur.dp),
                    refractionStrength = settings.refraction,
                )
            }
            chromaMultiplier(if (settings.enhanceColors) 1.3f else 1f)
        }
    }
    Surface(
        shape = composerShape,
        color = if (glass) Color.Transparent else colors.surface,
        contentColor = colors.onSurface,
        border = BorderStroke(1.dp, colors.outlineVariant),
        shadowElevation = 3.dp,
    ) {
        val effect = if (glass) Modifier.hazeGlass(HazeInput.Sources(requireNotNull(backdrop)), style) else Modifier
        Box(effect) { content() }
    }
}

@Composable
internal fun GlassSetting(settings: GlassSettings, onApply: (GlassSettings) -> Unit) {
    val w = LocalWords.current
    var open by rememberSaveable { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(w.text("Стекло", "Glass")) },
        supportingContent = {
            Text(if (settings.enabled) w.text("Включено для панели ввода", "On for the message composer")
            else w.text("Выключено", "Off"))
        },
        trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null) },
        modifier = Modifier.clickable(role = Role.Button) { open = true },
    )
    if (!open) return

    var enabled by rememberSaveable { mutableStateOf(settings.enabled) }
    var blur by rememberSaveable { mutableFloatStateOf(settings.blur) }
    var refraction by rememberSaveable { mutableFloatStateOf(settings.refraction) }
    var transparency by rememberSaveable { mutableFloatStateOf(settings.transparency) }
    var enhanceColors by rememberSaveable { mutableStateOf(settings.enhanceColors) }
    val preview = GlassSettings(enabled, blur, refraction, transparency, enhanceColors)
    val supportsOptics = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(w.text("Стекло панели ввода", "Composer glass")) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(w.text("Включить стекло", "Enable glass"), Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                Text(w.text("Предпросмотр", "Preview"), style = MaterialTheme.typography.labelMedium)
                GlassPreview(preview)
                if (enabled) {
                    if (supportsOptics) {
                        GlassSlider(w.text("Размытие", "Blur"), "${blur.roundToInt()} dp", blur, 0f..24f) { blur = it }
                        GlassSlider(w.text("Преломление", "Refraction"), "${(refraction * 100).roundToInt()}%", refraction, 0f..1f) { refraction = it }
                    } else {
                        Text(
                            w.text("Полный эффект стекла доступен с Android 13.", "The full glass effect requires Android 13 or later."),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    GlassSlider(w.text("Прозрачность", "Transparency"), "${(transparency * 100).roundToInt()}%", transparency, 0f..0.60f) { transparency = it }
                    if (supportsOptics) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(w.text("Усилить цвета", "Enhance colors"), Modifier.weight(1f))
                            Switch(checked = enhanceColors, onCheckedChange = { enhanceColors = it })
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(preview); open = false }) { Text(w.text("Применить", "Apply")) } },
        dismissButton = { TextButton(onClick = { open = false }) { Text(w.text("Отмена", "Cancel")) } },
    )
}

@Composable
private fun GlassSlider(label: String, description: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Text("$label · $description", style = MaterialTheme.typography.labelLarge)
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = label
                stateDescription = description
            },
        )
    }
}

@Composable
private fun GlassPreview(settings: GlassSettings) {
    val w = LocalWords.current
    val haze = rememberHazeState()
    val source = if (settings.enabled) Modifier.hazeSource(haze) else Modifier
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))) {
        Column(
            Modifier.fillMaxWidth().then(source).background(MaterialTheme.colorScheme.background).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(w.text("Пример диалога", "Sample conversation"), style = MaterialTheme.typography.bodyMedium)
            Surface(
                modifier = Modifier.align(Alignment.End).padding(bottom = 8.dp),
                shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Text(w.text("Новое сообщение", "New message"), Modifier.padding(12.dp))
            }
            Spacer(Modifier.height(16.dp))
        }
        Box(Modifier.align(Alignment.BottomCenter).padding(8.dp)) {
            ComposerSurface(settings, haze) {
                Text(
                    w.text("Напишите сообщение…", "Write a message…"),
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
