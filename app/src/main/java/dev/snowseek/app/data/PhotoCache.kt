package dev.snowseek.app.data

import dev.snowseek.app.settings.PhotoCacheLimit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

internal class PhotoCache(private val directory: File, private val accountRevision: () -> Long) {
    data class Ticket(val account: Long, val generation: Long)

    private val lock = Any()
    @Volatile private var generation = 0L
    private var limit = PhotoCacheLimit.MB_500.bytes
    private val mutableSize = MutableStateFlow(0L)
    val size = mutableSize.asStateFlow()

    fun ticket() = Ticket(accountRevision(), generation)

    private fun isCurrent(ticket: Ticket) = ticket.account == accountRevision() && ticket.generation == generation

    fun <T> read(ticket: Ticket, id: String, variant: String, decode: (File) -> T?): T? = synchronized(lock) {
        if (!isCurrent(ticket)) return@synchronized null
        val file = entry(id, variant)
        if (!file.isFile) return@synchronized null
        val result = decode(file)
        if (result != null) file.setLastModified(System.currentTimeMillis())
        else {
            file.delete()
            updateSize()
        }
        result
    }

    fun write(ticket: Ticket, id: String, variant: String, size: Long, source: () -> InputStream) {
        synchronized(lock) {
            if (!isCurrent(ticket) || size <= 0 || size > limit) return
            val file = entry(id, variant)
            if (file.isFile) {
                file.setLastModified(System.currentTimeMillis())
                return
            }
            if (!directory.isDirectory && !directory.mkdirs()) return
            try {
                val temporary = File.createTempFile("photo-", ".tmp", directory)
                try {
                    source().use { input -> temporary.outputStream().use { input.copyTo(it) } }
                    if (temporary.length() == size && isCurrent(ticket) && temporary.renameTo(file)) trim()
                } finally {
                    temporary.delete()
                }
            } catch (_: IOException) {
                // A full or unavailable cache must not interrupt loading or sending the photo.
            }
        }
    }

    suspend fun setLimit(value: PhotoCacheLimit) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            limit = value.bytes
            directory.listFiles { file -> file.name.endsWith(".tmp") }?.forEach { it.delete() }
            trim()
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        synchronized(lock) {
            generation++
            entries().forEach { it.delete() }
            updateSize()
        }
    }

    private fun entry(id: String, variant: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(id.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(directory, "$hash-$variant.photo")
    }

    private fun entries() = directory.listFiles { file -> file.isFile && file.name.endsWith(".photo") }.orEmpty()

    private fun updateSize() {
        mutableSize.value = entries().sumOf { it.length() }
    }

    private fun trim() {
        val files = entries()
        var total = files.sumOf { it.length() }
        if (total > limit) {
            for (file in files.sortedBy { it.lastModified() }) {
                val size = file.length()
                if (file.delete()) total -= size
                if (total <= limit) break
            }
        }
        mutableSize.value = total
    }
}
