package dev.snowseek.app.ui.components

import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.snowseek.app.data.WallpaperStore
import dev.snowseek.app.data.decodeCachedPhoto
import dev.snowseek.app.settings.WallpaperKind
import dev.snowseek.app.settings.WallpaperSettings
import dev.snowseek.app.ui.LocalWords
import dev.snowseek.app.ui.SnowSeekViewModel
import dev.snowseek.app.ui.Words
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

internal fun Words.wallpaperName(kind: WallpaperKind): String = when (kind) {
    WallpaperKind.NONE -> text("Без обоев", "No wallpaper")
    WallpaperKind.SNOW -> text("Снежинки", "Snowflakes")
    WallpaperKind.WAVES -> text("Волны", "Waves")
    WallpaperKind.PHOTO -> text("Своё фото", "Your photo")
}

@Composable
internal fun ChatWallpaper(settings: WallpaperSettings, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    if (settings.kind == WallpaperKind.NONE) {
        Box(modifier.background(colors.background))
        return
    }
    val context = LocalContext.current
    val photo by produceState<Bitmap?>(null, settings.kind, settings.photoId, context) {
        value = null
        if (settings.kind == WallpaperKind.PHOTO) {
            value = withContext(Dispatchers.IO) {
                try {
                    WallpaperStore.photoFile(context, settings.photoId)?.let { decodeCachedPhoto(it, 2048) }
                } catch (_: java.io.IOException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                } catch (_: SecurityException) {
                    null
                }
            }
        }
    }
    Box(modifier.background(colors.background)) {
        val blur = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(settings.blur.dp) else Modifier
        Box(Modifier.matchParentSize().then(blur)) {
            when (settings.kind) {
                WallpaperKind.PHOTO -> photo?.let {
                    Image(it.asImageBitmap(), null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
                }
                WallpaperKind.SNOW, WallpaperKind.WAVES -> WallpaperPattern(settings.kind, colors.onBackground.copy(alpha = .16f))
                WallpaperKind.NONE -> Unit
            }
        }
        Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = settings.dimming)))
    }
}

@Composable
private fun WallpaperPattern(kind: WallpaperKind, color: Color) {
    Canvas(Modifier.fillMaxSize()) {
        val spacing = 72.dp.toPx()
        val stroke = 1.dp.toPx()
        if (kind == WallpaperKind.SNOW) {
            var y = -spacing
            var row = 0
            while (y < size.height + spacing) {
                var x = if (row % 2 == 0) -spacing else -spacing / 2
                while (x < size.width + spacing) {
                    val radius = 9.dp.toPx()
                    drawLine(color, Offset(x, y - radius), Offset(x, y + radius), stroke)
                    drawLine(color, Offset(x - radius * .86f, y - radius * .5f),
                        Offset(x + radius * .86f, y + radius * .5f), stroke)
                    drawLine(color, Offset(x - radius * .86f, y + radius * .5f),
                        Offset(x + radius * .86f, y - radius * .5f), stroke)
                    x += spacing
                }
                row++
                y += spacing
            }
        } else {
            var y = -spacing
            while (y < size.height + spacing) {
                val path = Path()
                path.moveTo(-spacing, y)
                var x = -spacing
                while (x < size.width + spacing) {
                    path.cubicTo(x + spacing * .25f, y - spacing * .3f,
                        x + spacing * .75f, y + spacing * .3f, x + spacing, y)
                    x += spacing
                }
                drawPath(path, color, style = Stroke(stroke))
                y += spacing / 2
            }
        }
    }
}

@Composable
internal fun WallpaperEditor(settings: WallpaperSettings, model: SnowSeekViewModel, importing: Boolean, onBack: () -> Unit) {
    val w = LocalWords.current
    var preview by remember(settings) { mutableStateOf(settings) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(model::wallpaperPhoto)
    }
    AppearancePage(w.text("Обои чата", "Chat wallpaper"), onBack) {
        Box(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(16.dp))) {
            ChatWallpaper(preview, Modifier.matchParentSize())
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(Modifier.align(Alignment.End), shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primaryContainer) {
                    Text(w.text("Пример сообщения", "Sample message"), Modifier.padding(10.dp),
                        style = MaterialTheme.typography.bodyMedium)
                }
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .94f)) {
                    Text(w.text("Так выглядит ответ на выбранном фоне.", "This is how a reply looks on this wallpaper."),
                        Modifier.padding(10.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (importing) LinearProgressIndicator(Modifier.fillMaxWidth())
        Column(Modifier.selectableGroup()) {
            WallpaperKind.entries.forEach { kind ->
                AppearanceChoice(w.wallpaperName(kind), preview.kind == kind, {
                    if (!importing) {
                        if (kind == WallpaperKind.PHOTO && preview.photoId == null) {
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        } else {
                            preview = preview.copy(kind = kind)
                            model.wallpaper(preview)
                        }
                    }
                })
            }
        }
        if (preview.photoId != null) {
            TextButton(enabled = !importing, onClick = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Text(w.text("Выбрать другое фото", "Choose another photo")) }
        }
        if (preview.kind != WallpaperKind.NONE) {
            AppearanceSlider(
                w.text("Затемнение", "Dimming"), "${(preview.dimming * 100).roundToInt()}%",
                preview.dimming, 0f..1f,
                onChange = { if (!importing) preview = preview.copy(dimming = it) },
                onFinished = { model.wallpaper(preview) },
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                AppearanceSlider(
                    w.text("Размытие", "Blur"), "${preview.blur.roundToInt()} dp",
                    preview.blur, 0f..24f,
                    onChange = { if (!importing) preview = preview.copy(blur = it) },
                    onFinished = { model.wallpaper(preview) },
                )
            } else Text(w.text("Размытие обоев доступно с Android 12.", "Wallpaper blur requires Android 12 or later."),
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
