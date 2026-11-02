package dev.snowseek.core

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DeepSeekWebProviderTest {
    @Test fun `integration is explicitly disabled and exposes no capabilities`() = runTest {
        val provider = DeepSeekWebProvider()
        assertEquals(ProviderCapabilities(false, false, false, false), provider.capabilities)
        assertTrue(provider.chats.value.isEmpty())
        val operations: List<suspend () -> Unit> = listOf(
            { provider.initialize() }, { provider.getCurrentUser() }, { provider.getChats() },
            { provider.createChat() }, { provider.deleteChat("id") }, { provider.getMessages("id") },
            { provider.sendMessage("id", "hello").collect() }, { provider.stopGeneration("id") }, { provider.clear() },
        )
        operations.forEach { operation ->
            try {
                operation()
                fail("Disabled integration must fail")
            } catch (failure: UnsupportedOperationException) {
                assertTrue(failure.message.orEmpty().contains("WebView"))
            }
        }
    }
}
