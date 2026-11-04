package dev.snowseek.core

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.*

/** Free web account, with no API keys, proxy, automatic resend or protection bypass. */
class DeepSeekWebProvider(
    private val transport: DeepSeekTransport? = null,
    private val instructionStore: AppliedInstructionStore? = null,
) : ChatProvider {
    private val localInstructions = mutableMapOf<String, ChatInstruction>()

    private suspend fun decodeInstruction(chatId: String, prompt: String): InstructionPrompt.Decoded? {
        if (!prompt.startsWith("Правила ответа в этом диалоге:\n")) return InstructionPrompt.decode(prompt)
        val key = InstructionPrompt.key(chatId, prompt)
        return InstructionPrompt.decode(prompt, localInstructions[key] ?: instructionStore?.load(key))
    }
    private val state = MutableStateFlow<List<ChatSession>>(emptyList())
    override val chats: StateFlow<List<ChatSession>> = state.asStateFlow()
    override val capabilities = ProviderCapabilities(transport != null, transport != null, transport != null, false)
    private val sending = Mutex()
    private val parents = mutableMapOf<String, String?>()
    private var active: String? = null
    private var stopRequested = false
    private fun api() = transport ?: throw UnsupportedOperationException("Sign in through WebView to connect DeepSeek")

    override suspend fun initialize() { getCurrentUser(); getChats() }
    override suspend fun getCurrentUser(): UserProfile {
        val profile = api().request("/api/v0/users/current").jsonObject
        return UserProfile((profile["id_profile"] as? JsonObject)?.text("name") ?: "DeepSeek", false)
    }
    override suspend fun getChats(): List<ChatSession> {
        val result = api().request("/api/v0/chat_session/fetch_page?count=100&lte_cursor.pinned=false").jsonObject
        val previous = state.value.associateBy { it.id }
        val loaded = result.array("chat_sessions").map { raw ->
            val item = raw.jsonObject
            val id = item.text("id") ?: throw DeepSeekFailure("DeepSeek: chat id missing")
            ChatSession(id, item.text("title") ?: "", previous[id]?.messages.orEmpty(), timestamp(item, "updated_at"))
        }
        state.value = loaded
        return loaded
    }
    override suspend fun createChat(): ChatSession {
        val item = api().request("/api/v0/chat_session/create", buildJsonObject { }).jsonObject["chat_session"]?.jsonObject
            ?: throw DeepSeekFailure("DeepSeek: chat was not created")
        val chat = ChatSession(item.text("id") ?: throw DeepSeekFailure("DeepSeek: chat id missing"), "")
        state.update { listOf(chat) + it.filterNot { old -> old.id == chat.id } }
        parents[chat.id] = null
        return chat
    }
    override suspend fun deleteChat(chatId: String) {
        if (active == chatId) throw DeepSeekFailure("Сначала остановите генерацию / Stop generation first")
        api().request("/api/v0/chat_session/delete", buildJsonObject { putJsonArray("chat_session_ids") { add(chatId) } })
        state.update { it.filterNot { chat -> chat.id == chatId } }
        parents.remove(chatId)
    }
    override suspend fun getMessages(chatId: String): List<ChatMessage> {
        api()
        if (active == chatId) return state.value.firstOrNull { it.id == chatId }?.messages.orEmpty()
        val result = api().request("/api/v0/chat/history_messages?chat_session_id=${java.net.URLEncoder.encode(chatId, "UTF-8")}").jsonObject
        val raw = result.array("chat_messages").filterIsInstance<JsonObject>()
        val byId = raw.associateBy { it.text("message_id") }
        val session = result["chat_session"] as? JsonObject
        var tail = session?.text("current_message_id") ?: raw.maxByOrNull { timestamp(it, "inserted_at") }?.text("message_id")
        val chain = mutableListOf<JsonObject>()
        val seen = mutableSetOf<String>()
        while (tail != null && seen.add(tail)) {
            val message = byId[tail] ?: break
            chain.add(message); tail = message.text("parent_id")
        }
        val ordered = if (chain.isNotEmpty()) chain.asReversed() else raw.sortedBy { timestamp(it, "inserted_at") }
        val messages = ordered.mapNotNull { item ->
            val role = when (item.text("role")?.uppercase()) { "USER" -> MessageRole.USER; "ASSISTANT" -> MessageRole.ASSISTANT; else -> return@mapNotNull null }
            val fragments = item.array("fragments").filterIsInstance<JsonObject>()
            fun contents(type: String) = fragments.filter { it.text("type") == type }.joinToString("") { it.text("content").orEmpty() }
            val request = if (role == MessageRole.USER) decodeInstruction(chatId, contents("REQUEST")) else null
            ChatMessage(item.text("message_id") ?: return@mapNotNull null, role,
                request?.text ?: contents(if (role == MessageRole.USER) "REQUEST" else "RESPONSE"), contents("THINK"),
                if (item.text("status") in listOf("FINISHED", "COMPLETE")) MessageStatus.COMPLETE else MessageStatus.STOPPED,
                timestamp(item, "inserted_at"), instruction = request?.instruction)
        }
        parents[chatId] = ordered.lastOrNull()?.text("message_id")
        state.update { list -> list.map { if (it.id == chatId) it.copy(messages = messages, title = session?.text("title") ?: it.title) else it } }
        return messages
    }
    override fun sendMessage(chatId: String, prompt: String, options: ChatOptions): Flow<ChatMessage> = flow {
        val remote = api()
        require(prompt.isNotBlank()) { "Empty prompt" }
        if (!sending.tryLock()) throw DeepSeekFailure("Уже выполняется генерация / Generation already in progress")
        var submitted = false
        val draftId = "pending-${UUID.randomUUID()}"
        var latest = ChatMessage(draftId, MessageRole.ASSISTANT, status = MessageStatus.GENERATING)
        var published = false
        val decoder = DeepSeekStreamDecoder()
        try {
            val previousMessages = getMessages(chatId)
            active = chatId; stopRequested = false
            val instruction = instructionForRequest(previousMessages, null, options.instruction)
            val wirePrompt = instruction?.let { InstructionPrompt.encode(it, prompt) } ?: prompt
            val user = ChatMessage("pending-user-${UUID.randomUUID()}", MessageRole.USER, prompt, instruction = instruction)
            state.update { list -> list.map { if (it.id == chatId) it.copy(messages = it.messages + user + latest) else it } }
            published = true
            val challenge = remote.request("/api/v0/chat/create_pow_challenge", buildJsonObject { put("target_path", "/api/v0/chat/completion") })
                .jsonObject["challenge"]?.jsonObject ?: throw DeepSeekFailure("DeepSeek: challenge missing")
            val proof = remote.solveOfficialChallenge(challenge)
            currentCoroutineContext().ensureActive()
            if (stopRequested) throw CancellationException("Stopped")
            val parent = parents[chatId]
            val payload = buildJsonObject {
                put("chat_session_id", chatId)
                put("parent_message_id", parent?.let { it.toLongOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(it) } ?: JsonNull)
                put("model_type", JsonNull); put("prompt", wirePrompt); putJsonArray("ref_file_ids") { }
                put("thinking_enabled", options.deepThink); put("search_enabled", options.search)
                put("action", JsonNull); put("preempt", false)
            }
            if (instruction != null) {
                val key = InstructionPrompt.key(chatId, wirePrompt)
                instructionStore?.save(key, instruction)
                localInstructions[key] = instruction
            }
            submitted = true
            remote.completion(payload, proof).collect { frame ->
                if (stopRequested) throw CancellationException("Stopped")
                decoder.accept(frame)
                decoder.requestId?.takeIf { frame.event == "ready" }?.let { id ->
                    state.update { chats -> chats.map { chat ->
                        if (chat.id != chatId) chat else chat.copy(
                            messages = chat.messages.map { if (it.id == user.id) it.copy(id = id) else it })
                    } }
                }
                latest = decoder.message(draftId)
                replaceDraft(chatId, draftId, latest, decoder.title)
                emit(latest)
            }
            if (!decoder.completed()) throw DeepSeekDeliveryUncertain()
            latest = decoder.message(draftId, MessageStatus.COMPLETE)
            parents[chatId] = decoder.responseId
            replaceDraft(chatId, draftId, latest, decoder.title); emit(latest)
        } catch (cancelled: CancellationException) {
            if (published) replaceDraft(chatId, draftId, latest.copy(status = MessageStatus.STOPPED), decoder.title)
            throw cancelled
        } catch (failure: Exception) {
            if (stopRequested) {
                if (published) replaceDraft(chatId, draftId, latest.copy(status = MessageStatus.STOPPED), decoder.title)
                throw CancellationException("Stopped")
            }
            if (submitted) {
                val recovered = recoverCompletedResponse(chatId, decoder.responseId)
                if (recovered != null && !stopRequested) {
                    parents[chatId] = recovered.id
                    replaceDraft(chatId, draftId, recovered, decoder.title)
                    emit(recovered)
                    return@flow
                }
            }
            if (published) replaceDraft(chatId, draftId, latest.copy(status = MessageStatus.ERROR), decoder.title)
            if (submitted) throw DeepSeekDeliveryUncertain((failure as? DeepSeekFailure)?.message)
            throw failure
        } finally {
            remote.cancelCompletion(); active = null; sending.unlock()
        }
    }
    private suspend fun recoverCompletedResponse(chatId: String, responseId: String?): ChatMessage? {
        if (responseId == null) return null
        return kotlinx.coroutines.withTimeoutOrNull(4_000) {
            try {
                val url = java.net.URLEncoder.encode(chatId, "UTF-8")
                val history = api().request("/api/v0/chat/history_messages?chat_session_id=$url").jsonObject
                val response = history.array("chat_messages").filterIsInstance<JsonObject>().firstOrNull {
                    it.text("message_id") == responseId && it.text("role")?.equals("ASSISTANT", ignoreCase = true) == true &&
                        it.text("status") in setOf("FINISHED", "COMPLETE")
                } ?: return@withTimeoutOrNull null
                val fragments = response.array("fragments").filterIsInstance<JsonObject>()
                fun contents(type: String) = fragments.filter { it.text("type") == type }.joinToString("") { it.text("content").orEmpty() }
                val answer = contents("RESPONSE")
                if (answer.isBlank()) return@withTimeoutOrNull null
                ChatMessage(responseId, MessageRole.ASSISTANT, answer, contents("THINK"), createdAt = timestamp(response, "inserted_at"))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun replaceDraft(chatId: String, draftId: String, message: ChatMessage, title: String?) {
        state.update { list -> list.map { chat -> if (chat.id != chatId) chat else chat.copy(
            messages = chat.messages.map { if (it.id == draftId || it.id == message.id || it.status == MessageStatus.GENERATING && it.role == MessageRole.ASSISTANT) message else it },
            title = title ?: chat.title, updatedAt = System.currentTimeMillis()) } }
    }
    override suspend fun stopGeneration(chatId: String) {
        val remote = api()
        if (active == chatId) { stopRequested = true; remote.cancelCompletion() }
    }
    override suspend fun clear() { api().cancelCompletion(); state.value = emptyList(); parents.clear() }
    private fun timestamp(item: JsonObject, key: String) = ((item.text(key)?.toDoubleOrNull() ?: 0.0) * 1000).toLong()
}
