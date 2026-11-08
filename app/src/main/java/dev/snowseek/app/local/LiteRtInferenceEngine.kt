package dev.snowseek.app.local

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

internal class LiteRtInferenceEngine(
    private val cacheDirectory: File,
) : LocalInferenceEngine {
    private var engine: Engine? = null
    private var conversation: Conversation? = null

    override suspend fun load(modelFile: File, format: LocalModelFormat, contextTokens: Int) {
        require(format == LocalModelFormat.LITERTLM) { "LiteRT-LM поддерживает только файлы .litertlm" }
        require(modelFile.isFile && modelFile.length() > 0) { "Файл LiteRT-LM не найден или пуст" }
        close()
        withContext(Dispatchers.IO) {
            val loadedEngine = Engine(
                EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = Backend.CPU(),
                    cacheDir = cacheDirectory.absolutePath,
                ),
            )
            try {
                loadedEngine.initialize()
                val loadedConversation = loadedEngine.createConversation(conversationConfig())
                engine = loadedEngine
                conversation = loadedConversation
            } catch (failure: Exception) {
                runCatching { loadedEngine.close() }
                throw failure
            }
        }
    }

    override fun stream(messages: List<LocalChatMessage>): Flow<String> {
        val question = messages.lastOrNull { it.role == "user" }?.content?.trim().orEmpty()
        check(question.isNotEmpty()) { "Добавьте вопрос перед отправкой" }
        val activeConversation = conversation ?: checkNotNull(engine) { "Движок LiteRT-LM ещё не загружен" }
            .createConversation(conversationConfig()).also { conversation = it }
        return activeConversation.sendMessageAsync(question).map { it.toString() }
    }

    override fun cancel() {
        runCatching { conversation?.close() }
        conversation = null
    }

    override fun close() {
        runCatching { conversation?.close() }
        runCatching { engine?.close() }
        conversation = null
        engine = null
    }

    private fun conversationConfig() = ConversationConfig(
        systemInstruction = Contents.of("You are a helpful, concise assistant. Reply in the user's language."),
        samplerConfig = SamplerConfig(temperature = 0.7, topK = 40, topP = 0.9),
    )
}
