package dev.snowseek.app.local

import android.app.ActivityManager
import android.content.Context
import android.os.Build

internal data class DeviceModelFit(
    val title: String,
    val explanation: String,
    val measured: Boolean,
)

internal object DeviceModelFitEvaluator {
    private const val MIB = 1024L * 1024L
    private const val GGUF_RUNTIME_FACTOR = 1.25
    private const val LITERT_RUNTIME_FACTOR = 1.55
    private const val GGUF_CONTEXT_BYTES = 256L * MIB
    private const val LITERT_CONTEXT_BYTES = 384L * MIB

    fun evaluate(
        context: Context,
        entry: ModelCatalogEntry?,
        modelBytes: Long? = entry?.sizeBytes,
        format: LocalModelFormat? = entry?.format,
    ): DeviceModelFit {
        val abiSupported = Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }
        if (!abiSupported) return DeviceModelFit("Не поддерживается", "Нужен Android arm64 (arm64-v8a).", false)

        val fileBytes = modelBytes?.takeIf { it > 0 }
        if (fileBytes == null || format == null) {
            return DeviceModelFit("Нет оценки", "Не удалось определить размер файла модели.", false)
        }

        val activityManager = context.getSystemService(ActivityManager::class.java)
            ?: return DeviceModelFit("Нет оценки", "Android не сообщил данные о памяти устройства.", false)
        val memoryInfo = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val total = memoryInfo.totalMem
        val available = memoryInfo.availMem
        if (total <= 0L || available <= 0L) {
            return DeviceModelFit("Нет оценки", "Android не сообщил данные о памяти устройства.", false)
        }

        val factor = if (format == LocalModelFormat.GGUF) GGUF_RUNTIME_FACTOR else LITERT_RUNTIME_FACTOR
        val contextBytes = if (format == LocalModelFormat.GGUF) GGUF_CONTEXT_BYTES else LITERT_CONTEXT_BYTES
        val profileFloor = entry?.minimumMemoryBytes ?: 0L
        val estimatedBytes = maxOf((fileBytes * factor).toLong() + contextBytes, profileFloor)
        val requiredMiB = ceilMiB(estimatedBytes)
        val availableMiB = available / MIB
        val totalMiB = total / MIB

        val title = when {
            available < estimatedBytes -> "Высокая нагрузка"
            available >= estimatedBytes * 1.5 && total >= estimatedBytes * 2 -> "Низкая нагрузка"
            available >= estimatedBytes && total >= estimatedBytes * 1.3 -> "Средняя нагрузка"
            else -> "Высокая нагрузка"
        }
        val availability = "Свободно ${availableMiB} МБ из ${totalMiB} МБ"
        val estimate = "Модели нужно около ${requiredMiB} МБ RAM"
        val note = if (available < estimatedBytes) "Свободной памяти сейчас может не хватить." else "Это примерный расчёт для контекста 2048 токенов."
        return DeviceModelFit(title, "$estimate · $availability. $note", true)
    }

    private fun ceilMiB(bytes: Long): Long = (bytes + MIB - 1L) / MIB
}
