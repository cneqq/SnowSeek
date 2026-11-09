package dev.snowseek.app.local

import android.content.Context
import android.net.Uri
import android.os.StatFs
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class LocalModelRepository(context: Context) {
    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "local-models").apply { mkdirs() }
    private val metadata = appContext.getSharedPreferences("local-model-library", Context.MODE_PRIVATE)

    init {
        val oldFile = File(root, "qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm")
        val first = ModelCatalog.entries.getValue("qwen3-06b-litert-int4")
        if (oldFile.isFile && metadata.getString("model:${oldFile.name}", null) == null) {
            putMetadata(oldFile, first.id, first.name, first.format, "Каталог · ${first.publisher}")
        }
    }

    fun catalog(): List<ModelCatalogEntry> = ModelCatalog.entries.values.toList()

    fun installed(): List<InstalledModel> = root.listFiles()
        .orEmpty()
        .filter { it.isFile && !it.name.endsWith(".part") }
        .mapNotNull { file -> metadata.getString("model:${file.name}", null)?.let { encoded ->
            val fields = encoded.split('|', limit = 4)
            if (fields.size != 4) return@let null
            val format = LocalModelFormat.entries.firstOrNull { it.name == fields[1] } ?: return@let null
            val entry = ModelCatalog.entries[fields[0]]
            val validation = if (fields[3] == "Импортирована") "Совместимость с движком проверена" else "SHA-256 проверена"
            InstalledModel(fields[0], fields[2], format, file, file.length(), fields[3], validation,
                entry?.memoryEstimate)
        } }
        .sortedBy { it.displayName.lowercase() }

    fun selectedId(): String? = metadata.getString("selected", null)

    fun chats(): List<SavedLocalChat> = runCatching {
        val stored = JSONArray(metadata.getString("chats", "[]"))
        (0 until stored.length()).mapNotNull { index ->
            val chat = stored.optJSONObject(index) ?: return@mapNotNull null
            val turns = chat.optJSONArray("turns") ?: JSONArray()
            SavedLocalChat(
                id = chat.optString("id"), modelId = chat.optString("modelId"),
                modelName = chat.optString("modelName"), title = chat.optString("title"),
                updatedAt = chat.optLong("updatedAt"),
                turns = (0 until turns.length()).mapNotNull { turnIndex ->
                    turns.optJSONObject(turnIndex)?.let { SavedLocalTurn(it.optString("question"), it.optString("answer")) }
                },
            ).takeIf { it.id.isNotBlank() && it.modelId.isNotBlank() }
        }.sortedByDescending(SavedLocalChat::updatedAt)
    }.getOrDefault(emptyList())

    fun saveChat(chat: SavedLocalChat) {
        val chats = (chats().filterNot { it.id == chat.id } + chat)
            .sortedByDescending(SavedLocalChat::updatedAt).take(100)
        val stored = JSONArray()
        chats.forEach { value ->
            val turns = JSONArray()
            value.turns.forEach { turn -> turns.put(JSONObject().put("question", turn.question).put("answer", turn.answer)) }
            stored.put(JSONObject().put("id", value.id).put("modelId", value.modelId)
                .put("modelName", value.modelName).put("title", value.title)
                .put("updatedAt", value.updatedAt).put("turns", turns))
        }
        metadata.edit().putString("chats", stored.toString()).apply()
    }

    fun deleteChat(id: String) {
        val remaining = chats().filterNot { it.id == id }
        val stored = JSONArray()
        remaining.forEach { chat ->
            val turns = JSONArray()
            chat.turns.forEach { turns.put(JSONObject().put("question", it.question).put("answer", it.answer)) }
            stored.put(JSONObject().put("id", chat.id).put("modelId", chat.modelId)
                .put("modelName", chat.modelName).put("title", chat.title)
                .put("updatedAt", chat.updatedAt).put("turns", turns))
        }
        metadata.edit().putString("chats", stored.toString()).apply()
    }

    fun select(id: String) {
        require(installed().any { it.id == id }) { "Сначала добавьте модель в приложение" }
        metadata.edit().putString("selected", id).apply()
    }

    suspend fun download(entry: ModelCatalogEntry, onProgress: (Long) -> Unit, onVerifying: () -> Unit) = withContext(Dispatchers.IO) {
        require(ModelCatalog.entries[entry.id] == entry) { "Модель отсутствует в каталоге SnowSeek" }
        val destination = File(root, entry.fileName)
        val partial = File(root, "${entry.fileName}.part")
        downloadTo(entry.downloadUrl, entry.sizeBytes, partial, onProgress)
        onVerifying()
        ModelFileInspector.inspect(partial, entry.format)
        verifyAndCommit(partial, destination, entry.sizeBytes, entry.sha256)
        putMetadata(destination, entry.id, entry.name, entry.format, "Каталог · ${entry.publisher}")
    }

    suspend fun import(uri: Uri, onProgress: (Long) -> Unit, onVerifying: () -> Unit): InstalledModel = withContext(Dispatchers.IO) {
        val displayName = queryDisplayName(uri)
        val extension = displayName.substringAfterLast('.', "").lowercase()
        val format = LocalModelFormat.entries.firstOrNull { it.extension == extension }
            ?: error("Выберите файл .gguf или .litertlm")
        val id = "import-${UUID.randomUUID()}"
        val safeName = "${id}.${format.extension}"
        val destination = File(root, safeName)
        val partial = File(root, "$id.part.${format.extension}")
        try {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                RandomAccessFile(partial, "rw").use { output ->
                    output.setLength(0)
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        check(StatFs(root.absolutePath).availableBytes > buffer.size) { "Недостаточно места для копирования модели" }
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        onProgress(output.filePointer)
                    }
                }
            } ?: error("Не удалось открыть выбранный файл")
            onVerifying()
            val inspection = ModelFileInspector.inspect(partial, format)
            validateRuntime(partial, format)
            check(partial.renameTo(destination)) { "Не удалось сохранить модель в SnowSeek" }
            putMetadata(destination, id, inspection.displayName, format, "Импортирована")
            installed().first { it.id == id }
        } catch (failure: Exception) {
            partial.delete()
            destination.delete()
            throw failure
        }
    }

    fun delete(model: InstalledModel) {
        if (!model.file.delete() && model.file.exists()) error("Не удалось удалить файл модели")
        metadata.edit().remove("model:${model.file.name}")
            .also { if (selectedId() == model.id) it.remove("selected") }.apply()
    }

    private suspend fun validateRuntime(file: File, format: LocalModelFormat) {
        val engine: LocalInferenceEngine = when (format) {
            LocalModelFormat.LITERTLM -> LiteRtInferenceEngine(File(appContext.cacheDir, "litert-model-cache"))
            LocalModelFormat.GGUF -> LlamaCppEngine()
        }
        try {
            engine.load(file, format, contextTokens = 512)
        } finally {
            engine.close()
        }
    }

    private fun putMetadata(file: File, id: String, name: String, format: LocalModelFormat, source: String) {
        val value = listOf(id, format.name, name.replace('|', ' '), source.replace('|', ' ')).joinToString("|")
        metadata.edit().putString("model:${file.name}", value).apply()
    }

    private fun queryDisplayName(uri: Uri): String = appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Модель"

    private suspend fun downloadTo(url: String, expectedSize: Long, partial: File, onProgress: (Long) -> Unit) {
        var failures = 0
        while (partial.length() < expectedSize) {
            currentCoroutineContext().ensureActive()
            val offset = partial.length()
            check(StatFs(root.absolutePath).availableBytes >= expectedSize - offset + 16L * 1024 * 1024) {
                "Недостаточно свободного места для загрузки модели"
            }
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000; readTimeout = 30_000; instanceFollowRedirects = true
                setRequestProperty("User-Agent", "SnowSeek-Android/0.6.1")
                if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
            }
            try {
                val response = connection.responseCode
                check(response in 200..299) { "Сервер вернул HTTP $response" }
                val append = offset > 0 && response == HttpURLConnection.HTTP_PARTIAL
                if (append) {
                    val start = Regex("bytes (\\d+)-").find(connection.getHeaderField("Content-Range").orEmpty())?.groupValues?.get(1)?.toLongOrNull()
                    check(start == offset) { "Сервер возобновил загрузку с неверной позиции" }
                }
                RandomAccessFile(partial, "rw").use { output ->
                    if (!append) output.setLength(0) else output.seek(offset)
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            check(output.filePointer <= expectedSize) { "Размер модели превышает ожидаемый" }
                            onProgress(output.filePointer)
                        }
                    }
                }
                check(partial.length() == expectedSize) { "Загрузка прервалась; её можно продолжить" }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failures++
                if (failures >= 5) throw failure
                kotlinx.coroutines.delay(1_000L * failures)
            } finally { connection.disconnect() }
        }
    }

    private fun verifyAndCommit(partial: File, destination: File, expectedSize: Long, expectedHash: String) {
        if (partial.length() != expectedSize) error("Размер модели не совпал; продолжите загрузку")
        val digest = MessageDigest.getInstance("SHA-256")
        partial.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(expectedHash, ignoreCase = true)) { partial.delete(); error("Контрольная сумма не совпала; скачайте модель заново") }
        if (destination.exists()) check(destination.delete()) { "Не удалось заменить старую модель" }
        check(partial.renameTo(destination)) { "Не удалось сохранить модель после проверки" }
    }
}
