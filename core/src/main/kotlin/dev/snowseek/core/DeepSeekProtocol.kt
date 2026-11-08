package dev.snowseek.core

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.*

/** Requests go only to the official origin. Implementations must never log credentials or bodies. */
interface DeepSeekTransport {
    suspend fun request(path: String, body: JsonObject? = null): JsonElement
    suspend fun solveOfficialChallenge(challenge: JsonObject): String
    fun completion(body: JsonObject, proof: String): Flow<DeepSeekFrame>
    fun editMessage(body: JsonObject, proof: String): Flow<DeepSeekFrame> =
        throw UnsupportedOperationException("Message editing is unavailable")
    fun continueMessage(body: JsonObject): Flow<DeepSeekFrame> =
        throw UnsupportedOperationException("Continuing is unavailable")
    fun regenerateMessage(body: JsonObject, proof: String): Flow<DeepSeekFrame> =
        throw UnsupportedOperationException("Regeneration is unavailable")
    suspend fun uploadFile(file: UploadFile, proof: String, deepThink: Boolean): JsonElement =
        throw UnsupportedOperationException("Attachments are unavailable")
    fun cancelCompletion()
}
data class DeepSeekFrame(val event: String, val data: String)
open class DeepSeekFailure(message: String) : Exception(message)
class DeepSeekConnectionFailure : DeepSeekFailure(
    "Не удалось соединиться с DeepSeek. Проверьте сеть. / Could not connect to DeepSeek; check your network.",
)
class DeepSeekDeliveryUncertain(detail: String? = null) : DeepSeekFailure(
    "Отправка могла быть принята DeepSeek. Откройте диалог заново перед повтором. / Delivery may have succeeded; reload history before retrying." +
        (detail?.let { "\n$it" } ?: ""),
)
internal fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.array(key: String): JsonArray = get(key) as? JsonArray ?: JsonArray(emptyList())

internal fun searchCitations(fragments: List<JsonObject>): List<SearchCitation> =
    fragments.filter { it.text("type") == "SEARCH" && it.text("status") != "FAILED" }
        .flatMap { it.array("results").filterIsInstance<JsonObject>() }
        .mapNotNull { result ->
            val index = (result.text("cite_index") ?: result.text("citeIndex"))?.toIntOrNull() ?: return@mapNotNull null
            val url = result.text("url") ?: return@mapNotNull null
            val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return@mapNotNull null
            if (index < 1 || uri.scheme !in setOf("http", "https") || uri.host == null || uri.rawUserInfo != null) return@mapNotNull null
            SearchCitation(index, result.text("title").orEmpty(), url)
        }.distinctBy { it.index }

/** Reconstructs the compressed p/o/v patches verified against the supplied HAR streams. */
class DeepSeekStreamDecoder {
    private var root = JsonObject(emptyMap())
    private var path = ""
    private var operation = "SET"
    var responseId: String? = null
        private set
    var requestId: String? = null
        private set
    var title: String? = null
        private set
    var closed = false
        private set
    fun accept(frame: DeepSeekFrame) {
        val obj = try { Json.parseToJsonElement(frame.data) as? JsonObject }
            catch (_: Exception) { throw DeepSeekFailure("DeepSeek: invalid SSE JSON") }
            ?: throw DeepSeekFailure("DeepSeek: invalid SSE event")
        when (frame.event) {
            "ready" -> {
                responseId = obj.text("response_message_id")
                requestId = obj.text("request_message_id")
            }
            "title" -> title = obj.text("content")
            "close" -> closed = true
            "update_session" -> Unit
            "message", "" -> {
                val value = obj["v"] ?: throw DeepSeekFailure("DeepSeek: unknown stream format")
                if (value is JsonObject && value["response"] is JsonObject) {
                    root = value; path = ""; operation = "SET"
                } else {
                    obj.text("p")?.let { path = it }
                    obj.text("o")?.let { operation = it }
                    root = apply(root, path, operation, value) as? JsonObject
                        ?: throw DeepSeekFailure("DeepSeek: invalid response snapshot")
                }
            }
            "error" -> throw DeepSeekFailure("DeepSeek прервал генерацию / DeepSeek interrupted generation")
            else -> throw DeepSeekFailure("DeepSeek: unknown stream event")
        }
    }
    fun message(fallbackId: String, status: MessageStatus = MessageStatus.GENERATING, previous: ChatMessage? = null): ChatMessage {
        val response = root["response"] as? JsonObject
        if (response == null && previous != null) return previous.copy(id = responseId ?: fallbackId,
            status = status, continuable = false)
        val fragments = response?.array("fragments").orEmpty().filterIsInstance<JsonObject>()
        return ChatMessage(responseId ?: response?.text("message_id") ?: fallbackId, MessageRole.ASSISTANT,
            fragments.filter { it.text("type") == "RESPONSE" }.joinToString("") { it.text("content").orEmpty() },
            fragments.filter { it.text("type") == "THINK" }.joinToString("") { it.text("content").orEmpty() }, status,
            continuable = response?.text("status") == "INCOMPLETE",
            citations = searchCitations(fragments),
            regenerable = (response?.get("ban_regenerate") as? JsonPrimitive)?.booleanOrNull != true)
    }
    fun completed(): Boolean = closed && (root["response"] as? JsonObject)?.text("status") == "FINISHED"
    fun incomplete(): Boolean = closed && (root["response"] as? JsonObject)?.text("status") == "INCOMPLETE"
    private fun apply(node: JsonElement, at: String, op: String, value: JsonElement): JsonElement {
        if (op == "BATCH") {
            var result = node
            (value as? JsonArray ?: throw DeepSeekFailure("DeepSeek: invalid BATCH")).forEach { element ->
                val child = element as? JsonObject ?: throw DeepSeekFailure("DeepSeek: invalid patch")
                val childPath = listOf(at, child.text("p").orEmpty()).filter { it.isNotEmpty() }.joinToString("/")
                result = apply(result, childPath, child.text("o") ?: "SET", child["v"] ?: JsonNull)
            }
            return result
        }
        return change(node, at.split('/').filter { it.isNotEmpty() }, op, value)
    }
    private fun change(node: JsonElement, parts: List<String>, op: String, value: JsonElement): JsonElement {
        if (parts.isEmpty()) return when (op) {
            "SET" -> value
            "APPEND" -> when {
                node is JsonArray && value is JsonArray -> JsonArray(node + value)
                node is JsonPrimitive && node.isString && value is JsonPrimitive && value.isString -> {
                    if (node.content.length + value.content.length > 2_000_000) throw DeepSeekFailure("DeepSeek: response too large")
                    JsonPrimitive(node.content + value.content)
                }
                else -> throw DeepSeekFailure("DeepSeek: incompatible APPEND")
            }
            else -> throw DeepSeekFailure("DeepSeek: unsupported patch operation")
        }
        val head = parts.first()
        return when (node) {
            is JsonObject -> JsonObject(node.toMutableMap().apply {
                val child = node[head] ?: if (parts.size == 1 && op == "SET") JsonNull else throw DeepSeekFailure("DeepSeek: missing patch path")
                put(head, change(child, parts.drop(1), op, value))
            })
            is JsonArray -> {
                val index = if (head == "-1") node.lastIndex else head.toIntOrNull() ?: -1
                if (index !in node.indices) throw DeepSeekFailure("DeepSeek: invalid fragment index")
                JsonArray(node.toMutableList().apply { set(index, change(node[index], parts.drop(1), op, value)) })
            }
            else -> throw DeepSeekFailure("DeepSeek: invalid patch path")
        }
    }
}
