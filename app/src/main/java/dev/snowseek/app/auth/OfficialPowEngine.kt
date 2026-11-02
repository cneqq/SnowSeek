package dev.snowseek.app.auth

import android.annotation.SuppressLint
import android.content.Context
import android.net.http.SslError
import android.webkit.*
import androidx.core.net.toUri
import dev.snowseek.core.DeepSeekFailure
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.coroutines.resume

/** Executes the official site's own worker for a fresh challenge; never skips or replaces PoW. */
class OfficialPowEngine(private val context: Context) {
    @Volatile private var running: Job? = null
    fun cancel() { running?.cancel() }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun solve(challenge: JsonObject): String = withContext(Dispatchers.Main.immediate) {
        val readyRevision = WebSession.awaitReady()
        running = currentCoroutineContext().job
        var view: WebView? = null
        try {
            withTimeoutOrNull(125_000) {
                val loaded = CompletableDeferred<Unit>()
                WebView.setWebContentsDebuggingEnabled(false)
                val browser = WebView(context).also { view = it }
                WebSession.attach(browser)
                browser.settings.apply {
                    javaScriptEnabled = true; domStorageEnabled = true
                    allowFileAccess = false; allowContentAccess = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    safeBrowsingEnabled = true
                }
                CookieManager.getInstance().setAcceptThirdPartyCookies(browser, false)
                browser.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean =
                        request.isForMainFrame && (request.url.scheme != "https" || request.url.host != "chat.deepseek.com")
                    override fun onPageFinished(v: WebView, url: String?) {
                        if (url.orEmpty().toUri().host == "chat.deepseek.com") loaded.complete(Unit)
                    }
                    override fun onReceivedSslError(v: WebView, handler: SslErrorHandler, error: SslError) {
                        handler.cancel(); loaded.completeExceptionally(DeepSeekFailure("DeepSeek: TLS verification failed"))
                    }
                    override fun onReceivedError(v: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame) loaded.completeExceptionally(DeepSeekFailure("Не удалось загрузить официальный PoW-клиент. Откройте вход. / Could not load official PoW client; open sign-in."))
                    }
                }
                browser.loadUrl(OfficialWebActivity.OFFICIAL_URL)
                loaded.await()
                if (WebSession.awaitReady() != readyRevision) throw DeepSeekFailure("Сессия изменена. Войдите снова. / Session changed; sign in again.")
                browser.evaluateJavascript(workerScript(challenge), null)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val raw = evaluate(browser, "JSON.stringify(window.__snowseekPowResult || null)")
                    val wrapped = try { Json.parseToJsonElement(raw) } catch (_: Exception) { JsonNull }
                    val decoded = if (wrapped is JsonPrimitive && wrapped.isString) {
                        try { Json.parseToJsonElement(wrapped.content) } catch (_: Exception) { JsonNull }
                    } else JsonNull
                    if (decoded is JsonObject) {
                        (decoded["proof"] as? JsonPrimitive)?.contentOrNull?.let { proof ->
                            if (proof.isNotBlank() && proof.length < 32_768) return@withTimeoutOrNull proof
                        }
                        throw DeepSeekFailure("Штатная проверка PoW DeepSeek не выполнена. Откройте вход и повторите. / Official PoW failed; open sign-in and retry.")
                    }
                    delay(150)
                }
                @Suppress("UNREACHABLE_CODE") null
            } ?: throw DeepSeekFailure("Истекло время штатной проверки PoW DeepSeek. / Official DeepSeek PoW timed out.")
        } finally {
            running = null
            view?.let { browser -> WebSession.detach(browser); browser.stopLoading(); browser.destroy() }
        }
    }
    private suspend fun evaluate(view: WebView, source: String): String = suspendCancellableCoroutine { continuation ->
        view.evaluateJavascript(source) { value -> if (continuation.isActive) continuation.resume(value) }
    }
    private fun workerScript(challenge: JsonObject): String = """
        (() => {
          window.__snowseekPowResult = null;
          const challenge = $challenge;
          (async () => {
            let worker, blobUrl;
            try {
              const response = await fetch('https://fe-static.deepseek.com/chat/static/76608.8f2a9fa413.js', {credentials:'omit'});
              if (!response.ok) throw new Error('worker-unavailable');
              blobUrl = URL.createObjectURL(new Blob([await response.text()], {type:'application/javascript'}));
              const answer = await new Promise((resolve, reject) => {
                worker = new Worker(blobUrl);
                worker.onerror = () => reject(new Error('worker-error'));
                worker.onmessage = event => {
                  if (event.data && event.data.type === 'pow-answer' && event.data.answer) resolve(event.data.answer);
                  else reject(new Error('worker-answer-invalid'));
                };
                worker.postMessage({type:'pow-challenge',challenge:{algorithm:challenge.algorithm,
                  challenge:challenge.challenge,salt:challenge.salt,difficulty:challenge.difficulty,
                  signature:challenge.signature,expireAt:challenge.expire_at}});
              });
              if (answer.answer === undefined) throw new Error('no-answer');
              const bytes = new TextEncoder().encode(JSON.stringify({algorithm:challenge.algorithm,
                challenge:challenge.challenge,salt:challenge.salt,answer:answer.answer,
                signature:challenge.signature,target_path:challenge.target_path}));
              let binary = ''; for (const byte of bytes) binary += String.fromCharCode(byte);
              window.__snowseekPowResult = {proof:btoa(binary)};
            } catch (_) { window.__snowseekPowResult = {error:'official-worker-failed'}; }
            finally { if (worker) worker.terminate(); if (blobUrl) URL.revokeObjectURL(blobUrl); }
          })();
        })();
    """.trimIndent()
}
