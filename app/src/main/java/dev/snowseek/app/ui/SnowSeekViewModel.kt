package dev.snowseek.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.snowseek.app.auth.WebSession
import dev.snowseek.app.auth.DeepSeekCredentials
import dev.snowseek.app.data.DeepSeekHttpTransport
import dev.snowseek.app.data.FileChatStore
import dev.snowseek.app.settings.AppSettings
import dev.snowseek.app.settings.SettingsStore
import dev.snowseek.app.settings.ThemeMode
import dev.snowseek.core.ChatOptions
import dev.snowseek.core.ChatSession
import dev.snowseek.core.MockChatProvider
import dev.snowseek.core.ChatProvider
import dev.snowseek.core.DeepSeekWebProvider
import dev.snowseek.core.DeepSeekFailure
import dev.snowseek.core.DeepSeekDeliveryUncertain
import kotlinx.coroutines.flow.drop
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
    val chats: List<ChatSession> = emptyList(),
    val selectedId: String? = null,
    val input: String = "",
    val loading: Boolean = true,
    val generating: Boolean = false,
    val deepThink: Boolean = false,
    val search: Boolean = false,
    val error: Boolean = false,
    val initialized: Boolean = false,
    val signedOut: Boolean = false,
    val signingOut: Boolean = false,
    val isDemo: Boolean = true,
    val errorMessage: String? = null,
)

class SnowSeekViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = SettingsStore(application)
    private val credentials = DeepSeekCredentials.get(application)
    private val transport = DeepSeekHttpTransport(application)
    private val demo = MockChatProvider(FileChatStore(application))
    private val web = DeepSeekWebProvider(transport)
    private var provider: ChatProvider = if (credentials.token() == null) demo else web
    private val mutableUi = MutableStateFlow(ChatUiState())
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
    private var activeChatId: String? = null
    private var initialization: Job? = null
    private var providerCollection: Job? = null
    private var historyLoad: Job? = null
    private var historyRevision = 0L

    init {
        bindProvider()
        initialize()
        viewModelScope.launch {
            credentials.changes.drop(1).collect {
                activeChatId?.let { provider.stopGeneration(it) }
                generation?.cancelAndJoin()
                initialization?.cancelAndJoin()
                historyLoad?.cancelAndJoin()
                if (provider === web) web.clear()
                provider = if (credentials.token() == null) demo else web
                mutableUi.value = ChatUiState(isDemo = provider === demo)
                bindProvider()
                initialize()
            }
        }
    }
    private fun bindProvider() {
        providerCollection?.cancel()
        val selected = provider
        mutableUi.update { it.copy(isDemo = selected === demo) }
        providerCollection = viewModelScope.launch {
            selected.chats.collect { chats -> mutableUi.update { it.copy(chats = chats) } }
        }
    }
    private fun report(failure: Exception) {
        mutableUi.update { it.copy(error = true, errorMessage = (failure as? DeepSeekFailure)?.message) }
    }

    fun initialize() {
        if (initialization?.isActive == true) return
        mutableUi.update { it.copy(loading = true, error = false, errorMessage = null) }
        initialization = viewModelScope.launch {
            try {
                provider.initialize()
                val firstId = provider.chats.value.firstOrNull()?.id
                if (firstId != null && provider === web) provider.getMessages(firstId)
                mutableUi.update {
                    it.copy(selectedId = firstId, initialized = true)
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
            current.chats.firstOrNull { it.id == current.selectedId }?.messages?.isNotEmpty() == true) return
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
    fun select(id: String) {
        mutableUi.update { it.copy(selectedId = id, input = "", selectedInstruction = null) }
        if (provider !== web) return
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
        if (ui.value.generating || ui.value.loading || !ui.value.initialized) return
        mutableUi.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                val chat = provider.createChat()
                mutableUi.update { it.copy(selectedId = chat.id, input = "", selectedInstruction = null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { report(failure) }
            finally { mutableUi.update { it.copy(loading = false) } }
        }
    }

    fun deleteChat(id: String) {
        if (ui.value.generating || ui.value.loading) return
        mutableUi.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                provider.deleteChat(id)
                mutableUi.update {
                    it.copy(selectedId = if (it.selectedId == id) provider.chats.value.firstOrNull()?.id else it.selectedId)
                }
                if (provider === web) ui.value.selectedId?.let { provider.getMessages(it) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { report(failure) }
            finally { mutableUi.update { it.copy(loading = false) } }
        }
    }

    fun send() {
        val current = ui.value
        val prompt = current.input.trim()
        if (current.generating || current.loading || !current.initialized || prompt.isEmpty()) return
        mutableUi.update { it.copy(generating = true, input = "", error = false) }
        generation = viewModelScope.launch {
            try {
                val chatId = current.selectedId ?: provider.createChat().id.also { id ->
                    mutableUi.update { it.copy(selectedId = id) }
                }
                activeChatId = chatId
                provider.sendMessage(chatId, prompt, ChatOptions(current.deepThink, current.search, settings.value.language, instruction = current.selectedInstruction)).collect { }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                report(failure)
                if (failure !is DeepSeekDeliveryUncertain) mutableUi.update { it.copy(input = if (it.input.isEmpty()) prompt else it.input) }
            } finally {
                activeChatId = null
                mutableUi.update { it.copy(generating = false) }
            }
        }
    }

    fun stop() {
        val activeJob = generation ?: return
        val chatId = activeChatId
        viewModelScope.launch {
            try {
                if (chatId != null) provider.stopGeneration(chatId)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { report(failure) }
            finally { activeJob.cancelAndJoin() }
        }
    }

    private fun save(block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { report(failure) }
        }
    }
    fun welcomeComplete() = save { preferences.completeWelcome() }
    fun theme(value: ThemeMode) = save { preferences.updateTheme(value) }
    fun language(value: String) = save { preferences.updateLanguage(value) }
    fun textScale(value: Float) = save { preferences.updateTextScale(value) }
    fun animations(value: Boolean) = save { preferences.updateAnimations(value) }
    fun logout() {
        if (ui.value.signingOut) return
        mutableUi.update { it.copy(signingOut = true) }
        save {
            try {
                activeChatId?.let { provider.stopGeneration(it) }
                generation?.cancelAndJoin()
                WebSession.clear(getApplication())
                mutableUi.update { it.copy(signedOut = true) }
            } finally {
                mutableUi.update { it.copy(signingOut = false) }
            }
        }
    }
    override fun onCleared() { transport.cancelCompletion() }
}
