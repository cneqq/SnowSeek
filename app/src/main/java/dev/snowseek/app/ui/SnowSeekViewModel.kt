package dev.snowseek.app.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import dev.snowseek.core.ChatAttachment
import dev.snowseek.core.UploadFile
import dev.snowseek.core.MessageRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import java.io.File
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.snowseek.app.auth.WebSession
import dev.snowseek.app.auth.DeepSeekCredentials
import dev.snowseek.app.data.DeepSeekHttpTransport
import dev.snowseek.app.settings.AppSettings
import dev.snowseek.app.settings.FontMode
import dev.snowseek.app.settings.GlassSettings
import dev.snowseek.app.settings.SettingsStore
import dev.snowseek.app.settings.ThemeMode
import dev.snowseek.core.ChatOptions
import dev.snowseek.core.ChatSession
import dev.snowseek.core.ChatProvider
import dev.snowseek.core.DeepSeekWebProvider
import dev.snowseek.core.DeepSeekFailure
import dev.snowseek.core.DeepSeekDeliveryUncertain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatUiState(
    val selectedInstruction: dev.snowseek.core.ChatInstruction? = null,
    val authenticated: Boolean = false,
    val sessionChanging: Boolean = false,
    val chats: List<ChatSession> = emptyList(),
    val selectedId: String? = null,
    val input: String = "",
    val editingMessageId: String? = null,
    val attachments: List<ChatAttachment> = emptyList(),
    val uploading: Boolean = false,
    val loading: Boolean = true,
    val generating: Boolean = false,
    val deepThink: Boolean = false,
    val search: Boolean = false,
    val error: Boolean = false,
    val initialized: Boolean = false,
    val signedOut: Boolean = false,
    val signingOut: Boolean = false,
    val errorMessage: String? = null,
    val deliveryUncertain: Boolean = false,
)

class SnowSeekViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = SettingsStore(application)
    private val credentials = DeepSeekCredentials.get(application)
    private val transport = DeepSeekHttpTransport(application)
    private val web = DeepSeekWebProvider(transport, preferences)
    private val provider: ChatProvider = web
    private val mutableUi = MutableStateFlow(ChatUiState(authenticated = credentials.token() != null))
    val ui = mutableUi.asStateFlow()
    private val mutableSettingsReady = MutableStateFlow(false)
    val settingsReady = mutableSettingsReady.asStateFlow()
    private var lastSettings = AppSettings()
    val settings = preferences.settings.retryWhen { cause, attempt ->
        if (cause is CancellationException) throw cause
        if (attempt == 0L) mutableUi.update { state -> state.copy(error = true) }
        emit(lastSettings)
        delay(1500)
        true
    }.onEach { lastSettings = it; mutableSettingsReady.value = true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    private var generation: Job? = null
    private var stopping = false
    private var inputBeforeEdit = ""
    private var attachmentsBeforeEdit = emptyList<ChatAttachment>()
    private var upload: Job? = null
    private var activeChatId: String? = null
    private var initialization: Job? = null
    private var providerCollection: Job? = null
    private var historyLoad: Job? = null
    private var accountMutation: Job? = null
    private var loggingOut = false
    private var historyRevision = 0L

    init {
        var observedRevision = credentials.changes.value
        bindProvider()
        initialize()
        viewModelScope.launch {
            credentials.changes.collect { revision ->
                if (revision == observedRevision) return@collect
                mutableUi.update { it.copy(authenticated = false, sessionChanging = true,
                    loading = true, chats = emptyList(), selectedId = null) }
                providerCollection?.cancel()
                initialization?.cancel()
                historyLoad?.cancel()
                generation?.cancel()
                // The credential has already changed. Disconnect the old stream locally.
                transport.cancelCompletion()
                upload?.cancelAndJoin()
                accountMutation?.cancelAndJoin()
                generation?.join()
                initialization?.join()
                historyLoad?.join()
                web.clear()
                observedRevision = credentials.changes.value
                mutableUi.value = ChatUiState(authenticated = credentials.token() != null,
                    sessionChanging = loggingOut, signingOut = loggingOut)
                bindProvider()
                if (!loggingOut) initialize()
            }
        }
    }
    private fun bindProvider() {
        providerCollection?.cancel()
        val selected = provider
        providerCollection = viewModelScope.launch {
            selected.chats.collect { chats -> mutableUi.update { it.copy(chats = chats) } }
        }
    }
    private fun report(failure: Exception) {
        mutableUi.update { it.copy(error = true, errorMessage = (failure as? DeepSeekFailure)?.message,
            deliveryUncertain = failure is DeepSeekDeliveryUncertain) }
    }

    fun initialize() {
        if (loggingOut || initialization?.isActive == true) return
        if (credentials.token() == null) {
            mutableUi.value = ChatUiState(authenticated = false, loading = false, initialized = true)
            return
        }
        mutableUi.update { it.copy(authenticated = true, loading = true, error = false, errorMessage = null) }
        initialization = viewModelScope.launch {
            try {
                provider.initialize()
                mutableUi.update {
                    it.copy(chats = provider.chats.value, initialized = true)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                report(failure)
            } finally {
                mutableUi.update { it.copy(loading = false) }
            }
        }
    }

    fun selectInstruction(instruction: dev.snowseek.core.ChatInstruction?) {
        val current = ui.value
        if (current.loading || current.generating ||
            current.selectedId != null) return
        mutableUi.update { it.copy(selectedInstruction = instruction) }
    }
    fun saveInstruction(id: String?, title: String, text: String) {
        val instruction = dev.snowseek.core.ChatInstruction(id ?: ("custom." + java.util.UUID.randomUUID()), title.trim(), text.trim())
        if (!instruction.isValid()) return
        viewModelScope.launch {
            try {
                preferences.saveInstruction(instruction)
                if (ui.value.selectedInstruction?.id == instruction.id) selectInstruction(instruction)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { report(failure) }
        }
    }
    fun deleteInstruction(id: String) {
        viewModelScope.launch {
            try {
                preferences.deleteInstruction(id)
                if (ui.value.selectedInstruction?.id == id) selectInstruction(null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { report(failure) }
        }
    }

    fun input(value: String) { mutableUi.update { it.copy(input = value.take(32_768)) } }
    fun attachFiles(uris: List<Uri>) {
        val current = ui.value
        if (!current.authenticated || current.signingOut || current.generating || current.loading || current.uploading) return
        if (current.attachments.size + uris.size > 10) {
            report(DeepSeekFailure("В приложении можно приложить до 10 файлов за сообщение. / Up to 10 files per message."))
            return
        }
        mutableUi.update { it.copy(uploading = true, error = false, errorMessage = null) }
        upload = viewModelScope.launch {
            try {
                for (uri in uris) {
                    val temporary = File.createTempFile("snowseek-attachment-", ".upload", getApplication<Application>().cacheDir)
                    try {
                        val prepared = prepareAttachment(uri, temporary)
                        val ready = web.uploadAttachment(prepared, current.deepThink)
                        mutableUi.update { it.copy(attachments = (it.attachments + ready).distinctBy { file -> file.id }) }
                    } finally { temporary.delete() }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { report(failure) }
            finally { mutableUi.update { it.copy(uploading = false) } }
        }
    }
    private suspend fun prepareAttachment(uri: Uri, temporary: File): UploadFile = withContext(Dispatchers.IO) {
        if (uri.scheme != "content") throw DeepSeekFailure("Выберите файл через системное окно. / Select a document using the system picker.")
        val application = getApplication<Application>()
        val resolver = application.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }?.takeIf { it.isNotBlank() } ?: "attachment"
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        try {
            resolver.openInputStream(uri)?.use { source ->
                temporary.outputStream().use { destination ->
                    val buffer = ByteArray(64 * 1024)
                    var size = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = source.read(buffer)
                        if (count < 0) break
                        if (count == 0) throw DeepSeekFailure("Не удалось прочитать файл. / Could not read the document.")
                        size += count
                        if (size > 50L * 1024 * 1024) throw DeepSeekFailure("Лимит приложения — 50 МиБ на файл. / App limit: 50 MiB per file.")
                        destination.write(buffer, 0, count)
                    }
                }
            } ?: throw DeepSeekFailure("Файл недоступен. Выберите его повторно. / Document unavailable; select it again.")
            UploadFile(name, mime, temporary.length()) { temporary.inputStream() }
        } catch (failure: Exception) {
            temporary.delete()
            throw failure
        }
    }
    fun removeAttachment(id: String) {
        if (ui.value.generating || ui.value.uploading) return
        mutableUi.update { it.copy(attachments = it.attachments.filterNot { file -> file.id == id }) }
    }
    fun editMessage(id: String) {
        val current = ui.value
        if (!current.authenticated) return
        if (current.signingOut || current.generating || current.loading || current.uploading) {
            report(DeepSeekFailure("Сначала дождитесь завершения запроса или остановите ответ. / Wait for the request or stop the response first."))
            return
        }
        val chatId = current.selectedId ?: return
        val before = current.chats.firstOrNull { it.id == chatId }?.messages.orEmpty()
        val message = before.firstOrNull { it.id == id && it.role == MessageRole.USER } ?: return
        if (message.id.toLongOrNull() != null && message.editable) {
            openEditor(message)
            return
        }
        val ticket = ++historyRevision
        historyLoad?.cancel()
        mutableUi.update { it.copy(loading = true) }
        historyLoad = viewModelScope.launch {
            try {
                val refreshed = provider.getMessages(chatId)
                val resolved = resolveEditableMessage(before, refreshed, id)
                    ?: throw DeepSeekFailure("Сообщение изменилось или недоступно для редактирования. Обновите чат и выберите его снова. / Message changed or cannot be edited; reload the chat and select it again.")
                if (historyRevision == ticket && ui.value.selectedId == chatId) openEditor(resolved)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (historyRevision == ticket && ui.value.selectedId == chatId) report(failure) }
            finally { if (historyRevision == ticket && ui.value.selectedId == chatId) mutableUi.update { it.copy(loading = false) } }
        }
    }
    private fun openEditor(message: dev.snowseek.core.ChatMessage) {
        val current = ui.value
        if (current.editingMessageId == null) {
            inputBeforeEdit = current.input
            attachmentsBeforeEdit = current.attachments
        }
        mutableUi.update { it.copy(editingMessageId = message.id, input = message.text, attachments = message.attachments) }
    }
    fun cancelEdit() {
        if (ui.value.generating || ui.value.editingMessageId == null) return
        mutableUi.update { it.copy(editingMessageId = null, input = inputBeforeEdit, attachments = attachmentsBeforeEdit) }
        inputBeforeEdit = ""
    }
    fun select(id: String) {
        if (!ui.value.authenticated || ui.value.signingOut || ui.value.uploading || !ui.value.initialized) return
        mutableUi.update { it.copy(selectedId = id, input = "", editingMessageId = null, attachments = emptyList(), selectedInstruction = null) }
        loadHistory(id)
    }
    fun refreshHistory() {
        val current = ui.value
        if (!current.authenticated || current.signingOut || current.generating || current.loading) return
        current.selectedId?.let(::loadHistory)
    }
    private fun loadHistory(id: String) {
        historyLoad?.cancel()
        val ticket = ++historyRevision
        mutableUi.update { it.copy(loading = true) }
        historyLoad = viewModelScope.launch {
            try { provider.getMessages(id) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (historyRevision == ticket && ui.value.selectedId == id) report(failure) }
            finally { if (historyRevision == ticket && ui.value.selectedId == id) mutableUi.update { it.copy(loading = false) } }
        }
    }
    fun deepThink() { mutableUi.update { it.copy(deepThink = !it.deepThink) } }
    fun search() { mutableUi.update { it.copy(search = !it.search) } }
    fun dismissError() { mutableUi.update { it.copy(error = false, errorMessage = null, signedOut = false) } }

    fun newChat() {
        if (!ui.value.authenticated || ui.value.signingOut || ui.value.generating || ui.value.loading || ui.value.uploading || !ui.value.initialized) return
        mutableUi.update { it.copy(selectedId = null, input = "", selectedInstruction = null, editingMessageId = null, attachments = emptyList()) }
    }

    fun deleteChat(id: String) {
        if (!ui.value.authenticated || ui.value.signingOut || ui.value.generating || ui.value.loading || ui.value.uploading) return
        mutableUi.update { it.copy(loading = true) }
        accountMutation = viewModelScope.launch {
            try {
                provider.deleteChat(id)
                mutableUi.update {
                    if (it.selectedId == id) it.copy(selectedId = provider.chats.value.firstOrNull()?.id,
                        editingMessageId = null, input = "", attachments = emptyList()) else it
                }
                if (provider === web) ui.value.selectedId?.let { provider.getMessages(it) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { report(failure) }
            finally { mutableUi.update { it.copy(loading = false) } }
        }
    }

    fun pinChat(id: String, pinned: Boolean) {
        val current = ui.value
        if (!current.authenticated || current.signingOut || current.generating || current.loading || current.uploading) return
        mutableUi.update { it.copy(loading = true) }
        accountMutation = viewModelScope.launch {
            try { web.setPinned(id, pinned) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { report(failure) }
            finally { mutableUi.update { it.copy(loading = false) } }
        }
    }

    fun send() {
        val current = ui.value
        val prompt = current.input.trim()
        if (!current.authenticated || current.signingOut || current.generating || current.loading || current.uploading || !current.initialized ||
            (prompt.isEmpty() && current.attachments.isEmpty())) return
        mutableUi.update { it.copy(generating = true, input = if (current.editingMessageId == null) "" else prompt,
            attachments = if (current.editingMessageId == null) emptyList() else current.attachments, error = false) }
        generation = viewModelScope.launch {
            var destinationId = current.selectedId
            try {
                val chatId = current.selectedId ?: provider.createChat().id.also { id ->
                    destinationId = id
                    mutableUi.update { it.copy(selectedId = id) }
                }
                activeChatId = chatId
                val options = ChatOptions(current.deepThink, current.search, settings.value.language,
                    current.attachments, replaceAttachments = current.editingMessageId != null,
                    instruction = current.selectedInstruction)
                val stream = current.editingMessageId?.let { provider.editMessage(chatId, it, prompt, options) }
                    ?: provider.sendMessage(chatId, prompt, options)
                stream.collect { }
                if (current.editingMessageId != null) {
                    mutableUi.update { if (it.selectedId == chatId && it.editingMessageId == current.editingMessageId)
                        it.copy(editingMessageId = null, input = "", attachments = emptyList()) else it }
                    inputBeforeEdit = ""
                }
            } catch (cancelled: CancellationException) {
                if (current.editingMessageId != null) mutableUi.update {
                    if (it.selectedId == current.selectedId && it.editingMessageId == current.editingMessageId)
                        it.copy(editingMessageId = null, input = "", attachments = emptyList()) else it
                }
                throw cancelled
            } catch (failure: Exception) {
                report(failure)
                if (current.editingMessageId != null) {
                    mutableUi.update { if (it.selectedId != current.selectedId || it.editingMessageId != current.editingMessageId) it else
                        if (failure is DeepSeekDeliveryUncertain) it.copy(editingMessageId = null, input = "", attachments = emptyList())
                        else it.copy(editingMessageId = current.editingMessageId, input = prompt, attachments = current.attachments) }
                } else if (failure !is DeepSeekDeliveryUncertain) mutableUi.update {
                    if (it.selectedId != destinationId) it else it.copy(input = if (it.input.isEmpty()) prompt else it.input,
                        attachments = (it.attachments + current.attachments).distinctBy { file -> file.id }) }
            } finally {
                activeChatId = null
                if (!stopping) mutableUi.update { it.copy(generating = false) }
            }
        }
    }

    fun switchVersion(messageId: String, versionId: String) {
        val current = ui.value
        val chatId = current.selectedId ?: return
        if (!current.authenticated || current.signingOut || current.generating ||
            current.loading || current.uploading || current.editingMessageId != null) return
        web.switchVersion(chatId, messageId, versionId)
    }

    fun continueMessage(id: String) = runResponseAction(id, resume = true)
    fun regenerateMessage(id: String) = runResponseAction(id, resume = false)
    private fun runResponseAction(id: String, resume: Boolean) {
        val current = ui.value
        val chatId = current.selectedId ?: return
        if (!current.authenticated || current.signingOut || current.generating || current.loading || current.uploading || !current.initialized ||
            current.editingMessageId != null) return
        val message = current.chats.firstOrNull { it.id == chatId }?.messages?.firstOrNull { it.id == id } ?: return
        if (message.role != MessageRole.ASSISTANT || if (resume) !message.continuable else !message.regenerable) return
        mutableUi.update { it.copy(generating = true, error = false, errorMessage = null) }
        generation = viewModelScope.launch {
            try {
                activeChatId = chatId
                val stream = if (resume) provider.continueMessage(chatId, id) else
                    provider.regenerateMessage(chatId, id, ChatOptions(current.deepThink, current.search, settings.value.language))
                stream.collect { }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { report(failure) }
            finally {
                activeChatId = null
                if (!stopping) mutableUi.update { it.copy(generating = false) }
            }
        }
    }

    fun stop() {
        val activeJob = generation ?: return
        if (stopping || !activeJob.isActive) return
        val chatId = activeChatId
        stopping = true
        viewModelScope.launch {
            try {
                if (chatId != null) provider.stopGeneration(chatId)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { report(failure) }
            finally {
                activeJob.cancelAndJoin()
                stopping = false
                mutableUi.update { it.copy(generating = false) }
            }
        }
    }

    private fun save(block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { report(failure) }
        }
    }
    fun theme(value: ThemeMode) = save { preferences.updateTheme(value) }
    fun font(value: FontMode) = save { preferences.updateFont(value) }
    fun glass(value: GlassSettings) = save { preferences.updateGlass(value) }
    fun language(value: String) = save { preferences.updateLanguage(value) }
    fun textScale(value: Float) = save { preferences.updateTextScale(value) }
    fun animations(value: Boolean) = save { preferences.updateAnimations(value) }
    fun logout() {
        if (ui.value.signingOut) return
        loggingOut = true
        mutableUi.update { it.copy(signingOut = true, sessionChanging = true) }
        save {
            try {
                upload?.cancelAndJoin()
                accountMutation?.cancelAndJoin()
                initialization?.cancelAndJoin()
                historyLoad?.cancelAndJoin()
                try { activeChatId?.let { provider.stopGeneration(it) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Signing out must also work when server stop fails. */ }
                generation?.cancelAndJoin()
                WebSession.clear(getApplication())
                mutableUi.value = ChatUiState(authenticated = false, loading = false, initialized = true)
            } finally {
                loggingOut = false
                mutableUi.update { it.copy(signingOut = false, sessionChanging = false) }
            }
        }
    }
    override fun onCleared() { transport.cancelCompletion() }
}
