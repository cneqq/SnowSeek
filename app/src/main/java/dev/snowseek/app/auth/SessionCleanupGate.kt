package dev.snowseek.app.auth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A logout belongs to the process, so activity recreation cannot interrupt it. */
internal class SessionCleanupGate(private val scope: CoroutineScope) {
    private val mutex = Mutex()
    private var pending: Deferred<Unit>? = null
    private var revision = 0L

    suspend fun clear(operation: suspend () -> Unit) = withContext(NonCancellable) {
        val work = mutex.withLock {
            pending?.takeIf { it.isActive } ?: scope.async(start = CoroutineStart.LAZY) {
                withContext(NonCancellable) { operation() }
            }.also {
                revision++
                pending = it
                it.start()
            }
        }
        work.await()
    }

    /** A failed cleanup blocks navigation until an explicit successful retry. */
    suspend fun awaitReady(): Long {
        while (true) {
            val observed = mutex.withLock { pending to revision }
            observed.first?.await()
            val unchanged = mutex.withLock { pending === observed.first && revision == observed.second }
            if (unchanged) return observed.second
        }
    }
}
