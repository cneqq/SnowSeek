package dev.snowseek.core

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** A local, deterministic demonstration. This provider never connects to DeepSeek or the web. */
class MockChatProvider(
    private val store: ChatStore,
    private val tokenDelayMillis: Long = 24L,
) : ChatProvider {
    init { require(tokenDelayMillis >= 0) { "Token delay must not be negative" } }

    private val state = MutableStateFlow<List<ChatSession>>(emptyList())
    override val chats: StateFlow<List<ChatSession>> = state.asStateFlow()
    override val capabilities = ProviderCapabilities(true, true, true, false)
    private val mutex = Mutex()
    private val active = mutableMapOf<String, Generation>()
    private var initialized = false

    override suspend fun initialize() {
        transaction {
            if (initialized) return@transaction
            val loaded = store.load()
            val normalized = loaded.map { chat ->
                chat.copy(messages = chat.messages.map { message ->
                    if (message.status == MessageStatus.GENERATING) message.copy(status = MessageStatus.STOPPED)
                    else message
                })
            }
            if (normalized != loaded) store.save(normalized)
            state.value = normalized
            initialized = true
        }
    }

    override suspend fun getCurrentUser(): UserProfile {
        initialize()
        return UserProfile("Demo user / Демо-пользователь", isDemo = true)
    }

    override suspend fun getChats(): List<ChatSession> {
        initialize()
        return state.value
    }

    override suspend fun createChat(): ChatSession {
        initialize()
        return transaction {
            val chat = ChatSession(id(), "Новый чат")
            commit(listOf(chat) + state.value)
            chat
        }
    }

    override suspend fun deleteChat(chatId: String) {
        initialize()
        transaction {
            if (state.value.none { it.id == chatId }) return@transaction
            commit(state.value.filterNot { it.id == chatId })
            active.remove(chatId)?.stopped?.complete(Unit)
        }
    }

    override suspend fun getMessages(chatId: String): List<ChatMessage> {
        initialize()
        return state.value.find { it.id == chatId }?.messages
            ?: throw IllegalArgumentException("Chat does not exist / Чат не найден")
    }

    override fun sendMessage(chatId: String, prompt: String, options: ChatOptions): Flow<ChatMessage> = flow {
        require(prompt.isNotBlank()) { "Message must not be empty / Введите сообщение" }
        initialize()
        // Keep the identity available to finally even if cancellation happens while committing
        // the initial request, before transaction() returns to the collector.
        val generation = Generation(id())
        var endStatus = MessageStatus.STOPPED
        var failure: Throwable? = null
        try {
            transaction {
                val chat = state.value.find { it.id == chatId }
                    ?: throw IllegalArgumentException("Chat does not exist / Чат не найден")
                check(chatId !in active) { "A reply is already generating / Дождитесь ответа или остановите генерацию" }
                val user = ChatMessage(id(), MessageRole.USER, text = prompt.trim())
                val reply = ChatMessage(generation.messageId, MessageRole.ASSISTANT, status = MessageStatus.GENERATING)
                val next = chat.copy(
                    title = if (chat.messages.isEmpty()) prompt.trim().lineSequence().first().take(48) else chat.title,
                    messages = chat.messages + user + reply,
                    updatedAt = System.currentTimeMillis(),
                )
                commit(replace(next))
                active[chatId] = generation
            }
            emit(currentReply(chatId, generation) ?: return@flow)
            val response = responseFor(prompt, options)
            var reasoning = ""
            var text = ""
            for (chunk in response.reasoning.chunked(16)) {
                if (awaitStop(generation)) return@flow
                reasoning += chunk
                emit(updateReply(chatId, generation) { it.copy(reasoning = reasoning) } ?: return@flow)
            }
            for (chunk in response.text.chunked(16)) {
                if (awaitStop(generation)) return@flow
                text += chunk
                emit(updateReply(chatId, generation) { it.copy(text = text) } ?: return@flow)
            }
            emit(updateReply(chatId, generation) { it.copy(status = MessageStatus.COMPLETE) } ?: return@flow)
        } catch (error: Throwable) {
            failure = error
            if (error !is CancellationException) endStatus = MessageStatus.ERROR
            throw error
        } finally {
            // Collection belongs to the caller. Cancellation must also settle its partial reply.
            try {
                withContext(NonCancellable) { finishGeneration(chatId, generation, endStatus) }
            } catch (cleanupError: Throwable) {
                val original = failure
                if (original == null) throw cleanupError
                if (original !== cleanupError) original.addSuppressed(cleanupError)
            }
        }
    }

    override suspend fun stopGeneration(chatId: String) {
        initialize()
        transaction {
            val generation = active[chatId] ?: return@transaction
            val chat = state.value.find { it.id == chatId } ?: return@transaction
            val next = chat.copy(messages = chat.messages.map {
                if (it.id == generation.messageId && it.status == MessageStatus.GENERATING)
                    it.copy(status = MessageStatus.STOPPED) else it
            }, updatedAt = System.currentTimeMillis())
            commit(replace(next))
            active.remove(chatId)
            generation.stopped.complete(Unit)
        }
    }

    override suspend fun clear() {
        initialize()
        transaction {
            commit(emptyList())
            active.values.forEach { it.stopped.complete(Unit) }
            active.clear()
        }
    }

    private suspend fun awaitStop(generation: Generation): Boolean {
        if (generation.stopped.isCompleted) return true
        if (tokenDelayMillis == 0L) return false
        return withTimeoutOrNull(tokenDelayMillis) { generation.stopped.await(); true } == true
    }

    private suspend fun currentReply(chatId: String, generation: Generation): ChatMessage? = mutex.withLock {
        if (active[chatId] !== generation || generation.stopped.isCompleted) null
        else state.value.find { it.id == chatId }?.messages?.find { it.id == generation.messageId }
    }

    private suspend fun updateReply(
        chatId: String,
        generation: Generation,
        transform: (ChatMessage) -> ChatMessage,
    ): ChatMessage? = transaction {
        if (active[chatId] !== generation || generation.stopped.isCompleted) return@transaction null
        val chat = state.value.find { it.id == chatId } ?: return@transaction null
        val old = chat.messages.find { it.id == generation.messageId } ?: return@transaction null
        val reply = transform(old)
        commit(replace(chat.copy(
            messages = chat.messages.map { if (it.id == reply.id) reply else it },
            updatedAt = System.currentTimeMillis(),
        )))
        if (reply.status == MessageStatus.COMPLETE) {
            active.remove(chatId)
            generation.stopped.complete(Unit)
        }
        reply
    }

    private suspend fun finishGeneration(chatId: String, generation: Generation, status: MessageStatus) {
        mutex.withLock {
            if (active[chatId] !== generation) return@withLock
            try {
                val chat = state.value.find { it.id == chatId } ?: return@withLock
                val next = replace(chat.copy(messages = chat.messages.map {
                    if (it.id == generation.messageId && it.status == MessageStatus.GENERATING)
                        it.copy(status = status) else it
                }, updatedAt = System.currentTimeMillis()))
                try {
                    store.save(next)
                } finally {
                    // Preserve known history and unlock the UI even if storage is unavailable.
                    // A durable GENERATING snapshot is normalized to STOPPED on restart.
                    state.value = next
                }
            } finally {
                active.remove(chatId)
                generation.stopped.complete(Unit)
            }
        }
    }

    private suspend fun commit(next: List<ChatSession>) {
        store.save(next)
        state.value = next
    }

    /**
     * Once a durable write starts, finish publishing it and updating generation ownership.
     * FileChatStore switches to IO: cancellation on its return must not split disk and memory.
     * Waiting for the lock and streaming outside these short transactions remain cancellable.
     */
    private suspend fun <T> transaction(block: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        val result = mutex.withLock {
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) { block() }
        }
        currentCoroutineContext().ensureActive()
        return result
    }

    private fun replace(chat: ChatSession): List<ChatSession> = state.value
        .map { if (it.id == chat.id) chat else it }
        .sortedByDescending { it.updatedAt }

    private fun id(): String = UUID.randomUUID().toString()
    private class Generation(val messageId: String, val stopped: CompletableDeferred<Unit> = CompletableDeferred())
    private data class Response(val text: String, val reasoning: String)

    private fun responseFor(prompt: String, options: ChatOptions): Response {
        val english = options.language.startsWith("en", ignoreCase = true)
        val reasoning = if (!options.deepThink) "" else if (english)
            "Simulated DeepThink summary: identify the topic, choose a small example, and check its result. This is a scripted demonstration, not model reasoning."
        else "Имитация DeepThink: определим тему, выберем короткий пример и проверим результат. Это заданная демонстрационная сводка, а не рассуждения модели."
        val search = if (!options.search) "" else if (english)
            "\n\n> Search is simulated. No web search was performed; there are no live sources or current facts."
        else "\n\n> Search моделируется: поиск в интернете не выполнялся, актуальные сведения и источники не получены."
        val codeRequested = Regex("code|код|kotlin|python|программ", RegexOption.IGNORE_CASE).containsMatchIn(prompt)
        val body = if (english) buildString {
            append("## Demo response\n\nThis is a **local demonstration** of SnowSeek. No request was sent to DeepSeek.\n\n")
            if (codeRequested) append("Here is a small Kotlin example:\n\n")
            else append("Try the chat features:\n\n1. Enable DeepThink to see a simulated summary.\n2. Enable Search to see its simulation notice.\n3. Stop a reply and keep the partial text.\n\nA small code example:\n\n")
            append("```kotlin\nfun greet(name: String): String {\n    return \"Hello, \$name!\"\n}\n\nprintln(greet(\"SnowSeek\"))\n```\n\nThe output is `Hello, SnowSeek!`. You can select text or copy the code.")
        } else buildString {
            append("## Демонстрационный ответ\n\nЭто **локальная демонстрация** SnowSeek. Запрос в DeepSeek не отправлялся.\n\n")
            if (codeRequested) append("Короткий пример на Kotlin:\n\n")
            else append("Попробуйте возможности чата:\n\n1. Включите DeepThink для демонстрационной сводки.\n2. Включите Search для имитации режима поиска.\n3. Остановите ответ: уже полученный текст сохранится.\n\nПример кода:\n\n")
            append("```kotlin\nfun greet(name: String): String {\n    return \"Привет, \$name!\"\n}\n\nprintln(greet(\"SnowSeek\"))\n```\n\nРезультат: `Привет, SnowSeek!`. Текст можно выделить, а код — скопировать.")
        }
        return Response(body + search, reasoning)
    }
}
