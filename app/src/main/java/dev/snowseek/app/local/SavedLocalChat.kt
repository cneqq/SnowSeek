package dev.snowseek.app.local

internal data class SavedLocalTurn(val question: String, val answer: String)

internal data class SavedLocalChat(
    val id: String,
    val modelId: String,
    val modelName: String,
    val title: String,
    val updatedAt: Long,
    val turns: List<SavedLocalTurn>,
)
