package dev.snowseek.app.local

import java.io.File
import java.io.RandomAccessFile
import java.io.DataInputStream
import java.io.EOFException

internal data class ModelInspection(
    val format: LocalModelFormat,
    val displayName: String,
)

internal object ModelFileInspector {
    fun inspect(file: File, expectedFormat: LocalModelFormat? = null): ModelInspection {
        require(file.isFile && file.length() > 16) { "Файл пуст или недоступен" }
        val format = expectedFormat ?: LocalModelFormat.entries.firstOrNull { it.extension == file.extension.lowercase() }
            ?: error("Выберите файл .gguf или .litertlm")
        return try {
            when (format) {
                LocalModelFormat.GGUF -> inspectGguf(file)
                LocalModelFormat.LITERTLM -> inspectLiteRt(file)
            }
        } catch (_: EOFException) {
            error("Файл модели обрезан: заголовок не заполнен полностью")
        }
    }

    private fun inspectGguf(file: File): ModelInspection = RandomAccessFile(file, "r").use { input ->
        val magic = ByteArray(4)
        input.readFully(magic)
        require(magic.contentEquals(byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte()))) {
            "В файле .gguf не найден заголовок GGUF"
        }
        val version = Integer.reverseBytes(input.readInt())
        val tensors = java.lang.Long.reverseBytes(input.readLong())
        val metadata = java.lang.Long.reverseBytes(input.readLong())
        require(version in 2..3) { "Версия GGUF $version не поддерживается; нужна версия 2 или 3" }
        require(tensors in 1L..1_000_000L && metadata in 1L..100_000L) { "Заголовок GGUF повреждён или некорректен" }
        ModelInspection(LocalModelFormat.GGUF, file.nameWithoutExtension)
    }

    private fun inspectLiteRt(file: File): ModelInspection = DataInputStream(file.inputStream().buffered()).use { input ->
        val magic = ByteArray(8)
        input.readFully(magic)
        require(magic.contentEquals("LITERTLM".toByteArray(Charsets.US_ASCII))) {
            "В файле .litertlm не найден заголовок LiteRT-LM"
        }
        ModelInspection(LocalModelFormat.LITERTLM, file.nameWithoutExtension)
    }
}
