package dev.snowseek.app.auth

import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class WebDebugTab { Console, Network, WebView }

data class WebDebugEntry(val id: Long, val time: Long, val tab: WebDebugTab, val message: String) {
    fun display(): String = "${timeFormat.format(Instant.ofEpochMilli(time))} $message"

    companion object {
        private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
    }
}

data class WebDebugState(
    val entries: List<WebDebugEntry> = emptyList(),
    val currentUrl: String = "—",
    val progress: Int = 0,
    val environment: String = "",
    val dropped: Int = 0,
)

/** In-memory, bounded and safe to call from shouldInterceptRequest's background thread. */
class WebViewDebugLog(
    private val enabled: Boolean,
    private val maxEntries: Int = 600,
    private val maxCharacters: Int = 256_000,
) {
    private val mutableState = MutableStateFlow(WebDebugState())
    val state = mutableState.asStateFlow()
    private var sequence = 0L

    init { require(maxEntries > 0 && maxCharacters >= 4_096) }

    @Synchronized fun environment(value: String) {
        if (enabled) mutableState.value = mutableState.value.copy(environment = DebugRedactor.text(value))
    }

    @Synchronized fun location(url: String?, progress: Int? = null) {
        if (!enabled) return
        mutableState.value = mutableState.value.copy(
            currentUrl = DebugRedactor.url(url),
            progress = progress?.coerceIn(0, 100) ?: mutableState.value.progress,
        )
    }

    @Synchronized fun add(tab: WebDebugTab, message: String) {
        if (!enabled) return
        val entry = WebDebugEntry(++sequence, System.currentTimeMillis(), tab, DebugRedactor.text(message))
        val old = mutableState.value
        val entries = (old.entries + entry).toMutableList()
        var characters = entries.sumOf { it.message.length }
        var dropped = old.dropped
        while (entries.size > maxEntries || characters > maxCharacters) {
            characters -= entries.removeAt(0).message.length
            dropped++
        }
        mutableState.value = old.copy(entries = entries.toList(), dropped = dropped)
    }

    fun request(method: String, url: String, mainFrame: Boolean, allowed: Boolean) {
        add(WebDebugTab.Network, "REQUEST $method ${DebugRedactor.url(url)} · ${if (mainFrame) "main" else "resource/frame"} · " +
            if (allowed) "HTTP ? (not exposed)" else "BLOCKED_BY_APP (no network status)")
    }

    fun httpError(method: String, url: String, status: Int, reason: String, mainFrame: Boolean) {
        add(WebDebugTab.Network, "HTTP_ERROR $status $reason · $method ${DebugRedactor.url(url)} · ${if (mainFrame) "main" else "resource/frame"}")
    }

    fun console(level: String, message: String, source: String?, line: Int) {
        detailed(WebDebugTab.Console, "$level $message\n  at ${DebugRedactor.url(source)} · line $line")
    }

    @Synchronized fun detailed(tab: WebDebugTab, message: String) {
        if (!enabled) return
        val safe = DebugRedactor.text(message, limit = 65_536)
        val chunks = safe.chunked(3_500)
        val group = sequence + 1
        chunks.forEachIndexed { index, chunk ->
            add(tab, if (chunks.size == 1) chunk else "DETAIL $group [${index + 1}/${chunks.size}]\n$chunk")
        }
    }

    @Synchronized fun clear() {
        // Keep live location/environment; clearing logs does not reload or change the website.
        mutableState.value = mutableState.value.copy(entries = emptyList(), dropped = 0)
    }

    fun export(): String {
        val snapshot = state.value
        return buildString {
            appendLine("SnowSeek WebView diagnostics")
            appendLine(snapshot.environment)
            appendLine("URL: ${snapshot.currentUrl}")
            appendLine("Progress: ${snapshot.progress}%")
            appendLine("Dropped oldest entries: ${snapshot.dropped}")
            appendLine("Network: request callbacks and reported errors only; ? = HTTP status not exposed.")
            appendLine("No request/response headers or bodies. URL query values/fragments and recognized secrets are redacted.")
            appendLine("Console contains website text; review before sharing. Logs are held in memory only.")
            snapshot.entries.forEach { appendLine("[${it.tab}] ${it.display()}") }
        }
    }
}

/** Redact before storage, so display and clipboard use exactly the same sanitized data. */
object DebugRedactor {
    private const val HIDDEN = "[redacted]"
    private val urls = Regex("(?:https?://|blob:https?://)[^\\s<>\"'`]+", RegexOption.IGNORE_CASE)
    private val dataUrls = Regex("data:[^\\s<>\"'`]+", RegexOption.IGNORE_CASE)
    private val jwt = Regex("\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)?")
    private val bearer = Regex("(?i)\\bBearer\\s+[^\\s\"',;]+")
    private val cookie = Regex("(?im)\\b(?:set-cookie|cookie|authorization|proxy-authorization)\\s*[:=]\\s*[^\\r\\n]+")
    private val secret = Regex("""(?i)(?<![A-Za-z0-9_])(["']?(?:password|passwd|access[_-]?token|refresh[_-]?token|userToken|token|secret|nonce|session[_-]?id|email|phone|prompt|authorization|cookie|set-cookie|otp|credential)["']?\s*[:=]\s*)("[^"\r\n]*"|'[^'\r\n]*'|[^\s,;}]+)""")
    private val email = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val opaque = Regex("[A-Za-z0-9_+/-]{48,}={0,2}")

    fun url(raw: String?): String {
        if (raw.isNullOrBlank()) return "—"
        val limited = raw.take(4_096)
        if (limited == "about:blank") return limited
        if (limited.startsWith("data:", true)) return "data:$HIDDEN"
        if (limited.startsWith("blob:", true)) return "blob:" + url(limited.substring(5))
        return try {
            val uri = URI(limited.replace(HIDDEN, "%5Bredacted%5D"))
            if (uri.scheme !in listOf("https", "http") || uri.host == null) return "[non-HTTP source]"
            val query = uri.rawQuery?.split('&')?.take(30)?.joinToString("&") {
                val key = it.substringBefore('=').take(80)
                // Even parameter names can carry data; keep only ordinary names.
                "${key.takeIf { name -> name.matches(Regex("[A-Za-z_][A-Za-z0-9_.-]{0,79}")) } ?: "param"}=$HIDDEN"
            }
            val port = if (uri.port >= 0) ":${uri.port}" else ""
            val path = opaque.replace(uri.rawPath.orEmpty(), HIDDEN)
            "${uri.scheme}://${uri.host}$port$path" +
                (query?.let { "?$it" } ?: "") + (if (uri.rawFragment != null) "#$HIDDEN" else "")
        } catch (_: Exception) { "[invalid/redacted URL]" }
    }

    fun text(raw: String, limit: Int = 4_000): String {
        var value = raw.take(limit * 3)
        value = urls.replace(value) { url(it.value) }
        value = dataUrls.replace(value, "data:$HIDDEN")
        value = bearer.replace(value, "Bearer $HIDDEN")
        value = jwt.replace(value, HIDDEN)
        value = cookie.replace(value) { it.value.substringBefore(':').substringBefore('=') + ": $HIDDEN" }
        value = secret.replace(value) { it.groupValues[1] + HIDDEN }
        value = email.replace(value, HIDDEN)
        value = opaque.replace(value, HIDDEN)
        // A console line cannot forge new log entries via control characters.
        value = value.map { if (it < ' ' && it !in listOf('\n', '\t')) ' ' else it }.joinToString("")
        return value.take(limit) + if (value.length > limit || raw.length > limit * 3) " [truncated at $limit characters]" else ""
    }
}
