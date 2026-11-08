package dev.snowseek.app.ui.components

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.snowseek.app.ui.LocalWords
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
internal fun PhotoAttachment(uri: Uri, name: String, modifier: Modifier = Modifier) {
    val w = LocalWords.current
    var open by remember(uri) { mutableStateOf(false) }
    val thumbnail = attachmentImage(uri, 256)
    val bitmap = thumbnail.bitmap
    Surface(
        modifier.clip(RoundedCornerShape(12.dp)).clickable(
            role = Role.Button,
            onClickLabel = w.text("Открыть фото", "Open photo"),
            onClick = { open = true },
        ),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (bitmap != null) {
                Image(bitmap.asImageBitmap(), name, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
            } else Icon(Icons.Outlined.Image, name, Modifier.size(24.dp))
        }
    }
    if (open) {
        val preview = attachmentImage(uri, 1600)
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Box(Modifier.fillMaxWidth().heightIn(min = 96.dp, max = 480.dp), contentAlignment = Alignment.Center) {
                    val image = preview.bitmap
                    when {
                        image != null -> Image(image.asImageBitmap(), name, Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
                        preview.loading -> CircularProgressIndicator(Modifier.size(28.dp))
                        else -> Text(w.text("Не удалось открыть фото", "Could not open photo"))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text(w.text("Закрыть", "Close")) } },
        )
    }
}

private data class AttachmentImage(val bitmap: Bitmap? = null, val loading: Boolean = true)

@Composable
private fun attachmentImage(uri: Uri, maxSide: Int): AttachmentImage {
    val resolver = LocalContext.current.contentResolver
    val image by produceState(AttachmentImage(), uri, maxSide, resolver) {
        value = AttachmentImage()
        val bitmap = withContext(Dispatchers.IO) {
            try {
                decodeAttachment(resolver, uri, maxSide)
            } catch (_: java.io.IOException) {
                null
            } catch (_: SecurityException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }
        value = AttachmentImage(bitmap, loading = false)
    }
    return image
}

private fun decodeAttachment(resolver: ContentResolver, uri: Uri, maxSide: Int): Bitmap? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            val size = info.size
            val longest = maxOf(size.width, size.height)
            if (longest > maxSide) {
                val ratio = maxSide.toDouble() / longest
                decoder.setTargetSize(
                    (size.width * ratio).roundToInt().coerceAtLeast(1),
                    (size.height * ratio).roundToInt().coerceAtLeast(1),
                )
            }
        }
    }
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    val longest = maxOf(options.outWidth, options.outHeight)
    if (longest <= 0) return null
    options.inJustDecodeBounds = false
    options.inSampleSize = 1
    while (longest / options.inSampleSize > maxSide) options.inSampleSize *= 2
    return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
}
