@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package dev.snowseek.core

import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DeepSeekIntegrationTest {
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject
    private val snapshot = """{"v":{"response":{"message_id":8,"status":"WIP","fragments":[{"type":"THINK","content":"reason"}]}}}"""
    private fun frames(close: Boolean = true) = buildList {
        add(DeepSeekFrame("ready", """{"request_message_id":7,"response_message_id":8}"""))
        add(DeepSeekFrame("message", snapshot))
        add(DeepSeekFrame("message", """{"p":"response/fragments/-1/content","o":"APPEND","v":"ing"}"""))
        add(DeepSeekFrame("message", """{"p":"response/fragments","o":"APPEND","v":[{"type":"RESPONSE","content":"Hello"}]}"""))
        add(DeepSeekFrame("message", """{"p":"response/fragments/-1/content","o":"APPEND","v":" "}"""))
        add(DeepSeekFrame("message", """{"p":"response/fragments/-1/content","v":"world"}"""))
        add(DeepSeekFrame("message", """{"v":"!"}"""))
        add(DeepSeekFrame("message", """{"p":"response","o":"BATCH","v":[{"p":"accumulated_token_usage","v":12},{"p":"status","v":"FINISHED"}]}"""))
        if (close) add(DeepSeekFrame("close", """{"auto_resume":false}"""))
    }
    @Test fun compressedPatchesKeepReasoningSeparateAndRequireClose() {
        val decoder = DeepSeekStreamDecoder()
        frames(false).forEach(decoder::accept)
        assertFalse(decoder.completed())
        assertEquals("reasoning", decoder.message("fallback").reasoning)
        assertEquals("Hello world!", decoder.message("fallback").text)
        assertEquals("8", decoder.responseId)
        decoder.accept(DeepSeekFrame("close", "{}"))
        assertTrue(decoder.completed())
    }
    @Test fun malformedStreamDoesNotExposeResponseInError() {
        try {
            DeepSeekStreamDecoder().accept(DeepSeekFrame("message", "PRIVATE secret body"))
            fail("Expected protocol failure")
        } catch (failure: DeepSeekFailure) { assertFalse(failure.message.orEmpty().contains("PRIVATE")) }
    }
    private inner class FakeTransport(private val close: Boolean = true) : DeepSeekTransport {
        var completionCalls = 0
        var sent: JsonObject? = null
        var cancelled = 0
        var failProof = false
        var hold = false
        val started = CompletableDeferred<Unit>()
        val history = obj("""{"chat_session":{"current_message_id":3,"title":"Remote"},"chat_messages":[
            {"message_id":1,"parent_id":null,"role":"USER","status":"FINISHED","inserted_at":1,"fragments":[{"type":"REQUEST","content":"first"}]},
            {"message_id":2,"parent_id":1,"role":"ASSISTANT","status":"FINISHED","inserted_at":2,"fragments":[{"type":"RESPONSE","content":"reply"}]},
            {"message_id":3,"parent_id":2,"role":"USER","status":"FINISHED","inserted_at":3,"fragments":[{"type":"REQUEST","content":"continue"}]},
            {"message_id":4,"parent_id":1,"role":"ASSISTANT","status":"FINISHED","inserted_at":4,"fragments":[{"type":"RESPONSE","content":"other branch"}]}
        ]}""")
        override suspend fun request(path: String, body: JsonObject?): JsonElement = when {
            path == "/api/v0/users/current" -> obj("""{"id_profile":{"name":"User"}}""")
            path.startsWith("/api/v0/chat_session/fetch_page") -> obj("""{"chat_sessions":[{"id":"one","title":"Remote","updated_at":1}],"has_more":false}""")
            path.startsWith("/api/v0/chat/history_messages") -> history
            path == "/api/v0/chat/create_pow_challenge" -> {
                assertEquals("/api/v0/chat/completion", body?.get("target_path")?.jsonPrimitive?.content)
                obj("""{"challenge":{"target_path":"/api/v0/chat/completion"}}""")
            }
            else -> error("Unexpected request")
        }
        override suspend fun solveOfficialChallenge(challenge: JsonObject): String {
            if (failProof) throw DeepSeekFailure("Official worker unavailable")
            return "fresh-official-proof"
        }
        override fun completion(body: JsonObject, proof: String): Flow<DeepSeekFrame> = flow {
            assertEquals("fresh-official-proof", proof)
            completionCalls++; sent = body
            if (hold) {
                frames(false).take(2).forEach { emit(it) }
                started.complete(Unit)
                awaitCancellation()
            } else frames(close).forEach { emit(it) }
        }
        override fun cancelCompletion() { cancelled++ }
    }
    @Test fun loadsActualHistoryAndFollowsCurrentBranch() = runTest {
        val provider = DeepSeekWebProvider(FakeTransport())
        provider.initialize()
        assertFalse(provider.getCurrentUser().isDemo)
        assertEquals(listOf("1", "2", "3"), provider.getMessages("one").map { it.id })
        assertEquals("continue", provider.chats.value.single().messages.last().text)
    }
    @Test fun sendsParentAndOptionsAndPublishesStreamWithoutDuplicateAssistant() = runTest {
        val transport = FakeTransport()
        val provider = DeepSeekWebProvider(transport)
        provider.initialize()
        val output = provider.sendMessage("one", "test", ChatOptions(deepThink = true, search = true)).toList()
        assertEquals(3, transport.sent!!["parent_message_id"]!!.jsonPrimitive.int)
        assertTrue(transport.sent!!["thinking_enabled"]!!.jsonPrimitive.boolean)
        assertTrue(transport.sent!!["search_enabled"]!!.jsonPrimitive.boolean)
        assertEquals("Hello world!", output.last().text)
        assertEquals(MessageStatus.COMPLETE, output.last().status)
        assertEquals(1, provider.chats.value.single().messages.count { it.id == "8" })
    }
    @Test fun disconnectedStreamIsUncertainAndNeverAutomaticallyResent() = runTest {
        val transport = FakeTransport(close = false)
        val provider = DeepSeekWebProvider(transport)
        provider.initialize()
        try { provider.sendMessage("one", "test").collect(); fail("Expected uncertain delivery") }
        catch (_: DeepSeekDeliveryUncertain) { }
        assertEquals(1, transport.completionCalls)
        assertEquals(MessageStatus.ERROR, provider.chats.value.single().messages.last().status)
        assertTrue(transport.cancelled > 0)
    }
    @Test fun unavailableOfficialProofNeverSendsOrFabricatesAnswer() = runTest {
        val transport = FakeTransport().apply { failProof = true }
        val provider = DeepSeekWebProvider(transport)
        provider.initialize()
        try { provider.sendMessage("one", "test").collect(); fail("Expected official worker failure") }
        catch (failure: DeepSeekFailure) { assertEquals("Official worker unavailable", failure.message) }
        assertEquals(0, transport.completionCalls)
        assertTrue(provider.chats.value.single().messages.last().text.isEmpty())
    }
    @Test fun stopCancelsTransportAndSettlesPartialReply() = runTest {
        val transport = FakeTransport().apply { hold = true }
        val provider = DeepSeekWebProvider(transport)
        provider.initialize()
        val job = launch { provider.sendMessage("one", "test").collect() }
        runCurrent()
        assertTrue(transport.started.isCompleted)
        provider.stopGeneration("one")
        job.cancelAndJoin()
        assertTrue(transport.cancelled > 0)
        assertEquals(MessageStatus.STOPPED, provider.chats.value.single().messages.last().status)
    }
}
