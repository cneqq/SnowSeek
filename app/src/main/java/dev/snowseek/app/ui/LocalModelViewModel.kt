package dev.snowseek.app.ui

import android.app.Application
import android.os.StatFs
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LocalModelExchange(val question: String, val answer: String)

data class LocalModelUiState(
    val downloadedBytes: Long = 0,
    val downloading: Boolean = false,
    val verifying: Boolean = false,
    val loading: Boolean = false,
    val ready: Boolean = false,
    val generating: Boolean = false,
    val prompt: String = "",
    val exchanges: List<LocalModelExchange> = emptyList(),
    val error: String? = null,
)

private object FirstLocalModel {
    const val SIZE = 347_251_840L
    const val SHA256 = "2df6821ec12702dafd33915e7a1a1adc7c4b053f3672fd9555dfaf3a114c4139"
    const val FILE = "qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm"
    const val URL = "https://huggingface.co/litert-community/Qwen3-0.6B-int4/resolve/6aa2daf8aba4aa456797fb8040b36a3948bcfda7/$FILE?download=true"
}

class LocalModelViewModel(application: Application) : AndroidViewModel(application) {
    private val modelFile = File(File(application.filesDir, "local-models"), FirstLocalModel.FILE)
    private val partialFile = File(modelFile.parentFile, "${FirstLocalModel.FILE}.part")
    private val mutableState = MutableStateFlow(LocalModelUiState(
        downloadedBytes = if (modelFile.isFile) FirstLocalModel.SIZE else partialFile.length(),
        ready = modelFile.isFile,
    ))
    val state = mutableState.asStateFlow()

    private var downloadJob: Job? = null
    private var inferenceJob: Job? = null
    private var engine: Engine? = null
    private var conversation: Conversation? = null

    fun prompt(value: String) = mutableState.update { it.copy(prompt = value.take(4_000), error = null) }

    fun download() {
        if (downloadJob?.isActive == true || mutableState.value.ready) return
        downloadJob = viewModelScope.launch {
            mutableState.update { it.copy(downloading = true, verifying = false, error = null) }
            try {
                withContext(Dispatchers.IO) {
                    downloadModel { count -> mutableState.update { it.copy(downloadedBytes = count) } }
                }
                mutableState.update { it.copy(downloading = false, verifying = true) }
                withContext(Dispatchers.IO) { verifyAndInstall() }
                mutableState.update { it.copy(downloadedBytes = FirstLocalModel.SIZE, verifying = false, ready = true) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableState.update { it.copy(downloadedBytes = partialFile.length(), downloading = false,
                    verifying = false, error = failure.message ?: "Не удалось загрузить модель") }
            } finally {
                mutableState.update { it.copy(downloading = false, verifying = false) }
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
    }

    fun deleteModel() {
        unload()
        partialFile.delete()
        modelFile.delete()
        mutableState.value = LocalModelUiState()
    }

    fun unload() {
        inferenceJob?.cancel()
        inferenceJob = null
        runCatching { conversation?.close() }
        runCatching { engine?.close() }
        conversation = null
        engine = null
        mutableState.update { it.copy(loading = false, generating = false) }
    }

    fun send() {
        val question = state.value.prompt.trim()
        if (!state.value.ready || question.isEmpty() || state.value.generating || state.value.loading) return
        mutableState.update { it.copy(prompt = "", generating = true, error = null,
            exchanges = (it.exchanges + LocalModelExchange(question, "")).takeLast(20)) }
        inferenceJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val active = conversation ?: createConversation().also { conversation = it }
                active.sendMessageAsync(question).collect { piece ->
                    val text = piece.toString()
                    mutableState.update { current ->
                        val last = current.exchanges.lastOrNull() ?: return@update current
                        current.copy(exchanges = current.exchanges.dropLast(1) + last.copy(answer = last.answer + text))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableState.update { it.copy(error = failure.message ?: "Не удалось получить локальный ответ") }
            } finally {
                mutableState.update { it.copy(generating = false) }
            }
        }
    }

    private fun createConversation(): Conversation {
        mutableState.update { it.copy(loading = true) }
        try {
            val readyEngine = engine ?: Engine(EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.CPU(),
                cacheDir = File(getApplication<Application>().cacheDir, "litert-model-cache").absolutePath,
            )).also { it.initialize(); engine = it }
            return readyEngine.createConversation(ConversationConfig(
                systemInstruction = Contents.of("You are a helpful, concise assistant. Reply in the user's language."),
                samplerConfig = SamplerConfig(temperature = 0.7, topK = 40, topP = 0.9),
            ))
        } finally {
            mutableState.update { it.copy(loading = false) }
        }
    }

    private suspend fun downloadModel(onProgress: (Long) -> Unit) {
        modelFile.parentFile?.mkdirs() ?: error("Не удалось создать папку модели")
        var failures = 0
        while (partialFile.length() < FirstLocalModel.SIZE) {
            currentCoroutineContext().ensureActive()
            val offset = partialFile.length()
            check(StatFs(modelFile.parentFile!!.absolutePath).availableBytes >= FirstLocalModel.SIZE - offset + 16L * 1024 * 1024) {
                "Недостаточно свободного места для загрузки модели"
            }
            val connection = (URL(FirstLocalModel.URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "SnowSeek-Android/0.3.8")
                if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
            }
            try {
                val response = connection.responseCode
                if (response !in 200..299) error("Сервер вернул HTTP $response")
                val append = offset > 0 && response == HttpURLConnection.HTTP_PARTIAL
                if (append) {
                    val rangeStart = Regex("bytes (\\d+)-").find(connection.getHeaderField("Content-Range").orEmpty())
                        ?.groupValues?.get(1)?.toLongOrNull()
                    check(rangeStart == offset) { "Сервер возобновил загрузку с неверной позиции" }
                }
                if (offset > 0 && !append) partialFile.writeBytes(byteArrayOf())
                RandomAccessFile(partialFile, "rw").use { output ->
                    if (!append) output.setLength(0) else output.seek(offset)
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            val downloaded = output.filePointer
                            if (downloaded > FirstLocalModel.SIZE) error("Размер модели превышает ожидаемый")
                            onProgress(downloaded)
                        }
                    }
                }
                if (partialFile.length() < FirstLocalModel.SIZE) error("Загрузка прервалась")
                failures = 0
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failures++
                if (failures >= 5) throw failure
                kotlinx.coroutines.delay(1_000L * failures)
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun verifyAndInstall() {
        if (partialFile.length() != FirstLocalModel.SIZE) {
            partialFile.delete()
            error("Размер файла модели не совпадает; попробуйте загрузить её ещё раз")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        partialFile.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != FirstLocalModel.SHA256) {
            partialFile.delete()
            error("Контрольная сумма модели не совпала; загрузите её ещё раз")
        }
        if (modelFile.exists()) check(modelFile.delete()) { "Не удалось заменить файл модели" }
        check(partialFile.renameTo(modelFile)) { "Не удалось сохранить модель" }
    }

    override fun onCleared() {
        downloadJob?.cancel()
        unload()
        super.onCleared()
    }
}
