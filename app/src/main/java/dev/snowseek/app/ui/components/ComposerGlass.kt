package dev.snowseek.app.ui.components

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.SubcomposeLayout
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
    val blurRadius = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) settings.blur.dp else 0.dp
    val refraction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) settings.refraction else 0f
    Surface(
        shape = composerShape,
        color = if (settings.enabled) Color.Transparent else colors.surface,
        contentColor = colors.onSurface,
        border = BorderStroke(1.dp, colors.outlineVariant),
        shadowElevation = 3.dp,
    ) {
        Box {
            if (settings.enabled) {
                if (backdrop != null && (blurRadius > 0.dp || refraction > 0f)) {
                    val style = remember(refraction, colors.background) {
                        GlassStyle.regular.then {
                            shape(composerShape)
                            backgroundColor(colors.background)
                            tint(Color.Transparent)
                            optics {
                                copy(blurRadius = OpticalSizeValue.Fixed(0.dp), refractionStrength = refraction)
                            }
                            chromaMultiplier(1f)
                        }
                    }
                    // Blur the sampled backdrop even when Glass uses its non-refracting renderer.
                    Box(Modifier.matchParentSize().blur(blurRadius).hazeGlass(HazeInput.Sources(backdrop), style))
                }
                Box(Modifier.matchParentSize().background(colors.surface.copy(alpha = 1f - settings.transparency)))
            }
            content()
        }
    }
}

@Composable
internal fun GlassEditor(settings: GlassSettings, onBack: () -> Unit, onApply: (GlassSettings) -> Unit) {
    val w = LocalWords.current
    var enabled by rememberSaveable { mutableStateOf(settings.enabled) }
    var blur by rememberSaveable { mutableFloatStateOf(settings.blur) }
    var refraction by rememberSaveable { mutableFloatStateOf(settings.refraction) }
    var transparency by rememberSaveable { mutableFloatStateOf(settings.transparency) }
    val preview = GlassSettings(enabled, blur, refraction, transparency)
    val supportsBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val supportsRefraction = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    AppearancePage(
        title = w.text("Стекло панели ввода", "Composer glass"),
        onBack = onBack,
        bottomBar = {
            Surface {
                Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Button(onClick = { onApply(preview); onBack() }, modifier = Modifier.align(Alignment.CenterEnd),
                        enabled = preview != settings) {
                        Text(w.text("Применить", "Apply"))
                    }
                }
            }
        },
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(w.text("Включить стекло", "Enable glass"), Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = { enabled = it })
        }
        Text(w.text("Предпросмотр", "Preview"), style = MaterialTheme.typography.labelMedium)
        GlassPreview(preview)
        if (enabled) {
            if (supportsBlur) {
                AppearanceSlider(w.text("Размытие", "Blur"), "${blur.roundToInt()} dp", blur, 0f..24f, onChange = { blur = it })
            }
            if (supportsRefraction) {
                AppearanceSlider(w.text("Преломление", "Refraction"), "${(refraction * 100).roundToInt()}%", refraction, 0f..1f,
                    onChange = { refraction = it })
            } else {
                Text(
                    if (supportsBlur) w.text("Преломление доступно с Android 13.", "Refraction requires Android 13 or later.")
                    else w.text("Размытие доступно с Android 12, преломление — с Android 13.",
                        "Blur requires Android 12 or later, and refraction requires Android 13 or later."),
                    style = MaterialTheme.typography.bodySmall)
            }
            AppearanceSlider(w.text("Прозрачность", "Transparency"), "${(transparency * 100).roundToInt()}%", transparency, 0f..1f,
                onChange = { transparency = it })
        }
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(w.text("Текст над панелью", "Text above the composer"), style = MaterialTheme.typography.bodyMedium)
            Surface(
                modifier = Modifier.fillMaxWidth().heightIn(min = 84.dp),
                shape = RoundedCornerShape(12.dp),
                color = lerp(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.surface, .25f),
            ) {
                Box(Modifier.padding(10.dp), contentAlignment = Alignment.CenterStart) {
                    Text(w.text("Текст за стеклом · 123\nСравните резкость букв", "Text behind the glass · 123\nCompare the letter edges"),
                        style = MaterialTheme.typography.bodyLarge)
                }
            }
            Text(w.text("Текст под панелью", "Text below the composer"), style = MaterialTheme.typography.bodyMedium)
        }
        Box(Modifier.align(Alignment.Center).padding(horizontal = 8.dp)) {
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
