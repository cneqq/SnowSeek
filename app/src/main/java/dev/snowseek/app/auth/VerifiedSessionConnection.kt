package dev.snowseek.app.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Serializes session verification on Main; credentials are published without a suspension. */
internal class VerifiedSessionConnection {
    private var checking = false
    private var attempted: String? = null
    private var completed = false

    suspend fun connect(token: String?, current: () -> Boolean, verify: suspend (String) -> Unit, save: (String) -> Unit): Boolean {
        if (checking || completed || token == null || token.isBlank() || token == "null" || token.length >= 16_384 || token == attempted || !current()) return false
        checking = true
        attempted = token
        try {
            verify(token)
            currentCoroutineContext().ensureActive()
            if (!current()) { attempted = null; return false }
            save(token)
            completed = true
            return true
        } catch (cancelled: CancellationException) {
            attempted = null
            throw cancelled
        } finally { checking = false }
    }

    fun retry() { if (!checking) attempted = null }
}
