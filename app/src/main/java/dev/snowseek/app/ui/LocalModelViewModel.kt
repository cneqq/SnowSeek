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
import dev.snowseek.app.local.ModelCatalogEntry
import java.io.File
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
    val chatModelId: String? = null,
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
    private val mutableState = MutableStateFlow(
        LocalModelUiState(
            catalog = repository.catalog(),
            installed = repository.installed(),
            selectedId = repository.selectedId(),
        ),
    )
    val state = mutableState.asStateFlow()

    private var downloadJob: Job? = null
    private var inferenceJob: Job? = null
    private var engine: LocalInferenceEngine? = null
    private var engineModelId: String? = null

    fun prompt(value: String) = mutableState.update { it.copy(prompt = value.take(4_000), error = null) }

    fun download(entry: ModelCatalogEntry) {
        if (downloadJob?.isActive == true || mutableState.value.installed.any { it.id == entry.id }) return
        downloadJob = viewModelScope.launch {
            mutableState.update { it.copy(downloadedBytes = 0, downloadingId = entry.id, verifying = false, error = null) }
            try {
                repository.download(entry, { count -> mutableState.update { it.copy(downloadedBytes = count) } },
                    { mutableState.update { it.copy(verifying = true) } })
                refreshLibrary()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableState.update { it.copy(error = failure.message ?: "Не удалось скачать модель") }
            } finally {
                mutableState.update { it.copy(downloadingId = null, verifying = false) }
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        mutableState.update { it.copy(downloadingId = null, verifying = false) }
    }

    fun import(uri: Uri) {
        if (downloadJob?.isActive == true) return
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
        mutableState.update { it.copy(installed = repository.installed(), selectedId = model.id,
            chatModelId = model.id, exchanges = emptyList(), prompt = "", error = null) }
    }

    fun backToLibrary() {
        unload()
        mutableState.update { it.copy(chatModelId = null, prompt = "", exchanges = emptyList(), error = null) }
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
                active.stream(messages).collect { piece ->
                    mutableState.update { value ->
                        val last = value.exchanges.lastOrNull() ?: return@update value
                        value.copy(exchanges = value.exchanges.dropLast(1) + last.copy(answer = last.answer + piece))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableState.update { it.copy(error = failure.message ?: "Не удалось получить локальный ответ") }
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
        unload()
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
        mutableState.update { it.copy(installed = repository.installed(), selectedId = repository.selectedId()) }
    }

    private fun unload() {
        inferenceJob?.cancel()
        inferenceJob = null
        runCatching { engine?.cancel() }
        runCatching { engine?.close() }
        engine = null
        engineModelId = null
        mutableState.update { it.copy(loading = false, generating = false) }
    }

    override fun onCleared() {
        downloadJob?.cancel()
        unload()
        super.onCleared()
    }
}
