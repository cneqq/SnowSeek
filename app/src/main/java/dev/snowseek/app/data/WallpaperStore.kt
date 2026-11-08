package dev.snowseek.app.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** One private wallpaper copy, independent of temporary Photo Picker grants and the photo cache. */
internal class WallpaperStore(private val context: Context) {
    private val directory = File(context.noBackupFilesDir, "wallpapers")

    suspend fun importPhoto(uri: Uri): String = withContext(Dispatchers.IO) {
        require(uri.scheme == "content")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Wallpaper directory unavailable")
        val id = UUID.randomUUID().toString()
        val file = File(directory, "$id.jpg")
        try {
            val bitmap = decodeAttachment(context.contentResolver, uri, 2048)
                ?: throw IOException("Photo could not be decoded")
            try {
                coroutineContext.ensureActive()
                file.outputStream().use {
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) throw IOException("Photo could not be saved")
                }
            } finally {
                bitmap.recycle()
            }
            coroutineContext.ensureActive()
            id
        } catch (failure: Exception) {
            file.delete()
            throw failure
        }
    }

    suspend fun discard(id: String) = withContext(Dispatchers.IO) { photoFile(context, id)?.delete(); Unit }

    companion object {
        fun photoFile(context: Context, id: String?): File? =
            id?.takeIf { it.matches(Regex("[a-f0-9-]{36}")) }
                ?.let { File(context.noBackupFilesDir, "wallpapers/$it.jpg") }
    }
}
