package dev.snowseek.app.data

import android.content.Context
import android.webkit.CookieManager
import dev.snowseek.app.auth.DeepSeekCredentials
import dev.snowseek.app.auth.OfficialPowEngine
import dev.snowseek.core.*
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

/** No global CookieHandler, redirects, custom trust manager, browser impersonation or request logging. */
class DeepSeekHttpTransport(context: Context, private val tokenOverride: String? = null, private val includeWebCookies: Boolean = true) : DeepSeekTransport {
    private val credentials = DeepSeekCredentials.get(context)
    private val pow = OfficialPowEngine(context.applicationContext)
    @Volatile private var streaming: HttpURLConnection? = null
    override suspend fun request(path: String, body: JsonObject?): JsonElement = requestJson(path, body)

    /** One-time first-party Google result; never send a previous account's credentials. */
    suspend fun exchangeGoogleNonce(nonce: String): JsonElement {
        require(nonce.matches(Regex("[A-Za-z0-9_-]{64}")))
        return requestJson("/api/v0/users/oauth/get_token", buildJsonObject { put("nonce", nonce); put("provider", "GOOGLE") }, anonymous = true)
    }

    private suspend fun requestJson(path: String, body: JsonObject?, anonymous: Boolean = false): JsonElement = withContext(Dispatchers.IO) {
        try {
            val connection = open(path, body, anonymous = anonymous)
            try { parseJsonResponse(connection, path) }
            finally { connection.disconnect() }
        } catch (failure: IOException) {
            currentCoroutineContext().ensureActive()
            throw DeepSeekConnectionFailure()
        }
    }
    private suspend fun parseJsonResponse(connection: HttpURLConnection, path: String): JsonElement {
        val code = connection.responseCode
        if (code != 200) throw httpFailure(code)
        val mediaType = connection.contentType.orEmpty().substringBefore(';').trim()
        if (!mediaType.equals("application/json", ignoreCase = true)) {
            throw DeepSeekFailure("DeepSeek: вместо JSON получена страница проверки. Откройте вход. / Verification page received; open sign-in.")
        }
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
        return data["biz_data"] ?: JsonNull
    }

    override suspend fun uploadFile(file: UploadFile, proof: String, deepThink: Boolean): JsonElement = withContext(Dispatchers.IO) {
        require(file.size >= 0) { "File size must be known" }
        currentCoroutineContext().ensureActive()
        val path = "/api/v0/file/upload_file"
        // Keep caller-controlled names out of multipart syntax and HTTP headers.
        val filename = file.name.substringAfterLast('/').substringAfterLast('\\')
            .map { if (it.isISOControl() || it == '"' || it == '\\') '_' else it }.joinToString("")
            .take(255).ifBlank { "attachment" }
        val contentType = file.contentType.takeIf {
            it.matches(Regex("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+"))
        } ?: "application/octet-stream"
        val boundary = "SnowSeek" + java.util.UUID.randomUUID().toString().replace("-", "")
        val prefix = ("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$filename\"\r\n" +
            "Content-Type: $contentType\r\n\r\n").toByteArray(Charsets.UTF_8)
        val suffix = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        require(file.size <= Long.MAX_VALUE - prefix.size - suffix.size) { "File size too large" }
        val connection = open(path, null, proof)
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.setRequestProperty("x-thinking-enabled", deepThink.toString())
            connection.setRequestProperty("x-model-type", "default")
            connection.setRequestProperty("x-file-size", file.size.toString())
            connection.setFixedLengthStreamingMode(file.size + prefix.size + suffix.size)
            file.openStream().use { input ->
                connection.outputStream.use { output ->
                    output.write(prefix)
                    val buffer = ByteArray(64 * 1024)
                    var remaining = file.size
                    while (remaining > 0) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (count < 0) throw DeepSeekFailure("Размер файла изменился. Выберите его заново. / File size changed; select it again.")
                        if (count == 0) throw DeepSeekFailure("Не удалось прочитать файл. / Could not read the file.")
                        output.write(buffer, 0, count)
                        remaining -= count
                    }
                    currentCoroutineContext().ensureActive()
                    if (input.read() != -1) throw DeepSeekFailure("Размер файла изменился. Выберите его заново. / File size changed; select it again.")
                    output.write(suffix)
                }
            }
            parseJsonResponse(connection, path)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (known: DeepSeekFailure) { throw known }
        catch (_: Exception) { throw DeepSeekFailure("Не удалось загрузить файл. Проверьте сеть. / Could not upload the file; check your network.") }
        finally { connection.disconnect() }
    }

    override suspend fun solveOfficialChallenge(challenge: JsonObject): String = pow.solve(challenge)
    override fun completion(body: JsonObject, proof: String): Flow<DeepSeekFrame> = stream("/api/v0/chat/completion", body, proof)
    override fun editMessage(body: JsonObject, proof: String): Flow<DeepSeekFrame> = stream("/api/v0/chat/edit_message", body, proof)
    override fun continueMessage(body: JsonObject): Flow<DeepSeekFrame> = stream("/api/v0/chat/continue", body)
    override fun regenerateMessage(body: JsonObject, proof: String): Flow<DeepSeekFrame> = stream("/api/v0/chat/regenerate", body, proof)
    private fun stream(path: String, body: JsonObject, proof: String? = null): Flow<DeepSeekFrame> = flow {
        currentCoroutineContext().ensureActive()
        val connection = open(path, body, proof, streamResponse = true)
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
    private fun open(path: String, body: JsonObject?, proof: String? = null, anonymous: Boolean = false, streamResponse: Boolean = false): HttpURLConnection {
        require(path.startsWith("/api/v0/") && !path.contains('#') && URI(path).host == null)
        require(!anonymous || (path == "/api/v0/users/oauth/get_token" && proof == null))
        val token = if (anonymous) null else tokenOverride ?: credentials.token() ?: throw DeepSeekFailure("Войдите в DeepSeek в SnowSeek / Sign in to DeepSeek in SnowSeek")
        val connection = URL("https://chat.deepseek.com$path").openConnection() as HttpURLConnection
        if (streamResponse) streaming = connection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 20_000
        connection.readTimeout = if (streamResponse) 90_000 else 30_000
        connection.useCaches = false
        token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
        connection.setRequestProperty("Accept", if (streamResponse) "text/event-stream" else "application/json")
        connection.setRequestProperty("User-Agent", "SnowSeek/0.2 (Android)")
        connection.setRequestProperty("Origin", "https://chat.deepseek.com")
        connection.setRequestProperty("Referer", "https://chat.deepseek.com/")
        connection.setRequestProperty("x-client-platform", "web")
        connection.setRequestProperty("x-client-version", "2.2.0")
        connection.setRequestProperty("x-client-locale", "ru_RU")
        connection.setRequestProperty("x-client-bundle-id", "com.deepseek.chat")
        if (!anonymous && includeWebCookies && (tokenOverride != null || credentials.mayAttachWebCookies(requireNotNull(token)))) {
            CookieManager.getInstance().getCookie("https://chat.deepseek.com/")?.takeIf { it.isNotEmpty() }?.let {
                connection.setRequestProperty("Cookie", it)
            }
        }
        proof?.let { connection.setRequestProperty("x-ds-pow-response", it) }
        if (body != null) {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            try { connection.outputStream.use { it.write(bytes) } }
            catch (failure: Exception) { if (streaming === connection) streaming = null; connection.disconnect(); throw failure }
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
