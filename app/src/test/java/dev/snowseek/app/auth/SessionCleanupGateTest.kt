package dev.snowseek.app.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SessionCleanupGateTest {
    @Test fun cancellationDuringLogoutCannotReleaseNewWebViewBeforeCleanupFinishes() = runBlocking {
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val gate = SessionCleanupGate(processScope)
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            var cleared = false
            val oldActivity = launch(start = CoroutineStart.UNDISPATCHED) {
                gate.clear { started.complete(Unit); finish.await(); cleared = true }
            }
            started.await()
            oldActivity.cancel()
            val newActivity = async(start = CoroutineStart.UNDISPATCHED) { gate.awaitReady() }
            assertFalse("A recreated activity must wait for logout", newActivity.isCompleted)
            finish.complete(Unit)
            oldActivity.join()
            assertEquals(1L, newActivity.await())
            assertTrue("Cookie deletion and flush must finish despite activity cancellation", cleared)
        } finally { processScope.cancel() }
    }

    @Test fun duplicateLogoutSharesOperationAndRevision() = runBlocking {
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val gate = SessionCleanupGate(processScope)
            val finish = CompletableDeferred<Unit>()
            var calls = 0
            val first = launch(start = CoroutineStart.UNDISPATCHED) { gate.clear { calls++; finish.await() } }
            val second = launch(start = CoroutineStart.UNDISPATCHED) { gate.clear { calls++ } }
            assertEquals(1, calls)
            assertFalse(second.isCompleted)
            finish.complete(Unit)
            first.join(); second.join()
            assertEquals(1L, gate.awaitReady())
        } finally { processScope.cancel() }
    }

    @Test fun failedCleanupBlocksPageLoadUntilSuccessfulRetry() = runBlocking {
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val gate = SessionCleanupGate(processScope)
            val failure = IllegalStateException("Test cleanup failed")
            try { gate.clear { throw failure }; fail("Cleanup must fail visibly") }
            catch (actual: IllegalStateException) { assertCleanupFailure(failure, actual) }
            try { gate.awaitReady(); fail("A page must not load after failed cleanup") }
            catch (actual: IllegalStateException) { assertCleanupFailure(failure, actual) }
            gate.clear { }
            assertEquals(2L, gate.awaitReady())
        } finally { processScope.cancel() }
    }

    private fun assertCleanupFailure(expected: Exception, actual: Exception) {
        // Coroutine stack-trace recovery may copy an exception across await().
        assertEquals(expected.javaClass, actual.javaClass)
        assertEquals(expected.message, actual.message)
    }
}
