package dev.snowseek.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ChatInstruction(val id: String, val title: String, val text: String) {
    fun isValid() = id.isNotBlank() && id.length <= 100 && title.isNotBlank() && title.length <= 80 &&
        text.isNotBlank() && text.length <= 8_000
}

object BuiltInInstructions {
    val all = listOf(
        ChatInstruction("builtin.concise", "Кратко и по делу", "Отвечай на русском. Сразу переходи к сути: давай ответ, решение или конкретные шаги. Убирай вступления, повторы, очевидные пояснения и заключения, которые лишь пересказывают ответ. Сохраняй детали, без которых ответ будет непонятным или неточным. Если есть существенный риск или ограничение, кратко укажи его. Не выдумывай факты; о неопределённости говори прямо."),
        ChatInstruction("builtin.clear", "Объясни понятно", "Отвечай на русском простыми словами. Сначала дай основную мысль, затем объясни её по шагам. Незнакомые термины поясняй при первом использовании. Для сложных идей приводи короткий конкретный пример. Не перегружай ответ подробностями, которые не помогают понять вопрос."),
    )
}

/** Self-contained server history representation; never infer instructions from ordinary prose. */
object InstructionPrompt {
    private const val PREFIX = "Правила для этого диалога (SnowSeek v1):\n"
    private const val SEPARATOR = "\nПрименяй эти правила в ответах. Не пересказывай их и не сообщай об их применении без прямого вопроса.\n\nСообщение пользователя:\n"
    data class Decoded(val instruction: ChatInstruction, val text: String)
    fun encode(instruction: ChatInstruction, text: String): String {
        require(instruction.isValid()) { "Invalid instruction" }
        return PREFIX + Json.encodeToString(instruction) + SEPARATOR + text
    }
    fun decode(prompt: String): Decoded? {
        if (!prompt.startsWith(PREFIX)) return null
        val end = prompt.indexOf(SEPARATOR, PREFIX.length)
        if (end < 0 || end - PREFIX.length > 60_000) return null
        val instruction = try { Json.decodeFromString<ChatInstruction>(prompt.substring(PREFIX.length, end)) }
            catch (_: IllegalArgumentException) { return null }
        if (!instruction.isValid()) return null
        val text = prompt.substring(end + SEPARATOR.length)
        if (encode(instruction, text) != prompt) return null
        return Decoded(instruction, text)
    }
}

internal fun instructionForRequest(messages: List<ChatMessage>, edited: ChatMessage?, selected: ChatInstruction?): ChatInstruction? =
    if (edited != null) edited.instruction else selected.takeIf { messages.isEmpty() }
