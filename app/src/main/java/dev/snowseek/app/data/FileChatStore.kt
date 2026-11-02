package dev.snowseek.app.data

import android.content.Context
import android.util.AtomicFile
import dev.snowseek.core.ChatSession
import dev.snowseek.core.ChatStore
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Private, atomic local history. Existing unreadable history is never replaced. */
class FileChatStore(context: Context) : ChatStore {
    private val file = AtomicFile(File(context.applicationContext.filesDir, "demo_chats.json"))
    private val json = Json { encodeDefaults = true }

    override suspend fun load(): List<ChatSession> = withContext(Dispatchers.IO) {
        lock.withLock { readExisting() }
    }

    override suspend fun save(chats: List<ChatSession>) = withContext(Dispatchers.IO) {
        lock.withLock {
            // Revalidate before every write: even a new store instance must preserve corruption.
            readExisting()
            val bytes = json.encodeToString(chats).toByteArray(Charsets.UTF_8)
            val stream = file.startWrite()
            try {
                stream.write(bytes)
                file.finishWrite(stream)
            } catch (error: Exception) {
                file.failWrite(stream)
                throw IOException("Could not save local chat history.", error)
            }
        }
    }

    private fun readExisting(): List<ChatSession> {
        val bytes = try {
            file.openRead().use { it.readBytes() }
        } catch (error: FileNotFoundException) {
            if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) {
                return emptyList()
            }
            throw IOException("Local history could not be read; the original file was preserved.", error)
        }
        return try {
            json.decodeFromString<List<ChatSession>>(bytes.toString(Charsets.UTF_8))
        } catch (error: Exception) {
            throw IOException("Local history is damaged or incompatible; the original file was preserved.", error)
        }
    }

    private companion object {
        // AtomicFile itself does not provide mutual exclusion, including between store instances.
        val lock = Mutex()
    }
}
