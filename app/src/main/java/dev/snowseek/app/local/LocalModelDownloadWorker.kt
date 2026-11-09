package dev.snowseek.app.local

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.IOException
import kotlinx.coroutines.CancellationException

class LocalModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val modelId = inputData.getString(KEY_MODEL_ID)
            ?: return Result.failure(workDataOf(KEY_ERROR to "Не указана модель для загрузки"))
        val entry = ModelCatalog.entries[modelId]
            ?: return Result.failure(workDataOf(KEY_ERROR to "Модель отсутствует в каталоге"))
        val repository = LocalModelRepository(applicationContext)
        var lastPercent = -1
        var lastUpdateAt = 0L

        return try {
            setForeground(foregroundInfo(entry.name, 0, verifying = false))
            repository.download(
                entry,
                onProgress = { bytes ->
                    val percent = (bytes * 100 / entry.sizeBytes).toInt().coerceIn(0, 100)
                    val now = System.currentTimeMillis()
                    if (percent != lastPercent || now - lastUpdateAt >= 1_000L || bytes >= entry.sizeBytes) {
                        setProgress(workDataOf(KEY_DOWNLOADED_BYTES to bytes, KEY_VERIFYING to false))
                        setForeground(foregroundInfo(entry.name, percent, verifying = false))
                        lastPercent = percent
                        lastUpdateAt = now
                    }
                },
                onVerifying = {
                    setProgress(workDataOf(KEY_DOWNLOADED_BYTES to entry.sizeBytes, KEY_VERIFYING to true))
                    setForeground(foregroundInfo(entry.name, 100, verifying = true))
                },
            )
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            Result.retry()
        } catch (failure: Exception) {
            Result.failure(workDataOf(KEY_ERROR to (failure.message ?: "Не удалось скачать модель")))
        }
    }

    private fun foregroundInfo(modelName: String, percent: Int, verifying: Boolean): ForegroundInfo {
        ensureNotificationChannel()
        val text = if (verifying) "Проверка файла" else "$modelName · $percent%"
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Загрузка модели SnowSeek")
            .setContentText(text)
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                0,
                "Отмена",
                WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
            )
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Загрузка моделей", NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        const val TAG = "local-model-download"
        const val KEY_MODEL_ID = "model-id"
        const val KEY_DOWNLOADED_BYTES = "downloaded-bytes"
        const val KEY_VERIFYING = "verifying"
        const val KEY_ERROR = "error"
        const val CHANNEL_ID = "model-downloads"
        private const val NOTIFICATION_ID = 7301

        fun uniqueWorkName(modelId: String) = "$TAG:$modelId"
        fun modelTag(modelId: String) = "$TAG:$modelId"
    }
}
