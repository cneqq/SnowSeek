package dev.snowseek.app.ui

import dev.snowseek.core.ChatMessage
import dev.snowseek.core.MessageRole

/** Resolve a local pending bubble only after its actual server identity has been fetched. */
internal fun resolveEditableMessage(before: List<ChatMessage>, refreshed: List<ChatMessage>, id: String): ChatMessage? {
    val index = before.indexOfFirst { it.id == id && it.role == MessageRole.USER }
    if (index < 0) return null
    if (id.toLongOrNull() != null) return refreshed.firstOrNull {
        it.id == id && it.role == MessageRole.USER && it.editable
    }
    val local = before[index]
    val parent = before.take(index).lastOrNull { it.id.toLongOrNull() != null }
    val position = if (parent == null) 0 else {
        val parentIndex = refreshed.indexOfFirst { it.id == parent.id }
        if (parentIndex < 0) return null
        parentIndex + 1
    }
    return refreshed.getOrNull(position)?.takeIf {
        it.role == MessageRole.USER && it.editable && it.id.toLongOrNull() != null && it.text == local.text &&
            it.attachments.map { file -> file.id } == local.attachments.map { file -> file.id }
    }
}
