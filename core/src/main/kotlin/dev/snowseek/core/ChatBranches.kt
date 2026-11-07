package dev.snowseek.core

data class MessageVersions(val index: Int, val ids: List<String>)

/** Tracks server message IDs and remembers the last visited continuation of each version. */
internal class ChatBranches {
    private data class Node(val message: ChatMessage, val parent: String?)
    private val nodes = linkedMapOf<String, Node>()
    private val preferred = mutableMapOf<String?, String>()
    var selectedTail: String? = null

    fun record(message: ChatMessage, parent: String?) {
        nodes[message.id] = Node(message, parent)
    }

    fun remember(messages: List<ChatMessage>) {
        var parent: String? = null
        for (message in messages) {
            if (message.id.toLongOrNull() == null) break
            record(message, parent)
            preferred[parent] = message.id
            parent = message.id
        }
    }

    fun path(tail: String?): List<ChatMessage> {
        val result = mutableListOf<ChatMessage>()
        val seen = mutableSetOf<String>()
        var id = tail
        while (id != null && seen.add(id)) {
            val node = nodes[id] ?: break
            result.add(node.message)
            id = node.parent
        }
        return result.asReversed()
    }

    fun versions(messages: List<ChatMessage>): Map<String, MessageVersions> {
        val siblings = nodes.values
            .groupBy { it.parent to it.message.role }.mapValues { (_, values) ->
                values.map { it.message }.sortedWith(compareBy({ it.createdAt }, { it.id.toLongOrNull() }))
                    .map { it.id }
            }
        return messages.mapNotNull { message ->
            val node = nodes[message.id] ?: return@mapNotNull null
            val ids = siblings[node.parent to message.role].orEmpty()
            if (ids.size > 1) message.id to MessageVersions(ids.indexOf(message.id), ids) else null
        }.toMap()
    }

    fun switchTo(id: String): List<ChatMessage> {
        val prefix = path(id)
        if (prefix.isEmpty()) return emptyList()
        val result = prefix.toMutableList()
        val seen = prefix.mapTo(mutableSetOf()) { it.id }
        var tail = id
        while (true) {
            val next = preferred[tail]?.let(nodes::get)?.takeIf { it.parent == tail }
                ?: nodes.values.lastOrNull { it.parent == tail } ?: break
            if (!seen.add(next.message.id)) break
            result.add(next.message)
            tail = next.message.id
        }
        selectedTail = tail
        remember(result)
        return result
    }
}
