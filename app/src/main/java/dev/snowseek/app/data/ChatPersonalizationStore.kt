package dev.snowseek.app.data

import android.content.Context
import java.security.MessageDigest
import java.util.Base64

/** Small per-account UI state that does not modify the remote chat history. */
internal class ChatPersonalizationStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("chat_personalization", Context.MODE_PRIVATE)

    @Synchronized
    fun archived(token: String?): Set<String> = preferences
        .getStringSet("archived.${accountKey(token)}", emptySet()).orEmpty().toSet()

    @Synchronized
    fun setArchived(token: String?, chatIds: Set<String>, archived: Boolean) {
        val key = "archived.${accountKey(token)}"
        val next = preferences.getStringSet(key, emptySet()).orEmpty().toMutableSet()
        if (archived) next.addAll(chatIds) else next.removeAll(chatIds)
        preferences.edit().putStringSet(key, next).apply()
    }

    @Synchronized
    fun draft(token: String?, chatId: String): String = preferences
        .getString(draftKey(token, chatId), "").orEmpty()

    @Synchronized
    fun saveDraft(token: String?, chatId: String, value: String) {
        val edit = preferences.edit()
        if (value.isBlank()) edit.remove(draftKey(token, chatId))
        else edit.putString(draftKey(token, chatId), value.take(32_768))
        edit.apply()
    }

    @Synchronized
    fun removeChats(token: String?, chatIds: Set<String>) {
        val key = "archived.${accountKey(token)}"
        val archived = preferences.getStringSet(key, emptySet()).orEmpty().toMutableSet()
        archived.removeAll(chatIds)
        val edit = preferences.edit().putStringSet(key, archived)
        chatIds.forEach { edit.remove(draftKey(token, it)) }
        edit.apply()
    }

    private fun draftKey(token: String?, chatId: String): String =
        "draft.${accountKey(token)}.${Base64.getUrlEncoder().withoutPadding().encodeToString(chatId.toByteArray())}"

    private fun accountKey(token: String?): String {
        if (token.isNullOrEmpty()) return "signed-out"
        return MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
            .take(12).joinToString("") { "%02x".format(it) }
    }
}
