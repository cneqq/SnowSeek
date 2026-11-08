package dev.snowseek.app.local

enum class LocalModelFormat(val extension: String) {
    LITERTLM("litertlm"),
    GGUF("gguf"),
}

data class LocalChatMessage(
    val role: String,
    val content: String,
)
