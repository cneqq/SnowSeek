package dev.snowseek.app.data

import android.content.Context
import android.webkit.CookieManager
import dev.snowseek.app.auth.DeepSeekCredentials
import dev.snowseek.app.auth.OfficialPowEngine
import dev.snowseek.core.*
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

/** No global CookieHandler, redirects, custom trust manager, browser impersonation or request logging. */
class DeepSeekHttpTransport(context: Context, private val tokenOverride: String? = null) : DeepSeekTransport {
    private val credentials = DeepSeekCredentials.get(context)
    private val pow = OfficialPowEngine(context.applicationContext)
    @Volatile private var streaming: HttpURLConnection? = null
    override suspend fun request(path: String, body: JsonObject?): JsonElement = requestJson(path, body)

    private suspend fun requestJson(path: String, body: JsonObject?): JsonElement = withContext(Dispatchers.IO) {
        val connection = open(path, body)
        try {
            val code = connection.responseCode
            if (code != 200) throw httpFailure(code)
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val result = StringBuilder()
                val buffer = CharArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (result.length + count > 8_000_000) throw DeepSeekFailure("DeepSeek: response too large")
                    result.append(buffer, 0, count)
                }
                result.toString()
            }
            val root = try { Json.parseToJsonElement(text).jsonObject }
                catch (_: Exception) { throw DeepSeekFailure("DeepSeek: вместо JSON получена страница проверки. Откройте вход. / Verification page received; open sign-in.") }
            val data = root["data"] as? JsonObject
            val biz = (data?.get("biz_code") as? JsonPrimitive)?.intOrNull
            if ((root["code"] as? JsonPrimitive)?.intOrNull != 0 || biz != 0) {
                throw DeepSeekFailure("DeepSeek: ${path.substringBefore('?')} · code=${(root["code"] as? JsonPrimitive)?.intOrNull}, biz_code=$biz. Войдите повторно или обновите историю. / Sign in again or reload history.")
            }
            data["biz_data"] ?: JsonNull
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (known: DeepSeekFailure) { throw known }
        catch (_: Exception) { throw DeepSeekFailure("Не удалось соединиться с DeepSeek. Проверьте сеть. / Could not connect to DeepSeek; check your network.") }
        finally { connection.disconnect() }
    }
    override suspend fun solveOfficialChallenge(challenge: JsonObject): String = pow.solve(challenge)
    override fun completion(body: JsonObject, proof: String): Flow<DeepSeekFrame> = flow {
        currentCoroutineContext().ensureActive()
        val connection = open("/api/v0/chat/completion", body, proof)
        streaming = connection
        try {
            val code = connection.responseCode
            if (code != 200) throw httpFailure(code)
            if (!connection.contentType.orEmpty().startsWith("text/event-stream", ignoreCase = true)) {
                throw DeepSeekFailure("DeepSeek: SSE не получен; откройте вход / SSE not received; open sign-in")
            }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                var event = "message"
                val data = StringBuilder()
                var lineSize = 0
                while (true) {
                    currentCoroutineContext().ensureActive()
                    // Bound individual lines before allocation, including malformed server responses.
                    val line = StringBuilder()
                    var eof = false
                    while (true) {
                        val next = reader.read()
                        if (next < 0) { eof = true; break }
                        if (next == 10) break
                        if (next != 13) line.append(next.toChar())
                        if (line.length > 2_000_000) throw DeepSeekFailure("DeepSeek: SSE frame too large")
                    }
                    if (eof) break // An unterminated final SSE frame is not proof of success.
                    val value = line.toString()
                    if (value.isEmpty()) {
                        if (data.isNotEmpty()) {
                            val frame = DeepSeekFrame(event, data.toString())
                            emit(frame)
                            if (event == "close") return@flow
                        }
                        data.setLength(0); event = "message"; lineSize = 0
                    } else if (value.startsWith("event:")) event = value.substring(6).trim()
                    else if (value.startsWith("data:")) {
                        if (data.isNotEmpty()) data.append('\n')
                        data.append(value.substring(5).removePrefix(" "))
                        lineSize += value.length
                        if (lineSize > 2_000_000) throw DeepSeekFailure("DeepSeek: SSE frame too large")
                    }
                }
            }
        } finally { if (streaming === connection) streaming = null; connection.disconnect() }
    }.flowOn(Dispatchers.IO)
    override fun cancelCompletion() { streaming?.disconnect(); pow.cancel() }
    private fun open(path: String, body: JsonObject?, proof: String? = null): HttpURLConnection {
        require(path.startsWith("/api/v0/") && !path.contains('#') && URI(path).host == null)
        val token = tokenOverride ?: credentials.token() ?: throw DeepSeekFailure("Войдите в DeepSeek в SnowSeek / Sign in to DeepSeek in SnowSeek")
        val connection = URL("https://chat.deepseek.com$path").openConnection() as HttpURLConnection
        if (proof != null) streaming = connection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 20_000
        connection.readTimeout = if (proof == null) 30_000 else 90_000
        connection.useCaches = false
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Accept", if (proof == null) "application/json" else "text/event-stream")
        connection.setRequestProperty("User-Agent", "SnowSeek/0.2 (Android)")
        connection.setRequestProperty("Origin", "https://chat.deepseek.com")
        connection.setRequestProperty("Referer", "https://chat.deepseek.com/")
        connection.setRequestProperty("x-client-platform", "web")
        connection.setRequestProperty("x-client-version", "2.2.0")
        connection.setRequestProperty("x-client-locale", "ru_RU")
        connection.setRequestProperty("x-client-bundle-id", "com.deepseek.chat")
        CookieManager.getInstance().getCookie("https://chat.deepseek.com/")?.takeIf { it.isNotEmpty() }?.let {
            connection.setRequestProperty("Cookie", it)
        }
        proof?.let { connection.setRequestProperty("x-ds-pow-response", it) }
        if (body != null) {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            try { connection.outputStream.use { it.write(bytes) } }
            catch (failure: Exception) { connection.disconnect(); throw failure }
        }
        return connection
    }
    private fun httpFailure(code: Int) = DeepSeekFailure(when (code) {
        401 -> "Сессия DeepSeek истекла. Войдите снова. / DeepSeek session expired; sign in again."
        403, 202 -> "DeepSeek требует проверку в официальном окне входа (HTTP $code). / Complete verification on the official sign-in page."
        429 -> "DeepSeek ограничил частоту запросов. Подождите. / DeepSeek rate limit; please wait."
        else -> "DeepSeek: HTTP $code. Повторная отправка автоматически не выполняется. / No automatic resend."
    })
}
