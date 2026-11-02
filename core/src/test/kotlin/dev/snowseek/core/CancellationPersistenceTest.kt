package dev.snowseek.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CancellationPersistenceTest {
    @Test fun `initial send cancellation after disk write retains messages and settles generation`() = runTest {
        val store = CancelAfterWriteStore(StandardTestDispatcher(testScheduler, "storage"))
        val provider = MockChatProvider(store, 0)
        val chat = provider.createChat()
        val operation = launch(start = CoroutineStart.LAZY) {
            provider.sendMessage(chat.id, "saved before cancellation").collect()
        }
        store.cancelAfterWrite = operation
        operation.start()
        operation.join()
        assertTrue(operation.isCancelled)
        val messages = provider.getMessages(chat.id)
        assertEquals(2, messages.size)
        assertEquals("saved before cancellation", messages.first().text)
        assertEquals(MessageStatus.STOPPED, messages.last().status)
        assertEquals(provider.chats.value, store.snapshot)
        // A subsequent mutation must not overwrite the interrupted request or encounter orphaned active state.
        provider.createChat()
        provider.sendMessage(chat.id, "retry").collect()
        assertEquals(4, provider.getMessages(chat.id).size)
        assertEquals(MessageStatus.COMPLETE, provider.getMessages(chat.id).last().status)
        assertEquals(provider.chats.value, MockChatProvider(store, 0).getChats())
    }

    @Test fun `create cancellation after disk write publishes committed chat before next mutation`() = runTest {
        val store = CancelAfterWriteStore(StandardTestDispatcher(testScheduler, "storage"))
        val provider = MockChatProvider(store, 0)
        provider.initialize()
        val operation = launch(start = CoroutineStart.LAZY) { provider.createChat() }
        store.cancelAfterWrite = operation
        operation.start()
        operation.join()
        assertEquals(1, provider.chats.value.size)
        assertEquals(store.snapshot, provider.chats.value)
        val savedId = store.snapshot.single().id
        provider.createChat()
        assertEquals(2, store.snapshot.size)
        assertTrue(store.snapshot.any { it.id == savedId })
    }

    @Test fun `stream update cancellation keeps the committed partial text`() = runTest {
        val store = CancelAfterWriteStore(StandardTestDispatcher(testScheduler, "storage"))
        val provider = MockChatProvider(store, 10)
        val chat = provider.createChat()
        val operation = launch {
            provider.sendMessage(chat.id, "hello").collect { message ->
                if (message.text.isEmpty()) store.cancelAfterWrite = currentCoroutineContext()[Job]
            }
        }
        operation.join()
        val reply = provider.getMessages(chat.id).last()
        assertTrue(reply.text.isNotEmpty())
        assertEquals(MessageStatus.STOPPED, reply.status)
        assertEquals(provider.chats.value, store.snapshot)
        provider.sendMessage(chat.id, "retry").collect()
        assertEquals(4, provider.getMessages(chat.id).size)
    }

    @Test fun `delete cancellation after disk write cannot resurrect deleted chat`() = runTest {
        val store = CancelAfterWriteStore(StandardTestDispatcher(testScheduler, "storage"))
        val provider = MockChatProvider(store, 0)
        val chat = provider.createChat()
        val operation = launch(start = CoroutineStart.LAZY) { provider.deleteChat(chat.id) }
        store.cancelAfterWrite = operation
        operation.start()
        operation.join()
        assertTrue(provider.chats.value.isEmpty())
        provider.createChat()
        assertTrue(store.snapshot.none { it.id == chat.id })
    }

    @Test fun `clear cancellation after disk write cannot restore cleared history`() = runTest {
        val store = CancelAfterWriteStore(StandardTestDispatcher(testScheduler, "storage"))
        val provider = MockChatProvider(store, 0)
        val oldIds = listOf(provider.createChat().id, provider.createChat().id)
        val operation = launch(start = CoroutineStart.LAZY) { provider.clear() }
        store.cancelAfterWrite = operation
        operation.start()
        operation.join()
        assertTrue(provider.chats.value.isEmpty())
        provider.createChat()
        assertEquals(1, store.snapshot.size)
        assertTrue(store.snapshot.none { it.id in oldIds })
    }

    /** Models FileChatStore's IO boundary: durable write succeeds, caller is cancelled before dispatch back. */
    private class CancelAfterWriteStore(private val dispatcher: CoroutineDispatcher) : ChatStore {
        var snapshot: List<ChatSession> = emptyList()
            private set
        var cancelAfterWrite: Job? = null
        override suspend fun load(): List<ChatSession> = withContext(dispatcher) { snapshot }
        override suspend fun save(chats: List<ChatSession>) = withContext(dispatcher) {
            snapshot = chats.toList()
            cancelAfterWrite?.let { victim ->
                cancelAfterWrite = null
                victim.cancel()
            }
            Unit
        }
    }
}
