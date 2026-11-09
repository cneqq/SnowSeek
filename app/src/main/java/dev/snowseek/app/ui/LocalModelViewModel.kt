package dev.snowseek.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.snowseek.app.local.InstalledModel
import dev.snowseek.app.local.LiteRtInferenceEngine
import dev.snowseek.app.local.LlamaCppEngine
import dev.snowseek.app.local.LocalChatMessage
import dev.snowseek.app.local.LocalInferenceEngine
import dev.snowseek.app.local.LocalModelFormat
import dev.snowseek.app.local.LocalModelRepository
import dev.snowseek.app.local.LocalModelDownloadWorker
import dev.snowseek.app.local.ModelCatalog
import dev.snowseek.app.local.ModelCatalogEntry
import dev.snowseek.app.local.SavedLocalChat
import dev.snowseek.app.local.SavedLocalTurn
import java.io.File
import java.util.UUID
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import androidx.lifecycle.Observer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LocalModelExchange(val question: String, val answer: String)

internal data class LocalModelUiState(
    val catalog: List<ModelCatalogEntry> = emptyList(),
    val installed: List<InstalledModel> = emptyList(),
    val selectedId: String? = null,
    val history: List<SavedLocalChat> = emptyList(),
    val chatModelId: String? = null,
    val activeChatId: String? = null,
    val chatTitle: String = "Локальный чат",
    val downloadedBytes: Long = 0,
    val downloadingId: String? = null,
    val verifying: Boolean = false,
    val loading: Boolean = false,
    val generating: Boolean = false,
    val prompt: String = "",
    val exchanges: List<LocalModelExchange> = emptyList(),
    val error: String? = null,
)

