package dev.snowseek.app.local

import android.app.ActivityManager
import android.content.Context
import android.os.Build

internal data class DeviceModelFit(val title: String, val explanation: String, val measured: Boolean)

internal object DeviceModelFitEvaluator {
    fun evaluate(context: Context, entry: ModelCatalogEntry?): DeviceModelFit {
        if (entry == null) return DeviceModelFit("Оценка примерная", "Нет профиля для импортированной модели.", false)
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val memoryInfo = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val available = memoryInfo.availMem
        val abiSupported = Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }
        if (!abiSupported) return DeviceModelFit("Не поддерживается", "Нужен Android arm64 (arm64-v8a).", false)
        val enough = memoryInfo.totalMem >= entry.minimumMemoryBytes * 2 && available >= entry.minimumMemoryBytes / 3
        return if (enough) {
            DeviceModelFit("Средняя нагрузка · оценка", "${entry.memoryEstimate}; реальная скорость зависит от телефона и контекста.", false)
        } else {
            DeviceModelFit("Высокая нагрузка · оценка", "Свободной памяти может не хватить. ${entry.memoryEstimate}; закройте тяжёлые приложения.", false)
        }
    }
}
