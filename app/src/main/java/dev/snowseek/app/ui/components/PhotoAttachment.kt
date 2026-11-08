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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

@Composable
internal fun PhotoAttachment(uri: Uri?, name: String, modifier: Modifier = Modifier, previewUrl: String? = null) {
    val w = LocalWords.current
    var open by remember(uri, previewUrl) { mutableStateOf(false) }
    val thumbnail = attachmentImage(uri, previewUrl, 256, "t")
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
        val preview = attachmentImage(uri, previewUrl, 1600, "p")
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
private fun attachmentImage(uri: Uri?, previewUrl: String?, maxSide: Int, variant: String): AttachmentImage {
    val resolver = LocalContext.current.contentResolver
    val image by produceState(AttachmentImage(), uri, previewUrl, maxSide, resolver) {
        value = AttachmentImage()
        val bitmap = withContext(Dispatchers.IO) {
            val local = uri?.let { decodeOrNull { decodeAttachment(resolver, it, maxSide) } }
            local ?: previewUrl?.let { url ->
                decodeOrNull { downloadPreview(url, variant)?.let { decodePreview(it, maxSide) } }
            }
        }
        value = AttachmentImage(bitmap, loading = false)
    }
    return image
}

private inline fun decodeOrNull(block: () -> Bitmap?): Bitmap? = try {
    block()
} catch (_: java.io.IOException) {
    null
} catch (_: SecurityException) {
    null
} catch (_: IllegalArgumentException) {
    null
}

private suspend fun downloadPreview(url: String, variant: String): ByteArray? {
    val base = Uri.parse(url)
    val request = if (base.getQueryParameter("ty") == null) {
        base.buildUpon().appendQueryParameter("ty", variant).build().toString()
    } else url
    val connection = URI(request).toURL().openConnection() as HttpURLConnection
    connection.instanceFollowRedirects = false
    connection.connectTimeout = 10_000
    connection.readTimeout = 15_000
    connection.useCaches = false
    connection.setRequestProperty("Accept", "image/*")
    val limit = 12 * 1024 * 1024
    try {
        if (connection.responseCode != HttpURLConnection.HTTP_OK || connection.contentLengthLong > limit ||
            connection.contentType?.startsWith("image/", ignoreCase = true) != true) return null
        connection.inputStream.use { stream ->
            val data = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val count = stream.read(buffer)
                if (count < 0) break
                if (count == 0 || data.size() + count > limit) return null
                data.write(buffer, 0, count)
            }
            return data.toByteArray()
        }
    } finally {
        connection.disconnect()
    }
}

private fun decodePreview(bytes: ByteArray, maxSide: Int): Bitmap? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        return decodeSampled(ImageDecoder.createSource(ByteBuffer.wrap(bytes)), maxSide)
    }
    return decodeSampled(maxSide) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, it) }
}

private fun decodeAttachment(resolver: ContentResolver, uri: Uri, maxSide: Int): Bitmap? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        return decodeSampled(ImageDecoder.createSource(resolver, uri), maxSide)
    }
    return decodeSampled(maxSide) { options ->
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }
}

@androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
private fun decodeSampled(source: ImageDecoder.Source, maxSide: Int): Bitmap =
    ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
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

private inline fun decodeSampled(maxSide: Int, decode: (BitmapFactory.Options) -> Bitmap?): Bitmap? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    decode(options)
    val longest = maxOf(options.outWidth, options.outHeight)
    if (longest <= 0) return null
    options.inJustDecodeBounds = false
    options.inSampleSize = 1
    while (longest / options.inSampleSize > maxSide) options.inSampleSize *= 2
    return decode(options)
}
