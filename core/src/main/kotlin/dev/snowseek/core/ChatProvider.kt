@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package dev.snowseek.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

@Serializable
enum class MessageRole { USER, ASSISTANT }

@Serializable
enum class MessageStatus { COMPLETE, GENERATING, STOPPED, ERROR }

@Serializable
data class ChatMessage(
    val id: String,
    val role: MessageRole,
    val text: String = "",
    val reasoning: String = "",
    val status: MessageStatus = MessageStatus.COMPLETE,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
data class ChatSession(
    val id: String,
    val title: String,
    val messages: List<ChatMessage> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val updatedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class ChatOptions(
    val deepThink: Boolean = false,
    val search: Boolean = false,
    val language: String = "ru",
)

@Serializable
data class UserProfile(val displayName: String, val isDemo: Boolean)

@Serializable
data class ProviderCapabilities(
    val streaming: Boolean,
    val deepThink: Boolean,
    val search: Boolean,
    val regeneration: Boolean,
)

/** Implementations must atomically replace history or throw without damaging the last good snapshot. */
interface ChatStore {
    suspend fun load(): List<ChatSession>
    suspend fun save(chats: List<ChatSession>)
}

interface ChatProvider {
    val chats: StateFlow<List<ChatSession>>
    val capabilities: ProviderCapabilities
    suspend fun initialize()
    suspend fun getCurrentUser(): UserProfile
    suspend fun getChats(): List<ChatSession>
    suspend fun createChat(): ChatSession
    suspend fun deleteChat(chatId: String)
    suspend fun getMessages(chatId: String): List<ChatMessage>
    fun sendMessage(chatId: String, prompt: String, options: ChatOptions = ChatOptions()): Flow<ChatMessage>
    suspend fun stopGeneration(chatId: String)
    suspend fun clear()
}
