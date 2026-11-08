package dev.snowseek.app.local

import java.io.File
import kotlinx.coroutines.flow.Flow

interface LocalInferenceEngine : AutoCloseable {
    suspend fun load(modelFile: File, format: LocalModelFormat, contextTokens: Int)

    fun stream(messages: List<LocalChatMessage>): Flow<String>

    fun cancel()

    override fun close()
}
