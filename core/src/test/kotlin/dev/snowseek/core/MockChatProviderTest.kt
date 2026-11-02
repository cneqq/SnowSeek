package dev.snowseek.core

import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MockChatProviderTest {
    @Test fun `blank prompts do not mutate a chat`() = runTest {
        val provider = MockChatProvider(MemoryStore(), 10)
        val chat = provider.createChat()
        expectFailure<IllegalArgumentException> { provider.sendMessage(chat.id, " \n ").collect() }
        assertTrue(provider.getMessages(chat.id).isEmpty())
    }

    @Test fun `stream accumulates text and persists a complete localized answer`() = runTest {
        val store = MemoryStore()
        val provider = MockChatProvider(store, 10)
        val chat = provider.createChat()
        val parts = provider.sendMessage(chat.id, "show code", ChatOptions(true, true, "en")).toList()
        assertTrue(parts.size > 3)
        assertEquals(MessageStatus.GENERATING, parts.first().status)
        assertEquals(MessageStatus.COMPLETE, parts.last().status)
        assertTrue(parts.last().text.contains("```kotlin"))
        assertTrue(parts.last().text.contains("simulated", ignoreCase = true))
        assertTrue(parts.last().reasoning.contains("simulated", ignoreCase = true))
        parts.zipWithNext().forEach { (a, b) ->
            assertTrue(b.text.startsWith(a.text))
            assertTrue(b.reasoning.startsWith(a.reasoning))
            assertEquals(a.id, b.id)
        }
        val restarted = MockChatProvider(store, 0)
        restarted.initialize()
        assertEquals(provider.chats.value, restarted.chats.value)
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT), restarted.getMessages(chat.id).map { it.role })
    }

    @Test fun `Russian is selected by default and demo identity is explicit`() = runTest {
        val provider = MockChatProvider(MemoryStore(), 0)
        val chat = provider.createChat()
        val answer = provider.sendMessage(chat.id, "Привет").toList().last()
        assertTrue(answer.text.contains("демонстрац", ignoreCase = true))
        assertTrue(answer.reasoning.isEmpty())
        assertTrue(provider.getCurrentUser().isDemo)
    }

    @Test fun `stop retains partial reply and allows next request`() = runTest {
        val provider = MockChatProvider(MemoryStore(), 10)
        val chat = provider.createChat()
        val first = launch { provider.sendMessage(chat.id, "hello").collect() }
        runCurrent()
        advanceTimeBy(40)
        runCurrent()
        provider.stopGeneration(chat.id)
        val stopped = provider.getMessages(chat.id).last()
        assertEquals(MessageStatus.STOPPED, stopped.status)
        assertTrue(stopped.text.isNotEmpty())
        provider.sendMessage(chat.id, "again").collect()
        advanceUntilIdle()
        first.join()
        assertEquals(MessageStatus.COMPLETE, provider.getMessages(chat.id).last().status)
    }

    @Test fun `collector cancellation is durably stopped`() = runTest {
        val store = MemoryStore()
        val provider = MockChatProvider(store, 10)
        val chat = provider.createChat()
        val job = launch { provider.sendMessage(chat.id, "hello").collect() }
        runCurrent()
        advanceTimeBy(30)
        job.cancelAndJoin()
        assertEquals(MessageStatus.STOPPED, provider.getMessages(chat.id).last().status)
        val restarted = MockChatProvider(store)
        restarted.initialize()
        assertEquals(MessageStatus.STOPPED, restarted.getMessages(chat.id).last().status)
    }

    @Test fun `collector may stop generation without deadlocking the state lock`() = runTest {
        val provider = MockChatProvider(MemoryStore(), 10)
        val chat = provider.createChat()
        provider.sendMessage(chat.id, "hello").collect { provider.stopGeneration(chat.id) }
        assertEquals(MessageStatus.STOPPED, provider.getMessages(chat.id).last().status)
    }

    @Test fun `cancellation during a storage outage preserves cancellation and known history`() = runTest {
        val store = MemoryStore()
        val provider = MockChatProvider(store, 10)
        val chat = provider.createChat()
        var cancellationBecameIoFailure = false
        val job = launch {
            try { provider.sendMessage(chat.id, "hello").collect() }
            catch (_: IOException) { cancellationBecameIoFailure = true }
        }
        runCurrent()
        store.failWrites = true
        job.cancelAndJoin()
        assertFalse(cancellationBecameIoFailure)
        assertEquals(2, provider.getMessages(chat.id).size)
        assertEquals(MessageStatus.STOPPED, provider.getMessages(chat.id).last().status)
        store.failWrites = false
        provider.sendMessage(chat.id, "retry").collect()
        assertEquals(MessageStatus.COMPLETE, provider.getMessages(chat.id).last().status)
    }

    @Test fun `concurrent send in same chat is rejected without interleaving`() = runTest {
        val provider = MockChatProvider(MemoryStore(), 10)
        val chat = provider.createChat()
        val job = launch { provider.sendMessage(chat.id, "first").collect() }
        runCurrent()
        expectFailure<IllegalStateException> { provider.sendMessage(chat.id, "second").collect() }
        assertEquals(2, provider.getMessages(chat.id).size)
        provider.stopGeneration(chat.id)
        advanceUntilIdle()
        job.join()
    }

    @Test fun `independent chats stream concurrently`() = runTest {
        val provider = MockChatProvider(MemoryStore(), 10)
        val one = provider.createChat()
        val two = provider.createChat()
        val jobs = listOf(one, two).map { chat -> launch { provider.sendMessage(chat.id, "hello").collect() } }
        advanceUntilIdle()
        jobs.forEach { it.join() }
        assertEquals(MessageStatus.COMPLETE, provider.getMessages(one.id).last().status)
        assertEquals(MessageStatus.COMPLETE, provider.getMessages(two.id).last().status)
    }

    @Test fun `deleting an active chat cannot resurrect it`() = runTest {
        val store = MemoryStore()
        val provider = MockChatProvider(store, 10)
        val chat = provider.createChat()
        val job = launch { provider.sendMessage(chat.id, "hello").collect() }
        runCurrent()
        provider.deleteChat(chat.id)
        advanceUntilIdle()
        job.join()
        assertTrue(provider.getChats().isEmpty())
        val restarted = MockChatProvider(store)
        assertTrue(restarted.getChats().isEmpty())
    }

    @Test fun `clear stops all generation and removes durable history`() = runTest {
        val store = MemoryStore()
        val provider = MockChatProvider(store, 10)
        val chat = provider.createChat()
        val job = launch { provider.sendMessage(chat.id, "hello").collect() }
        runCurrent()
        provider.clear()
        advanceUntilIdle()
        job.join()
        assertTrue(provider.chats.value.isEmpty())
        assertTrue(MockChatProvider(store).getChats().isEmpty())
    }

    @Test fun `initialization normalizes interrupted persisted generation only once`() = runTest {
        val interrupted = ChatMessage("reply", MessageRole.ASSISTANT, "partial", status = MessageStatus.GENERATING)
        val store = MemoryStore(listOf(ChatSession("chat", "Saved", listOf(interrupted))))
        val provider = MockChatProvider(store)
        provider.initialize()
        assertEquals(MessageStatus.STOPPED, provider.getMessages("chat").single().status)
        provider.createChat()
        provider.initialize()
        assertEquals(2, provider.getChats().size)
        assertEquals(MessageStatus.STOPPED, MockChatProvider(store).getMessages("chat").single().status)
    }

    @Test fun `failed save never publishes loss of known history`() = runTest {
        val store = MemoryStore()
        val provider = MockChatProvider(store)
        val chat = provider.createChat()
        store.failWrites = true
        expectFailure<IOException> { provider.deleteChat(chat.id) }
        assertEquals(listOf(chat), provider.chats.value)
        expectFailure<IOException> { provider.sendMessage(chat.id, "hello").collect() }
        assertTrue(provider.getMessages(chat.id).isEmpty())
        store.failWrites = false
        provider.sendMessage(chat.id, "retry").collect()
        assertEquals(MessageStatus.COMPLETE, provider.getMessages(chat.id).last().status)
    }

    @Test fun `failed load remains retryable instead of overwriting history`() = runTest {
        val store = MemoryStore(listOf(ChatSession("saved", "Saved")))
        store.failLoads = true
        val provider = MockChatProvider(store)
        expectFailure<IOException> { provider.createChat() }
        store.failLoads = false
        assertEquals("saved", provider.getChats().single().id)
    }

    @Test fun `midstream persistence failure is visible and does not lock future requests`() = runTest {
        val store = MemoryStore()
        val provider = MockChatProvider(store, 10)
        val chat = provider.createChat()
        var failure: Throwable? = null
        val job = launch {
            try { provider.sendMessage(chat.id, "hello").collect() }
            catch (error: IOException) { failure = error }
        }
        runCurrent()
        store.failWrites = true
        advanceUntilIdle()
        job.join()
        assertTrue(failure is IOException)
        assertEquals(MessageStatus.ERROR, provider.getMessages(chat.id).last().status)
        store.failWrites = false
        provider.sendMessage(chat.id, "retry").collect()
        assertEquals(MessageStatus.COMPLETE, provider.getMessages(chat.id).last().status)
    }

    @Test fun `missing chat is rejected`() = runTest {
        val provider = MockChatProvider(MemoryStore(), 0)
        expectFailure<IllegalArgumentException> { provider.sendMessage("missing", "hello").collect() }
        expectFailure<IllegalArgumentException> { provider.getMessages("missing") }
    }

    private class MemoryStore(initial: List<ChatSession> = emptyList()) : ChatStore {
        private var data = Json.encodeToString(initial)
        var failWrites = false
        var failLoads = false
        override suspend fun load(): List<ChatSession> {
            if (failLoads) throw IOException("read unavailable")
            return Json.decodeFromString(data)
        }
        override suspend fun save(chats: List<ChatSession>) {
            if (failWrites) throw IOException("write unavailable")
            data = Json.encodeToString(chats)
        }
    }

    private suspend inline fun <reified T : Throwable> expectFailure(block: () -> Unit) {
        try {
            block()
            fail("Expected ${T::class.java.simpleName}")
        } catch (failure: Throwable) {
            if (failure !is T) throw failure
        }
    }
}
