package dev.snowseek.app.ui.components

import android.graphics.Bitmap
import android.net.Uri
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
import dev.snowseek.app.data.PhotoCache
import dev.snowseek.app.data.decodeAttachment
import dev.snowseek.app.data.decodeCachedPhoto
import dev.snowseek.app.data.decodePreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import kotlin.coroutines.coroutineContext

@Composable
internal fun PhotoAttachment(id: String, uri: Uri?, name: String, modifier: Modifier = Modifier, previewUrl: String? = null) {
    val w = LocalWords.current
    var open by remember(uri, previewUrl) { mutableStateOf(false) }
    val thumbnail = attachmentImage(id, uri, previewUrl, 256, "t")
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
        val preview = attachmentImage(id, uri, previewUrl, 1600, "p")
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

internal val LocalPhotoCache = staticCompositionLocalOf<PhotoCache?> { null }

@Composable
private fun attachmentImage(id: String, uri: Uri?, previewUrl: String?, maxSide: Int, variant: String): AttachmentImage {
    val resolver = LocalContext.current.contentResolver
    val cache = LocalPhotoCache.current
    val image by produceState(AttachmentImage(), id, uri, previewUrl, maxSide, resolver, cache) {
        value = AttachmentImage()
        val ticket = cache?.ticket()
        val bitmap = withContext(Dispatchers.IO) {
            fun cached(quality: String) = ticket?.let {
                cache.read(it, id, quality) { file -> decodeOrNull { decodeCachedPhoto(file, maxSide) } }
            }
            val local = cached("original") ?: cached(variant)
                ?: if (variant == "t") cached("p") else null
            local ?: uri?.let { decodeOrNull { decodeAttachment(resolver, it, maxSide) } }
            ?: previewUrl?.let { url ->
                decodeOrNull {
                    val bytes = downloadPreview(url, variant) ?: return@decodeOrNull null
                    val decoded = decodePreview(bytes, maxSide)
                    if (decoded != null && ticket != null) {
                        coroutineContext.ensureActive()
                        cache.write(ticket, id, variant, bytes.size.toLong()) { bytes.inputStream() }
                    }
                    decoded
                }
            } ?: if (variant == "p") cached("t") else null
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