internal class LocalModelViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = LocalModelRepository(application)
    private val workManager = WorkManager.getInstance(application)
    private val mutableState = MutableStateFlow(
        LocalModelUiState(
            catalog = repository.catalog(),
            installed = repository.installed(),
            selectedId = repository.selectedId(),
            history = repository.chats(),
        ),
    )
    val state = mutableState.asStateFlow()

    private var downloadJob: Job? = null
    private var inferenceJob: Job? = null
    private var engine: LocalInferenceEngine? = null
    private var engineModelId: String? = null
    private val downloadWorkLiveData = workManager.getWorkInfosByTagLiveData(LocalModelDownloadWorker.TAG)
    private val downloadWorkObserver = Observer<List<WorkInfo>>(::syncDownloadState)

    init {
        downloadWorkLiveData.observeForever(downloadWorkObserver)
    }

    private fun syncDownloadState(infos: List<WorkInfo>?) {
        infos ?: return
        fun modelId(info: WorkInfo) = info.tags.firstOrNull { it.startsWith("${LocalModelDownloadWorker.TAG}:") }
            ?.substringAfter(':')
        val active = infos.firstOrNull { !it.state.isFinished }
        if (active != null) {
            val modelId = modelId(active) ?: return
            mutableState.update {
                it.copy(
                    downloadedBytes = active.progress.getLong(LocalModelDownloadWorker.KEY_DOWNLOADED_BYTES, 0L),
                    downloadingId = modelId,
                    verifying = active.progress.getBoolean(LocalModelDownloadWorker.KEY_VERIFYING, false),
                    error = null,
                )
            }
        } else {
            val currentId = mutableState.value.downloadingId
            if (currentId != null && currentId != "import") {
                val finished = infos.filter { modelId(it) == currentId }.maxByOrNull { it.generation }
                if (finished != null) {
                    val completed = finished.state == WorkInfo.State.SUCCEEDED
                    mutableState.update {
                        it.copy(
                            installed = if (completed) repository.installed() else it.installed,
                            downloadedBytes = if (completed) 0 else it.downloadedBytes,
                            downloadingId = null,
                            verifying = false,
                            error = if (finished.state == WorkInfo.State.FAILED) {
                                finished.outputData.getString(LocalModelDownloadWorker.KEY_ERROR) ?: "Не удалось скачать модель"
                            } else it.error,
                        )
                    }
                }
            }
        }
    }

    fun prompt(value: String) = mutableState.update { it.copy(prompt = value.take(4_000), error = null) }

    fun download(entry: ModelCatalogEntry) {
        if (downloadJob?.isActive == true || mutableState.value.downloadingId != null ||
            mutableState.value.installed.any { it.id == entry.id }) return
        val request = OneTimeWorkRequestBuilder<LocalModelDownloadWorker>()
            .setInputData(workDataOf(LocalModelDownloadWorker.KEY_MODEL_ID to entry.id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(LocalModelDownloadWorker.TAG)
            .addTag(LocalModelDownloadWorker.modelTag(entry.id))
            .build()
        mutableState.update { it.copy(downloadedBytes = 0, downloadingId = entry.id, verifying = false, error = null) }
        workManager.enqueueUniqueWork(
            LocalModelDownloadWorker.uniqueWorkName(entry.id), ExistingWorkPolicy.KEEP, request,
        )
    }

    fun cancelDownload() {
        val activeId = mutableState.value.downloadingId
        if (activeId == "import") downloadJob?.cancel()
        else if (activeId != null) workManager.cancelUniqueWork(LocalModelDownloadWorker.uniqueWorkName(activeId))
        mutableState.update { it.copy(downloadingId = null, verifying = false) }
    }

    fun import(uri: Uri) {
        if (downloadJob?.isActive == true || mutableState.value.downloadingId != null) return
        downloadJob = viewModelScope.launch {
            mutableState.update { it.copy(downloadedBytes = 0, downloadingId = "import", verifying = false, error = null) }
            try {
                repository.import(uri, { count -> mutableState.update { it.copy(downloadedBytes = count) } },
                    { mutableState.update { it.copy(verifying = true) } })
                refreshLibrary()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableState.update { it.copy(error = failure.message ?: "Не удалось импортировать файл") }
            } finally {
                mutableState.update { it.copy(downloadingId = null, verifying = false) }
            }
        }
    }

    fun cancelTransfer() = cancelDownload()

    fun openChat(modelId: String) {
        val model = repository.installed().firstOrNull { it.id == modelId } ?: return
        unload()
        repository.select(model.id)
        val chat = SavedLocalChat(UUID.randomUUID().toString(), model.id, model.displayName,
            "Новый чат", System.currentTimeMillis(), emptyList())
        repository.saveChat(chat)
        mutableState.update { it.copy(installed = repository.installed(), selectedId = model.id,
            history = repository.chats(), chatModelId = model.id, activeChatId = chat.id,
            chatTitle = chat.title, exchanges = emptyList(), prompt = "", error = null) }
    }

    fun openSavedChat(chatId: String) {
        val chat = repository.chats().firstOrNull { it.id == chatId } ?: return
        if (repository.installed().none { it.id == chat.modelId }) {
            mutableState.update { it.copy(error = "Для этого чата сначала импортируйте или установите модель ${chat.modelName}") }
            return
        }
        unload()
        repository.select(chat.modelId)
        mutableState.update { it.copy(installed = repository.installed(), selectedId = chat.modelId,
            history = repository.chats(), chatModelId = chat.modelId, activeChatId = chat.id,
            chatTitle = chat.title, exchanges = chat.turns.filter { it.answer.isNotBlank() }
                .map { LocalModelExchange(it.question, it.answer) },
            prompt = "", error = null) }
    }

    fun deleteSavedChat(chatId: String) {
        repository.deleteChat(chatId)
        mutableState.update { it.copy(history = repository.chats()) }
    }

    fun backToLibrary() {
        saveCurrentChat()
        unload()
        mutableState.update { it.copy(chatModelId = null, activeChatId = null, chatTitle = "Локальный чат",
            prompt = "", exchanges = emptyList(), history = repository.chats(), error = null) }
    }

    fun onScreenDisposed() = backToLibrary()

    fun delete(modelId: String) {
        val target = repository.installed().firstOrNull { it.id == modelId } ?: return
        if (engineModelId == modelId) unload()
        runCatching { repository.delete(target) }.onFailure { failure ->
            mutableState.update { it.copy(error = failure.message ?: "Не удалось удалить модель") }
        }
        refreshLibrary()
        mutableState.update { it.copy(chatModelId = null, exchanges = emptyList()) }
    }

    fun send() {
        val current = state.value
        val question = current.prompt.trim()
        val model = current.installed.firstOrNull { it.id == current.chatModelId }
        if (model == null || question.isEmpty() || current.generating || current.loading) return
        mutableState.update { it.copy(prompt = "", generating = true, error = null,
            exchanges = (it.exchanges + LocalModelExchange(question, "")).takeLast(20)) }
        mutableState.update { value ->
            value.copy(chatTitle = if (value.exchanges.size == 1 && value.chatTitle == "Новый чат") question.take(48) else value.chatTitle)
        }
        saveCurrentChat()
        inferenceJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val active = engineFor(model)
                val messages = buildList {
                    add(LocalChatMessage("system", "You are a helpful, concise assistant. Reply in the user's language."))
                    state.value.exchanges.forEach { exchange ->
                        add(LocalChatMessage("user", exchange.question))
                        if (exchange.answer.isNotBlank()) add(LocalChatMessage("assistant", exchange.answer))
                    }
                }
                var lastSavedAt = System.currentTimeMillis()
                active.stream(messages).collect { piece ->
                    mutableState.update { value ->
                        val last = value.exchanges.lastOrNull() ?: return@update value
                        value.copy(exchanges = value.exchanges.dropLast(1) + last.copy(answer = last.answer + piece))
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastSavedAt >= 1_000L) { saveCurrentChat(); lastSavedAt = now }
                }
                if (state.value.exchanges.lastOrNull()?.answer.isNullOrBlank()) {
                    error("Модель завершила ответ без текста. Проверьте совместимость файла и шаблона диалога.")
                }
                saveCurrentChat()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableState.update { value ->
                    val last = value.exchanges.lastOrNull()
                    val exchanges = if (last?.question == question) value.exchanges.dropLast(1) else value.exchanges
                    value.copy(exchanges = exchanges, error = failure.message ?: "Не удалось получить локальный ответ")
                }
                saveCurrentChat()
            } finally {
                mutableState.update { it.copy(generating = false, loading = false) }
            }
        }
    }

    fun cancelGeneration() {
        engine?.cancel()
        inferenceJob?.cancel()
        inferenceJob = null
        mutableState.update { it.copy(generating = false) }
    }

    private suspend fun engineFor(model: InstalledModel): LocalInferenceEngine {
        if (engineModelId == model.id) return checkNotNull(engine)
        closeEngine()
        mutableState.update { it.copy(loading = true) }
        return try {
            val next: LocalInferenceEngine = when (model.format) {
                LocalModelFormat.LITERTLM -> LiteRtInferenceEngine(File(getApplication<Application>().cacheDir, "litert-model-cache"))
                LocalModelFormat.GGUF -> LlamaCppEngine()
            }
            try {
                next.load(model.file, model.format, contextTokens = 2048)
                engine = next
                engineModelId = model.id
                next
            } catch (failure: Exception) {
                next.close()
                throw failure
            }
        } finally {
            mutableState.update { it.copy(loading = false) }
        }
    }

    private fun refreshLibrary() {
        mutableState.update { it.copy(installed = repository.installed(), selectedId = repository.selectedId(), history = repository.chats()) }
    }

    private fun saveCurrentChat() {
        val current = state.value
        val id = current.activeChatId ?: return
        val model = current.installed.firstOrNull { it.id == current.chatModelId } ?: return
        repository.saveChat(SavedLocalChat(id, model.id, model.displayName, current.chatTitle,
            System.currentTimeMillis(), current.exchanges.map { SavedLocalTurn(it.question, it.answer) }))
        mutableState.update { it.copy(history = repository.chats()) }
    }

    private fun unload() {
        inferenceJob?.cancel()
        inferenceJob = null
        closeEngine()
        mutableState.update { it.copy(loading = false, generating = false) }
    }

    private fun closeEngine() {
        runCatching { engine?.cancel() }
        runCatching { engine?.close() }
        engine = null
        engineModelId = null
    }

    override fun onCleared() {
        downloadWorkLiveData.removeObserver(downloadWorkObserver)
        downloadJob?.cancel()
        unload()
        super.onCleared()
    }
}
