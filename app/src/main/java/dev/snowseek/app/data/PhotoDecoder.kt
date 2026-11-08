package dev.snowseek.app.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.roundToInt

internal fun decodeCachedPhoto(file: File, maxSide: Int): Bitmap? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        return decodeSampled(ImageDecoder.createSource(file), maxSide)
    }
    return decodeSampled(maxSide) { BitmapFactory.decodeFile(file.path, it) }
}

internal fun decodePreview(bytes: ByteArray, maxSide: Int): Bitmap? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        return decodeSampled(ImageDecoder.createSource(ByteBuffer.wrap(bytes)), maxSide)
    }
    return decodeSampled(maxSide) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, it) }
}

internal fun decodeAttachment(resolver: ContentResolver, uri: Uri, maxSide: Int): Bitmap? {
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
