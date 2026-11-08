package dev.snowseek.core

import java.util.UUID
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
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
    override val capabilities = ProviderCapabilities(transport != null, transport != null, transport != null, transport != null)
    private val sending = Mutex()
    private val parents = mutableMapOf<String, String?>()
    private val branches = mutableMapOf<String, ChatBranches>()
    private val files = mutableMapOf<String, ChatAttachment>()
    private enum class Operation { SEND, EDIT, CONTINUE, REGENERATE }
    private class Generation(val chatId: String) {
        val responseId = CompletableDeferred<String?>()
        val stopping = Mutex()
        var submitted = false
        var stopRequested = false
        var stopConfirmed = false
        var finished = false
        var ended = false
    }
    private var active: Generation? = null
    private fun api() = transport ?: throw UnsupportedOperationException("Sign in through WebView to connect DeepSeek")

    override suspend fun initialize() { getCurrentUser(); getChats() }
    override suspend fun getCurrentUser(): UserProfile {
        val profile = read("/api/v0/users/current")
        return UserProfile((profile["id_profile"] as? JsonObject)?.text("name") ?: "DeepSeek", false)
    }
    override suspend fun getChats(): List<ChatSession> {
        val previous = state.value.associateBy { it.id }
        val chats = linkedMapOf<String, ChatSession>()
        val cursors = mutableSetOf<String>()
        var cursor = "&lte_cursor.pinned=true"
        while (cursors.add(cursor)) {
            val result = read("/api/v0/chat_session/fetch_page?count=100$cursor")
            val page = result.array("chat_sessions").map { it.jsonObject }
            for (item in page) {
                val id = item.text("id") ?: throw DeepSeekFailure("DeepSeek: chat id missing")
                chats.putIfAbsent(id, ChatSession(id, item.text("title") ?: "", previous[id]?.messages.orEmpty(),
                    timestamp(item, "updated_at"), previous[id]?.versions.orEmpty(),
                    pinned = (item["pinned"] as? JsonPrimitive)?.booleanOrNull == true))
            }
            if ((result["has_more"] as? JsonPrimitive)?.booleanOrNull != true) break
            val last = page.lastOrNull() ?: break
            val updatedAt = last.text("updated_at")?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: break
            val pinned = (last["pinned"] as? JsonPrimitive)?.booleanOrNull == true
            cursor = "&lte_cursor.pinned=$pinned&lte_cursor.updated_at=${if (updatedAt >= 1e11) updatedAt / 1000 else updatedAt}"
        }
        val loaded = chats.values.toList()
        state.value = loaded
        return loaded
    }

    private suspend fun read(path: String): JsonObject {
        for (pause in listOf(500L, 1_500L)) {
            try { return api().request(path).jsonObject }
            catch (_: DeepSeekConnectionFailure) { delay(pause) }
        }
        return api().request(path).jsonObject
    }

    private fun historyPath(chatId: String) =
        "/api/v0/chat/history_messages?chat_session_id=${URLEncoder.encode(chatId, "UTF-8")}"
    override suspend fun createChat(): ChatSession {
        val item = api().request("/api/v0/chat_session/create", buildJsonObject { }).jsonObject["chat_session"]?.jsonObject
            ?: throw DeepSeekFailure("DeepSeek: chat was not created")
        val chat = ChatSession(item.text("id") ?: throw DeepSeekFailure("DeepSeek: chat id missing"), "")
        state.update { listOf(chat) + it.filterNot { old -> old.id == chat.id } }
        parents[chat.id] = null
        return chat
    }

    suspend fun setPinned(chatId: String, pinned: Boolean) {
        api().request("/api/v0/chat_session/batch_update_pinned", buildJsonObject {
            putJsonArray("chat_session_ids") { add(chatId) }
            put("pinned", pinned)
        })
        state.update { chats -> chats.map { if (it.id == chatId) it.copy(pinned = pinned) else it } }
    }

    suspend fun renameChat(chatId: String, title: String) {
        val name = title.trim()
        require(name.isNotEmpty()) { "Chat title cannot be empty" }
        if (active?.chatId == chatId) throw DeepSeekFailure("Сначала остановите генерацию / Stop generation first")
        val result = api().request("/api/v0/chat_session/update_title", buildJsonObject {
            put("chat_session_id", chatId)
            put("title", name)
        }) as? JsonObject
        val savedTitle = result?.text("title")?.takeIf { it.isNotBlank() } ?: name
        state.update { chats -> chats.map { if (it.id == chatId) it.copy(title = savedTitle) else it } }
    }

    override suspend fun deleteChat(chatId: String) {
        if (active?.chatId == chatId) throw DeepSeekFailure("Сначала остановите генерацию / Stop generation first")
        api().request("/api/v0/chat_session/delete", buildJsonObject { putJsonArray("chat_session_ids") { add(chatId) } })
        state.update { it.filterNot { chat -> chat.id == chatId } }
        parents.remove(chatId)
        branches.remove(chatId)
    }
    override suspend fun getMessages(chatId: String): List<ChatMessage> {
        api()
        if (active?.chatId == chatId) return state.value.firstOrNull { it.id == chatId }?.messages.orEmpty()
        val result = read(historyPath(chatId))
        val raw = result.array("chat_messages").filterIsInstance<JsonObject>()
        val session = result["chat_session"] as? JsonObject
        val tree = branches.getOrPut(chatId, ::ChatBranches)
        val cached = state.value.firstOrNull { it.id == chatId }?.messages.orEmpty().associateBy { it.id }
        val decoded = raw.mapNotNull { item ->
            val role = when (item.text("role")?.uppercase()) { "USER" -> MessageRole.USER; "ASSISTANT" -> MessageRole.ASSISTANT; else -> return@mapNotNull null }
            val fragments = item.array("fragments").filterIsInstance<JsonObject>()
            fun contents(type: String) = fragments.filter { it.text("type") == type }.joinToString("") { it.text("content").orEmpty() }
            val request = if (role == MessageRole.USER) decodeInstruction(chatId, contents("REQUEST")) else null
            val message = ChatMessage(item.text("message_id") ?: return@mapNotNull null, role,
                request?.text ?: contents(if (role == MessageRole.USER) "REQUEST" else "RESPONSE"), contents("THINK"),
                if (item.text("status") in listOf("FINISHED", "COMPLETE")) MessageStatus.COMPLETE else MessageStatus.STOPPED,
                timestamp(item, "inserted_at"),
                editable = (item["ban_edit"] as? JsonPrimitive)?.booleanOrNull != true &&
                    (role != MessageRole.USER || fragments.all { it.text("type") in setOf("REQUEST", "FILE") }),
                continuable = role == MessageRole.ASSISTANT && item.text("status") == "INCOMPLETE",
                regenerable = role == MessageRole.ASSISTANT && (item["ban_regenerate"] as? JsonPrimitive)?.booleanOrNull != true,
                attachments = readAttachments(item).ifEmpty { cached[item.text("message_id")]?.attachments.orEmpty() },
                citations = searchCitations(fragments),
                instruction = request?.instruction)
            tree.record(message, item.text("parent_id"))
            message
        }
        val tail = session?.text("current_message_id") ?: raw.maxByOrNull { timestamp(it, "inserted_at") }?.text("message_id")
        val serverPath = tree.path(tail).ifEmpty { decoded.sortedBy { it.createdAt } }
        val messages = tree.path(tree.selectedTail).ifEmpty { serverPath }
        tree.remember(messages)
        showPath(chatId, tree, messages, session?.text("title"))
        return messages
    }

    private fun showPath(chatId: String, tree: ChatBranches, messages: List<ChatMessage>, title: String? = null) {
        parents[chatId] = messages.lastOrNull()?.id
        val versions = tree.versions(messages)
        state.update { list -> list.map { if (it.id == chatId) it.copy(messages = messages,
            versions = versions, title = title ?: it.title) else it } }
    }

    fun switchVersion(chatId: String, messageId: String, versionId: String) {
        if (sending.isLocked || active != null) return
        val chat = state.value.firstOrNull { it.id == chatId } ?: return
        if (versionId == messageId || versionId !in chat.versions[messageId]?.ids.orEmpty()) return
        val tree = branches[chatId] ?: return
        tree.switchTo(versionId).takeIf { it.isNotEmpty() }?.let { showPath(chatId, tree, it) }
    }

    override fun sendMessage(chatId: String, prompt: String, options: ChatOptions): Flow<ChatMessage> =
        generate(chatId, prompt, options)
    override fun editMessage(chatId: String, messageId: String, prompt: String, options: ChatOptions): Flow<ChatMessage> =
        generate(chatId, prompt, options, messageId, Operation.EDIT)
    override fun continueMessage(chatId: String, messageId: String): Flow<ChatMessage> =
        generate(chatId, "", ChatOptions(), messageId, Operation.CONTINUE)
    override fun regenerateMessage(chatId: String, messageId: String, options: ChatOptions): Flow<ChatMessage> =
        generate(chatId, "", options, messageId, Operation.REGENERATE)
    private fun generate(chatId: String, prompt: String, options: ChatOptions,
        targetId: String? = null, operation: Operation = Operation.SEND): Flow<ChatMessage> = flow {
        val remote = api()
        if (operation == Operation.SEND) require(prompt.isNotBlank() || options.attachments.isNotEmpty()) { "Empty prompt" }
        validateAttachments(options.attachments)
        if (active != null || !sending.tryLock()) throw DeepSeekFailure("Уже выполняется генерация / Generation already in progress")
        val generation = Generation(chatId)
        var submitted = false
        val draftId = "pending-${UUID.randomUUID()}"
        var latest = ChatMessage(draftId, MessageRole.ASSISTANT, status = MessageStatus.GENERATING)
        var published = false
        val decoder = DeepSeekStreamDecoder()
        var original: ChatMessage? = null
        fun settleStopped() {
            if (published) replaceDraft(chatId, draftId, latest.copy(status = MessageStatus.STOPPED,
                continuable = decoder.incomplete() || generation.stopConfirmed && !generation.finished), decoder.title)
        }
        try {
            val previousMessages = getMessages(chatId)
            val targetIndex = if (targetId == null) -1 else previousMessages.indexOfFirst { it.id == targetId }
            original = previousMessages.getOrNull(targetIndex)
            val validTarget = when (operation) {
                Operation.SEND -> true
                Operation.EDIT -> original?.role == MessageRole.USER && original.editable
                Operation.CONTINUE -> original?.role == MessageRole.ASSISTANT && original.continuable && targetIndex == previousMessages.lastIndex
                Operation.REGENERATE -> original?.role == MessageRole.ASSISTANT && original.regenerable && original.status != MessageStatus.ERROR
            }
            if (!validTarget || operation != Operation.SEND && targetId?.toLongOrNull() == null)
                throw DeepSeekFailure("Это действие недоступно. Обновите историю. / This action is unavailable; reload history.")
            val attachments = if (operation == Operation.EDIT && !options.replaceAttachments && options.attachments.isEmpty())
                original?.attachments.orEmpty() else options.attachments
            validateAttachments(attachments)
            if (operation == Operation.EDIT && prompt.isBlank() && attachments.isEmpty())
                throw DeepSeekFailure("Введите сообщение или добавьте файл. / Enter a message or attach a file.")
            active = generation
            val instruction = instructionForRequest(previousMessages, original.takeIf { operation == Operation.EDIT },
                options.instruction).takeIf { operation == Operation.SEND || operation == Operation.EDIT }
            val wirePrompt = instruction?.let { InstructionPrompt.encode(it, prompt) } ?: prompt
            val user = ChatMessage("pending-user-${UUID.randomUUID()}", MessageRole.USER, prompt,
                attachments = attachments, instruction = instruction)
            if (operation == Operation.CONTINUE) {
                latest = requireNotNull(original).copy(status = MessageStatus.GENERATING, continuable = false)
                generation.responseId.complete(latest.id)
                replaceDraft(chatId, latest.id, latest, null)
                published = true
            } else if (operation == Operation.SEND) {
                state.update { list -> list.map { if (it.id == chatId) it.copy(messages = it.messages + user + latest) else it } }
                published = true
            }
            val proof = if (operation == Operation.CONTINUE) null else proof("/api/v0/chat/completion")
            currentCoroutineContext().ensureActive()
            if (generation.stopRequested) throw CancellationException("Stopped")
            val parent = parents[chatId]
            val payload = buildJsonObject {
                put("chat_session_id", chatId)
                when (operation) {
                    Operation.CONTINUE -> { put("message_id", requireNotNull(targetId).toLong()); put("fallback_to_resume", true) }
                    Operation.REGENERATE -> {
                        put("child_message_id", requireNotNull(targetId).toLong())
                        put("search_enabled", options.search); put("thinking_enabled", options.deepThink); put("user_options", JsonNull)
                    }
                    Operation.SEND, Operation.EDIT -> {
                        if (operation == Operation.SEND) {
                            put("parent_message_id", parent?.let { it.toLongOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(it) } ?: JsonNull)
                            put("model_type", JsonNull); put("preempt", false)
                        } else put("message_id", requireNotNull(targetId).toLong())
                        put("prompt", wirePrompt); putJsonArray("ref_file_ids") { attachments.forEach { add(it.id) } }
                        put("thinking_enabled", options.deepThink); put("search_enabled", options.search); put("action", JsonNull)
                    }
                }
            }
            if (operation == Operation.EDIT || operation == Operation.REGENERATE) {
                val branch = previousMessages.take(targetIndex) + if (operation == Operation.EDIT) listOf(user, latest) else listOf(latest)
                state.update { list -> list.map { if (it.id == chatId)
                    it.copy(messages = branch) else it } }
                published = true
            }
            if (instruction != null) {
                val key = InstructionPrompt.key(chatId, wirePrompt)
                instructionStore?.save(key, instruction)
                localInstructions[key] = instruction
            }
            submitted = true
            generation.submitted = true
            val stream = when (operation) {
                Operation.SEND -> remote.completion(payload, requireNotNull(proof))
                Operation.EDIT -> remote.editMessage(payload, requireNotNull(proof))
                Operation.CONTINUE -> remote.continueMessage(payload)
                Operation.REGENERATE -> remote.regenerateMessage(payload, requireNotNull(proof))
            }
            stream.collect { frame ->
                decoder.accept(frame)
                if (operation == Operation.CONTINUE && decoder.responseId != null && decoder.responseId != targetId)
                    throw DeepSeekFailure("DeepSeek: unexpected continuation id")
                decoder.requestId?.takeIf { frame.event == "ready" }?.let { id ->
                    state.update { list -> list.map { chat -> if (chat.id != chatId) chat else chat.copy(
                        messages = chat.messages.map { if (it.id == user.id) it.copy(id = id) else it }) } }
                }
                latest = decoder.message(if (operation == Operation.CONTINUE) requireNotNull(targetId) else draftId,
                    previous = original.takeIf { operation == Operation.CONTINUE })
                // Keep receiving ready after an early Stop: only the server can assign this ID.
                if (latest.id != draftId) generation.responseId.complete(latest.id)
                replaceDraft(chatId, draftId, latest, decoder.title)
                emit(latest)
            }
            generation.finished = decoder.completed()
            if (generation.stopRequested) throw CancellationException("Stopped")
            if (decoder.incomplete()) {
                latest = decoder.message(latest.id, MessageStatus.STOPPED)
                parents[chatId] = latest.id
                replaceDraft(chatId, draftId, latest, decoder.title); emit(latest)
                return@flow
            }
            if (!decoder.completed()) throw DeepSeekDeliveryUncertain()
            latest = decoder.message(draftId, MessageStatus.COMPLETE)
            parents[chatId] = decoder.responseId
            replaceDraft(chatId, draftId, latest, decoder.title); emit(latest)
        } catch (cancelled: CancellationException) {
            settleStopped()
            throw cancelled
        } catch (failure: Exception) {
            if (generation.stopRequested) {
                settleStopped()
                throw CancellationException("Stopped")
            }
            if (submitted) {
                val responseId = if (operation == Operation.CONTINUE) targetId
                    else decoder.responseId ?: latest.id.takeIf { it.toLongOrNull() != null }
                val requestId = decoder.requestId.takeIf {
                    operation == Operation.SEND || operation == Operation.EDIT && it != targetId
                }
                val recovered = try {
                    recoverResponse(generation, responseId, requestId, original.takeIf { operation == Operation.CONTINUE })
                }
                catch (cancelled: CancellationException) {
                    settleStopped()
                    throw cancelled
                }
                if (generation.stopRequested) {
                    settleStopped()
                    throw CancellationException("Stopped")
                }
                if (recovered != null) {
                    generation.finished = true
                    parents[chatId] = recovered.id
                    replaceDraft(chatId, draftId, recovered, decoder.title)
                    emit(recovered)
                    return@flow
                }
            }
            if (published) replaceDraft(chatId, draftId, latest.copy(status = MessageStatus.ERROR, continuable = false, regenerable = false), decoder.title)
            if (submitted) throw DeepSeekDeliveryUncertain((failure as? DeepSeekFailure)?.message)
            throw failure
        } finally {
            if (published) {
                val tree = branches.getOrPut(chatId, ::ChatBranches)
                val messages = state.value.firstOrNull { it.id == chatId }?.messages.orEmpty()
                tree.remember(messages)
                // Keep the active branch across history refreshes once DeepSeek assigns IDs.
                if (operation == Operation.EDIT || operation == Operation.REGENERATE || tree.selectedTail != null) {
                    tree.selectedTail = messages.lastOrNull()?.id?.takeIf { id ->
                        id.toLongOrNull() != null && messages.all { it.id.toLongOrNull() != null }
                    }
                }
                val versions = tree.versions(messages)
                state.update { list -> list.map { if (it.id == chatId) it.copy(versions = versions) else it } }
            }
            generation.ended = true
            generation.responseId.complete(null)
            remote.cancelCompletion()
            if (active === generation && !generation.stopping.isLocked) active = null
            sending.unlock()
        }
    }
    private suspend fun recoverResponse(
        generation: Generation, responseId: String?, requestId: String?, previous: ChatMessage?,
    ): ChatMessage? {
        if (responseId == null && requestId == null) return null
        return withTimeoutOrNull(12_000) {
            for (pause in listOf(0L, 750L, 2_000L, 4_000L)) {
                delay(pause)
                if (generation.stopRequested) return@withTimeoutOrNull null
                val history = try { api().request(historyPath(generation.chatId)) as? JsonObject }
                catch (_: DeepSeekConnectionFailure) { continue }
                catch (_: DeepSeekFailure) { return@withTimeoutOrNull null }
                if (history == null) return@withTimeoutOrNull null
                val response = history.array("chat_messages").filterIsInstance<JsonObject>().filter {
                    it.text("role")?.equals("ASSISTANT", ignoreCase = true) == true &&
                        if (responseId != null) it.text("message_id") == responseId else it.text("parent_id") == requestId
                }.singleOrNull() ?: continue
                val id = response.text("message_id") ?: continue
                val status = when (response.text("status")) {
                    "FINISHED", "COMPLETE" -> MessageStatus.COMPLETE
                    "INCOMPLETE" -> MessageStatus.STOPPED
                    else -> continue
                }
                val fragments = response.array("fragments").filterIsInstance<JsonObject>()
                fun contents(type: String) = fragments.filter { it.text("type") == type }.joinToString("") { it.text("content").orEmpty() }
                val answer = contents("RESPONSE")
                val reasoning = contents("THINK")
                if (previous != null && status == previous.status && answer == previous.text && reasoning == previous.reasoning) continue
                return@withTimeoutOrNull ChatMessage(id, MessageRole.ASSISTANT, answer, reasoning,
                    status, timestamp(response, "inserted_at"), continuable = status == MessageStatus.STOPPED,
                    citations = searchCitations(fragments),
                    regenerable = (response["ban_regenerate"] as? JsonPrimitive)?.booleanOrNull != true)
            }
            null
        }
    }

    private fun replaceDraft(chatId: String, draftId: String, message: ChatMessage, title: String?) {
        state.update { list -> list.map { chat -> if (chat.id != chatId) chat else chat.copy(
            messages = chat.messages.map { if (it.id == draftId || it.id == message.id || it.status == MessageStatus.GENERATING && it.role == MessageRole.ASSISTANT)
                message.copy(uiKey = it.uiKey) else it },
            title = title ?: chat.title, updatedAt = System.currentTimeMillis()) } }
    }
    override suspend fun stopGeneration(chatId: String) {
        val remote = api()
        val generation = active?.takeIf { it.chatId == chatId } ?: return
        generation.stopping.withLock {
            try {
                if (generation.stopConfirmed || generation.finished) return@withLock
                generation.stopRequested = true
                if (!generation.submitted) return@withLock
                val id = withTimeoutOrNull(10_000) { generation.responseId.await() }?.toLongOrNull()
                if (generation.finished) return@withLock
                if (id == null) throw DeepSeekFailure("DeepSeek: response id unavailable")
                remote.request("/api/v0/chat/stop_stream", buildJsonObject {
                    put("chat_session_id", chatId)
                    put("message_id", id)
                })
                generation.stopConfirmed = true
                if (!generation.finished) state.update { list -> list.map { chat -> if (chat.id != chatId) chat else chat.copy(
                    messages = chat.messages.map { if (it.id == id.toString()) it.copy(continuable = true) else it }) } }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                throw DeepSeekFailure("Остановка на сервере DeepSeek не подтверждена. Получение ответа прекращено; обновите историю перед повтором. / Server stop was not confirmed. Receiving stopped; reload history before retrying.")
            } finally {
                // An old stop request must never disconnect a later generation.
                if (active === generation) {
                    remote.cancelCompletion()
                    if (generation.ended) active = null
                }
            }
        }
    }
    override suspend fun clear() {
        api().cancelCompletion()
        state.value = emptyList()
        parents.clear()
        branches.clear()
        files.clear()
        localInstructions.clear()
    }
    private suspend fun proof(target: String): String {
        val challenge = api().request("/api/v0/chat/create_pow_challenge", buildJsonObject { put("target_path", target) })
            .jsonObject["challenge"]?.jsonObject ?: throw DeepSeekFailure("DeepSeek: challenge missing")
        return api().solveOfficialChallenge(challenge)
    }
    private fun validateAttachments(attachments: List<ChatAttachment>) {
        if (attachments.size > 10 || attachments.any { it.status != "SUCCESS" || it.id.isBlank() })
            throw DeepSeekFailure("Файлы ещё не готовы или их слишком много. / Files are not ready or there are too many attachments.")
    }
    private fun readAttachments(item: JsonObject): List<ChatAttachment> {
        val result = linkedMapOf<String, ChatAttachment>()
        val fragmentFiles = item.array("fragments").filterIsInstance<JsonObject>()
            .filter { it.text("type") == "FILE" }.flatMap { it.array("files") }
        val entries = item.array("ref_file_ids") + item.array("files") + item.array("ref_files") + fragmentFiles
        for (raw in entries) {
            val metadata = raw as? JsonObject
            val id = metadata?.text("id") ?: metadata?.text("file_id") ?: (raw as? JsonPrimitive)?.contentOrNull ?: continue
            val previous = result[id] ?: files[id] ?: ChatAttachment(id, "Вложение")
            val file = if (metadata != null) previous.copy(
                name = metadata.text("file_name") ?: metadata.text("name") ?: previous.name,
                size = metadata.text("file_size")?.toLongOrNull() ?: previous.size,
                status = metadata.text("status") ?: previous.status,
                    isImage = metadata.text("is_image")?.toBooleanStrictOrNull() ?: previous.isImage,
                previewUrl = if (metadata.containsKey("signed_path") || metadata.containsKey("audit_result")) {
                    attachmentPreviewUrl(metadata)
                } else previous.previewUrl,
            ) else previous
            result[id] = file
            files[id] = file
        }
        return result.values.toList()
    }

    private fun attachmentPreviewUrl(file: JsonObject): String? {
        if (file.text("is_image") != "true" || file.text("audit_result") != "pass") return null
        val path = file.text("signed_path") ?: return null
        val candidate = if (path.startsWith('/') && !path.startsWith("//")) "https://files.deepseeksvc.com/api$path" else path
        val uri = runCatching { java.net.URI(candidate) }.getOrNull() ?: return null
        return candidate.takeIf {
            uri.scheme == "https" && uri.host == "files.deepseeksvc.com" && uri.rawUserInfo == null &&
                uri.rawFragment == null && uri.port in listOf(-1, 443)
        }
    }
    suspend fun uploadAttachment(file: UploadFile, deepThink: Boolean = false): ChatAttachment {
        val remote = api()
        if (file.size !in 0..50L * 1024 * 1024) throw DeepSeekFailure("Лимит приложения — 50 МиБ на файл. / App file limit: 50 MiB.")
        if (active != null || !sending.tryLock()) throw DeepSeekFailure("Дождитесь завершения текущего запроса. / Wait for the current request.")
        try {
            var metadata = remote.uploadFile(file, proof("/api/v0/file/upload_file"), deepThink).jsonObject
            val id = metadata.text("id") ?: throw DeepSeekFailure("DeepSeek: file id missing")
            val ready = withTimeoutOrNull(60_000) {
                while (true) {
                    when (metadata.text("status")) {
                        "SUCCESS" -> return@withTimeoutOrNull ChatAttachment(id, metadata.text("file_name") ?: file.name,
                            metadata.text("file_size")?.toLongOrNull() ?: file.size,
                            isImage = metadata.text("is_image") == "true" || file.contentType.startsWith("image/"))
                        "PENDING", "PROCESSING", "PARSING" -> Unit
                        else -> throw DeepSeekFailure("DeepSeek не смог обработать файл. / DeepSeek could not process this file.")
                    }
                    delay(500)
                    val page = remote.request("/api/v0/file/fetch_files?file_ids=${java.net.URLEncoder.encode(id, "UTF-8")}").jsonObject
                    metadata = page.array("files").filterIsInstance<JsonObject>().firstOrNull { it.text("id") == id }
                        ?: throw DeepSeekFailure("DeepSeek: uploaded file missing")
                }
                @Suppress("UNREACHABLE_CODE") null
            } ?: throw DeepSeekFailure("Обработка файла не завершилась за минуту. Выберите файл повторно. / File processing timed out; select it again.")
            files[id] = ready
            return ready
        } finally { sending.unlock() }
    }
    private fun timestamp(item: JsonObject, key: String) = ((item.text(key)?.toDoubleOrNull() ?: 0.0) * 1000).toLong()
}
