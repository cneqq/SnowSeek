package dev.snowseek.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.snowseek.app.auth.WebSession
import dev.snowseek.app.data.FileChatStore
import dev.snowseek.app.settings.AppSettings
import dev.snowseek.app.settings.SettingsStore
import dev.snowseek.app.settings.ThemeMode
import dev.snowseek.core.ChatOptions
import dev.snowseek.core.ChatSession
import dev.snowseek.core.MockChatProvider
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
)

class SnowSeekViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = SettingsStore(application)
    private val provider = MockChatProvider(FileChatStore(application))
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

    init {
        viewModelScope.launch {
            provider.chats.collect { chats -> mutableUi.update { it.copy(chats = chats) } }
        }
        initialize()
    }

    fun initialize() {
        if (initialization?.isActive == true) return
        mutableUi.update { it.copy(loading = true, error = false) }
        initialization = viewModelScope.launch {
            try {
                provider.initialize()
                mutableUi.update {
                    it.copy(selectedId = provider.chats.value.firstOrNull()?.id, initialized = true)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableUi.update { it.copy(error = true) }
            } finally {
                mutableUi.update { it.copy(loading = false) }
            }
        }
    }

    fun input(value: String) { mutableUi.update { it.copy(input = value.take(32_768)) } }
    fun select(id: String) { mutableUi.update { it.copy(selectedId = id, input = "") } }
    fun deepThink() { mutableUi.update { it.copy(deepThink = !it.deepThink) } }
    fun search() { mutableUi.update { it.copy(search = !it.search) } }
    fun dismissError() { mutableUi.update { it.copy(error = false, signedOut = false) } }

    fun newChat() {
        if (ui.value.generating || ui.value.loading || !ui.value.initialized) return
        mutableUi.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                val chat = provider.createChat()
                mutableUi.update { it.copy(selectedId = chat.id, input = "") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableUi.update { it.copy(error = true) } }
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
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableUi.update { it.copy(error = true) } }
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
                provider.sendMessage(chatId, prompt, ChatOptions(current.deepThink, current.search, settings.value.language)).collect { }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableUi.update { it.copy(error = true, input = if (it.input.isEmpty()) prompt else it.input) }
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
            catch (_: Exception) { mutableUi.update { it.copy(error = true) } }
            finally { activeJob.cancelAndJoin() }
        }
    }

    private fun save(block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableUi.update { it.copy(error = true) } }
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
                WebSession.clear(getApplication())
                mutableUi.update { it.copy(signedOut = true) }
            } finally {
                mutableUi.update { it.copy(signingOut = false) }
            }
        }
    }
}
