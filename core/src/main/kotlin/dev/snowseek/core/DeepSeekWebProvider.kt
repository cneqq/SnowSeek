package dev.snowseek.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow

/** Signing in through WebView never enables a native chat API. */
class DeepSeekWebProvider : ChatProvider {
    override val chats: StateFlow<List<ChatSession>> = MutableStateFlow<List<ChatSession>>(emptyList()).asStateFlow()
    override val capabilities = ProviderCapabilities(false, false, false, false)
    override suspend fun initialize(): Unit = unavailable()
    override suspend fun getCurrentUser(): UserProfile = unavailable()
    override suspend fun getChats(): List<ChatSession> = unavailable()
    override suspend fun createChat(): ChatSession = unavailable()
    override suspend fun deleteChat(chatId: String): Unit = unavailable()
    override suspend fun getMessages(chatId: String): List<ChatMessage> = unavailable()
    override fun sendMessage(chatId: String, prompt: String, options: ChatOptions): Flow<ChatMessage> = flow { unavailable() }
    override suspend fun stopGeneration(chatId: String): Unit = unavailable()
    override suspend fun clear(): Unit = unavailable()

    private fun unavailable(): Nothing = throw UnsupportedOperationException(
        "Native DeepSeek integration is disabled. Open the official site in WebView. " +
            "Нативное подключение DeepSeek недоступно; используйте официальный сайт в WebView.",
    )
}
