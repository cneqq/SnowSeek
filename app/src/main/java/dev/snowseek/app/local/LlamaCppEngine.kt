package dev.snowseek.app.local

import java.io.File
import java.io.DataInputStream
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
internal class LlamaCppEngine : LocalInferenceEngine {
    private val handle = AtomicLong(0L)
    private val nativeCallLock = ReentrantLock()

    override suspend fun load(modelFile: File, format: LocalModelFormat, contextTokens: Int) {
        require(format == LocalModelFormat.GGUF) { "llama.cpp поддерживает только файлы GGUF" }
        require(modelFile.isFile && modelFile.length() >= GGUF_HEADER_BYTES) { "Файл GGUF не найден или повреждён" }
        require(modelFile.extension.equals(LocalModelFormat.GGUF.extension, ignoreCase = true)) {
            "Файл GGUF должен иметь расширение .gguf"
        }
        val magic = DataInputStream(modelFile.inputStream().buffered()).use { input -> ByteArray(4).also(input::readFully) }
        require(magic.contentEquals(GGUF_MAGIC)) { "Файл повреждён или не является GGUF" }
        require(contextTokens in MIN_CONTEXT_TOKENS..MAX_CONTEXT_TOKENS) { "Недопустимый размер контекста" }
        val loaded = withContext(Dispatchers.IO) { nativeLoad(modelFile.absolutePath, contextTokens) }
        check(loaded != 0L) { "Не удалось загрузить модель GGUF" }
        check(handle.compareAndSet(0L, loaded)) {
            nativeClose(loaded)
            "Движок GGUF уже загружен"
        }
    }

    override fun stream(messages: List<LocalChatMessage>): Flow<String> {
        require(messages.isNotEmpty()) { "Передайте хотя бы одно сообщение" }
        require(messages.all { it.role in ROLES }) { "Модель получила неизвестный тип сообщения" }
        val activeHandle = checkNotNull(handle.get().takeIf { it != 0L }) { "Движок GGUF ещё не загружен" }

        return callbackFlow {
            val generation = launch(Dispatchers.IO) {
                var acquired = false
                try {
                    nativeCallLock.lockInterruptibly()
                    acquired = true
                    nativeGenerate(
                        activeHandle,
                        messages.map(LocalChatMessage::role).toTypedArray(),
                        messages.map(LocalChatMessage::content).toTypedArray(),
                        TokenSink { piece -> trySend(piece) },
                    )
                } catch (failure: Exception) {
                    close(failure)
                } finally {
                    if (acquired) nativeCallLock.unlock()
                    close()
                }
            }
            awaitClose {
                nativeCancel(activeHandle)
                generation.cancel()
            }
        }
    }

    override fun cancel() {
        handle.get().takeIf { it != 0L }?.let(::nativeCancel)
    }

    override fun close() {
        val activeHandle = handle.getAndSet(0L)
        if (activeHandle == 0L) return
        nativeCancel(activeHandle)
        nativeCallLock.lock()
        try {
            nativeClose(activeHandle)
        } finally {
            nativeCallLock.unlock()
        }
    }

    private external fun nativeLoad(modelPath: String, contextTokens: Int): Long
    private external fun nativeGenerate(handle: Long, roles: Array<String>, contents: Array<String>, sink: TokenSink)
    private external fun nativeCancel(handle: Long)
    private external fun nativeClose(handle: Long)

    private fun interface TokenSink {
        fun onToken(piece: String)
    }

    private companion object {
        const val GGUF_HEADER_BYTES = 24L
        const val MIN_CONTEXT_TOKENS = 512
        const val MAX_CONTEXT_TOKENS = 8192
        val ROLES = setOf("system", "user", "assistant")
        val GGUF_MAGIC = byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte())

        init {
            System.loadLibrary("snowseek_llama")
        }
    }
}
